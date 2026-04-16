# Skill 多层嵌套调用场景规划

## 文档作用

- doc_type: implementation-plan
- intended_for: worker-runtime + reviewer
- purpose: 在 31 号设计的 Frame 生命周期和调用栈语义（§12）基础上，规划 Skill 中调用 Skill 的通用嵌套机制

## 1. 现状分析

### 1.1 已实现

- Frame 状态机：7 状态 + 合法转换矩阵 ✅
- `SkillRuntime`：invoke_skill / mark_waiting_child / resume_from_child / submit_result / close_frame ✅
- `_invoke_child()` helper：exception_triage 中手动编排子 Skill 调用 ✅
- 子 Skill 结果回写父 Frame 的 `private_working_state["child_results"]` ✅
- E2E 测试验证两层嵌套（exception_triage → 2 个子 Skill）✅

### 1.2 当前问题

| 问题 | 说明 |
|------|------|
| **嵌套逻辑硬编码** | `_invoke_child()` 是 exception_triage 的私有 helper，不是 Runtime 提供的通用能力 |
| **子 Skill 编排写死** | 父 Skill 在 Python 代码里显式调用 `invoke_evidence_child()` + `invoke_rule_check_child()`，新 Skill 要嵌套就要抄一遍 |
| **缺少深度嵌套** | 当前只有 2 层（parent → child），未验证 3 层（parent → child → grandchild）|
| **缺少调用栈追踪** | 没有统一的调用栈视图，调试时只能逐个 Frame 看 parent_frame_id |
| **子 Skill 选择不由 LLM 决定** | 当前由代码决定调哪个子 Skill，未来应由 LLM 判断 |

## 2. 目标

1. 将 `_invoke_child` 提升为 **Runtime 级通用能力**
2. 支持 **任意深度嵌套**（通过 parent_frame_id 链条追踪）
3. 提供 **调用栈查询** API
4. 保持首版约束：**子 Skill 选择仍由代码/Manifest 声明决定**（LLM 选择延后）

## 3. 非目标

- LLM 动态选择子 Skill（属于设计文档 §15.3 `CALL_CHILD_SKILL` 信号的完整实现，延后）
- 子 Skill 并发执行（设计文档 §3 已排除）
- 跨 Worker 子 Skill 调用

## 4. 方案设计

### 4.1 Runtime 提升：`invoke_child_skill()`

将 `_invoke_child` 的核心逻辑提升到 `SkillRuntime` 中：

```python
class SkillRuntime:
    def invoke_child_skill(
        self,
        parent_frame_id: str,
        child_skill_id: str,
        child_input: dict[str, Any],
    ) -> str:
        """标准化子 Skill 调用。

        1. mark_waiting_child(parent)
        2. invoke_skill(child, parent_frame_id=parent)
        3. 返回 child_frame_id
        """

    def complete_child_and_resume_parent(
        self,
        child_frame_id: str,
    ) -> dict[str, Any]:
        """标准化子 Skill 完成后的收口。

        1. close_frame(child) → 获取 promoted result
        2. write_child_result_to_parent(parent, child, promoted)
        3. resume_from_child(parent)
        4. 返回 promoted result
        """
```

这样 Skill 子图代码只需：

```python
child_fid = runtime.invoke_child_skill(parent_fid, "order_evidence_collect", input)
# ... 执行子 Skill 逻辑 ...
promoted = runtime.complete_child_and_resume_parent(child_fid)
```

### 4.2 调用栈查询

新增 Runtime 方法：

```python
def get_call_stack(self, frame_id: str) -> list[SkillFrameState]:
    """从当前 Frame 沿 parent_frame_id 向上遍历，返回调用栈。
    
    栈顶是当前 Frame，栈底是根 Frame。
    """

def get_nesting_depth(self, frame_id: str) -> int:
    """返回当前 Frame 的嵌套深度（根 Frame = 0）。"""

def get_max_nesting_depth(self) -> int:
    """全局最大嵌套深度限制（默认 5，可配置）。"""
```

### 4.3 深度保护

新增配置项：

```python
class Settings:
    max_skill_nesting_depth: int = 5
```

`invoke_child_skill()` 中检查：

```python
if self.get_nesting_depth(parent_frame_id) >= self.max_nesting_depth:
    raise MaxNestingDepthExceeded(...)
```

### 4.4 Skill Manifest 声明子 Skill

在 SKILL.md 的 metadata 中新增可选字段：

```yaml
metadata:
  child-skills:
    - order_evidence_collect
    - rule_check
```

首版：仅作文档声明 + 路由提示。
后续：Runtime 校验子 Skill 调用是否在声明范围内。

### 4.5 SSE 事件增强

子 Skill Frame open/close 事件增加 `parent_frame_id` 字段：

```python
QueryEvent(
    type="skill_frame_open",
    skill_frame_id=child_frame_id,
    skill_id=child_skill_id,
    # 新增字段
    parent_frame_id=parent_frame_id,
    nesting_depth=depth,
)
```

便于前端渲染嵌套层级。

## 5. 验证场景

### 5.1 两层嵌套（现有，巩固）

```
root → exception_triage
         → order_evidence_collect
         → rule_check
```

已有 E2E 测试覆盖。改用 `invoke_child_skill()` 后回归验证。

### 5.2 三层嵌套（新增验证场景）

```
root → exception_triage
         → order_evidence_collect
              → address_verify（新 Mock Skill）
         → rule_check
```

新增一个 `address_verify` Mock Skill，作为 `order_evidence_collect` 的子 Skill。
验证：三层 Frame 链、调用栈查询、深度保护。

### 5.3 深度限制触发

配置 `max_skill_nesting_depth = 2`，触发三层嵌套，验证 `MaxNestingDepthExceeded` 异常和 Frame FAILED 处理。

## 6. 执行分步

### Step 1：Runtime 提升（不改现有行为）

| # | 任务 | 完成定义 |
|---|------|---------|
| 1 | `SkillRuntime` 新增 `invoke_child_skill()` 和 `complete_child_and_resume_parent()` | 新增单元测试通过 |
| 2 | 新增 `get_call_stack()` 和 `get_nesting_depth()` | 新增单元测试通过 |
| 3 | 新增 `max_skill_nesting_depth` 配置 + 深度检查 | 深度限制测试通过 |

### Step 2：迁移现有 Skill

| # | 任务 | 完成定义 |
|---|------|---------|
| 4 | `exception_triage.py` 改用 `runtime.invoke_child_skill()` 替代私有 `_invoke_child()` | 现有 145 个测试全部通过（零行为变更） |
| 5 | SKILL.md 补充 `child-skills` 声明 | SkillRegistry 能解析新字段 |

### Step 3：三层嵌套验证

| # | 任务 | 完成定义 |
|---|------|---------|
| 6 | 新增 `address_verify` Mock Skill（builtin + SKILL.md） | SkillRegistry 加载成功 |
| 7 | `order_evidence_collect` 调用 `address_verify` 作为子 Skill | 三层嵌套 E2E 测试通过 |
| 8 | 深度限制测试 | 超深度触发 FAILED 测试通过 |

### 执行顺序

```
Step 1 (1→2→3，串行)
  ↓
Step 2 (4+5 并行)
  ↓
Step 3 (6→7→8，串行)
  ↓
全量测试回归
```

## 7. 暂不做

- LLM 动态选择子 Skill（`decide_next_step` → `CALL_CHILD_SKILL` 信号）
- 子 Skill 并发执行
- Manifest `child-skills` 声明强校验
- 前端嵌套层级可视化
- QueryEvent 增加 `parent_frame_id` / `nesting_depth`（与前端协商后再加）
