# Deviations, and what belongs in the paper

Each entry is a place where this implementation departs from DepSpace or from the plan. Plan §6 and
§12.1 ask for several of these to be stated explicitly in Paper 2; the rest are recorded so a
reviewer finds them here rather than deriving them.

## From DepSpace

**Policies are compiled Java, not Groovy.** DepSpace accepts a policy as a Groovy class compiled at
space creation, sandboxed by a class loader restricted to reading tuple-space contents. A VDR's
policy set is small, stable and security-critical, so dynamic loading buys flexibility we do not
need at the cost of a large attack surface — DepSpace itself had to defend against a
`System.exit(0)` in a policy script. `P1`–`P6` are fixed at build time behind the `VdrPolicy`
interface. **State this in the paper** (plan §6).

**No outlier discarding.** DepSpace discarded the 5% highest-variance latency values. We report
everything, because that practice cannot support a p99 claim. **State this** if a reviewer compares
against DepSpace's figures (plan §12.1).

**`update` is an append, not remove-update-reinsert.** DepSpace's naming service works around the
tuple space's inability to update a stored tuple using a temporary tuple plus a policy that
prevents tree corruption. Our store is an append-only versioned log, so `update` is an append plus
a DIDHEAD pointer swap inside one ordered operation. This is a simplification the design earns by
not being a general tuple space (plan §3.2).

**Repair is scoped to REVENTRY.** DepSpace's Algorithm 3 applies to any tuple whose fingerprint
does not match its content. DIDDOC records are all-public and therefore fully verifiable at
admission, so only the confidential revocation payload needs the justification/deletion/blacklist
path (plan §3.5).

**Modern primitives.** SHA-256 not SHA-1; AES-256-GCM not 3DES; Ed25519 not RSA-1024. DepSpace's
cost table is still the sanity check for the PVSS layer, but only after normalising for this and
for hardware (plan §8).

## From the plan

**The confidentiality layer is share plumbing, not PVSS.** Plan §8 specifies DepSpace's (n, f+1)
publicly verifiable secret sharing with all three optimisations. What exists is the per-replica
share storage and the §5 exclusion rule that keeps shares out of the state root — enough for the M7
root-equality invariant to be a real test, not enough to claim confidentiality. DepSpace had to
implement PVSS from scratch because no non-trivial public implementation existed; plan §15 already
scopes this as "optional, may slip", and H1 is testable without it.

**The revocation accumulator is a hash chain.** `acc' = H(acc ‖ sorted(batch))`. It is monotone and
deterministic, which is what P5 and the checkpoint root need, but it is not a cryptographic
accumulator: there is no compact non-membership proof, so a holder cannot prove non-revocation
without the registry's handle set. A real deployment needs an RSA or bilinear-pairing accumulator.
This affects what the bursty-revoke row means: batch cost here is dominated by signature
verification, not by accumulator arithmetic, and a real accumulator will shift that balance.

**No wire serialisation, so plan §14's lesson is untested.** Everything runs in one JVM, so no
message is ever serialised. The custom `Externalizable` codecs the plan requires from the start do
not exist yet, and the BFT-SMaRt adapter currently uses default Java serialisation as a
placeholder — precisely what that lesson says not to ship. Write the codecs before the first
measurement run.

**Gateway batching is in-process Java, not the Go shim.** `vdr.replication.Gateway` implements the
aggregation and two-path routing of plan §3.4 and §9, but as a library inside the benchmark. The
separately deployable, horizontally scalable Go gateway is not written, so the plan's claim that a
compromised gateway cannot violate integrity is currently demonstrated only by the M2 gate
(core-side re-authorisation), not by a real deployment boundary.

**Fault injection is partial.** `ServiceReplica.Behaviour` supports equivocation, stale reads,
forged documents and revocation suppression, which is what the M6 gates exercise. The Jepsen-style
nemeses of plan §13 — kill replica, partition minority, partition leader, clock skew, packet loss —
have nothing to act on until there is a network.

## Numbers

A `RESULTS.md` produced on the simulated layer is not a measurement of a Byzantine system, and it
says so itself: the harness writes the provenance warning only when
`Replication.simulated()` is true, and the notes column carries
`SimulatedCluster.describe()`, which names the two hardcoded constants the latency figures come
from. Those rows exist to show the measurement pipeline is sound, which is M0's question.

Paper 2's Table III is filled at M9 from the BFT-SMaRt layer (`-Dvdr.replication=bftsmart`,
`deploy/vps/run-protocol.sh tailored-bft`), where neither constant exists. That path needs
`library-1.2.jar` in `vdr-bftsmart/lib/`; without it `vdr-bftsmart/build.sh` type-checks against API
stubs and refuses to start a replica, a bench or a gate run, so a stubbed cluster cannot produce a
number by accident.
