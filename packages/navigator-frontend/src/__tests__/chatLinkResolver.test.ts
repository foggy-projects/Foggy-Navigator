import { describe, expect, it, vi } from 'vitest'

import type { FileSearchResponse } from '@/api/fileBrowser'
import { resolveChatLinkTarget } from '@/utils/chatLinkResolver'

const origin = 'http://dev-kvm-jdk17.foggysource.com'
const directoryId = 'dir-1'
const workerId = 'worker-1'
const directoryRoot = 'D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev'

function makeSearchResponse(relativePaths: string[]): FileSearchResponse {
  return {
    query: '',
    total: relativePaths.length,
    results: relativePaths.map(relativePath => ({
      name: relativePath.split('/').pop() || relativePath,
      relative_path: relativePath,
      size: 1,
      modified: '2026-04-06T00:00:00',
      type: 'file',
    })),
  }
}

describe('resolveChatLinkTarget', () => {
  it('opens a unique workspace file for basename markdown links', async () => {
    const searchFiles = vi.fn().mockResolvedValue(
      makeSearchResponse(['docs/version-tracker/1.0.0-SNAPSHOT/12-acceptance-signoff.md']),
    )

    const result = await resolveChatLinkTarget({
      href: '12-acceptance-signoff.md',
      text: '12-acceptance-signoff.md',
      origin,
      directoryId,
      workerId,
      directoryRoot,
      searchFiles,
    })

    expect(searchFiles).toHaveBeenCalledWith(directoryId, '12-acceptance-signoff.md', 80)
    expect(result).toEqual({
      kind: 'open',
      url: `${origin}/#/files?directoryId=${directoryId}&workerId=${workerId}&filePath=docs%2Fversion-tracker%2F1.0.0-SNAPSHOT%2F12-acceptance-signoff.md`,
    })
  })

  it('prefers an exact suffix match for nested relative links', async () => {
    const searchFiles = vi.fn().mockResolvedValue(
      makeSearchResponse([
        'docs/version-tracker/1.0.0-SNAPSHOT/12-acceptance-signoff.md',
        'docs/archive/12-acceptance-signoff.md',
      ]),
    )

    const result = await resolveChatLinkTarget({
      href: './docs/version-tracker/1.0.0-SNAPSHOT/12-acceptance-signoff.md',
      text: 'signoff',
      origin,
      directoryId,
      workerId,
      directoryRoot,
      searchFiles,
    })

    expect(result).toEqual({
      kind: 'open',
      url: `${origin}/#/files?directoryId=${directoryId}&workerId=${workerId}&filePath=docs%2Fversion-tracker%2F1.0.0-SNAPSHOT%2F12-acceptance-signoff.md`,
    })
  })

  it('keeps absolute workspace paths working without search', async () => {
    const searchFiles = vi.fn()

    const result = await resolveChatLinkTarget({
      href: 'D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/docs/version-tracker/1.0.0-SNAPSHOT/12-acceptance-signoff.md',
      text: '',
      origin,
      directoryId,
      workerId,
      directoryRoot,
      searchFiles,
    })

    expect(searchFiles).not.toHaveBeenCalled()
    expect(result).toEqual({
      kind: 'open',
      url: `${origin}/#/files?directoryId=${directoryId}&workerId=${workerId}&filePath=docs%2Fversion-tracker%2F1.0.0-SNAPSHOT%2F12-acceptance-signoff.md`,
    })
  })

  it('preserves same-origin file browser deeplinks', async () => {
    const searchFiles = vi.fn()

    const result = await resolveChatLinkTarget({
      href: `${origin}/#/files?directoryId=${directoryId}&workerId=${workerId}&filePath=docs%2Fversion-tracker%2F1.0.0-SNAPSHOT%2F12-acceptance-signoff.md`,
      text: '',
      origin,
      directoryId,
      workerId,
      directoryRoot,
      searchFiles,
    })

    expect(searchFiles).not.toHaveBeenCalled()
    expect(result).toEqual({
      kind: 'open',
      url: `${origin}/#/files?directoryId=${directoryId}&workerId=${workerId}&filePath=docs%2Fversion-tracker%2F1.0.0-SNAPSHOT%2F12-acceptance-signoff.md`,
    })
  })

  it('warns when basename links are ambiguous', async () => {
    const searchFiles = vi.fn().mockResolvedValue(
      makeSearchResponse([
        'docs/version-tracker/1.0.0-SNAPSHOT/README.md',
        'packages/foggy-chat/README.md',
      ]),
    )

    const result = await resolveChatLinkTarget({
      href: 'README.md',
      text: 'README.md',
      origin,
      directoryId,
      workerId,
      directoryRoot,
      searchFiles,
    })

    expect(result).toEqual({
      kind: 'warn',
      message: '找到多个同名文件，无法自动定位，请补充更完整路径',
    })
  })

  it('preserves external http links', async () => {
    const searchFiles = vi.fn()

    const result = await resolveChatLinkTarget({
      href: 'https://example.com/docs',
      text: 'external',
      origin,
      directoryId,
      workerId,
      directoryRoot,
      searchFiles,
    })

    expect(searchFiles).not.toHaveBeenCalled()
    expect(result).toEqual({
      kind: 'open',
      url: 'https://example.com/docs',
    })
  })
})
