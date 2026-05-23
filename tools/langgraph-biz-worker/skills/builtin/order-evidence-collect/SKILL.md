---
name: order_evidence_collect
description: 收集订单相关证据（Mock 版本）
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
      - order_details
      - vehicle_details
    properties:
      order_details:
        type: object
      vehicle_details:
        type: object
  promote-to-parent:
    - result_summary
    - structured_output
    - evidence_refs
  business-rules: {}
  subgraph: order_evidence_collect
allowed-tools: mock_get_order mock_get_vehicle_status submit_skill_result
---

# 订单取证

## When to use
Use this skill when the parent skill needs order and vehicle evidence collected.

## Instructions
1. Fetch order details via `mock_get_order`.
2. Fetch vehicle status via `mock_get_vehicle_status`.
3. Assemble structured evidence.
4. Return the assembled evidence. If this run needs structured output or is inside an Agent frame, call `submit_skill_result`; otherwise a root/top-level caller may finish with a natural-language answer.
