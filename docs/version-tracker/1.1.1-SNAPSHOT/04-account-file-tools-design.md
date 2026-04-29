# 账号目录文件工具族设计

## 文档作用

- doc_type: architecture-design
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 定义业务型 Worker 中账号目录文件工具族、权限边界、上下文治理规则，以及与 Skill Writer / Artifact Runtime 的关系

## Version

- `1.1.1-SNAPSHOT`

## Status

- Ready for execution planning
- 2026-04-28

## 1. 背景

`1.1.0-SNAPSHOT` 已完成账号私有 Skill 的只读加载链路，但尚未闭环 LLM 创建和维护账号私有 Skill 文件。

单独提供 `write_file` 可以解决首次创建，但后续修改 Skill 时还需要读取、列目录、精确替换和 patch 能力。为避免每个工具重复做路径权限，本版本将文件能力设计为统一工具族。

## 2. 设计目标

1. 提供账号目录内受控文件工具族：
   - `list_files`
   - `read_file`
   - `write_file`
   - `edit_file`
   - `str_replace`
   - `patch_file`
2. 所有工具共用 AccountPathGuard。
3. 对模型暴露逻辑相对路径，不暴露真实物理路径。
4. 默认不把大文件全文长期放入活跃上下文。
5. 支持 Skill Writer 指导 LLM 创建和维护账号私有 Skill。

## 3. 非目标

首版不包含：

1. 任意文件系统访问。
2. 公共 Skill / 内建 Skill 写入。
3. 跨账号文件访问。
4. Worker 代码目录读写。
5. 二进制文件上传和编辑。
6. Git commit / diff review / merge conflict 工作流。
7. 自动格式化 Skill 内容。

## 4. 统一路径模型

模型侧只传账号根目录下的逻辑相对路径：

```json
{
  "relative_path": "skills/my-skill/SKILL.md"
}
```

Runtime 注入：

1. `account_id`
2. `task_id`
3. `data_root`
4. 当前允许路径集合

服务端解析：

```text
<data_root>/accounts/<account-id>/<relative_path>
```

路径校验必须：

1. 拒绝绝对路径。
2. 拒绝 `../`、`.`、空路径段。
3. resolved path 必须位于 `<data_root>/accounts/<account-id>/` 下。
4. 不返回真实物理路径。
5. Worker 仅按 Linux 运行环境设计；路径链路中的 symlink、目标 symlink 均拒绝。
6. 新建文件时父目录必须真实位于账号根目录下且不是 symlink。

## 5. 允许路径集合

首版围绕账号私有 Skill 创建与维护，允许：

```text
skills/<skill-name>/SKILL.md
skills/<skill-name>/references/**
skills/<skill-name>/assets/**
```

其中：

1. `<skill-name>` 必须是单个安全路径段。
2. `list_files` 可列 `skills/` 与 `skills/<skill-name>/`，用于发现账号内已有私有 Skill。
3. 根部只允许 `SKILL.md`。
4. `references/**` 和 `assets/**` 首版允许文本资源：`.md`、`.txt`、`.json`、`.yaml`、`.yml`、`.fsscript`。
5. `.fsscript` 仅作为文本资源，不绑定执行语义。
6. 是否允许更多账号目录路径或文件类型，必须另行评审。

## 6. 工具契约

### 6.1 `list_files`

作用：列出允许目录下的文件，帮助 LLM 发现已有 Skill 文件。

输入：

```json
{
  "relative_path": "skills/my-skill",
  "recursive": false,
  "max_entries": 100
}
```

输出：

```json
{
  "ok": true,
  "relative_path": "skills/my-skill",
  "entries": [
    {"path": "skills/my-skill/SKILL.md", "type": "file", "size": 512}
  ]
}
```

约束：

1. 输出只包含逻辑相对路径。
2. 默认不递归。
3. 递归时必须限制数量。

### 6.2 `read_file`

作用：读取账号目录内允许文件。

输入：

```json
{
  "relative_path": "skills/my-skill/SKILL.md",
  "start_line": 1,
  "max_lines": 200
}
```

输出：

```json
{
  "ok": true,
  "relative_path": "skills/my-skill/SKILL.md",
  "content": "...",
  "start_line": 1,
  "end_line": 80,
  "truncated": false
}
```

约束：

1. 默认限制读取行数或字节数。
2. 首版默认最多返回 200 行或 64KB。
3. 大文件必须返回 `truncated=true`。
4. 如需长期引用大内容，应转入 `create_artifact`。

### 6.3 `write_file`

作用：完整写入账号目录内允许文件。

输入：

```json
{
  "relative_path": "skills/my-skill/SKILL.md",
  "content": "...",
  "encoding": "utf-8",
  "mode": "create"
}
```

`mode`：

1. `create`：文件已存在则拒绝。
2. `overwrite`：显式覆盖已有文件，必须保留测试和 runtime audit record。

首版默认：

1. 默认 `mode=create`。
2. `overwrite` 纳入 1.1.1 首版范围，但必须显式传入。
3. 首版单文件写入大小上限为 1MB。
4. 同一文件写操作必须串行化。
5. `overwrite` 支持可选 `expected_sha256` 做乐观并发校验；提供后不匹配必须拒绝。

最小 runtime audit record 字段：

1. `operation`
2. `account_id`
3. `task_id`
4. `relative_path`
5. `sha256_before`
6. `sha256_after`
7. `timestamp`
8. `actor`

### 6.4 `str_replace`

作用：对文件中的唯一文本片段做精确替换，适合小范围修改。

输入：

```json
{
  "relative_path": "skills/my-skill/SKILL.md",
  "old_str": "old text",
  "new_str": "new text"
}
```

约束：

1. `old_str` 必须唯一匹配。
2. 零匹配或多匹配必须拒绝。
3. 成功后返回轻量摘要，不返回完整文件。

### 6.5 `edit_file`

作用：语义化编辑入口，用于较高层的局部编辑。

输入建议：

```json
{
  "relative_path": "skills/my-skill/SKILL.md",
  "operation": "replace_section",
  "anchor": "## 执行流程",
  "content": "..."
}
```

首版约束：

1. `edit_file` 不应绕过 `str_replace` / `patch_file` 的确定性校验。
2. 如无法确定唯一编辑位置，应拒绝并要求先 `read_file`。
3. 复杂编辑优先降级为 `read_file` + `str_replace`。

### 6.6 `patch_file`

作用：应用 unified diff，适合多处修改。

输入建议：

```json
{
  "relative_path": "skills/my-skill/SKILL.md",
  "patch": "--- a/skills/my-skill/SKILL.md\n+++ b/skills/my-skill/SKILL.md\n@@ ...",
  "expected_sha256": "optional-current-file-sha256"
}
```

约束：

1. `patch_file` 纳入 1.1.1 版本完成门槛，首版只支持 unified diff。
2. 实际目标文件只来自 `relative_path`；diff header 中的路径只能用于校验或忽略，不能决定写入位置。
3. patch 必须只作用于单个允许文件。
4. patch 冲突时必须整体失败，不产生半写入。
5. 冲突包括语法非法、上下文匹配失败、删除行不存在、同一 hunk 多义匹配、试图修改非目标文件。
6. 成功时先在内存中应用 patch，再写临时文件，fsync 后 `os.replace` 原子替换。
7. 同一文件写操作必须串行化。
8. 可选 `expected_sha256` 不匹配时必须拒绝。
9. 成功输出只返回 `ok`、`relative_path`、`changed`、`sha256_before`、`sha256_after`、`summary`，不返回完整文件内容。

## 7. 上下文治理

1. `list_files` 返回条目，不返回文件内容。
2. `read_file` 默认限 200 行或 64KB。
3. `write_file` / `str_replace` / `edit_file` / `patch_file` 成功后只返回摘要，不返回完整新内容。
4. 大内容应通过 `create_artifact` 外部化，再由模型传引用。

## 8. 与 Skill Writer 的关系

Skill Writer 负责：

1. 生成符合规范的 `SKILL.md` 内容。
2. 决定需要哪些 `references/**` 或 `assets/**`。
3. 指导 LLM 使用 `write_file` 保存新 Skill。
4. 修改已有 Skill 时，指导 LLM 先 `read_file`，再使用 `str_replace` 或 `patch_file`。

文件工具 Runtime 负责：

1. 路径权限。
2. 文件读写。
3. 冲突校验。
4. 轻量结果返回。

## 9. 验收标准

1. `list_files` 不泄漏真实路径。
2. `read_file` 支持大小限制和截断标识。
3. `write_file(mode=create)` 创建成功，文件已存在时拒绝。
4. `write_file(mode=overwrite)` 必须显式传入，且覆盖权限、runtime audit record 测试。
5. `str_replace` 唯一匹配成功，零匹配/多匹配拒绝。
6. `edit_file` 无法定位唯一位置时拒绝。
7. `patch_file` 使用 unified diff，单文件、原子写入，冲突时整体失败。
8. 所有工具拒绝绝对路径、`../`、其他账号目录、公共 Skill、内建 Skill、Worker 代码目录。
9. Linux symlink 路径拒绝测试通过。
10. `.fsscript` 可写入 `references/**` 或 `assets/**`，不允许替代根部 `SKILL.md`，不自动执行。

## 10. 实施优先级

1. P0：AccountPathGuard、`list_files`、`read_file`、`write_file`。
2. P1：`str_replace`、`edit_file`。
3. P2：`patch_file`，仍属于 1.1.1 必做范围。

P2 仍属于 1.1.1 确认范围；进入开发前需完成本设计评审，开发中如发现 patch 契约需要调整，必须先回写设计并暂停评审。
