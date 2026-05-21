import { describe, expect, it } from 'vitest'
import {
  createFoggyMessageIngestor,
  extractNavigatorActions,
  renderBasicMarkdown,
  sessionMessagesToOpenMessages,
} from '../lib/normalizer'

describe('foggy-navigator-chat normalizer', () => {
  it('renders result actions and strips raw structured output', () => {
    const ingestor = createFoggyMessageIngestor()

    ingestor.ingestOpenMessages([
      {
        id: 'result-1',
        type: 'RESULT',
        content: JSON.stringify({
          summary: '已生成运单草稿',
          structured_output: {
            type: 'OPEN_TMS_PAGE',
            label: '打开草稿',
            payload: {
              page: 'draft',
              task_scoped_token: 'secret-token',
            },
          },
        }),
      },
    ], 'task-1', 'COMPLETED')

    const messages = ingestor.getMessages()
    expect(messages).toHaveLength(1)
    expect(messages[0]).toMatchObject({
      role: 'assistant',
      content: '已生成运单草稿',
    })
    expect(messages[0].actions?.[0]).toMatchObject({
      type: 'OPEN_TMS_PAGE',
      label: '打开草稿',
    })
    expect(messages[0].actions?.[0].payload?.task_scoped_token).toBe('[redacted]')
  })

  it('upserts tool call/result into one compact tool message with report digest', () => {
    const ingestor = createFoggyMessageIngestor({
      showToolCalls: true,
      showToolResults: true,
    })

    ingestor.ingestOpenMessages([
      {
        id: 'call-1',
        type: 'TOOL_CALL',
        payload: {
          toolCallId: 'tool-1',
          toolName: 'invoke_business_function',
          functionId: 'waybill.search',
        },
        timestamp: 1000,
      },
      {
        id: 'result-1',
        type: 'TOOL_RESULT',
        payload: {
          toolCallId: 'tool-1',
          toolName: 'invoke_business_function',
          functionId: 'waybill.search',
          success: true,
          durationMs: 1200,
          executionReportDigest: {
            reportRef: 'report-1',
            summary: '查询到 3 条',
          },
        },
        timestamp: 2200,
      },
    ], 'task-1', 'WORKING')

    const messages = ingestor.getMessages()
    expect(messages).toHaveLength(1)
    expect(messages[0].toolExecution).toMatchObject({
      toolCallId: 'tool-1',
      functionId: 'waybill.search',
      status: 'success',
      durationMs: 1200,
      executionReportRef: 'report-1',
    })
    expect(messages[0].toolExecution?.executionReportDigest?.summary).toBe('查询到 3 条')
  })

  it('sanitizes technical error details in business mode', () => {
    const ingestor = createFoggyMessageIngestor()

    ingestor.ingestOpenMessages([
      {
        id: 'err-1',
        type: 'ERROR',
        content: 'stack trace contains task_scoped_token=abc',
      },
    ], 'task-1', 'FAILED')

    expect(ingestor.getMessages()[0]).toMatchObject({
      role: 'system',
      content: '操作执行失败，请稍后重试或联系管理员。',
      error: '操作执行失败，请稍后重试或联系管理员。',
    })
  })

  it('maps session history messages back to OpenAPI message types', () => {
    expect(sessionMessagesToOpenMessages([
      { id: 'm1', role: 'USER', content: 'hello' },
      { id: 'm2', type: 'TOOL_CALL_RESULT', content: 'ok' },
    ])).toMatchObject([
      { id: 'm1', type: 'USER' },
      { id: 'm2', type: 'TOOL_RESULT' },
    ])
  })

  it('extracts nested actions and escapes basic markdown', () => {
    const actions = extractNavigatorActions({
      type: 'RESULT',
      content: JSON.stringify({
        result: {
          structured_output: {
            type: 'OPEN_TMS_PAGE',
            pageLabel: '订单',
            payload: { id: 'o1' },
          },
        },
      }),
    })

    expect(actions[0]).toMatchObject({
      type: 'OPEN_TMS_PAGE',
      label: '打开订单',
    })
    expect(renderBasicMarkdown('**ok** <script>x</script>')).toContain('&lt;script&gt;')
  })
})
