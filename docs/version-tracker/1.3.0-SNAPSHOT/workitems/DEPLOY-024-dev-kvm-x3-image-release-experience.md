# DEPLOY-024 dev-kvm-x3 Image Release Experience

Purpose: record the release, bootstrap, smoke-test, and deployment-script lessons from the `dev-kvm-x3-20260517-9` Navigator rollout.

Version: `1.3.0-SNAPSHOT`

Status: execution-checkin complete

Owner: Navigator deployment/runtime

## Background

`dev-kvm-x3` is currently both a dev/demo runtime and a temporary build-and-push node. The Navigator release model now follows the same direction as TMS: build images from a pinned Git ref on the build node, push to Harbor project `x3`, and deploy target hosts by image tag. Production hosts should only pull and run images.

The release also validated first-platform bootstrap for TMS X3 integration and the non-blocking materialize behavior when Skill/Function resources are not ready.

## Release Evidence

- Branch: `qd-win11/dev`
- Final tag: `dev-kvm-x3-20260517-9`
- Final commit: `21040536 fix: increase report bridge retry window`
- Harbor project: `x3`
- Backend image: `test.synthoflow.com:8080/x3/navigator-backend:dev-kvm-x3-20260517-9`
- Frontend image: `test.synthoflow.com:8080/x3/navigator-frontend:dev-kvm-x3-20260517-9`
- Runtime directory: `/opt/foggy/navigator/runtime`
- Release source directory: `/opt/foggy/navigator/build/source/dev-kvm-x3-20260517-9`

## Experience Notes

1. Image tag should be the release coordination unit.
   Backend, frontend, release source, Biz Worker source, smoke reports, and rollback commands should all refer to the same `IMAGE_TAG`. This made it easy to detect the earlier Biz Worker drift from `/opt/foggy/navigator/current` and move it to the release source directory.

2. Deploy health checks need a startup wait window.
   `docker compose up -d` can return before Spring Boot is ready. A direct backend `/actuator/health` check can fail with connection reset even when the container becomes healthy seconds later. Deployment should wait for Docker health and HTTP probes before failing.

3. First bootstrap must persist platform metadata before optional runtime materialize.
   ClientApp, Business Agent, public skill index, grants, model config, and upstream user grants are control-plane metadata. Worker materialize is a retryable runtime step and should not roll back first bootstrap when Skill/Function artifacts are empty or incomplete.

4. Sync-path materialize and operator materialize should have different failure semantics.
   Upstream bundle sync now treats materialize as best-effort and returns `FAILED` or `SKIPPED_NO_CONTENT` without throwing. Explicit operator materialize remains strict so remediation still fails loudly.

5. Node workers should stay outside the main app image chain for now.
   Claude/Codex/Gemini workers are installed from OBS and managed as node runtimes. This reduces image coupling, but their health checks, auth state, allowed cwd, and upgrade path must be checked separately from Docker Compose.

6. Local dev/demo dependencies must be named explicitly.
   This release uses `foggy-navigator-mysql` and `foggy-navigator-rabbitmq` on `dev-kvm-x3`. Production should move these to managed/external services and set `NAVIGATOR_LOCAL_INFRA=false`.

7. Smoke tests should cover integration shape, not only process health.
   The useful final gate was: Compose health, backend/frontend HTTP checks, LangGraph Biz Worker health and cwd, Claude/Codex worker health, materialize-failure smoke, platform bootstrap restore, and TMS OpenAPI ask/messages smoke.

8. Secrets must remain runtime-only.
   `release.env`, `platform-bootstrap.env`, `tms-upstream.env`, Worker env, Harbor credentials, root/admin passwords, OpenAI-compatible keys, and ClientApp credentials belong in ignored local files or server secret storage only.

## Script Adjustment

`deploy/dev-kvm-x3/remote/status-check.sh` now supports:

- `NAVIGATOR_STATUS_RETRIES`
- `NAVIGATOR_STATUS_INTERVAL_SECONDS`

`deploy/dev-kvm-x3/remote/deploy-by-image.sh` now calls `status-check.sh` with deploy defaults:

- `NAVIGATOR_DEPLOY_HEALTH_RETRIES=45`
- `NAVIGATOR_DEPLOY_HEALTH_INTERVAL_SECONDS=2`

Docker health statuses `starting` and `unhealthy` are treated as not ready, so deploy waits instead of failing on the first early HTTP probe. During deploy retries, transient HTTP failures are rendered as `waiting`; only the final exhausted attempt is rendered as `FAILED`.

## Verification

- Local Java, Python, and frontend tests passed during the release.
- `dev-kvm-x3-20260517-9` images built and pushed successfully to Harbor `x3`.
- Compose status after deploy: backend/frontend/mysql/rabbitmq healthy.
- Backend health: `http://127.0.0.1:8112/actuator/health` OK.
- Frontend health: `http://127.0.0.1/health` OK.
- LangGraph Biz Worker health OK and cwd confirmed as `/opt/foggy/navigator/build/source/dev-kvm-x3-20260517-9/tools/langgraph-biz-worker`.
- Materialize failure smoke returned HTTP 200 with `materializeStatus=FAILED` and one backend warn.
- Standard platform bootstrap restored the TMS agent bundle after the failure smoke.
- TMS OpenAPI smoke completed with runtime token, ask, and message retrieval.

## Follow-Ups

- Decide whether LangGraph Biz Worker becomes a dedicated image, OBS package, or systemd-managed release-source runtime before production.
- Move production database and RabbitMQ out of local Compose.
- Finalize Codex Worker authentication and Worker token injection for formal demo/production.
- Decide lockfile and internal package publishing strategy for `@foggy/chat-core`, `@foggy/chat`, and `@foggy/navigator-frontend`.
- Add a periodic reconciliation or CLI command for bundles whose sync-path materialize result is `FAILED`.
- Keep deployment reports tied to `IMAGE_TAG` and avoid mixing runtime source directories across tags.
