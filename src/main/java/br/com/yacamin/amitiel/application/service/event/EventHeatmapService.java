package br.com.yacamin.amitiel.application.service.event;

import br.com.yacamin.amitiel.adapter.out.persistence.EventRepository;
import br.com.yacamin.amitiel.adapter.out.persistence.SimEventRepository;
import br.com.yacamin.amitiel.application.service.util.SlugUtils;
import br.com.yacamin.amitiel.domain.Event;
import br.com.yacamin.amitiel.domain.SimEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

/**
 * Heatmap baseado em eventos PNL (collection events + sim_events).
 * Agrega pnlReal por hora x dia do mes, igual ao heatmap old mas usando a source de eventos.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EventHeatmapService {

    private static final ZoneId SP_ZONE = ZoneId.of("America/Sao_Paulo");

    private final EventRepository eventRepository;
    private final SimEventRepository simEventRepository;

    /**
     * Gera heatmap de PnL a partir de eventos PNL.
     *
     * @param year  ano
     * @param month mes (1-12)
     * @param mode  "real" ou "sim"
     * @param algorithm para sim: "ALPHA", "GAMA" ou null (todos)
     */
    public Map<String, Object> getHeatmap(int year, int month, String mode, String algorithm) {
        return getHeatmap(year, month, mode, algorithm, null);
    }

    public Map<String, Object> getHeatmap(int year, int month, String mode, String algorithm, String marketGroup) {
        LocalDate firstDay = LocalDate.of(year, month, 1);
        int daysInMonth = firstDay.lengthOfMonth();

        long fromMs = firstDay.atStartOfDay(SP_ZONE).toInstant().toEpochMilli();
        long toMs = firstDay.plusMonths(1).atStartOfDay(SP_ZONE).toInstant().toEpochMilli() - 1;

        // Buscar eventos PNL
        List<PnlRecord> records = new ArrayList<>();

        if ("real".equals(mode)) {
            List<Event> events = eventRepository.findByTimestampBetweenOrderByTimestampDesc(fromMs, toMs);
            for (Event e : events) {
                if (!"PNL".equals(e.getType())) continue;
                if (!matchesMarketGroup(e.getSlug(), marketGroup)) continue;
                Map<String, Object> payload = getPayload(e);
                double pnlReal = getNumber(payload, "pnlReal");
                double fees = getNumber(payload, "totalFees");
                records.add(new PnlRecord(e.getTimestamp(), pnlReal, fees));
            }
        } else {
            List<SimEvent> events = (algorithm != null && !algorithm.isBlank())
                    ? simEventRepository.findByAlgorithmAndTimestampBetweenOrderByTimestampDesc(algorithm, fromMs, toMs)
                    : simEventRepository.findByTimestampBetweenOrderByTimestampDesc(fromMs, toMs);
            for (SimEvent e : events) {
                if (!"PNL".equals(e.getType())) continue;
                if (!matchesMarketGroup(e.getSlug(), marketGroup)) continue;
                Map<String, Object> payload = getSimPayload(e);
                double pnlReal = getNumber(payload, "pnlReal");
                double fees = getNumber(payload, "totalFees");
                records.add(new PnlRecord(e.getTimestamp(), pnlReal, fees));
            }
        }

        // Agregar por hora x dia
        // grid[hour][day] = {pnl, fees, trades}
        double[][] pnlGrid = new double[24][daysInMonth + 1];
        double[][] feesGrid = new double[24][daysInMonth + 1];
        int[][] tradesGrid = new int[24][daysInMonth + 1];

        for (PnlRecord r : records) {
            ZonedDateTime zdt = Instant.ofEpochMilli(r.timestamp).atZone(SP_ZONE);
            int hour = zdt.getHour();
            int day = zdt.getDayOfMonth();
            pnlGrid[hour][day] += r.pnlReal;
            feesGrid[hour][day] += r.fees;
            tradesGrid[hour][day]++;
        }

        // Montar resposta no mesmo formato do heatmap old
        // cells[hour][day] = {pnl, fees, trades}
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("year", year);
        result.put("month", month);
        result.put("daysInMonth", daysInMonth);

        // Grid data
        List<Map<String, Object>> grid = new ArrayList<>();
        double totalPnl = 0, totalFees = 0;
        int totalTrades = 0;

        for (int h = 0; h < 24; h++) {
            Map<String, Object> hourRow = new LinkedHashMap<>();
            hourRow.put("hour", h);

            List<Map<String, Object>> days = new ArrayList<>();
            double hourPnl = 0, hourFees = 0;
            int hourTrades = 0;

            for (int d = 1; d <= daysInMonth; d++) {
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("day", d);
                cell.put("pnl", round4(pnlGrid[h][d]));
                cell.put("fees", round4(feesGrid[h][d]));
                cell.put("trades", tradesGrid[h][d]);
                days.add(cell);

                hourPnl += pnlGrid[h][d];
                hourFees += feesGrid[h][d];
                hourTrades += tradesGrid[h][d];
            }

            hourRow.put("days", days);
            hourRow.put("totalPnl", round4(hourPnl));
            hourRow.put("totalFees", round4(hourFees));
            hourRow.put("totalTrades", hourTrades);
            grid.add(hourRow);

            totalPnl += hourPnl;
            totalFees += hourFees;
            totalTrades += hourTrades;
        }

        result.put("grid", grid);

        // Day totals
        List<Map<String, Object>> dayTotals = new ArrayList<>();
        for (int d = 1; d <= daysInMonth; d++) {
            double dayPnl = 0, dayFees = 0;
            int dayTrades = 0;
            for (int h = 0; h < 24; h++) {
                dayPnl += pnlGrid[h][d];
                dayFees += feesGrid[h][d];
                dayTrades += tradesGrid[h][d];
            }
            Map<String, Object> dt = new LinkedHashMap<>();
            dt.put("day", d);
            dt.put("pnl", round4(dayPnl));
            dt.put("fees", round4(dayFees));
            dt.put("trades", dayTrades);
            dayTotals.add(dt);
        }
        result.put("dayTotals", dayTotals);

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("pnl", round4(totalPnl));
        totals.put("fees", round4(totalFees));
        totals.put("trades", totalTrades);
        result.put("totals", totals);

        return result;
    }

    private record PnlRecord(long timestamp, double pnlReal, double fees) {}

    @SuppressWarnings("unchecked")
    private Map<String, Object> getPayload(Event event) {
        if (event.getPayload() instanceof Map) return (Map<String, Object>) event.getPayload();
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getSimPayload(SimEvent event) {
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

    private boolean matchesMarketGroup(String slug, String marketGroup) {
        if (marketGroup == null || marketGroup.isBlank()) return true;
        return SlugUtils.extractMarketGroup(slug).equals(marketGroup);
    }

    private double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
