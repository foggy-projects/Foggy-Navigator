import { describe, expect, it } from 'vitest'
import router from '@/router'

describe('legacy workspace routes', () => {
  it.each([
    ['/tasks', 'Tasks'],
    ['/cross-tasks', 'CrossProjectTasks'],
  ])('redirects %s to the named Workers route without loading a legacy view', (path, name) => {
    const route = router.getRoutes().find((candidate) => candidate.path === path)

    expect(route).toMatchObject({
      name,
      redirect: { name: 'Workers' },
    })
    expect(route?.components).toBeUndefined()
  })

  it('keeps Workers and Chat as the canonical workspace routes', () => {
    expect(router.getRoutes().find((route) => route.name === 'Workers')?.path).toBe('/')
    expect(router.getRoutes().find((route) => route.name === 'Chat')?.path).toBe('/c/:id')
  })
})
