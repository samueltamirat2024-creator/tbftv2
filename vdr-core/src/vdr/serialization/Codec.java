package vdr.serialization;

import vdr.merkle.Merkle;
import vdr.ops.Op;
import vdr.ops.Reply;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Canonical wire codec for {@link Op} and {@link Reply} (BFT-SMaRt implementation plan WP2).
 *
 * <p><b>Why not Java serialisation.</b> DepSpace's §8 lesson, carried into plan §14: default Java
 * serialisation was very inefficient with the confidentiality layer on, and custom codecs cut a
 * STORE message from 2313 to 1300 bytes. Writing them at benchmark time is the failure mode the
 * plan warns about, so they exist before the first measured run.
 *
 * <p><b>Canonical means byte-identical for equal values.</b> Fields are written in a fixed order,
 * every variable-length field is length-prefixed, and collections keep the order they were built
 * in -- which the store guarantees is deterministic. This matters beyond size: replies are matched
 * f+1-wise by a digest over their content, and an encoder that could produce two encodings of one
 * value would make honest replicas look like they disagreed.
 *
 * <p>Absent values are encoded as a single 0 byte, so null and empty are distinguishable: an empty
 * signature is a client that sent one and got it wrong, a null signature is one that sent none.
 */
public final class Codec {

    private static final byte ABSENT = 0;
    private static final byte PRESENT = 1;

    private Codec() {}

    // ------------------------------------------------------------------------ Op

    public static byte[] encode(Op op) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream(256);
             DataOutputStream out = new DataOutputStream(bos)) {
            out.writeByte(op.kind.ordinal());
            writeString(out, op.clientId);
            writeString(out, op.did);
            out.writeLong(op.expectedVersion);
            writeBytes(out, op.docBytes);
            writeBytes(out, op.controllerPubKey);
            writeBytes(out, op.sig);
            writeString(out, op.registryId);
            writeStrings(out, op.handles);
            writeBytes(out, op.reasonPayload);
            out.writeLong(op.nonce);
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("could not encode " + op, e);
        }
    }

    public static Op decodeOp(byte[] b) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(b))) {
            Op.Kind kind = KINDS[in.readByte()];
            String clientId = readString(in);
            String did = readString(in);
            long expectedVersion = in.readLong();
            byte[] docBytes = readBytes(in);
            byte[] controllerPubKey = readBytes(in);
            byte[] sig = readBytes(in);
            String registryId = readString(in);
            List<String> handles = readStrings(in);
            byte[] reasonPayload = readBytes(in);
            long nonce = in.readLong();
            return Op.restore(kind, clientId, did, expectedVersion, docBytes, controllerPubKey,
                    sig, registryId, handles, reasonPayload, nonce);
        } catch (IOException | ArrayIndexOutOfBoundsException e) {
            throw new IllegalStateException("could not decode an operation (" + b.length
                    + " bytes); a codec mismatch between replica and client would look like this", e);
        }
    }

    private static final Op.Kind[] KINDS = Op.Kind.values();

    // --------------------------------------------------------------------- Reply

    public static byte[] encode(Reply r) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream(256);
             DataOutputStream out = new DataOutputStream(bos)) {
            out.writeInt(r.replicaId);
            out.writeBoolean(r.ok);
            writeString(out, r.errorCode);
            writeBytes(out, r.value);
            out.writeLong(r.version);
            out.writeLong(r.epoch);
            out.writeLong(r.seq);
            writeBytes(out, r.checkpointRoot);
            writeProof(out, r.proof);
            writeString(out, r.leafKey);
            out.writeBoolean(r.revoked);
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("could not encode " + r, e);
        }
    }

    public static Reply decodeReply(byte[] b) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(b))) {
            int replicaId = in.readInt();
            boolean ok = in.readBoolean();
            String errorCode = readString(in);
            byte[] value = readBytes(in);
            long version = in.readLong();
            long epoch = in.readLong();
            long seq = in.readLong();
            byte[] root = readBytes(in);
            Merkle.Proof proof = readProof(in);
            String leafKey = readString(in);
            boolean revoked = in.readBoolean();
            return new Reply(replicaId, ok, errorCode, value, version, epoch, seq, root, proof,
                    leafKey, revoked);
        } catch (IOException | ArrayIndexOutOfBoundsException e) {
            throw new IllegalStateException("could not decode a reply (" + b.length + " bytes)", e);
        }
    }

    // --------------------------------------------------------------------- Proof

    private static void writeProof(DataOutputStream out, Merkle.Proof p) throws IOException {
        if (p == null) {
            out.writeByte(ABSENT);
            return;
        }
        out.writeByte(PRESENT);
        writeString(out, p.key());
        // siblings and siblingOnRight are parallel lists; one length covers both, and a mismatch
        // is a corrupt proof rather than something to encode.
        List<byte[]> siblings = p.siblings();
        List<Boolean> right = p.siblingOnRight();
        if (siblings.size() != right.size()) {
            throw new IllegalStateException("malformed proof: " + siblings.size()
                    + " siblings but " + right.size() + " direction flags");
        }
        out.writeInt(siblings.size());
        for (int i = 0; i < siblings.size(); i++) {
            writeBytes(out, siblings.get(i));
            out.writeBoolean(right.get(i));
        }
    }

    private static Merkle.Proof readProof(DataInputStream in) throws IOException {
        if (in.readByte() == ABSENT) {
            return null;
        }
        String key = readString(in);
        int size = in.readInt();
        List<byte[]> siblings = new ArrayList<>(size);
        List<Boolean> right = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            siblings.add(readBytes(in));
            right.add(in.readBoolean());
        }
        return new Merkle.Proof(key, siblings, right);
    }

    // ------------------------------------------------------------------ptimitives

    public static void writeString(DataOutputStream out, String s) throws IOException {
        writeBytes(out, s == null ? null : s.getBytes(StandardCharsets.UTF_8));
    }

    public static String readString(DataInputStream in) throws IOException {
        byte[] b = readBytes(in);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    public static void writeBytes(DataOutputStream out, byte[] b) throws IOException {
        if (b == null) {
            out.writeByte(ABSENT);
            return;
        }
        out.writeByte(PRESENT);
        out.writeInt(b.length);
        out.write(b);
    }

    public static byte[] readBytes(DataInputStream in) throws IOException {
        if (in.readByte() == ABSENT) {
            return null;
        }
        int len = in.readInt();
        if (len < 0) {
            throw new IOException("negative length " + len);
        }
        byte[] b = new byte[len];
        in.readFully(b);
        return b;
    }

    public static void writeStrings(DataOutputStream out, List<String> list) throws IOException {
        if (list == null) {
            out.writeByte(ABSENT);
            return;
        }
        out.writeByte(PRESENT);
        out.writeInt(list.size());
        for (String s : list) {
            writeString(out, s);
        }
    }

    public static List<String> readStrings(DataInputStream in) throws IOException {
        if (in.readByte() == ABSENT) {
            return null;
        }
        int size = in.readInt();
        List<String> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(readString(in));
        }
        return list;
    }
}
