# Claude Agent Worker Guidance

- Treat `pyproject.toml`, current Pydantic models, routes, SDK wrapper, event mapper, and tests as authoritative; do not copy endpoint or SDK assumptions from retired skills.
- All filesystem and Git routes must preserve realpath-based `allowed_cwds` validation. Do not bypass it for convenience, and include traversal and symlink-boundary cases when changing path handling.
- Keep OS process detection limited to raw process facts. Provider/business enrichment, task ownership, and orphan reconciliation belong in their current route or Java service layers.
- Process patterns must distinguish SDK-spawned non-interactive CLI processes from user terminals. Verify changed patterns on the target platform and keep subprocess failures non-fatal.
- SDK message-to-SSE mapping is a cross-runtime contract with `addons/claude-worker-agent`. Update event fields and terminal semantics on both sides with tests.
- Preserve per-request authentication precedence and avoid logging API keys, auth tokens, Worker tokens, SSH credentials, or full secret-bearing request bodies.
- Run `python -m pytest` from this directory for Worker changes; run the Java addon tests when HTTP or SSE contracts change.
