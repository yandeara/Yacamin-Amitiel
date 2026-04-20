package br.com.yacamin.amitiel.application.service.algoritms.simulation;

import br.com.yacamin.amitiel.adapter.out.persistence.EventRepository;
import br.com.yacamin.amitiel.adapter.out.persistence.SimEventRepository;
import br.com.yacamin.amitiel.application.service.AlgorithmQueryService;
import br.com.yacamin.amitiel.application.service.util.SlugUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
public class SimPnlQueryService {

    private static final long MINUTE_MS = 60_000L;

    private static final Map<String, Long> PERIODS = Map.of(
            "15m", 15 * MINUTE_MS,
            "30m", 30 * MINUTE_MS,
            "1h", 60 * MINUTE_MS,
            "3h", 180 * MINUTE_MS,
            "6h", 360 * MINUTE_MS,
            "12h", 720 * MINUTE_MS,
            "24h", 1440 * MINUTE_MS
    );

    private static final String[] PERIOD_KEYS = {"15m", "30m", "1h", "3h", "6h", "12h", "24h"};

    private final EventRepository eventRepository;
    private final SimEventRepository simEventRepository;
    private final AlgorithmQueryService algorithmQueryService;

    public Map<String, Object> getPnlByPeriods(String mode, String algorithm) {
        return getPnlByPeriods(mode, algorithm, null);
    }

    public Map<String, Object> getPnlByPeriods(String mode, String algorithm, String marketGroup) {
        long now = System.currentTimeMillis();
        long oldest = now - PERIODS.get("24h");

        // Extract PNL records from events/sim_events (type="PNL")
        // long[2]: timestamp, pnlReal (bits)
        List<long[]> pnlRecords;
        if ("real".equals(mode)) {
            pnlRecords = eventRepository.findByTypeAndTimestampGreaterThanEqual("PNL", oldest).stream()
                    .filter(e -> matchesMarketGroup(e.getSlug(), marketGroup))
                    .map(e -> new long[]{e.getTimestamp(), Double.doubleToLongBits(getPayloadNumber(e.getPayload(), "pnlReal"))})
                    .toList();
        } else if (algorithm != null && !algorithm.isBlank()) {
            pnlRecords = simEventRepository.findByTypeAndAlgorithmAndTimestampGreaterThanEqual("PNL", algorithm, oldest).stream()
                    .filter(e -> matchesMarketGroup(e.getSlug(), marketGroup))
                    .map(e -> new long[]{e.getTimestamp(), Double.doubleToLongBits(getPayloadNumber(e.getPayload(), "pnlReal"))})
                    .toList();
        } else {
            pnlRecords = simEventRepository.findByTypeAndTimestampGreaterThanEqual("PNL", oldest).stream()
                    .filter(e -> matchesMarketGroup(e.getSlug(), marketGroup))
                    .map(e -> new long[]{e.getTimestamp(), Double.doubleToLongBits(getPayloadNumber(e.getPayload(), "pnlReal"))})
                    .toList();
        }

        Map<String, Object> result = new LinkedHashMap<>();

        for (String key : PERIOD_KEYS) {
            long cutoff = now - PERIODS.get(key);

            double totalPnl = 0;
            int trades = 0;
            int wins = 0;
            int losses = 0;

            for (long[] rec : pnlRecords) {
                if (rec[0] >= cutoff) {
                    double pnl = Double.longBitsToDouble(rec[1]);
                    totalPnl += pnl;
                    trades++;
                    if (pnl >= 0) wins++;
                    else losses++;
                }
            }

            Map<String, Object> period = new LinkedHashMap<>();
            period.put("pnl", Math.round(totalPnl * 10000.0) / 10000.0);
            period.put("trades", trades);
            period.put("wins", wins);
            period.put("losses", losses);
            period.put("winRate", trades > 0 ? Math.round((double) wins / trades * 1000.0) / 1000.0 : 0);

            result.put(key, period);
        }

        return result;
    }

    public Map<String, Object> getSimPnlPeriods(String algorithm) {
        Map<String, Object> full = getPnlByPeriods("sim", algorithm);
        Map<String, Object> subset = new LinkedHashMap<>();
        for (String key : new String[]{"15m", "30m", "1h", "24h"}) {
            Object period = full.get(key);
            if (period instanceof Map<?,?> map) {
                Map<String, Object> slim = new LinkedHashMap<>();
                slim.put("pnl", map.get("pnl"));
                slim.put("trades", map.get("trades"));
                subset.put(key, slim);
            }
        }
        return subset;
    }

    /** Retorna PnL por periodo para todos os algoritmos (pagina de comparacao) */
    public Map<String, Object> getComparisonPnl() {
        return getComparisonPnl(null);
    }

    public Map<String, Object> getComparisonPnl(String marketGroup) {
        Map<String, Object> comparison = new LinkedHashMap<>();
        for (String algoName : algorithmQueryService.listAllNames()) {
            comparison.put(algoName, getPnlByPeriods("sim", algoName, marketGroup));
        }
        return comparison;
    }

    @SuppressWarnings("unchecked")
    private double getPayloadNumber(Object payload, String key) {
        if (payload instanceof Map) {
            Object val = ((Map<String, Object>) payload).get(key);
            if (val instanceof Number) return ((Number) val).doubleValue();
            if (val != null) {
                try { return Double.parseDouble(val.toString()); }
                catch (NumberFormatException e) { return 0; }
            }
        }
        return 0;
    }

    private boolean matchesMarketGroup(String slug, String marketGroup) {
        if (marketGroup == null || marketGroup.isBlank()) return true;
        return SlugUtils.extractMarketGroup(slug).equals(marketGroup);
    }
}
