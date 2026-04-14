---
name: rule_check
description: 核验处置建议是否符合业务规则（Mock 版本）
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
      classification:
        type: string
      recommended_action:
        type: string
  output-schema:
    type: object
    required:
      - rule_passed
      - checked_rules
    properties:
      rule_passed:
        type: boolean
      checked_rules:
        type: array
      violations:
        type: array
  promote-to-parent:
    - result_summary
    - structured_output
  business-rules: {}
  subgraph: rule_check
allowed-tools: mock_search_incidents submit_skill_result
---

# 规则核验

## When to use
Use this skill when the parent skill needs to verify whether the recommended action complies with business rules.

## Instructions
1. Search for related incidents via `mock_search_incidents`.
2. Check each applicable rule against the classification and action.
3. Report which rules passed and which were violated.
4. When finished, call `submit_skill_result`.
