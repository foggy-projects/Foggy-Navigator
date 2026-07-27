import { describe, expect, it, vi } from 'vitest'

import type { FileSearchResponse } from '@/api/fileBrowser'
import { resolveChatLinkTarget } from '@/utils/chatLinkResolver'

const origin = 'http://dev-kvm-jdk17.foggysource.com'
const directoryId = 'dir-1'
const workerId = 'worker-1'
const directoryRoot = 'D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev'
const posixDirectoryRoot = '/home/sa/workspace/foggy-data-mcp'

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

  it('opens a POSIX absolute workspace path without basename search', async () => {
    const searchFiles = vi.fn()

    const result = await resolveChatLinkTarget({
      href: '/home/sa/workspace/foggy-data-mcp/foggy-data-mcp-bridge/docs/9.5.2/prototype/runtime-console-prototype.html',
      text: 'runtime-console-prototype.html',
      origin,
      directoryId,
      workerId,
      directoryRoot: posixDirectoryRoot,
      searchFiles,
    })

    expect(searchFiles).not.toHaveBeenCalled()
    expect(result).toEqual({
      kind: 'open',
      url: `${origin}/#/files?directoryId=${directoryId}&workerId=${workerId}&filePath=foggy-data-mcp-bridge%2Fdocs%2F9.5.2%2Fprototype%2Fruntime-console-prototype.html`,
    })
  })

  it('preserves encoded POSIX paths and trailing line numbers without search', async () => {
    const searchFiles = vi.fn()

    const result = await resolveChatLinkTarget({
      href: '/home/sa/workspace/foggy-data-mcp/docs/%E8%BF%90%E8%A1%8C%E6%97%B6/runtime-console-prototype.html:42',
      text: '',
      origin,
      directoryId,
      workerId,
      directoryRoot: posixDirectoryRoot,
      searchFiles,
    })

    expect(searchFiles).not.toHaveBeenCalled()
    expect(result).toEqual({
      kind: 'open',
      url: `${origin}/#/files?directoryId=${directoryId}&workerId=${workerId}&filePath=docs%2F%E8%BF%90%E8%A1%8C%E6%97%B6%2Fruntime-console-prototype.html&line=42`,
    })
  })

  it.each([
    ['hash', 'a%23b.md', 'a%23b.md'],
    ['question mark', 'a%3Fb.md', 'a%3Fb.md'],
  ])('preserves encoded %s characters in POSIX filenames', async (_label, encodedName, encodedParam) => {
    const searchFiles = vi.fn()

    const result = await resolveChatLinkTarget({
      href: `${posixDirectoryRoot}/docs/${encodedName}`,
      text: '',
      origin,
      directoryId,
      workerId,
      directoryRoot: posixDirectoryRoot,
      searchFiles,
    })

    expect(searchFiles).not.toHaveBeenCalled()
    expect(result).toEqual({
      kind: 'open',
      url: `${origin}/#/files?directoryId=${directoryId}&workerId=${workerId}&filePath=docs%2F${encodedParam}`,
    })
  })

  it('opens POSIX file URIs without dropping the root slash', async () => {
    const searchFiles = vi.fn()

    const result = await resolveChatLinkTarget({
      href: 'file:///home/sa/workspace/foggy-data-mcp/docs/runtime-console-prototype.html',
      text: '',
      origin,
      directoryId,
      workerId,
      directoryRoot: posixDirectoryRoot,
      searchFiles,
    })

    expect(searchFiles).not.toHaveBeenCalled()
    expect(result).toEqual({
      kind: 'open',
      url: `${origin}/#/files?directoryId=${directoryId}&workerId=${workerId}&filePath=docs%2Fruntime-console-prototype.html`,
    })
  })

  it('keeps Windows file URIs compatible with workspace resolution', async () => {
    const searchFiles = vi.fn()

    const result = await resolveChatLinkTarget({
      href: 'file:///D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/docs/README.md',
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
      url: `${origin}/#/files?directoryId=${directoryId}&workerId=${workerId}&filePath=docs%2FREADME.md`,
    })
  })

  it('opens the POSIX workspace root without search', async () => {
    const searchFiles = vi.fn()

    const result = await resolveChatLinkTarget({
      href: `${posixDirectoryRoot}/`,
      text: '',
      origin,
      directoryId,
      workerId,
      directoryRoot: posixDirectoryRoot,
      searchFiles,
    })

    expect(searchFiles).not.toHaveBeenCalled()
    expect(result).toEqual({
      kind: 'open',
      url: `${origin}/#/files?directoryId=${directoryId}&workerId=${workerId}`,
    })
  })

  it('rejects POSIX absolute paths outside the workspace without basename fallback', async () => {
    const searchFiles = vi.fn()

    const result = await resolveChatLinkTarget({
      href: '/home/sa/workspace/other-project/runtime-console-prototype.html',
      text: 'runtime-console-prototype.html',
      origin,
      directoryId,
      workerId,
      directoryRoot: posixDirectoryRoot,
      searchFiles,
    })

    expect(searchFiles).not.toHaveBeenCalled()
    expect(result).toEqual({
      kind: 'warn',
      message: '该链接不在当前工作目录下，无法自动定位',
    })
  })

  it('keeps POSIX containment case-sensitive', async () => {
    const searchFiles = vi.fn()

    const result = await resolveChatLinkTarget({
      href: '/home/sa/Workspace/foggy-data-mcp/runtime-console-prototype.html',
      text: '',
      origin,
      directoryId,
      workerId,
      directoryRoot: posixDirectoryRoot,
      searchFiles,
    })

    expect(searchFiles).not.toHaveBeenCalled()
    expect(result).toEqual({
      kind: 'warn',
      message: '该链接不在当前工作目录下，无法自动定位',
    })
  })

  it('rejects absolute paths that escape the workspace through parent segments', async () => {
    const searchFiles = vi.fn()

    const result = await resolveChatLinkTarget({
      href: '/home/sa/workspace/foggy-data-mcp/../other-project/runtime-console-prototype.html',
      text: '',
      origin,
      directoryId,
      workerId,
      directoryRoot: posixDirectoryRoot,
      searchFiles,
    })

    expect(searchFiles).not.toHaveBeenCalled()
    expect(result).toEqual({
      kind: 'warn',
      message: '该链接不在当前工作目录下，无法自动定位',
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

  it('preserves external http links without a selected workspace', async () => {
    const searchFiles = vi.fn()

    const result = await resolveChatLinkTarget({
      href: 'https://example.com/docs',
      text: 'external',
      origin,
      directoryId: '',
      workerId: null,
      directoryRoot: '',
      searchFiles,
    })

    expect(searchFiles).not.toHaveBeenCalled()
    expect(result).toEqual({
      kind: 'open',
      url: 'https://example.com/docs',
    })
  })
})
