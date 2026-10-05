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

    /**
     * One digest per thread. MessageDigest.getInstance does a provider lookup on every call, and
     * the replicas hash on every checkpoint, proof and policy check -- on a 1 vCPU replica that
     * lookup was a measurable share of the ordering thread's time. digest() resets the instance.
     */
    private static final ThreadLocal<MessageDigest> SHA256 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    });

    public static byte[] sha256(byte[]... parts) {
        MessageDigest md = SHA256.get();
        for (byte[] p : parts) {
            // length-prefix each part so concatenation is unambiguous (same bytes as before)
            int v = p.length;
            md.update((byte) (v >>> 24));
            md.update((byte) (v >>> 16));
            md.update((byte) (v >>> 8));
            md.update((byte) v);
            md.update(p);
        }
        return md.digest();
    }

    public static byte[] sha256(String... parts) {
        byte[][] raw = new byte[parts.length][];
        for (int i = 0; i < parts.length; i++) raw[i] = parts[i].getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return sha256(raw);
    }

    private static final HexFormat HEX = HexFormat.of();

    public static String hex(byte[] b) {
        return HEX.formatHex(b);
    }

    public static byte[] unhex(String s) {
        return HEX.parseHex(s);
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

    private static final ThreadLocal<Signature> ED25519 = ThreadLocal.withInitial(() -> {
        try {
            return Signature.getInstance("Ed25519");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Ed25519 unavailable", e);
        }
    });

    /**
     * Decoded controller keys. Every ordered write re-verifies its signature on every replica
     * (policies P1/P2/P4), and decoding the X.509 encoding through KeyFactory each time cost about
     * as much as the verification itself. Bounded: cleared wholesale when it grows past the cap.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, PublicKey> KEY_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final int KEY_CACHE_MAX = 65_536;

    private static PublicKey cachedPublic(byte[] encoded) {
        String k = HEX.formatHex(encoded);
        PublicKey pk = KEY_CACHE.get(k);
        if (pk == null) {
            pk = decodePublic(encoded);
            if (KEY_CACHE.size() >= KEY_CACHE_MAX) KEY_CACHE.clear();
            KEY_CACHE.put(k, pk);
        }
        return pk;
    }

    public static byte[] sign(PrivateKey sk, byte[] message) {
        if (FastEd25519.ENABLED && sk instanceof java.security.interfaces.EdECPrivateKey ed) {
            java.util.Optional<byte[]> seed = ed.getBytes();
            if (seed.isPresent() && seed.get().length == 32) {
                return FastEd25519.sign(seed.get(), message);
            }
        }
        try {
            Signature s = ED25519.get();
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
        if (FastEd25519.ENABLED) {
            byte[] raw = FastEd25519.rawPublic(encodedPublicKey);
            if (raw != null) {
                return signature.length == 64 && FastEd25519.verify(raw, message, signature);
            }
        }
        try {
            Signature s = ED25519.get();
            s.initVerify(cachedPublic(encodedPublicKey));
            s.update(message);
            return s.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }

    /** Which Ed25519 implementation this JVM uses; recorded in every run's notes. */
    public static String ed25519Provider() {
        return FastEd25519.ENABLED ? FastEd25519.DESCRIPTION : "JDK SunEC";
    }

    public static boolean eq(byte[] a, byte[] b) {
        return Arrays.equals(a, b);
    }

    /**
     * Optional fast Ed25519: BouncyCastle's RFC 8032 implementation, used when its jar is on the
     * classpath (vdr-bftsmart/lib/bcprov-*.jar; Dockerfile.bench fetches it). vdr-core still has no
     * compile-time dependency: the class is bound reflectively, like the BFT-SMaRt and Indy modules.
     *
     * <p>Why: the JDK's pure-Java EdDSA costs about 1 ms per sign and per verify. Every ordered write
     * is verified on every replica, on BFT-SMaRt's single delivery thread, which also serves the
     * unordered reads -- so on a 1 vCPU replica signature checks dominated the ordering path, and on
     * the client they dominated the generator. Indy's nodes and client use libsodium, which is
     * ~20x faster; leaving the JDK in place handicapped one side for reasons that have nothing to do
     * with the protocol.
     *
     * <p>Safety: RFC 8032 Ed25519 signing is deterministic, so both implementations produce
     * identical signatures, and a self-test at class load cross-checks them (JDK-signed verifies under
     * BouncyCastle, BouncyCastle's signature equals the JDK's byte for byte, a tampered signature is
     * rejected). If any check fails the JDK path is used. -Dvdr.crypto.ed25519=jdk forces the JDK.
     * All replicas must run the same image, so they agree on edge-case encodings too.
     */
    static final class FastEd25519 {
        static final boolean ENABLED;
        static final String DESCRIPTION;
        private static final java.lang.invoke.MethodHandle VERIFY, SIGN;
        /** X.509 SubjectPublicKeyInfo prefix for an Ed25519 key (RFC 8410): 12 bytes + 32-byte key. */
        private static final byte[] SPKI_PREFIX = HEX.parseHex("302a300506032b6570032100");

        static {
            java.lang.invoke.MethodHandle v = null, sg = null;
            boolean ok = false;
            String desc = "JDK SunEC";
            if (!"jdk".equalsIgnoreCase(System.getProperty("vdr.crypto.ed25519", "auto"))) {
                try {
                    Class<?> c = Class.forName("org.bouncycastle.math.ec.rfc8032.Ed25519");
                    java.lang.invoke.MethodHandles.Lookup l = java.lang.invoke.MethodHandles.publicLookup();
                    v = l.findStatic(c, "verify", java.lang.invoke.MethodType.methodType(boolean.class,
                            byte[].class, int.class, byte[].class, int.class, byte[].class, int.class, int.class));
                    sg = l.findStatic(c, "sign", java.lang.invoke.MethodType.methodType(void.class,
                            byte[].class, int.class, byte[].class, int.class, int.class, byte[].class, int.class));
                    ok = selfTest(v, sg);
                    Package pkg = c.getPackage();
                    String ver = pkg != null && pkg.getImplementationVersion() != null
                            ? " " + pkg.getImplementationVersion() : "";
                    desc = ok ? "BouncyCastle" + ver : "JDK SunEC (BouncyCastle self-test FAILED)";
                } catch (ClassNotFoundException e) {
                    // not on the classpath: JDK path
                } catch (Throwable t) {
                    desc = "JDK SunEC (BouncyCastle unusable: " + t + ")";
                }
            }
            VERIFY = v;
            SIGN = sg;
            ENABLED = ok;
            DESCRIPTION = desc;
        }

        private static boolean selfTest(java.lang.invoke.MethodHandle v, java.lang.invoke.MethodHandle sg)
                throws Throwable {
            KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            byte[] msg = "vdr-ed25519-self-test".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Signature js = Signature.getInstance("Ed25519");
            js.initSign(kp.getPrivate());
            js.update(msg);
            byte[] jdkSig = js.sign();
            byte[] pub = rawPublic(kp.getPublic().getEncoded());
            byte[] seed = ((java.security.interfaces.EdECPrivateKey) kp.getPrivate()).getBytes().orElse(null);
            if (pub == null || seed == null) return false;
            if (!(boolean) v.invokeExact(jdkSig, 0, pub, 0, msg, 0, msg.length)) return false;
            byte[] bcSig = new byte[64];
            sg.invokeExact(seed, 0, msg, 0, msg.length, bcSig, 0);
            if (!Arrays.equals(bcSig, jdkSig)) return false;
            byte[] bad = jdkSig.clone();
            bad[10] ^= 1;
            return !(boolean) v.invokeExact(bad, 0, pub, 0, msg, 0, msg.length);
        }

        /** The 32-byte key inside an Ed25519 SPKI encoding, or null if it is not one. */
        static byte[] rawPublic(byte[] spki) {
            if (spki.length != 44) return null;
            for (int i = 0; i < SPKI_PREFIX.length; i++) {
                if (spki[i] != SPKI_PREFIX[i]) return null;
            }
            return Arrays.copyOfRange(spki, 12, 44);
        }

        static boolean verify(byte[] rawPub, byte[] msg, byte[] sig) {
            try {
                return (boolean) VERIFY.invokeExact(sig, 0, rawPub, 0, msg, 0, msg.length);
            } catch (Throwable t) {
                return false;                     // never throws: a bad signature is a denial
            }
        }

        static byte[] sign(byte[] seed, byte[] msg) {
            byte[] out = new byte[64];
            try {
                SIGN.invokeExact(seed, 0, msg, 0, msg.length, out, 0);
            } catch (Throwable t) {
                throw new IllegalStateException("Ed25519 signing failed", t);
            }
            return out;
        }
    }
}
