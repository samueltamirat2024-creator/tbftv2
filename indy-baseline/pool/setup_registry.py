#!/usr/bin/env python3
"""
Setup transactions for the Indy baseline (milestone B1).

Writes SCHEMA, CRED_DEF, REVOC_REG_DEF and the initial REVOC_REG_ENTRY to the pool and prints the
revocation registry id and initial accumulator the benchmark needs. These are SETUP, not workload:
baseline plan §3 puts them before the measurement window, exactly as the Tailored BFT DID pool is
populated before warm-up. Counting registry creation as workload would inflate Indy's write cost
and flatter our system.

    pip3 install indy-vdr anoncreds base58 pynacl
    python3 setup_registry.py --genesis /tmp/pool_transactions_genesis \\
        --seed 000000000000000000000000Trustee1

2026-09-19: SCHEMA, CRED_DEF and REVOC_REG_DEF are sent in the legacy Indy ledger format
(ver/id, cred def schemaId = schema seqNo, issuanceType = ISSUANCE_BY_DEFAULT). anoncreds'
to_json() emits the AnonCreds format, which indy-vdr 0.4.2 refuses to build into a request.

2026-09-20:
  - --schema-version defaults to a per-run timestamp. The ledger allows one SCHEMA per
    (DID, name, version); a fixed version made every rerun against the same pool fail.
  - Writes the initial REVOC_REG_ENTRY (accum only). The ledger needs it before any revocation
    entry; later entries must carry prevAccum equal to the ledger's current accum. The value is
    printed as INDY_REVOC_ACCUM for the benchmark.
  - signature_input is encoded to bytes if indy-vdr returns str (PyNaCl requires bytes).
  - Reply parsing tolerates both the bare result and a {"result": ...} wrapper.

2026-09-30 (remediation plan §2 A1): --count N writes N revocation registries in one pass and
records them, in order, in the file named by --out. IndyBackend takes the next unused one for
every sweep level and every measurement run, so no run inherits another run's revoked set. The
SCHEMA and CRED_DEF are written once; only the REVOC_REG_DEF and its initial entry repeat, each
under its own tag.

The registry is sized so an 800-credential burst fits without rolling over: a rollover mid-burst
would measure registry creation rather than revocation (baseline plan §5).
"""

import argparse
import asyncio
import json
import pathlib
import sys
import time

# Imports are checked one at a time so a failure names the package that is actually missing.
try:
    from indy_vdr import open_pool, ledger
except ImportError as e:
    sys.exit(f"indy-vdr import failed ({e}); pip3 install indy-vdr")

try:
    from anoncreds import (
        Schema,
        CredentialDefinition,
        RevocationRegistryDefinition,
        RevocationStatusList,
    )
except ImportError as e:
    sys.exit(f"anoncreds import failed ({e}); pip3 install anoncreds")

try:
    import base58
except ImportError as e:
    sys.exit(f"base58 import failed ({e}); pip3 install base58")

try:
    import nacl.signing
except ImportError as e:
    sys.exit(f"pynacl import failed ({e}); pip3 install pynacl")


BURST_SIZE = 800           # must match Bench.BURST_SIZE
REGISTRY_HEADROOM = 4      # registry large enough that a burst never triggers a rollover


def key_from_seed(seed: str):
    """Indy's seed-to-key convention: the 32-byte seed IS the Ed25519 signing key."""
    raw = seed.encode("ascii")
    if len(raw) != 32:
        sys.exit("seed must be exactly 32 characters")
    sk = nacl.signing.SigningKey(raw)
    verkey = bytes(sk.verify_key)
    did = base58.b58encode(verkey[:16]).decode()
    return sk, base58.b58encode(verkey).decode(), did


async def submit(pool, request, sk):
    """Sign with the canonical signature input and submit."""
    sig_input = request.signature_input
    if isinstance(sig_input, str):
        sig_input = sig_input.encode("utf-8")
    request.set_signature(sk.sign(sig_input).signature)
    return await pool.submit_request(request)


def seq_no_of(resp) -> int:
    """Extract txnMetadata.seqNo whether or not the reply is wrapped in 'result'."""
    if isinstance(resp, str):
        resp = json.loads(resp)
    body = resp.get("result", resp) if isinstance(resp, dict) else None
    try:
        return body["txnMetadata"]["seqNo"]
    except (KeyError, TypeError):
        sys.exit(f"unexpected ledger reply, no txnMetadata.seqNo:\n{json.dumps(resp, indent=2)}")


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--genesis", required=True)
    ap.add_argument("--seed", required=True, help="32-char seed of an endorser/trustee DID")
    ap.add_argument("--tag", default="bench")
    ap.add_argument(
        "--schema-version",
        default=f"1.{int(time.time())}",
        help="unique per run: the ledger rejects a second SCHEMA with the same name+version",
    )
    ap.add_argument(
        "--count",
        type=int,
        default=1,
        help="how many revocation registries to create. One per measurement run and per sweep "
             "level, plus headroom: about 40 for a full configuration.",
    )
    ap.add_argument(
        "--out",
        default="/results/registries.env",
        help="file that receives the registry ids, one per line, in order",
    )
    args = ap.parse_args()
    if args.count < 1:
        sys.exit("--count must be at least 1")

    sk, verkey, did = key_from_seed(args.seed)
    pool = await open_pool(transactions_path=args.genesis)
    print(f"submitter DID : {did}")
    print(f"verkey        : {verkey}")

    # ---- SCHEMA ------------------------------------------------------------
    schema_name = "vdr-bench"
    schema_version = args.schema_version
    attr_names = ["subject"]
    schema_id = f"{did}:2:{schema_name}:{schema_version}"

    # The anoncreds object is kept for CredentialDefinition.create below. The ledger gets the
    # legacy Indy schema format instead.
    schema = Schema.create(
        name=schema_name, version=schema_version, issuer_id=did, attr_names=attr_names
    )
    ledger_schema = {
        "ver": "1.0",
        "id": schema_id,
        "name": schema_name,
        "version": schema_version,
        "attrNames": attr_names,
        "seqNo": None,
    }
    req = ledger.build_schema_request(did, json.dumps(ledger_schema))
    resp = await submit(pool, req, sk)
    schema_seq_no = seq_no_of(resp)
    print(f"schema        : {schema_id} (seqNo {schema_seq_no})")

    # ---- CRED_DEF, revocation-enabled -------------------------------------
    cred_def, cred_def_priv, key_proof = CredentialDefinition.create(
        schema_id=schema_id,
        schema=schema,
        issuer_id=did,
        tag=args.tag,
        signature_type="CL",
        support_revocation=True,
    )
    cred_def_id = f"{did}:3:CL:{schema_seq_no}:{args.tag}"
    # Legacy Indy format: the ledger's schemaId is the schema's seqNo as a string.
    ledger_cred_def = {
        "ver": "1.0",
        "id": cred_def_id,
        "schemaId": str(schema_seq_no),
        "type": "CL",
        "tag": args.tag,
        "value": json.loads(cred_def.to_json())["value"],
    }
    req = ledger.build_cred_def_request(did, json.dumps(ledger_cred_def))
    await submit(pool, req, sk)
    print(f"cred def      : {cred_def_id}")

    # ---- REVOC_REG_DEF x N, each with its own tag --------------------------
    # Tails generation happens HERE, before measurement. This is a real cost of the AnonCreds
    # model that the Tailored BFT VDR does not carry; baseline plan §7 requires it to be
    # discussed in the paper rather than silently benefited from.
    #
    # One registry per measurement run (remediation plan §2 A1). The registry id ends in its tag,
    # so a distinct tag per registry is what makes them distinct on the ledger.
    max_cred_num = BURST_SIZE * REGISTRY_HEADROOM
    registries = []
    first_accum = None
    for i in range(args.count):
        tag = args.tag if args.count == 1 else f"{args.tag}-{i:03d}"
        rev_reg_def, rev_reg_def_priv = RevocationRegistryDefinition.create(
            cred_def_id=cred_def_id,
            cred_def=cred_def,
            issuer_id=did,
            tag=tag,
            registry_type="CL_ACCUM",
            max_cred_num=max_cred_num,
        )
        rev_reg_def_id = f"{did}:4:{cred_def_id}:CL_ACCUM:{tag}"
        # ISSUANCE_BY_DEFAULT (every index starts issued) matches IndyBackend, whose
        # REVOC_REG_ENTRY carries "issued": [] and only revoked indices.
        rev_reg_value = json.loads(rev_reg_def.to_json())["value"]
        rev_reg_value["issuanceType"] = "ISSUANCE_BY_DEFAULT"
        ledger_rev_reg_def = {
            "ver": "1.0",
            "id": rev_reg_def_id,
            "revocDefType": "CL_ACCUM",
            "tag": tag,
            "credDefId": cred_def_id,
            "value": rev_reg_value,
        }
        req = ledger.build_revoc_reg_def_request(did, json.dumps(ledger_rev_reg_def))
        await submit(pool, req, sk)

        # The ledger needs an initial accumulator before any revocation entry. Every later entry
        # must carry prevAccum equal to the ledger's current accum.
        status_list = RevocationStatusList.create(
            cred_def,
            rev_reg_def_id,
            rev_reg_def,
            rev_reg_def_priv,
            did,
            True,          # issuance_by_default
            int(time.time()),
        )
        initial_accum = json.loads(status_list.to_json())["currentAccumulator"]
        initial_entry = {"ver": "1.0", "value": {"accum": initial_accum}}
        req = ledger.build_revoc_reg_entry_request(
            did, rev_reg_def_id, "CL_ACCUM", json.dumps(initial_entry)
        )
        await submit(pool, req, sk)
        registries.append(rev_reg_def_id)
        if first_accum is None:
            first_accum = initial_accum
        print(f"registry {i + 1:3d}/{args.count}: {tag}", flush=True)

    # ---- record the list ---------------------------------------------------
    # IndyBackend reads this file and a counter beside it, taking the next unused registry for
    # every sweep level and every run. A run that starts on a used registry is not comparable
    # with one that starts clean.
    out = pathlib.Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(registries) + "\n", encoding="utf-8")
    counter = out.with_name("registry.counter")
    counter.write_text("0\n", encoding="utf-8")

    print()
    print("export INDY_SUBMITTER_DID=" + did)
    print("export INDY_REVOC_REG_DEF_ID='" + registries[0] + "'")
    print("export INDY_REVOC_ACCUM='" + first_accum + "'")
    print("export INDY_REGISTRIES_FILE='" + str(out) + "'")
    print()
    print(f"{len(registries)} registry/registries written to {out}; counter reset in {counter}")
    print(f"each holds {max_cred_num} credentials — a {BURST_SIZE}-revocation burst fits "
          f"without a rollover")

    pool.close()


if __name__ == "__main__":
    asyncio.run(main())
