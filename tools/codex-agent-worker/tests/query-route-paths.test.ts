import test from 'node:test'
import assert from 'node:assert/strict'
import { isPathWithinAllowedCwd } from '../src/routes/query.ts'

test('isPathWithinAllowedCwd accepts exact and nested Windows paths', () => {
  assert.equal(isPathWithinAllowedCwd('D:\\repo', 'D:\\repo'), true)
  assert.equal(isPathWithinAllowedCwd('D:\\repo\\scenario-1', 'D:\\repo'), true)
  assert.equal(isPathWithinAllowedCwd('d:\\repo\\scenario-1', 'D:\\repo'), true)
})

test('isPathWithinAllowedCwd rejects Windows sibling prefix paths', () => {
  assert.equal(isPathWithinAllowedCwd('D:\\repo2', 'D:\\repo'), false)
  assert.equal(isPathWithinAllowedCwd('D:/repo-other/scenario-1', 'D:/repo'), false)
})

test('isPathWithinAllowedCwd accepts nested POSIX paths and rejects sibling prefixes', () => {
  assert.equal(isPathWithinAllowedCwd('/srv/repo/scenario-1', '/srv/repo'), true)
  assert.equal(isPathWithinAllowedCwd('/srv/repo2', '/srv/repo'), false)
})
