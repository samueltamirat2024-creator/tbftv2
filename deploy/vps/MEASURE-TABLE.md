# Measuring the four-row table on one 4 vCPU server

```
Configuration                              Throughput (ops/s)   p50 / p95 / p99 latency
Tailored BFT VDR — read-heavy
Tailored BFT VDR — bursty-revoke
Hyperledger Indy baseline — read-heavy
Hyperledger Indy baseline — bursty-revoke
```

`measure-table.sh` measures exactly these four rows, nothing else, and writes them to
`results/table/<UTC stamp>/TABLE.md`.

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

Other knobs: `SYSTEMS=tailored` or `SYSTEMS=indy` (one system only), `TAILORED_LEVELS=...`,
`INDY_LEVELS=...` (comma-separated sweep levels), `VDR_MAX_BATCH_DELAY_MS` (default 50, applied
to both systems).

## 4. What the script does

1. `cpu-plan.sh` with `NODE_CPUS=1`: node *i* (Indy validator or BFT-SMaRt replica) gets
   `--cpus 1 --cpuset-cpus i-1`, cores 0, 1, 2, 3.
2. Builds the images once (the runner image compiles everything and fetches BouncyCastle for
   Ed25519; replicas then start without recompiling).
3. **Tailored BFT VDR**: stops the Indy pool, starts four fresh replicas, waits until every
   replica has authenticated channels to the other three (BFT-SMaRt 1.2 occasionally fails one
   key exchange at startup; such a cluster is recreated, never measured), then runs
   `vdr.bench.Bench` for both mixes.
4. **Indy**: wipes the ledger, starts the four validators, fetches the genesis file, **stops the
   ledger browser** (it would otherwise compete for CPU), mints one fresh revocation registry per
   run, and runs the same `vdr.bench.Bench` for both mixes.
5. Merges the two `rows.tsv` files into `TABLE.md`.

For each mix the harness sweeps the offered load, bisects the knee (`bench.refine`), then
re-measures the highest level whose schedule is kept and whose **merged** p99 is within 500 ms,
stepping down if the full runs miss it. Throughput is committed operations per second at that
level; latency percentiles come from the merged histogram of all runs at that level.

## 5. The one compromise on a 4 vCPU server

Four nodes take all four vCPUs, so the load generator shares cores 0–3 with them. It is the same
generator with the same CPU allowance for both systems, so the comparison stays like-for-like, but
both rows are measured with some generator interference. If you can, use a server with more
vCPUs: the script still gives every node exactly 1 pinned vCPU and puts the generator on the
remaining cores (e.g. 8 vCPU: nodes on 0–3, generator on 4–7).

## 6. Output

```
results/table/<stamp>/
  TABLE.md                  the four rows
  measure.log               everything the run printed
  tailored-RESULTS.md       full Tailored harness output (sweep path, burst, per-operation split)
  tailored-environment.txt  jars, BFT-SMaRt config, CPU, JVM
  indy-RESULTS.md           full Indy harness output
  replica-logs/, indy-node-logs/
```

## Troubleshooting

| Symptom | Fix |
|---|---|
| `need at least 4 vCPUs` | Resize the server. |
| `replica startup handshake incomplete` three times | `docker compose --profile bftsmart logs vdr-replica-0`; usually a stale `config/currentView` — delete it and retry. |
| `pool not ready after 20 min` | `docker compose logs webserver node1`; confirm the host is x86-64. |
| `no unused registry left` | Raise `INDY_REGISTRY_COUNT` (default is computed from the levels and runs). |
| `Ed25519: JDK (bcprov not fetched)` | The image could not reach Maven Central. Runs still work; signatures cost ~1 ms instead of ~0.05 ms, and every row's notes say which was used. |
