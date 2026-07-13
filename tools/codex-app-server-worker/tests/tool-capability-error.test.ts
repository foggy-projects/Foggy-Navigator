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
    '仍未恢复：当前会话依然只能调用图像生成工具，刚才的白图就是可用工具检查结果；shell、文件读取和写入工具仍未暴露。\n\n因此仓库尚未修改。需要重新开启带 workspace/terminal 工具的 Codex 会话后再继续执行。',
  ), 'APP_SERVER_TOOL_CAPABILITY_UNAVAILABLE')
  assert.equal(detectToolCapabilityFailure(
    '当前未能写入仓库：本会话只暴露了图像生成工具，没有 shell/文件编辑工具；未修改任何项目文件。恢复 shell/exec 能力后可直接继续。',
  ), 'APP_SERVER_TOOL_CAPABILITY_UNAVAILABLE')
  assert.equal(detectToolCapabilityFailure(
    '当前工具列表仍只有 `image_gen`，没有 shell 或文件编辑能力，无法操作 `/home/sa/workspace/tms-x3`。这次未修改仓库。需要重建/刷新为带终端工具的会话后才能继续。',
  ), 'APP_SERVER_TOOL_CAPABILITY_UNAVAILABLE')
  assert.equal(detectToolCapabilityFailure(
    'I reviewed how the shell tool behaves and the task is complete.',
  ), undefined)
  assert.equal(detectToolCapabilityFailure(
    '图片生成工具适合生成位图，但这个任务不需要它。',
  ), undefined)
  assert.equal(detectToolCapabilityFailure(
    '仓库尚未修改，因为需求还没有确认；确认后我会继续执行。',
  ), undefined)
})
