---
acceptance_scope: feature
version: 1.3.1-SNAPSHOT
target: OPT-001-stage5-runtime-config-hardening
doc_role: acceptance-record
doc_purpose: 记录 OPT-001 Stage 5 运行配置硬化的功能级正式验收与签收结论
status: signed-off
decision: accepted-with-risks
signed_off_by: Codex
signed_off_at: 2026-06-25
reviewed_by: N/A
blocking_items: []
follow_up_required: yes
evidence_count: 5
---

# Feature Acceptance

## Document Purpose

- doc_type: acceptance
- intended_for: signoff-owner / reviewer / java-platform
- purpose: 记录 OPT-001 Stage 5 运行配置硬化的正式验收结论、证据和后续风险项。

## Background

- Version: 1.3.1-SNAPSHOT
- Target: OPT-001-stage5-runtime-config-hardening
- Owner: launcher / java-platform
- Goal: 将 `launcher` 的开发默认配置与生产 profile 安全边界分离，阻止生产环境继续依赖 `ddl-auto=update`、bean overriding、默认密钥和过度 actuator 暴露。

Stage 5 的验收范围限定为 `launcher` 生产 profile、启动前置检查、部署检查表和自动化测试。数据库 migration 工具引入、全量密钥轮换和非 launcher 独立启动入口不纳入本次签收范围。

## Acceptance Basis

- [workitems/OPT-001-stage5-runtime-config-hardening.md](../workitems/OPT-001-stage5-runtime-config-hardening.md)
- [workitems/OPT-001-java-architecture-risk-governance.md](../workitems/OPT-001-java-architecture-risk-governance.md)
- [launcher/src/main/resources/application-prod.yml](../../../../launcher/src/main/resources/application-prod.yml)
- [launcher/src/main/java/com/foggy/navigator/launcher/ProductionConfigurationGuard.java](../../../../launcher/src/main/java/com/foggy/navigator/launcher/ProductionConfigurationGuard.java)
- [launcher/src/test/java/com/foggy/navigator/launcher/ProductionConfigurationGuardTest.java](../../../../launcher/src/test/java/com/foggy/navigator/launcher/ProductionConfigurationGuardTest.java)

## Checklist

- [x] scope 内功能点已全部交付。
- [x] 原始 acceptance criteria 已逐项覆盖。
- [x] 关键测试已通过。
- [x] 体验验证已完成，或明确标记 `N/A`。
- [x] 文档、配置、依赖项已闭环。

## Evidence

- Requirement:
  - `workitems/OPT-001-stage5-runtime-config-hardening.md` 已记录 target outcome、production deployment checklist、acceptance criteria 和 execution check-in。
  - `workitems/OPT-001-java-architecture-risk-governance.md` 已回写 Stage 5 完成范围、测试证据和 acceptance readiness。
- Implementation:
  - `application-prod.yml` 设置生产 profile 的 `ddl-auto=validate`、`allow-bean-definition-overriding=false`、actuator 默认 `health,metrics`、health details 默认 `never`。
  - `ProductionConfigurationGuard` 在 `prod` / `production` profile 下校验 datasource、JWT、ROOT password、credential key/salt、external URL、DDL、bean overriding 和 actuator 风险项。
  - `FogyNavigatorApplication` 已注册 `ProductionConfigurationGuard`。
- Test:
  - `mvn test -pl launcher -am '-Dtest=ProductionConfigurationGuardTest' '-DfailIfNoTests=false' '-Dsurefire.failIfNoSpecifiedTests=false'`：4 tests pass，0 failures，0 errors，0 skipped。
  - `mvn test -pl launcher -am`：Surefire XML 合计 250 reports / 1756 tests，0 failures，0 errors，0 skipped。
- Experience:
  - N/A。该切片为后端启动配置、生产 profile 和部署检查表治理；未新增或修改 UI 页面、表单、按钮、权限可见性或前端交互。
- Artifact:
  - `launcher/src/main/resources/application.yml`
  - `launcher/src/main/resources/application-prod.yml`
  - `launcher/src/main/java/com/foggy/navigator/launcher/FogyNavigatorApplication.java`
  - `launcher/src/main/java/com/foggy/navigator/launcher/ProductionConfigurationGuard.java`
  - `launcher/src/test/java/com/foggy/navigator/launcher/ProductionConfigurationGuardTest.java`
  - `launcher/pom.xml`

## Failed Items

- none

## Risks / Open Items

- `accepted-with-risks`: 本阶段未引入 Flyway/Liquibase；生产 schema 仍需通过 migration 或人工流程预先准备，`prod` profile 只负责禁止隐式 `ddl-auto=update`。
- `accepted-with-risks`: `user-auth-module` 等非 launcher 独立启动配置未纳入本切片；如存在独立生产启动方式，应补充对应 prod profile 或统一入口约束。

## Final Decision

Stage 5 运行配置硬化满足当前功能级验收标准：生产 profile 已与开发默认配置分离，关键危险配置已通过 `ProductionConfigurationGuard` fail-fast，部署检查表和测试证据完整。

验收结论为 `accepted-with-risks`。风险均为非阻断后续项，不影响 Stage 5 当前范围签收。

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-25
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage5-runtime-config-hardening-acceptance.md
- blocking_items: none
- follow_up_required: yes
