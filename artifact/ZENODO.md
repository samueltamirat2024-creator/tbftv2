# Archiving for a DOI

Plan §12.3 and milestone M9 require the artifact, raw data and analysis scripts to be archived
under a permanent identifier. Zenodo is the usual choice; figshare or your institutional
repository work identically.

## Before archiving

- [ ] `./artifact/run-all.sh` passes from a clean unpack on a machine that is not yours.
- [ ] `artifact/results/` contains at least one complete run, with its `environment.txt`.
- [ ] `bench/METRICS.md` is signed off by all co-authors. Metric definitions frozen after
      measurement are not frozen.
- [ ] `docs/DEVIATIONS.md` matches the code — every stub named, nothing quietly fixed or quietly
      broken since it was written.
- [ ] `deploy/` digest placeholders are either filled in or the README says plainly that they are
      placeholders. A `REPLACE_WITH_PINNED_DIGEST` in an archived artifact is a reproducibility
      claim that fails on first use.
- [ ] `RESULTS.md` still carries its provenance warning. If the numbers were regenerated against
      BFT-SMaRt, the warning must be removed and replaced with the cluster description — not left
      in place "to be safe", which would understate real results as badly as omitting it
      overstates simulated ones.

## Archiving

1. Tag the revision: `git tag -a artifact-v1 -m "TDSC submission artifact" && git push --tags`.
2. On Zenodo, link the GitHub repository and publish the tag, or upload the archive directly.
3. Record the DOI in three places: this file, the paper's artifact appendix, and `README.md`.
4. Zenodo mints a *concept* DOI (all versions) and a *version* DOI. Cite the version DOI in the
   paper — a reviewer must be able to fetch exactly what was evaluated, not whatever the artifact
   later became.

## Recorded identifiers

| Field | Value |
|---|---|
| Version DOI | _pending_ |
| Concept DOI | _pending_ |
| Git tag | _pending_ |
| Commit | _pending_ |
| Archived on | _pending_ |

## What to include beyond the code

The code alone is not the artifact. Also archive:

- every `artifact/results/` run behind a number in the paper, environment files included;
- the analysis scripts that turn those logs into the paper's table, so a reviewer can rerun the
  statistics rather than trust the summary;
- baseline configuration and image digests for Indy and the public-chain reference (M8), since an
  under-tuned baseline is the easiest thing for a reviewer to attack and the cheapest to defend in
  advance.
