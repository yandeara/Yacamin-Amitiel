package br.com.yacamin.amitiel.application.service.event;

import br.com.yacamin.amitiel.adapter.out.persistence.EventRepository;
import br.com.yacamin.amitiel.adapter.out.rest.polymarket.PolymarketClobClient;
import br.com.yacamin.amitiel.adapter.out.rest.polymarket.PolymarketClobClient.ClobTrade;
import br.com.yacamin.amitiel.adapter.out.rest.polymarket.PolymarketGammaClient;
import br.com.yacamin.amitiel.adapter.out.rest.polymarket.PolymarketGammaClient.GammaMarketResponse;
import br.com.yacamin.amitiel.adapter.out.rest.polymarket.PolymarketRedeemService;
import br.com.yacamin.amitiel.domain.Event;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class EventTimelineService {

    private static final ZoneId SP_ZONE = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM");

    private final EventRepository eventRepository;
    private final PolymarketGammaClient gammaClient;
    private final PolymarketClobClient clobClient;
    private final PolymarketRedeemService redeemService;

    // ─── List Markets ─────────────────────────────────────────────────

    public Map<String, Object> listMarkets(long fromMs, long toMs, int page, int size, String sort, String filter) {
        List<Event> events = eventRepository.findByTimestampBetweenOrderByTimestampDesc(fromMs, toMs);

        Map<String, List<Event>> grouped = new LinkedHashMap<>();
        for (Event e : events) {
            grouped.computeIfAbsent(e.getSlug(), k -> new ArrayList<>()).add(e);
        }

        // Aplicar filtro se solicitado
        if ("pending_reconciliation".equals(filter)) {
            grouped.entrySet().removeIf(entry -> {
                Set<String> types = new HashSet<>();
                for (Event e : entry.getValue()) types.add(e.getType());
                // Manter apenas: tem PNL mas NAO tem RECONCILED
                return !types.contains("PNL") || types.contains("RECONCILED");
            });
        } else if ("active_only".equals(filter)) {
            Set<String> irrelevant = Set.of("RESOLVE", "BLOCK_COMPLETED");
            grouped.entrySet().removeIf(entry -> {
                for (Event e : entry.getValue()) {
                    if (!irrelevant.contains(e.getType())) return false;
                }
                return true;
            });
        }

        List<Map.Entry<String, List<Event>>> entries = new ArrayList<>(grouped.entrySet());
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
        List<Map.Entry<String, List<Event>>> pageEntries = entries.subList(fromIndex, toIndex);

        List<Map<String, Object>> markets = new ArrayList<>();
        for (Map.Entry<String, List<Event>> entry : pageEntries) {
            markets.add(buildMarketSummary(entry.getKey(), entry.getValue()));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("markets", markets);
        result.put("totalMarkets", total);
        result.put("page", page);
        result.put("size", size);
        result.put("totalPages", (int) Math.ceil((double) total / size));
        result.put("filter", filter);
        return result;
    }

    // ─── Timeline ─────────────────────────────────────────────────────

    public Map<String, Object> getTimeline(String slug) {
        List<Event> events = eventRepository.findBySlugOrderByTimestampAsc(slug);

        if (events.isEmpty()) {
            return Map.of("error", "Nenhum evento encontrado para slug=" + slug);
        }

        long marketUnixTime = extractUnixFromSlug(slug);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("slug", slug);
        result.put("marketUnixTime", marketUnixTime);
        addTimeFields(result, marketUnixTime);
        result.put("totalEvents", events.size());

        Map<String, Integer> typeCounts = new LinkedHashMap<>();
        for (Event e : events) {
            typeCounts.merge(e.getType(), 1, Integer::sum);
        }
        result.put("typeCounts", typeCounts);

        List<Map<String, Object>> timeline = new ArrayList<>();
        for (Event e : events) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", e.getId());
            item.put("timestamp", e.getTimestamp());
            item.put("timeSP", formatTimestamp(e.getTimestamp()));
            item.put("type", e.getType());
            item.put("payload", e.getPayload());
            timeline.add(item);
        }
        result.put("timeline", timeline);

        return result;
    }

    // ─── Verify (CLOB reconciliation) ─────────────────────────────────

    /**
     * Reconcilia nossos eventos com trades reais do CLOB da Polymarket.
     *
     * Fluxo:
     * 1. Busca todos os nossos eventos do slug
     * 2. Gamma API → conditionId + tokenIds
     * 3. CLOB API → trades reais
     * 4. Correlaciona: para cada CLOB trade, busca WS_TRADE com mesmo side+price+size
     * 5. Para cada BUY/SELL_ORDER_RESPONSE nosso com success=true, verifica se existe CLOB trade
     * 6. Calcula PnL real com fees
     */
    public Map<String, Object> verify(String slug) {
        List<Event> events = eventRepository.findBySlugOrderByTimestampAsc(slug);

        if (events.isEmpty()) {
            return Map.of("error", "Nenhum evento encontrado para slug=" + slug);
        }

        long marketUnixTime = extractUnixFromSlug(slug);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("slug", slug);
        result.put("marketUnixTime", marketUnixTime);
        addTimeFields(result, marketUnixTime);

        log.info("[EV-VERIFY] Iniciando verificacao para slug={}", slug);

        // ─── Parse nossos eventos ───
        List<Map<String, Object>> ourBuyResponses = new ArrayList<>();
        List<Map<String, Object>> ourSellResponses = new ArrayList<>();
        List<Map<String, Object>> ourWsTrades = new ArrayList<>();
        List<Map<String, Object>> ourOnChain = new ArrayList<>();
        boolean resolved = false;
        String winningOutcome = null;
        Event existingFeesEvent = null;
        Event existingPnlEvent = null;
        boolean alreadyReconciled = false;

        for (Event e : events) {
            Map<String, Object> payload = getPayload(e);

            if ("FEES".equals(e.getType())) { existingFeesEvent = e; continue; }
            if ("PNL".equals(e.getType())) { existingPnlEvent = e; continue; }
            if ("RECONCILED".equals(e.getType())) { alreadyReconciled = true; continue; }

            switch (e.getType()) {
                case "BUY_ORDER_RESPONSE" -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("timestamp", e.getTimestamp());
                    entry.put("orderId", getString(payload, "orderId"));
                    entry.put("success", getBoolean(payload, "success"));
                    entry.put("status", getString(payload, "status"));
                    entry.put("outcome", getString(payload, "outcome"));
                    entry.put("latencyMs", getNumber(payload, "latencyMs"));
                    entry.put("errorMsg", getString(payload, "errorMsg"));
                    ourBuyResponses.add(entry);
                }
                case "SELL_ORDER_RESPONSE" -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("timestamp", e.getTimestamp());
                    entry.put("orderId", getString(payload, "orderId"));
                    entry.put("success", getBoolean(payload, "success"));
                    entry.put("status", getString(payload, "status"));
                    entry.put("outcome", getString(payload, "outcome"));
                    entry.put("attempt", getNumber(payload, "attempt"));
                    entry.put("errorMsg", getString(payload, "errorMsg"));
                    ourSellResponses.add(entry);
                }
                case "WS_TRADE" -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("timestamp", e.getTimestamp());
                    entry.put("status", getString(payload, "status"));
                    entry.put("side", getString(payload, "side"));
                    entry.put("price", getString(payload, "price"));
                    entry.put("size", getString(payload, "size"));
                    entry.put("outcome", getString(payload, "outcome"));
                    entry.put("assetId", getString(payload, "assetId"));
                    entry.put("takerOrderId", getString(payload, "takerOrderId"));
                    entry.put("matchTime", getString(payload, "matchTime"));
                    ourWsTrades.add(entry);
                }
                case "ON_CHAIN" -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("timestamp", e.getTimestamp());
                    entry.put("outcome", getString(payload, "outcome"));
                    entry.put("tokenId", getString(payload, "tokenId"));
                    entry.put("amount", getNumber(payload, "amount"));
                    entry.put("txHash", getString(payload, "txHash"));
                    ourOnChain.add(entry);
                }
                case "RESOLVE" -> {
                    resolved = true;
                    winningOutcome = getString(payload, "winningOutcome");
                }
            }
        }

        // Nossos WS_TRADE CONFIRMED (as confirmacoes reais de trade)
        List<Map<String, Object>> confirmedWsTrades = ourWsTrades.stream()
                .filter(ws -> "CONFIRMED".equals(ws.get("status")))
                .toList();

        Map<String, Object> ourSummary = new LinkedHashMap<>();
        ourSummary.put("totalEvents", events.size());
        ourSummary.put("buyResponses", ourBuyResponses.size());
        ourSummary.put("sellResponses", ourSellResponses.size());
        ourSummary.put("wsTradesConfirmed", confirmedWsTrades.size());
        ourSummary.put("onChainEvents", ourOnChain.size());
        ourSummary.put("resolved", resolved);
        ourSummary.put("winningOutcome", winningOutcome);

        // Redeem status from Uriel events
        boolean hasAwaitingRedeem = events.stream().anyMatch(e -> "AWAITING_REDEEM".equals(e.getType()));
        boolean hasRedeemConfirmed = events.stream().anyMatch(e -> "REDEEM_CONFIRMED".equals(e.getType()));
        boolean hasRedeemFailed = events.stream().anyMatch(e -> "REDEEM_FAILED".equals(e.getType()));
        boolean hasRedeemRequested = events.stream().anyMatch(e -> "REDEEM_REQUESTED".equals(e.getType()));
        String redeemStatus = hasRedeemConfirmed ? "CONFIRMED"
                : hasRedeemFailed ? "FAILED"
                : hasRedeemRequested ? "PROCESSING"
                : hasAwaitingRedeem ? "PENDING"
                : null;
        if (redeemStatus != null) {
            ourSummary.put("redeemStatus", redeemStatus);
        }

        result.put("ourSummary", ourSummary);

        // ─── Gamma API ───
        GammaMarketResponse gammaMarket = null;
        try {
            gammaMarket = gammaClient.getMarketBySlug(slug);
            if (gammaMarket == null) {
                result.put("gammaError", "Gamma retornou null para slug=" + slug);
            }
        } catch (Exception e) {
            log.error("[EV-VERIFY] Gamma falhou slug={}: {}", slug, e.getMessage(), e);
            result.put("gammaError", e.getMessage());
        }

        if (gammaMarket != null) {
            Map<String, Object> gamma = new LinkedHashMap<>();
            gamma.put("conditionId", gammaMarket.getConditionId());
            gamma.put("outcomes", gammaMarket.getOutcomes());
            gamma.put("clobTokenIds", gammaMarket.getClobTokenIds());
            gamma.put("active", gammaMarket.isActive());
            gamma.put("closed", gammaMarket.isClosed());
            gamma.put("question", gammaMarket.getQuestion());
            result.put("gammaMarket", gamma);
        }

        // ─── CLOB API → trades ───
        List<ClobTrade> clobTrades = new ArrayList<>();
        if (gammaMarket != null && gammaMarket.getConditionId() != null) {
            try {
                clobTrades = clobClient.getTradesForMarket(gammaMarket.getConditionId());
                log.info("[EV-VERIFY] CLOB retornou {} trades", clobTrades.size());
            } catch (Exception e) {
                log.error("[EV-VERIFY] CLOB falhou: {}", e.getMessage(), e);
                result.put("clobError", e.getMessage());
            }
        } else if (gammaMarket == null) {
            result.put("clobError", "Gamma indisponivel - sem conditionId");
        } else {
            result.put("clobError", "conditionId null no Gamma");
        }

        // ─── Reconciliacao ───
        // Mapa de WS_TRADE confirmados indexados por (side + price + size) pra matching
        Set<Integer> matchedWsIndices = new HashSet<>();
        Set<Integer> matchedClobIndices = new HashSet<>();

        List<Map<String, Object>> reconciliation = new ArrayList<>();

        // Para cada CLOB trade, tentar encontrar WS_TRADE correspondente
        for (int ci = 0; ci < clobTrades.size(); ci++) {
            ClobTrade ct = clobTrades.get(ci);
            Map<String, Object> rec = new LinkedHashMap<>();

            Map<String, Object> clobEntry = new LinkedHashMap<>();
            clobEntry.put("side", ct.getSide());
            clobEntry.put("price", ct.getPrice());
            clobEntry.put("size", ct.getSize());
            clobEntry.put("status", ct.getStatus());
            clobEntry.put("matchTime", ct.getMatchTime());
            clobEntry.put("outcome", ct.getOutcome());
            clobEntry.put("traderSide", ct.getTraderSide());
            clobEntry.put("transactionHash", ct.getTransactionHash());
            clobEntry.put("assetId", ct.getAssetId());
            rec.put("clobTrade", clobEntry);

            // Busca WS_TRADE correspondente: side + price + size
            int matchIdx = -1;
            for (int wi = 0; wi < confirmedWsTrades.size(); wi++) {
                if (matchedWsIndices.contains(wi)) continue;
                Map<String, Object> ws = confirmedWsTrades.get(wi);

                boolean sideMatch = ct.getSide() != null && ct.getSide().equals(ws.get("side"));
                boolean priceMatch = pricesMatch(ct.getPrice(), (String) ws.get("price"));
                boolean sizeMatch = sizesMatch(ct.getSize(), (String) ws.get("size"));

                if (sideMatch && priceMatch && sizeMatch) {
                    matchIdx = wi;
                    break;
                }
            }

            // Fallback: match by txHash via ON_CHAIN
            if (matchIdx < 0 && ct.getTransactionHash() != null) {
                for (int wi = 0; wi < confirmedWsTrades.size(); wi++) {
                    if (matchedWsIndices.contains(wi)) continue;
                    Map<String, Object> ws = confirmedWsTrades.get(wi);
                    // Checa se tem ON_CHAIN com esse txHash e mesmo side
                    boolean txMatch = ourOnChain.stream().anyMatch(oc ->
                            ct.getTransactionHash().equalsIgnoreCase((String) oc.get("txHash")));
                    boolean sideMatch = ct.getSide() != null && ct.getSide().equals(ws.get("side"));
                    if (txMatch && sideMatch) {
                        matchIdx = wi;
                        break;
                    }
                }
            }

            if (matchIdx >= 0) {
                matchedWsIndices.add(matchIdx);
                matchedClobIndices.add(ci);

                Map<String, Object> ws = confirmedWsTrades.get(matchIdx);
                rec.put("ourEvent", ws);

                // Verificar divergencias
                List<String> divergences = new ArrayList<>();
                if (!pricesMatch(ct.getPrice(), (String) ws.get("price"))) {
                    divergences.add("Price: CLOB=" + ct.getPrice() + " vs Evento=" + ws.get("price"));
                }
                if (!sizesMatch(ct.getSize(), (String) ws.get("size"))) {
                    divergences.add("Size: CLOB=" + ct.getSize() + " vs Evento=" + ws.get("size"));
                }

                rec.put("status", divergences.isEmpty() ? "MATCH" : "DIVERGENCE");
                rec.put("divergences", divergences);
            } else {
                rec.put("ourEvent", null);
                rec.put("status", "CLOB_ONLY");
                rec.put("divergences", List.of("Trade existe no CLOB mas nao encontrado nos nossos eventos WS_TRADE"));
            }

            reconciliation.add(rec);
        }

        // Verificar WS_TRADE que nao tiveram match no CLOB
        // Duplicatas: se um WS_TRADE nao-matched tem mesmo side+price+size+takerOrderId
        // de um WS_TRADE ja matched, e a segunda confirmacao on-chain (normal)
        for (int wi = 0; wi < confirmedWsTrades.size(); wi++) {
            if (matchedWsIndices.contains(wi)) continue;
            Map<String, Object> ws = confirmedWsTrades.get(wi);

            // Checa se e duplicata de um ja matched
            boolean isDuplicate = false;
            for (int mi : matchedWsIndices) {
                Map<String, Object> matched = confirmedWsTrades.get(mi);
                boolean sameSide = Objects.equals(ws.get("side"), matched.get("side"));
                boolean samePrice = pricesMatch((String) ws.get("price"), (String) matched.get("price"));
                boolean sameSize = sizesMatch((String) ws.get("size"), (String) matched.get("size"));
                boolean sameTakerOrderId = ws.get("takerOrderId") != null
                        && ws.get("takerOrderId").equals(matched.get("takerOrderId"));

                if (sameSide && samePrice && sameSize && sameTakerOrderId) {
                    isDuplicate = true;
                    break;
                }
            }

            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("clobTrade", null);
            rec.put("ourEvent", ws);
            if (isDuplicate) {
                rec.put("status", "DUPLICATE");
                rec.put("divergences", List.of("Segunda confirmacao on-chain (duplicata esperada)"));
            } else {
                rec.put("status", "EVENT_ONLY");
                rec.put("divergences", List.of("Evento WS_TRADE confirmado mas nao encontrado no CLOB"));
            }
            reconciliation.add(rec);
        }

        // Verificar BUY/SELL_ORDER_RESPONSE com success=true mas sem WS_TRADE CONFIRMED correspondente
        List<Map<String, Object>> orphanOrders = new ArrayList<>();
        for (Map<String, Object> buy : ourBuyResponses) {
            if (Boolean.TRUE.equals(buy.get("success"))) {
                String orderId = (String) buy.get("orderId");
                boolean hasWsConfirm = confirmedWsTrades.stream()
                        .anyMatch(ws -> orderId != null && orderId.equals(ws.get("takerOrderId")));
                if (!hasWsConfirm) {
                    Map<String, Object> orphan = new LinkedHashMap<>(buy);
                    orphan.put("orderType", "BUY");
                    orphan.put("issue", "BUY matched no CLOB (orderId=" + orderId + ") mas sem WS_TRADE CONFIRMED correspondente");
                    orphanOrders.add(orphan);
                }
            }
        }
        for (Map<String, Object> sell : ourSellResponses) {
            if (Boolean.TRUE.equals(sell.get("success"))) {
                String orderId = (String) sell.get("orderId");
                boolean hasWsConfirm = confirmedWsTrades.stream()
                        .anyMatch(ws -> orderId != null && orderId.equals(ws.get("takerOrderId")));
                if (!hasWsConfirm) {
                    Map<String, Object> orphan = new LinkedHashMap<>(sell);
                    orphan.put("orderType", "SELL");
                    orphan.put("issue", "SELL matched no CLOB (orderId=" + orderId + ") mas sem WS_TRADE CONFIRMED correspondente");
                    orphanOrders.add(orphan);
                }
            }
        }

        result.put("reconciliation", reconciliation);
        result.put("orphanOrders", orphanOrders);

        // ─── Contagem de reconciliacao ───
        long matches = reconciliation.stream().filter(r -> "MATCH".equals(r.get("status"))).count();
        long divergences = reconciliation.stream().filter(r -> "DIVERGENCE".equals(r.get("status"))).count();
        long clobOnly = reconciliation.stream().filter(r -> "CLOB_ONLY".equals(r.get("status"))).count();
        long eventOnly = reconciliation.stream().filter(r -> "EVENT_ONLY".equals(r.get("status"))).count();
        long duplicates = reconciliation.stream().filter(r -> "DUPLICATE".equals(r.get("status"))).count();

        Map<String, Object> recSummary = new LinkedHashMap<>();
        recSummary.put("matches", matches);
        recSummary.put("divergences", divergences);
        recSummary.put("clobOnly", clobOnly);
        recSummary.put("eventOnly", eventOnly);
        recSummary.put("duplicates", duplicates);
        recSummary.put("orphanOrders", orphanOrders.size());
        result.put("reconciliationSummary", recSummary);

        // ─── PnL real com fees ───
        double clobTotalBuyCost = 0, clobTotalSellRevenue = 0;
        double clobTotalBuySize = 0, clobTotalSellSize = 0;
        double clobTotalFees = 0;

        List<Map<String, Object>> clobTradeDetails = new ArrayList<>();
        for (ClobTrade ct : clobTrades) {
            Map<String, Object> td = new LinkedHashMap<>();
            td.put("side", ct.getSide());
            td.put("price", ct.getPrice());
            td.put("size", ct.getSize());
            td.put("outcome", ct.getOutcome());
            td.put("traderSide", ct.getTraderSide());
            td.put("matchTime", ct.getMatchTime());
            td.put("transactionHash", ct.getTransactionHash());

            try {
                double price = Double.parseDouble(ct.getPrice());
                double size = Double.parseDouble(ct.getSize());
                double notional = price * size;

                double fee = 0;
                if (!"MAKER".equals(ct.getTraderSide())) {
                    fee = calculateCryptoFee(size, price);
                }
                clobTotalFees += fee;
                td.put("notional", round4(notional));
                td.put("feeAmount", round4(fee));

                if ("BUY".equals(ct.getSide())) {
                    clobTotalBuySize += size;
                    clobTotalBuyCost += notional;
                } else if ("SELL".equals(ct.getSide())) {
                    clobTotalSellSize += size;
                    clobTotalSellRevenue += notional;
                }
            } catch (NumberFormatException ignored) {
                td.put("notional", 0);
                td.put("feeAmount", 0);
            }
            clobTradeDetails.add(td);
        }

        // Redeem payout: preferir REDEEM_CONFIRMED do Uriel (redeemValue real)
        // Fallback: estimar a partir das shares se resolved e ganhou
        double redeemPayout = 0;
        int redeemCount = 0;
        double expectedRedeemPayout = 0;
        double dustAmount = 0;
        boolean hasDust = false;

        // Tentar ler redeemValue do evento REDEEM_CONFIRMED (Uriel)
        Map<String, Object> redeemConfirmedPayload = events.stream()
                .filter(e -> "REDEEM_CONFIRMED".equals(e.getType()))
                .findFirst()
                .map(this::getPayload)
                .orElse(null);

        // Calcular net shares do outcome vencedor a partir dos CLOB trades
        double winBuySize = 0, winSellSize = 0, winNetShares = 0;
        List<Map<String, Object>> winningTrades = new ArrayList<>();
        if (resolved && winningOutcome != null) {
            for (ClobTrade ct : clobTrades) {
                try {
                    double size = Double.parseDouble(ct.getSize());
                    double price = Double.parseDouble(ct.getPrice());
                    String outcome = ct.getOutcome();
                    if (outcome != null && outcome.equalsIgnoreCase(winningOutcome)) {
                        if ("BUY".equals(ct.getSide())) winBuySize += size;
                        else if ("SELL".equals(ct.getSide())) winSellSize += size;

                        Map<String, Object> wt = new LinkedHashMap<>();
                        wt.put("side", ct.getSide());
                        wt.put("size", round4(size));
                        wt.put("price", round4(price));
                        wt.put("matchTime", ct.getMatchTime());
                        winningTrades.add(wt);
                    }
                } catch (NumberFormatException ignored) {}
            }
            winNetShares = round4(winBuySize - winSellSize);
            if (winNetShares > 0) {
                expectedRedeemPayout = round4(winNetShares * 1.0);
            }
        }

        if (redeemConfirmedPayload != null) {
            redeemPayout = getNumber(redeemConfirmedPayload, "redeemValue");
            redeemCount = 1;

            // Verificar dust: diferenca entre esperado e recebido
            if (expectedRedeemPayout > 0) {
                dustAmount = round4(expectedRedeemPayout - redeemPayout);
                hasDust = Math.abs(dustAmount) >= 0.0001;
            }
        } else if (resolved && winningOutcome != null) {
            // Fallback: estimar a partir dos CLOB trades
            double netShares = clobTotalBuySize - clobTotalSellSize;
            if (netShares > 0) {
                String ourOutcome = confirmedWsTrades.stream()
                        .filter(ws -> "BUY".equals(ws.get("side")))
                        .map(ws -> (String) ws.get("outcome"))
                        .findFirst()
                        .orElse(null);

                if (ourOutcome != null && winningOutcome.equalsIgnoreCase(ourOutcome)) {
                    redeemPayout = netShares * 1.0;
                }
                redeemCount = 1;
            }
        }

        // Fluxo real de USDC
        double totalUsdcFlow = -clobTotalBuyCost + clobTotalSellRevenue + redeemPayout - clobTotalFees;
        double pnlBeforeFees = -clobTotalBuyCost + clobTotalSellRevenue + redeemPayout;

        Map<String, Object> pnl = new LinkedHashMap<>();
        pnl.put("clobTotalBuyCost", round4(clobTotalBuyCost));
        pnl.put("clobTotalBuySize", round4(clobTotalBuySize));
        pnl.put("clobTotalSellRevenue", round4(clobTotalSellRevenue));
        pnl.put("clobTotalSellSize", round4(clobTotalSellSize));
        pnl.put("clobTotalFees", round4(clobTotalFees));
        pnl.put("redeemPayout", round4(redeemPayout));
        pnl.put("redeemCount", redeemCount);
        pnl.put("expectedRedeemPayout", round4(expectedRedeemPayout));
        pnl.put("hasDust", hasDust);
        pnl.put("dustAmount", round4(dustAmount));
        pnl.put("winBuySize", round4(winBuySize));
        pnl.put("winSellSize", round4(winSellSize));
        pnl.put("winNetShares", round4(winNetShares));
        pnl.put("winningOutcome", winningOutcome);
        pnl.put("winningTrades", winningTrades);
        pnl.put("pnlBeforeFees", round4(pnlBeforeFees));
        pnl.put("totalUsdcFlow", round4(totalUsdcFlow));
        pnl.put("clobTradeCount", clobTrades.size());
        result.put("pnl", pnl);
        result.put("clobTrades", clobTradeDetails);
        result.put("alreadyReconciled", alreadyReconciled);

        // ─── Verificacao de FEES/PNL existentes ───
        Map<String, Object> existingCheck = new LinkedHashMap<>();
        existingCheck.put("hasFeesEvent", existingFeesEvent != null);
        existingCheck.put("hasPnlEvent", existingPnlEvent != null);

        if (existingFeesEvent != null) {
            Map<String, Object> fp = getPayload(existingFeesEvent);
            double existingFees = getNumber(fp, "totalFees");
            double existingBuyCost = getNumber(fp, "buyCost");
            double existingSellRevenue = getNumber(fp, "sellRevenue");
            String feesSource = getString(fp, "source");

            boolean feesMatch = valuesMatch(existingFees, round4(clobTotalFees))
                    && valuesMatch(existingBuyCost, round4(clobTotalBuyCost))
                    && valuesMatch(existingSellRevenue, round4(clobTotalSellRevenue));

            existingCheck.put("feesCorrect", feesMatch);
            existingCheck.put("feesSource", feesSource);
            existingCheck.put("feesEventId", existingFeesEvent.getId());
            if (!feesMatch) {
                List<String> diffs = new ArrayList<>();
                if (!valuesMatch(existingFees, round4(clobTotalFees)))
                    diffs.add("totalFees: evento=" + existingFees + " vs calculado=" + round4(clobTotalFees));
                if (!valuesMatch(existingBuyCost, round4(clobTotalBuyCost)))
                    diffs.add("buyCost: evento=" + existingBuyCost + " vs calculado=" + round4(clobTotalBuyCost));
                if (!valuesMatch(existingSellRevenue, round4(clobTotalSellRevenue)))
                    diffs.add("sellRevenue: evento=" + existingSellRevenue + " vs calculado=" + round4(clobTotalSellRevenue));
                existingCheck.put("feesDivergences", diffs);
            }
        }

        if (existingPnlEvent != null) {
            Map<String, Object> pp = getPayload(existingPnlEvent);
            double existingPnlReal = getNumber(pp, "pnlReal");
            String pnlSource = getString(pp, "source");

            boolean pnlMatch = valuesMatch(existingPnlReal, round4(totalUsdcFlow));

            existingCheck.put("pnlCorrect", pnlMatch);
            existingCheck.put("pnlSource", pnlSource);
            existingCheck.put("pnlEventId", existingPnlEvent.getId());
            if (!pnlMatch) {
                existingCheck.put("pnlDivergence",
                        "pnlReal: evento=" + existingPnlReal + " vs calculado=" + round4(totalUsdcFlow));
            }
        }

        boolean existingCorrect = (existingFeesEvent == null || Boolean.TRUE.equals(existingCheck.get("feesCorrect")))
                && (existingPnlEvent == null || Boolean.TRUE.equals(existingCheck.get("pnlCorrect")));
        existingCheck.put("allCorrect", existingCorrect);
        result.put("existingCheck", existingCheck);

        return result;
    }

    private boolean valuesMatch(double a, double b) {
        return Math.abs(a - b) < 0.00015; // tolerancia de meio centesimo de centavo
    }

    // ─── Accept Reconciliation ──────────────────────────────────────

    /**
     * Aceita a conciliacao. Logica:
     * - Se FEES/PNL nao existem: cria
     * - Se FEES/PNL existem mas com valores errados: atualiza (sobrescreve payload)
     * - Se FEES/PNL existem e estao corretos: nao mexe
     * - Em todos os casos: grava RECONCILED (se ainda nao existe)
     *
     * Cada mercado so pode ter 1 FEES, 1 PNL e 1 RECONCILED.
     */
    public Map<String, Object> acceptReconciliation(String slug, double totalFees, double pnlReal,
                                                     double buyCost, double sellRevenue, double redeemPayout,
                                                     int clobTradeCount, double dustAmount) {
        long now = System.currentTimeMillis();
        long marketUnixTime = extractUnixFromSlug(slug);

        List<Event> existing = eventRepository.findBySlugOrderByTimestampAsc(slug);

        // Verifica se ja foi conciliado
        boolean alreadyReconciled = existing.stream()
                .anyMatch(e -> "RECONCILED".equals(e.getType()));
        if (alreadyReconciled) {
            return Map.of("error", "Este mercado ja foi conciliado manualmente",
                    "slug", slug);
        }

        Event existingFees = existing.stream().filter(e -> "FEES".equals(e.getType())).findFirst().orElse(null);
        Event existingPnl = existing.stream().filter(e -> "PNL".equals(e.getType())).findFirst().orElse(null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("slug", slug);

        boolean feesChanged = false;
        boolean pnlChanged = false;

        // ─── FEES: criar ou corrigir ───
        Map<String, Object> feesPayload = new LinkedHashMap<>();
        feesPayload.put("totalFees", round4(totalFees));
        feesPayload.put("clobTradeCount", clobTradeCount);
        feesPayload.put("buyCost", round4(buyCost));
        feesPayload.put("sellRevenue", round4(sellRevenue));
        feesPayload.put("source", "AMITIEL_RECONCILIATION");

        if (existingFees == null) {
            Event feesEvent = Event.builder()
                    .slug(slug).timestamp(now).type("FEES").payload(feesPayload).build();
            eventRepository.save(feesEvent);
            result.put("feesAction", "CREATED");
            result.put("feesEventId", feesEvent.getId());
            feesChanged = true;
        } else {
            Map<String, Object> oldPayload = getPayload(existingFees);
            boolean feesCorrect = valuesMatch(getNumber(oldPayload, "totalFees"), round4(totalFees))
                    && valuesMatch(getNumber(oldPayload, "buyCost"), round4(buyCost))
                    && valuesMatch(getNumber(oldPayload, "sellRevenue"), round4(sellRevenue));

            if (!feesCorrect) {
                existingFees.setPayload(feesPayload);
                existingFees.setTimestamp(now);
                eventRepository.save(existingFees);
                result.put("feesAction", "CORRECTED");
                result.put("feesEventId", existingFees.getId());
                feesChanged = true;
            } else {
                result.put("feesAction", "OK");
                result.put("feesEventId", existingFees.getId());
            }
        }

        // ─── PNL: criar ou corrigir ───
        Map<String, Object> pnlPayload = new LinkedHashMap<>();
        pnlPayload.put("marketUnixTime", marketUnixTime);
        pnlPayload.put("pnlReal", round4(pnlReal));
        pnlPayload.put("totalFees", round4(totalFees));
        pnlPayload.put("buyCost", round4(buyCost));
        pnlPayload.put("sellRevenue", round4(sellRevenue));
        pnlPayload.put("redeemPayout", round4(redeemPayout));
        pnlPayload.put("source", "AMITIEL_RECONCILIATION");

        if (existingPnl == null) {
            Event pnlEvent = Event.builder()
                    .slug(slug).timestamp(now + 1).type("PNL").payload(pnlPayload).build();
            eventRepository.save(pnlEvent);
            result.put("pnlAction", "CREATED");
            result.put("pnlEventId", pnlEvent.getId());
            pnlChanged = true;
        } else {
            Map<String, Object> oldPayload = getPayload(existingPnl);
            boolean pnlCorrect = valuesMatch(getNumber(oldPayload, "pnlReal"), round4(pnlReal));

            if (!pnlCorrect) {
                existingPnl.setPayload(pnlPayload);
                existingPnl.setTimestamp(now + 1);
                eventRepository.save(existingPnl);
                result.put("pnlAction", "CORRECTED");
                result.put("pnlEventId", existingPnl.getId());
                pnlChanged = true;
            } else {
                result.put("pnlAction", "OK");
                result.put("pnlEventId", existingPnl.getId());
            }
        }

        // ─── RECONCILED: sempre grava (marca conciliacao manual) ───
        Map<String, Object> reconciledPayload = new LinkedHashMap<>();
        reconciledPayload.put("marketUnixTime", marketUnixTime);
        reconciledPayload.put("pnlReal", round4(pnlReal));
        reconciledPayload.put("totalFees", round4(totalFees));
        reconciledPayload.put("clobTradeCount", clobTradeCount);
        reconciledPayload.put("feesAction", result.get("feesAction"));
        reconciledPayload.put("pnlAction", result.get("pnlAction"));
        reconciledPayload.put("reconciledBy", "AMITIEL");
        reconciledPayload.put("reconciledAt", Instant.now().atZone(SP_ZONE)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        Event reconciledEvent = Event.builder()
                .slug(slug).timestamp(now + 2).type("RECONCILED").payload(reconciledPayload).build();
        eventRepository.save(reconciledEvent);
        result.put("reconciledEventId", reconciledEvent.getId());

        // ─── DUST: verificar on-chain se realmente existe dust ───
        boolean hasDustEvent = false;
        if (Math.abs(dustAmount) >= 0.0001) {
            // Verificar on-chain se o dust realmente existe
            boolean dustExistsOnChain = checkDustOnChain(slug, existing);

            if (dustExistsOnChain) {
                // Dust real: criar evento DUST como antes
                Map<String, Object> dustPayload = new LinkedHashMap<>();
                dustPayload.put("marketUnixTime", marketUnixTime);
                dustPayload.put("dustAmount", round4(dustAmount));
                dustPayload.put("redeemPayout", round4(redeemPayout));
                dustPayload.put("expectedRedeemPayout", round4(redeemPayout + dustAmount));
                dustPayload.put("source", "AMITIEL_RECONCILIATION");

                Event dustEvent = Event.builder()
                        .slug(slug).timestamp(now + 3).type("DUST").payload(dustPayload).build();
                eventRepository.save(dustEvent);
                result.put("dustEventId", dustEvent.getId());
                result.put("dustAmount", round4(dustAmount));
                hasDustEvent = true;
            } else {
                // Sem dust on-chain: o REDEEM_CONFIRMED tem redeemValue errado.
                // Corrigir o evento original para bater com o esperado.
                double correctedRedeemValue = round4(redeemPayout + dustAmount);
                boolean corrected = correctRedeemConfirmedEvent(slug, existing, correctedRedeemValue);

                if (corrected) {
                    // Recalcular pnlReal com o redeemPayout corrigido
                    double correctedPnlReal = round4(-buyCost + sellRevenue + correctedRedeemValue - totalFees);

                    // Atualizar o evento PNL que acabamos de criar/corrigir
                    if (existingPnl != null) {
                        Map<String, Object> updatedPnlPayload = getPayload(existingPnl);
                        updatedPnlPayload.put("pnlReal", correctedPnlReal);
                        updatedPnlPayload.put("redeemPayout", correctedRedeemValue);
                        existingPnl.setPayload(updatedPnlPayload);
                        eventRepository.save(existingPnl);
                    }

                    result.put("dustCorrected", true);
                    result.put("dustOriginalRedeemValue", round4(redeemPayout));
                    result.put("dustCorrectedRedeemValue", correctedRedeemValue);
                    result.put("dustCorrectedPnlReal", correctedPnlReal);
                    log.info("[EV-ACCEPT] Dust corrigido no REDEEM_CONFIRMED: slug={}, redeemValue {} -> {}",
                            slug, round4(redeemPayout), correctedRedeemValue);
                } else {
                    log.warn("[EV-ACCEPT] Dust detectado mas nao encontrou REDEEM_CONFIRMED para corrigir: slug={}", slug);
                    result.put("dustCorrected", false);
                    result.put("dustWarning", "Sem dust on-chain mas REDEEM_CONFIRMED nao encontrado para correcao");
                }
            }
        }
        result.put("hasDust", hasDustEvent);

        result.put("totalFees", round4(totalFees));
        result.put("pnlReal", round4(pnlReal));
        result.put("changed", feesChanged || pnlChanged);

        log.info("[EV-ACCEPT] Conciliacao slug={}: fees={} ({}), pnl={} ({})",
                slug, round4(totalFees), result.get("feesAction"),
                round4(pnlReal), result.get("pnlAction"));

        return result;
    }

    // ─── Dust on-chain check ─────────────────────────────────────────

    /**
     * Verifica on-chain se existe saldo residual (dust) de conditional tokens.
     * Busca tokenIds no Gamma API e consulta ERC-1155 balance na proxy wallet.
     * BTC UP/DOWN markets sao sempre negRisk=true.
     */
    private boolean checkDustOnChain(String slug, List<Event> events) {
        try {
            GammaMarketResponse gamma = gammaClient.getMarketBySlug(slug);
            if (gamma == null || gamma.getClobTokenIds() == null || gamma.getClobTokenIds().size() < 2) {
                log.warn("[EV-ACCEPT] Gamma indisponivel para verificar dust on-chain: slug={}", slug);
                return true; // Assume dust existe se nao conseguir verificar
            }

            String tokenUpId = gamma.getClobTokenIds().get(0);
            String tokenDownId = gamma.getClobTokenIds().get(1);
            boolean negRisk = slug.contains("btc-updown"); // BTC UP/DOWN = negRisk

            Map<String, Object> balanceCheck = redeemService.queryDustBalance(tokenUpId, tokenDownId, negRisk);
            boolean hasDust = Boolean.TRUE.equals(balanceCheck.get("hasDust"));

            log.info("[EV-ACCEPT] Dust on-chain check: slug={}, hasDust={}, upBalance={}, downBalance={}",
                    slug, hasDust, balanceCheck.get("tokenUpBalance"), balanceCheck.get("tokenDownBalance"));

            return hasDust;

        } catch (Exception e) {
            log.error("[EV-ACCEPT] Erro ao verificar dust on-chain: slug={}, erro={}", slug, e.getMessage());
            return true; // Assume dust existe se deu erro
        }
    }

    /**
     * Corrige o redeemValue no evento REDEEM_CONFIRMED original.
     * Usado quando on-chain mostra saldo 0 mas o evento registrou valor menor que o esperado.
     */
    @SuppressWarnings("unchecked")
    private boolean correctRedeemConfirmedEvent(String slug, List<Event> events, double correctedRedeemValue) {
        Event redeemConfirmed = events.stream()
                .filter(e -> "REDEEM_CONFIRMED".equals(e.getType()))
                .findFirst()
                .orElse(null);

        if (redeemConfirmed == null) return false;

        Map<String, Object> payload;
        if (redeemConfirmed.getPayload() instanceof Map) {
            payload = new LinkedHashMap<>((Map<String, Object>) redeemConfirmed.getPayload());
        } else {
            return false;
        }

        double originalValue = payload.get("redeemValue") instanceof Number
                ? ((Number) payload.get("redeemValue")).doubleValue() : 0;

        payload.put("redeemValueOriginal", round4(originalValue));
        payload.put("redeemValue", round4(correctedRedeemValue));
        payload.put("correctedByAmitiel", true);
        payload.put("correctionReason", "On-chain balance=0, dust era falso positivo do evento");

        redeemConfirmed.setPayload(payload);
        eventRepository.save(redeemConfirmed);

        log.info("[EV-ACCEPT] REDEEM_CONFIRMED corrigido: slug={}, redeemValue {} -> {}",
                slug, round4(originalValue), round4(correctedRedeemValue));

        return true;
    }

    // ─── Matching helpers ─────────────────────────────────────────────

    private boolean pricesMatch(String clobPrice, String eventPrice) {
        if (clobPrice == null || eventPrice == null) return false;
        try {
            double cp = Double.parseDouble(clobPrice);
            double ep = Double.parseDouble(eventPrice);
            return Math.abs(cp - ep) < 0.0001;
        } catch (NumberFormatException e) {
            return clobPrice.equals(eventPrice);
        }
    }

    private boolean sizesMatch(String clobSize, String eventSize) {
        if (clobSize == null || eventSize == null) return false;
        try {
            double cs = Double.parseDouble(clobSize);
            double es = Double.parseDouble(eventSize);
            return Math.abs(cs - es) < 0.001;
        } catch (NumberFormatException e) {
            return clobSize.equals(eventSize);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getPayload(Event event) {
        if (event.getPayload() instanceof Map) {
            return (Map<String, Object>) event.getPayload();
        }
        return Map.of();
    }

    private String getString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : null;
    }

    private boolean getBoolean(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Boolean) return (Boolean) val;
        return "true".equals(String.valueOf(val));
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

    // ─── Fee calculation (same as VerificationService) ────────────────

    private double calculateCryptoFee(double shares, double price) {
        double feeRate = 0.25;
        int exponent = 2;
        double pq = price * (1.0 - price);
        double fee = shares * price * feeRate * Math.pow(pq, exponent);
        fee = Math.round(fee * 10000.0) / 10000.0;
        return Math.max(fee, 0.0001);
    }


    private double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    // ─── Market summary (for list) ────────────────────────────────────

    // ─── Undo Reconciliation ──────────────────────────────────────

    /**
     * Remove eventos de conciliacao do Amitiel (RECONCILED, DUST, e FEES/PNL com source AMITIEL_RECONCILIATION).
     * Eventos do Gabriel (source=GABRIEL) sao preservados.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> undoReconciliation(String slug) {
        List<Event> events = eventRepository.findBySlugOrderByTimestampAsc(slug);

        boolean hasReconciled = events.stream().anyMatch(e -> "RECONCILED".equals(e.getType()));
        if (!hasReconciled) {
            return Map.of("error", "Este mercado nao foi conciliado", "slug", slug);
        }

        List<String> removedIds = new ArrayList<>();
        List<String> removedTypes = new ArrayList<>();

        Set<String> amitielTypes = Set.of("RECONCILED", "DUST");

        for (Event e : events) {
            boolean remove = false;

            if (amitielTypes.contains(e.getType())) {
                remove = true;
            } else if ("FEES".equals(e.getType()) || "PNL".equals(e.getType())) {
                if (e.getPayload() instanceof Map<?, ?> payload) {
                    if ("AMITIEL_RECONCILIATION".equals(payload.get("source"))) {
                        remove = true;
                    }
                }
            }

            if (remove) {
                eventRepository.deleteById(e.getId());
                removedIds.add(e.getId());
                removedTypes.add(e.getType());
                log.info("[EV-UNDO] Removido evento: slug={}, type={}, id={}", slug, e.getType(), e.getId());
            }
        }

        log.info("[EV-UNDO] Conciliacao desfeita: slug={}, removidos={}", slug, removedTypes);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("slug", slug);
        result.put("removedCount", removedIds.size());
        result.put("removedTypes", removedTypes);
        return result;
    }

    private Map<String, Object> buildMarketSummary(String slug, List<Event> events) {
        long marketUnixTime = extractUnixFromSlug(slug);

        Map<String, Object> market = new LinkedHashMap<>();
        market.put("slug", slug);
        market.put("marketUnixTime", marketUnixTime);
        addTimeFields(market, marketUnixTime);
        market.put("totalEvents", events.size());

        Set<String> types = new LinkedHashSet<>();
        for (Event e : events) {
            types.add(e.getType());
        }
        market.put("eventTypes", types);

        boolean hasBuy = types.contains("BUY_ORDER_PLACED");
        boolean hasSell = types.contains("SELL_ORDER_PLACED") || types.contains("MANUAL_CLOSE_PLACED");
        boolean hasResolve = types.contains("RESOLVE");
        boolean hasRedeem = types.contains("REDEEM") || types.contains("AWAITING_REDEEM")
                || types.contains("REDEEM_REQUESTED") || types.contains("REDEEM_CONFIRMED")
                || types.contains("REDEEM_FAILED") || types.contains("REDEEM_CANCELLED");
        boolean hasError = events.stream().anyMatch(e ->
                "BUY_ORDER_RESPONSE".equals(e.getType()) && hasPayloadField(e, "success", false)
                        || "SELL_ORDER_RESPONSE".equals(e.getType()) && hasPayloadField(e, "success", false));
        boolean hasManual = types.contains("MANUAL_CLOSE_PLACED")
                || events.stream().anyMatch(e -> "BUY_ORDER_PLACED".equals(e.getType()) && hasPayloadField(e, "manual", true));

        boolean hasReconciled = types.contains("RECONCILED");
        boolean hasPnl = types.contains("PNL");

        market.put("hasBuy", hasBuy);
        market.put("hasSell", hasSell);
        market.put("hasResolve", hasResolve);
        market.put("hasRedeem", hasRedeem);
        market.put("hasError", hasError);
        market.put("hasManual", hasManual);
        market.put("hasReconciled", hasReconciled);
        market.put("hasPnl", hasPnl);

        return market;
    }

    @SuppressWarnings("unchecked")
    private boolean hasPayloadField(Event event, String field, Object expectedValue) {
        if (event.getPayload() instanceof Map) {
            Object val = ((Map<String, Object>) event.getPayload()).get(field);
            return expectedValue.equals(val);
        }
        return false;
    }

    // ─── Slug / time helpers ──────────────────────────────────────────

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
