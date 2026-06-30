# TMS DB-Backed Binding

This reference was split from the navigator-upstream-cli skill main file. Read it only when the current task matches the section title or routing guidance in SKILL.md.

## TMS DB-Backed ClientApp Boundary

For TMS X3, `.navigator/upstream.env` is now a CLI/bootstrap/smoke profile only. It is not a TMS microservice runtime configuration source.

TMS runtime and BFF flows must resolve tenant-scoped Navigator config from the TMS DB ACTIVE binding, currently `x3_navigator_integration_binding`. Do not treat values in `.navigator/upstream.env`, `NAVI_UPSTREAM_ENV_FILE`, or project yml as a fallback for TMS tenant-level `ClientApp`, runtime key/secret, control key, agent, model, skill, worker, biz-worker, or upstreamRef fields.

For `dev-kvm-x3`, the current accepted shape is:

- TMS tenant `88800` has an ACTIVE DB binding.
- Tenant-scoped runtime/control secrets are stored in DB as `enc:v1:aes-gcm` encrypted fields, not as `env:` references.
- The TMS runtime secret env only carries the encrypted-field decryption key.
- `.navigator/upstream.env` and `/opt/foggy/navigator/runtime/tms-upstream.env` remain valid only for CLI/bootstrap/smoke tools.

When validating TMS runtime readiness, prefer the TMS BFF readiness smoke over the Navigator CLI-only smoke:

```powershell
bash scripts/navigator-integration-readiness-smoke.sh
```

That script requires only `TMS_STAFF_SESSION_TOKEN` and calls `/bff/navigator/admin/integration-bindings/readiness` with DB-backed gating. Use `NAVI_READINESS_SMOKE_DEPTH=runtime|grant|preflight|ask` to choose depth. A valid TMS release gate should report `source=db` and passed runtime-token, upstream-user grant, preflight, and ask checks for `ask` depth.

Do not use Navigator CLI smoke success by itself to prove TMS DB binding readiness. CLI smoke proves the project-local Navigator profile works; TMS readiness proves the deployed TMS service can resolve DB binding, decrypt secrets, exchange runtime token, grant upstream user, and call the agent without `.navigator` fallback.

As of the 1.0.7 owner-aware rollout, School Sim has completed the new PhysicalWorker / backend capability smoke, but TMS should be treated as not yet migrated until its DB ACTIVE binding is updated and TMS readiness passes. Do not copy a School Sim `.navigator` profile, Agent code, directory id, or model grant into TMS.
