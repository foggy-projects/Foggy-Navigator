"""Prompt construction helpers for the LLM skill agent."""

from __future__ import annotations

import datetime
import json
from typing import Any

from ..models import SkillManifest
from .attachment_context import build_attachment_context_prompt as _build_attachment_context_prompt
from .llm_tool_call_codec import _safe_content


_SYSTEM_ROOT_SKILL_ID = "system.root"
_PRIVATE_CONTEXT_KEYS = {
    "clientappid",
    "contextid",
    "conversationid",
    "credentialid",
    "llmconfig",
    "messageid",
    "navigatorsessionid",
    "rootagentid",
    "sessionid",
    "taskscopedtoken",
    "upstreamref",
    "upstreamuserid",
    "visionllmconfig",
    "workertoken",
}
_PRIVATE_CONTEXT_MARKERS = (
    "apikey",
    "accesskey",
    "accesstoken",
    "authorization",
    "businessskillid",
    "businessskillname",
    "credential",
    "password",
    "secret",
    "token",
)
_MODEL_VISIBLE_SKILL_KEYS = {"id", "name", "description"}
_SYSTEM_ROOT_IDENTITY_KEYS = {
    "businessskillid",
    "businessskillname",
    "childskillid",
    "rootskillid",
    "rootskillname",
    "skillid",
    "skillname",
}


def _build_system_prompt(
    manifest: SkillManifest,
    account_context_prompt: str = "",
    *,
    skill_input: dict[str, Any] | None = None,
    skill_id: str | None = None,
    runtime_context: dict[str, Any] | None = None,
) -> str:
    prompt = _build_system_identity_prompt(manifest, runtime_context)
    if account_context_prompt:
        prompt += f"\n---\n{account_context_prompt}\n---\n\n"
    if manifest.markdown_body:
        prompt += f"\n---\n技能说明:\n{manifest.markdown_body}\n---\n\n"

    prompt += (
        "业务函数可能以 `function_id@version` 的形式展示；调用 "
        "invoke_business_function 时，`function_id` 只传不带 @version 的函数 id，"
        "`version` 单独传入。"
        "Navigator 运行时标识，例如 skillId、functionId、frameId、"
        "skillFrameId、function_frame_id、taskId、sessionId、messageId，"
        "以及 frm_、lgt_、msg_、sess_ 前缀的值，都是内部追踪 id。"
        "只有在分析执行历史或用户明确要求排查/调试标识时才使用这些 id；"
        "正常面向用户的总结中不要暴露这些 id，也不要把它们当作订单号、"
        "运单号、业务单据号，或当作正式业务单已创建的证明。"
        "只有当工具输出 schema 或结果中明确提供 orderNo、orderIdentifier、"
        "waybillNo 等公开业务标识字段时，才能称其为订单/运单/业务 id。"
        "对于打开页面类结构化输出，优先使用工具返回的 summary 和 action label；"
        "不要根据页面 action、按钮、Navigator frame id、skill id 或 action metadata "
        "推断业务成功或业务 id。"
        "如果技能引用其 bundle 内的文件，使用 list_skill_resources 或 "
        "read_skill_resource；这些工具只会暴露当前 ClientApp 的公开技能资源。"
        "如果已提供 list_files、read_file、write_file 或 patch_file，可在当前账号/"
        "工作目录文件作用域内读取或维护文件；优先 list_files/read_file 观察现状，"
        "修改现有文件优先使用 patch_file，整文件创建或覆盖才使用 write_file。"
        "如果运行时提供了 delegated workspace，文件工具的 relative_path 以 delegated "
        "workspace 根目录为基准；用户要求写入 `actors/pm/example.txt` 时，"
        "write_file 的 relative_path 就传 `actors/pm/example.txt`。"
        "`agent/skills/.../assets` 只用于明确维护 Skill 资源，不用于普通任务产物或 smoke marker。"
        "不要臆造真实路径，不要访问未授权目录。"
        "Skill 是当前 frame 内的能力材料，使用 invoke_business_skill 不会打开 child frame。"
        "普通业务领域请求默认先用 invoke_business_skill 加载 Skill 材料，并在当前 frame "
        "继续推理和调用业务函数；不要仅因为 bundle 名称包含 agent 就调用 "
        "invoke_business_agent。只有用户明确要求子 Agent/独立代理，或任务确实需要与当前 "
        "frame 隔离的独立生命周期、独立报告、长任务等待或多层委派时，才使用 "
        "invoke_business_agent 打开 Agent frame。"
    )
    child_agent_contract = _build_sub_agent_base_contract_prompt(runtime_context)
    if child_agent_contract:
        prompt += child_agent_contract
    prompt += _build_completion_contract_prompt(skill_id or manifest.id, runtime_context)
    runtime_prompt = _build_runtime_system_context_prompt(
        skill_input or {},
        skill_id or manifest.id,
        runtime_context,
    )
    if runtime_prompt:
        prompt += f"\n\n---\n{runtime_prompt}\n---\n"
    return prompt


def _build_system_identity_prompt(
    manifest: SkillManifest,
    runtime_context: dict[str, Any] | None = None,
) -> str:
    if manifest.id == _SYSTEM_ROOT_SKILL_ID:
        description = (
            "当前业务会话的根编排 Agent。负责处理当前用户回合，必要时调用业务工具，"
            "并向用户返回简洁结果。"
        )
        return (
            "你是当前业务会话的根编排 Agent。\n"
            f"职责说明: {description}\n"
            f"输出 schema: {json.dumps(manifest.output_schema, ensure_ascii=False)}\n"
        )
    if runtime_context and runtime_context.get("_agent_frame") is True:
        agent_id = runtime_context.get("_agent_id") or manifest.id
        return (
            f"你正在执行被委派的业务 Agent `{agent_id}`。\n"
            f"职责说明: {manifest.description}\n"
            f"输出 schema: {json.dumps(manifest.output_schema, ensure_ascii=False)}\n"
        )
    return (
        f"你正在执行业务技能 {manifest.id}。\n"
        f"职责说明: {manifest.description}\n"
        f"输出 schema: {json.dumps(manifest.output_schema, ensure_ascii=False)}\n"
    )


def _build_sub_agent_base_contract_prompt(runtime_context: dict[str, Any] | None) -> str:
    if not (runtime_context or {}).get("_agent_frame"):
        return ""
    return (
        "子 Agent 默认工作方式: "
        "你是被委派的子 Agent，只处理父级交给你的任务。"
        "你不会默认看到 Root 完整历史、Root 可用业务技能目录或 parent raw tool chain；"
        "以上下文中的 handoff instruction、附件、refs 和必要摘要为准。"
        "如需业务 Skill，先在当前 Agent frame 内调用 list_skill_resources 查看可见技能，"
        "再调用 read_skill_resource 或 invoke_business_skill 读取 Skill 材料。"
        "invoke_business_skill 只在当前 Agent frame 内加载材料，不会创建新的 frame。"
        "只有任务确实需要更深层独立生命周期，或用户明确要求子 Agent 时，才继续调用 "
        "invoke_business_agent。完成、等待用户补充或交还父级时，优先调用 "
        "submit_frame_result 或 handoff_to_parent 提交结构化状态、refs 和退出意图。"
    )


def _build_completion_contract_prompt(
    skill_id: str,
    runtime_context: dict[str, Any] | None,
) -> str:
    if (runtime_context or {}).get("_persistent_frame") is True or skill_id == _SYSTEM_ROOT_SKILL_ID:
        return (
            "只能使用已提供的工具。当前是根会话回合：如果可以直接回答用户，"
            "可以直接输出自然语言作为本回合最终答复。普通寒暄、简单问答、"
            "无需保留结构化状态的答复，不要调用 submit_frame_result。若需要保存结构化状态、"
            "active_plan、artifact_refs 或 evidence_refs，才主动调用 submit_frame_result "
            "提交本回合结果。普通业务技能请求默认加载 Skill 材料并在 Root 当前上下文继续；"
            "不要为了普通业务路由打开子 Agent frame。"
        )
    return (
        "只能使用已提供的工具。当前是子 Agent frame：完成、等待用户补充或需要返回父级时，"
        "优先主动调用 submit_frame_result 或 handoff_to_parent，以便提交结构化状态。"
        "如果只是自然语言完成或追问用户，也可以直接输出最终消息，运行时会将其归一化为子 Agent 结果。"
    )


def _build_user_prompt(
    prompt: str,
    skill_input: dict[str, Any],
    skill_id: str,
    runtime_context: dict[str, Any] | None = None,
) -> str:
    time_prompt = _build_user_request_time_prompt(runtime_context)
    if time_prompt:
        return f"{prompt}\n\n---\n{time_prompt}"
    return prompt


def _build_runtime_system_context_prompt(
    skill_input: dict[str, Any],
    skill_id: str,
    runtime_context: dict[str, Any] | None = None,
) -> str:
    business_context = _model_visible_business_context(skill_input, runtime_context)
    parts = [
        _build_turn_header(skill_id, runtime_context),
        _build_runtime_date_context_prompt(runtime_context),
        _build_recoverable_interruption_prompt(runtime_context),
        _build_awaiting_user_input_prompt(runtime_context),
        _build_nested_child_completed_prompt(runtime_context),
        _build_active_plan_prompt(runtime_context),
        _build_root_planning_policy_prompt(runtime_context, skill_id),
        _build_child_handoff_policy_prompt(runtime_context, skill_id),
        _build_frame_result_contract_prompt(runtime_context),
        _build_delegated_workspace_file_contract_prompt(runtime_context),
        _build_model_visible_skill_input_prompt(business_context),
        _build_attachment_context_prompt(_runtime_attachments(runtime_context)),
        _build_visible_context_prompt(runtime_context),
    ]
    return "\n".join(part for part in parts if part)


def _build_turn_header(skill_id: str, runtime_context: dict[str, Any] | None = None) -> str:
    if (runtime_context or {}).get("_persistent_frame") is True or skill_id == _SYSTEM_ROOT_SKILL_ID:
        return "当前根回合上下文:"
    if (runtime_context or {}).get("_agent_frame") is True:
        return "当前业务 Agent frame 上下文:"
    return "当前业务技能回合上下文:"


def _model_visible_business_context(
    skill_input: dict[str, Any],
    runtime_context: dict[str, Any] | None,
) -> dict[str, Any]:
    if runtime_context:
        override = runtime_context.get("_model_visible_business_context")
        if isinstance(override, dict):
            return override
    return skill_input


def _build_model_visible_skill_input_prompt(skill_input: dict[str, Any]) -> str:
    visible = model_visible_context(skill_input)
    if not visible:
        return ""
    if not isinstance(visible, dict):
        return f"业务上下文: {json.dumps(visible, ensure_ascii=False, sort_keys=True)}"

    business_context = dict(visible)
    allowed_skills = business_context.pop("allowed_skills", None)
    parts = [_build_allowed_skills_prompt(allowed_skills)]
    if business_context:
        parts.append(f"业务上下文: {json.dumps(business_context, ensure_ascii=False, sort_keys=True)}")
    return "\n".join(part for part in parts if part)


def _build_allowed_skills_prompt(allowed_skills: Any) -> str:
    if not isinstance(allowed_skills, list):
        return ""
    lines = ["可用业务技能:"]
    for item in allowed_skills:
        if not isinstance(item, dict):
            continue
        skill_id = str(item.get("id") or "").strip()
        if not skill_id:
            continue
        name = str(item.get("name") or "").strip()
        description = str(item.get("description") or "").strip()
        label = f"`{skill_id}`"
        if name and name != skill_id:
            label += f"（{name}）"
        if description:
            lines.append(f"- {label}: {description}")
        else:
            lines.append(f"- {label}")
    return "\n".join(lines) if len(lines) > 1 else ""


def model_visible_context(value: Any) -> Any:
    """Return the model-visible projection of runtime context-like data."""
    if isinstance(value, dict):
        projected: dict[str, Any] = {}
        for key, item in value.items():
            key_text = str(key)
            if _is_private_context_key(key_text):
                continue
            if _is_system_root_identity(key_text, item):
                continue
            if key in {"recentConversation", "recent_conversation", "attachments"}:
                continue
            if key == "allowed_skills" and isinstance(item, list):
                allowed_skills = _visible_allowed_skills(item)
                if allowed_skills:
                    projected[key] = allowed_skills
                continue
            projected_item = model_visible_context(item)
            if projected_item not in (None, {}, []):
                projected[str(key)] = projected_item
        return projected
    if isinstance(value, list):
        projected_list = []
        for item in value:
            projected_item = model_visible_context(item)
            if projected_item not in (None, {}, []):
                projected_list.append(projected_item)
        return projected_list
    return value


def _visible_allowed_skills(skills: list[Any]) -> list[dict[str, Any]]:
    visible: list[dict[str, Any]] = []
    for item in skills:
        if not isinstance(item, dict):
            continue
        if item.get("id") == _SYSTEM_ROOT_SKILL_ID:
            continue
        skill = {
            key: item[key]
            for key in _MODEL_VISIBLE_SKILL_KEYS
            if isinstance(item.get(key), str) and item.get(key).strip()
        }
        if skill.get("id"):
            visible.append(skill)
    return visible


def _is_private_context_key(key: str) -> bool:
    if key.startswith("_"):
        return True
    normalized = "".join(char for char in key.lower() if char.isalnum())
    if normalized in _PRIVATE_CONTEXT_KEYS:
        return True
    return any(marker in normalized for marker in _PRIVATE_CONTEXT_MARKERS)


def _is_system_root_identity(key: str, value: Any) -> bool:
    normalized = "".join(char for char in key.lower() if char.isalnum())
    if normalized not in _SYSTEM_ROOT_IDENTITY_KEYS:
        return False
    if isinstance(value, str):
        return value.strip().lower() == _SYSTEM_ROOT_SKILL_ID
    return False


def _runtime_attachments(runtime_context: dict[str, Any] | None) -> list[dict[str, Any]] | None:
    if not runtime_context:
        return None
    value = runtime_context.get("attachments")
    return value if isinstance(value, list) else None


def _recoverable_interruption_context(working_state: dict[str, Any]) -> dict[str, Any] | None:
    if working_state.get("continuation_state") != "INTERRUPTED":
        return None
    if not working_state.get("recoverable"):
        return None
    context = {
        "reason": working_state.get("interrupt_reason") or "unknown",
        "last_error": working_state.get("last_error") or "",
        "interrupted_at": working_state.get("interrupted_at") or "",
    }
    pending_child = working_state.get("pending_recoverable_child")
    if isinstance(pending_child, dict):
        context["pending_child_agent"] = model_visible_context(_safe_content(pending_child))
    recoverable_focus = working_state.get("recoverable_focus_summary")
    if isinstance(recoverable_focus, dict):
        context["recoverable_focus"] = model_visible_context(_safe_content(recoverable_focus))
    recoverable_focus_stack = working_state.get("recoverable_focus_stack")
    if isinstance(recoverable_focus_stack, list):
        context["recoverable_focus_stack"] = model_visible_context(_safe_content(recoverable_focus_stack))
    continuation_summary = _continuation_summary_context(working_state)
    if isinstance(continuation_summary, dict):
        context["continuation_summary"] = model_visible_context(_safe_content(continuation_summary))
    return context


def _continuation_summary_context(working_state: dict[str, Any]) -> dict[str, Any] | None:
    summary = working_state.get("latest_child_result_summary")
    if isinstance(summary, dict):
        return summary
    root_summary = working_state.get("root_context_summary")
    if not isinstance(root_summary, dict):
        return None
    summary = root_summary.get("latest_child_result_summary")
    if isinstance(summary, dict):
        return summary
    summaries = root_summary.get("child_result_summaries")
    if isinstance(summaries, list):
        for item in reversed(summaries):
            if isinstance(item, dict):
                return item
    return None


def _active_plan_context(working_state: dict[str, Any]) -> Any | None:
    active_plan = working_state.get("active_plan")
    if isinstance(active_plan, (dict, list)) and active_plan:
        return _safe_content(active_plan)
    return None


def _build_recoverable_interruption_prompt(runtime_context: dict[str, Any] | None) -> str:
    if not runtime_context:
        return ""
    interruption = runtime_context.get("_recoverable_interruption")
    if not isinstance(interruption, dict):
        return ""
    parts = [
        "上一次执行被中断。",
        f"原因: {interruption.get('reason') or 'unknown'}",
    ]
    last_error = interruption.get("last_error")
    if last_error:
        parts.append(f"上次错误: {last_error}")
    pending_child = interruption.get("pending_child_agent") or interruption.get("pending_child_skill")
    if isinstance(pending_child, dict):
        parts.append(
            "待恢复子 Agent: "
            f"{json.dumps(pending_child, ensure_ascii=False, sort_keys=True)}"
        )
    recoverable_focus = interruption.get("recoverable_focus")
    if isinstance(recoverable_focus, dict):
        parts.append(
            "可恢复焦点: "
            f"{json.dumps(recoverable_focus, ensure_ascii=False, sort_keys=True)}"
        )
    recoverable_focus_stack = interruption.get("recoverable_focus_stack")
    if isinstance(recoverable_focus_stack, list):
        parts.append(
            "可恢复焦点栈: "
            f"{json.dumps(recoverable_focus_stack, ensure_ascii=False, sort_keys=True)}"
        )
    continuation_summary = interruption.get("continuation_summary")
    if isinstance(continuation_summary, dict):
        parts.append(
            "来自子 Agent 提升结果的续跑摘要: "
            f"{json.dumps(continuation_summary, ensure_ascii=False, sort_keys=True)}"
        )
    parts.append("当前用户消息见下一条 human message。")
    parts.append(
        "中断工作只是可恢复候选，不是强制续跑。先判断 intent_resolution，"
        "取值为 CONTINUE_PREVIOUS、ABANDON_PREVIOUS、START_UNRELATED_NEW_TASK "
        "或 ASK_CLARIFICATION。若当前用户消息明确继续、纠正或补充中断工作，"
        "使用 CONTINUE_PREVIOUS 并从现有 frame 上下文继续。若存在待恢复子 Agent，"
        "使用 resume_recoverable_child_skill，使同一个子 frame 继续。若用户明确停止/"
        "取消，使用 ABANDON_PREVIOUS。若用户要求无关的新任务，使用 "
        "START_UNRELATED_NEW_TASK。对于任何搁置场景，先总结被放弃的内容，然后调用 "
        "shelve_interrupted_frame，decision 设置为 ABANDON_PREVIOUS 或 "
        "START_UNRELATED_NEW_TASK，同时包含 intent_resolution 和 "
        "abandoned_interruption 摘要。若意图不明确且中断工作涉及审批或业务副作用，"
        "使用 ASK_CLARIFICATION，并通过 submit_frame_result 向用户澄清。"
    )
    return "\n".join(parts)


def _build_awaiting_user_input_prompt(runtime_context: dict[str, Any] | None) -> str:
    if not runtime_context:
        return ""
    awaiting = runtime_context.get("_awaiting_user_input")
    if not isinstance(awaiting, dict):
        return ""
    parts = [
        "上一个子 Agent frame 正在等待用户输入。",
        "等待用户输入上下文:",
        json.dumps(model_visible_context(awaiting), ensure_ascii=False, sort_keys=True),
        "当前 human message 是用户对上次提示的回复。",
        (
            "规则: 继续同一个未完成 frame。除非当前用户回复明确取消或改变请求，"
            "否则把当前 human message 视为用户对上一次面向用户提示的回答。"
        ),
    ]
    return "\n".join(parts)


def _build_nested_child_completed_prompt(runtime_context: dict[str, Any] | None) -> str:
    if not runtime_context:
        return ""
    result = runtime_context.get("_nested_child_completed")
    if not isinstance(result, dict):
        return ""
    return "\n".join([
        "刚完成的子 Agent 提升结果:",
        json.dumps(model_visible_context(result), ensure_ascii=False, sort_keys=True),
        (
            "规则: 这是当前 frame 刚刚等待的子 Agent 结果。继续当前 frame 的业务决策，"
            "只有在该结果缺少必要字段且用户明确需要排障时，才读取执行报告。"
        ),
    ])


def _build_frame_result_contract_prompt(runtime_context: dict[str, Any] | None) -> str:
    return (
        "Frame 结果契约: 将 invoke_business_agent 或 resume_recoverable_child_skill "
        "返回的结果视为该子 Agent frame 的主要业务决策上下文，包括 status、next_step、"
        "missing_fields、structured_output、artifact_refs 和 evidence_refs。"
        "中断后注入的续跑摘要来自同一个被提升的子结果，应按普通工具结果一致理解。"
        "普通 invoke_business_skill 只返回当前 frame 可用的 Skill 材料，不代表 child frame。"
        "正常子 Agent 完成后，不要仅为了恢复这些字段而调用 read_frame_execution_report。"
        "只有当用户询问 frame 如何运行、需要调试/审计执行过程，或提升结果/续跑摘要缺少"
        "下一步业务决策所需字段时，才使用 read_frame_execution_report。"
    )


def _build_delegated_workspace_file_contract_prompt(runtime_context: dict[str, Any] | None) -> str:
    policy = (runtime_context or {}).get("execution_policy")
    if not isinstance(policy, dict) or not policy.get("workdir"):
        return ""
    workdir = str(policy.get("workdir") or "")
    normalized = workdir.replace("\\", "/").rstrip("/")
    actor_suffix = ""
    parts = [part for part in normalized.split("/") if part]
    if len(parts) >= 2 and parts[-2] == "actors":
        actor_suffix = f"actors/{parts[-1]}"
    return "\n".join([
        "Delegated workspace 文件契约:",
        "- list_files/read_file/write_file/patch_file 的 relative_path 以 delegated workspace 根目录为基准。",
        "- 如果任务要求在已绑定/当前/私有工作目录内创建文件，只传文件名或该根目录下的相对路径。",
        "- Skill 或账号上下文中的 private workspace（例如 `actors/pm/`）是逻辑说明，不一定是 file tool 前缀。",
        "- 不要因为上下文提到 private workspace 就自动给 relative_path 加 `actors/<role>/` 前缀。",
        (
            f"- 当前 delegated workspace 根目录已经是 `{actor_suffix}/`；写入该私有目录内文件时"
            f"不要再加 `{actor_suffix}/` 前缀。"
            if actor_suffix
            else "- 当用户明确给出 delegated workspace 根目录下的子路径时，才按该子路径写入。"
        ),
        "- `agent/skills/.../assets` 只用于明确编辑 Skill bundle 资源；不要把普通任务产物或 smoke marker 写到那里。",
    ])


def _build_active_plan_prompt(runtime_context: dict[str, Any] | None) -> str:
    if not runtime_context:
        return ""
    active_plan = runtime_context.get("_active_plan")
    if not isinstance(active_plan, (dict, list)):
        return ""
    return "\n".join([
        "当前活动任务计划:",
        json.dumps(active_plan, ensure_ascii=False, sort_keys=True),
        (
            "规则: 将 active_plan 视为当前持久根 frame 的工作计划。"
            "结束本回合前，将预期结果与该计划对照。若计划仍有用且需要继续保留，"
            "主动调用 submit_frame_result，并在 structured_output.active_plan 中保留或更新。"
            "若用户明确放弃该计划或开始无关任务，将 intent_resolution 设置为 "
            "ABANDON_PREVIOUS 或 START_UNRELATED_NEW_TASK，并总结被放弃的计划。"
        ),
    ])


def _build_root_planning_policy_prompt(
    runtime_context: dict[str, Any] | None,
    skill_id: str,
) -> str:
    if not runtime_context or runtime_context.get("_persistent_frame") is not True:
        return ""
    return (
        "持久根计划策略: 对复杂、多意图、多技能或需要外部协同的工作，"
        "需要跨回合保留计划时，主动调用 submit_frame_result 并在 "
        "structured_output.active_plan 中维护 active_plan。"
        "计划应简洁、结构化，并随工作推进更新；这是未来回合使用的工作状态，"
        "不是面向用户的叙述。"
    )


def _build_child_handoff_policy_prompt(
    runtime_context: dict[str, Any] | None,
    skill_id: str,
) -> str:
    if skill_id == _SYSTEM_ROOT_SKILL_ID:
        return ""
    if runtime_context and runtime_context.get("_persistent_frame") is True:
        return ""
    return (
        "子 Agent 退出策略: 如果当前用户消息明确表示取消、停止当前任务、换个问题、"
        "回到主对话，或当前请求明显不属于本 Agent 职责，不要继续要求用户补齐原任务参数。"
        "应调用 handoff_to_parent 受控交还父级。"
        "仅确认取消或回到主对话时，将 requires_parent_synthesis 设为 false；"
        "用户提出无关新任务、需要父级重新判断，或你无法独立处理时，将 "
        "requires_parent_synthesis 设为 true，并在 parent_instruction 中写清父级需要处理的事项。"
        "如果用户只是在补充、纠正或继续当前任务，则不要退出，继续当前 frame。"
    )


def _build_visible_context_prompt(runtime_context: dict[str, Any] | None) -> str:
    if not runtime_context:
        return ""
    summary = runtime_context.get("_visible_root_context_summary")
    if not isinstance(summary, dict):
        return ""
    return (
        "可见父级/根上下文摘要:\n"
        f"{json.dumps(model_visible_context(summary), ensure_ascii=False, sort_keys=True)}"
    )


def _build_runtime_date_context_prompt(runtime_context: dict[str, Any] | None) -> str:
    time_context = _runtime_time_context(runtime_context)
    return (
        "运行时日期上下文:\n"
        f"- 时区: {time_context['timezone']}\n"
        f"- 业务日期: {time_context['business_date']}\n"
        f"- 当前月份范围: [{time_context['current_month_start']}, {time_context['next_month_start']})\n"
        "规则: 使用该运行时上下文解析“本月、今天、昨日、近7天”等相对日期。"
    )


def _build_user_request_time_prompt(runtime_context: dict[str, Any] | None) -> str:
    if not _has_explicit_request_time(runtime_context):
        return ""
    time_context = _runtime_time_context(runtime_context)
    return (
        "当前请求时间:\n"
        f"- 当前时间: {time_context['current_time']}\n"
        f"- 时区: {time_context['timezone']}"
    )


def _has_explicit_request_time(runtime_context: dict[str, Any] | None) -> bool:
    if not runtime_context:
        return False
    return bool(_runtime_context_str(runtime_context, "current_time", "currentTime"))


def _build_runtime_time_context_prompt(runtime_context: dict[str, Any] | None) -> str:
    return _build_runtime_date_context_prompt(runtime_context)


def _runtime_time_context(runtime_context: dict[str, Any] | None) -> dict[str, str]:
    context = runtime_context or {}
    current_time = _runtime_context_str(context, "current_time", "currentTime")
    timezone_name = _runtime_context_str(context, "timezone", "timeZone", "tz") or _local_timezone_name()

    if current_time:
        now = _parse_runtime_datetime(current_time) or datetime.datetime.now().astimezone()
    else:
        now = datetime.datetime.now().astimezone()
        current_time = now.isoformat()

    business_date = _runtime_context_str(context, "business_date", "businessDate")
    if not business_date:
        business_date = now.date().isoformat()

    business_day = _parse_runtime_date(business_date) or now.date()
    current_month_start, next_month_start = _month_range_for(business_day)

    return {
        "current_time": current_time,
        "timezone": timezone_name,
        "business_date": business_day.isoformat(),
        "current_month_start": current_month_start.isoformat(),
        "next_month_start": next_month_start.isoformat(),
    }


def _runtime_context_str(context: dict[str, Any], *keys: str) -> str | None:
    for key in keys:
        value = context.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return None


def _local_timezone_name() -> str:
    tzinfo = datetime.datetime.now().astimezone().tzinfo
    if tzinfo is None:
        return "local"
    return getattr(tzinfo, "key", None) or tzinfo.tzname(None) or str(tzinfo)


def _parse_runtime_datetime(value: str) -> datetime.datetime | None:
    try:
        normalized = value[:-1] + "+00:00" if value.endswith("Z") else value
        return datetime.datetime.fromisoformat(normalized)
    except ValueError:
        return None


def _parse_runtime_date(value: str) -> datetime.date | None:
    try:
        return datetime.date.fromisoformat(value[:10])
    except ValueError:
        return None


def _month_range_for(day: datetime.date) -> tuple[datetime.date, datetime.date]:
    current_month_start = day.replace(day=1)
    if current_month_start.month == 12:
        next_month_start = current_month_start.replace(
            year=current_month_start.year + 1,
            month=1,
        )
    else:
        next_month_start = current_month_start.replace(month=current_month_start.month + 1)
    return current_month_start, next_month_start
