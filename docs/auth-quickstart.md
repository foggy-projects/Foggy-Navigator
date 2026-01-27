# 认证模块快速使用指南

## 1. 启动服务

启动 metadata-config-module（已集成认证）：

```bash
cd metadata-config-module
mvn spring-boot:run
```

服务启动后会自动创建 ROOT 账号：
```
========================================
ROOT user created successfully!
Username: root
Password: root123
========================================
```

## 2. 登录获取 Token

```bash
# 登录
curl -X POST http://localhost:8083/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "root",
    "password": "root123"
  }'
```

响应示例：
```json
{
  "code": 0,
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "tokenType": "Bearer",
    "expiresIn": 86400,
    "user": {
      "id": "xxx",
      "username": "root",
      "roles": "SUPER_ADMIN"
    }
  }
}
```

## 3. 使用 Token 访问 API

```bash
# 设置 Token 变量
TOKEN="eyJhbGciOiJIUzI1NiJ9..."

# 调用需要认证的 API
curl -X POST http://localhost:8083/api/config/datasource \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "basicInfo": {
      "name": "Test MySQL",
      "type": "JDBC"
    },
    "jdbcInfo": {
      "dbType": "MySQL",
      "host": "localhost",
      "port": 3306,
      "databaseName": "test"
    }
  }'
```

## 4. 使用 API Key（可选）

### 创建 API Key

```bash
curl -X POST http://localhost:8083/api/v1/users/{userId}/api-keys \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Development Key"
  }'
```

### 使用 API Key 访问

```bash
curl -X GET http://localhost:8083/api/v1/auth/me \
  -H "X-API-Key: sk-abcdef123456..."
```

## 5. 接口权限说明

| 接口 | 权限要求 |
|------|---------|
| `POST /api/v1/auth/login` | 无需认证 |
| `POST /api/v1/auth/register` | 无需认证 |
| `GET /api/v1/auth/me` | 需要 Token 或 API Key |
| `POST /api/config/datasource` | 需要认证（@RequireAuth） |
| `PUT /api/config/datasource/{id}` | 需要认证 |
| `DELETE /api/config/datasource/{id}` | 需要认证 |

## 6. ROOT 账号特权

ROOT 账号（角色：SUPER_ADMIN）拥有以下特权：
- 可以访问所有租户的数据
- 绕过租户权限检查
- 可以创建其他租户的用户

## 7. 在代码中获取当前用户

```java
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;

// 在 Controller 或 Service 中
CurrentUser user = UserContext.getCurrentUser();

// 获取用户信息
String userId = user.getUserId();
String username = user.getUsername();
String tenantId = user.getTenantId();

// 权限检查
if (user.isSuperAdmin()) {
    // 超级管理员逻辑
}

if (user.hasRole("DEVELOPER")) {
    // 开发者角色逻辑
}

if (user.canAccessTenant("tenant-001")) {
    // 可以访问该租户的数据
}
```

## 8. 使用 @RequireAuth 注解

```java
// 整个 Controller 需要认证
@RestController
@RequireAuth
public class MyController {
    // ...
}

// 特定方法需要特定角色
@PostMapping("/admin/action")
@RequireAuth(roles = {"TENANT_ADMIN", "SUPER_ADMIN"})
public RX<Void> adminAction() {
    // ...
}
```

## 9. 默认账号信息

| 用户名 | 密码 | 角色 | 说明 |
|--------|------|------|------|
| root | root123 | SUPER_ADMIN | 系统超级管理员 |

**生产环境请务必修改默认密码！**

```yaml
# application.yml
system:
  root:
    username: root
    password: ${ROOT_PASSWORD:your-secure-password}
```
