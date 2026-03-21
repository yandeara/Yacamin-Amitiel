package br.com.yacamin.amitiel.adapter.out.rest.polymarket;

import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

/**
 * EIP-712 typed data signing for L1 auth on Polymarket CLOB API.
 * Copied from Yacamin-Gabriel's working implementation.
 */
public class EIP712Signer {

    public static final long POLYGON_CHAIN_ID = 137L;
    public static final String AUTH_MESSAGE =
            "This message attests that I control the given wallet";

    private static final String DOMAIN_TYPE =
            "EIP712Domain(string name,string version,uint256 chainId)";

    private static final String CLOB_AUTH_TYPE =
            "ClobAuth(address address,string timestamp,uint256 nonce,string message)";

    public record AuthResult(String address, String signature, long timestamp, long nonce) {}

    public static AuthResult signAuth(Credentials credentials, long timestamp, long nonce) {
        String address = credentials.getAddress();
        String tsStr = String.valueOf(timestamp);
        String signature = buildAndSign(credentials, address, tsStr, nonce);
        return new AuthResult(address, signature, timestamp, nonce);
    }

    private static String buildAndSign(Credentials creds, String address, String timestamp, long nonce) {
        byte[] domainSep = buildDomainSeparator();
        byte[] structHash = buildStructHash(address, timestamp, nonce);

        byte[] payload = new byte[66];
        payload[0] = 0x19;
        payload[1] = 0x01;
        System.arraycopy(domainSep, 0, payload, 2, 32);
        System.arraycopy(structHash, 0, payload, 34, 32);

        byte[] digest = Hash.sha3(payload);
        Sign.SignatureData sig = Sign.signMessage(digest, creds.getEcKeyPair(), false);
        return encodeSignature(sig);
    }

    private static byte[] buildDomainSeparator() {
        byte[] typeHash = keccak256str(DOMAIN_TYPE);
        byte[] nameHash = keccak256str("ClobAuthDomain");
        byte[] versionHash = keccak256str("1");
        byte[] chainId = leftPad32(BigInteger.valueOf(POLYGON_CHAIN_ID).toByteArray());
        return Hash.sha3(concat(typeHash, nameHash, versionHash, chainId));
    }

    private static byte[] buildStructHash(String address, String timestamp, long nonce) {
        byte[] typeHash = keccak256str(CLOB_AUTH_TYPE);
        byte[] addrBytes = addressToBytes32(address);
        byte[] tsHash = keccak256str(timestamp);
        byte[] nonceBytes = leftPad32(BigInteger.valueOf(nonce).toByteArray());
        byte[] msgHash = keccak256str(AUTH_MESSAGE);
        return Hash.sha3(concat(typeHash, addrBytes, tsHash, nonceBytes, msgHash));
    }

    private static byte[] keccak256str(String text) {
        return Hash.sha3(text.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] leftPad32(byte[] input) {
        byte[] padded = new byte[32];
        int srcStart = input.length > 32 ? input.length - 32 : 0;
        int copyLen = Math.min(input.length, 32);
        int destStart = 32 - copyLen;
        System.arraycopy(input, srcStart, padded, destStart, copyLen);
        return padded;
    }

    private static byte[] addressToBytes32(String address) {
        String hex = address.startsWith("0x") ? address.substring(2) : address;
        return leftPad32(Numeric.hexStringToByteArray(hex));
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

    private static String encodeSignature(Sign.SignatureData sig) {
        byte[] combined = new byte[65];
        System.arraycopy(sig.getR(), 0, combined, 0, 32);
        System.arraycopy(sig.getS(), 0, combined, 32, 32);
        int v = sig.getV()[0] & 0xFF;
        combined[64] = (byte) (v < 27 ? v + 27 : v);
        return "0x" + Numeric.toHexStringNoPrefix(combined);
    }
}
