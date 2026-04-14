---
name: address_verify
description: 验证订单地址可达性（Mock 版本，用于三层嵌套测试）
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
      - reachable
      - address
    properties:
      reachable:
        type: boolean
      address:
        type: string
  promote-to-parent:
    - result_summary
    - structured_output
  business-rules: {}
  subgraph: address_verify
allowed-tools: submit_skill_result
---

# 地址验证

## When to use
Use this skill when the parent skill needs to verify the delivery address reachability.

## Instructions
1. Look up the order address.
2. Check if it is reachable.
3. Call `submit_skill_result` with the result.
