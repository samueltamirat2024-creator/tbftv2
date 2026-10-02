# Pool bring-up (milestone B0)

This directory is the shape of the four-node pool, not a working one. Two things are missing and
both are deliberate:

1. **Image digests are placeholders.** Baseline plan §2 requires the exact indy-node and
   indy-plenum versions plus per-node image digests to be frozen and published before the first
   number is collected. A `REPLACE_WITH_PINNED_DIGEST` left in an archived artifact is a
   reproducibility claim that fails on first use.
2. **No genesis file.** Generate it for your four nodes and record its hash in
   `docs/baseline-tuning.md`.

## Client libraries — use the current ones

indy-sdk is deprecated. Benchmarking a deprecated client is an easy reviewer objection.

| Use | Not |
|---|---|
| indy-vdr (ledger client) | indy-sdk |
| anoncreds-rs (revocation) | indy-anoncreds |
| askar (wallet/keys) | indy-wallet |

The GitHub organisation moved to `hyperledger-indy`; confirm the current stable release and any
advisories at build time rather than trusting a document written earlier.

## Where measurement happens

On the same cluster hardware as the Tailored BFT VDR — same machine class, same core count, same
memory, same disk class, same network, one node per machine. A laptop is fine for functional
bring-up and for running `../build.sh probe`. It is not where any number in the paper comes from.
