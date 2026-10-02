# Claims → experiments

What each claim in the paper rests on, where an evaluator checks it, and what would falsify it.
The last table is the important one: claims this artifact **cannot** support.

## Supported: safety and functional claims

| # | Claim | Check | Expected | Falsified by |
|---|---|---|---|---|
| C1 | A client cannot write to a DID it does not control. | `gates.log`, M1 | PASS | any accepted write under a key not authorised in the current document version |
| C2 | Replicas agree on checkpoint roots, so read proofs verify against a root every honest replica committed. | `gates.log`, M3 (10k mixed ops) | PASS | byte-differing roots across the n replicas |
| C3 | Enabling the confidentiality layer does not break root equality — the state root covers only the replica-identical projection. | `gates.log`, M7 | PASS | divergent roots once per-replica shares exist |
| C4 | A Byzantine replica cannot serve a forged document on the fast path. | `gates.log`, M6 | PASS | a client accepting a document whose inclusion proof does not verify |
| C5 | A stale-but-valid read never satisfies a freshness-critical query. | `gates.log`, M6 | PASS | a Tier-1 revocation check answered from an older epoch |
| C6 | **Key safety claim.** No fault scenario within the f bound causes a missed revocation to be served on the read path. | `gates.log`, M6 | PASS | any scenario where a committed revocation reads as not-revoked |
| C7 | Damage from a Byzantine issuer is recoverable and bounded (DepSpace Algorithm 3: repair, then blacklist). | `gates.log`, M6 | PASS | a blacklisted client's later writes taking effect |
| C8 | Policy verdicts are produced on every correct replica, not delegated to the gateway. | `gates.log`, M2 | PASS | a write accepted by the core that the gateway alone had approved |
| C9 | The state machine is deterministic: replaying one ordered log against fresh replicas reproduces the state exactly. | `gates.log`, M1 replay | PASS | differing final roots or reply digests |
| C10 | A revocation burst commits in a number of consensus instances bounded by batch size, not by burst size. | `gates.log`, M5; `bench.log` burst line | 2000 revocations in 2 instances | instance count growing with burst size |

C1–C10 hold on a single machine and are what the Functional badge covers.

## Partially supported: mechanism claims

| # | Claim | Check | Caveat |
|---|---|---|---|
| C11 | The read-only fast path substantially reduces resolve latency versus the ordered path. | `gates.log`, M4 gate: unordered p50 must beat ordered p50 by ≥2× | **Treat with suspicion.** Against the simulator the ordered path pays a hardcoded 3 ms constant, so the gate currently measures that constant, not the design. It becomes a real test only against BFT-SMaRt. |
| C12 | Revocation batching raises burst throughput. | `bench.log` burst drain line | Direction is real — batching genuinely reduces instance count — but the magnitude inherits the same constant. |

## Not supported by this artifact

| # | Claim | Why not | Where it gets settled |
|---|---|---|---|
| C13 | Throughput and latency figures in Table III. | The consensus core is a simulator and the latency is dominated by two hardcoded constants. | M9, against BFT-SMaRt on a real cluster |
| C14 | The system outperforms a Hyperledger Indy baseline. | No baseline is deployed here; both baseline rows read "not measured". | M8 (baselines frozen, digests published) then M9 |
| C15 | Confidentiality of revocation metadata under f compromised replicas. | PVSS is not implemented — only share storage and the state-root exclusion rule. | M7, and it is scoped as optional: H1 is testable without it |
| C16 | Behaviour under crash, partition, leader failure or clock skew. | Jepsen-style nemeses need a network; there isn't one. | M6 against the real engine |
| C17 | A compromised gateway cannot violate integrity. | Demonstrated only by core-side re-authorisation in M2; the gateway is an in-process library, not a deployment boundary. | M6 Byzantine-gateway scenario, once the Go gateway exists |

We are not applying for Results Reproduced. Listing C13–C17 here is cheaper than having a reviewer
derive them, and the alternative — letting a matching throughput number imply a corroborated
performance claim — would be worse than useless.
