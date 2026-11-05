# What the Tailored BFT VDR's disk log guarantees

**Short version: on BFT-SMaRt 1.2, `log_to_disk=true` measures the cost of synchronous logging but does
not give crash recovery. Do not call the disk arm durable unless `deploy/vps/durable-gate.sh` says PASS
on the machine that produced the numbers.**

The in-memory arm is unaffected. It runs stock BFT-SMaRt, tolerates f crashes through state transfer, and
loses everything if all replicas stop at once.

## How this was found

Four real replica processes on a laptop over loopback, killed with SIGKILL, with every write a client saw
acknowledged recorded and checked afterwards (2026-11-04). The causes below come from disassembling
`library-1.2.jar` and from replica logs. They have not been run on the VPS, so `durable-gate.sh` runs before
the disk arm is measured there.

## The defects

All four are in BFT-SMaRt 1.2's own code. This repository does not modify that code.

| # | Defect | Where | Effect |
|---|---|---|---|
| 1 | At startup a replica reports `TOMLayer.getLastExec()`, still -1, instead of the id it recovered from disk | `BaseStateManager.currentConsensusIdAsked` | After a full restart all replicas hear -1, which differs from their own id, and request "state up to CID -1" forever. The cluster never serves another write. |
| 2 | Each request's reply is sent as it executes, but its batch is logged only after the last request in the batch | `ServiceReplica.receiveMessages`, `DefaultSingleRecoverable.executeOrdered` | With batches above one request, f+1 replicas can acknowledge a write that no disk holds yet. |
| 3 | Recovery rewrites the checkpoint and truncates the log, without writing back the instances it just replayed | `DiskStateLog.update` via `DefaultSingleRecoverable.setState` | Those instances, already acknowledged, are lost at the next crash. |
| 4 | A replica with a checkpoint and an empty log recovers as if it had no state | `DiskStateLog.loadDurableState`, where the last id comes from the log | Two restarts with nothing written between them come back with an empty store. Observed directly: all acknowledged DIDs lost. |

Also seen: serving a state transfer from a recovered replica can throw
`NullPointerException: recoveredBatches is null` on the `Server CS` thread, and the wasted retries of defect 1
eventually overflow a doubling timer (`IllegalArgumentException: Negative delay`).

## What was tried

A small `StateManager` subclass, used only with `log_to_disk=true`, fixed defect 1: replicas reported their
recovered ids, and adopted the (f+1)-th highest of the n reports. With it:

- one kill after a quiet load recovered every acknowledged write, with equal roots on all four replicas;
- a kill during load left replicas one instance apart, agreed on a target, and then stalled in a leader
  change, so the check timed out;
- a second kill lost all state, through defects 3 and 4.

It was removed. It did not make the arm recoverable, and it would have changed startup code for a guarantee
that still did not hold.

## What this means for the comparison

- The disk arm answers a narrower question: how much does synchronous logging cost? Label it **disk logging,
  not recoverable**, as `make-table.py` does when the gate fails.
- Hyperledger Indy keeps its ledger on disk and is designed to catch up after a restart. Its full-restart
  behaviour is measured by the `crash-all` scenario in `deploy/vps/faults/`, not assumed.
- So the claim "Tailored survives a full-cluster crash" cannot be made for this implementation.

## If full-crash recovery is required

Defects 2 to 4 are in the log itself, so the fix is a replacement rather than a patch: a recoverable
executor in this repository (`BatchExecutable`, so that replies follow the batch's fsync) with its own
log and checkpoints, the startup rule above, and state transfer on top of them. That changes the system
under test, and the statement in `VdrReplica` that BFT-SMaRt's state transfer is used unmodified, so it is a
decision for the authors. It would then need the gate, and a new round of measurements.
