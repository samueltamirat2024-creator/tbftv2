package vdr.model;

import vdr.crypto.Crypto;

import java.util.ArrayList;
import java.util.List;

/**
 * DepSpace fingerprint(t, v_t), implemented verbatim (plan section 2.2):
 *
 *   h_i = *        if f_i = *      (wildcard in a template)
 *       = f_i      if v_i = PU
 *       = H(f_i)   if v_i = CO
 *       = PR       if v_i = PR
 *
 * This is the ONE place where VDR-specific indexing lives. DepSpace's generality
 * note says adapting the scheme to a non-tuple store only requires changing this
 * function so a query can find matching items; keeping it isolated in a single
 * class is the plan's explicit instruction.
 */
public final class Fingerprint {

    public static final String WILDCARD = "*";
    public static final String PRIVATE_MARKER = "PR";

    private Fingerprint() {}

    /**
     * @param fields  tuple fields; a null field means the wildcard '*' in a template
     * @param vector  protection type vector, one entry per field
     */
    public static List<String> of(List<String> fields, List<Protection> vector) {
        if (fields.size() != vector.size()) {
            throw new IllegalArgumentException("field/protection arity mismatch: "
                    + fields.size() + " vs " + vector.size());
        }
        List<String> fp = new ArrayList<>(fields.size());
        for (int i = 0; i < fields.size(); i++) {
            String f = fields.get(i);
            if (f == null) { fp.add(WILDCARD); continue; }
            switch (vector.get(i)) {
                case PU -> fp.add(f);
                case CO -> fp.add(Crypto.hex(Crypto.sha256(f)));
                case PR -> fp.add(PRIVATE_MARKER);
            }
        }
        return List.copyOf(fp);
    }

    /**
     * Template match, as in DepSpace: same arity, and every defined template field
     * equals the corresponding entry field. PR fields never match by value, so a
     * template must wildcard them.
     */
    public static boolean matches(List<String> entryFp, List<String> templateFp) {
        if (entryFp.size() != templateFp.size()) return false;
        for (int i = 0; i < entryFp.size(); i++) {
            String t = templateFp.get(i);
            if (WILDCARD.equals(t)) continue;
            if (PRIVATE_MARKER.equals(t)) continue; // private fields are not comparable
            if (!t.equals(entryFp.get(i))) return false;
        }
        return true;
    }

    /** Stable content-addressable index key for a fingerprint. */
    public static String key(List<String> fp) {
        return String.join("\u001f", fp);
    }
}
