# indy-plenum configuration for the baseline pool.
#
# Every value here is a measurement input. Whatever is chosen must be swept and recorded in
# docs/baseline-tuning.md with the values that lost, because milestone B3 asks whether the
# baseline was tuned as carefully as our system.

# --- consensus batching: the direct analogue of our gateway's batchSize / maxBatchDelay ---
Max3PCBatchSize = 100          # SWEEP, mirroring our maxbatchsize sweep
Max3PCBatchWait = 0.05         # SWEEP, mirroring our maxBatchDelay sweep

# --- logging: production level. Debug logging on one system only is a silent handicap. ---
logLevel = 30                  # WARNING

# --- do not enable a monitoring plugin here unless the equivalent is enabled on our system ---
