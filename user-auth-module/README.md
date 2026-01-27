# User Auth Module - 用户认证授权模块

轻量级的用户认证和授权模块，提供 JWT Token 和 API Key 两种认证方式。

## 功能特性

### 1. 用户管理
- ✅ 用户注册（支持租户隔离）
- ✅ 用户登录（JWT Token）
- ✅ 用户信息查询和更新
- ✅ 用户状态管理（ACTIVE/DISABLED/DELETED）
- ✅ 密码加密存储（BCrypt）

### 2. 认证方式
- **JWT Token 认证**：适用于 Web/Mobile 应用
- **API Key 认证**：适用于服务端调用

### 3. 角色管理
- **SUPER_ADMIN**：超级管理员，全部权限
- **TENANT_ADMIN**：租户管理员，租户内全部权限
- **DEVELOPER**：开发者，可以创建和管理资源
- **VIEWER**：查看者，只读权限

## 模块结构

```
user-auth-module/
├── src/main/java/com/foggy/navigator/auth/
│   ├── controller/         # REST API
│   │   ├── AuthController.java      # 认证接口（注册、登录）
│   │   └── UserController.java      # 用户管理接口
│   ├── service/            # 业务逻辑
│   │   └── UserAuthServiceImpl.java
│   ├── repository/         # 数据访问
│   │   ├── UserRepository.java
│   │   └── ApiKeyRepository.java
│   ├── util/              # 工具类
│   │   ├── JwtUtil.java           # JWT Token 工具
│   │   ├── PasswordUtil.java      # 密码加密工具
│   │   └── ApiKeyGenerator.java   # API Key 生成工具
│   └── UserAuthApplication.java   # 启动类
└── src/test/java/          # 单元测试
```

## 数据模型

### UserEntity
```java
- id: String (UUID)
- tenantId: String
- username: String (唯一)
- passwordHash: String (BCrypt)
- email: String
- displayName: String
- roles: String (逗号分隔，如：TENANT_ADMIN,DEVELOPER)
- status: UserStatus (ACTIVE/DISABLED/DELETED)
- lastLoginAt: LocalDateTime
- createdAt/updatedAt: LocalDateTime
```

### ApiKeyEntity
```java
- id: String (UUID)
- userId: String
- apiKey: String (格式：sk-xxxxx)
- name: String
- enabled: Boolean
- expiresAt: LocalDateTime (可选)
- lastUsedAt: LocalDateTime
- createdAt/updatedAt: LocalDateTime
```

## API 接口

### 认证接口

#### 用户注册
```http
POST /api/v1/auth/register
Content-Type: application/json

{
  "tenantId": "tenant-001",
  "username": "developer",
  "password": "password123",
  "email": "dev@example.com",
  "displayName": "开发者",
  "roles": "DEVELOPER"
}
```

#### 用户登录
```http
POST /api/v1/auth/login
Content-Type: application/json

{
  "username": "developer",
  "password": "password123"
}

Response:
{
  "code": 0,
  "data": {
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "tokenType": "Bearer",
    "expiresIn": 86400,
    "user": {
      "id": "xxx",
      "username": "developer",
      "email": "dev@example.com",
      ...
    }
  }
}
```

#### 获取当前用户信息
```http
GET /api/v1/auth/me
Authorization: Bearer <token>
```

### 用户管理接口

#### 获取用户信息
```http
GET /api/v1/users/{userId}
```

#### 更新用户信息
```http
PUT /api/v1/users/{userId}
Content-Type: application/json

{
  "email": "newemail@example.com",
  "displayName": "新名称",
  "roles": "DEVELOPER,VIEWER",
  "status": "ACTIVE",
  "newPassword": "newpassword123"  // 可选
}
```

#### 删除用户
```http
DELETE /api/v1/users/{userId}
```

#### 查询租户下的用户
```http
GET /api/v1/users/tenant/{tenantId}
```

### API Key 管理

#### 创建 API Key
```http
POST /api/v1/users/{userId}/api-keys
Content-Type: application/json

{
  "name": "Production API Key",
  "expiresAt": "2026-12-31T23:59:59"  // 可选
}

Response:
{
  "code": 0,
  "data": {
    "id": "xxx",
    "apiKey": "sk-abcdef123456...",  // 仅此一次返回明文
    "maskedApiKey": "sk-***6789",
    "name": "Production API Key",
    ...
  }
}
```

#### 查询用户的 API Key 列表
```http
GET /api/v1/users/{userId}/api-keys
```

#### 撤销 API Key
```http
DELETE /api/v1/users/api-keys/{apiKeyId}
```

## 使用示例

### 1. 注册和登录

```bash
# 注册用户
curl -X POST http://localhost:8084/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "tenantId": "tenant-001",
    "username": "developer",
    "password": "password123",
    "email": "dev@example.com",
    "roles": "DEVELOPER"
  }'

# 登录获取 Token
curl -X POST http://localhost:8084/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "developer",
    "password": "password123"
  }'
```

### 2. 使用 Token 访问受保护资源

```bash
curl -X GET http://localhost:8084/api/v1/auth/me \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
```

### 3. 创建和使用 API Key

```bash
# 创建 API Key
curl -X POST http://localhost:8084/api/v1/users/{userId}/api-keys \
  -H "Content-Type: application/json" \
  -d '{
    "name": "My API Key"
  }'

# 使用 API Key 访问
curl -X GET http://localhost:8084/api/v1/auth/me \
  -H "X-API-Key: sk-abcdef123456..."
```

## 配置说明

### application.yml

```yaml
jwt:
  secret: your-secret-key-here  # 生产环境必须修改
  expiration: 86400  # Token 过期时间（秒），默认 24 小时

spring:
  security:
    autoconfigure:
      exclude: org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
```

### 环境变量

生产环境建议通过环境变量配置敏感信息：

```bash
export JWT_SECRET=your-production-secret-key
export SPRING_DATASOURCE_URL=jdbc:mysql://prod-db:3306/foggy_navigator
export SPRING_DATASOURCE_USERNAME=prod_user
export SPRING_DATASOURCE_PASSWORD=prod_password
```

## 安全建议

1. **JWT Secret**：生产环境必须使用强随机密钥（至少 256 位）
2. **密码策略**：建议在前端/Gateway层增加密码复杂度校验
3. **API Key 管理**：
   - 创建后立即保存，系统不会再次显示明文
   - 定期轮换 API Key
   - 设置合理的过期时间
4. **HTTPS**：生产环境必须使用 HTTPS
5. **速率限制**：建议在 Gateway 层添加登录接口的速率限制

## 集成到其他模块

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.foggy.navigator</groupId>
    <artifactId>user-auth-module</artifactId>
    <version>${project.version}</version>
</dependency>
```

### 2. 使用服务

```java
@Autowired
private UserAuthService userAuthService;

// 验证用户角色
boolean hasRole = userAuthService.hasRole(userId, "DEVELOPER");

// 验证租户归属
boolean belongsToTenant = userAuthService.belongsToTenant(userId, tenantId);

// 获取用户信息
Optional<UserDTO> user = userAuthService.getUser(userId);
```

## 测试

```bash
# 运行单元测试
cd user-auth-module
mvn test

# 查看测试覆盖率
mvn test jacoco:report
```

## 后续优化方向

1. 添加 OAuth2 第三方登录支持
2. 实现更细粒度的权限控制（RBAC 完整版）
3. 添加登录日志和审计功能
4. 实现 Token 刷新机制
5. 添加多因素认证（MFA）
6. 集成 Spring Security 的完整配置
