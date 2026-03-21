package br.com.yacamin.amitiel.adapter.out.rest.polymarket;

import br.com.yacamin.amitiel.application.configuration.JsonStringArrayToListDeserializer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

@Slf4j
@Component
public class PolymarketGammaClient {

    private final String baseUrl;
    private final RestClient restClient;

    public PolymarketGammaClient(@Value("${polymarket.paths.gamma.markets.rest}") String baseUrl) {
        this.baseUrl = baseUrl;
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    public GammaMarketResponse getMarketBySlug(String slug) {
        String url = baseUrl + "/slug/" + slug;
        log.info("[GAMMA] GET {}", url);

        try {
            String rawBody = restClient.get()
                    .uri("/slug/{slug}", slug)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, resp) -> {
                        byte[] body = resp.getBody().readAllBytes();
                        String bodyStr = new String(body);
                        log.error("[GAMMA] HTTP {} {} - Response: {}", resp.getStatusCode().value(), url, bodyStr);
                        throw new RuntimeException("Gamma HTTP " + resp.getStatusCode().value() + ": " + bodyStr);
                    })
                    .body(String.class);

            log.info("[GAMMA] Response: {}",
                    rawBody != null && rawBody.length() > 500 ? rawBody.substring(0, 500) + "..." : rawBody);

            if (rawBody == null || rawBody.isBlank()) {
                log.warn("[GAMMA] Response vazio para slug={}", slug);
                return null;
            }

            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            GammaMarketResponse response = mapper.readValue(rawBody, GammaMarketResponse.class);

            log.info("[GAMMA] Market encontrado: conditionId={}, outcomes={}, tokenIds={}",
                    response.getConditionId(), response.getOutcomes(), response.getClobTokenIds());

            return response;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("[GAMMA] Erro inesperado slug={}: {} ({})", slug, e.getMessage(), e.getClass().getSimpleName(), e);
            throw new RuntimeException("Gamma API error: " + e.getMessage(), e);
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GammaMarketResponse {
        private String id;
        private String question;
        private String slug;
        private String conditionId;
        @JsonDeserialize(using = JsonStringArrayToListDeserializer.class)
        private List<String> outcomes;
        @JsonDeserialize(using = JsonStringArrayToListDeserializer.class)
        private List<String> clobTokenIds;
        @JsonDeserialize(using = JsonStringArrayToListDeserializer.class)
        private List<String> outcomePrices;  // prices as strings, e.g. ["0.75", "0.25"]
        private String liquidity;
        private String volume;
        private boolean active;
        private boolean closed;
        private String startDate;
        private String endDate;
        @JsonProperty("bestBid")
        private Double bestBid;
        @JsonProperty("bestAsk")
        private Double bestAsk;
    }
}
