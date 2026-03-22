package br.com.yacamin.amitiel.application.service.algoritms.simulation;

import br.com.yacamin.amitiel.adapter.out.persistence.EventRepository;
import br.com.yacamin.amitiel.adapter.out.persistence.RealPnlEventRepository;
import br.com.yacamin.amitiel.adapter.out.persistence.SimEventRepository;
import br.com.yacamin.amitiel.adapter.out.persistence.SimPnlEventRepository;
import br.com.yacamin.amitiel.application.service.algoritms.AlgoCalc;
import br.com.yacamin.amitiel.application.service.util.SlugUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

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

    private final SimPnlEventRepository simRepository;
    private final RealPnlEventRepository realRepository;
    private final EventRepository eventRepository;
    private final SimEventRepository simEventRepository;

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

    @Async("pnlQueryExecutor")
    public CompletableFuture<Map<String, Object>> getPnlHeatmap(int year, int month, String mode, String algorithm) {
        return getPnlHeatmap(year, month, mode, algorithm, null);
    }

    @Async("pnlQueryExecutor")
    public CompletableFuture<Map<String, Object>> getPnlHeatmap(int year, int month, String mode, String algorithm, String marketGroup) {
        ZoneId zone = ZoneId.of("America/Sao_Paulo");

        LocalDate firstDay = LocalDate.of(year, month, 1);
        LocalDate lastDay = firstDay.withDayOfMonth(firstDay.lengthOfMonth());

        long fromMs = firstDay.atStartOfDay(zone).toInstant().toEpochMilli();
        long toMs = lastDay.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();

        List<long[]> events;
        if ("real".equals(mode)) {
            events = realRepository.findByTimestampBetween(fromMs, toMs).stream()
                    .filter(e -> matchesMarketGroup(e.getSlug(), marketGroup))
                    .map(e -> new long[]{e.getTimestamp(), Double.doubleToLongBits(e.getPnl())}).toList();
        } else if (algorithm != null && !algorithm.isBlank()) {
            events = simRepository.findByAlgorithmAndTimestampBetween(algorithm, fromMs, toMs).stream()
                    .filter(e -> matchesMarketGroup(e.getSlug(), marketGroup))
                    .map(e -> new long[]{e.getTimestamp(), Double.doubleToLongBits(e.getPnl())}).toList();
        } else {
            events = simRepository.findByTimestampBetween(fromMs, toMs).stream()
                    .filter(e -> matchesMarketGroup(e.getSlug(), marketGroup))
                    .map(e -> new long[]{e.getTimestamp(), Double.doubleToLongBits(e.getPnl())}).toList();
        }

        int daysInMonth = firstDay.lengthOfMonth();

        double[][] pnlGrid = new double[24][daysInMonth];
        int[][] tradesGrid = new int[24][daysInMonth];
        int[][] winsGrid = new int[24][daysInMonth];

        for (long[] ev : events) {
            ZonedDateTime zdt = Instant.ofEpochMilli(ev[0]).atZone(zone);
            int hour = zdt.getHour();
            int day = zdt.getDayOfMonth() - 1;
            double pnl = Double.longBitsToDouble(ev[1]);

            pnlGrid[hour][day] += pnl;
            tradesGrid[hour][day]++;
            if (pnl >= 0) winsGrid[hour][day]++;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> byHourTotals = new LinkedHashMap<>();
        Map<String, Object> byDayTotals = new LinkedHashMap<>();
        double grandTotal = 0;
        int grandTrades = 0;

        for (int h = 0; h < 24; h++) {
            Map<String, Object> hourRow = new LinkedHashMap<>();
            double hourTotal = 0;
            int hourTrades = 0;
            int hourWins = 0;

            for (int d = 0; d < daysInMonth; d++) {
                if (tradesGrid[h][d] > 0) {
                    Map<String, Object> cell = new LinkedHashMap<>();
                    cell.put("pnl", round4(pnlGrid[h][d]));
                    cell.put("trades", tradesGrid[h][d]);
                    cell.put("wins", winsGrid[h][d]);
                    hourRow.put(String.valueOf(d + 1), cell);
                }
                hourTotal += pnlGrid[h][d];
                hourTrades += tradesGrid[h][d];
                hourWins += winsGrid[h][d];
            }

            if (hourTrades > 0) {
                data.put(String.valueOf(h), hourRow);
                Map<String, Object> ht = new LinkedHashMap<>();
                ht.put("pnl", round4(hourTotal));
                ht.put("trades", hourTrades);
                ht.put("wins", hourWins);
                byHourTotals.put(String.valueOf(h), ht);
            }

            grandTotal += hourTotal;
            grandTrades += hourTrades;
        }

        for (int d = 0; d < daysInMonth; d++) {
            double dayTotal = 0;
            int dayTrades = 0;
            int dayWins = 0;
            for (int h = 0; h < 24; h++) {
                dayTotal += pnlGrid[h][d];
                dayTrades += tradesGrid[h][d];
                dayWins += winsGrid[h][d];
            }
            if (dayTrades > 0) {
                Map<String, Object> dt = new LinkedHashMap<>();
                dt.put("pnl", round4(dayTotal));
                dt.put("trades", dayTrades);
                dt.put("wins", dayWins);
                byDayTotals.put(String.valueOf(d + 1), dt);
            }
        }

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("byHour", byHourTotals);
        totals.put("byDay", byDayTotals);
        totals.put("total", round4(grandTotal));
        totals.put("trades", grandTrades);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("year", year);
        result.put("month", month);
        result.put("daysInMonth", daysInMonth);
        result.put("data", data);
        result.put("totals", totals);

        return CompletableFuture.completedFuture(result);
    }

    @Async("pnlQueryExecutor")
    public CompletableFuture<Map<String, Object>> getFlipsHeatmap(int year, int month, String mode, String algorithm) {
        return getFlipsHeatmap(year, month, mode, algorithm, null);
    }

    @Async("pnlQueryExecutor")
    public CompletableFuture<Map<String, Object>> getFlipsHeatmap(int year, int month, String mode, String algorithm, String marketGroup) {
        ZoneId zone = ZoneId.of("America/Sao_Paulo");

        LocalDate firstDay = LocalDate.of(year, month, 1);
        long fromMs = firstDay.atStartOfDay(zone).toInstant().toEpochMilli();
        long toMs = firstDay.plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli();
        int daysInMonth = firstDay.lengthOfMonth();

        // Busca eventos e extrai timestamp + totalFlips
        // Usa o max de totalFlips por hora×dia (cada trade grava o total acumulado do market)
        int[][] maxFlipsGrid = new int[24][daysInMonth];
        int[][] tradesGrid = new int[24][daysInMonth];

        if ("real".equals(mode)) {
            for (var e : realRepository.findByTimestampBetween(fromMs, toMs)) {
                if (!matchesMarketGroup(e.getSlug(), marketGroup)) continue;
                ZonedDateTime zdt = Instant.ofEpochMilli(e.getTimestamp()).atZone(zone);
                int h = zdt.getHour(), d = zdt.getDayOfMonth() - 1;
                maxFlipsGrid[h][d] = Math.max(maxFlipsGrid[h][d], e.getTotalFlips());
                tradesGrid[h][d]++;
            }
        } else {
            var events = (algorithm != null && !algorithm.isBlank())
                    ? simRepository.findByAlgorithmAndTimestampBetween(algorithm, fromMs, toMs)
                    : simRepository.findByTimestampBetween(fromMs, toMs);
            for (var e : events) {
                if (!matchesMarketGroup(e.getSlug(), marketGroup)) continue;
                ZonedDateTime zdt = Instant.ofEpochMilli(e.getTimestamp()).atZone(zone);
                int h = zdt.getHour(), d = zdt.getDayOfMonth() - 1;
                maxFlipsGrid[h][d] = Math.max(maxFlipsGrid[h][d], e.getTotalFlips());
                tradesGrid[h][d]++;
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> byHourTotals = new LinkedHashMap<>();
        Map<String, Object> byDayTotals = new LinkedHashMap<>();
        int grandMaxFlips = 0, grandTrades = 0;

        for (int h = 0; h < 24; h++) {
            Map<String, Object> hourRow = new LinkedHashMap<>();
            int hourMaxFlips = 0, hourTrades = 0;
            for (int d = 0; d < daysInMonth; d++) {
                if (tradesGrid[h][d] > 0) {
                    hourRow.put(String.valueOf(d + 1), Map.of("flips", maxFlipsGrid[h][d], "trades", tradesGrid[h][d]));
                }
                hourMaxFlips = Math.max(hourMaxFlips, maxFlipsGrid[h][d]);
                hourTrades += tradesGrid[h][d];
            }
            if (hourTrades > 0) {
                data.put(String.valueOf(h), hourRow);
                byHourTotals.put(String.valueOf(h), Map.of("flips", hourMaxFlips, "trades", hourTrades));
            }
            grandMaxFlips = Math.max(grandMaxFlips, hourMaxFlips);
            grandTrades += hourTrades;
        }

        for (int d = 0; d < daysInMonth; d++) {
            int dayMaxFlips = 0, dayTrades = 0;
            for (int h = 0; h < 24; h++) {
                dayMaxFlips = Math.max(dayMaxFlips, maxFlipsGrid[h][d]);
                dayTrades += tradesGrid[h][d];
            }
            if (dayTrades > 0) {
                byDayTotals.put(String.valueOf(d + 1), Map.of("flips", dayMaxFlips, "trades", dayTrades));
            }
        }

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("byHour", byHourTotals);
        totals.put("byDay", byDayTotals);
        totals.put("maxFlips", grandMaxFlips);
        totals.put("trades", grandTrades);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("data", data);
        result.put("totals", totals);

        return CompletableFuture.completedFuture(result);
    }

    /** Retorna PnL por periodo para todos os algoritmos (pagina de comparacao) */
    public Map<String, Object> getComparisonPnl() {
        return getComparisonPnl(null);
    }

    public Map<String, Object> getComparisonPnl(String marketGroup) {
        Map<String, Object> comparison = new LinkedHashMap<>();
        for (AlgoCalc algo : AlgoCalc.values()) {
            comparison.put(algo.name(), getPnlByPeriods("sim", algo.name(), marketGroup));
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

    private double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
