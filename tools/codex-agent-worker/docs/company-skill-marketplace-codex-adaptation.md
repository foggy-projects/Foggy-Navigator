# Company Skill Marketplace 改造成 Codex 兼容仓库的讨论稿

本文用于讨论 `http://gitlib.foggysource.com/foggy-tools/company-skill-marketplace.git` 是否以及如何改造成更适合 Codex 使用的技能仓库。

## 1. 结论摘要

当前仓库：

- 适合作为 Claude marketplace 仓库
- 不适合作为 Codex 原生技能仓库直接使用
- 仓库中的大多数单个 skill 内容本身接近 Codex 要求
- 最合理的方案不是推翻重做，而是增加一层 Codex 导出结构

建议采用：

1. 保留现有 Claude marketplace 结构不动
2. 增加一个标准化导出层，面向 Codex 和其他遵循 Agent Skills 标准的工具
3. skill 内容继续以 `SKILL.md + scripts + references + assets` 为核心
4. Claude 专有的 marketplace 元数据和包装文件保持为分发层，不作为跨平台主格式

## 2. 当前仓库现状

当前仓库核心结构大致如下：

```text
company-skill-marketplace/
├── .claude-plugin/
│   └── marketplace.json
├── plugins/
│   └── <plugin-name>/
│       ├── .claude-plugin/
│       │   └── plugin.json
│       └── skills/
│           └── <skill-name>/
│               ├── SKILL.md
│               ├── scripts/          # 部分 skill 有
│               └── references/       # 部分 skill 有
```

这说明它是一个：

- 以 Claude marketplace 为入口的插件仓库
- skill 内容嵌在 `plugins/*/skills/*` 下

而不是一个：

- 以通用 `skills/<name>/SKILL.md` 为入口的标准技能仓库

## 3. 为什么它不算 Codex 原生兼容

### 3.1 根目录是 Claude marketplace 格式

当前仓库根目录依赖：

- `.claude-plugin/marketplace.json`
- `plugins/<plugin>/`
- `plugin.json`

这些对 Claude marketplace 有意义，但对 Codex 没有直接价值。

Codex 更适合消费的是：

```text
skills/<skill-name>/SKILL.md
```

或直接安装到：

- `.agents/skills/`
- `~/.codex/skills/`

### 3.2 skill 发现路径不适合直接给 Codex

Codex 侧的主流兼容路径是：

- 仓库本身直接是 skills 集合
- 或通过 `npx skills add` 把 skill 安装到 Codex 识别目录

但当前仓库的真实 skill 深藏在：

```text
plugins/<plugin>/skills/<skill>/
```

这要求安装器或使用方知道 Claude marketplace 的内部结构。

### 3.3 部分 frontmatter 带有平台特有字段

当前部分 skill 里有类似字段：

```yaml
allowed-tools: Read, Grep, Glob, WebFetch, Bash
```

或者更复杂的 Claude 风格工具声明。

这类字段未必会让 Codex 完全不可用，但它们不是跨平台最稳的公共子集。

当前跨平台最稳的 frontmatter 只建议保留：

- `name`
- `description`

### 3.4 部分 skill 文本内容明显绑定 Claude 生态

例如内容里会提到：

- `.claude/skills/`
- Claude Code
- `/plugin marketplace ...`
- Claude Worker

这些不会阻止 Codex 读取 skill，但会影响跨平台可读性和准确性。

## 4. 为什么说大多数单个 skill 仍然“接近可用”

仓库里的大部分单个 skill 目录其实已经具备核心结构：

```text
<skill-name>/
├── SKILL.md
├── scripts/        # 可选
└── references/     # 可选
```

而且 `SKILL.md` 一般都已经有：

- `name`
- `description`

因此问题主要不在 skill 内容本体，而在：

- 仓库根布局
- Claude 专有包装
- 一些平台绑定字段和文案

## 5. 推荐改造目标

建议把仓库分成两层理解：

### 5.1 源内容层

保留技能本体：

- `SKILL.md`
- `scripts/`
- `references/`
- `assets/`

这是跨平台可复用的核心。

### 5.2 平台包装层

按不同平台生成包装：

- Claude marketplace 包装
- Codex 安装目录导出
- 未来也可以支持 Cursor、Cline、通用 `.agents/skills`

原则：

- skill 本体是源
- 平台包装是生成物

## 6. 推荐目录方案

推荐引入一个新的标准目录：

```text
company-skill-marketplace/
├── skills/                             # 新增：标准 skill 导出层
│   ├── playwright-cli/
│   │   ├── SKILL.md
│   │   └── references/
│   ├── send-email/
│   │   ├── SKILL.md
│   │   ├── scripts/
│   │   └── references/
│   └── ...
├── .claude-plugin/
│   └── marketplace.json
├── plugins/                            # 现有 Claude marketplace 结构保留
│   └── ...
└── scripts/
    └── export_codex_skills.py          # 新增：导出脚本
```

这样做的好处：

- Claude 继续走现有 marketplace
- Codex 可以直接消费 `skills/`
- 其他支持 Agent Skills 的工具也更容易接入

## 7. 三种可选改造方案

### 方案 A：仅增加 Codex 导出层

做法：

- 保留 `plugins/*/skills/*` 为源
- 增加 `scripts/export_codex_skills.py`
- 每次从源目录导出到 `skills/*`

优点：

- 改动小
- 不影响现有 Claude 生态
- 讨论和落地成本最低

缺点：

- 会存在两份目录
- 需要维护导出流程

适合：

- 短期最稳妥落地

### 方案 B：把 `skills/*` 变成主源，Claude 包装从它生成

做法：

- 将 `skills/*` 定义为唯一真源
- `plugins/*/skills/*` 由脚本生成或链接

优点：

- 架构更干净
- 更符合跨平台技能仓库思路

缺点：

- 改动较大
- 会影响现有 marketplace 流程

适合：

- 中长期统一技能规范

### 方案 C：单独拆出一个 Codex 兼容仓库

做法：

- 继续保留现仓库给 Claude
- 新建 `company-agent-skills` 之类的标准仓库

优点：

- 职责清晰
- 平台边界最干净

缺点：

- 仓库变多
- skill 同步机制必须做好

适合：

- 组织内已经明确要做多平台技能分发

## 8. 推荐采用的实际方案

建议先采用 **方案 A**：

- 风险最低
- 不破坏现有 Claude marketplace
- 最容易验证效果

具体建议：

1. 保持现有 `plugins/*/skills/*` 不动
2. 新增 `skills/*` 导出目录
3. 新增导出脚本
4. 在 CI 中校验导出结果是否最新
5. 等兼容稳定后，再决定是否升级到方案 B

## 9. Codex 导出时的规范化规则

导出到 `skills/*` 时建议做以下规则：

### 9.1 frontmatter 只保留公共字段

保留：

- `name`
- `description`

删除或忽略：

- `allowed-tools`
- 其他平台专有字段

### 9.2 保留资源目录

原样复制：

- `scripts/`
- `references/`
- `assets/`

### 9.3 文案做轻量去平台化

对以下内容进行替换或审查：

- `.claude/skills/`
- Claude Code
- `/plugin ...`
- 仅适用于 Claude marketplace 的安装说明

保留真正的技能工作流，不保留特定平台安装方法。

### 9.4 命名规则统一

要求 skill 目录名与 frontmatter `name` 一致：

```text
skills/<name>/SKILL.md
```

名称建议限制为：

- 小写
- 数字
- 连字符

## 10. 推荐增加的 CI 校验

建议新增以下检查：

1. 所有导出的 `skills/*/SKILL.md` 都存在
2. 所有导出 skill 都有合法 frontmatter
3. `name` 与目录名一致
4. 不包含禁止字段或警告字段
5. 不包含明显平台绑定安装说明
6. 导出结果与源内容同步，没有过期

## 11. 迁移步骤建议

### 第一阶段：验证

1. 选 2 到 3 个 skill 试点导出
2. 用 Codex 本地目录手工安装验证
3. 检查触发效果和资源加载效果

推荐先选：

- `send-email`
- `playwright-cli`
- `skill-writer`

### 第二阶段：批量导出

1. 编写统一导出脚本
2. 导出全部 skill 到 `skills/`
3. 补 CI 校验

### 第三阶段：规范收敛

1. 收敛 frontmatter 规范
2. 清理 Claude 专有文案
3. 评估是否让 `skills/*` 成为唯一真源

## 12. 风险与注意事项

### 12.1 不要直接删除 Claude 包装层

当前仓库已经服务 Claude marketplace，直接改根结构会破坏既有流程。

### 12.2 不要把 Claude 插件元数据混进跨平台主格式

像下面这些应视为平台包装，而不是 skill 主内容：

- `plugin.json`
- `marketplace.json`
- marketplace 安装说明

### 12.3 部分 skill 需要人工审查

不是所有 skill 都能 100% 自动导出，尤其是：

- 明确写死 Claude 工具权限的
- 明确写死 Claude 路径的
- 明确依赖 Claude 专属工作流命令的

这些 skill 在导出时应进入人工复核列表。

## 13. 最终建议

最终建议如下：

- 这个仓库不要直接当 Codex 技能仓库使用
- 这个仓库很适合继续做 Claude marketplace 仓库
- 其内部 skill 本体值得复用
- 最优改造方式是增加一个标准化导出层 `skills/`
- 短期采用“Claude 源仓库 + Codex 导出层”
- 中长期再评估是否升级为“标准 skill 为源，平台包装为生成物”

这条路线最稳，也最符合后续多平台兼容的方向。

