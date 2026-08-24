import { describe, expect, it } from 'vitest'
import { adminMenus } from '../menu'

describe('admin navigation', () => {
  it('contains the governed workspaces in stable order', () => {
    expect(adminMenus.map(item => item.id)).toEqual(['overview', 'users', 'releases', 'admins', 'announcements', 'audit'])
  })
})
