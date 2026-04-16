---
name: exception_triage
description: 分析异常并给出处置建议（Mock 版本）
compatibility: Designed for langgraph-biz-worker
metadata:
  owner: platform
  version: "1.0.0"
  domain: tms
  visibility: builtin
  input-schema:
    type: object
    properties:
      order_id:
        type: string
  output-schema:
    type: object
    required:
      - classification
      - recommended_action
      - confidence
    properties:
      classification:
        type: string
        enum: [vehicle_delay, weather, system_error, other]
      recommended_action:
        type: string
        enum: [manual_dispatch, auto_retry, escalate, ignore]
      confidence:
        type: number
        minimum: 0
        maximum: 1
  promote-to-parent:
    - result_summary
    - structured_output
    - evidence_refs
  business-rules:
    require_evidence: true
    min_evidence_count: 1
  subgraph: exception_triage
allowed-tools: mock_get_order mock_get_vehicle_status submit_skill_result
---

# 异常分诊

## When to use
Use this skill when the task involves analyzing an exception order and providing a structured handling recommendation.

## Instructions
1. Read order details first via `mock_get_order`.
2. Collect vehicle status evidence via `mock_get_vehicle_status`.
3. Classify the exception (vehicle_delay / weather / system_error / other).
4. Recommend an action (manual_dispatch / auto_retry / escalate / ignore).
5. Provide a confidence score between 0 and 1.
6. Do not guess without evidence.
7. When finished, call `submit_skill_result`.
