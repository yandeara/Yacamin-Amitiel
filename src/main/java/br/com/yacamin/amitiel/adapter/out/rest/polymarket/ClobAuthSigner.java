package br.com.yacamin.amitiel.adapter.out.rest.polymarket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Keys;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Full L1 (EIP-712) + L2 (HMAC-SHA256) auth for Polymarket CLOB API.
 * Matches Gabriel's PolymarketAuthService:
 *   1. L1: derive API key via EIP-712 signature → gets session (apiKey, secret, passphrase)
 *   2. L2: sign requests with session credentials via HMAC-SHA256
 */
@Slf4j
@Component
public class ClobAuthSigner {

    @Value("${polymarket.private-key}")
    private String privateKey;

    @Value("${polymarket.wallet-address}")
    private String walletAddress;

    @Value("${polymarket.paths.clob.rest}")
    private String clobBaseUrl;

    @Value("${polymarket.nonce:0}")
    private long defaultNonce;

    // Session credentials (derived via L1)
    private volatile String sessionApiKey;
    private volatile String sessionSecret;
    private volatile String sessionPassphrase;
    private volatile String signerChecksumAddress;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Signs a L2 request. Derives session on first call.
     */
    public Map<String, String> sign(String method, String path, long timestamp) {
        ensureSession();

        try {
            String message = timestamp + method.toUpperCase() + path;

            byte[] secretBytes = Base64.getUrlDecoder().decode(sessionSecret);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretBytes, "HmacSHA256"));
            String signature = Base64.getUrlEncoder()
                    .encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));

            String addr = getSignerAddress();

            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("POLY_ADDRESS", addr);
            headers.put("POLY_SIGNATURE", signature);
            headers.put("POLY_TIMESTAMP", String.valueOf(timestamp));
            headers.put("POLY_NONCE", "0");
            headers.put("POLY_API_KEY", sessionApiKey);
            headers.put("POLY_PASSPHRASE", sessionPassphrase);
            return headers;
        } catch (Exception e) {
            log.error("[AUTH] Falha ao assinar request L2: {}", e.getMessage(), e);
            throw new RuntimeException("CLOB L2 auth signing failed", e);
        }
    }

    public String getWalletAddress() {
        return Keys.toChecksumAddress(walletAddress);
    }

    public String getSignerAddress() {
        if (signerChecksumAddress != null) return signerChecksumAddress;
        String key = privateKey.startsWith("0x") ? privateKey.substring(2) : privateKey;
        Credentials credentials = Credentials.create(key);
        signerChecksumAddress = Keys.toChecksumAddress(credentials.getAddress());
        log.info("[AUTH] Signer EOA: {}, Proxy wallet: {}", signerChecksumAddress, walletAddress);
        return signerChecksumAddress;
    }

    /**
     * Derives API session via L1 (EIP-712). Tries derive first, falls back to create.
     */
    private synchronized void ensureSession() {
        if (sessionApiKey != null) return;

        String key = privateKey.startsWith("0x") ? privateKey.substring(2) : privateKey;
        Credentials credentials = Credentials.create(key);

        long serverTs = fetchServerTimestamp();

        // Try derive first (reuses existing nonce)
        try {
            deriveSession(credentials, serverTs);
            return;
        } catch (Exception e) {
            log.info("[AUTH] Derive falhou ({}), tentando criar...", e.getMessage());
        }

        // Fallback: create new
        try {
            createSession(credentials, serverTs);
        } catch (Exception e) {
            log.error("[AUTH] Create tambem falhou: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to obtain CLOB API session", e);
        }
    }

    private void deriveSession(Credentials credentials, long timestamp) {
        EIP712Signer.AuthResult auth = EIP712Signer.signAuth(credentials, timestamp, defaultNonce);
        callL1("GET", "/auth/derive-api-key", auth);
    }

    private void createSession(Credentials credentials, long timestamp) {
        EIP712Signer.AuthResult auth = EIP712Signer.signAuth(credentials, timestamp, defaultNonce);
        callL1("POST", "/auth/api-key", auth);
    }

    private void callL1(String method, String path, EIP712Signer.AuthResult auth) {
        String url = clobBaseUrl + path;
        String checksumAddr = Keys.toChecksumAddress(auth.address());

        log.info("[AUTH] L1 {} {} | address={} nonce={} timestamp={}",
                method, url, checksumAddr, auth.nonce(), auth.timestamp());

        try {
            var restClient = org.springframework.web.client.RestClient.builder()
                    .baseUrl(clobBaseUrl)
                    .build();

            var requestSpec = "GET".equals(method)
                    ? restClient.get().uri(path)
                    : restClient.post().uri(path);

            String body = requestSpec
                    .headers(h -> {
                        h.set("POLY_ADDRESS", checksumAddr);
                        h.set("POLY_SIGNATURE", auth.signature());
                        h.set("POLY_TIMESTAMP", String.valueOf(auth.timestamp()));
                        h.set("POLY_NONCE", String.valueOf(auth.nonce()));
                    })
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, resp) -> {
                        byte[] respBody = resp.getBody().readAllBytes();
                        String respStr = new String(respBody);
                        log.error("[AUTH] L1 HTTP {} {} - Response: {}", resp.getStatusCode().value(), url, respStr);
                        throw new RuntimeException("L1 HTTP " + resp.getStatusCode().value() + ": " + respStr);
                    })
                    .body(String.class);

            log.info("[AUTH] L1 response: {}", body);

            JsonNode root = objectMapper.readTree(body);
            sessionApiKey = root.path("apiKey").asText();
            sessionSecret = root.path("secret").asText();
            sessionPassphrase = root.path("passphrase").asText();

            log.info("[AUTH] Session obtida: apiKey={}", sessionApiKey);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("L1 auth failed: " + e.getMessage(), e);
        }
    }

    private long fetchServerTimestamp() {
        try {
            var restClient = org.springframework.web.client.RestClient.builder()
                    .baseUrl(clobBaseUrl)
                    .build();

            String body = restClient.get().uri("/time").retrieve().body(String.class);
            log.info("[AUTH] /time raw: '{}'", body);

            if (body == null || body.isBlank()) return System.currentTimeMillis() / 1000;

            JsonNode node = objectMapper.readTree(body.trim());
            long ts = node.isNumber() ? node.asLong() : node.path("time").asLong();
            log.info("[AUTH] Server timestamp: {}", ts);
            return ts;
        } catch (Exception e) {
            long local = System.currentTimeMillis() / 1000;
            log.warn("[AUTH] Falha /time: {} - usando local: {}", e.getMessage(), local);
            return local;
        }
    }
}
