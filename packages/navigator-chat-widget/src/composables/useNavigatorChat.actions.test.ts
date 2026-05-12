import { describe, expect, it } from 'vitest'
import { extractActions } from './useNavigatorChat'
import type { OpenTaskMessage } from '../types'

describe('extractActions', () => {
  it('extracts submit_skill_result structured_output from metadata args', () => {
    const message: OpenTaskMessage = {
      id: 'msg-1',
      type: 'TOOL_RESULT',
      content: JSON.stringify({ ok: true }),
      metadata: {
        toolName: 'submit_skill_result',
        args: {
          summary: '已生成开单草稿，可点击打开补齐并确认。',
          structured_output: {
            type: 'OPEN_TMS_PAGE',
            routeName: 'OrderWorkbench',
            query: { aiDraftId: 'afd_xxx' },
          },
        },
      },
    }

    const actions = extractActions(message)

    expect(actions).toHaveLength(1)
    expect(actions[0]).toMatchObject({
      type: 'OPEN_TMS_PAGE',
      label: '打开开单工作台',
    })
    expect(actions[0].payload).toEqual({
      type: 'OPEN_TMS_PAGE',
      routeName: 'OrderWorkbench',
      query: { aiDraftId: 'afd_xxx' },
    })
  })

  it('extracts camelCase structuredOutput nested under result', () => {
    const message: OpenTaskMessage = {
      id: 'msg-2',
      type: 'RESULT',
      content: '已生成开单草稿',
      result: {
        summary: '已生成开单草稿',
        structuredOutput: {
          type: 'OPEN_TMS_PAGE',
          label: '打开开单工作台',
          routeName: 'OrderWorkbench',
          query: { aiDraftId: 'afd_123' },
        },
      },
    }

    const actions = extractActions(message)

    expect(actions).toHaveLength(1)
    expect(actions[0]).toMatchObject({
      type: 'OPEN_TMS_PAGE',
      label: '打开开单工作台',
    })
    expect(actions[0].payload).toEqual({
      type: 'OPEN_TMS_PAGE',
      label: '打开开单工作台',
      routeName: 'OrderWorkbench',
      query: { aiDraftId: 'afd_123' },
    })
  })

  it('parses stringified structured output containers', () => {
    const message: OpenTaskMessage = {
      id: 'msg-3',
      type: 'TOOL_RESULT',
      metadata: {
        args: JSON.stringify({
          structured_output: {
            type: 'OPEN_TMS_PAGE',
            routeName: 'OrderWorkbench',
            query: { aiDraftId: 'afd_stringified' },
          },
        }),
      },
    }

    const actions = extractActions(message)

    expect(actions).toHaveLength(1)
    expect(actions[0].payload).toEqual({
      type: 'OPEN_TMS_PAGE',
      routeName: 'OrderWorkbench',
      query: { aiDraftId: 'afd_stringified' },
    })
  })

  it('deduplicates identical candidates without dropping different query payloads', () => {
    const first = {
      type: 'OPEN_TMS_PAGE',
      label: '打开开单工作台',
      routeName: 'OrderWorkbench',
      query: { aiDraftId: 'afd_1' },
    }
    const second = {
      type: 'OPEN_TMS_PAGE',
      label: '打开开单工作台',
      routeName: 'OrderWorkbench',
      query: { aiDraftId: 'afd_2' },
    }
    const message: OpenTaskMessage = {
      id: 'msg-4',
      type: 'RESULT',
      content: JSON.stringify({ structured_output: first }),
      metadata: {
        args: {
          structured_output: first,
          actions: [second],
        },
      },
    }

    const actions = extractActions(message)

    expect(actions).toHaveLength(2)
    expect(actions.map((action) => action.payload)).toEqual([first, second])
  })
})
