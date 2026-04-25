package br.com.yacamin.amitiel.application.service.prediction;

import br.com.yacamin.amitiel.adapter.out.persistence.SimEventRepository;
import br.com.yacamin.amitiel.domain.SimEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class PredictionAccuracyService {

    private static final int[][] CONFIDENCE_BANDS = {
            {0, 40}, {40, 50},
            {50, 51}, {51, 52}, {52, 53}, {53, 54}, {54, 55},
            {55, 56}, {56, 57}, {57, 58}, {58, 59}, {59, 60},
            {60, 65}, {65, 70}, {70, 75}, {75, 80}, {80, 85}, {85, 90}, {90, 95}, {95, 100}
    };

    private final SimEventRepository simEventRepository;

    /**
     * Calcula acuracia por faixa de confianca para um tipo de predicao.
     *
     * @param predictionType HORIZON, BLOCK ou M2M
     * @param validFilter    apenas para BLOCK: null/TODOS, VALID, INVALID
     */
    public Map<String, Object> getAccuracy(String predictionType, long fromMs, long toMs, String validFilter) {
        String eventType = resolveEventType(predictionType);
        String hitField = "M2M".equals(predictionType) ? "hit" : "hitResolve";

        List<SimEvent> events = simEventRepository
                .findByTypeAndTimestampBetweenOrderByTimestampDesc(eventType, fromMs, toMs);

        // Filtro valid/invalid para BLOCK
        if ("BLOCK".equals(predictionType) && validFilter != null && !validFilter.isBlank()
                && !"TODOS".equalsIgnoreCase(validFilter)) {
            boolean wantValid = "VALID".equalsIgnoreCase(validFilter);
            events = events.stream().filter(e -> {
                Boolean valid = getBoolean(getPayload(e), "valid");
                return valid != null && valid == wantValid;
            }).toList();
        }

        List<Map<String, Object>> bands = new ArrayList<>();
        int totalHit = 0, totalMiss = 0;

        for (int[] band : CONFIDENCE_BANDS) {
            int hit = 0, miss = 0;
            for (SimEvent event : events) {
                Map<String, Object> payload = getPayload(event);
                double confidence = getNumber(payload, "confidence");
                double pct = confidence * 100.0;
                Boolean hitValue = getBoolean(payload, hitField);

                if (hitValue == null) continue;
                if (!inBand(pct, band)) continue;

                if (hitValue) hit++;
                else miss++;
            }

            int total = hit + miss;
            double accuracy = total > 0 ? (double) hit / total * 100.0 : 0;

            Map<String, Object> bandResult = new LinkedHashMap<>();
            bandResult.put("label", band[0] + "-" + band[1] + "%");
            bandResult.put("from", band[0]);
            bandResult.put("to", band[1]);
            bandResult.put("hit", hit);
            bandResult.put("miss", miss);
            bandResult.put("total", total);
            bandResult.put("accuracy", Math.round(accuracy * 100.0) / 100.0);
            bands.add(bandResult);

            totalHit += hit;
            totalMiss += miss;
        }

        int grandTotal = totalHit + totalMiss;
        double overallAccuracy = grandTotal > 0 ? (double) totalHit / grandTotal * 100.0 : 0;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("predictionType", predictionType);
        result.put("totalEvents", grandTotal);
        result.put("totalHit", totalHit);
        result.put("totalMiss", totalMiss);
        result.put("overallAccuracy", Math.round(overallAccuracy * 100.0) / 100.0);
        result.put("bands", bands);
        if ("BLOCK".equals(predictionType)) {
            result.put("validFilter", validFilter != null ? validFilter : "TODOS");
        }
        return result;
    }

    private String resolveEventType(String predictionType) {
        return switch (predictionType.toUpperCase()) {
            case "HORIZON" -> "PREDICTION_HORIZON_RESOLVED";
            case "FASTHORIZON" -> "PREDICTION_FASTHORIZON_RESOLVED";
            case "BLOCK" -> "PREDICTION_BLOCK_RESOLVED";
            case "M2M" -> "PREDICTION_M2M_RESOLVED";
            default -> throw new IllegalArgumentException("Tipo de predicao invalido: " + predictionType);
        };
    }

    private boolean inBand(double pct, int[] band) {
        if (pct == 100.0) return band[1] == 100;
        return pct >= band[0] && pct < band[1];
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getPayload(SimEvent event) {
        if (event.getPayload() instanceof Map) {
            return (Map<String, Object>) event.getPayload();
        }
        return Map.of();
    }

    private double getNumber(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).doubleValue();
        if (val != null) {
            try { return Double.parseDouble(val.toString()); }
            catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }

    private Boolean getBoolean(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Boolean) return (Boolean) val;
        if (val != null) return Boolean.parseBoolean(val.toString());
        return null;
    }
}
