package br.com.yacamin.amitiel.adapter.out.rest.polymarket;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class PolymarketClobClient {

    private final String baseUrl;
    private final RestClient restClient;
    private final ClobAuthSigner authSigner;

    public PolymarketClobClient(
            @Value("${polymarket.paths.clob.rest}") String baseUrl,
            ClobAuthSigner authSigner) {
        this.baseUrl = baseUrl;
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .build();
        this.authSigner = authSigner;
    }

    public long getServerTime() {
        try {
            log.info("[CLOB] GET {}/time", baseUrl);
            String body = restClient.get()
                    .uri("/time")
                    .retrieve()
                    .body(String.class);
            log.info("[CLOB] /time raw response: '{}'", body);

            if (body == null || body.isBlank()) {
                log.warn("[CLOB] /time retornou vazio, usando local");
                return System.currentTimeMillis() / 1000;
            }

            body = body.trim();
            long ts;

            // Pode vir como numero puro (1773248491) ou JSON {"time": 1773248491.123}
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(body);

            if (node.isNumber()) {
                ts = node.asLong();
            } else {
                ts = node.path("time").asLong();
            }

            log.info("[CLOB] Server time parsed: {}", ts);
            return ts;
        } catch (Exception e) {
            long local = System.currentTimeMillis() / 1000;
            log.warn("[CLOB] Falha ao buscar server time: {} - usando local: {}", e.getMessage(), local);
            return local;
        }
    }

    public List<ClobTrade> getTradesForMarket(String conditionId) {
        List<ClobTrade> allTrades = new ArrayList<>();
        String cursor = null;

        log.info("[CLOB] Buscando trades para conditionId={}, wallet={}", conditionId, authSigner.getWalletAddress());

        for (int page = 0; page < 10; page++) {
            String path = "/trades";
            StringBuilder queryBuilder = new StringBuilder();
            queryBuilder.append("?market=").append(conditionId);
            queryBuilder.append("&maker_address=").append(authSigner.getWalletAddress());
            if (cursor != null && !cursor.equals("LTE=")) {
                queryBuilder.append("&next_cursor=").append(cursor);
            }

            String fullUri = path + queryBuilder;
            String fullUrl = baseUrl + fullUri;
            long timestamp = getServerTime();
            Map<String, String> headers = authSigner.sign("GET", path, timestamp);

            log.info("[CLOB] GET {} (page={})", fullUrl, page);
            log.debug("[CLOB] Auth headers: POLY_ADDRESS={}, POLY_TIMESTAMP={}, POLY_API_KEY={}, POLY_NONCE={}",
                    headers.get("POLY_ADDRESS"), headers.get("POLY_TIMESTAMP"),
                    headers.get("POLY_API_KEY"), headers.get("POLY_NONCE"));

            try {
                String rawBody = restClient.get()
                        .uri(fullUri)
                        .headers(h -> headers.forEach(h::set))
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, (req, resp) -> {
                            byte[] body = resp.getBody().readAllBytes();
                            String bodyStr = new String(body);
                            log.error("[CLOB] HTTP {} {} - Response: {}", resp.getStatusCode().value(), fullUrl, bodyStr);
                            throw new RuntimeException("CLOB HTTP " + resp.getStatusCode().value() + ": " + bodyStr);
                        })
                        .body(String.class);

                log.info("[CLOB] Response (page={}): {}", page,
                        rawBody != null && rawBody.length() > 500 ? rawBody.substring(0, 500) + "..." : rawBody);

                if (rawBody == null || rawBody.isBlank()) {
                    log.warn("[CLOB] Response body vazio, encerrando paginacao");
                    break;
                }

                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                ClobTradesResponse response = mapper.readValue(rawBody, ClobTradesResponse.class);

                if (response.getData() == null || response.getData().isEmpty()) {
                    log.info("[CLOB] Nenhum trade na page={}, total coletado={}", page, allTrades.size());
                    break;
                }

                allTrades.addAll(response.getData());
                log.info("[CLOB] Page={} retornou {} trades, total={}, nextCursor={}",
                        page, response.getData().size(), allTrades.size(), response.getNextCursor());

                if (response.getNextCursor() == null || "LTE=".equals(response.getNextCursor())) {
                    break;
                }
                cursor = response.getNextCursor();
            } catch (RuntimeException e) {
                // Already logged in onStatus handler
                throw e;
            } catch (Exception e) {
                log.error("[CLOB] Erro inesperado page={}: {} ({})", page, e.getMessage(), e.getClass().getSimpleName(), e);
                break;
            }
        }

        log.info("[CLOB] Total trades coletados para conditionId={}: {}", conditionId, allTrades.size());
        return allTrades;
    }

    public List<ClobTrade> getTradesByAssetId(String assetId) {
        List<ClobTrade> allTrades = new ArrayList<>();
        String cursor = null;

        log.info("[CLOB] Buscando trades por assetId={}, wallet={}", assetId, authSigner.getWalletAddress());

        for (int page = 0; page < 10; page++) {
            String path = "/trades";
            StringBuilder queryBuilder = new StringBuilder();
            queryBuilder.append("?asset_id=").append(assetId);
            queryBuilder.append("&maker_address=").append(authSigner.getWalletAddress());
            if (cursor != null && !cursor.equals("LTE=")) {
                queryBuilder.append("&next_cursor=").append(cursor);
            }

            String fullUri = path + queryBuilder;
            long timestamp = getServerTime();
            Map<String, String> headers = authSigner.sign("GET", path, timestamp);

            log.info("[CLOB] GET {}{} (page={})", baseUrl, fullUri, page);

            try {
                String rawBody = restClient.get()
                        .uri(fullUri)
                        .headers(h -> headers.forEach(h::set))
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, (req, resp) -> {
                            byte[] body = resp.getBody().readAllBytes();
                            String bodyStr = new String(body);
                            log.error("[CLOB] HTTP {} - Response: {}", resp.getStatusCode().value(), bodyStr);
                            throw new RuntimeException("CLOB HTTP " + resp.getStatusCode().value() + ": " + bodyStr);
                        })
                        .body(String.class);

                if (rawBody == null || rawBody.isBlank()) break;

                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                ClobTradesResponse response = mapper.readValue(rawBody, ClobTradesResponse.class);

                if (response.getData() == null || response.getData().isEmpty()) break;

                allTrades.addAll(response.getData());

                if (response.getNextCursor() == null || "LTE=".equals(response.getNextCursor())) break;
                cursor = response.getNextCursor();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                log.error("[CLOB] Erro inesperado assetId page={}: {}", page, e.getMessage(), e);
                break;
            }
        }

        return allTrades;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ClobTradesResponse {
        private int limit;
        @JsonProperty("next_cursor")
        private String nextCursor;
        private int count;
        private List<ClobTrade> data;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ClobTrade {
        private String id;
        private String market;
        @JsonProperty("asset_id")
        private String assetId;
        private String side;
        private String price;
        private String size;
        private String status;
        @JsonProperty("match_time")
        private String matchTime;
        private String outcome;
        @JsonProperty("maker_address")
        private String makerAddress;
        @JsonProperty("trader_side")
        private String traderSide;
        @JsonProperty("fee_rate_bps")
        private String feeRateBps;
        @JsonProperty("created_at")
        private String createdAt;
        private String type;
        @JsonProperty("transaction_hash")
        private String transactionHash;
        @JsonProperty("bucket_index")
        private String bucketIndex;
        private String title;
    }
}
