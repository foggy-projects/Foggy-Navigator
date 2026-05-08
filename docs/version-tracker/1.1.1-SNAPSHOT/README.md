# 1.1.1-SNAPSHOT

下个版本跟踪目录，用于存放 `1.1.1-SNAPSHOT` 阶段围绕业务型 Worker、Skill Runtime 补充设计与上下文治理细化事项。

## 文档作用

- doc_type: version-index
- intended_for: root-controller | execution-agent | reviewer | signoff-owner
- purpose: 定义 `1.1.1-SNAPSHOT` 的版本目标、边界、执行顺序和验收入口

本版本当前重点方向：

1. LangGraph Biz Worker 补充设计
2. Skill Runtime 细化与上下文治理
3. Artifact 外部化与工具长参数治理

## 版本目标

`1.1.1-SNAPSHOT` 承接 `1.1.0-SNAPSHOT` 的封版结论，重点处理以下 follow-up：

1. 落地业务型 Worker 的 Artifact Runtime 首版能力：`create_artifact` / `read_artifact`。
2. 建立长参数上下文治理规则：长内容外部化后，活跃上下文默认只保留 `artifact_id + summary`。
3. 闭环账号私有 Skill 创建：允许模型通过受控 `write_file` 工具写入当前账号私有 Skill 目录。
4. 建立账号目录文件工具族：`read_file`、`list_files`、`write_file`、`edit_file`、`str_replace`、`patch_file`。
5. 闭环路径权限：禁止跨账号、公共 Skill、内建 Skill、Worker 代码目录、绝对路径和 `../` 越权写入。

## 非目标

首版不包含：

1. `delete_artifact` / `list_artifacts`。
2. 对象存储、数据库 metadata 后端、向量索引。
3. 跨账号 artifact 共享、跨任务共享 task-scope artifact。
4. 二进制大文件上传。
5. 不受账号根目录与路径白名单约束的裸文件系统工具。
6. 真实业务工具注册表与外部业务系统适配。
7. Anthropic tool_use 专项验证。
8. 完整 `SKILL.md` body 注入 Frame prompt。

## 完成标准

版本完成必须满足：

1. Artifact 创建、读取、账号隔离、任务隔离测试通过。
2. 长参数外部化后，Worker 自己持久化或继续传给 LLM 的历史消息不再携带原始长 `content`，而是替换为 `artifact_id + summary` 轻量占位。
3. 账号目录文件工具族使用同一套路径权限与输出治理规则。
4. `write_file(mode=overwrite)` 必须显式传入，且覆盖、权限、runtime audit record 测试通过。
5. `patch_file` 使用 unified diff，单文件、原子写入；冲突整体失败、不产生半写入的测试通过。
6. 账号私有 Skill 文件写入成功后，`SkillRegistry.load(account_id)` 能发现新 Skill。
7. 越权路径拒绝测试通过：其他账号目录、公共 Skill、内建 Skill、绝对路径、`../outside`。
8. query-time 使用目标 `userId/accountId` 能路由到新建私有 Skill。
9. Linux 路径安全测试通过：路径链路中的 symlink、目标 symlink 均被拒绝。
10. `.fsscript` 作为账号私有 Skill 文本资源可写入 `references/**` 或 `assets/**`，不允许替代根部 `SKILL.md`，不绑定执行语义。
11. `tools/langgraph-biz-worker` 全量 Python 测试通过。
12. 完成实现自检、质量检查、测试覆盖审计和版本验收记录。

## 建议执行顺序

1. 收口 Artifact 工具契约与上下文治理落点。
2. 收口账号目录文件工具族契约和统一路径权限模型。
3. 实现 Artifact Runtime 文件后端和权限校验。
4. 接入 LLM Skill Agent 工具 schema / tool-call loop。
5. 实现受控文件工具，由 Skill Writer 指导 LLM 将 Skill 内容写入允许路径。
6. 回归 Skill Registry 账号私有 Skill 发现和 query-time 路由。
7. 补齐测试、progress、quality、coverage、acceptance 证据。

## 条目列表

- [01-biz-worker-artifact-externalization-design.md](./01-biz-worker-artifact-externalization-design.md) - 架构设计 / `create_artifact/read_artifact`、账号目录下的首版文件实现、长参数外部化与上下文治理规则
- [02-account-skill-write-file-permission.md](./02-account-skill-write-file-permission.md) - 延期项 / 账号私有 Skill 创建、`write_file` 路径权限、Artifact Runtime 与 Skill 目录写入边界
- [03-artifact-and-account-skill-implementation-plan.md](./03-artifact-and-account-skill-implementation-plan.md) - 执行计划 / ownership、代码触点、阶段拆解、测试与验收门槛
- [04-account-file-tools-design.md](./04-account-file-tools-design.md) - 架构设计 / `read_file/list_files/write_file/edit_file/str_replace/patch_file` 账号目录文件工具族与权限模型
- [progress/artifact-and-account-skill-progress.md](./progress/artifact-and-account-skill-progress.md) - 进度模板 / 阶段状态、测试证据、自检与验收标准映射
- [quality/artifact-and-account-skill-implementation-quality.md](./quality/artifact-and-account-skill-implementation-quality.md) - 质量闸门 / 实现质量检查与阻断修复记录
- [coverage/artifact-and-account-skill-coverage-audit.md](./coverage/artifact-and-account-skill-coverage-audit.md) - 覆盖审计 / 验收项、BUG 回归与测试证据映射
- [acceptance/version-signoff.md](./acceptance/version-signoff.md) - 版本验收 / `accepted` 签收记录

## Acceptance Status

- acceptance_status: signed-off
- acceptance_decision: accepted
- signed_off_by: Codex
- signed_off_at: 2026-04-29
- acceptance_record: docs/version-tracker/1.1.1-SNAPSHOT/acceptance/version-signoff.md
- blocking_items: none
- follow_up_required: no
