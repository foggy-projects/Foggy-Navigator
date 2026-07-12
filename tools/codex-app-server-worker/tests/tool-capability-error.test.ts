import assert from 'node:assert/strict'
import test from 'node:test'
import { detectToolCapabilityFailure } from '../src/app-server/event-bridge.js'

test('detects the observed shell capability-loss refusal without matching ordinary tool discussion', () => {
  assert.equal(detectToolCapabilityFailure(
    '当前会话出现真实环境阻塞：Shell/文件操作工具 `functions.exec` 在中断后不再可用，目前仅暴露了图片生成工具，因此无法继续运行 packaged restart。',
  ), 'APP_SERVER_TOOL_CAPABILITY_UNAVAILABLE')
  assert.equal(detectToolCapabilityFailure(
    'The functions.exec shell tool is no longer available, so I cannot continue modifying files.',
  ), 'APP_SERVER_TOOL_CAPABILITY_UNAVAILABLE')
  assert.equal(detectToolCapabilityFailure(
    'I reviewed how the shell tool behaves and the task is complete.',
  ), undefined)
  assert.equal(detectToolCapabilityFailure(
    '图片生成工具适合生成位图，但这个任务不需要它。',
  ), undefined)
})
