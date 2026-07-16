# Claude Worker Addon Guidance

- This addon is the Java control plane and provider adapter for `tools/claude-agent-worker`; keep Python runtime behavior in the Worker and Java ownership, routing, persistence, and proxy behavior here.
- Preserve the distinction between logical `agentId`, execution `providerType`, and `modelConfigId`. A bound Session must not silently switch provider or credentials; use the current session and A2A architecture documents as the contract.
- Directory, file-browser, Git, and SSH operations must resolve resources through the authenticated user's Worker and directory records. Do not treat client-supplied paths or identifiers as proof of ownership.
- Treat Worker HTTP and SSE payloads as cross-runtime contracts. When they change, inspect and update the Python models/routes/event mapper, Java client/event relay/DTOs, frontend types/API adapters, and contract tests together.
- Keep Worker, SSH, and model credentials encrypted or masked at persistence and response boundaries, and never add raw values to logs or tracked evidence.
- Validate Java changes with `mvn test -pl addons/claude-worker-agent -am`; run the Python Worker tests as well when a Worker contract changes.
