package vdr.crypto;

import java.security.*;
import java.security.spec.*;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Cryptographic envelope for the Tailored BFT VDR.
 *
 * Plan section 8, "Modern substitutions": SHA-256 (not SHA-1), Ed25519 (not RSA-1024).
 * All primitives come from the JDK; no external dependencies, so the core builds
 * with javac alone.
 */
public final class Crypto {

    private Crypto() {}

    public static final byte[] EMPTY = new byte[0];

    // ---------------------------------------------------------------- hashing

    public static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) {
                // length-prefix each part so concatenation is unambiguous
                md.update(intToBytes(p.length));
                md.update(p);
            }
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] sha256(String... parts) {
        byte[][] raw = new byte[parts.length][];
        for (int i = 0; i < parts.length; i++) raw[i] = parts[i].getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return sha256(raw);
    }

    public static String hex(byte[] b) {
        return HexFormat.of().formatHex(b);
    }

    public static byte[] unhex(String s) {
        return HexFormat.of().parseHex(s);
    }

    public static byte[] intToBytes(int v) {
        return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    public static byte[] longToBytes(long v) {
        byte[] b = new byte[8];
        for (int i = 7; i >= 0; i--) { b[i] = (byte) v; v >>>= 8; }
        return b;
    }

    // ------------------------------------------------------------- signatures

    public static KeyPair generateKeyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Ed25519 unavailable", e);
        }
    }

    public static byte[] encodePublic(PublicKey pk) {
        return pk.getEncoded();
    }

    public static PublicKey decodePublic(byte[] encoded) {
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("bad public key", e);
        }
    }

    public static byte[] sign(PrivateKey sk, byte[] message) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(sk);
            s.update(message);
            return s.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Never throws on malformed input: a bad signature is a denied operation, not a crash. */
    public static boolean verify(byte[] encodedPublicKey, byte[] message, byte[] signature) {
        if (encodedPublicKey == null || signature == null) return false;
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(decodePublic(encodedPublicKey));
            s.update(message);
            return s.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean eq(byte[] a, byte[] b) {
        return Arrays.equals(a, b);
    }
}
