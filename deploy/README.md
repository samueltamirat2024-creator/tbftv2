# deploy/ — Compose stack works; the Helm chart is still a skeleton

`deploy/vps/` is a working four-replica Compose stack for both systems and is what the measurement
protocol uses (`deploy/vps/run-protocol.sh`). The Helm chart beside it is still the shape plan §10
calls for rather than something that will start a cluster in Kubernetes today.

1. **The replica entrypoint is `vdr.replication.bftsmart.VdrReplica`** (argv: replica id, then the
   config directory holding `hosts.config` and `system.config`). The Compose stack passes those; the
   Helm templates still name the old placeholder and need updating to match.
2. **Image digests are placeholders.** `REPLACE_WITH_PINNED_DIGEST` appears in the Dockerfile base
   images and in `values.yaml`. Plan §11 M8 requires real digests published *before* any
   measurement, so fill these in as part of freezing the baselines, not afterwards.
3. **No gateway deployment.** The stateless Go gateway of plan §9 is not written; the Helm chart
   covers replicas only. Gateways scale horizontally and independently, so they belong in a
   separate Deployment with an HPA, and gateway CPU must be reported alongside every result
   (plan §15: otherwise a saturated shim gets measured instead of the design).

## What the chart does encode

The n = 3f+1 invariant is enforced at render time in `_helpers.tpl` — a 5- or 6-replica cluster
tolerates the same f as a 4-replica one while costing more and looking safer.

The `diversity` block in `values.yaml` is the deployment half of plan §14, lesson 4: DepSpace
investigated multi-version programming and opportunistic diversity and concluded that most real
failures trace to infrastructure software and management rather than application code, so fault
independence is bought by spreading replicas across nodes, zones, OS images and administrators
rather than by writing the VDR twice. This is the assumption Paper 2's deployment section has to
defend. If all four replicas land on one node, the Byzantine fault tolerance claim is decorative.
