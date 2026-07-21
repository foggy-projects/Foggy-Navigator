---
doc_type: signoff-evidence-snapshot
version: 1.4.3-SNAPSHOT
targets: [INT-001, BUG-008]
captured_at: 2026-07-21
reviewed_head: c2780ba0f353e601901a0b8e0c6a2558ca50c34e
inventory_sha256: 3d5f67d9b5cfc216c3d341622b286f7b29f2009c775953f7b4592f69b0524f8a
---

# INT-001 / BUG-008 Independent Signoff Snapshot

## Purpose and Boundary

- purpose: Bind the fresh normal and forced-SIGNAL disposable replays, focused checks, and independent signoff to one reviewed implementation snapshot.
- scope: Only the INT-001 harness and BUG-008 owner-context changed surface listed below.
- excluded_baseline: Other dirty-worktree paths and ignored generated `tools/navigator-upstream/fixtures/synthetic-integration/__pycache__/**` files are not part of this review.
- confidentiality: This record contains paths and SHA-256 values only. It contains no credential, profile, runtime log, private carrier, or upstream data.
- status: Evidence snapshot only; it is not an acceptance decision.

## Snapshot Capture

```text
git rev-parse HEAD
c2780ba0f353e601901a0b8e0c6a2558ca50c34e

tracked_modified=11
untracked_source_docs=19
inventory_sha256=3d5f67d9b5cfc216c3d341622b286f7b29f2009c775953f7b4592f69b0524f8a
```

Each `tracked` row is bound by `git diff --binary -- <path> | sha256sum`; each `untracked` row is bound by `sha256sum <path>`. `inventory_sha256` is the SHA-256 of exactly these raw bytes: ASCII `foggy-navigator-inventory-v1\n`, followed by every row in `LC_ALL=C` lexical `kind`, then `path` order as `kind\tpath\tsha256\n`, where `kind` is literally `tracked` or `untracked`.

## Tracked Diff Hashes

```text
4a885a4f4bd7af8a599e104ce15b72a720beda32dc7082c5a847493db24700cd  addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiControllerMessageMappingTest.java
fd012c56610c813061e5ecb027c38fa29fba90c90f4dce59282694394ed74bc9  addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphBusinessAgentWorkerTaskLauncher.java
6e14af17dcaaf4b4a738f6a6b295b58ad3509a409cef7becfdaa5eeaf547c0bf  addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphWorkerService.java
1e229fa9d3e61834fc7c922f7b59aa25a08f9cbee9a0288dbe35ff0d33cb1d0b  addons/langgraph-biz-worker/src/test/java/com/foggy/navigator/langgraph/worker/service/LanggraphBusinessAgentWorkerTaskLauncherTest.java
0d29c583e6adc704d1d4d707793294610a0e2e66f4bc83ba001566a42b058f77  addons/langgraph-biz-worker/src/test/java/com/foggy/navigator/langgraph/worker/service/LanggraphWorkerServiceTest.java
76576be8d490118481a81bdbb722927b31b1e2e0e48519e69af956d8a566f1b0  business-agent-module/integration-tests/package.json
6107467e975556e4fab05345acfff8334e4671fb0ac185ce2f38ad6e6e368463  business-agent-module/integration-tests/vitest.config.ts
7e06f644f94644021083100524d7704985a70775134489aa589d0b7a7c7772f7  business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BusinessAgentTaskService.java
1e1a1e7deafae8733beae22c06349fbc1b1ae86c6c44775c00f268005331eb70  business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/worker/BusinessAgentWorkerTaskLaunchRequest.java
4d214f4bb46516ad62bd5df75b5449a76051aac8073e908b582748120af65cbf  business-agent-module/src/test/java/com/foggy/navigator/business/agent/service/BusinessAgentTaskServiceTest.java
4e8d36ae9465ca11ea38393ce553f7fcd3a99d5b8c1a4dc6cd40fc980bd2c2ea  docs/version-tracker/1.4.3-SNAPSHOT/README.md
```

## Untracked File Hashes

```text
bcd1a247d963421c00a69870ac5623b883ddd4728060bc2b6bdd049696bdf70f  business-agent-module/integration-tests/src/synthetic-runtime-config.ts
b5fe4070267f85f3b266219f58238ba839ab04b774b06f18a37045cc910b0226  business-agent-module/integration-tests/tests/05-synthetic-runtime-audit-summary.test.ts
e694b36c0b27b7d60fdadba7ade69ce9c4c15d5acb9f60ee80011a16f97b824f  business-agent-module/integration-tests/tests/05-synthetic-runtime-config.unit.test.ts
094e4eede8b5d3e5171c10a3706419a3a631afabdc7a378622babe1da1f32072  business-agent-module/integration-tests/tests/05-synthetic-upstream-bootstrap-safety.test.ts
cbac4315a08523b6d9b286ac9b59ec4ae28a0b10fe5a0ce9d651e4810ee8dcfe  business-agent-module/integration-tests/tests/05-synthetic-upstream-runtime-harness.test.ts
4898348040ef1151bdbaa25a05ebc6990fea29503b40fd22ae401c9f24a02c44  business-agent-module/integration-tests/vitest.synthetic.config.ts
5c450d3174d7c3d3c29c99f910a7363456256c47ff1c43c04b12500fcd3e27bc  docs/version-tracker/1.4.3-SNAPSHOT/runbooks/INT-001-synthetic-upstream-integration-harness.md
af577254f38e9075b918fe8ace516c950dea8229cd6c632fe6e2979268db8a02  docs/version-tracker/1.4.3-SNAPSHOT/test-records/INT-001-BUG-008-synthetic-runtime-2026-07-21.md
bd154c82c309370fb4b193ac6746e2f0e252161a994c1002747a77f8dcbfbd1d  docs/version-tracker/1.4.3-SNAPSHOT/workitems/BUG-008-openapi-upstream-physical-langgraph-identity-context.md
88f33ce1a8f799ec4c200ad0a7a9d7dc454402d3080d72d369513b1036ab3095  docs/version-tracker/1.4.3-SNAPSHOT/workitems/INT-001-synthetic-upstream-integration-harness.md
040c19aeb47aaaead9643f1dfbf9b63eea578dbf096bf1666d0a11641021a159  tools/navigator-upstream/fixtures/synthetic-integration/biz_ingress_proxy.py
6828fcc48dac3e9efa887a030c58d1254a48ba99ce980a8cc04204001d0b8258  tools/navigator-upstream/fixtures/synthetic-integration/directory_facade.py
cdef76f6684a6c6263c0af234a08725b6225ad6bd56be41fd0e079faa44da5bd  tools/navigator-upstream/fixtures/synthetic-integration/docker-compose.yml
0fd9f225cea598bbe1afae20ef3d20cb5ebc1d4a5927f08ed53ed2339aee384d  tools/navigator-upstream/fixtures/synthetic-integration/responses/static-no-tool.yaml.template
88af917c3e5e829607218206293c36b8359ee2285b0df129989ea6288b04c4da  tools/navigator-upstream/fixtures/synthetic-integration/test_biz_ingress_proxy.py
4579fee671bffc1c08e826ec1e84bb3090025f285fa87be4aea66da11ef0894d  tools/navigator-upstream/fixtures/synthetic-integration/test_directory_facade.py
1e7289e9a390bada32ef654eb45351c882cf66f1cc97f12f25aea7f4cac60bbf  tools/navigator-upstream/scripts/synthetic-upstream-bootstrap.sh
6924c7eec0a8a7888381479bb62f8f7d951bad113cb25c37be4591af7abdaeb0  tools/navigator-upstream/scripts/synthetic-upstream-harness.sh
44a8592d89a0903355a3d76c15061f634ed3ff7c17a8f3c49a9d83c007503582  tools/navigator-upstream/scripts/synthetic-upstream-runtime-audit.sh
```

## Replay Rule

- Every post-snapshot check must complete while the hashes above remain unchanged.
- Any source-surface hash drift invalidates this snapshot and requires a new capture before a signoff decision.
- The pre-snapshot normal replay `int001-signoff-20260721-c7d4e5` is retained as diagnostic confirmation only; it does not substitute for a post-snapshot normal replay.

## Post-document-correction Recapture

- Independent review identified `BusinessAgentTaskServiceTest.java` as a focused BUG-008 regression test that was omitted from the initial inventory. Its binary-diff SHA-256 and the version-index binary-diff SHA-256 were captured before the post-snapshot normal replay completed; the 30-path inventory includes both additions.
- The prior aggregate serialization was undocumented and could not be independently reproduced. This recapture replaces it with the versioned deterministic serialization above and updates only the documented bindings for the version index, INT-001 work item, and BUG-008 work item. It changes no implementation or test file, no replay evidence, and no acceptance decision.
