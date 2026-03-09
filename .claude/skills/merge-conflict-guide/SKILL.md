---
name: merge-conflict-guide
description: Git 合并冲突安全解决指南。当用户需要解决合并冲突、执行分支合并、或使用 /merge-conflict、/mc-guide 时使用。
---

# Git 合并冲突安全解决指南

指导 AI 在合并分支出现冲突时，按安全流程解决冲突，防止功能丢失。

## 使用场景

- 执行 `git merge` 产生冲突时
- 用户请求合并两个分支
- 用户报告合并后功能丢失，需要排查修复

## 事故案例（本技能诞生的教训）

一个 5000+ 行的 Vue 文件（ClaudeWorkerView.vue）在合并时冲突。解决时直接选了改动少的分支版本（1 个 commit），丢弃了改动多的分支（25 个 commit），导致 29 项功能全部丢失��

**根因：没有评估两边的改动量就选了一边。**

## 执行流程

### 阶段 1：冲突评估（必须先做）

1. 找到共同祖先：
   ```bash
   MERGE_BASE=$(git merge-base HEAD <other-branch>)
   ```

2. 分别统计两边对冲突文件的改动量：
   ```bash
   # ours 的改动
   git diff --stat $MERGE_BASE HEAD -- <conflicted-files>
   # theirs 的改动
   git diff --stat $MERGE_BASE <other-branch> -- <conflicted-files>
   ```

3. 向用户报告评估结果，格式：
   ```
   冲突文件: xxx.vue
   ├── ours (当前分支):  +850 -120, 涉及 25 个 commit
   └── theirs (目标分支): +6 -0, 涉及 1 个 commit
   → 建议：以 ours 为基准，手动补入 theirs 的 6 行改动
   ```

### 阶段 2：选择解决策略

根据评估结果选择策略：

| 场景 | 策略 |
|------|------|
| 一边改动远大于另一边 | **基准+补丁**：取改动多的版本为基准，手动补入改动少的部分 |
| 两边改动量相近，冲突面积小 | **逐段 resolve**：逐个冲突块判断保留哪边或合并 |
| 两边改动量相近，冲突面积大 | **三方对比**：用 merge-base 版本作参照，逐段合并两边改动 |
| 文件超过 1000 行且冲突严重 | **禁止 take ours/theirs**，必须用上述策略之一 |

### 阶段 3：执行解决

**基准+补丁策略（最常用）：**

1. 取改动多的分支版本作为基准：
   ```bash
   # 如果 ours 改动多
   git checkout --ours <file>
   # 如果 theirs 改动多
   git checkout --theirs <file>
   ```

2. 查看改动少的分支具体改了什么：
   ```bash
   git diff $MERGE_BASE <smaller-branch> -- <file>
   ```

3. 手动将这些改动补入基准版本

4. 标记冲突已解决：
   ```bash
   git add <file>
   ```

**逐段 resolve 策略：**

1. 打开冲突文件，逐个 `<<<<<<<` 块处理
2. 对每个冲突块，参考 merge-base 版本判断合并方式
3. 确保不遗漏任何一边的改动

### 阶段 4：验证（必须做）

1. **构建验证**：
   ```bash
   # 前端
   bash scripts/build-frontend.sh
   # 后端
   mvn compile test -pl launcher -am
   ```

2. **Diff 复查** — 确认两边改动都保留了：
   ```bash
   # 检查 ours 的改动是否保留
   git diff $MERGE_BASE HEAD -- <file> | head -50
   # 检查 theirs 的改动是否保留
   # （对比 theirs 的关键改动是否出现在最终版本中）
   ```

3. **功能抽查** — 对照两边的 git log 确认关键功能点未丢失：
   ```bash
   git log --oneline $MERGE_BASE..HEAD -- <file>
   git log --oneline $MERGE_BASE..<other-branch> -- <file>
   ```

## 约束条件

- **禁止对 1000+ 行文件直接 take ours/theirs**（除非另一边改动为 0）
- 冲突解决后必须运行构建验证
- 必须先评估改动量再选策略，不允许跳过阶段 1
- 如果评估发现一边有 10+ 个 commit 而另一边只有 1-2 个，必须用基准+补丁策略

## 决策规则

- 如果 `git merge` 无冲突 → 直接完成，跳到阶段 4 验证
- 如果冲突文件 < 200 行 → 可以逐段 resolve
- 如果冲突文件 > 1000 行且两边都有改动 → 必须先评估，用基准+补丁
- 如果用户说"功能丢失" → 用 `git diff merge-base..HEAD` 排查丢失内容
- 如果构建失败 → 检查是否遗漏了某一边的 import、类型定义或依赖
