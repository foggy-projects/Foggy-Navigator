/** 用户角色与状态的显示格式化工具 */

// ===== 角色 =====

const ROLE_LABELS: Record<string, string> = {
  SUPER_ADMIN: '超级管理员',
  TENANT_ADMIN: '租户管理员',
  DEVELOPER: '开发者',
  VIEWER: '观察者',
}

const ROLE_TAG_TYPES: Record<string, '' | 'success' | 'warning' | 'danger' | 'info'> = {
  SUPER_ADMIN: 'danger',
  TENANT_ADMIN: 'warning',
  DEVELOPER: '',
  VIEWER: 'info',
}

/** 将逗号分隔的角色字符串解析为数组 */
export function parseRoles(roles?: string): string[] {
  if (!roles) return []
  return roles.split(',').filter(Boolean)
}

/** 角色 → 中文标签 */
export function roleLabel(role: string): string {
  return ROLE_LABELS[role] ?? role
}

/** 角色 → Element Plus Tag 类型 */
export function roleTagType(role: string): '' | 'success' | 'warning' | 'danger' | 'info' {
  return ROLE_TAG_TYPES[role] ?? 'info'
}

// ===== 状态 =====

const STATUS_LABELS: Record<string, string> = {
  ACTIVE: '启用',
  DISABLED: '禁用',
  DELETED: '已删除',
}

const STATUS_TAG_TYPES: Record<string, '' | 'success' | 'warning' | 'danger'> = {
  ACTIVE: 'success',
  DISABLED: 'warning',
  DELETED: 'danger',
}

/** 状态 → 中文标签 */
export function statusLabel(status: string): string {
  return STATUS_LABELS[status] ?? status
}

/** 状态 → Element Plus Tag 类型 */
export function statusTagType(status: string): '' | 'success' | 'warning' | 'danger' {
  return STATUS_TAG_TYPES[status] ?? ''
}

// ===== 日期 =====

/** 格式化日期字符串为中文本地化格式 */
export function formatDate(dateStr: string): string {
  try {
    return new Date(dateStr).toLocaleString('zh-CN')
  } catch {
    return dateStr
  }
}
