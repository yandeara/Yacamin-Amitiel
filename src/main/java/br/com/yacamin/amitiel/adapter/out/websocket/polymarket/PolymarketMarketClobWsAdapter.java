package br.com.yacamin.amitiel.adapter.out.websocket.polymarket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Adapter fino sobre {@link PolymarketMarketClobSocket}. Expoe uma API minima
 * pra subfase 1b: start() conecta o WS, subscribeToken() adiciona um tokenId
 * ao set ativo. A logica de descoberta de markets e o trigger do start vivem
 * na subfase 1b (LiveMarketStateService), nao aqui.
 */
@Slf4j
@Service
public class PolymarketMarketClobWsAdapter {

    public static final String MARKET_CHANNEL = "market";
    private static final String BASE_URL = "wss://ws-subscriptions-clob.polymarket.com";

    private final PolymarketMarketClobSocket ws;

    public PolymarketMarketClobWsAdapter(PolymarketMarketClobSocket ws) {
        this.ws = ws;
    }

    /**
     * Configura e conecta o WS no canal market. Deve ser chamado uma vez
     * durante o bootstrap do Amitiel com a lista de tokenIds descobertos
     * pelo {@code LiveMarketStateService}. A lista inicial e usada no
     * {@code buildInitMessage()} do socket — passar uma lista vazia pode
     * ser rejeitado pelo servidor da Polymarket ou abrir uma conexao inutil.
     */
    public void start(List<String> initialTokens) {
        ws.configure(
                MARKET_CHANNEL,
                BASE_URL,
                initialTokens != null ? initialTokens : List.of(),
                true
        );

        ws.setOnMessage(msg -> log.debug("WS raw: {}", msg));
        ws.setOnError(err -> log.error("WS error: {}", err.getMessage(), err));
        ws.setOnClose(reason -> log.info("WS closed: {}", reason));

        ws.connect();
        log.info("Polymarket market WS adapter connecting with {} initial tokens",
                initialTokens != null ? initialTokens.size() : 0);
    }

    public void subscribeToken(String slug, String outcome, String tokenId) {
        ws.subscribeToTokensIds(slug, outcome, tokenId);
    }

    public void subscribeTokens(List<String> assetsIds) {
        ws.subscribeToTokensIds(assetsIds);
    }

    public void unsubscribeTokens(List<String> assetsIds) {
        ws.unsubscribeToTokensIds(assetsIds);
    }

    public boolean isConnected() {
        return ws.isConnected();
    }
}
