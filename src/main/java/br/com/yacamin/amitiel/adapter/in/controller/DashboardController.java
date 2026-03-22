package br.com.yacamin.amitiel.adapter.in.controller;

import br.com.yacamin.amitiel.application.service.algoritms.AlgoCalc;
import br.com.yacamin.amitiel.application.service.algoritms.simulation.SimPnlQueryService;
import br.com.yacamin.amitiel.application.service.event.EventTimelineService;
import br.com.yacamin.amitiel.application.service.event.EventHeatmapService;
import br.com.yacamin.amitiel.application.service.event.SimEventTimelineService;
import br.com.yacamin.amitiel.application.service.MarketGroupQueryService;
import br.com.yacamin.amitiel.application.service.verification.VerificationService;
import br.com.yacamin.amitiel.application.service.wallet.WalletBalanceService;
import br.com.yacamin.amitiel.application.service.wallet.AutoSnapshotScheduler;
import br.com.yacamin.amitiel.application.service.config.AmitielConfigService;
import br.com.yacamin.amitiel.application.service.config.AmitielConfigPersistenceService;
import br.com.yacamin.amitiel.adapter.out.rest.polymarket.PolymarketRedeemService;
import br.com.yacamin.amitiel.application.service.wallet.DustRedeemService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private static final ZoneId SP_ZONE = ZoneId.of("America/Sao_Paulo");

    private final SimPnlQueryService simPnlQueryService;
    private final VerificationService verificationService;
    private final EventTimelineService eventTimelineService;
    private final SimEventTimelineService simEventTimelineService;
    private final EventHeatmapService eventHeatmapService;
    private final WalletBalanceService walletBalanceService;
    private final PolymarketRedeemService polymarketRedeemService;
    private final DustRedeemService dustRedeemService;
    private final AmitielConfigService configService;
    private final AmitielConfigPersistenceService configPersistenceService;
    private final AutoSnapshotScheduler autoSnapshotScheduler;
    private final MarketGroupQueryService marketGroupQueryService;

    @GetMapping("/market-groups")
    public List<Map<String, Object>> getMarketGroups() {
        return marketGroupQueryService.listGroups();
    }

    @GetMapping("/sim-pnl")
    public Map<String, Object> getSimPnl(
            @RequestParam(defaultValue = "sim") String mode,
            @RequestParam(required = false) String algorithm,
            @RequestParam(required = false) String marketGroup) {
        return simPnlQueryService.getPnlByPeriods(mode, algorithm, marketGroup);
    }

    @GetMapping("/sim-pnl/heatmap")
    public CompletableFuture<Map<String, Object>> getSimPnlHeatmap(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(defaultValue = "sim") String mode,
            @RequestParam(required = false) String algorithm,
            @RequestParam(required = false) String marketGroup) {
        LocalDate now = LocalDate.now(SP_ZONE);
        int y = year != null ? year : now.getYear();
        int m = month != null ? month : now.getMonthValue();
        return simPnlQueryService.getPnlHeatmap(y, m, mode, algorithm, marketGroup);
    }

    @GetMapping("/sim-comparison")
    public Map<String, Object> getSimComparison(
            @RequestParam(required = false) String marketGroup) {
        return simPnlQueryService.getComparisonPnl(marketGroup);
    }

    @GetMapping("/heatmap")
    public CompletableFuture<Map<String, Object>> getUnifiedHeatmap(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(defaultValue = "sim") String mode,
            @RequestParam(required = false) String algorithm,
            @RequestParam(required = false) String marketGroup) {
        LocalDate now = LocalDate.now(SP_ZONE);
        int y = year != null ? year : now.getYear();
        int m = month != null ? month : now.getMonthValue();

        CompletableFuture<Map<String, Object>> pnlFuture = simPnlQueryService.getPnlHeatmap(y, m, mode, algorithm, marketGroup);
        CompletableFuture<Map<String, Object>> flipsFuture = simPnlQueryService.getFlipsHeatmap(y, m, mode, algorithm, marketGroup);

        return CompletableFuture.allOf(pnlFuture, flipsFuture).thenApply(v -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("year", y);
            result.put("month", m);
            result.put("daysInMonth", LocalDate.of(y, m, 1).lengthOfMonth());
            result.put("pnl", pnlFuture.join());
            result.put("flips", flipsFuture.join());
            return result;
        });
    }

    @GetMapping("/verification/markets")
    public Map<String, Object> getVerificationMarkets(
            @RequestParam String date,
            @RequestParam(defaultValue = "0") int hourFrom,
            @RequestParam(defaultValue = "23") int hourTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String marketGroup) {

        LocalDate day = LocalDate.parse(date);
        long fromMs = day.atTime(LocalTime.of(hourFrom, 0)).atZone(SP_ZONE).toInstant().toEpochMilli();
        long toMs = day.atTime(LocalTime.of(hourTo, 59, 59)).atZone(SP_ZONE).toInstant().toEpochMilli();

        return verificationService.listMarkets(fromMs, toMs, page, size, marketGroup);
    }

    @GetMapping("/verification/detail")
    public Map<String, Object> getVerificationDetail(@RequestParam long marketUnixTime) {
        return verificationService.getMarketDetail(marketUnixTime);
    }

    @PostMapping("/verification/verify")
    public Map<String, Object> verifyClobTrades(@RequestParam long marketUnixTime) {
        return verificationService.verifyClobTrades(marketUnixTime);
    }

    @GetMapping("/events/markets")
    public Map<String, Object> getEventMarkets(
            @RequestParam String dateFrom,
            @RequestParam String dateTo,
            @RequestParam(defaultValue = "0") int hourFrom,
            @RequestParam(defaultValue = "23") int hourTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "desc") String sort,
            @RequestParam(required = false) String filter,
            @RequestParam(required = false) String marketGroup) {

        LocalDate dayFrom = LocalDate.parse(dateFrom);
        LocalDate dayTo = LocalDate.parse(dateTo);
        long fromMs = dayFrom.atTime(LocalTime.of(hourFrom, 0)).atZone(SP_ZONE).toInstant().toEpochMilli();
        long toMs = dayTo.atTime(LocalTime.of(hourTo, 59, 59)).atZone(SP_ZONE).toInstant().toEpochMilli();

        return eventTimelineService.listMarkets(fromMs, toMs, page, size, sort, filter, marketGroup);
    }

    @GetMapping("/events/timeline")
    public Map<String, Object> getEventTimeline(@RequestParam String slug) {
        return eventTimelineService.getTimeline(slug);
    }

    @PostMapping("/events/verify")
    public Map<String, Object> verifyEvents(@RequestParam String slug) {
        return eventTimelineService.verify(slug);
    }

    @PostMapping("/events/accept")
    public Map<String, Object> acceptReconciliation(@RequestBody Map<String, Object> body) {
        String slug = (String) body.get("slug");
        double totalFees = ((Number) body.get("totalFees")).doubleValue();
        double pnlReal = ((Number) body.get("pnlReal")).doubleValue();
        double buyCost = ((Number) body.get("buyCost")).doubleValue();
        double sellRevenue = ((Number) body.get("sellRevenue")).doubleValue();
        double redeemPayout = ((Number) body.get("redeemPayout")).doubleValue();
        int clobTradeCount = ((Number) body.get("clobTradeCount")).intValue();
        double dustAmount = body.get("dustAmount") != null ? ((Number) body.get("dustAmount")).doubleValue() : 0;

        return eventTimelineService.acceptReconciliation(slug, totalFees, pnlReal,
                buyCost, sellRevenue, redeemPayout, clobTradeCount, dustAmount);
    }

    @PostMapping("/events/undo-reconciliation")
    public Map<String, Object> undoReconciliation(@RequestParam String slug) {
        return eventTimelineService.undoReconciliation(slug);
    }

    // ─── Sim Events ───────────────────────────────────────────────

    @GetMapping("/sim-events/markets")
    public Map<String, Object> getSimEventMarkets(
            @RequestParam String dateFrom,
            @RequestParam String dateTo,
            @RequestParam(defaultValue = "0") int hourFrom,
            @RequestParam(defaultValue = "23") int hourTo,
            @RequestParam(required = false) String algorithm,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "desc") String sort,
            @RequestParam(required = false) String filter,
            @RequestParam(required = false) String marketGroup) {

        LocalDate dayFrom = LocalDate.parse(dateFrom);
        LocalDate dayTo = LocalDate.parse(dateTo);
        long fromMs = dayFrom.atTime(LocalTime.of(hourFrom, 0)).atZone(SP_ZONE).toInstant().toEpochMilli();
        long toMs = dayTo.atTime(LocalTime.of(hourTo, 59, 59)).atZone(SP_ZONE).toInstant().toEpochMilli();

        return simEventTimelineService.listMarkets(fromMs, toMs, algorithm, page, size, sort, filter, marketGroup);
    }

    @GetMapping("/sim-events/timeline")
    public Map<String, Object> getSimEventTimeline(
            @RequestParam String slug,
            @RequestParam String algorithm) {
        return simEventTimelineService.getTimeline(slug, algorithm);
    }

    // ─── Event Heatmap ────────────────────────────────────────────

    @GetMapping("/event-heatmap")
    public Map<String, Object> getEventHeatmap(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(defaultValue = "sim") String mode,
            @RequestParam(required = false) String algorithm,
            @RequestParam(required = false) String marketGroup) {
        LocalDate now = LocalDate.now(SP_ZONE);
        int y = year != null ? year : now.getYear();
        int m = month != null ? month : now.getMonthValue();
        return eventHeatmapService.getHeatmap(y, m, mode, algorithm, marketGroup);
    }

    // ─── Wallet Balance ──────────────────────────────────────────

    @GetMapping("/wallet/snapshots")
    public Map<String, Object> getWalletSnapshots(
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @RequestParam(defaultValue = "0") int hourFrom,
            @RequestParam(defaultValue = "23") int hourTo,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        long fromMs = 0;
        long toMs = Long.MAX_VALUE;
        if (dateFrom != null && dateTo != null) {
            LocalDate dayFrom = LocalDate.parse(dateFrom);
            LocalDate dayTo = LocalDate.parse(dateTo);
            fromMs = dayFrom.atTime(LocalTime.of(hourFrom, 0)).atZone(SP_ZONE).toInstant().toEpochMilli();
            toMs = dayTo.atTime(LocalTime.of(hourTo, 59, 59)).atZone(SP_ZONE).toInstant().toEpochMilli();
        }

        return walletBalanceService.listSnapshots(fromMs, toMs, type, page, size);
    }

    @PostMapping("/wallet/snapshot")
    public Map<String, Object> takeWalletSnapshot() {
        return walletBalanceService.takeSnapshot();
    }

    @PostMapping("/wallet/reset-baseline")
    public Map<String, Object> resetWalletBaseline() {
        return walletBalanceService.resetBaseline();
    }

    @GetMapping("/wallet/export-pnl")
    public Map<String, Object> exportPnlEvents() {
        return walletBalanceService.exportPnlChain();
    }

    @PostMapping("/wallet/recalculate-pnl")
    public Map<String, Object> recalculateSystemPnl() {
        return walletBalanceService.recalculateSystemPnl();
    }

    @GetMapping("/wallet/auto-status")
    public Map<String, Object> getAutoSnapshotStatus() {
        return autoSnapshotScheduler.getAutoStatus();
    }

    // ─── Config ───────────────────────────────────────────────────

    @GetMapping("/config")
    public Map<String, Object> getConfig() {
        return configService.getConfigMap();
    }

    @PostMapping("/config")
    public Map<String, Object> updateConfig(@RequestBody Map<String, Object> body) {
        if (body.containsKey("autoSnapshotEnabled")) {
            configService.setAutoSnapshotEnabled(Boolean.TRUE.equals(body.get("autoSnapshotEnabled")));
        }
        if (body.containsKey("autoSnapshotIntervalMinutes")) {
            int val = ((Number) body.get("autoSnapshotIntervalMinutes")).intValue();
            if (val >= 1 && val <= 1440) {
                configService.setAutoSnapshotIntervalMinutes(val);
            }
        }
        try {
            configPersistenceService.save();
        } catch (Exception e) {
            // Config salva em memoria, falha de persistencia nao bloqueia
        }
        return configService.getConfigMap();
    }

    // ─── Dust Redeem ─────────────────────────────────────────────

    @PostMapping("/dust/redeem")
    public Map<String, Object> redeemDust(@RequestBody Map<String, Object> body) {
        String slug = (String) body.get("slug");
        String conditionId = (String) body.get("conditionId");
        String tokenUpId = (String) body.get("tokenUpId");
        String tokenDownId = (String) body.get("tokenDownId");
        boolean negRisk = Boolean.TRUE.equals(body.get("negRisk"));

        return dustRedeemService.submitDustRedeem(slug, conditionId, tokenUpId, tokenDownId, negRisk);
    }

    @PostMapping("/dust/check")
    public Map<String, Object> checkDustRedeemStatus(@RequestBody Map<String, Object> body) {
        String slug = (String) body.get("slug");
        String tokenUpId = (String) body.get("tokenUpId");
        String tokenDownId = (String) body.get("tokenDownId");
        boolean negRisk = Boolean.TRUE.equals(body.get("negRisk"));

        return dustRedeemService.checkDustRedeemStatus(slug, tokenUpId, tokenDownId, negRisk);
    }

    @PostMapping("/dust/query")
    public Map<String, Object> queryDustBalance(@RequestBody Map<String, Object> body) {
        String tokenUpId = (String) body.get("tokenUpId");
        String tokenDownId = (String) body.get("tokenDownId");
        boolean negRisk = Boolean.TRUE.equals(body.get("negRisk"));

        return polymarketRedeemService.queryDustBalance(tokenUpId, tokenDownId, negRisk);
    }
}
