package br.com.yacamin.amitiel.application.service.reconciliation;

import br.com.yacamin.amitiel.adapter.out.rest.polymarket.PolymarketGammaClient;
import br.com.yacamin.amitiel.adapter.out.rest.polymarket.PolymarketGammaClient.GammaMarketResponse;
import br.com.yacamin.amitiel.adapter.out.websocket.polymarket.PolymarketMarketClobWsAdapter;
import br.com.yacamin.amitiel.domain.LiveMarketCard;
import br.com.yacamin.amitiel.domain.LiveMarketState;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orquestra a descoberta de markets do grupo {@code btc-updown-5m-} via Gamma API,
 * mantem um {@code Map<String, LiveMarketCard>} em memoria, e inscreve o WS market
 * channel nos tokenIds descobertos.
 *
 * <p><b>Subfase 1b:</b> esta classe apenas descobre e popula os cards com os campos
 * de discovery. A subfase 1c adiciona um {@code @EventListener} pro
 * {@code PolyMarketResolvedEvent} que completa os campos de reconciliation.
 *
 * <p><b>Nao esta no escopo da 1b:</b> cleanup de cards antigos (markets ja resolvidos
 * ha mais de N minutos), reconnect logic, reconciliation de divergencia, UI.
 */
@Service
@Slf4j
public class LiveMarketStateService {

    /** Prefixo do slug do grupo de markets BTC UP/DOWN 5min. */
    private static final String MARKET_GROUP = "btc-updown-5m-";

    /** Duracao de cada bloco em segundos (5 minutos). */
    private static final long BLOCK_DURATION_SECONDS = 300;

    /**
     * Quantos segundos de markets futuros manter na grade ao fazer discovery.
     * 4200s = 70min = 14 blocos — espelha o que o Gabriel faz no
     * {@code PolymarketMarketClobWsAdapter.run()}.
     */
    private static final long LOOKAHEAD_SECONDS = 4200;

    /**
     * Quanto tempo manter cards em estado {@code RESOLVED} no mapa antes de
     * limpar. Rolling window de 24h — o usuario ve um dia inteiro de historia
     * na tela. A ~12 cards por hora × 24h = ~288 cards no teto, ~200KB de
     * heap total. Zero pressao de memoria.
     *
     * <p>Cleanup baseado em {@code endUnixTime} (quando o bloco terminou),
     * nao em quando o card foi reconciliado — o "relogio" de historia e o
     * market, nao o processamento.
     */
    private static final long CARD_RETENTION_SECONDS = 24 * 3600;

    private final PolymarketGammaClient gammaClient;
    private final PolymarketMarketClobWsAdapter wsAdapter;

    /** Chave = slug. Populada por {@link #discoverMarkets()} e consumida via {@link #getCards()}. */
    private final Map<String, LiveMarketCard> cards = new ConcurrentHashMap<>();

    /** Timestamp do ultimo reconnect — usado como debounce no {@link #wsHealthCheck()}. */
    private volatile long lastReconnectAttemptMs = 0;

    /** Debounce minimo entre tentativas de reconnect (evita loop enquanto handshake nao terminou). */
    private static final long RECONNECT_DEBOUNCE_MS = 10_000;

    public LiveMarketStateService(PolymarketGammaClient gammaClient,
                                  PolymarketMarketClobWsAdapter wsAdapter) {
        this.gammaClient = gammaClient;
        this.wsAdapter = wsAdapter;
    }

    /**
     * Bootstrap: discovery inicial (Gamma API) + start do WS ja com a lista
     * de tokens descobertos + subscribe per-token com {@code custom_feature_enabled=true}.
     *
     * <p>Ordem importa — o socket da Polymarket espera receber {@code assets_ids}
     * no init message, e passar lista vazia pode ser rejeitado ou abrir conexao
     * idle. Mas o init message sozinho NAO ativa o delivery de {@code market_resolved}
     * events — isso exige subscribe per-token com o flag {@code custom_feature_enabled=true}
     * que vem via {@code subscribeToken(slug, outcome, tokenId)} (versao 3-args do socket).
     * Esse e o padrao do Gabriel e e essencial pra receber resolves.
     */
    @PostConstruct
    public void init() {
        log.info("LiveMarketStateService bootstrap iniciando...");

        List<LiveMarketCard> initialCards = new ArrayList<>();
        int discovered = runDiscoveryLoop(initialCards);

        List<String> initialTokens = extractTokens(initialCards);
        wsAdapter.start(initialTokens);

        // Subscribe per-token com custom_feature_enabled: true — essencial pra
        // receber market_resolved events. So o init message NAO basta.
        subscribePerToken(initialCards);

        log.info("Bootstrap concluido: {} markets descobertos, {} tokens no init + {} subscribes per-token",
                discovered, initialTokens.size(), initialCards.size() * 2);
    }

    /**
     * Re-executa discovery a cada 60s. A cada 5min (boundary de bloco) aparece um market
     * novo no horizonte (o que agora esta 70min a frente). Rodar a cada 60s garante que
     * pegamos o novo dentro de 1min. Idempotente — skips markets ja conhecidos.
     *
     * <p>Para cards novos descobertos, faz subscribe per-token com
     * {@code custom_feature_enabled=true} via {@code wsAdapter.subscribeToken(...)}.
     */
    @Scheduled(fixedRate = 60_000)
    public void scheduledDiscovery() {
        try {
            List<LiveMarketCard> newCards = new ArrayList<>();
            int discovered = runDiscoveryLoop(newCards);
            if (discovered > 0) {
                subscribePerToken(newCards);
                log.info("Scheduled discovery: +{} markets, +{} tokens subscritos (per-token)",
                        discovered, newCards.size() * 2);
            }
        } catch (Exception e) {
            log.error("Erro no scheduled discovery: {}", e.getMessage(), e);
        }
    }

    /**
     * Cleanup periodico de cards {@code RESOLVED} antigos. Mantem rolling
     * window de {@link #CARD_RETENTION_SECONDS} segundos (24h) baseado no
     * {@code endUnixTime} do card (momento em que o bloco terminou, nao
     * em quando a reconciliation rodou).
     *
     * <p>Cards em {@code OPEN} ou {@code RESOLVING} <b>nunca</b> sao
     * removidos por este cleanup — so cards com state {@code RESOLVED}.
     * Isso protege contra um card ficar "stuck" e ser removido por engano.
     *
     * <p>Roda a cada 15 minutos. A cadencia nao precisa ser agressiva — no
     * pior caso ~3 cards ultrapassam a janela entre runs, e ~200KB no teto
     * e trivial.
     */
    @Scheduled(fixedRate = 15 * 60_000)
    public void cleanupOldResolvedCards() {
        long cutoff = Instant.now().getEpochSecond() - CARD_RETENTION_SECONDS;
        int removed = 0;

        var iterator = cards.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            LiveMarketCard card = entry.getValue();
            if (card.getState() == LiveMarketState.RESOLVED
                    && card.getEndUnixTime() < cutoff) {
                iterator.remove();
                removed++;
            }
        }

        if (removed > 0) {
            log.info("Cleanup: {} cards RESOLVED removidos (endUnixTime < now - 24h). Cards restantes: {}",
                    removed, cards.size());
        }
    }

    /**
     * Health check do WS a cada 15s. Se o socket cair (Connection reset, timeout,
     * servidor fechou, rede local), o {@code onFailure}/{@code onClosed} seta
     * {@code opened=false} mas nao reconecta sozinho. Este scheduler detecta e
     * reconecta chamando {@code wsAdapter.start(...)} novamente com todos os
     * tokenIds dos cards em estado {@code OPEN}/{@code RESOLVING}, seguido de
     * subscribe per-token com {@code custom_feature_enabled=true} — sem esse
     * flag o servidor nao entrega {@code market_resolved} events.
     *
     * <p>Debounce de {@link #RECONNECT_DEBOUNCE_MS}ms previne loops de reconnect
     * enquanto o handshake anterior ainda nao terminou (o handshake e async no
     * OkHttp, entao {@code isConnected()} pode continuar false por alguns
     * centenas de ms apos a chamada a {@code connect()}).
     */
    @Scheduled(fixedRate = 15_000)
    public void wsHealthCheck() {
        if (wsAdapter.isConnected()) return;

        long now = System.currentTimeMillis();
        if (now - lastReconnectAttemptMs < RECONNECT_DEBOUNCE_MS) {
            log.debug("WS desconectado mas dentro do debounce — aguardando proximo tick");
            return;
        }

        lastReconnectAttemptMs = now;

        List<LiveMarketCard> activeCards = collectActiveCards();
        if (activeCards.isEmpty()) {
            log.warn("WS desconectado e nenhum card OPEN/RESOLVING em memoria — rodando discovery pra popular");
            runDiscoveryLoop(activeCards);
        }

        if (activeCards.isEmpty()) {
            log.error("WS desconectado e discovery nao retornou nenhum card — reconnect abortado");
            return;
        }

        List<String> tokens = extractTokens(activeCards);
        log.warn("WS desconectado — tentando reconectar com {} cards / {} tokens ativos",
                activeCards.size(), tokens.size());
        try {
            wsAdapter.start(tokens);
            subscribePerToken(activeCards);
        } catch (Exception e) {
            log.error("Erro no reconnect do WS: {}", e.getMessage(), e);
        }
    }

    /**
     * Coleta todos os cards em estado {@code OPEN} ou {@code RESOLVING}. Cards
     * em {@code RESOLVED} sao ignorados porque o resolve ja chegou — nao ha mais
     * nada no WS que a gente queira observar desses markets.
     */
    private List<LiveMarketCard> collectActiveCards() {
        List<LiveMarketCard> active = new ArrayList<>();
        for (LiveMarketCard card : cards.values()) {
            if (card.getState() == LiveMarketState.OPEN || card.getState() == LiveMarketState.RESOLVING) {
                active.add(card);
            }
        }
        return active;
    }

    /**
     * Extrai a lista plana de tokenIds (UP + DOWN) a partir de uma lista de cards.
     * Usado pra construir o {@code initMessage} do WS na hora do start/reconnect.
     */
    private List<String> extractTokens(List<LiveMarketCard> cardList) {
        List<String> tokens = new ArrayList<>(cardList.size() * 2);
        for (LiveMarketCard card : cardList) {
            if (card.getTokenUpId() != null) tokens.add(card.getTokenUpId());
            if (card.getTokenDownId() != null) tokens.add(card.getTokenDownId());
        }
        return tokens;
    }

    /**
     * Envia subscribe per-token (UP + DOWN) pra cada card via
     * {@code wsAdapter.subscribeToken(slug, outcome, tokenId)}, que usa a
     * versao 3-args do socket com {@code custom_feature_enabled=true}.
     * Esse flag e o que ativa o delivery de {@code market_resolved} events pelo
     * servidor da Polymarket — sem ele, o WS entrega book/price_change mas
     * silencia resolves.
     */
    private void subscribePerToken(List<LiveMarketCard> cardList) {
        for (LiveMarketCard card : cardList) {
            if (card.getTokenUpId() != null) {
                wsAdapter.subscribeToken(card.getSlug(), "UP", card.getTokenUpId());
            }
            if (card.getTokenDownId() != null) {
                wsAdapter.subscribeToken(card.getSlug(), "DOWN", card.getTokenDownId());
            }
        }
    }

    // ─── Discovery core ─────────────────────────────────────────────

    /**
     * Calcula os boundaries dos proximos N blocos, chama a Gamma API pra cada um,
     * cria um {@link LiveMarketCard} novo pra cada market encontrado, e acumula
     * os cards novos em {@code newCardsOut} pro caller decidir o que fazer
     * (extrair tokens pra init message via {@code extractTokens}, subscrever
     * per-token via {@code subscribePerToken}, etc).
     *
     * @param newCardsOut lista acumuladora — sera preenchida com os cards dos markets NOVOS
     * @return numero de markets NOVOS adicionados nesta chamada (ignora ja existentes).
     */
    private int runDiscoveryLoop(List<LiveMarketCard> newCardsOut) {
        long nowEpoch = Instant.now().getEpochSecond();
        long currentBoundary = nowEpoch - (nowEpoch % BLOCK_DURATION_SECONDS);
        int blocksToLoad = (int) Math.ceil((double) LOOKAHEAD_SECONDS / BLOCK_DURATION_SECONDS);

        int added = 0;

        for (int i = 0; i < blocksToLoad; i++) {
            long marketUnix = currentBoundary + (i * BLOCK_DURATION_SECONDS);
            String slug = MARKET_GROUP + marketUnix;

            if (cards.containsKey(slug)) {
                continue;
            }

            try {
                GammaMarketResponse response = gammaClient.getMarketBySlug(slug);

                if (response == null) {
                    log.debug("Gamma retornou null para slug={}", slug);
                    continue;
                }

                List<String> tokenIds = response.getClobTokenIds();
                if (tokenIds == null || tokenIds.size() != 2) {
                    log.warn("Gamma retornou tokenIds invalidos para slug={}: {}", slug, tokenIds);
                    continue;
                }

                LiveMarketCard card = LiveMarketCard.builder()
                        .slug(slug)
                        .marketUnixTime(marketUnix)
                        .endUnixTime(marketUnix + BLOCK_DURATION_SECONDS)
                        .displayName(response.getQuestion())
                        .conditionId(response.getConditionId())
                        .tokenUpId(tokenIds.get(0))
                        .tokenDownId(tokenIds.get(1))
                        .state(LiveMarketState.OPEN)
                        .build();

                cards.put(slug, card);
                newCardsOut.add(card);
                added++;

                log.info("Discovered market: slug={} tokenUp={} tokenDown={}",
                        slug, card.getTokenUpId(), card.getTokenDownId());

            } catch (RuntimeException e) {
                log.warn("Gamma falhou para slug={}: {}", slug, e.getMessage());
            }
        }

        return added;
    }

    // ─── Consulta pra endpoint (subfase 1d) ─────────────────────────

    /**
     * Retorna snapshot da lista de cards ordenada por {@code marketUnixTime} ascendente.
     * Cada chamada devolve uma nova lista — seguro pra leitura concorrente.
     */
    public List<LiveMarketCard> getCards() {
        return cards.values().stream()
                .sorted(Comparator.comparingLong(LiveMarketCard::getMarketUnixTime))
                .toList();
    }

    /**
     * Usado pela subfase 1c pra localizar o card quando um resolve event chegar.
     */
    public LiveMarketCard getCard(String slug) {
        return cards.get(slug);
    }

    /**
     * Usado pela subfase 1c pra localizar o card pelo assetId (tokenId) recebido
     * no {@code PolyMarketResolvedEvent.winningAssetId}. Varredura linear —
     * aceitavel porque o mapa tem ~15 cards na pratica.
     */
    public LiveMarketCard getCardByTokenId(String tokenId) {
        if (tokenId == null) return null;
        Collection<LiveMarketCard> snapshot = cards.values();
        for (LiveMarketCard card : snapshot) {
            if (tokenId.equals(card.getTokenUpId()) || tokenId.equals(card.getTokenDownId())) {
                return card;
            }
        }
        return null;
    }
}
