# 前端登录认证使用指南

## 问题说明

后端集成了 `user-auth-module` 认证模块，使用 JWT Token 进行身份验证。所有 `/api/**` 路径（除了登录和注册）都需要认证。

未登录用户访问 API 会收到 SecurityException 异常："未登录，请先登录"。

## 解决方案

### 1. 新增文件

```
frontend/src/
├── api/
│   └── auth.ts              # 登录/注册 API
├── utils/
│   └── auth.ts              # Token 和用户信息管理
└── views/
    └── Login.vue            # 登录页面
```

### 2. 修改文件

- `src/api/client.ts` - 添加请求拦截器（自动携带 Token）和响应拦截器（处理认证失败）
- `src/router/index.ts` - 添加登录路由和路由守卫
- `src/App.vue` - 添加用户信息显示和退出登录功能
- `vite.config.ts` - 优化代理配置

### 3. 工作流程

1. **首次访问**：未登录用户访问任何页面 → 路由守卫拦截 → 跳转到登录页
2. **登录**：输入用户名和密码 → 调用 `/api/v1/auth/login` → 保存 JWT Token 到 localStorage
3. **API 请求**：所有 API 请求自动在 Header 中携带 `Authorization: Bearer <token>`
4. **Token 失效**：收到 401 或 SecurityException → 清除登录信息 → 跳转到登录页
5. **退出登录**：点击右上角用户名 → 退出登录 → 清除本地信息 → 跳转到登录页

## 使用方法

### 启动前端开发服务器

```bash
cd addons/coding-agent/frontend
npm install  # 首次需要安装依赖
npm run dev
```

### 访问应用

1. 打开浏览器访问 `http://localhost:5173`
2. 自动跳转到登录页 `http://localhost:5173/#/login`
3. 注册或登录：
   - **注册新用户**：点击"还没有账号？立即注册"
   - **已有账号**：直接输入用户名和密码登录

### 测试账号

后端初始化时会自动创建管理员账号，可以查看后端日志获取初始密码。

或者在登录页面注册新账号。

## 技术实现

### Token 存储

```typescript
// 保存 Token
localStorage.setItem('coding_agent_token', token)

// 获取 Token
const token = localStorage.getItem('coding_agent_token')
```

### API 请求自动携带 Token

```typescript
// src/api/client.ts
client.interceptors.request.use(config => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})
```

### 认证失败自动跳转

```typescript
// src/api/client.ts
client.interceptors.response.use(
  response => response.data,
  error => {
    if (error.response?.status === 401 ||
        error.response?.data?.message?.includes('未登录')) {
      clearAuth()
      router.push('/login')
    }
    return Promise.reject(error)
  }
)
```

### 路由守卫

```typescript
// src/router/index.ts
router.beforeEach((to, from, next) => {
  const requireAuth = to.meta.requireAuth

  if (requireAuth && !isLoggedIn()) {
    next('/login')
  } else {
    next()
  }
})
```

## 调试

### 查看 Token

打开浏览器开发者工具：

```javascript
// Console 中执行
localStorage.getItem('coding_agent_token')
```

### 查看 API 请求头

开发者工具 → Network → 选择任意 API 请求 → Headers → Request Headers

应该看到：
```
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

### 清除登录信息

```javascript
// Console 中执行
localStorage.clear()
location.reload()
```

## 常见问题

### Q1: 登录后依然跳转到登录页

**原因**：Token 保存失败或路由守卫配置错误

**解决**：
1. 检查浏览器控制台是否有错误
2. 检查 localStorage 中是否有 `coding_agent_token`
3. 刷新页面重试

### Q2: API 请求返回 401

**原因**：Token 失效或后端未正确解析 Token

**解决**：
1. 检查 Token 格式是否正确（`Bearer <token>`）
2. 检查后端日志，看是否有 JWT 解析错误
3. 重新登录获取新 Token

### Q3: 开发环境跨域问题

**原因**：Vite proxy 配置不正确

**解决**：
- 确保 `vite.config.ts` 中 proxy 配置正确
- 前端请求使用相对路径 `/api/v1/...`
- 不要直接请求 `http://localhost:8112`

### Q4: 注册失败

**原因**：用户名重复或密码不符合规则

**解决**：
1. 检查表单验证提示
2. 用户名：3-20 个字符
3. 密码：6-20 个字符
4. 确保用户名唯一

## 后端 API 文档

### 登录

```http
POST /api/v1/auth/login
Content-Type: application/json

{
  "username": "admin",
  "password": "password"
}

Response:
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "userId": "uuid",
  "username": "admin",
  "roles": ["ROLE_USER", "ROLE_ADMIN"]
}
```

### 注册

```http
POST /api/v1/auth/register
Content-Type: application/json

{
  "username": "newuser",
  "password": "password123",
  "email": "user@example.com"  // 可选
}

Response:
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "userId": "uuid",
  "username": "newuser",
  "roles": ["ROLE_USER"]
}
```

### 受保护的 API

```http
GET /api/v1/conversations
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...

Response:
[...]
```

## 注意事项

1. **生产环境**：Token 应该设置过期时间，前端需要实现 Token 刷新机制
2. **安全性**：不要在代码中硬编码用户名和密码
3. **HTTPS**：生产环境必须使用 HTTPS 传输 Token
4. **XSS 防护**：Token 存储在 localStorage 有 XSS 风险，考虑使用 HttpOnly Cookie
