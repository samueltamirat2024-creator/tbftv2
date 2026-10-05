> **For the four-row comparison table (4 nodes × 1 vCPU), use `./measure-table.sh` —
> see [MEASURE-TABLE.md](MEASURE-TABLE.md).** The rest of this page describes the individual services.

# Running both systems on one virtual server (Docker Compose)

This runs a four-node Indy pool and both benchmark runners on a single Linux VPS.

| Service | What it does | Starts with |
|---|---|---|
| `node1`–`node4` | Indy pool (von-network image), fixed addresses `172.28.0.11`–`.14` | `docker compose up -d` |
| `webserver` | Ledger browser; serves `/genesis` once the pool is reachable | `docker compose up -d` |
| `tailored` | Tailored BFT VDR: `artifact/run-all.sh` (environment, build, gates, harness) | `docker compose run --rm tailored` |
| `indy-bench` | Indy baseline: binding probe, one-time registry setup, harness | `docker compose run --rm indy-bench` |

> **Bring-up, not measurement.** Everything shares one machine, and the Tailored BFT runner still
> uses the in-JVM simulator. No number from this stack belongs in the paper. See
> `docs/BUILD-AND-RUN.md` and the testbed plan for the multi-machine setup.

## 1. The server

- **x86-64 (amd64) only.** The von-network images have no arm64 build. Under emulation on Apple
  Silicon, the Indy nodes hang after binding their client port and the pool never becomes ready.
- At least 4 vCPU, 8 GB RAM and 30 GB disk. Ubuntu 22.04 or 24.04.
- Only SSH needs to be open to the internet. The stack publishes one port, bound to `127.0.0.1`.

Install Docker with the Compose plugin:

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker "$USER"      # log out and back in afterwards
docker compose version
```

## 2. Copy the repository

The project is not a git repository, so copy it from your machine:

```bash
rsync -av --exclude build --exclude .DS_Store --exclude 'deploy/vps/results' \
  "tailored-bft-vdr/" <user>@<vps>:~/tailored-bft-vdr/
```

`von-network/` must be included, because the pool image is built from it.

## 3. Plan the vCPUs, then start the pool

Every node runs on at least one whole vCPU. `cpu-plan.sh` reads the server's vCPU count and
writes `.env` (which Compose loads automatically):

| Server | node1–node4 | Tailored runner (n = 4) |
|---|---|---|
| 4 vCPU | 1 vCPU each, pinned to cores 0, 1, 2, 3 | 4 vCPU, one replica thread per vCPU |
| 8 vCPU | 2 vCPU each, pinned to cores 0-1, 2-3, 4-5, 6-7 | 8 vCPU, 2 per replica |
| 2 vCPU | nodes share cores (warning printed) | 2 vCPU, 0.5 per replica (warning) |

Rule: per-node vCPUs = max(1, floor(total / 4)). Each node is both capped (`cpus`) and pinned
(`cpuset`), so one busy node cannot take time from another. The Tailored BFT runner executes each
replica on its own thread and gets `TAILORED_N × per-node` vCPUs, so its replicas have the same
budget as an Indy node. It prints `vCPUs available = …, vCPU per replica = …` at the top of
`bench.log`.

```bash
cd ~/tailored-bft-vdr/deploy/vps
./cpu-plan.sh                       # or NODE_CPUS=2 ./cpu-plan.sh, TAILORED_N=7 ./cpu-plan.sh
docker compose up -d --build        # first build takes several minutes
docker compose ps                   # node1-4 and webserver should be "Up"
```

The pool is ready when `/genesis` returns the genesis file instead of `{"detail": "Not ready"}`:

```bash
curl -s http://127.0.0.1:9000/genesis | head -c 200
curl -s http://127.0.0.1:9000/status
```

To open the ledger browser from your laptop, tunnel the port:

```bash
ssh -L 9000:localhost:9000 <user>@<vps>      # then open http://localhost:9000
```

## 4. Run the Tailored BFT VDR

```bash
docker compose run --rm tailored                         # gates + harness, n = 4 (~5 min)
TAILORED_MODE=quick docker compose run --rm tailored     # gates only (~30 s)
TAILORED_N=7 ./cpu-plan.sh && docker compose run --rm tailored   # n = 7 (re-plan first)
```

On a 4 vCPU server the Tailored runner's four replicas use the same cores as the Indy nodes.
For a like-for-like measurement, stop the pool first: `docker compose stop`, run `tailored`,
then `docker compose start`.

Output goes to `results/tailored/<timestamp>-n<N>/`. The gates should report `passed 12, failed 0`.

## 5. Run the Indy baseline

```bash
docker compose run --rm indy-bench
```

`run-indy.sh` then does the following:

1. waits up to 5 minutes for `/genesis`;
2. runs the binding probe (expect `passed 14, failed 0`);
3. runs `setup_registry.py --count $INDY_REGISTRY_COUNT` (default 40), which writes one schema and
   credential definition and then `$INDY_REGISTRY_COUNT` independent revocation registries to
   `results/indy/registries.env`, and resets `registry.counter`;
4. runs `vdr.bench.Bench` with `-Dvdr.backend=indy`, n = 4. Each `IndyBackend` — that is, each
   measured run — takes the next unused registry from that list and prints how many indices were
   already revoked on it at open, which must be 0.

Output goes to `results/indy/<timestamp>/`: genesis, `probe.log`, `setup.log`, `registries.env`,
`bench.log`, `RESULTS.md`.

**Why a registry per run.** A revocation registry accumulates state, and its accumulator chain gets
longer with every entry. Runs that shared one registry drifted upward across the sequence — the burst
drain went 1256 → 1628 → 2831 ms over three runs of the same configuration — which measures registry
growth, not the system. `./reset-pool.sh [count]` wipes the ledger and mints a fresh batch, and
`run-protocol.sh indy` calls it before a reportable run.

## Settings

Set these as environment variables before `docker compose`, or put them in a `.env` file next
to `docker-compose.yml`.

| Variable | Default | Meaning |
|---|---|---|
| `TAILORED_N` | `4` | Replica count for the Tailored runner (4, 7, 10) |
| `NODE_CPUS` | `max(1, vCPUs/4)` | vCPUs per Indy node (set by `cpu-plan.sh`) |
| `NODE1_CPUSET`…`NODE4_CPUSET` | from `cpu-plan.sh` | Cores each node is pinned to |
| `TAILORED_CPUS` | `TAILORED_N × NODE_CPUS` | vCPU limit of the Tailored runner |
| `BENCH_CPUS`, `AUX_CPUSET` | from `cpu-plan.sh` | vCPU limit and cores of `indy-bench` and `webserver` |
| `TAILORED_MODE` | `full` | `quick` skips the harness |
| `INDY_ENTRIES_PER_TXN` | gateway `batchSize` | Revoked indices per REVOC_REG_ENTRY; change only as a declared sweep |
| `INDY_REGISTRY_COUNT` | `40` | Fresh revocation registries minted per pool reset; must cover every measured run |
| `INDY_TRUSTEE_SEED` | von-network's default trustee | Seed used for the registry setup |
| `INDY_LOG_LEVEL` | `warning` | Node log level; `info` for debugging (logs in `/home/indy/log/sandbox/`) |
| `WEB_SERVER_HOST_PORT` | `9000` | Loopback port for the ledger browser |

Indy batching knobs (`Max3PCBatchSize`, `Max3PCBatchWait`) are in `indy_config.py` in this
folder. Keep them in step with `indy-baseline/pool/indy_config.py`, and log every value tried in
`indy-baseline/docs/baseline-tuning.md`. Restart the nodes after changing them:
`docker compose restart node1 node2 node3 node4`.

## Day-to-day commands

```bash
docker compose logs -f webserver                  # pool connection progress
docker compose logs --tail 50 node1
docker compose stop                               # stop, keep ledger data
./reset-pool.sh 40                                # wipe the ledger + mint 40 fresh registries
```

Use `reset-pool.sh` rather than `docker compose down -v` by hand: wiping the ledger without deleting
`registries.env` and `registry.counter` leaves the next run pointing at revocation registries that no
longer exist. The script removes all three and re-runs setup.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `Bind for 0.0.0.0:9000 failed: port is already allocated` | Another service uses the port. Set `WEB_SERVER_HOST_PORT=9010`. |
| `/status` shows `"init_error": "Error initializing pool ledger"` | The web server gave up before the nodes were ready. Check `docker compose ps`, then `docker compose restart webserver`. |
| Nodes keep restarting | Read `docker compose logs node1`. If the genesis step failed, wipe the ledger (see above) and start again. |
| `pool not ready after 5 minutes` from `indy-bench` | Same as the two rows above. Also check that the server is x86-64. |
| `WARNING: … nodes share cores` from `cpu-plan.sh` | The server has fewer than 4 vCPUs. Resize to 4+ vCPU. |
| `Requested CPUs are not available` / `invalid cpuset` | `.env` was planned for a bigger server. Re-run `./cpu-plan.sh`. |
| `-Dindy.revocRegDefId is required` | `registries.env` is empty or missing. Run `./reset-pool.sh` so setup runs again. |
| `registry ... opened with N revoked index(es)`, N > 0 | A run is reusing a registry a previous run already wrote to; its numbers include that registry's growth. Run `./reset-pool.sh` and start the sequence again. |
| `no unused registry left` | The batch is exhausted. `./reset-pool.sh <count>` with a count at least as large as the number of measured runs (10 runs × levels swept). |
