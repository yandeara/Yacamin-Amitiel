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
 * Heatmap do preco medio de entrada (share price) por hora x dia do mes.
 * - Sim: BUY_ORDER_RESPONSE do sim_events (fillPrice, fillSize)
 * - Real: WS_TRADE com side="BUY" do events (price, size)
 * Media ponderada por size: sum(price * size) / sum(size).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EntryPriceHeatmapService {

    private static final ZoneId SP_ZONE = ZoneId.of("America/Sao_Paulo");

    private final EventRepository eventRepository;
    private final SimEventRepository simEventRepository;

    public Map<String, Object> getHeatmap(int year, int month, String mode, String algorithm, String marketGroup) {
        LocalDate firstDay = LocalDate.of(year, month, 1);
        int daysInMonth = firstDay.lengthOfMonth();

        long fromMs = firstDay.atStartOfDay(SP_ZONE).toInstant().toEpochMilli();
        long toMs = firstDay.plusMonths(1).atStartOfDay(SP_ZONE).toInstant().toEpochMilli() - 1;

        List<EntryRecord> records = new ArrayList<>();

        if ("real".equals(mode)) {
            List<Event> events = eventRepository.findByTimestampBetweenOrderByTimestampDesc(fromMs, toMs);
            for (Event e : events) {
                if (!"WS_TRADE".equals(e.getType())) continue;
                if (!matchesMarketGroup(e.getSlug(), marketGroup)) continue;
                Map<String, Object> payload = asMap(e.getPayload());
                if (!"BUY".equals(payload.get("side"))) continue;
                double price = getNumber(payload, "price");
                double size = getNumber(payload, "size");
                if (price <= 0 || size <= 0) continue;
                records.add(new EntryRecord(e.getTimestamp(), price, size));
            }
        } else {
            List<SimEvent> events = (algorithm != null && !algorithm.isBlank())
                    ? simEventRepository.findByTypeAndAlgorithmAndTimestampGreaterThanEqual("BUY_ORDER_RESPONSE", algorithm, fromMs).stream()
                            .filter(ev -> ev.getTimestamp() <= toMs).toList()
                    : simEventRepository.findByTypeAndTimestampBetweenOrderByTimestampDesc("BUY_ORDER_RESPONSE", fromMs, toMs);
            for (SimEvent e : events) {
                if (!matchesMarketGroup(e.getSlug(), marketGroup)) continue;
                Map<String, Object> payload = asMap(e.getPayload());
                double price = getNumber(payload, "fillPrice");
                double size = getNumber(payload, "fillSize");
                if (size <= 0) size = getNumber(payload, "grossSize");
                if (price <= 0 || size <= 0) continue;
                records.add(new EntryRecord(e.getTimestamp(), price, size));
            }
        }

        double[][] priceSizeGrid = new double[24][daysInMonth + 1];
        double[][] sizeGrid = new double[24][daysInMonth + 1];
        int[][] tradesGrid = new int[24][daysInMonth + 1];

        for (EntryRecord r : records) {
            ZonedDateTime zdt = Instant.ofEpochMilli(r.timestamp).atZone(SP_ZONE);
            int hour = zdt.getHour();
            int day = zdt.getDayOfMonth();
            priceSizeGrid[hour][day] += r.price * r.size;
            sizeGrid[hour][day] += r.size;
            tradesGrid[hour][day]++;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("year", year);
        result.put("month", month);
        result.put("daysInMonth", daysInMonth);

        List<Map<String, Object>> grid = new ArrayList<>();
        double totalPriceSize = 0, totalSize = 0;
        int totalTrades = 0;

        for (int h = 0; h < 24; h++) {
            Map<String, Object> hourRow = new LinkedHashMap<>();
            hourRow.put("hour", h);

            List<Map<String, Object>> days = new ArrayList<>();
            double hourPriceSize = 0, hourSize = 0;
            int hourTrades = 0;

            for (int d = 1; d <= daysInMonth; d++) {
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("day", d);
                double avg = sizeGrid[h][d] > 0 ? priceSizeGrid[h][d] / sizeGrid[h][d] : 0;
                cell.put("avgPrice", round4(avg));
                cell.put("size", round4(sizeGrid[h][d]));
                cell.put("trades", tradesGrid[h][d]);
                days.add(cell);

                hourPriceSize += priceSizeGrid[h][d];
                hourSize += sizeGrid[h][d];
                hourTrades += tradesGrid[h][d];
            }

            hourRow.put("days", days);
            hourRow.put("avgPrice", round4(hourSize > 0 ? hourPriceSize / hourSize : 0));
            hourRow.put("size", round4(hourSize));
            hourRow.put("trades", hourTrades);
            grid.add(hourRow);

            totalPriceSize += hourPriceSize;
            totalSize += hourSize;
            totalTrades += hourTrades;
        }
        result.put("grid", grid);

        List<Map<String, Object>> dayTotals = new ArrayList<>();
        for (int d = 1; d <= daysInMonth; d++) {
            double dayPriceSize = 0, daySize = 0;
            int dayTrades = 0;
            for (int h = 0; h < 24; h++) {
                dayPriceSize += priceSizeGrid[h][d];
                daySize += sizeGrid[h][d];
                dayTrades += tradesGrid[h][d];
            }
            Map<String, Object> dt = new LinkedHashMap<>();
            dt.put("day", d);
            dt.put("avgPrice", round4(daySize > 0 ? dayPriceSize / daySize : 0));
            dt.put("size", round4(daySize));
            dt.put("trades", dayTrades);
            dayTotals.add(dt);
        }
        result.put("dayTotals", dayTotals);

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("avgPrice", round4(totalSize > 0 ? totalPriceSize / totalSize : 0));
        totals.put("size", round4(totalSize));
        totals.put("trades", totalTrades);
        result.put("totals", totals);

        return result;
    }

    private record EntryRecord(long timestamp, double price, double size) {}

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object payload) {
        if (payload instanceof Map) return (Map<String, Object>) payload;
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
