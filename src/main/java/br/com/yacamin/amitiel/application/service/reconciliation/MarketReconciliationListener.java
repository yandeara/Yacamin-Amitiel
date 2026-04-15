package br.com.yacamin.amitiel.application.service.reconciliation;

import br.com.yacamin.amitiel.adapter.out.persistence.EventRepository;
import br.com.yacamin.amitiel.adapter.out.rest.polymarket.PolymarketClobClient;
import br.com.yacamin.amitiel.adapter.out.rest.polymarket.PolymarketClobClient.ClobTrade;
import br.com.yacamin.amitiel.adapter.out.websocket.polymarket.dto.response.PolyMarketResolvedEvent;
import br.com.yacamin.amitiel.domain.Event;
import br.com.yacamin.amitiel.domain.LiveMarketCard;
import br.com.yacamin.amitiel.domain.LiveMarketState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Escuta {@link PolyMarketResolvedEvent} (publicado pelo
 * {@code PolymarketMarketClobSocket} quando o WS recebe um {@code market_resolved})
 * e completa os campos de reconciliation do {@link LiveMarketCard} correspondente.
 *
 * <p>Estrategia em duas fases:
 *
 * <ol>
 *   <li><b>Fase imediata</b>: ao receber o evento, marca o card como {@code RESOLVING}
 *       e salva {@code winningOutcome}. Tenta reconciliation imediata (happy path).</li>
 *   <li><b>Fase de retry</b>: se a imediata falhar (tipicamente porque o Gabriel ainda
 *       nao escreveu o evento {@code PNL} no Mongo — ele tambem processa o resolve e
 *       leva alguns segundos), um scheduler re-tenta a cada 5s ate funcionar.</li>
 * </ol>
 *
 * <p>Mutations dos cards sao feitas direto na referencia retornada pelo
 * {@link LiveMarketStateService} — o {@code LiveMarketCard} e um objeto Lombok
 * {@code @Data} com setters e mora no {@code ConcurrentHashMap} do LSS, entao
 * setters alteram o estado visivel a todos os leitores.
 */
@Service
@Slf4j
public class MarketReconciliationListener {

    /**
     * Taxa de fee efetiva aplicada em trades crypto da Polymarket.
     * Formula correta: {@code fee = shares * FEE_RATE * price * (1-price)}.
     *
     * <p>Validada empiricamente contra os fees reais que a Polymarket CLOB API
     * retorna por trade (ver [[yac-bug-001-analise]] sessao "15/04/2026 03:45").
     * Gabriel tem uma versao BUGADA dessa mesma formula em
     * {@code RedeemEventService.calculateCryptoFee} que usa
     * {@code min(price, 1-price)} em vez de {@code price * (1-price)} —
     * overconta fees e e a root cause do yac-bug-001.
     */
    private static final double FEE_RATE = 0.072;

    private final LiveMarketStateService liveMarketStateService;
    private final EventRepository eventRepository;
    private final PolymarketClobClient clobClient;

    /**
     * Slug do card atualmente sendo retry-polled pra completar reconciliation.
     * No maximo UM card por vez e polled — quando o proximo resolve chega, o
     * card anterior e abandonado (promovido a RESOLVED com dados parciais) e o
     * novo vira o {@code currentResolvingSlug}. Isso previne loops de pooling
     * infinito pra cards cujo Gabriel travou ou que nunca vao ter PNL escrito.
     */
    private volatile String currentResolvingSlug = null;

    public MarketReconciliationListener(
            LiveMarketStateService liveMarketStateService,
            EventRepository eventRepository,
            PolymarketClobClient clobClient) {
        this.liveMarketStateService = liveMarketStateService;
        this.eventRepository = eventRepository;
        this.clobClient = clobClient;
    }

    /**
     * Handler do {@link PolyMarketResolvedEvent}. Marca o card como
     * {@code RESOLVING} imediatamente (persistindo {@code winningOutcome}) e
     * tenta a reconciliation. Roda no {@code pnlQueryExecutor} pra nao bloquear
     * a thread do WS — o {@code tryReconcile} chama Gamma + CLOB API, operacoes
     * de rede que podem demorar centenas de ms.
     *
     * <p>Se ja havia outro card em RESOLVING, ele e abandonado agora — o novo
     * resolve sinaliza que o momento do anterior passou.
     */
    @EventListener
    @Async("pnlQueryExecutor")
    public void onMarketResolved(PolyMarketResolvedEvent event) {
        String winningAssetId = event.getWinningAssetId();
        String winningOutcome = event.getWinningOutcome();

        LiveMarketCard card = liveMarketStateService.getCardByTokenId(winningAssetId);
        if (card == null) {
            log.warn("Resolve event recebido para tokenId desconhecido: {}", winningAssetId);
            return;
        }

        abandonPreviousIfNeeded(card.getSlug());

        card.setWinningOutcome(winningOutcome);
        card.setState(LiveMarketState.RESOLVING);
        currentResolvingSlug = card.getSlug();
        log.info("Market {} resolved (outcome={}) — iniciando reconciliation", card.getSlug(), winningOutcome);

        tryReconcile(card);
    }

    /**
     * Se havia um card anterior ainda em {@code RESOLVING} (nao terminou a
     * reconciliation ate agora), abandona ele promovendo pra {@code RESOLVED}
     * com os dados parciais que tinha. {@code pnlGabriel}/{@code divergence}
     * provavelmente vao ficar null — a UI mostra como "dados indisponiveis"
     * (card em cor cinza-dim em vez de verde/vermelho).
     *
     * <p>Motivacao: sem esse abandono, um card que nunca recebesse o
     * {@code PNL} event do Gabriel (travamento, crash, etc) ficaria polled pra
     * sempre pelo scheduler. Abandonar quando o proximo resolve chega e uma
     * heuristica natural — "o momento passou".
     */
    private void abandonPreviousIfNeeded(String newSlug) {
        String previousSlug = currentResolvingSlug;
        if (previousSlug == null || previousSlug.equals(newSlug)) return;

        LiveMarketCard previousCard = liveMarketStateService.getCard(previousSlug);
        if (previousCard == null) return;

        if (previousCard.getState() == LiveMarketState.RESOLVING) {
            previousCard.setState(LiveMarketState.RESOLVED);
            log.warn("Abandonando reconciliation de {} — proximo resolve chegou. " +
                            "Dados parciais: pnlGabriel={}, divergence={}",
                    previousSlug, previousCard.getPnlGabriel(), previousCard.getDivergence());
        }
    }

    /**
     * Re-tenta reconciliation apenas pro {@code currentResolvingSlug} (no maximo
     * 1 card por tick). Se o card ja foi reconciliado (estado != RESOLVING)
     * ou nao existe mais, limpa o {@code currentResolvingSlug} e dorme ate o
     * proximo resolve chegar.
     *
     * <p>Condicao de entrada tipica: Gabriel ainda nao escreveu o {@code PNL}
     * event no Mongo quando o WS resolve chegou (Gabriel leva 5-15s pra rodar
     * o pipeline de resolve dele).
     */
    @Scheduled(fixedRate = 5000)
    public void retryResolving() {
        String slug = currentResolvingSlug;
        if (slug == null) return;

        LiveMarketCard card = liveMarketStateService.getCard(slug);
        if (card == null || card.getState() != LiveMarketState.RESOLVING) {
            // Ja foi reconciliado com sucesso pelo tryReconcile na chamada imediata,
            // ou o card foi removido por algum motivo — limpa o slot
            currentResolvingSlug = null;
            return;
        }

        tryReconcile(card);
    }

    /**
     * Tentativa unica de reconciliation. Idempotente — pode ser chamado
     * quantas vezes for necessario ate passar.
     *
     * <p>Fluxo:
     * <ol>
     *   <li>Le o {@code PNL} event do Gabriel do Mongo (collection {@code events}).
     *       Extrai {@code pnlReal} e {@code redeemPayout} de uma so vez.</li>
     *   <li>Chama {@link PolymarketClobClient#getTradesForMarket(String)} com o
     *       {@code conditionId} ja armazenado no card (da descoberta em 1b) —
     *       dispensa qualquer lookup em DB ou Gamma API.</li>
     *   <li>Agrega {@code buyCost}, {@code sellRevenue}, {@code totalFees} a partir
     *       da lista de trades retornada pelo CLOB. Mesma formula que o legado
     *       {@code VerificationService} usa, so sem passar pela collection
     *       {@code real_pnl_events} (que e legada).</li>
     *   <li>Calcula {@code walletNet = sellRevenue + redeemPayout - buyCost - totalFees}
     *       e {@code divergence = pnlGabriel - walletNet}.</li>
     *   <li>Preenche o card e promove pra {@code RESOLVED}.</li>
     * </ol>
     *
     * <p><b>Sobre o redeemPayout:</b> vem do proprio {@code PNL} event do Gabriel,
     * nao do CLOB API (CLOB nao expoe redeems — sao eventos on-chain). A alternativa
     * seria consultar o chain diretamente via RPC, mas e fora do escopo da Fase 1.
     * Na pratica o {@code redeemPayout} do Gabriel e derivado das suas entries que
     * estao em status {@code RESOLVED}, que por sua vez foram setadas via
     * {@code PolygonOnChainListener} do Gabriel — "chain truth via listener do Gabriel".
     */
    private void tryReconcile(LiveMarketCard card) {
        try {
            // 1) Le o PNL event do Gabriel (payload inteiro)
            Map<String, Object> pnlPayload = readGabrielPnlPayload(card.getSlug());
            if (pnlPayload == null) {
                // Gabriel ainda nao computou — fica em RESOLVING, scheduler retenta
                return;
            }

            Double pnlGabriel = asDouble(pnlPayload.get("pnlReal"));
            Double redeemPayout = asDouble(pnlPayload.get("redeemPayout"));
            if (pnlGabriel == null) {
                log.warn("PNL event de {} existe mas pnlReal e null/invalido: {}", card.getSlug(), pnlPayload);
                return;
            }
            if (redeemPayout == null) redeemPayout = 0.0;

            // 2) Chama CLOB API direto com o conditionId ja armazenado no card
            if (card.getConditionId() == null || card.getConditionId().isBlank()) {
                log.warn("Card {} sem conditionId — nao da pra consultar CLOB API", card.getSlug());
                return;
            }

            List<ClobTrade> trades;
            try {
                trades = clobClient.getTradesForMarket(card.getConditionId());
            } catch (Exception e) {
                log.warn("CLOB API falhou pra {}: {}", card.getSlug(), e.getMessage());
                return;
            }
            if (trades == null) trades = Collections.emptyList();

            // 3) Agrega trades em buyCost/sellRevenue/totalFees
            double buyCost = 0;
            double sellRevenue = 0;
            double totalFees = 0;
            log.info("Reconciling {} — trades recebidos do CLOB: {}", card.getSlug(), trades.size());
            for (ClobTrade t : trades) {
                try {
                    double price = Double.parseDouble(t.getPrice());
                    double size = Double.parseDouble(t.getSize());
                    double notional = price * size;

                    // Fees so em takers (makers tem 0 fee + rebates).
                    // Formula correta da Polymarket: size * rate * price * (1-price).
                    // NAO usar min(price, 1-price) — essa e a formula bugada do
                    // Gabriel que motivou a yac-bug-001.
                    //
                    // Filtro duplo: (A) traderSide != MAKER (autoridade direta),
                    // (B) side == SELL (fallback empirico — todos os BUYs do
                    // Gabriel no Polymarket mostram fee 0 na CLOB API).
                    // Trade paga fee se AMBOS forem verdade (nao maker E sell).
                    boolean isMaker = "MAKER".equals(t.getTraderSide());
                    boolean isSell = "SELL".equals(t.getSide());
                    boolean chargeFee = !isMaker && isSell;

                    double fee = 0;
                    if (chargeFee) {
                        fee = size * FEE_RATE * price * (1.0 - price);
                        fee = Math.round(fee * 100000.0) / 100000.0;
                        fee = Math.max(fee, 0.00001);
                        totalFees += fee;
                    }

                    // Log diagnostico por trade — facilita diagnose de bugs de fee
                    log.info("  trade side={} price={} size={} notional={} traderSide={} feeRateBps={} chargeFee={} feeComputed={}",
                            t.getSide(), price, size, notional, t.getTraderSide(),
                            t.getFeeRateBps(), chargeFee, fee);

                    if ("BUY".equals(t.getSide())) {
                        buyCost += notional;
                    } else if ("SELL".equals(t.getSide())) {
                        sellRevenue += notional;
                    }
                } catch (NumberFormatException ignored) {
                    // trade com price/size invalido — ignora e segue
                }
            }

            buyCost = round4(buyCost);
            sellRevenue = round4(sellRevenue);
            totalFees = round4(totalFees);

            // 4) Calcula walletNet e divergencia
            double walletNet = sellRevenue + redeemPayout - buyCost - totalFees;
            double divergence = pnlGabriel - walletNet;

            // 5) Preenche card e promove pra RESOLVED
            card.setBuyCost(buyCost);
            card.setSellRevenue(sellRevenue);
            card.setTotalFees(totalFees);
            card.setRedeemPayout(redeemPayout);
            card.setPnlGabriel(pnlGabriel);
            card.setDivergence(round4(divergence));
            card.setState(LiveMarketState.RESOLVED);

            log.info("Reconciled {}: gabrielPnl={} walletNet={} divergence={} ({} clobTrades)",
                    card.getSlug(), pnlGabriel, round4(walletNet), round4(divergence), trades.size());

        } catch (Exception e) {
            log.error("Erro reconciliando card {}: {}", card.getSlug(), e.getMessage(), e);
        }
    }

    /**
     * Le o {@code PNL} event do Gabriel no Mongo (collection {@code events}) pelo
     * slug e retorna o payload inteiro como {@code Map<String, Object>}. O payload
     * tem {@code pnlReal}, {@code totalFees}, {@code buyCost}, {@code sellRevenue},
     * {@code redeemPayout}, {@code marketUnixTime}, {@code source}.
     *
     * <p>Retorna null se o evento nao existir (Gabriel nao processou o resolve
     * ainda) ou se o shape do payload for inesperado.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readGabrielPnlPayload(String slug) {
        List<Event> events = eventRepository.findBySlugOrderByTimestampAsc(slug);
        Event pnlEvent = events.stream()
                .filter(e -> "PNL".equals(e.getType()))
                .findFirst()
                .orElse(null);

        if (pnlEvent == null || pnlEvent.getPayload() == null) {
            return null;
        }

        try {
            return (Map<String, Object>) pnlEvent.getPayload();
        } catch (ClassCastException e) {
            log.warn("Payload do PNL event de {} tem shape inesperado: {}",
                    slug, pnlEvent.getPayload());
            return null;
        }
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    /**
     * Conversao defensiva Object → Double. Mongo pode deserializar numeros
     * como Integer, Long, Double ou ate Decimal128, dependendo do campo. Nao
     * queremos quebrar por causa de um tipo inesperado.
     */
    private static Double asDouble(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
