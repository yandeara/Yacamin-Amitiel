package br.com.yacamin.amitiel.adapter.out.rest.polymarket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Dust recovery via Builder Relayer API (gasless proxy transactions).
 * Simplified from Uriel's PolymarketRedeemService — single redeem only.
 */
@Slf4j
@Service
public class PolymarketRedeemService {

    private static final String RELAYER_URL = "https://relayer-v2.polymarket.com";
    private static final String CTF_ADDRESS = "0x4D97DCd97eC945f40cF65F87097ACe5EA0476045";
    private static final String NEG_RISK_ADAPTER = "0xd91E80cF2E7be2e162c6513ceD06f1dD0dA35296";
    private static final String USDC_E = "0x2791Bca1f2de4661ED88A30C99A7a9449Aa84174";
    private static final String PROXY_FACTORY = "0xaB45c5A4B0c941a2F231C04C3f49182e1A254052";
    private static final String RELAY_HUB = "0xD216153c06E857cD7f72665E0aF1d7D82172F494";
    private static final String PROXY_INIT_CODE_HASH = "0xd21df8dc65880a8606f09fe0ce3df9b8869287ab0b058be05aa9e8af6330a00b";
    private static final String POLYGON_RPC = "https://polygon-bor-rpc.publicnode.com";

    @Value("${polymarket.ws.api-key}")
    private String apiKey;

    @Value("${polymarket.ws.secret}")
    private String secret;

    @Value("${polymarket.ws.pass}")
    private String passphrase;

    @Value("${polymarket.wallet-address}")
    private String walletAddress;

    @Value("${polymarket.private-key}")
    private String privateKey;

    private final ObjectMapper objectMapper;

    public PolymarketRedeemService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ─── Public API ──────────────────────────────────────────────

    /**
     * Consulta o saldo de conditional tokens (ERC-1155) no proxy wallet
     * e faz redeem do dust restante.
     *
     * @param conditionId hex string (bytes32) do mercado
     * @param tokenUpId   tokenId do outcome UP
     * @param tokenDownId tokenId do outcome DOWN
     * @param negRisk     true se é negRisk market (BTC UP/DOWN)
     * @return mapa com resultado do redeem
     */
    public Map<String, Object> redeemDust(String conditionId, String tokenUpId, String tokenDownId, boolean negRisk) {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            Credentials credentials = loadCredentials();
            String checksumEoa = Keys.toChecksumAddress(credentials.getAddress());
            String proxyWallet = deriveProxyWallet(checksumEoa);

            log.info("[DUST-REDEEM] Proxy wallet: {}", proxyWallet);
            log.info("[DUST-REDEEM] conditionId={}, negRisk={}", conditionId, negRisk);

            // 1. Query ERC-1155 balances for both tokens
            String contract = negRisk ? NEG_RISK_ADAPTER : CTF_ADDRESS;
            long upBalance = queryErc1155Balance(proxyWallet, contract, tokenUpId);
            long downBalance = queryErc1155Balance(proxyWallet, contract, tokenDownId);

            double upUsdc = upBalance / 1_000_000.0;
            double downUsdc = downBalance / 1_000_000.0;

            result.put("proxyWallet", proxyWallet);
            result.put("contract", contract);
            result.put("tokenUpBalance", upBalance);
            result.put("tokenDownBalance", downBalance);
            result.put("tokenUpUsdc", round4(upUsdc));
            result.put("tokenDownUsdc", round4(downUsdc));

            if (upBalance == 0 && downBalance == 0) {
                result.put("success", false);
                result.put("error", "Nenhum token residual encontrado na proxy wallet");
                return result;
            }

            log.info("[DUST-REDEEM] Balances: UP={} ({}), DOWN={} ({})",
                    upBalance, upUsdc, downBalance, downUsdc);

            // 2. Encode redeem call
            String callData;
            if (negRisk) {
                callData = encodeNegRiskRedeem(conditionId,
                        BigInteger.valueOf(upBalance), BigInteger.valueOf(downBalance));
            } else {
                callData = encodeRegularRedeem(conditionId);
            }

            // 3. Submit via relayer
            String transactionId = submitProxyCall(credentials, contract, callData);

            if (transactionId != null) {
                result.put("success", true);
                result.put("transactionId", transactionId);
                log.info("[DUST-REDEEM] Submitted: txId={}", transactionId);
            } else {
                result.put("success", false);
                result.put("error", "Relayer retornou erro (ver logs)");
            }

        } catch (Exception e) {
            log.error("[DUST-REDEEM] Erro: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * Consulta apenas o saldo de conditional tokens sem fazer redeem.
     */
    public Map<String, Object> queryDustBalance(String tokenUpId, String tokenDownId, boolean negRisk) {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            Credentials credentials = loadCredentials();
            String checksumEoa = Keys.toChecksumAddress(credentials.getAddress());
            String proxyWallet = deriveProxyWallet(checksumEoa);

            String contract = negRisk ? NEG_RISK_ADAPTER : CTF_ADDRESS;
            long upBalance = queryErc1155Balance(proxyWallet, contract, tokenUpId);
            long downBalance = queryErc1155Balance(proxyWallet, contract, tokenDownId);

            result.put("proxyWallet", proxyWallet);
            result.put("contract", contract);
            result.put("tokenUpBalance", upBalance);
            result.put("tokenDownBalance", downBalance);
            result.put("tokenUpUsdc", round4(upBalance / 1_000_000.0));
            result.put("tokenDownUsdc", round4(downBalance / 1_000_000.0));
            result.put("hasDust", upBalance > 0 || downBalance > 0);

        } catch (Exception e) {
            log.error("[DUST-QUERY] Erro: {}", e.getMessage(), e);
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * Consulta o estado de uma transação no Relayer.
     */
    public String getTransactionState(String transactionId) {
        try {
            RestClient restClient = RestClient.builder().baseUrl(RELAYER_URL).build();
            String response = restClient.get()
                    .uri("/transaction?id={id}", transactionId)
                    .retrieve()
                    .body(String.class);

            log.info("[DUST-REDEEM] Transaction state response: {}", response);

            JsonNode node = objectMapper.readTree(response);
            JsonNode txNode = node.isArray() && !node.isEmpty() ? node.get(0) : node;
            return txNode.path("state").asText(null);

        } catch (Exception e) {
            log.error("[DUST-REDEEM] Erro ao consultar transação {}: {}", transactionId, e.getMessage());
            return null;
        }
    }

    // ─── ERC-1155 balance query ──────────────────────────────────

    private long queryErc1155Balance(String wallet, String contractAddress, String tokenId) {
        try (Web3j web3j = Web3j.build(new HttpService(POLYGON_RPC))) {
            Function fn = new Function(
                    "balanceOf",
                    Arrays.asList(
                            new Address(wallet),
                            new Uint256(new BigInteger(tokenId))
                    ),
                    Arrays.asList(new TypeReference<Uint256>() {})
            );

            String encoded = FunctionEncoder.encode(fn);
            log.info("[DUST] ERC-1155 query: wallet={}, contract={}, tokenId={}, encodedData={}",
                    wallet, contractAddress, tokenId, encoded);

            var ethCall = web3j.ethCall(
                    Transaction.createEthCallTransaction(wallet, contractAddress, encoded),
                    DefaultBlockParameterName.LATEST
            ).send();

            String rawValue = ethCall.getValue();
            boolean hasError = ethCall.hasError();
            log.info("[DUST] ERC-1155 ethCall response: rawValue={}, hasError={}, error={}",
                    rawValue, hasError, hasError ? ethCall.getError().getMessage() : "none");

            if (hasError) {
                log.error("[DUST] ERC-1155 ethCall error code={}, message={}",
                        ethCall.getError().getCode(), ethCall.getError().getMessage());
                return 0;
            }

            if (rawValue == null || rawValue.equals("0x")) {
                log.warn("[DUST] ERC-1155 ethCall retornou valor vazio/nulo para tokenId={}", tokenId);
                return 0;
            }

            var decoded = FunctionReturnDecoder.decode(rawValue, fn.getOutputParameters());
            BigInteger raw = (BigInteger) decoded.get(0).getValue();

            log.info("[DUST] ERC-1155 balance result: contract={} tokenId={} balance={}",
                    contractAddress, tokenId.substring(0, Math.min(16, tokenId.length())) + "...", raw);
            return raw.longValue();

        } catch (Exception e) {
            log.error("[DUST] Erro ao consultar ERC-1155: {}", e.getMessage(), e);
            return 0;
        }
    }

    // ─── Encode contract calls ───────────────────────────────────

    private String encodeNegRiskRedeem(String conditionId, BigInteger upAmount, BigInteger downAmount) {
        byte[] conditionBytes = hexToBytes32(conditionId);
        Function function = new Function(
                "redeemPositions",
                Arrays.asList(
                        new Bytes32(conditionBytes),
                        new DynamicArray<>(Uint256.class,
                                Arrays.asList(new Uint256(upAmount), new Uint256(downAmount)))
                ),
                Collections.emptyList()
        );
        return FunctionEncoder.encode(function);
    }

    private String encodeRegularRedeem(String conditionId) {
        byte[] parentCollection = new byte[32];
        byte[] conditionBytes = hexToBytes32(conditionId);
        Function function = new Function(
                "redeemPositions",
                Arrays.asList(
                        new Address(USDC_E),
                        new Bytes32(parentCollection),
                        new Bytes32(conditionBytes),
                        new DynamicArray<>(Uint256.class,
                                Arrays.asList(new Uint256(BigInteger.ONE), new Uint256(BigInteger.TWO)))
                ),
                Collections.emptyList()
        );
        return FunctionEncoder.encode(function);
    }

    // ─── Proxy transaction submission ────────────────────────────

    private String submitProxyCall(Credentials credentials, String contractAddress, String callData) throws Exception {
        String checksumEoa = Keys.toChecksumAddress(credentials.getAddress());
        String proxyWallet = deriveProxyWallet(checksumEoa);

        // Encode proxy call
        String proxyCallData = encodeProxyCallSingle(contractAddress, callData);

        // Fetch relay payload
        String[] relayPayload = fetchRelayPayload(checksumEoa);
        String relayAddress = relayPayload[0];
        String nonce = relayPayload[1];

        String gasLimit = "500000";

        // Create struct hash and sign
        byte[] structHash = createProxyStructHash(
                checksumEoa, PROXY_FACTORY, proxyCallData,
                "0", "0", gasLimit, nonce,
                RELAY_HUB, relayAddress);

        Sign.SignatureData sig = Sign.signPrefixedMessage(structHash, credentials.getEcKeyPair());
        String signature = encodeSignatureStandard(sig);

        // Build body
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "PROXY");
        body.put("from", checksumEoa);
        body.put("to", PROXY_FACTORY);
        body.put("proxyWallet", proxyWallet);
        body.put("data", proxyCallData);
        body.put("nonce", nonce);
        body.put("signature", signature);

        Map<String, String> sigParams = new LinkedHashMap<>();
        sigParams.put("gasPrice", "0");
        sigParams.put("gasLimit", gasLimit);
        sigParams.put("relayerFee", "0");
        sigParams.put("relayHub", RELAY_HUB);
        sigParams.put("relay", relayAddress);
        body.put("signatureParams", sigParams);
        body.put("metadata", "");

        String json = objectMapper.writeValueAsString(body);
        log.info("[DUST-REDEEM] POST /submit: {}", json);

        // HMAC headers
        long timestamp = System.currentTimeMillis() / 1000;
        String hmacSig = buildHmacSignature(String.valueOf(timestamp), "POST", "/submit", json);

        RestClient restClient = RestClient.builder().baseUrl(RELAYER_URL).build();

        String response = restClient.post()
                .uri("/submit")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(h -> {
                    h.set("POLY_BUILDER_API_KEY", apiKey);
                    h.set("POLY_BUILDER_SIGNATURE", hmacSig);
                    h.set("POLY_BUILDER_TIMESTAMP", String.valueOf(timestamp));
                    h.set("POLY_BUILDER_PASSPHRASE", passphrase);
                })
                .body(json)
                .retrieve()
                .body(String.class);

        log.info("[DUST-REDEEM] Relayer response: {}", response);

        JsonNode node = objectMapper.readTree(response);
        return node.path("transactionID").asText(null);
    }

    private String[] fetchRelayPayload(String eoaAddress) throws Exception {
        long timestamp = System.currentTimeMillis() / 1000;
        String hmacSig = buildHmacSignature(String.valueOf(timestamp), "GET", "/relay-payload", null);

        RestClient restClient = RestClient.builder().baseUrl(RELAYER_URL).build();

        String response = restClient.get()
                .uri("/relay-payload?address={addr}&type=PROXY", eoaAddress)
                .headers(h -> {
                    h.set("POLY_BUILDER_API_KEY", apiKey);
                    h.set("POLY_BUILDER_SIGNATURE", hmacSig);
                    h.set("POLY_BUILDER_TIMESTAMP", String.valueOf(timestamp));
                    h.set("POLY_BUILDER_PASSPHRASE", passphrase);
                })
                .retrieve()
                .body(String.class);

        log.info("[DUST-REDEEM] relay-payload: {}", response);
        JsonNode node = objectMapper.readTree(response);
        return new String[]{node.path("address").asText(), node.path("nonce").asText("0")};
    }

    // ─── Proxy encoding ──────────────────────────────────────────

    private String encodeProxyCallSingle(String contractAddress, String callData) {
        String selector = "34ee9791";
        String dataHex = callData.startsWith("0x") ? callData.substring(2) : callData;
        int dataLen = dataHex.length() / 2;
        String dataPadded = dataHex + "0".repeat((32 - (dataLen % 32)) % 32 * 2);

        String typeCode = padLeft("1", 64);
        String to = padLeft(contractAddress.substring(2).toLowerCase(), 64);
        String value = padLeft("0", 64);
        String dataOffset = padLeft("80", 64);
        String dataLenHex = padLeft(Integer.toHexString(dataLen), 64);
        String tuple = typeCode + to + value + dataOffset + dataLenHex + dataPadded;

        StringBuilder sb = new StringBuilder();
        sb.append(padLeft("20", 64));           // offset to array param
        sb.append(padLeft("1", 64));            // array length = 1
        sb.append(padLeft("20", 64));           // offset to first element (1 * 32 = 32 = 0x20)
        sb.append(tuple);

        return "0x" + selector + sb;
    }

    // ─── Struct hash ─────────────────────────────────────────────

    private byte[] createProxyStructHash(String from, String to, String data,
                                          String txFee, String gasPrice, String gasLimit, String nonce,
                                          String relayHub, String relay) {
        byte[] prefix = "rlx:".getBytes(StandardCharsets.UTF_8);
        byte[] fromBytes = Numeric.hexStringToByteArray(from.substring(2));
        byte[] toBytes = Numeric.hexStringToByteArray(to.substring(2));
        byte[] dataBytes = Numeric.hexStringToByteArray(data.startsWith("0x") ? data.substring(2) : data);
        byte[] txFeeBytes = leftPad32(new BigInteger(txFee).toByteArray());
        byte[] gasPriceBytes = leftPad32(new BigInteger(gasPrice).toByteArray());
        byte[] gasLimitBytes = leftPad32(new BigInteger(gasLimit).toByteArray());
        byte[] nonceBytes = leftPad32(new BigInteger(nonce).toByteArray());
        byte[] relayHubBytes = Numeric.hexStringToByteArray(relayHub.substring(2));
        byte[] relayBytes = Numeric.hexStringToByteArray(relay.substring(2));

        return Hash.sha3(concat(prefix, fromBytes, toBytes, dataBytes,
                txFeeBytes, gasPriceBytes, gasLimitBytes, nonceBytes,
                relayHubBytes, relayBytes));
    }

    // ─── HMAC ────────────────────────────────────────────────────

    private String buildHmacSignature(String timestamp, String method, String path, String body) {
        try {
            String message = timestamp + method + path;
            if (body != null && !body.isEmpty()) message += body;
            byte[] secretBytes = Base64.getDecoder().decode(secret);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretBytes, "HmacSHA256"));
            String base64 = Base64.getEncoder().encodeToString(
                    mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
            return base64.replace("+", "-").replace("/", "_");
        } catch (Exception e) {
            throw new RuntimeException("HMAC signing failed: " + e.getMessage(), e);
        }
    }

    // ─── Utilities ───────────────────────────────────────────────

    private Credentials loadCredentials() {
        String key = privateKey.startsWith("0x") ? privateKey.substring(2) : privateKey;
        return Credentials.create(key);
    }

    private static String encodeSignatureStandard(Sign.SignatureData sig) {
        byte[] combined = new byte[65];
        System.arraycopy(sig.getR(), 0, combined, 0, 32);
        System.arraycopy(sig.getS(), 0, combined, 32, 32);
        int v = sig.getV()[0] & 0xFF;
        if (v < 27) v += 27;
        combined[64] = (byte) v;
        return "0x" + Numeric.toHexStringNoPrefix(combined);
    }

    private String deriveProxyWallet(String eoaAddress) {
        byte[] addressBytes = Numeric.hexStringToByteArray(eoaAddress.substring(2));
        byte[] salt = Hash.sha3(addressBytes);
        byte[] factoryBytes = Numeric.hexStringToByteArray(PROXY_FACTORY.substring(2));
        byte[] initCodeHashBytes = Numeric.hexStringToByteArray(PROXY_INIT_CODE_HASH.substring(2));

        byte[] payload = new byte[1 + 20 + 32 + 32];
        payload[0] = (byte) 0xff;
        System.arraycopy(factoryBytes, 0, payload, 1, 20);
        System.arraycopy(salt, 0, payload, 21, 32);
        System.arraycopy(initCodeHashBytes, 0, payload, 53, 32);

        byte[] hash = Hash.sha3(payload);
        byte[] addrBytes = new byte[20];
        System.arraycopy(hash, 12, addrBytes, 0, 20);
        return Keys.toChecksumAddress("0x" + Numeric.toHexStringNoPrefix(addrBytes));
    }

    private byte[] hexToBytes32(String hex) {
        String clean = hex.startsWith("0x") ? hex.substring(2) : hex;
        byte[] bytes = new byte[32];
        byte[] decoded = Numeric.hexStringToByteArray(clean);
        System.arraycopy(decoded, 0, bytes, 32 - decoded.length, decoded.length);
        return bytes;
    }

    private static byte[] leftPad32(byte[] input) {
        byte[] padded = new byte[32];
        int srcStart = input.length > 32 ? input.length - 32 : 0;
        int copyLen = Math.min(input.length, 32);
        System.arraycopy(input, srcStart, padded, 32 - copyLen, copyLen);
        return padded;
    }

    private static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] a : arrays) total += a.length;
        byte[] result = new byte[total];
        int offset = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, result, offset, a.length);
            offset += a.length;
        }
        return result;
    }

    private static String padLeft(String hex, int totalChars) {
        return "0".repeat(Math.max(0, totalChars - hex.length())) + hex;
    }

    private double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
