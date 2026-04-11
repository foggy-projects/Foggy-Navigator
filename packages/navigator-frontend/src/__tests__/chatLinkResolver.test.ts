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

  it('extracts trailing line numbers from absolute workspace paths', async () => {
    const searchFiles = vi.fn()

    const result = await resolveChatLinkTarget({
      href: 'D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/docs/version-tracker/v3.0.0/P0-Step6-平台端页面联调-承接任务.md:77',
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
      url: `${origin}/#/files?directoryId=${directoryId}&workerId=${workerId}&filePath=docs%2Fversion-tracker%2Fv3.0.0%2FP0-Step6-%E5%B9%B3%E5%8F%B0%E7%AB%AF%E9%A1%B5%E9%9D%A2%E8%81%94%E8%B0%83-%E6%89%BF%E6%8E%A5%E4%BB%BB%E5%8A%A1.md&line=77`,
    })
  })

  it('normalizes leading-slash absolute workspace paths without falling back to search', async () => {
    const searchFiles = vi.fn()

    const result = await resolveChatLinkTarget({
      href: '/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/docs/v3.0.0/04-basic%E5%91%BD%E5%90%8D%E6%B2%BB%E7%90%86%E6%B8%85%E5%8D%95.md',
      text: '04-basic命名治理清单.md',
      origin,
      directoryId,
      workerId,
      directoryRoot,
      searchFiles,
    })

    expect(searchFiles).not.toHaveBeenCalled()
    expect(result).toEqual({
      kind: 'open',
      url: `${origin}/#/files?directoryId=${directoryId}&workerId=${workerId}&filePath=docs%2Fv3.0.0%2F04-basic%E5%91%BD%E5%90%8D%E6%B2%BB%E7%90%86%E6%B8%85%E5%8D%95.md`,
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

  it('extracts trailing line numbers before searching relative workspace links', async () => {
    const searchFiles = vi.fn().mockResolvedValue(
      makeSearchResponse(['docs/version-tracker/v3.0.0/P0-Step6-平台端页面联调-承接任务.md']),
    )

    const result = await resolveChatLinkTarget({
      href: './docs/version-tracker/v3.0.0/P0-Step6-平台端页面联调-承接任务.md:77',
      text: 'Step6-承接任务',
      origin,
      directoryId,
      workerId,
      directoryRoot,
      searchFiles,
    })

    expect(searchFiles).toHaveBeenCalledWith(directoryId, 'P0-Step6-平台端页面联调-承接任务.md', 80)
    expect(result).toEqual({
      kind: 'open',
      url: `${origin}/#/files?directoryId=${directoryId}&workerId=${workerId}&filePath=docs%2Fversion-tracker%2Fv3.0.0%2FP0-Step6-%E5%B9%B3%E5%8F%B0%E7%AB%AF%E9%A1%B5%E9%9D%A2%E8%81%94%E8%B0%83-%E6%89%BF%E6%8E%A5%E4%BB%BB%E5%8A%A1.md&line=77`,
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
