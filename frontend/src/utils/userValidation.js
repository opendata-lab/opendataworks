export function getUtf8ByteLength(value) {
  return new TextEncoder().encode(String(value ?? '')).length
}

export function validatePasswordBytes(password, label = '密码') {
  const raw = String(password ?? '')
  if (!raw) {
    return { valid: false, message: `请输入${label}` }
  }
  const bytes = getUtf8ByteLength(raw)
  if (raw.length < 8 || bytes > 72) {
    return { valid: false, message: `${label}至少 8 位，最多 72 字节（当前 ${bytes} 字节）` }
  }
  return { valid: true, message: '' }
}

export function validateNewUserDraft(draft = {}, existingUsers = []) {
  const username = String(draft.username ?? '').trim()
  const nickname = String(draft.nickname ?? '').trim()
  const email = String(draft.email ?? '').trim()
  const password = String(draft.password ?? '')

  if (!username) {
    return { valid: false, field: 'username', message: '请输入平台用户名' }
  }
  if (!/^[A-Za-z0-9][A-Za-z0-9_.-]{2,63}$/.test(username)) {
    return { valid: false, field: 'username', message: '用户名为3～64位字母、数字、下划线、点或短横线' }
  }
  if (
    Array.isArray(existingUsers) &&
    existingUsers.some((u) => String(u?.username || '').toLowerCase() === username.toLowerCase())
  ) {
    return { valid: false, field: 'username', message: '用户名已存在，请使用其他用户名' }
  }
  if (!nickname) {
    return { valid: false, field: 'nickname', message: '请输入姓名' }
  }
  if (nickname.length > 100) {
    return { valid: false, field: 'nickname', message: '姓名不能超过100字' }
  }
  if (email && (email.length > 128 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email))) {
    return { valid: false, field: 'email', message: '请输入有效的邮箱地址' }
  }
  const pwdCheck = validatePasswordBytes(password, '初始登录密码')
  if (!pwdCheck.valid) {
    return { valid: false, field: 'password', message: pwdCheck.message }
  }
  return { valid: true, field: '', message: '' }
}
