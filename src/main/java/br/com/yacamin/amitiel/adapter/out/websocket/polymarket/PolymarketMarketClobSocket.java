package br.com.yacamin.amitiel.adapter.out.websocket.polymarket;

import br.com.yacamin.amitiel.adapter.out.websocket.polymarket.dto.response.PolyMarketResolvedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Cliente WebSocket do Amitiel pro canal "market" da Polymarket CLOB.
 * Portado do Yacamin-Gabriel com simplificacoes: remove o canal "user", remove
 * tracking de latencia, remove parse de book/price_change/etc (Amitiel so precisa
 * do evento market_resolved).
 */
@Slf4j
@Service
public class PolymarketMarketClobSocket {

    public static final String MARKET_CHANNEL = "market";

    private final OkHttpClient client;
    private final ObjectMapper om;

    private final AtomicReference<WebSocket> wsRef = new AtomicReference<>();
    private final Queue<String> pendingSends = new ConcurrentLinkedQueue<>();

    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> pingTask;

    private volatile boolean opened = false;

    public boolean isConnected() {
        return opened && wsRef.get() != null;
    }

    private volatile String channelType;
    private volatile String baseUrl;
    private volatile List<String> data = List.of();
    private volatile boolean verbose = true;

    private volatile Consumer<String> onMessage = msg -> {};
    private volatile Consumer<Throwable> onError = err -> {};
    private volatile Consumer<String> onClose = reason -> {};

    private final Map<String, String> subscribedMap = new ConcurrentHashMap<>();

    private final ApplicationEventPublisher eventPublisher;

    public PolymarketMarketClobSocket(ObjectMapper objectMapper, ApplicationEventPublisher eventPublisher) {
        this.client = new OkHttpClient.Builder()
                .callTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ZERO)
                .build();

        this.om = objectMapper != null ? objectMapper : new ObjectMapper();
        this.eventPublisher = eventPublisher;

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "amitiel-ws-market-ping");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Configure antes de conectar.
     */
    public void configure(String channelType,
                          String baseUrl,
                          List<String> data,
                          boolean verbose) {
        this.channelType = Objects.requireNonNull(channelType, "channelType");
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.data = data != null ? List.copyOf(data) : List.of();
        this.verbose = verbose;
    }

    public void setOnMessage(Consumer<String> onMessage) {
        this.onMessage = onMessage != null ? onMessage : msg -> {};
    }

    public void setOnError(Consumer<Throwable> onError) {
        this.onError = onError != null ? onError : err -> {};
    }

    public void setOnClose(Consumer<String> onClose) {
        this.onClose = onClose != null ? onClose : reason -> {};
    }

    /**
     * Conecta com a configuracao atual.
     */
    public synchronized void connect() {
        if (opened || wsRef.get() != null) {
            if (verbose) log.info("WS ja conectado/connecting");
            return;
        }
        if (channelType == null || baseUrl == null) {
            throw new IllegalStateException("Chame configure(...) antes de connect()");
        }

        String fullUrl = baseUrl + "/ws/" + channelType;

        Request req = new Request.Builder()
                .url(fullUrl)
                .build();

        client.newWebSocket(req, new WebSocketListener() {

            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                wsRef.set(webSocket);
                opened = true;

                if (verbose) log.info("OPEN [{}] {}", channelType, fullUrl);

                try {
                    String initMsg = buildInitMessage();
                    if (initMsg == null) {
                        webSocket.close(1008, "invalid config");
                        opened = false;
                        onError.accept(new IllegalStateException("Config invalida para channel=" + channelType));
                        return;
                    }

                    webSocket.send(initMsg);
                    flushPending();
                    startPingLoop();

                } catch (Exception e) {
                    opened = false;
                    onError.accept(e);
                    webSocket.close(1011, "init failed");
                }
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                try {
                    if ("PONG".equalsIgnoreCase(text)) return;
                    if ("PING".equalsIgnoreCase(text) || "NO NEW ASSETS".equalsIgnoreCase(text)) return;

                    JsonNode root = om.readTree(text);

                    JsonNode et = root.get("event_type");
                    if (et == null || et.isNull()) {
                        onMessage.accept(text);
                        return;
                    }

                    String eventType = et.asText("");

                    switch (eventType) {
                        case "market_resolved" -> {
                            PolyMarketResolvedEvent dto = om.treeToValue(root, PolyMarketResolvedEvent.class);
                            eventPublisher.publishEvent(dto);
                        }
                        // Amitiel nao consome esses tipos no canal market — apenas market_resolved importa
                        case "price_change", "tick_size_change", "last_trade_price",
                             "best_bid_ask", "new_market", "book" -> {}
                        default -> log.info("Unknown event type: {}", eventType);
                    }

                } catch (Exception e) {
                    onError.accept(e);
                    onMessage.accept(text);
                }
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                if (verbose) log.info("CLOSING [{}] code={} reason={}", channelType, code, reason);
                webSocket.close(code, reason);
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                opened = false;
                stopPingLoop();
                wsRef.set(null);

                if (verbose) log.info("CLOSED [{}] code={} reason={}", channelType, code, reason);
                onClose.accept("code=" + code + " reason=" + reason);
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                if (verbose) {
                    log.error("ERROR [{}]: {}", channelType, t.getMessage());
                    if (response != null) {
                        log.error("HTTP: {} {}", response.code(), response.message());
                        try {
                            ResponseBody body = response.body();
                            if (body != null) log.error("Body: {}", body.string());
                        } catch (IOException ignored) {}
                    }
                }

                opened = false;
                stopPingLoop();
                wsRef.set(null);

                onError.accept(t);
            }
        });
    }

    /**
     * Fecha conexao.
     */
    public synchronized void close() {
        stopPingLoop();
        opened = false;

        WebSocket ws = wsRef.getAndSet(null);
        if (ws != null) {
            ws.close(1000, "bye");
        }
    }

    // -------------------- subscribe / unsubscribe --------------------

    public void subscribeToTokensIds(String slug, String outcome, String tokenId) {
        if (!MARKET_CHANNEL.equals(channelType)) return;
        sendOrQueueJson(Map.of(
                "assets_ids", tokenId != null ? List.of(tokenId) : List.of(),
                "operation", "subscribe",
                "custom_feature_enabled", true
        ));

        subscribedMap.put(tokenId, slug + "-" + outcome);
    }

    public void subscribeToTokensIds(List<String> assetsIds) {
        if (!MARKET_CHANNEL.equals(channelType)) return;
        sendOrQueueJson(Map.of(
                "assets_ids", assetsIds != null ? assetsIds : List.of(),
                "operation", "subscribe"
        ));
    }

    public void unsubscribeToTokensIds(List<String> assetsIds) {
        if (!MARKET_CHANNEL.equals(channelType)) return;
        sendOrQueueJson(Map.of(
                "assets_ids", assetsIds != null ? assetsIds : List.of(),
                "operation", "unsubscribe"
        ));
    }

    /**
     * Remove entradas do subscribedMap que nao estao mais nos tokenIds validos.
     */
    public void cleanupSubscriptions(Set<String> validTokenIds) {
        int before = subscribedMap.size();
        subscribedMap.keySet().removeIf(tokenId -> !validTokenIds.contains(tokenId));
        int removed = before - subscribedMap.size();
        if (removed > 0) {
            log.info("Cleanup: removed {} stale subscriptions from subscribedMap", removed);
        }
    }

    // -------------------- helpers --------------------

    private String buildInitMessage() throws Exception {
        if (MARKET_CHANNEL.equals(channelType)) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("assets_ids", data);
            payload.put("type", MARKET_CHANNEL);
            return om.writeValueAsString(payload);
        }
        return null;
    }

    private void sendOrQueueJson(Map<String, ?> payload) {
        try {
            sendOrQueue(om.writeValueAsString(payload));
        } catch (Exception e) {
            onError.accept(e);
        }
    }

    private void sendOrQueue(String msg) {
        WebSocket ws = wsRef.get();
        if (opened && ws != null) {
            ws.send(msg);
        } else {
            pendingSends.add(msg);
        }
    }

    private void flushPending() {
        WebSocket ws = wsRef.get();
        if (ws == null) return;

        String msg;
        while ((msg = pendingSends.poll()) != null) {
            ws.send(msg);
        }
    }

    private synchronized void startPingLoop() {
        stopPingLoop();
        pingTask = scheduler.scheduleAtFixedRate(() -> {
            WebSocket ws = wsRef.get();
            if (ws != null) {
                ws.send("PING");
            }
        }, 10, 10, TimeUnit.SECONDS);
    }

    private synchronized void stopPingLoop() {
        if (pingTask != null) {
            pingTask.cancel(true);
            pingTask = null;
        }
    }

    @PreDestroy
    public void destroy() {
        close();
        scheduler.shutdownNow();
    }
}
