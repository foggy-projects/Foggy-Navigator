---
name: company-skill-marketplace
description: "公司内部 Skill 插件市场管理。发布 skill 到公司市场、查看已有插件、更新 marketplace。当用户提到'发布skill'、'插件市场'、'publish skill'、'marketplace'、或使用 /marketplace 时触发。"
---

# 公司 Skill 插件市场

公司内部 Skill 共享市场，基于 GitLab 仓库托管。

## 市场信息

- **仓库地址**: `{{MARKETPLACE_URL}}`
- **本地配置**: `~/.claude/settings.json` 中的 `company-skill-marketplace` 条目
- **加载方式**: Claude Code 启动时自动 clone 仓库并加载其中的 skills

## 发布 Skill 到市场

### 1. Clone 仓库并创建分支

```bash
cd /tmp && git clone {{MARKETPLACE_URL}} && cd company-skill-marketplace
git checkout -b feat/add-<skill-name>
```

### 2. 创建插件目录结构

```
plugins/<skill-name>/
├── .claude-plugin/
│   └── plugin.json              # 插件元数据
└── skills/<skill-name>/
    ├── SKILL.md                 # 技能指令（必需）
    ├── scripts/                 # 可执行脚本（可选）
    └── references/              # 参考文档（可选）
```

### 3. 编写 plugin.json

```json
{
  "name": "<skill-name>",
  "version": "1.0.0",
  "description": "一句话描述",
  "author": { "name": "作者名", "email": "email" },
  "license": "UNLICENSED",
  "keywords": ["关键词"]
}
```

### 4. 注册到 marketplace.json

编辑 `.claude-plugin/marketplace.json`，在 `plugins` 数组中添加新条目：

```json
{
  "name": "<skill-name>",
  "description": "一句话描述",
  "version": "1.0.0",
  "author": "作者名",
  "source": "plugins/<skill-name>",
  "category": "development|utility|testing|devops",
  "tags": ["tag1", "tag2"]
}
```

### 5. 提交并推送

```bash
git add plugins/<skill-name>/ .claude-plugin/marketplace.json
git commit -m "feat: add <skill-name> skill"
git push -u origin feat/add-<skill-name>
```

推送后根据 GitLab 返回的链接创建 Merge Request。

## 查看已有插件

Clone 仓库后读取 `.claude-plugin/marketplace.json` 的 `plugins` 数组。

## 注意事项

- SKILL.md 中**不要硬编码**任何密钥或密码
- 不要直接推送到 main 分支，必须通过 MR
- 合并后全员可通过重启 Claude Code 获取更新
