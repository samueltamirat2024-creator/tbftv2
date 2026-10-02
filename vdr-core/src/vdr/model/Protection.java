package vdr.model;

/**
 * DepSpace field protection types (plan section 2.1).
 *
 *   PU - public:     not encrypted, comparable arbitrarily, disclosed if a replica is faulty
 *   CO - comparable: encrypted, but a collision-resistant hash is stored so equality
 *                    matching still works (this is how a verifier asks "is THIS
 *                    credential revoked?" without the replica set learning the handle)
 *   PR - private:    encrypted, no hash, never matched, never disclosed
 */
public enum Protection { PU, CO, PR }
