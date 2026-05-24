# Skill 注册中心设计

## 文档作用

- doc_type: requirement + implementation-plan
- intended_for: worker-runtime + root-controller + reviewer
- purpose: 定义 Skill 的获取、存储、加载机制，包括公共 Skill（GitLab 同步）和用户私有 Skill（账号目录写入）

## 文档分区

- **§1-§3**：背景、目标、非目标（约束）
- **§4-§6**：公共 Skill / 用户 Skill / 加载优先级（设计）
- **§7-§8**：执行方案与完成定义（当前活跃）

## 当前状态

- status: closed-with-deferral
- last_updated: 2026-04-28
- implementation_status: Step 1 completed, Step 2 completed for query-time account loading, Step 3 deferred to 1.1.1-SNAPSHOT
- acceptance_readiness: ready-for-acceptance-with-deferral

当前已完成：

1. 公共 Skill Git 同步：配置项、`skill_git_sync.py`、`/api/v1/skills/sync`、`/api/v1/skills/webhook`、启动同步接入。
2. 账号 Skill 加载：`SkillRegistry.load(account_id)` 支持 `data/accounts/<account-id>/agent/skills`，优先级高于 public/builtin/legacy。
3. query 执行前按 `userId` 或 `context.account_id/accountId` 重新加载账号 Skill。
4. Frame 创建时快照当前 Skill manifest，避免后续 registry reload 改变当前 Frame 的 output schema / promote 规则。
5. 账号 ID 路径穿越校验已补测试。

已延期到 `1.1.1-SNAPSHOT`：

1. Step 3 `write_file` 工具路径限制。当前 `langgraph-biz-worker` 代码中尚无通用 `write_file` 工具，因此本项不能在 1.1.0 内闭环，已移入 `docs/version-tracker/1.1.1-SNAPSHOT/02-account-skill-write-file-permission.md`。
2. 多 Worker 实例之间的 Skill 分发不在本版本范围。
3. 真正面向上游的 Skill CRUD / 管理 API 仍是非目标。

## 1. 背景

当前 Skill 以两种方式存在：

1. `skills/builtin/` — 随 Worker 代码一起发布的内建 Skill（4 个 Mock Skill）
2. `manifests/` — 遗留 YAML manifest（已兼容，优先级最低）

缺少的能力：

- 平台团队维护的公共 Skill 没有独立的发布渠道，每次更新要重新部署 Worker
- 员工无法自建 Skill

## 2. 目标

1. 公共 Skill 从 GitLab 仓库同步，支持启动拉取、手动触发、Webhook 自动拉取
2. 员工可在自己的账号目录下创建私有 Skill，通过 LLM 对话中的文件写入工具完成
3. SkillRegistry 按 `account > public > builtin > legacy` 优先级加载

## 3. 非目标

- Skill CRUD HTTP API
- 前端 Skill 配置台 / 编辑器
- Skill 版本回滚
- 跨 Worker 实例的 Skill 分发
- Skill 市场 / 发布系统

## 4. 公共 Skill（GitLab 同步）

### 4.1 仓库结构

一个 GitLab 仓库存放所有公共 Skill：

```text
foggy-skills/                     # GitLab 仓库
  ├── exception-triage/
  │   ├── SKILL.md
  │   ├── references/
  │   └── assets/
  ├── order-evidence-collect/
  │   ├── SKILL.md
  │   └── references/
  ├── rule-check/
  │   └── SKILL.md
  └── README.md
```

每个顶层目录是一个 Skill，入口文件固定为 `SKILL.md`。

### 4.2 同步机制

Worker 配置：

```python
class Settings:
    skill_git_repo: str = ""          # GitLab 仓库 URL
    skill_git_branch: str = "main"    # 跟踪分支
    skill_git_token: str = ""         # GitLab access token（private repo）
    skill_sync_on_startup: bool = True  # 启动时自动同步
```

同步触发方式（三选一或组合）：

| 触发方式 | 说明 |
|----------|------|
| **启动同步** | Worker 启动时 `git clone`（首次）或 `git pull`（已存在），同步到 `skills/public/` |
| **手动触发** | `POST /api/v1/skills/sync` — 技术人员主动触发一次拉取 |
| **Webhook 自动** | GitLab push event webhook → Worker `/api/v1/skills/webhook` → 自动 `git pull` |

同步目标目录：

```text
<worker-root>/
  skills/
    public/        ← git clone/pull 到这里
    builtin/       ← 不受 git 影响，随 Worker 代码发布
```

### 4.3 同步流程

```text
POST /api/v1/skills/sync  (或 Webhook 或启动)
  → 检查 skills/public/ 是否已是 git repo
    → 否：git clone <repo> skills/public/
    → 是：cd skills/public/ && git fetch && git reset --hard origin/<branch>
  → SkillRegistry.load() 重新加载
  → 返回加载结果（新增/更新/删除的 Skill 列表）
```

### 4.4 Webhook 契约

GitLab push event payload 包含 `ref` 字段：

```json
{
  "ref": "refs/heads/main",
  "project": { "path_with_namespace": "foggy/foggy-skills" },
  ...
}
```

Worker 只响应配置的分支（`skill_git_branch`），其他分支的 push 忽略。

Webhook 端点需要 token 校验（GitLab secret token），防止伪造请求。

## 5. 用户私有 Skill（账号目录）

### 5.1 存储位置

```text
<worker-root>/
  accounts/
    <account-id>/
      skills/
        my-custom-skill/
          SKILL.md
          references/
```

### 5.2 创建方式

不提供专门的 `save_skill` 工具。

LLM 在对话中通过标准的文件写入工具（`write_file`）帮用户创建 SKILL.md，但工具的权限限制为：

- **只能读写** `accounts/<当前 account-id>/` 目录
- **不能越目录访问**其他账号或 `skills/public/`、`skills/builtin/`

用户创建 Skill 的典型对话：

```
用户：帮我创建一个"日报生成"的 Skill
LLM：好的，我来创建...
     → write_file("accounts/user-001/agent/skills/daily-report/SKILL.md", content)
用户：可以用了吗？
LLM：已创建，下次你提到"日报"时我会自动使用这个 Skill。
```

### 5.3 生效时机

用户创建 Skill 后，需要 SkillRegistry 重新加载才能发现。两种方案：

- **方案 A（简单）**：每次 query 请求时重新扫描账号 Skill 目录
- **方案 B（高效）**：write_file 工具写入 `accounts/<id>/agent/skills/` 路径后，自动触发 registry reload

首版用方案 A（简单），Skill 数量少时性能不是问题。

## 6. 加载优先级

SkillRegistry.load() 按以下顺序加载，后加载的覆盖先加载的（同名 Skill 高优先级覆盖低优先级）：

```
1. legacy     — manifests/*.yaml（最低，向后兼容）
2. builtin    — skills/builtin/<name>/SKILL.md
3. public     — skills/public/<name>/SKILL.md（GitLab 同步）
4. account    — accounts/<account-id>/agent/skills/<name>/SKILL.md（最高）
```

当前 SkillRegistry 已支持 `builtin > public > legacy`（Phase 6B 实现），需新增 `account` 层。

## 7. 执行方案

### Step 1：GitLab 同步（Python 侧）

| # | 任务 | 完成定义 |
|---|------|---------|
| 1 | config.py 新增 `skill_git_repo/branch/token/sync_on_startup` | ✅ 配置项可读取 |
| 2 | 新建 `runtime/skill_git_sync.py`：clone/pull 逻辑 | ✅ 单元测试通过（mock git 命令） |
| 3 | 新建 `routes/skills.py`：`POST /api/v1/skills/sync` + `POST /api/v1/skills/webhook` | ✅ HTTP 测试通过 |
| 4 | main.py lifespan 启动同步 | ✅ 启动时自动拉取（如配置了 repo） |
| 5 | SkillRegistry.load() 后触发 SSE 通知（可选） | — |

### Step 2：用户 Skill 加载（Python 侧）

| # | 任务 | 完成定义 |
|---|------|---------|
| 6 | SkillRegistry 新增 `load_account_skills(account_id)` | ✅ 单元测试通过 |
| 7 | query 路由在执行前加载当前账号的 Skill | ✅ 现有测试不回归；Frame manifest 快照已补充 |

### Step 3：文件写入工具权限（Python 侧）

| # | 任务 | 完成定义 |
|---|------|---------|
| 8 | write_file 工具的路径限制：只允许写入 `accounts/<当前 account-id>/` | ↪ 已延期到 `1.1.1-SNAPSHOT/02-account-skill-write-file-permission.md` |

### 执行顺序

```text
Step 1 (1→2→3→4，串行)
  ↓
Step 2 (6→7，串行)
  ↓
Step 3 (8)
  ↓
全量测试回归
```

## 8. 完成定义

每个 Step 的交付必须满足：

1. 相关单元测试 **运行通过**
2. 现有 184 个 Python 测试不回归
3. 经过后置评审链路：quality-gate → test-coverage-audit → acceptance

## 9. 2026-04-28 Execution Check-in

- completed_work: 完成 Step 1、Step 2 代码核对与账号 Skill query-time 加载补齐；补充 manifest snapshot，防止当前 Frame 被后续 registry reload 影响。
- touched_code:
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/skill_registry.py`
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/skill_runtime.py`
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/graphs/root_graph.py`
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/routes/query.py`
  - `tools/langgraph-biz-worker/tests/test_skill_git_sync.py`
  - `tools/langgraph-biz-worker/tests/test_account_skill_routing.py`
- testing_status: pass。定向测试 `20 passed`，全量 `tools/langgraph-biz-worker` 回归 `202 passed`。
- experience_status: N/A，纯后端 Runtime 能力，无 UI 交互。
- remaining_risk: Step 3 依赖后续通用文件写入工具，不阻塞 Skill Registry 的只读加载链路，已转入 1.1.1 跟踪。
- acceptance_readiness: ready-for-acceptance-with-deferral。

## 10. 测试记录

2026-04-28 已运行：

1. `tools/langgraph-biz-worker`: `.venv/Scripts/python.exe -m pytest tests/test_skill_git_sync.py tests/test_account_skill_routing.py tests/test_query.py`
   - 结果：`20 passed`
2. `tools/langgraph-biz-worker`: `.venv/Scripts/python.exe -m pytest tests`
   - 结果：`202 passed`

新增覆盖：

1. account-private Skill 覆盖 builtin Skill。
2. 无 account_id 时不加载账号层，避免私有 Skill 泄漏到无账号请求。
3. account_id 路径穿越被拒绝。
4. query route 使用 `userId` 触发账号 Skill 加载。
5. Frame 创建时保存 manifest snapshot，避免后续 registry reload 影响当前 Frame。

## 11. 1.1.0 收口结论

34 在 1.1.0 范围内按“只读加载链路”收口：

1. 公共 Skill Git 同步已完成。
2. 账号私有 Skill 加载已完成。
3. query-time 账号 Skill 发现已完成。
4. 当前 Frame 使用的 manifest 已快照，避免 registry reload 干扰运行中的 Frame。

`write_file` 创建私有 Skill 与路径权限控制不再作为 1.1.0 阻塞项，已移动到 1.1.1。
