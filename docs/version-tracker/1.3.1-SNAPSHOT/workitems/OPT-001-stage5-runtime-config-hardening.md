---
type: implementation-plan
version: 1.3.1-SNAPSHOT
ticket: OPT-001-stage5
severity: major
status: ready-for-acceptance
owner: launcher/java-platform
created_at: 2026-06-25
---

# OPT-001 Stage 5: 运行配置硬化

## 文档作用

- doc_type: workitem
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 记录 `launcher` 生产 profile 配置硬化、启动前置检查、部署检查表和测试证据。

## Background

Java 架构 review 发现当前 `launcher/src/main/resources/application.yml` 以开发便利为默认：

- `spring.main.allow-bean-definition-overriding=true`
- `spring.jpa.hibernate.ddl-auto=update`
- JWT、ROOT 账号、凭证加密 key/salt 存在开发默认值
- actuator 暴露 `health,beans,metrics`，且 `health.show-details=always`

这些默认值适合本地调试，但生产环境不应依赖隐式建表、默认密钥或高风险 actuator endpoint 暴露。

## Target Outcome

- 保留本地开发 profile 的便利配置，不破坏现有测试和 dev-kvm-x3 部署。
- 新增明确的 `prod` profile，生产环境使用 `ddl-auto=validate`、禁止 bean overriding、收敛 actuator 暴露并移除默认密钥。
- 在 `prod` / `production` profile 下增加启动前置检查，对危险配置 fail-fast。
- 提供生产部署检查表，明确上线前必须设置的环境变量和 profile。
- 为启动前置检查补单测，覆盖 dev 跳过、prod 拒绝危险配置、prod 接受安全配置。

## Scope / Ownership

| Area | Owner | Touchpoints |
| --- | --- | --- |
| Launcher config | `launcher` | `application.yml`、`application-prod.yml` |
| Startup preflight | `launcher` | `FogyNavigatorApplication`、`ProductionConfigurationGuard` |
| Tests | `launcher` | `ProductionConfigurationGuardTest` |
| Deployment docs | `docs` | 本文档、OPT-001 主工作项 |

## Implementation Plan

### Stage 5.1 - Profile Separation

- [x] 将开发默认值保留在 base `application.yml`，并通过环境变量可覆盖。
- [x] 新增 `application-prod.yml`，覆盖生产 profile 的 JPA、bean overriding、actuator、JWT、ROOT 密码和凭证加密配置。
- [x] 明确 `prod` profile 下必须显式提供生产环境变量。

### Stage 5.2 - Startup Guard

- [x] 新增 `ProductionConfigurationGuard`，在 `prod` / `production` profile 下启动前校验危险配置。
- [x] 校验项包括 `ddl-auto`、bean overriding、JWT secret、ROOT password、credential key/salt、actuator endpoint 和 external URL。
- [x] 将 guard 注册到 `FogyNavigatorApplication`，确保 launcher 启动前执行。

### Stage 5.3 - Tests and Check-in

- [x] 为 guard 补单元测试。
- [x] 运行 launcher 定向回归。
- [x] 视测试耗时运行 launcher 受影响 reactor 回归。
- [x] 回写 OPT-001 主工作项和本文档 execution check-in。

## Production Deployment Checklist

生产启动必须满足：

- `SPRING_PROFILES_ACTIVE=prod` 或 `production`
- `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD` 已显式设置
- `JWT_SECRET` 已设置为非默认强密钥
- `SYSTEM_ROOT_PASSWORD` 已设置为非默认强密码
- `NAVIGATOR_CREDENTIAL_KEY` 已设置为非默认值
- `NAVIGATOR_CREDENTIAL_SALT` 已设置为非默认十六进制 salt
- `NAVIGATOR_API_EXTERNAL_URL` 已设置为外部可达地址，不是 localhost / 127.0.0.1
- actuator 只暴露必要 endpoint，不暴露 `beans`、`env`、`configprops`、`heapdump`、`threaddump`、`shutdown` 等高风险 endpoint
- 数据库 schema 已通过 migration 或人工变更准备完成，生产 profile 不依赖 `ddl-auto=update`

## Acceptance Criteria

| Criteria | Status | Evidence |
| --- | --- | --- |
| dev / prod profile 配置边界明确 | done | base `application.yml` 保留 dev 默认并支持环境变量覆盖；`application-prod.yml` 承载生产覆盖。 |
| 生产 profile 不使用 `ddl-auto=update` | done | `application-prod.yml` 设置 `ddl-auto=validate`；guard 只允许 `validate` / `none`。 |
| 生产 profile 禁止 bean overriding | done | `application-prod.yml` 设置 `allow-bean-definition-overriding=false`；guard 对 true fail-fast。 |
| 生产 profile 不允许默认 JWT / credential / ROOT 密码 | done | guard 拒绝空值、默认值和过短密钥/密码。 |
| actuator 生产暴露范围收敛 | done | `application-prod.yml` 默认只暴露 `health,metrics`；guard 拒绝 `beans`、`env`、`configprops` 等高风险 endpoint 和 `show-details=always`。 |
| 启动前置检查有自动化测试 | done | `ProductionConfigurationGuardTest` 覆盖 dev 跳过、prod 拒绝危险默认值、prod 安全配置放行和 `production` alias。 |

## Constraints / Non-Goals

- 不引入 Flyway/Liquibase；本阶段只禁止生产隐式 schema update，并通过部署检查表要求 schema 预先准备。
- 不修改 test profile 的 `create-drop` 配置。
- 不调整业务模块的 API、数据库表结构或认证流程。
- 不处理全量密钥轮换；本阶段只阻止生产继续使用默认密钥。

## Progress Tracking

### Development Progress

| Item | Status | Notes |
| --- | --- | --- |
| Stage 5 子计划落档 | done | 本文档记录范围、部署检查表和验收标准。 |
| Profile 配置拆分 | done | base 配置支持环境变量覆盖；新增 `application-prod.yml`，生产必填项使用空默认值交给 guard 汇总校验。 |
| 启动前置检查 | done | `ProductionConfigurationGuard` 已接入 `FogyNavigatorApplication`，仅在 `prod` / `production` profile 生效。 |
| 主 OPT-001 回写 | done | Stage 5 完成范围、测试证据和剩余风险已回写主 OPT-001 工作项。 |

### Testing Progress

| Scope | Command summary | Result |
| --- | --- | --- |
| launcher guard focused tests | `mvn test -pl launcher -am '-Dtest=ProductionConfigurationGuardTest' '-DfailIfNoTests=false' '-Dsurefire.failIfNoSpecifiedTests=false'` | PASS：4 tests，0 failures，0 errors，0 skipped |
| launcher affected regression | `mvn test -pl launcher -am` | PASS：Surefire XML 合计 250 reports / 1756 tests，0 failures，0 errors，0 skipped |

### Experience Progress

experience: N/A。该切片为后端启动配置、生产 profile 和部署检查表治理；未新增或修改 UI 页面、表单、按钮、权限可见性或前端交互。

## Execution Check-in

| Item | Status | Notes |
| --- | --- | --- |
| Completed work summary | done | 已完成 launcher dev/prod 配置拆分、生产启动 guard、部署检查表和 guard 单测。 |
| Touched code paths listed | done | `launcher/src/main/resources/application.yml`、`launcher/src/main/resources/application-prod.yml`、`launcher/src/main/java/com/foggy/navigator/launcher/FogyNavigatorApplication.java`、`launcher/src/main/java/com/foggy/navigator/launcher/ProductionConfigurationGuard.java`、`launcher/src/test/java/com/foggy/navigator/launcher/ProductionConfigurationGuardTest.java`、`launcher/pom.xml`。 |
| Self-review completed | done | 检查确认 guard 仅对 `prod` / `production` 生效，不改变 dev/test profile；prod 缺失环境变量通过空默认值进入统一校验。 |
| Test status recorded | done | 定向 guard 测试和 launcher `-am` 回归均通过。 |
| Remaining risks recorded | done | 未引入 Flyway/Liquibase；生产 schema 仍需通过 migration 或人工流程预先准备。`user-auth-module` 等非 launcher 独立启动配置未纳入本切片。 |
| Acceptance readiness | ready | Stage 5 可进入功能级验收签收。 |

## Acceptance Status

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-25
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage5-runtime-config-hardening-acceptance.md
- blocking_items: none
- follow_up_required: yes
