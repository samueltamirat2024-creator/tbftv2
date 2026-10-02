# Building and running the Tailored BFT VDR and the Indy VDR baseline

This guide covers both systems in this repository:

| System | Code | What running it gives you |
|---|---|---|
| **Tailored BFT VDR** | `vdr-core/` | the milestone gates (M1–M7) and the two Tailored BFT rows of the evaluation table |
| **Indy VDR baseline** | `indy-baseline/` + `von-network/` | the libindy_vdr binding probe and the two Hyperledger Indy rows |

Both systems use the same load generator, `vdr.bench.Bench`. The only difference between the two
runs is the `-Dvdr.backend` switch (`tailored` or `indy`), so the throughput and latency definitions
in `bench/METRICS.md` are the same for all four rows.

Run every command from the repository root (`tailored-bft-vdr/`) unless the step says otherwise.

To run both systems in Docker Compose on one Linux server instead, see
[`deploy/vps/README.md`](../deploy/vps/README.md).

---

## 0. Prerequisites

| Tool | Needed for | Check |
|---|---|---|
| JDK 21+ | both systems | `java -version` |
| Docker (running) | Indy pool (von-network) | `docker info` |
| Python 3 + pip | extracting libindy_vdr, writing the Indy registry | `python3 --version` |
| curl, unzip | fetching the genesis file and the library | — |

Install a JDK:

```bash
brew install --cask temurin@21          # macOS
sudo apt install openjdk-21-jdk         # Debian/Ubuntu
```

The Tailored BFT VDR needs only the JDK: no Maven or Gradle, no network access and no third-party
jars.

---

## Part A — Tailored BFT VDR

### A1. Build

```bash
./build.sh
```

This compiles every `.java` file under `vdr-core/src` into `build/classes`. Expected output:

```
compiled to build/classes — try: ./build.sh gates | ./build.sh bench [n]
```

### A2. Run the milestone gates (~30 s)

```bash
./build.sh gates
```

This runs `vdr.test.Gates`, which covers the M1–M7 go/no-go questions as executable assertions.
Expected last line:

```
passed 12, failed 0
```

A failing gate is a real finding about the design, not a setup problem.

### A3. Run the evaluation harness (~4 min)

```bash
./build.sh bench        # n = 4 replicas (f = 1), the default
./build.sh bench 7      # n = 7 (f = 2)
./build.sh bench 10     # n = 10 (f = 3)
```

This runs `vdr.bench.Bench` with `-Dvdr.backend=tailored` (the default). It does 10 runs per
configuration, discards a 1 s warm-up and uses an open-loop arrival schedule. When it finishes it
writes **`RESULTS.md`** in the repository root, containing:

- the *read-heavy* and *bursty-revoke* rows (throughput, p50/p95/p99),
- the per-operation throughput split,
- revoke-burst drain statistics (reported separately).

The Indy rows are shown as "not measured" in this file.

### A4. One-command reproduction (optional)

```bash
./artifact/run-all.sh            # environment + build + gates + bench, n = 4 (~5 min)
./artifact/run-all.sh 7          # same, n = 7
./artifact/run-all.sh 4 quick    # environment + build + gates only (~30 s)
```

Outputs go to `artifact/results/<UTC-timestamp>-n<N>/`: `environment.txt`, `build.log`,
`gates.log`, `bench.log`, `RESULTS.md`.

> **Read before quoting a number.** By default the replication layer is
> `vdr.replication.SimulatedCluster`, an in-JVM total-order simulator, not BFT-SMaRt. Its latencies
> are mostly set by two hardcoded constants in `SimulatedCluster.standard()`: 3000 µs per consensus
> instance and 300 µs per round trip. These rows show that the measurement pipeline works. They are
> not paper numbers, and the harness marks them as such.
>
> For the real engine, put `library-1.2.jar` in `vdr-bftsmart/lib/` and run with
> `-Dvdr.replication=bftsmart` (or `deploy/vps/run-protocol.sh tailored-bft`). See
> `docs/BFT-SMART-WIRING.md`.

---

## Part B — Indy VDR baseline

The baseline has four stages:

1. start a four-node Indy pool,
2. get `libindy_vdr`,
3. write the setup transactions,
4. run the same benchmark with `-Dvdr.backend=indy`.

Steps B1–B3 do not need the pool, so you can check the Java side first.

### B1. Build the baseline module

```bash
./indy-baseline/build.sh
```

This compiles `indy-baseline/src` into `build/indy` against `build/classes`, and builds vdr-core
first if needed. The module uses `java.lang.foreign`, which is a preview API in Java 21, so the
script passes `--enable-preview`. Compiler warnings about preview features are expected and are
filtered out.

### B2. Get libindy_vdr

The native library ships inside the `indy-vdr` Python wheel:

```bash
pip3 download indy-vdr --no-deps -d /tmp/ivdr
unzip -o /tmp/ivdr/indy_vdr-*.whl -d /tmp/ivdr/x
ls /tmp/ivdr/x/indy_vdr/        # libindy_vdr.dylib (macOS) or libindy_vdr.so (Linux)

export INDY_VDR_LIB=/tmp/ivdr/x/indy_vdr/libindy_vdr.dylib   # use .so on Linux
```

`/tmp` is cleared on reboot. If you want the library to persist, extract it somewhere else.

### B3. Probe the binding (no pool needed)

```bash
./indy-baseline/build.sh probe "$INDY_VDR_LIB"
```

This runs 14 offline checks against the real library: request construction, signing, batching and
error handling. Expected result:

```
passed 14, failed 0
```

If the probe fails, stop here. The benchmark cannot work until the binding does.

### B4. Start the Indy pool (von-network)

A copy of von-network is already in `von-network/`, so you don't need to clone it.

```bash
cd von-network
./manage build          # first time only; builds the node image
./manage start          # starts 4 nodes + ledger browser on :9000
cd ..
```

On Apple Silicon, `manage` sets `DOCKER_DEFAULT_PLATFORM=linux/amd64` automatically, because there
are no arm64 images. On this Mac under that emulation (September 2026), the nodes hung after binding
their client port and the pool never became ready (`/status` showed `init_error`). Use an x86-64
machine for the pool, e.g. the Compose setup in `deploy/vps/`.

Check that the pool is up: open <http://localhost:9000>. The ledger browser should show four nodes.

Fetch the genesis file:

```bash
curl -s http://localhost:9000/genesis -o /tmp/pool_transactions_genesis
export INDY_GENESIS=/tmp/pool_transactions_genesis
```

Other useful von-network commands:

```bash
./manage logs           # follow node logs
./manage stop           # stop, keep ledger data
./manage down           # stop and delete ledger data (fresh pool next start)
```

If you run `./manage down`, the ledger is wiped. Fetch the genesis file again and redo B5 before
the next benchmark.

### B5. Write the setup transactions

SCHEMA, CRED_DEF and REVOC_REG_DEF are written **once, before measurement**. They are not part of
the workload.

```bash
python3 -m venv .venv-indy && source .venv-indy/bin/activate
pip install indy-vdr anoncreds base58 pynacl

python3 indy-baseline/pool/setup_registry.py \
    --genesis "$INDY_GENESIS" \
    --seed 000000000000000000000000Trustee1
```

The seed is von-network's default trustee. The script prints two lines to paste into your shell:

```bash
export INDY_SUBMITTER_DID=V4SGRU86Z58d6TV7PBUe6f
export INDY_REVOC_REG_DEF_ID='<printed value>'
```

The registry is sized so that the 800-revocation burst fits without a rollover.

`setup_registry.py` has only been checked for syntax; it has never been run against a live pool.
Expect to adjust it the first time you use it, and record any change in the script's docstring. If
`anoncreds` has no wheel for your Python version, create the venv with an older Python, e.g.
`python3.12 -m venv .venv-indy`.

### B6. Run the Indy benchmark

**Save the Tailored results first.** Both benchmarks write to the same `RESULTS.md`, so this run
would overwrite it:

```bash
cp RESULTS.md RESULTS-tailored.md
```

Then run:

```bash
./indy-baseline/build.sh bench 4
cp RESULTS.md RESULTS-indy.md
```

The script requires these environment variables and exits with a message if one is missing:

| Variable | Required | Meaning |
|---|---|---|
| `INDY_VDR_LIB` | yes | path to `libindy_vdr.dylib` / `.so` (B2) |
| `INDY_GENESIS` | yes | pool genesis file (B4) |
| `INDY_REVOC_REG_DEF_ID` | yes | printed by `setup_registry.py` (B5) |
| `INDY_SUBMITTER_DID` | no | endorser/trustee DID; default `V4SGRU86Z58d6TV7PBUe6f` |
| `INDY_ENTRIES_PER_TXN` | no | revoked indices per REVOC_REG_ENTRY; defaults to the gateway `batchSize` so both systems batch the same way. Change it only as a declared sweep. |

The Indy `RESULTS.md` has the two Indy rows filled and the Tailored rows marked "not measured".
This is the reverse of Part A. The paper's four-row table combines the two files.

### B7. Shut down

```bash
(cd von-network && ./manage stop)     # or ./manage down to wipe the ledger
deactivate                            # leave the Python venv
```

---

## Quick reference

```bash
# --- Tailored BFT VDR ---
./build.sh && ./build.sh gates && ./build.sh bench 4
cp RESULTS.md RESULTS-tailored.md

# --- Indy baseline ---
export INDY_VDR_LIB=/tmp/ivdr/x/indy_vdr/libindy_vdr.dylib
./indy-baseline/build.sh probe "$INDY_VDR_LIB"
(cd von-network && ./manage build && ./manage start)
curl -s http://localhost:9000/genesis -o /tmp/pool_transactions_genesis
export INDY_GENESIS=/tmp/pool_transactions_genesis
python3 indy-baseline/pool/setup_registry.py --genesis "$INDY_GENESIS" \
    --seed 000000000000000000000000Trustee1          # then export the two printed lines
./indy-baseline/build.sh bench 4
cp RESULTS.md RESULTS-indy.md
```

---

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `javac: invalid source release` or an `UnsupportedClassVersionError` | JDK older than 21. Check `java -version` and `JAVA_HOME`. |
| `usage: ./indy-baseline/build.sh probe /path/to/libindy_vdr.so` | No library path given. Pass it, or set `INDY_VDR_LIB`. |
| `UnsatisfiedLinkError` / library fails to load | Wrong file for the OS (use `.dylib` on macOS, `.so` on Linux), or a wheel built for a different CPU architecture. |
| `INDY_GENESIS: set INDY_GENESIS to …` | A required variable is unset in this shell. `export` does not carry over to new terminals. |
| `-Dindy.revocRegDefId is required` | B5 was skipped, or its output was not exported. |
| Benchmark times out / pool unreachable | Pool is down, or the genesis file is from an earlier pool that was wiped with `./manage down`. Fetch the genesis file again and redo B5. |
| `unknown -Dvdr.backend=…` | Only `tailored` (alias `bft`) and `indy` are valid. |
| Harness aborts, saying no load level qualifies | Every offered load exceeded the 500 ms p99 target. The harness refuses to report saturated runs. On a laptop Indy pool this is likely. |

---

## Laptop runs are for bring-up, not the paper

A laptop is fine for building, running the gates, running the probe and checking that the Indy
pipeline works end to end. **Numbers from it do not go in the paper:**

- Four Indy nodes on one host share a CPU, a disk and loopback, and on Apple Silicon they also run
  under x86 emulation.
- The Tailored rows come from a simulator with fixed latency constants.

Reported numbers come from a real cluster: one node per machine, the same hardware class for both
systems, pinned image digests in `indy-baseline/pool/docker-compose.yml`, and BFT-SMaRt in place of
the simulator.

Before collecting any number, settle the decisions in these files:

- `indy-baseline/docs/read-equivalence.md` — what a resolve returns
- `indy-baseline/docs/parity-audit.md` — storage and other asymmetries
- `indy-baseline/docs/baseline-tuning.md` — the batching sweeps and frozen provenance
