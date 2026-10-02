import os
import logging

# von-network's node settings (von-network/indy_config.py). Mounting a file over
# /etc/indy/indy_config.py replaces that one, so its contents are repeated here.
NETWORK_NAME = 'sandbox'

LEDGER_DIR = '/home/indy/ledger'
LOG_DIR = '/home/indy/log'
KEYS_DIR = LEDGER_DIR
GENESIS_DIR = LEDGER_DIR
BACKUP_DIR = '/home/indy/backup'
PLUGINS_DIR = '/home/indy/plugins'
NODE_INFO_DIR = LEDGER_DIR

CLI_BASE_DIR = '/home/indy/.indy-cli/'
CLI_NETWORK_DIR = '/home/indy/.indy-cli/networks'

# Set to WARNING through LOG_LEVEL in docker-compose.yml.
logLevel = getattr(logging, os.getenv("LOG_LEVEL", "WARNING").upper())

# Baseline tuning knobs, kept in step with indy-baseline/pool/indy_config.py. Record every
# value tried in indy-baseline/docs/baseline-tuning.md.
Max3PCBatchSize = 100          # SWEEP, mirroring our maxbatchsize sweep
Max3PCBatchWait = 0.05         # SWEEP, mirroring our maxBatchDelay sweep
