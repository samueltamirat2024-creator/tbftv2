# Standing up a pool and filling the two Indy rows

Target:

| Configuration | Throughput (ops/s) | p50 / p95 / p99 latency |
|---|---|---|
| Hyperledger Indy baseline — read-heavy | — | — |
| Hyperledger Indy baseline — bursty-revoke | — | — |

Both rows come out of `vdr.bench.Bench` — the same class, the same generator and the same metric
definitions that produce the Tailored BFT rows. Only `-Dvdr.backend` changes. That is the whole
point of baseline plan §8: two generators would mean two definitions of throughput and the four
rows would stop being comparable.

---

## 1. A pool you can actually run (milestone B0)

For functional bring-up on a laptop, von-network is the path of least resistance: it builds a
four-node Indy pool and serves the genesis file over HTTP.

```bash
git clone https://github.com/bcgov/von-network
cd von-network
./manage build
./manage start
```

The genesis file is then at `http://localhost:9000/genesis`:

```bash
curl -s http://localhost:9000/genesis -o /tmp/pool_transactions_genesis
export INDY_GENESIS=/tmp/pool_transactions_genesis
```

Sanity-check the pool at `http://localhost:9000` — the ledger browser should show four nodes.

**This is bring-up, not measurement.** Four nodes on one laptop share a CPU, a disk and a
loopback interface. Numbers from it say nothing about a four-machine pool and must not go in the
paper. The reported rows come from the cluster described in baseline plan §6, one node per machine,
same hardware class as the BFT replicas, with `pool/docker-compose.yml` and pinned image digests.

## 2. The library

```bash
pip3 download indy-vdr --no-deps -d /tmp/ivdr
unzip -o /tmp/ivdr/indy_vdr-*.whl -d /tmp/ivdr/x
ls /tmp/ivdr/x/indy_vdr/          # libindy_vdr.dylib on macOS, .so on Linux
export INDY_VDR_LIB=/tmp/ivdr/x/indy_vdr/libindy_vdr.dylib
```

Verify the binding before anything else:

```bash
./indy-baseline/build.sh probe "$INDY_VDR_LIB"     # expect: passed 14, failed 0
```

## 3. Setup transactions (not workload)

The revocation registry must exist before the mixes run. SCHEMA, CRED_DEF and REVOC_REG_DEF are
written once here, before the measurement window, exactly as the Tailored BFT DID pool is
populated before warm-up. They never appear in a mix — counting registry creation as workload
would inflate Indy's write cost and flatter our system.

```bash
pip3 install indy-vdr anoncreds
python3 indy-baseline/pool/setup_registry.py \
    --genesis "$INDY_GENESIS" --seed 000000000000000000000000Trustee1
```

It prints a `REVOC_REG_DEF_ID`; export it:

```bash
export INDY_REVOC_REG_DEF_ID='<printed value>'
export INDY_SUBMITTER_DID='<printed DID>'
```

## 4. Run

```bash
./indy-baseline/build.sh bench 4
```

This writes `RESULTS.md` with the two Indy rows filled and the two Tailored BFT rows marked
`not measured` — the mirror image of what `./build.sh bench` produces. Keep both files; the paper's
four-row table is the union.

## 5. Before any of it counts

Three decisions must be made and signed off first, because all three change what gets measured and
all three are far more expensive to revisit once data exists:

- `docs/read-equivalence.md` — does a resolve return the identifier record or the full document?
- `docs/parity-audit.md` — the storage decision. Indy persists to disk; the Tailored BFT prototype
  does not. Comparing as-is hands us a large unearned advantage on writes.
- `docs/baseline-tuning.md` — the sweep, including Indy's revoked-indices-per-entry against our
  `batchSize`. Left at the default, `indy.entriesPerTxn` equals our `batchSize` so the two systems
  batch identically; sweeping them apart without saying so would manufacture a result.
