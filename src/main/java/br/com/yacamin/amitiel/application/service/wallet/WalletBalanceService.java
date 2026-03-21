package br.com.yacamin.amitiel.application.service.wallet;

import br.com.yacamin.amitiel.adapter.out.persistence.WalletSnapshotRepository;
import br.com.yacamin.amitiel.adapter.out.rest.polymarket.ClobAuthSigner;
import br.com.yacamin.amitiel.domain.WalletSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Keys;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.http.HttpService;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class WalletBalanceService {

    private static final String USDC_E_CONTRACT = "0x2791Bca1f2de4661ED88A30C99A7a9449Aa84174";
    private static final String USDC_NATIVE_CONTRACT = "0x3c499c542cEF5E3811e1192ce70d8cC03d5c3359";
    private static final String POLYGON_RPC = "https://polygon-bor-rpc.publicnode.com";
    private static final ZoneId SP_ZONE = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private final WalletSnapshotRepository snapshotRepository;
    private final MongoTemplate mongoTemplate;
    private final ClobAuthSigner authSigner;
    private final ObjectMapper objectMapper;

    @Value("${polymarket.wallet-address}")
    private String walletAddress;

    @Value("${polymarket.paths.clob.rest}")
    private String clobBaseUrl;

    // ─── List snapshots ──────────────────────────────────────────

    public Map<String, Object> listSnapshots(long fromMs, long toMs, String type, int page, int size) {
        // Query com filtro de timestamp
        Criteria criteria = new Criteria();
        if (fromMs > 0 || toMs < Long.MAX_VALUE) {
            criteria = criteria.and("timestamp").gte(fromMs).lte(toMs);
        }

        // Filtro por tipo
        if ("baseline".equals(type)) {
            criteria = criteria.and("baseline").is(true);
        } else if ("divergent".equals(type)) {
            criteria = new Criteria().andOperator(
                    criteria,
                    Criteria.where("baseline").is(false),
                    new Criteria().orOperator(
                            Criteria.where("divergence").gt(0.0009),
                            Criteria.where("divergence").lt(-0.0009)
                    )
            );
        } else if ("ok".equals(type)) {
            criteria = criteria.and("baseline").is(false)
                    .and("divergence").gte(-0.0009).lte(0.0009);
        }

        long total = mongoTemplate.count(
                org.springframework.data.mongodb.core.query.Query.query(criteria), WalletSnapshot.class);

        List<WalletSnapshot> snapshots = mongoTemplate.find(
                org.springframework.data.mongodb.core.query.Query.query(criteria)
                        .with(org.springframework.data.domain.Sort.by(
                                org.springframework.data.domain.Sort.Direction.DESC, "timestamp"))
                        .skip((long) page * size)
                        .limit(size),
                WalletSnapshot.class);

        // Sempre incluir o latest para o card de destaque (so na primeira pagina sem filtros)
        WalletSnapshot latest = null;
        if (page == 0) {
            Optional<WalletSnapshot> latestOpt = snapshotRepository.findFirstByOrderByTimestampDesc();
            latest = latestOpt.orElse(null);
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (WalletSnapshot s : snapshots) {
            items.add(formatSnapshot(s));
        }

        int totalPages = (int) Math.ceil((double) total / size);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("snapshots", items);
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        result.put("totalPages", totalPages);
        result.put("walletAddress", Keys.toChecksumAddress(walletAddress));
        if (latest != null) {
            result.put("latest", formatSnapshot(latest));
        }
        return result;
    }

    // ─── Take new snapshot ───────────────────────────────────────

    public Map<String, Object> takeSnapshot() {
        long now = System.currentTimeMillis();
        String proxyWallet = Keys.toChecksumAddress(walletAddress);

        log.info("[WALLET] Consultando saldos para proxy wallet: {}", proxyWallet);

        // 1. Query on-chain USDC balances
        double usdcE = queryErc20Balance(proxyWallet, USDC_E_CONTRACT, "USDC.e");
        double usdcNative = queryErc20Balance(proxyWallet, USDC_NATIVE_CONTRACT, "USDC native");
        double totalOnChain = round4(usdcE + usdcNative);

        // 2. Query CLOB balance (deposited on Polymarket)
        double clobBalance = queryClobBalance();

        // 3. Calculate system PnL from reconciled events
        double systemPnl = calculateSystemPnl();

        // 4. Find baseline snapshot
        Optional<WalletSnapshot> baselineOpt = snapshotRepository.findFirstByBaselineTrueOrderByTimestampDesc();
        boolean isBaseline = baselineOpt.isEmpty();

        double systemPnlDelta = 0;
        double expectedBalance = clobBalance; // default for baseline
        double divergence = 0;
        String baselineId = null;

        if (!isBaseline) {
            WalletSnapshot baseline = baselineOpt.get();
            baselineId = baseline.getId();

            systemPnlDelta = round4(systemPnl - baseline.getSystemPnl());
            expectedBalance = round4(baseline.getClobBalance() + systemPnlDelta);
            divergence = round4(clobBalance - expectedBalance);
        }

        // 5. Save snapshot
        WalletSnapshot snapshot = WalletSnapshot.builder()
                .timestamp(now)
                .usdcE(usdcE)
                .usdcNative(usdcNative)
                .clobBalance(clobBalance)
                .totalOnChain(totalOnChain)
                .systemPnl(systemPnl)
                .systemPnlDelta(systemPnlDelta)
                .expectedBalance(expectedBalance)
                .actualBalance(clobBalance)
                .divergence(divergence)
                .baseline(isBaseline)
                .baselineId(baselineId)
                .build();

        snapshotRepository.save(snapshot);
        log.info("[WALLET] Snapshot salvo: baseline={}, clob={}, systemPnl={}, divergence={}",
                isBaseline, clobBalance, systemPnl, divergence);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("snapshot", formatSnapshot(snapshot));
        return result;
    }

    // ─── Reset baseline ──────────────────────────────────────────

    public Map<String, Object> resetBaseline() {
        // Mark all existing baselines as non-baseline
        List<WalletSnapshot> all = snapshotRepository.findAllByOrderByTimestampDesc();
        for (WalletSnapshot s : all) {
            if (s.isBaseline()) {
                s.setBaseline(false);
                snapshotRepository.save(s);
            }
        }

        // Take a new snapshot as the new baseline
        return takeSnapshot();
    }

    // ─── System PnL calculation ──────────────────────────────────

    /**
     * Calcula PnL incremental: ultimo snapshot + soma dos PNL events desde entao.
     * Se nao existir snapshot anterior, faz calculo completo.
     */
    private double calculateSystemPnl() {
        Optional<WalletSnapshot> lastOpt = snapshotRepository.findFirstByOrderByTimestampDesc();
        if (lastOpt.isPresent()) {
            WalletSnapshot last = lastOpt.get();
            double delta = aggregatePnlSince(last.getTimestamp());
            double total = round4(last.getSystemPnl() + delta);
            log.info("[WALLET] SystemPnl incremental: base={} + delta={} = {}", last.getSystemPnl(), delta, total);
            return total;
        }
        return calculateSystemPnlFull();
    }

    /**
     * Calcula PnL completo desde o inicio via aggregation pipeline no MongoDB.
     * $match type=PNL → $group $sum payload.pnlReal
     */
    public double calculateSystemPnlFull() {
        double total = aggregatePnlSince(0);
        log.info("[WALLET] SystemPnl full recalc: {}", total);
        return total;
    }

    /**
     * MongoDB aggregation: soma payload.pnlReal de todos os eventos PNL com timestamp > sinceMs.
     */
    private double aggregatePnlSince(long sinceMs) {
        Aggregation agg = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("type").is("PNL").and("timestamp").gt(sinceMs)),
                Aggregation.group().sum("payload.pnlReal").as("total")
        );

        AggregationResults<Map> results = mongoTemplate.aggregate(agg, "events", Map.class);
        Map result = results.getUniqueMappedResult();
        if (result == null) return 0;
        Object val = result.get("total");
        return val instanceof Number ? round4(((Number) val).doubleValue()) : 0;
    }

    /**
     * Recalcula PnL do sistema do zero (aggregation completa) e salva novo snapshot.
     */
    public Map<String, Object> recalculateSystemPnl() {
        double fullPnl = calculateSystemPnlFull();

        // Atualiza o ultimo snapshot se existir, senao retorna o valor
        Optional<WalletSnapshot> lastOpt = snapshotRepository.findFirstByOrderByTimestampDesc();
        if (lastOpt.isPresent()) {
            WalletSnapshot last = lastOpt.get();
            double oldPnl = last.getSystemPnl();
            last.setSystemPnl(fullPnl);

            // Recalcular delta e divergencia
            Optional<WalletSnapshot> baselineOpt = snapshotRepository.findFirstByBaselineTrueOrderByTimestampDesc();
            if (baselineOpt.isPresent()) {
                WalletSnapshot baseline = baselineOpt.get();
                double delta = round4(fullPnl - baseline.getSystemPnl());
                double expected = round4(baseline.getClobBalance() + delta);
                double divergence = round4(last.getActualBalance() - expected);
                last.setSystemPnlDelta(delta);
                last.setExpectedBalance(expected);
                last.setDivergence(divergence);
            }

            snapshotRepository.save(last);
            log.info("[WALLET] SystemPnl recalculado: {} -> {}", oldPnl, fullPnl);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("previousPnl", oldPnl);
            result.put("recalculatedPnl", fullPnl);
            result.put("difference", round4(fullPnl - oldPnl));
            result.put("snapshot", formatSnapshot(last));
            return result;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("recalculatedPnl", fullPnl);
        result.put("note", "Nenhum snapshot existente para atualizar");
        return result;
    }

    // ─── CLOB Balance query ──────────────────────────────────────

    private double queryClobBalance() {
        try {
            long timestamp = fetchServerTimestamp();
            String signPath = "/balance-allowance";

            Map<String, String> authHeaders = authSigner.sign("GET", signPath, timestamp);

            String checksumProxy = Keys.toChecksumAddress(walletAddress);
            String checksumEoa = authSigner.getSignerAddress();
            boolean useProxy = !checksumEoa.equalsIgnoreCase(checksumProxy);
            int signatureType = useProxy ? 1 : 0;

            String fullUrl = clobBaseUrl + signPath + "?asset_type=COLLATERAL&signature_type=" + signatureType;

            RestClient restClient = RestClient.builder().baseUrl(clobBaseUrl).build();

            String body = restClient.get()
                    .uri(signPath + "?asset_type=COLLATERAL&signature_type=" + signatureType)
                    .headers(h -> authHeaders.forEach(h::set))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, resp) -> {
                        byte[] respBody = resp.getBody().readAllBytes();
                        log.error("[WALLET] CLOB balance error: {}", new String(respBody));
                        throw new RuntimeException("CLOB balance HTTP " + resp.getStatusCode().value());
                    })
                    .body(String.class);

            log.info("[WALLET] CLOB /balance-allowance response: {}", body);

            JsonNode node = objectMapper.readTree(body);
            String rawBalance = node.path("balance").asText("0");

            BigDecimal usdc = new BigDecimal(rawBalance)
                    .divide(new BigDecimal("1000000"), 6, RoundingMode.DOWN);

            return usdc.doubleValue();

        } catch (Exception e) {
            log.error("[WALLET] Erro ao consultar CLOB balance: {}", e.getMessage(), e);
            return -1;
        }
    }

    private long fetchServerTimestamp() {
        try {
            RestClient restClient = RestClient.builder().baseUrl(clobBaseUrl).build();
            String body = restClient.get().uri("/time").retrieve().body(String.class);
            if (body == null || body.isBlank()) return System.currentTimeMillis() / 1000;
            JsonNode node = objectMapper.readTree(body.trim());
            return node.isNumber() ? node.asLong() : node.path("time").asLong();
        } catch (Exception e) {
            return System.currentTimeMillis() / 1000;
        }
    }

    // ─── ERC-20 on-chain balance query ───────────────────────────

    private double queryErc20Balance(String wallet, String contractAddress, String label) {
        try (Web3j web3j = Web3j.build(new HttpService(POLYGON_RPC))) {

            Function fn = new Function(
                    "balanceOf",
                    Arrays.asList(new Address(wallet)),
                    Arrays.asList(new TypeReference<Uint256>() {})
            );

            String encoded = FunctionEncoder.encode(fn);

            var ethCall = web3j.ethCall(
                    Transaction.createEthCallTransaction(wallet, contractAddress, encoded),
                    DefaultBlockParameterName.LATEST
            ).send();

            var result = FunctionReturnDecoder.decode(ethCall.getValue(), fn.getOutputParameters());

            BigInteger raw = (BigInteger) result.get(0).getValue();
            BigDecimal balance = new BigDecimal(raw)
                    .divide(new BigDecimal("1000000"), 6, RoundingMode.DOWN);

            log.info("[WALLET] {} = {} USDC", label, balance.toPlainString());
            return balance.doubleValue();

        } catch (Exception e) {
            log.error("[WALLET] Erro ao consultar {} ({}): {}", label, contractAddress, e.getMessage());
            return -1;
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────

    private Map<String, Object> formatSnapshot(WalletSnapshot s) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", s.getId());
        item.put("timestamp", s.getTimestamp());
        item.put("timeSP", Instant.ofEpochMilli(s.getTimestamp()).atZone(SP_ZONE).format(DT_FMT));
        item.put("usdcE", s.getUsdcE());
        item.put("usdcNative", s.getUsdcNative());
        item.put("totalOnChain", s.getTotalOnChain());
        item.put("clobBalance", s.getClobBalance());
        item.put("systemPnl", s.getSystemPnl());
        item.put("systemPnlDelta", s.getSystemPnlDelta());
        item.put("expectedBalance", s.getExpectedBalance());
        item.put("actualBalance", s.getActualBalance());
        item.put("divergence", s.getDivergence());
        item.put("baseline", s.isBaseline());
        item.put("baselineId", s.getBaselineId());
        return item;
    }

    private double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
