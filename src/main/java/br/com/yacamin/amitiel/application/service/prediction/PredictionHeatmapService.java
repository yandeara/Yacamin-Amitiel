package br.com.yacamin.amitiel.application.service.prediction;

import br.com.yacamin.amitiel.adapter.out.persistence.SimEventRepository;
import br.com.yacamin.amitiel.domain.SimEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class PredictionHeatmapService {

    private static final ZoneId SP_ZONE = ZoneId.of("America/Sao_Paulo");

    private static final int[][] CONFIDENCE_BANDS = {
            {0, 40}, {50, 55}, {55, 60}, {60, 65}, {65, 70},
            {70, 75}, {75, 80}, {80, 85}, {85, 90}, {90, 95}, {95, 100}
    };

    private final SimEventRepository simEventRepository;

    public Map<String, Object> getHeatmap(String predictionType, int year, int month, String validFilter) {
        String eventType = resolveEventType(predictionType);
        String hitField = "M2M".equals(predictionType) ? "hit" : "hitResolve";

        LocalDate firstDay = LocalDate.of(year, month, 1);
        int daysInMonth = firstDay.lengthOfMonth();

        long fromMs = firstDay.atStartOfDay(SP_ZONE).toInstant().toEpochMilli();
        long toMs = firstDay.plusMonths(1).atStartOfDay(SP_ZONE).toInstant().toEpochMilli() - 1;

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

        // Classificar cada evento: hora, dia, banda, hit/miss
        // records[bandIndex][hour][day] = {hit, miss}
        int bandCount = CONFIDENCE_BANDS.length;
        int[][][][] counts = new int[bandCount][24][daysInMonth + 1][2]; // [0]=hit, [1]=miss

        for (SimEvent event : events) {
            Map<String, Object> payload = getPayload(event);
            double confidence = getNumber(payload, "confidence");
            double pct = confidence * 100.0;
            Boolean hitValue = getBoolean(payload, hitField);

            if (hitValue == null) continue;

            int bandIdx = findBandIndex(pct);
            if (bandIdx < 0) continue;

            ZonedDateTime zdt = Instant.ofEpochMilli(event.getTimestamp()).atZone(SP_ZONE);
            int hour = zdt.getHour();
            int day = zdt.getDayOfMonth();

            if (hitValue) counts[bandIdx][hour][day][0]++;
            else counts[bandIdx][hour][day][1]++;
        }

        // Montar resposta
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("predictionType", predictionType);
        result.put("year", year);
        result.put("month", month);
        result.put("daysInMonth", daysInMonth);
        if ("BLOCK".equals(predictionType)) {
            result.put("validFilter", validFilter != null ? validFilter : "TODOS");
        }

        // Banda por banda
        List<Map<String, Object>> bandsData = new ArrayList<>();
        for (int b = 0; b < bandCount; b++) {
            Map<String, Object> bandObj = new LinkedHashMap<>();
            bandObj.put("label", CONFIDENCE_BANDS[b][0] + "-" + CONFIDENCE_BANDS[b][1] + "%");
            bandObj.put("from", CONFIDENCE_BANDS[b][0]);
            bandObj.put("to", CONFIDENCE_BANDS[b][1]);

            // Grid: 24 linhas (horas), cada uma com daysInMonth celulas
            List<Map<String, Object>> grid = new ArrayList<>();
            int bandTotalHit = 0, bandTotalMiss = 0;

            for (int h = 0; h < 24; h++) {
                Map<String, Object> hourRow = new LinkedHashMap<>();
                hourRow.put("hour", h);

                List<Map<String, Object>> days = new ArrayList<>();
                int hourHit = 0, hourMiss = 0;

                for (int d = 1; d <= daysInMonth; d++) {
                    int hit = counts[b][h][d][0];
                    int miss = counts[b][h][d][1];
                    int total = hit + miss;

                    Map<String, Object> cell = new LinkedHashMap<>();
                    cell.put("hit", hit);
                    cell.put("miss", miss);
                    cell.put("total", total);
                    cell.put("accuracy", total > 0 ? round2((double) hit / total * 100.0) : 0);
                    days.add(cell);

                    hourHit += hit;
                    hourMiss += miss;
                }

                int hourTotal = hourHit + hourMiss;
                hourRow.put("days", days);
                hourRow.put("totalHit", hourHit);
                hourRow.put("totalMiss", hourMiss);
                hourRow.put("totalCount", hourTotal);
                hourRow.put("totalAccuracy", hourTotal > 0 ? round2((double) hourHit / hourTotal * 100.0) : 0);
                grid.add(hourRow);

                bandTotalHit += hourHit;
                bandTotalMiss += hourMiss;
            }

            // Day totals
            List<Map<String, Object>> dayTotals = new ArrayList<>();
            for (int d = 1; d <= daysInMonth; d++) {
                int dayHit = 0, dayMiss = 0;
                for (int h = 0; h < 24; h++) {
                    dayHit += counts[b][h][d][0];
                    dayMiss += counts[b][h][d][1];
                }
                int dayTotal = dayHit + dayMiss;
                Map<String, Object> dt = new LinkedHashMap<>();
                dt.put("hit", dayHit);
                dt.put("miss", dayMiss);
                dt.put("total", dayTotal);
                dt.put("accuracy", dayTotal > 0 ? round2((double) dayHit / dayTotal * 100.0) : 0);
                dayTotals.add(dt);
            }

            int bandTotal = bandTotalHit + bandTotalMiss;
            bandObj.put("grid", grid);
            bandObj.put("dayTotals", dayTotals);
            bandObj.put("totalHit", bandTotalHit);
            bandObj.put("totalMiss", bandTotalMiss);
            bandObj.put("totalCount", bandTotal);
            bandObj.put("totalAccuracy", bandTotal > 0 ? round2((double) bandTotalHit / bandTotal * 100.0) : 0);
            bandsData.add(bandObj);
        }

        result.put("bands", bandsData);
        return result;
    }

    private String resolveEventType(String predictionType) {
        return switch (predictionType.toUpperCase()) {
            case "HORIZON" -> "PREDICTION_HORIZON_RESOLVED";
            case "BLOCK" -> "PREDICTION_BLOCK_RESOLVED";
            case "M2M" -> "PREDICTION_M2M_RESOLVED";
            default -> throw new IllegalArgumentException("Tipo invalido: " + predictionType);
        };
    }

    private int findBandIndex(double pct) {
        for (int i = 0; i < CONFIDENCE_BANDS.length; i++) {
            int[] band = CONFIDENCE_BANDS[i];
            if (pct >= band[0] && pct < band[1]) return i;
            if (band[0] == 95 && pct == 100.0) return i;
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getPayload(SimEvent event) {
        if (event.getPayload() instanceof Map) return (Map<String, Object>) event.getPayload();
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

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
