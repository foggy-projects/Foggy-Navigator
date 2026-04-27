import assert from 'node:assert/strict'
import test from 'node:test'
import { createConfig } from '../src/config.ts'

test('default Gemini pro aliases delegate model choice to Gemini CLI auto routing', () => {
  const config = createConfig({})

  assert.equal(config.defaultModel, 'auto-gemini-3')
  assert.equal(config.modelAliases['gemini-pro'], 'auto-gemini-3')
  assert.equal(config.modelAliases.pro, 'auto-gemini-3')
  assert.equal(config.modelAliases['latest-pro'], 'auto-gemini-3')
})
