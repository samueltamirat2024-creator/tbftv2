# Baseline tuning log (milestone B3)

The M8 adversarial-review question is: **is the baseline tuned as carefully as our system?**
A baseline that is quietly under-configured produces a flattering result a reviewer can destroy in
one sentence. Record the sweep here, including the values that lost, so the search is visibly real.

## Swept knobs

| Knob | Where | Values tried | Chosen | Notes |
|---|---|---|---|---|
| `Max3PCBatchSize` | indy_config.py | | | mirror our maxbatchsize sweep |
| `Max3PCBatchWait` | indy_config.py | | | mirror our maxBatchDelay sweep |
| Revoked indices per REVOC_REG_ENTRY | issuer (`IndyBackend.entriesPerTransaction`) | | | **must** mirror our batchSize sweep — see below |
| Client connections / pool | indy-vdr | | | enough that the driver is not the bottleneck |
| Node CPU / memory | container | | | as for our replicas; Indy warns of OOM under sustained load on small nodes |

Fixing Indy at one revocation per transaction while our gateway batches 250 is the single easiest
way to produce a fraudulent-looking speed-up, and the most likely thing a reviewer checks.

## Anti-handicap checklist

- [ ] Debug/trace logging disabled on Indy.
- [ ] No monitoring plugin enabled on Indy that is not also enabled on ours.
- [ ] Nodes not co-located on one machine.
- [ ] Storage is local SSD, not a shared network volume.
- [ ] Pool fully caught up and stable before warm-up begins.
- [ ] No node in view change or catch-up at window start.
- [ ] Genesis pool sized to n, no demoted nodes.
- [ ] Revocation registry large enough that the burst does not trigger a rollover mid-window.

## Frozen provenance (milestone B0 — publish before the first number)

| Item | Value |
|---|---|
| indy-node version / commit | |
| indy-plenum version / commit | |
| Node image digests (one per node) | |
| indy-vdr version | 0.4.2 (update when changed) |
| anoncreds-rs version | |
| Genesis file hash | |
| `indy_config.py` hash | |
