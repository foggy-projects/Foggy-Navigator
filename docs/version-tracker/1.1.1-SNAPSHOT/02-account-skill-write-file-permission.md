# 账号私有 Skill 写入权限与创建闭环

## 文档作用

- doc_type: deferred-workitem
- intended_for: execution-agent / reviewer
- purpose: 承接 1.1.0 的 Skill Registry Step 3，定义 LLM 通过受控 `write_file` 创建账号私有 Skill 时的路径权限与验收边界

## 来源

- source_version: 1.1.0-SNAPSHOT
- source_doc: `docs/version-tracker/1.1.0-SNAPSHOT/34-skill-registry-design.md`
- deferred_item: Step 3 `write_file` 工具路径限制
- moved_at: 2026-04-28

## 背景

1.1.0 已完成 Skill Registry 的只读加载链路：

1. 公共 Skill GitLab 同步。
2. 账号私有 Skill 从 `<data_root>/accounts/<account-id>/skills/` 加载。
3. query-time 按 `userId` / `accountId` 加载账号 Skill。
4. Frame 创建时快照 manifest，避免后续 registry reload 影响当前执行。

未闭环的是“LLM 通过文件工具创建私有 Skill”：

1. 当前 `langgraph-biz-worker` 尚无通用受控文件写入工具。
2. 1.1.1 已开始设计 `create_artifact/read_artifact`，更适合统一处理长内容、文件外部化和账号目录权限。

## 目标

1. 定义账号目录内创建私有 Skill 的工具边界。
2. 禁止越权写入其他账号目录、公共 Skill、内建 Skill、Worker 代码目录。
3. 明确 `create_artifact/read_artifact` 与 `write_file` 的关系。
4. Skill 写入后能被 1.1.0 已完成的 `SkillRegistry.load(account_id)` 发现。

## 建议路径规则

首版只允许写入当前账号目录：

```text
<data_root>/accounts/<account-id>/
```

创建私有 Skill 时只允许写入：

```text
<data_root>/accounts/<account-id>/skills/<skill-name>/SKILL.md
<data_root>/accounts/<account-id>/skills/<skill-name>/references/**
<data_root>/accounts/<account-id>/skills/<skill-name>/assets/**
```

禁止：

1. `../` 或绝对路径。
2. 写入 `skills/public/`、`skills/builtin/`。
3. 写入其他账号目录。
4. 写入 `.git/`、可执行脚本白名单之外的路径。
5. Linux symlink 路径链路或 symlink 目标。

## 与 Artifact Runtime 的关系

首版不要暴露不受约束的裸文件系统写入，而是分两层：

1. `create_artifact`：创建普通长内容资源，返回 `artifact_ref`。
2. `write_file`：创建或覆盖账号目录内的受控文件，必须绑定 `account_id` 与允许路径集合。

`write_file` 必须遵守本文档定义的权限边界：Runtime 注入账号根目录，服务端做 resolved path 校验，模型不得传真实文件系统路径。

账号目录文件工具族的完整设计见：

```text
docs/version-tracker/1.1.1-SNAPSHOT/04-account-file-tools-design.md
```

## 与 Skill Writer 的关系

Skill Writer 负责指导 LLM 生成符合规范的 `SKILL.md` 内容、references 和 assets 内容，并指导 LLM 使用 `write_file` 将这些内容保存到账号私有 Skill 目录。

`write_file` 只负责受控落盘与权限校验：

1. 不负责生成 Skill 内容。
2. 不负责判断 Skill 业务语义是否合理。
3. 不接受真实文件系统路径。
4. 不写公共 Skill、内建 Skill 或其他账号目录。

## 验收标准

1. 正常创建 `<data_root>/accounts/user-001/skills/my-skill/SKILL.md` 成功。
2. 写入 `<data_root>/accounts/user-002/...` 被拒绝。
3. 写入 `skills/public/...` 被拒绝。
4. 写入 `../outside.txt` 被拒绝。
5. 写入成功后 `SkillRegistry.load(account_id="user-001")` 能发现新 Skill。
6. query-time 使用 `userId=user-001` 能路由到新 Skill。
7. symlink 父目录、symlink 目标写入均被拒绝。
8. `.fsscript` 可作为文本资源写入 `references/**` 或 `assets/**`，不允许替代根部 `SKILL.md`。

## 测试要求

1. 路径解析单元测试。
2. 权限拒绝测试。
3. Skill 创建后 registry reload 测试。
4. query-time 私有 Skill 发现回归测试。
5. Linux symlink 拒绝测试。
6. `.fsscript` 允许路径测试。

## 当前状态

- status: ready-for-execution-planning
- target_version: 1.1.1-SNAPSHOT
- blocking_1_1_0: no
- owner: langgraph-biz-worker / artifact runtime

## 1.1.1 首版决策

首版采用受控 `write_file`，不提供不受账号根目录与路径白名单约束的裸文件系统工具。

原因：

1. Skill Writer 负责指导 LLM 如何生成和保存 Skill 文件，Runtime 不需要提供 Skill 专用写入工具。
2. `write_file` 可以同时服务账号目录内的受控文件写入与 Skill 文件保存。
3. `write_file` 必须由 Runtime 注入账号根目录，并在服务端做 resolved path 校验，不能让模型自己决定真实文件系统根路径。

## `write_file` 输入建议

```json
{
  "relative_path": "skills/my-skill/SKILL.md",
  "content": "---\nname: my-skill\n---\n\n# My Skill",
  "encoding": "utf-8",
  "mode": "create"
}
```

约束：

1. `relative_path` 必须是相对当前账号根目录的路径。
2. 创建私有 Skill 时，`relative_path` 只能匹配 `skills/<skill-name>/SKILL.md`、`skills/<skill-name>/references/**`、`skills/<skill-name>/assets/**`。
3. `account_id` 不由模型传入，由 Runtime 注入。
4. 根部只允许 `SKILL.md`；`references/**` 和 `assets/**` 首版允许 `.md`、`.txt`、`.json`、`.yaml`、`.yml`、`.fsscript`。
5. 首版不允许写脚本文件；`.fsscript` 仅作为文本资源允许写入 `references/**` 或 `assets/**`，不绑定执行语义。
6. 首版支持 `mode=create` 与 `mode=overwrite`：默认 `mode=create`，`overwrite` 必须显式传入，并覆盖权限、runtime audit record 和回归测试。
7. 单文件写入大小上限 1MB。
8. 同一文件写操作串行化。
9. `overwrite` 支持可选 `expected_sha256` 乐观并发校验。

## `write_file` 输出建议

```json
{
  "ok": true,
  "relative_path": "skills/my-skill/SKILL.md",
  "size": 128,
  "summary": "account file created"
}
```

输出不得包含真实物理路径。

## 错误边界

首版至少需要返回可区分的错误：

1. `invalid_relative_path`：路径不是合法相对路径或不在允许集合内。
2. `invalid_skill_name`：Skill 路径中的 Skill 名称不是单个安全路径段。
3. `path_traversal_rejected`：包含 `../`、绝对路径或解析后越出账号目录。
4. `forbidden_target`：目标为公共 Skill、内建 Skill、其他账号目录或 Worker 代码目录。
5. `unsupported_file_type`：首版不允许写入的文件类型。
6. `symlink_rejected`：路径链路或目标为 symlink。
7. `file_too_large`：超过首版 1MB 写入上限。
8. `checksum_mismatch`：`expected_sha256` 与当前文件不匹配。
9. `storage_write_failed`：写入失败。
