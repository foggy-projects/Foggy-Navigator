# ARCH-001 activation target harness

This directory contains the fail-closed, repository-owned harness and closed-
provisioning client for the first disposable ARCH-001 activation target. The
implementation package does not activate Navigator or invoke a model.

The only approved target class and provider lane are:

- `ISOLATED_LOCAL_NON_FIXTURE`
- `REAL_CODEX_MODEL`

`doctor` and `cleanup-plan` are read-only. `observe` writes one target-owned
controller observation. `watch` continuously refreshes that observation and
writes a loss observation before exiting on drift. `cleanup` is destructive
and is reserved for a separately authorized execution session; it re-runs the
live ownership checks and requires the exact manifest digest.

`activation_bootstrap.py` implements a separate stopped-safe protocol. Its
commands verify and apply one explicit schema plan, bind live Hibernate
`validate` evidence to the copied candidate artifact/PID, create synthetic
runtime resources only through existing production APIs, verify authenticated
Worker lifecycle inventory, and atomically seal the server-generated IDs. It
contains no activation mutation or task/model submission command.

```bash
PYTHONPATH=tools/arch001-activation \
  python3 tools/arch001-activation/activation_target.py doctor \
  --manifest /absolute/target-owned/activation-target-manifest.json

PYTHONPATH=tools/arch001-activation \
  python3 tools/arch001-activation/activation_target.py watch \
  --manifest /absolute/target-owned/activation-target-manifest.json \
  --output /absolute/target-owned/controller-observation.json \
  --interval-seconds 5

PYTHONPATH=tools/arch001-activation \
  python3 tools/arch001-activation/activation_target.py cleanup-plan \
  --manifest /absolute/target-owned/activation-target-manifest.json

PYTHONPATH=tools/arch001-activation \
  python3 tools/arch001-activation/activation_bootstrap.py schema-plan-verify \
  --plan tools/arch001-activation/schema-plan-v1.json \
  --repo-root /home/sa/workspace/Foggy-Navigator
```

The remaining bootstrap commands are intentionally not a one-shot launcher.
Each takes an exact manifest/result path and fails closed between phases:
`schema-apply` (twice, with the exact plan digest on reapply),
`schema-validate`, `provision`, `verify-readiness`, `seal`, and finally the
separately confirmed `purge-credentials`. A later execution owner must start
and stop exact resources outside this client and satisfy live ownership checks.

The tracked `schema-plan-v1.json` is exhaustive and ordered: one current-
schema baseline followed by the three reviewed ARCH-001 forward migrations.
The baseline was generated during implementation from the candidate launcher
on isolated MySQL 8.0.44, then checked in and digest-sealed. Runtime use of
Hibernate `create`/`update`, migration directory scans, rollback SQL, and
caller-selected user/Worker/modelConfig IDs remains forbidden.

An offline snapshot is accepted only by `doctor --test-fixture`; it is never
activation authority. Normal CLI operations always inspect live loopback
ports, `/proc` cwd ownership, exact PID files, and exact Docker labels.
The `/proc` scan treats descendants of the exact PID-file-owned Navigator and
Worker processes as runtime workload, not relaunch controllers. A process in
the target root that is not the exact runtime and has no proven runtime
ancestor remains unknown and fails closed; re-parented/orphaned processes are
never inferred as owned.

Profile inspection retains and reports variable names only. It rejects
symlinks, group/other-writable files, missing required names, and names outside
the lane-specific allowlist. Provider credential values must be populated only
inside the target-owned, gitignored profile by the later execution owner.

See the versioned runbook for the phase and authorization boundaries:
`docs/version-tracker/1.4.3-SNAPSHOT/runbooks/ARCH-001-ACT-001-disposable-activation-target.md`.
