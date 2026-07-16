# Agent Framework Guidance

- Treat the current interfaces, auto-configuration, tests, and `pom.xml` as authoritative; do not restore package layouts or APIs from historical skill snapshots.
- Keep `agent-framework` a reusable runtime/library layer. Business HTTP endpoints and provider-specific task orchestration belong in the owning business or addon module.
- Optional SPI integrations must remain optional at startup. When adding or changing one, test both the provider-present and provider-absent paths.
- User-memory tools and prompt injection must remain scoped to the authenticated user and tenant. Do not broaden injection to unscoped records or make `UserMemoryManager` a mandatory dependency.
- Changes to shared protocol or event objects require checking all current producers, consumers, serialization tests, and frontend adapters rather than updating this module in isolation.
- Validate relevant changes with `mvn test -pl agent-framework -am` when practical.
