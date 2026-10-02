# Indy baseline

The frozen comparison baseline for the Tailored BFT VDR (milestone M8 of the Tailored plan, B0–B7
of `indy-baseline-implementation-plan.md`).

```bash
./indy-baseline/build.sh                              # compile
./indy-baseline/build.sh probe "$INDY_VDR_LIB"        # verify the binding, no pool needed
./indy-baseline/build.sh bench 4                      # measure the two Indy rows (needs a pool)
```

**Full walkthrough to the two rows: `pool/BRING-UP.md`.**

Get the library from the indy-vdr wheel:

```bash
pip download indy-vdr --no-deps -d /tmp/ivdr
unzip -o /tmp/ivdr/indy_vdr-*.whl -d /tmp/ivdr/x
./indy-baseline/build.sh probe /tmp/ivdr/x/indy_vdr/libindy_vdr.so
```

## What is here

| Path | State |
|---|---|
| `src/vdr/baseline/indy/IndyVdr.java` | FFM binding to libindy_vdr — **verified against the real library** |
| `src/vdr/baseline/indy/IndyProbe.java` | 14 offline checks: request construction, signing, batching, errors |
| `src/vdr/baseline/indy/IndyBackend.java` | the four workload operations behind the shared `Backend` seam — **compiles, untested without a pool** |
| `pool/` | four-node compose file and config — **skeleton, digests are placeholders** |
| `docs/read-equivalence.md` | decision record; decide before measuring |
| `docs/parity-audit.md` | the disclosed asymmetries (storage, Byzantine injection, accumulator, tails) |
| `docs/baseline-tuning.md` | sweep log and frozen provenance |

## One generator, two backends

`vdr.baseline.Backend` in vdr-core is the seam. `TailoredBftBackend` and `IndyBackend` implement
it, so the arrival schedule, warm-up rule, merged-histogram percentiles, burst accounting and
operating-point admission are shared and only the system under test changes. Using an Indy-native
load tool for the baseline rows would mean two definitions of throughput and two treatments of
coordinated omission, and the four rows would stop being comparable.

## Java version

`java.lang.foreign` is a preview API in Java 21, so this module builds with `--enable-preview`.
vdr-core stays preview-free. On Java 22+ the API is final and the flag can be dropped with no
source change.
