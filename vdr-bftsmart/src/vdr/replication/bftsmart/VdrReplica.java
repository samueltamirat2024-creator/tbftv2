package vdr.replication.bftsmart;

import bftsmart.tom.MessageContext;
import bftsmart.tom.server.defaultservices.DefaultSingleRecoverable;
import vdr.ops.Op;
import vdr.ops.Reply;
import vdr.replication.ServiceReplica;
import vdr.serialization.Codec;
import vdr.store.VdrStore;

/**
 * One VDR replica on BFT-SMaRt (plan §4, BFT-SMaRt implementation plan WP3).
 *
 * <p>Run as its own process, one per replica:
 *
 * <pre>
 *   java -cp ... vdr.replication.bftsmart.VdrReplica &lt;replicaId&gt; [configDir]
 * </pre>
 *
 * <p>BFT-SMaRt's ordering, view change and state transfer are used unmodified. Everything this
 * class adds sits above them: the store executes the decided stream, and the read fast path is
 * served unordered from the latest committed checkpoint.
 *
 * <p>{@code DefaultSingleRecoverable} gives durability and state transfer, which is what a new or
 * recovering replica needs in order to catch up without replaying from genesis.
 *
 * <p><b>Fault injection (real-engine M6 only).</b> {@code VDR_BYZANTINE=<Behaviour>} (or
 * {@code -Dvdr.byzantine}) makes this replica run the simulator's Byzantine behaviours from
 * {@link ServiceReplica} on top of real ordering. Unset, the honest path below is the only code
 * that runs. The compose file never sets it; only {@code deploy/vps/docker-compose.gates.yml}
 * does, and the replica announces it on stdout so a run's logs show which replica lied.
 */
public final class VdrReplica extends DefaultSingleRecoverable {

    private final VdrStore store;
    private final int replicaId;
    private volatile int lastConsensusId = -1;
    /** Null on an honest replica: the fault adapter is not even constructed. */
    private final ServiceReplica faults;

    public VdrReplica(int replicaId, int maxBatchSize, long checkpointInterval, String configDir) {
        this(replicaId, maxBatchSize, checkpointInterval, configDir, null);
    }

    public VdrReplica(int replicaId, int maxBatchSize, long checkpointInterval, String configDir,
                      ServiceReplica.Behaviour byzantine) {
        this.replicaId = replicaId;
        if (byzantine == null || byzantine == ServiceReplica.Behaviour.HONEST) {
            this.faults = null;
            this.store = new VdrStore(replicaId, maxBatchSize, checkpointInterval);
        } else {
            this.faults = new ServiceReplica(replicaId, maxBatchSize, checkpointInterval);
            this.faults.setBehaviour(byzantine);
            this.store = faults.store();
        }
        // Starts the replica: reads hosts.config and system.config from configDir. The nulls take
        // 1.2's defaults: accept-all verifier, DefaultReplier, and RSAKeyLoader over configDir/keys/.
        new bftsmart.tom.ServiceReplica(replicaId, configDir, this, this, null, null, null);
    }

    /**
     * Ordered execution: register / update / revoke / repair / checkpoint, and Tier-2 resolve.
     *
     * <p>Time is the consensus id, never the wall clock (plan §7). BFT-SMaRt puts it on the
     * message context, so every replica agrees on it by construction. A
     * {@code System.currentTimeMillis()} anywhere in this path would make replicas diverge on the
     * first operation whose behaviour depends on it — for this system, a revocation-epoch boundary.
     */
    @Override
    public byte[] appExecuteOrdered(byte[] command, MessageContext msgCtx) {
        Op op = Codec.decodeOp(command);
        int consensusId = msgCtx != null ? msgCtx.getConsensusId() : lastConsensusId + 1;
        lastConsensusId = consensusId;
        if (faults != null) return Codec.encode(faults.executeOrdered(op, consensusId));
        return Codec.encode(store.execute(op, consensusId));
    }

    /**
     * Unordered execution: the read-only fast path (DepSpace §4.6, plan §3.3). No total order
     * multicast. The reply carries a Merkle inclusion proof against a committed checkpoint root,
     * which is what lets one replica serve a Tier-0 read safely: a Byzantine replica cannot forge
     * a document, only serve a stale-but-valid one, bounded by the client's root window.
     *
     * <p>This method MUST NOT mutate state. BFT-SMaRt does not order it, so a mutation here would
     * apply on whichever replicas happened to serve the read. Anything that is not a read is
     * refused rather than quietly promoted to the ordered path: an unordered write is a
     * correctness bug, not a performance choice.
     */
    @Override
    public byte[] appExecuteUnordered(byte[] command, MessageContext msgCtx) {
        Op op = Codec.decodeOp(command);
        if (faults != null) {
            switch (op.kind) {
                case RESOLVE -> { return Codec.encode(faults.resolve(op.did)); }
                case REVSTATUS -> {
                    return Codec.encode(faults.revocationStatus(op.registryId, op.handles.get(0)));
                }
                default -> { }
            }
        }
        return switch (op.kind) {
            case RESOLVE -> Codec.encode(store.resolveUnordered(op.did));
            case ROOT -> Codec.encode(store.rootReply());
            case REVSTATUS -> Codec.encode(
                    store.revocationStatusUnordered(op.registryId, op.handles.get(0)));
            default -> Codec.encode(
                    Reply.denied(replicaId, "UNORDERED_WRITE_REFUSED", store.seq()));
        };
    }

    /**
     * Snapshot for state transfer: the replica-identical projection ONLY.
     *
     * <p>If a PVSS share or a decrypted payload reached the snapshot, a recovered replica would
     * diverge from the replicas that never crashed and every Tier-0 inclusion proof would silently
     * stop verifying. {@code VdrStore.serialiseIdenticalProjection()} serialises exactly what
     * {@code buildCheckpoint()} walks; the snapshot gates in {@code vdr.test.Gates} hold it to that.
     */
    @Override
    public byte[] getSnapshot() {
        return store.serialiseIdenticalProjection();
    }

    @Override
    public void installSnapshot(byte[] state) {
        // Rare (state transfer only) and the evidence real-gates.sh looks for in M3.
        System.out.printf("VDR replica %d installing state-transfer snapshot (%d bytes)%n",
                replicaId, state.length);
        store.installIdenticalProjection(state);
    }

    public VdrStore store() {
        return store;
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("usage: VdrReplica <replicaId> [configDir]");
            System.err.println("  configDir holds hosts.config and system.config; default ./config");
            System.exit(2);
        }
        int id = Integer.parseInt(args[0]);
        String configDir = args.length > 1 ? args[1] : "config";
        int maxBatchSize = Integer.getInteger("vdr.maxBatchSize", 4096);
        long checkpointInterval = Long.getLong("vdr.checkpointInterval", 2000L);
        String fault = System.getProperty("vdr.byzantine", System.getenv("VDR_BYZANTINE"));
        ServiceReplica.Behaviour byzantine = fault == null || fault.isBlank()
                ? null : ServiceReplica.Behaviour.valueOf(fault.trim().toUpperCase());

        System.out.printf("VDR replica %d starting: config=%s, maxBatchSize=%d, "
                        + "merkleCheckpointInterval=%d ops, Ed25519=%s, vCPUs=%d, maxHeap=%d MB%n",
                id, configDir, maxBatchSize, checkpointInterval, vdr.crypto.Crypto.ed25519Provider(),
                Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().maxMemory() >> 20);
        if (byzantine != null && byzantine != ServiceReplica.Behaviour.HONEST) {
            System.out.printf("VDR replica %d BYZANTINE MODE: %s (fault injection; never in a "
                    + "measured run)%n", id, byzantine);
        }
        new VdrReplica(id, maxBatchSize, checkpointInterval, configDir, byzantine);
        System.out.printf("VDR replica %d ready%n", id);
        // ServiceReplica runs on its own threads; keep the process alive.
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
