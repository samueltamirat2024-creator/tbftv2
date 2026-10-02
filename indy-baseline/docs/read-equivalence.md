# Decision record: read equivalence

**Status:** DECIDE BEFORE ANY MEASUREMENT. Co-author sign-off required.
**Why now:** this is far more expensive to change once data exists, and it is the single easiest
place to manufacture whichever result we want (baseline plan §4).

## The problem

Our client has three read tiers. Indy's client can serve a read from one node with a state proof,
or from a quorum. Comparing our Tier 0 against Indy's quorum read — or our Tier 2 against Indy's
single-node read — would produce a speed-up in either direction on demand.

## The binding

| Our tier | Indy configuration | Both give |
|---|---|---|
| Tier 0 (default) | single node, Patricia-trie state proof + BLS multi-signature, verified in indy-vdr | one round trip, cryptographic proof against a committed root, stale-but-valid possible |
| Tier 1 (freshness-critical) | read from f+1 nodes, require agreement | quorum freshness |
| Tier 2 (fallback) | read sent through consensus | ordered read |

- Read-heavy row: **Tier 0 on both**, proof verification enabled and inside the measured latency on
  both. Turning Indy's state-proof verification off to "reduce client overhead" would be measuring
  a weaker system and must not happen.
- Revocation status in the bursty-revoke mix: **Tier 1 on both**, because the freshness claim rests
  on it.
- Every row reports which tier produced it. A row that does not say is not comparable to anything.

## What a resolve returns — DECIDE

Our `resolve` returns the latest DID document. Indy's `GET_NYM` returns the NYM record. These are
not the same read.

- [ ] **Option A — identifier record.** Our resolve returns the DID head; Indy issues `GET_NYM`
      only. Cheaper on both; tests the ledger lookup, not document retrieval.
- [ ] **Option B — full document.** Our resolve returns the document; the Indy mix issues
      `GET_NYM` plus the corresponding `GET_ATTRIB`, and population writes the ATTRIBs. Closer to
      what a real resolver does, and more expensive on Indy.

Option B is the more defensible choice for an SSI paper, and it is the one that costs Indy more —
which is exactly why choosing it deliberately, in advance, is worth doing.

**Chosen:** ______   **Signed:** Samuel ____  Sileshi ____  Miguel ____   **Date:** ______
