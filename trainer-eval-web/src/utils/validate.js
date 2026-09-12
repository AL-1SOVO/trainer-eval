export function validateUsername(rule, value, callback) {
  if (!value) return callback(new Error('用户名不能为空'))
  if (value.length < 3 || value.length > 20) return callback(new Error('用户名长度3-20个字符'))
  callback()
}

export function validatePassword(rule, value, callback) {
  if (!value) return callback(new Error('密码不能为空'))
  if (value.length < 6) return callback(new Error('密码长度不能少于6位'))
  callback()
}

export function validatePhone(rule, value, callback) {
  if (!value) return callback()
  if (!/^1[3-9]\d{9}$/.test(value)) return callback(new Error('手机号格式不正确'))
  callback()
}

export function validateEmail(rule, value, callback) {
  if (!value) return callback()
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)) return callback(new Error('邮箱格式不正确'))
  callback()
}
