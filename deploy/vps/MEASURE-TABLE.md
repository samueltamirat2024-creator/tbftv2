# Measuring the comparison table on one 4 vCPU server

```
Configuration                              Throughput (ops/s)   p50 / p95 / p99 latency
Tailored BFT VDR — read-heavy
Tailored BFT VDR — bursty-revoke
Tailored BFT VDR — register
Tailored BFT VDR — update
Hyperledger Indy baseline — read-heavy
Hyperledger Indy baseline — bursty-revoke
Hyperledger Indy baseline — register
Hyperledger Indy baseline — update
```

`measure-table.sh` measures these rows and writes them to `results/table/<UTC stamp>/TABLE.md`.
`TABLE.md` holds four tables:
- every row above;
- **Register and Update**, Tailored next to Indy with ratios;
- the per-operation latency of updates and revocations inside the two mixed workloads
  (read-heavy = 95% resolve / 4% update / 1% revoke; bursty-revoke = 90% resolve / 10% update);
- the **Tier-0 fallback rate** of the Tailored BFT VDR in the mixed workloads, F of N reads over
  the measured windows of all runs (`bench/METRICS.md`, "Tier-0 fallback rate");
- **Revocation burst**, an experiment of its own: 800 revocations submitted at one instant halfway
  through each bursty-revoke run, on top of the steady stream. Tailored next to Indy with ratios:
  drain time (mean ± 95% CI), drain throughput, burst p50 / p95 / p99 measured from the injection
  instant, ordered revocation transactions the burst became (and revocations per transaction),
  and the steady stream's p99 in the same runs. With `EQUAL_LOAD=1` it is one block per
  bursty-revoke rate, so both systems carry the burst on the same background load.

The two VDR write operations each have a mix of their own:
- `register`: every arrival writes a fresh DID. On Tailored this is a DepSpace `cas`; on Indy, a
  trustee NYM.
- `update`: every arrival rotates the key of a pre-populated DID. On Tailored this is a versioned
  update; on Indy, a NYM verkey rotation signed by the DID's own key.

They use the same definitions as the other rows (`bench/METRICS.md`, "Register and Update mixes").
See `indy-baseline/docs/parity-audit.md` §8 for where the two write paths differ.

## 1. Server

- x86-64 Linux (Ubuntu 22.04/24.04), **4 vCPU**, 8 GB RAM, 30 GB disk. The von-network (Indy)
  images are amd64-only.
- Docker with the Compose plugin:

  ```bash
  curl -fsSL https://get.docker.com | sudo sh
  sudo usermod -aG docker "$USER"     # log out and back in
  sudo apt-get install -y tmux python3 curl
  ```

## 2. Copy the code

```bash
rsync -av --exclude build --exclude .DS_Store --exclude 'deploy/vps/results' \
  tailored-bft-vdr/ <user>@<vps>:~/tailored-bft-vdr/
```

(Or unzip the archive on the server.) `von-network/` must be included.

## 3. Run

```bash
cd ~/tailored-bft-vdr/deploy/vps
tmux new -s bench
PROFILE=quick ./measure-table.sh        # ~40 min: checks the whole pipeline end to end
./measure-table.sh                      # PROFILE=standard, ~2-3 h: the numbers to use
```

Detach with `Ctrl-b d`, re-attach with `tmux attach -t bench`.

| PROFILE | Sweep run (warm-up / window) | Reported runs (warm-up / window) | Runs | Use |
|---|---|---|---|---|
| `quick` | 5 s / 15 s | 10 s / 30 s | 3 | pipeline check only |
| `standard` | 15 s / 30 s | 60 s / 60 s | 5 | default |
| `full` | 60 s / 60 s | 60 s / 60 s | 10 | the paper's stated protocol |

Other knobs:
- `SYSTEMS=tailored` or `SYSTEMS=indy`: one system only.
- `MIXES="register update"`: a subset of `read-heavy bursty-revoke register update`.
- `TAILORED_LEVELS=...`, `INDY_LEVELS=...`: comma-separated sweep levels for the mixed workloads.
- `TAILORED_WRITE_LEVELS=...`, `INDY_WRITE_LEVELS=...`: sweep levels for `register` and `update`.
- `UPDATE_DID_POOL` (default 400): the DIDs the update mix rotates over. Raise it if a row reports
  contended updates.
- `VDR_MAX_BATCH_DELAY_MS` (default 50): applied to both systems.

**Equal offered load.** `EQUAL_LOAD=1 ./measure-table.sh` skips the sweep and measures every mix on
both systems at the same fixed rates: `EQ_READ_HEAVY` (50,100,200), `EQ_BURSTY_REVOKE` (25,50),
`EQ_REGISTER` (10,25,50) and `EQ_UPDATE` (10,25,50). Choose rates that both systems sustain. A row
marked SATURATED measures a queue.

```bash
MIXES="register update" ./measure-table.sh                # each system at its own knee
EQUAL_LOAD=1 MIXES="register update" ./measure-table.sh   # both systems at the same loads
```

## 4. What the script does

1. `cpu-plan.sh` with `NODE_CPUS=1`: node *i* (Indy validator or BFT-SMaRt replica) gets
   `--cpus 1 --cpuset-cpus i-1`, cores 0, 1, 2, 3.
2. Builds the images once (the runner image compiles everything and fetches BouncyCastle for
   Ed25519; replicas then start without recompiling).
3. **Tailored BFT VDR**: stops the Indy pool, starts four fresh replicas, waits until every
   replica has authenticated channels to the other three (BFT-SMaRt 1.2 occasionally fails one
   key exchange at startup; such a cluster is recreated, never measured), then runs
   `vdr.bench.Bench` for each mix (see below).
4. **Indy**: wipes the ledger, starts the four validators, fetches the genesis file, **stops the
   ledger browser** (it would otherwise compete for CPU), mints one fresh revocation registry per
   run, and runs the same `vdr.bench.Bench` for each mix.
5. Merges the two `rows.tsv` files into `TABLE.md`.

For each mix:
1. **Sweep** (its own invocation, `-Dbench.sweepOnly`). The harness sweeps the offered load on
   fresh state, bisects the knee (`bench.refine`), and lists every level whose schedule is kept and
   whose p99 is within 500 ms. It also prints the lowest level it failed (`SATURATED_AT`); the
   script saves the sweep lines to `<system>-<mix>-sweep.txt` and passes the ceiling to the
   measuring invocation (`-Dbench.sweepCeiling`).
2. **Measure on fresh state.** The highest admitted level is measured with the full `RUNS`, on a
   newly started cluster (Tailored) or a wiped ledger (Indy), in a new JVM. If its **merged** p99
   misses 500 ms (exit 3), the next level down is measured, again on fresh state. Every level tried
   is recorded in the row's admission path.

Each row's notes and `TABLE.md` then bracket the knee: `knee in (R, C]`, where C is the lowest
level that failed. A row is a lower bound only if no listed level failed, or if the generator's
in-flight cap rather than the system stopped it (`bench/METRICS.md`, "Knee bracket").

Throughput is committed operations per second at the reported level. Latency percentiles come
from the merged histogram of all runs at that level.

**Why fresh state per measured level.** The sweep deliberately pushes the system past its knee.
On a real 4-replica BFT-SMaRt cluster, measuring the operating point afterwards on the same cluster
and client measured that damage. Update at 500 ops/s dropped to 0–42 ops/s after a sweep, but held
500 ops/s with p99 15 ms on a fresh cluster. `FRESH_MEASURE=0` restores the old single-invocation
protocol, where the sweep and the measurement share one cluster. Use it only to reproduce older
tables.

**In-flight bound.** A level with more than 1 s of arrivals in flight is stopped early and marked
saturated (`bench/METRICS.md`, coordinated omission §2). Without that bound, a write-only mix past
its knee would exhaust the generator's threads.

## 5. The one compromise on a 4 vCPU server

Four nodes take all four vCPUs, so the load generator shares cores 0–3 with them. It is the same
generator with the same CPU allowance for both systems, so the comparison stays like-for-like, but
both rows are measured with some generator interference. If you can, use a server with more
vCPUs: the script still gives every node exactly 1 pinned vCPU and puts the generator on the
remaining cores (e.g. 8 vCPU: nodes on 0–3, generator on 4–7).

## 6. Output

```
results/table/<stamp>/
  TABLE.md                  the rows (with knee brackets), Register/Update, writes in the mixes,
                            Tier-0 fallbacks, revocation burst
  measure.log               everything the run printed
  tailored-<mix>-RESULTS.md full Tailored harness output (sweep path, burst, per-operation split)
  tailored-environment.txt  jars, BFT-SMaRt config, CPU, JVM
  indy-<mix>-RESULTS.md     full Indy harness output
  <system>-<mix>-sweep.txt  every sweep level and its verdict (the evidence for the knee bracket)
  replica-logs/, indy-node-logs/
```

## Troubleshooting

| Symptom | Fix |
|---|---|
| `need at least 4 vCPUs` | Resize the server. |
| `replica startup handshake incomplete` three times | `docker compose --profile bftsmart logs vdr-replica-0`; usually a stale `config/currentView` — delete it and retry. |
| `pool not ready after 20 min` | `docker compose logs webserver node1`; confirm the host is x86-64. |
| `no unused registry left` | Raise `INDY_REGISTRY_COUNT` (default is computed from the levels and runs). |
| `stopped early: N operations in flight > bound` | That sweep level is past the knee. This is expected, and the level counts as saturated. If it happens at the *reported* level (`OVERLOADED` in the notes), lower the levels. |
| `N contended updates` in a row's notes | Raise `UPDATE_DID_POOL`, for example to 1000. |
| `Ed25519: JDK (bcprov not fetched)` | The image could not reach Maven Central. Runs still work; signatures cost ~1 ms instead of ~0.05 ms, and every row's notes say which was used. |
