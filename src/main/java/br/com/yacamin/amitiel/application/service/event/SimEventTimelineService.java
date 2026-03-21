package br.com.yacamin.amitiel.application.service.event;

import br.com.yacamin.amitiel.adapter.out.persistence.SimEventRepository;
import br.com.yacamin.amitiel.domain.SimEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class SimEventTimelineService {

    private static final ZoneId SP_ZONE = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM");

    private final SimEventRepository simEventRepository;

    /**
     * Lista mercados simulados agrupados por slug, filtrado por algorithm, com paginacao.
     */
    public Map<String, Object> listMarkets(long fromMs, long toMs, String algorithm,
                                            int page, int size, String sort) {
        List<SimEvent> events = (algorithm != null && !algorithm.isBlank())
                ? simEventRepository.findByAlgorithmAndTimestampBetweenOrderByTimestampDesc(algorithm, fromMs, toMs)
                : simEventRepository.findByTimestampBetweenOrderByTimestampDesc(fromMs, toMs);

        // Agrupa por slug
        Map<String, List<SimEvent>> grouped = new LinkedHashMap<>();
        for (SimEvent e : events) {
            grouped.computeIfAbsent(e.getSlug(), k -> new ArrayList<>()).add(e);
        }

        List<Map.Entry<String, List<SimEvent>>> entries = new ArrayList<>(grouped.entrySet());
        entries.sort((a, b) -> {
            long unixA = extractUnixFromSlug(a.getKey());
            long unixB = extractUnixFromSlug(b.getKey());
            return "asc".equals(sort)
                    ? Long.compare(unixA, unixB)
                    : Long.compare(unixB, unixA);
        });

        int total = entries.size();
        int fromIndex = Math.min(page * size, total);
        int toIndex = Math.min(fromIndex + size, total);
        List<Map.Entry<String, List<SimEvent>>> pageEntries = entries.subList(fromIndex, toIndex);

        List<Map<String, Object>> markets = new ArrayList<>();
        for (Map.Entry<String, List<SimEvent>> entry : pageEntries) {
            markets.add(buildMarketSummary(entry.getKey(), entry.getValue()));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("markets", markets);
        result.put("totalMarkets", total);
        result.put("page", page);
        result.put("size", size);
        result.put("totalPages", (int) Math.ceil((double) total / size));
        return result;
    }

    /**
     * Timeline completa de um slug+algorithm, ordenada por timestamp asc.
     */
    public Map<String, Object> getTimeline(String slug, String algorithm) {
        List<SimEvent> events = simEventRepository.findBySlugAndAlgorithmOrderByTimestampAsc(slug, algorithm);

        if (events.isEmpty()) {
            return Map.of("error", "Nenhum evento encontrado para slug=" + slug + ", algorithm=" + algorithm);
        }

        long marketUnixTime = extractUnixFromSlug(slug);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("slug", slug);
        result.put("algorithm", algorithm);
        result.put("marketUnixTime", marketUnixTime);
        addTimeFields(result, marketUnixTime);
        result.put("totalEvents", events.size());

        Map<String, Integer> typeCounts = new LinkedHashMap<>();
        for (SimEvent e : events) {
            typeCounts.merge(e.getType(), 1, Integer::sum);
        }
        result.put("typeCounts", typeCounts);

        // PnL rapido (do evento PNL se existir)
        events.stream()
                .filter(e -> "PNL".equals(e.getType()))
                .findFirst()
                .ifPresent(pnlEvent -> {
                    Map<String, Object> payload = getPayload(pnlEvent);
                    result.put("pnlReal", getNumber(payload, "pnlReal"));
                    result.put("totalFees", getNumber(payload, "totalFees"));
                });

        List<Map<String, Object>> timeline = new ArrayList<>();
        for (SimEvent e : events) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", e.getId());
            item.put("timestamp", e.getTimestamp());
            item.put("timeSP", formatTimestamp(e.getTimestamp()));
            item.put("type", e.getType());
            item.put("algorithm", e.getAlgorithm());
            item.put("payload", e.getPayload());
            timeline.add(item);
        }
        result.put("timeline", timeline);

        return result;
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    private Map<String, Object> buildMarketSummary(String slug, List<SimEvent> events) {
        long marketUnixTime = extractUnixFromSlug(slug);

        Map<String, Object> market = new LinkedHashMap<>();
        market.put("slug", slug);
        market.put("marketUnixTime", marketUnixTime);
        addTimeFields(market, marketUnixTime);
        market.put("totalEvents", events.size());

        // Algoritmos presentes
        Set<String> algorithms = new LinkedHashSet<>();
        Set<String> types = new LinkedHashSet<>();
        for (SimEvent e : events) {
            algorithms.add(e.getAlgorithm());
            types.add(e.getType());
        }
        market.put("algorithms", algorithms);
        market.put("eventTypes", types);

        boolean hasBuy = types.contains("BUY_ORDER_PLACED");
        boolean hasSell = types.contains("SELL_ORDER_PLACED");
        boolean hasResolve = types.contains("RESOLVE");
        boolean hasPnl = types.contains("PNL");

        market.put("hasBuy", hasBuy);
        market.put("hasSell", hasSell);
        market.put("hasResolve", hasResolve);
        market.put("hasPnl", hasPnl);

        // PnL do evento PNL (se existir)
        events.stream()
                .filter(e -> "PNL".equals(e.getType()))
                .findFirst()
                .ifPresent(pnlEvent -> {
                    Map<String, Object> payload = getPayload(pnlEvent);
                    market.put("pnlReal", getNumber(payload, "pnlReal"));
                });

        return market;
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

    private long extractUnixFromSlug(String slug) {
        if (slug == null) return 0;
        int lastDash = slug.lastIndexOf('-');
        if (lastDash < 0) return 0;
        try {
            return Long.parseLong(slug.substring(lastDash + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void addTimeFields(Map<String, Object> result, long marketUnixTime) {
        if (marketUnixTime <= 0) {
            result.put("timeSP", "--");
            result.put("dateSP", "--");
            return;
        }
        String startSP = Instant.ofEpochSecond(marketUnixTime).atZone(SP_ZONE).format(TIME_FMT);
        String endSP = Instant.ofEpochSecond(marketUnixTime + 300).atZone(SP_ZONE).format(TIME_FMT);
        String dateSP = Instant.ofEpochSecond(marketUnixTime).atZone(SP_ZONE).format(DATE_FMT);
        result.put("timeSP", startSP + " - " + endSP);
        result.put("dateSP", dateSP);
    }

    private String formatTimestamp(long epochMs) {
        return Instant.ofEpochMilli(epochMs)
                .atZone(SP_ZONE)
                .format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"));
    }
}
