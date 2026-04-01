package br.com.yacamin.amitiel.application.service.verification;

import br.com.yacamin.amitiel.adapter.out.persistence.RealPnlEventRepository;
import br.com.yacamin.amitiel.adapter.out.rest.polymarket.PolymarketClobClient;
import br.com.yacamin.amitiel.application.service.util.SlugUtils;
import br.com.yacamin.amitiel.adapter.out.rest.polymarket.PolymarketClobClient.ClobTrade;
import br.com.yacamin.amitiel.adapter.out.rest.polymarket.PolymarketGammaClient;
import br.com.yacamin.amitiel.adapter.out.rest.polymarket.PolymarketGammaClient.GammaMarketResponse;
import br.com.yacamin.amitiel.domain.RealPnlEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class VerificationService {

    private static final ZoneId SP_ZONE = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM");

    private final RealPnlEventRepository realRepository;
    private final PolymarketGammaClient gammaClient;
    private final PolymarketClobClient clobClient;

    /**
     * Lista markets reais agrupados por marketUnixTime, com paginacao.
     */
    public Map<String, Object> listMarkets(long fromMs, long toMs, int page, int size) {
        return listMarkets(fromMs, toMs, page, size, null);
    }

    public Map<String, Object> listMarkets(long fromMs, long toMs, int page, int size, String marketGroup) {
        List<RealPnlEvent> events = realRepository.findByTimestampBetweenOrderByTimestampDesc(fromMs, toMs);

        Map<Long, List<RealPnlEvent>> grouped = new LinkedHashMap<>();
        for (RealPnlEvent e : events) {
            if (marketGroup != null && !marketGroup.isBlank()
                    && !SlugUtils.extractMarketGroup(e.getSlug()).equals(marketGroup)) continue;
            grouped.computeIfAbsent(e.getMarketUnixTime(), k -> new ArrayList<>()).add(e);
        }

        List<Long> sortedKeys = new ArrayList<>(grouped.keySet());
        sortedKeys.sort(Comparator.reverseOrder());

        int totalMarkets = sortedKeys.size();
        int fromIndex = Math.min(page * size, totalMarkets);
        int toIndex = Math.min(fromIndex + size, totalMarkets);
        List<Long> pageKeys = sortedKeys.subList(fromIndex, toIndex);

        List<Map<String, Object>> markets = new ArrayList<>();
        for (long unix : pageKeys) {
            markets.add(buildMarketSummary(unix, grouped.get(unix)));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("markets", markets);
        result.put("totalMarkets", totalMarkets);
        result.put("page", page);
        result.put("size", size);
        result.put("totalPages", (int) Math.ceil((double) totalMarkets / size));
        return result;
    }

    /**
     * Retorna apenas dados do banco para um market. Nao consulta APIs externas.
     */
    public Map<String, Object> getMarketDetail(long marketUnixTime) {
        List<RealPnlEvent> dbEvents = realRepository.findByMarketUnixTime(marketUnixTime);

        if (dbEvents.isEmpty()) {
            return Map.of("error", "Nenhum registro encontrado no banco para marketUnixTime=" + marketUnixTime);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("marketUnixTime", marketUnixTime);
        result.put("slug", dbEvents.get(0).getSlug());
        addTimeFields(result, marketUnixTime);
        addDbEntries(result, dbEvents);
        return result;
    }

    /**
     * Consulta Gamma API + CLOB API para verificar trades reais. Chamado sob demanda.
     */
    public Map<String, Object> verifyClobTrades(long marketUnixTime) {
        List<RealPnlEvent> dbEvents = realRepository.findByMarketUnixTime(marketUnixTime);

        if (dbEvents.isEmpty()) {
            return Map.of("error", "Nenhum registro encontrado no banco");
        }

        String slug = dbEvents.get(0).getSlug();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("marketUnixTime", marketUnixTime);
        result.put("slug", slug);

        // DB PnL summary (pra comparar com CLOB)
        double dbTotalPnl = 0;
        double dbTotalSize = 0;
        for (RealPnlEvent e : dbEvents) {
            dbTotalPnl += e.getPnl();
            dbTotalSize += e.getSize();
        }
        result.put("dbTotalPnl", round4(dbTotalPnl));
        result.put("dbTotalSize", round4(dbTotalSize));
        result.put("dbTradeCount", dbEvents.size());

        log.info("[VERIFY] Iniciando verificacao para marketUnixTime={}, slug={}", marketUnixTime, slug);

        // 1. Gamma API → conditionId + tokenIds
        GammaMarketResponse gammaMarket = null;
        try {
            gammaMarket = gammaClient.getMarketBySlug(slug);
            if (gammaMarket == null) {
                String msg = "Gamma retornou null para slug=" + slug + " (market pode nao existir mais)";
                log.warn("[VERIFY] {}", msg);
                result.put("gammaError", msg);
            }
        } catch (Exception e) {
            String msg = e.getMessage();
            log.error("[VERIFY] Gamma API falhou para slug={}: {}", slug, msg, e);
            result.put("gammaError", msg);
        }

        if (gammaMarket != null) {
            log.info("[VERIFY] Gamma OK: conditionId={}, tokenIds={}", gammaMarket.getConditionId(), gammaMarket.getClobTokenIds());
            Map<String, Object> gamma = new LinkedHashMap<>();
            gamma.put("conditionId", gammaMarket.getConditionId());
            gamma.put("outcomes", gammaMarket.getOutcomes());
            gamma.put("clobTokenIds", gammaMarket.getClobTokenIds());
            gamma.put("outcomePrices", gammaMarket.getOutcomePrices());
            gamma.put("active", gammaMarket.isActive());
            gamma.put("closed", gammaMarket.isClosed());
            gamma.put("question", gammaMarket.getQuestion());
            result.put("gammaMarket", gamma);
        }

        // 2. CLOB API → trades reais
        List<ClobTrade> clobTrades = new ArrayList<>();
        if (gammaMarket != null && gammaMarket.getConditionId() != null) {
            try {
                clobTrades = clobClient.getTradesForMarket(gammaMarket.getConditionId());
                log.info("[VERIFY] CLOB retornou {} trades", clobTrades.size());
            } catch (Exception e) {
                String msg = e.getMessage();
                log.error("[VERIFY] CLOB API falhou para conditionId={}: {}", gammaMarket.getConditionId(), msg, e);
                result.put("clobError", msg);
            }
        } else if (gammaMarket == null) {
            result.put("clobError", "Gamma API indisponivel - nao foi possivel obter conditionId");
        } else {
            result.put("clobError", "conditionId null no Gamma response");
        }

        List<Map<String, Object>> clobEntries = new ArrayList<>();
        double clobTotalBuySize = 0, clobTotalSellSize = 0;
        double clobTotalBuyCost = 0, clobTotalSellRevenue = 0;
        double clobTotalFees = 0;

        for (ClobTrade t : clobTrades) {
            Map<String, Object> trade = new LinkedHashMap<>();
            trade.put("id", t.getId());
            trade.put("side", t.getSide());
            trade.put("price", t.getPrice());
            trade.put("size", t.getSize());
            trade.put("status", t.getStatus());
            trade.put("matchTime", t.getMatchTime());
            trade.put("outcome", t.getOutcome());
            trade.put("traderSide", t.getTraderSide());
            trade.put("feeRateBps", t.getFeeRateBps());
            trade.put("transactionHash", t.getTransactionHash());
            trade.put("assetId", t.getAssetId());
            trade.put("title", t.getTitle());

            try {
                double price = Double.parseDouble(t.getPrice());
                double size = Double.parseDouble(t.getSize());
                double notional = price * size;

                // Fee real da Polymarket (crypto):
                //   fee = C * feeRate * p * (1-p)
                //   feeRate = 0.072 (Crypto category)
                // Fees only apply to takers. Makers have 0 fee (+ rebates).
                double fee = 0;
                if (!"MAKER".equals(t.getTraderSide())) {
                    fee = calculateCryptoFee(size, price);
                }
                clobTotalFees += fee;
                trade.put("feeAmount", round5(fee));

                if ("BUY".equals(t.getSide())) {
                    clobTotalBuySize += size;
                    clobTotalBuyCost += notional;
                } else if ("SELL".equals(t.getSide())) {
                    clobTotalSellSize += size;
                    clobTotalSellRevenue += notional;
                }
            } catch (NumberFormatException ignored) {}

            clobEntries.add(trade);
        }

        // 3. Redeems: CLOB nao retorna redeems (resolucao on-chain).
        // Usamos o banco pra saber quais posicoes foram resolvidas.
        // Redeem payout = exitPrice * size (exitPrice=1.0 se ganhou, 0.0 se perdeu)
        double redeemPayout = 0;
        int redeemCount = 0;
        for (RealPnlEvent e : dbEvents) {
            if ("RESOLVED".equals(e.getStatus())) {
                redeemPayout += e.getExitPrice() * e.getSize();
                redeemCount++;
            }
        }

        // Fluxo real de USDC:
        //   - Compras (BUY): saiu dinheiro (-clobTotalBuyCost)
        //   - Vendas (SELL): entrou dinheiro (+clobTotalSellRevenue)
        //   - Redeems: entrou dinheiro (+redeemPayout)
        //   - Fees: saiu dinheiro (-clobTotalFees)
        double totalUsdcFlow = -clobTotalBuyCost + clobTotalSellRevenue + redeemPayout - clobTotalFees;

        // PnL Real = PnL do banco - fees do CLOB
        double pnlReal = dbTotalPnl - clobTotalFees;

        result.put("clobTrades", clobEntries);
        result.put("clobTradeCount", clobTrades.size());
        result.put("clobTotalBuySize", round4(clobTotalBuySize));
        result.put("clobTotalSellSize", round4(clobTotalSellSize));
        result.put("clobTotalBuyCost", round4(clobTotalBuyCost));
        result.put("clobTotalSellRevenue", round4(clobTotalSellRevenue));
        result.put("clobTotalFees", round5(clobTotalFees));
        result.put("redeemPayout", round4(redeemPayout));
        result.put("redeemCount", redeemCount);
        result.put("totalUsdcFlow", round4(totalUsdcFlow));
        result.put("pnlReal", round4(pnlReal));

        return result;
    }

    private void addTimeFields(Map<String, Object> result, long marketUnixTime) {
        String startSP = Instant.ofEpochSecond(marketUnixTime).atZone(SP_ZONE).format(TIME_FMT);
        String endSP = Instant.ofEpochSecond(marketUnixTime + 300).atZone(SP_ZONE).format(TIME_FMT);
        String dateSP = Instant.ofEpochSecond(marketUnixTime).atZone(SP_ZONE).format(DATE_FMT);
        result.put("timeSP", startSP + " - " + endSP);
        result.put("dateSP", dateSP);
    }

    private void addDbEntries(Map<String, Object> result, List<RealPnlEvent> dbEvents) {
        List<Map<String, Object>> dbEntries = new ArrayList<>();
        double dbTotalPnl = 0, dbTotalSize = 0;

        for (RealPnlEvent e : dbEvents) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", e.getId());
            entry.put("timestamp", e.getTimestamp());
            entry.put("outcome", e.getOutcome());
            entry.put("sideClose", e.getSideClose());
            entry.put("status", e.getStatus());
            entry.put("entryPrice", e.getEntryPrice());
            entry.put("exitPrice", e.getExitPrice());
            entry.put("pnl", e.getPnl());
            entry.put("size", e.getSize());
            entry.put("algorithm", e.getAlgorithm());
            entry.put("partialFill", e.isPartialFill());
            entry.put("originalSize", e.getOriginalSize());
            entry.put("tickCount", e.getTickCount());
            entry.put("delta", e.getDelta());
            entry.put("totalFlips", e.getTotalFlips());
            dbEntries.add(entry);
            dbTotalPnl += e.getPnl();
            dbTotalSize += e.getSize();
        }

        result.put("dbEntries", dbEntries);
        result.put("dbTotalPnl", round4(dbTotalPnl));
        result.put("dbTotalSize", round4(dbTotalSize));
        result.put("dbTradeCount", dbEvents.size());
    }

    private Map<String, Object> buildMarketSummary(long unix, List<RealPnlEvent> entries) {
        Map<String, Object> market = new LinkedHashMap<>();
        market.put("marketUnixTime", unix);
        addTimeFields(market, unix);
        market.put("slug", entries.get(0).getSlug());
        market.put("algorithm", entries.get(0).getAlgorithm());

        double totalPnl = 0;
        int wins = 0, losses = 0;
        for (RealPnlEvent e : entries) {
            totalPnl += e.getPnl();
            if (e.getPnl() >= 0) wins++;
            else losses++;
        }

        market.put("trades", entries.size());
        market.put("totalPnl", round4(totalPnl));
        market.put("wins", wins);
        market.put("losses", losses);
        return market;
    }

    /**
     * Calcula fee real da Polymarket para mercados crypto.
     * Formula: fee = C * feeRate * p * (1 - p)
     * Crypto category: feeRate = 0.072
     * Rounded to 5 decimal places, minimum 0.00001 USDC.
     */
    private static final double FEE_RATE = 0.072;

    private double calculateCryptoFee(double shares, double price) {
        double fee = shares * FEE_RATE * price * (1.0 - price);
        fee = Math.round(fee * 100000.0) / 100000.0;
        return Math.max(fee, 0.00001);
    }

    private double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private double round5(double v) {
        return Math.round(v * 100000.0) / 100000.0;
    }
}
