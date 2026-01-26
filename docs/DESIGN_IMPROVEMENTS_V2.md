# 设计改进总结 V2

**日期**: 2026-01-25
**审阅状态**: ✅ 已优化
**相关文档**:
- [配置管理模块设计](./configuration-module-design.md)
- [元数据语义层统一查询设计](./metadata-semantic-layer-design.md)

---

## 改进点汇总

### 1. ✅ 语义层支持私有Git仓库

**问题**：原设计未明确说明是否支持私有GitLab/GitHub。

**改进**：
- ✅ 明确支持私有GitLab、GitHub、Gitee
- ✅ 提供4种认证方式：
  - `NONE`：公开仓库
  - `ACCESS_TOKEN`：访问令牌（推荐，GitHub PAT / GitLab Token）
  - `BASIC`：用户名密码（不推荐）
  - `SSH`：SSH密钥（企业内部GitLab）
- ✅ 新增字段：`accessToken`, `username`, `password`, `sshPrivateKey`
- ✅ 所有敏感信息（token、密码、私钥）后端加密存储

**示例配置**：

```java
GitRepoConfig gitConfig = new GitRepoConfig();
gitConfig.setRepoUrl("https://gitlab.company.com/team/semantic-models.git");
gitConfig.setBranch("main");
gitConfig.setAuthType(GitAuthType.ACCESS_TOKEN);
gitConfig.setAccessToken("glpat-xxx..."); // GitLab Project Access Token
```

**支持的Git平台**：
- ✅ GitHub（公开/私有）
- ✅ GitLab（公开/私有/企业版）
- ✅ Gitee（码云）
- ✅ Bitbucket
- ✅ 企业内部GitLab/Gogs/Gitea

---

### 2. 💡 用户权限管理方案建议

**问题**：Phase 2 权限配置管理是自研还是用开源？

**推荐方案**：

#### Phase 1（MVP）：Spring Security + JWT + RBAC

**优势**：
- ✅ 轻量级，快速启动
- ✅ 完全控制，灵活定制
- ✅ Spring生态原生支持

**实现**：
```sql
-- 用户表
CREATE TABLE users (
    id VARCHAR(64) PRIMARY KEY,
    username VARCHAR(128) UNIQUE NOT NULL,
    password VARCHAR(512) NOT NULL,  -- BCrypt加密
    email VARCHAR(255),
    status VARCHAR(32),
    created_at DATETIME
);

-- 角色表
CREATE TABLE roles (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(64) UNIQUE NOT NULL,  -- ADMIN, USER, VIEWER
    description VARCHAR(255)
);

-- 用户-角色关联表
CREATE TABLE user_roles (
    user_id VARCHAR(64),
    role_id VARCHAR(64),
    PRIMARY KEY (user_id, role_id)
);

-- 权限表（可选，细粒度权限控制）
CREATE TABLE permissions (
    id VARCHAR(64) PRIMARY KEY,
    resource VARCHAR(128),  -- datasource, semantic-layer, session
    action VARCHAR(64),     -- create, read, update, delete
    description VARCHAR(255)
);

-- 角色-权限关联表
CREATE TABLE role_permissions (
    role_id VARCHAR(64),
    permission_id VARCHAR(64),
    PRIMARY KEY (role_id, permission_id)
);
```

**预定义角色**：
- `ADMIN`：系统管理员（全部权限）
- `DEVELOPER`：开发者（配置数据源、语义层，查询会话）
- `VIEWER`：查看者（只读权限，查询配置和会话）

#### Phase 2+（可选）：Keycloak集成

**优势**：
- ✅ 企业级身份认证（SSO、OIDC、SAML）
- ✅ 多租户支持
- ✅ 用户联邦（AD/LDAP集成）
- ✅ 丰富的权限模型

**适用场景**：
- 企业级部署
- 需要SSO单点登录
- 多租户SaaS模式
- 与现有企业身份系统集成

**集成方式**：
```xml
<dependency>
    <groupId>org.keycloak</groupId>
    <artifactId>keycloak-spring-boot-starter</artifactId>
</dependency>
```

**推荐策略**：
1. **Phase 1**：Spring Security + JWT（快速启动）
2. **Phase 2**：评估Keycloak（根据企业需求）
3. **Phase 3**：支持两种模式（内置 / Keycloak）

---

### 3. ✅ 接口参数优化：使用Form/DTO代替Entity

**问题**：原设计直接传Entity（JPA实体），违反分层架构原则。

**改进**：采用**二层结构Form**，支持多种数据源类型扩展。

#### 改进前：

```java
// ❌ 直接传Entity
void updateDatasourceConfig(String configId, DatasourceConfig config);
```

**问题**：
- Entity包含JPA注解、审计字段（createdAt、updatedAt）
- 前端需要了解Entity结构
- 难以扩展（新增数据源类型需要修改Entity）

#### 改进后：

```java
// ✅ 使用Form/DTO
void updateDatasourceConfig(String configId, DatasourceConfigForm form);
```

**优势**：
- ✅ 分层清晰（Controller → Service → Repository → Entity）
- ✅ 灵活扩展（新增数据源类型只需添加新的xxxInfo）
- ✅ 支持部分更新（只传需要修改的字段）
- ✅ 前端友好（清晰的API契约）

#### 二层结构设计：

```java
@Data
public class DatasourceConfigForm {
    private String id;
    private String tenantId;

    // 第一层：基本信息（通用）
    private DatasourceBasicInfo basicInfo;

    // 第二层：类型特定信息（按需选择）
    private JdbcDatasourceInfo jdbcInfo;        // JDBC数据源
    private MongoDatasourceInfo mongoInfo;      // MongoDB
    private RedisDatasourceInfo redisInfo;      // Redis (Phase 2)

    // 根据 basicInfo.type 决定使用哪个具体配置
}
```

**扩展示例**：新增Elasticsearch数据源

```java
// 只需添加新的Info类，无需修改Form
private ElasticsearchDatasourceInfo esInfo;
```

---

### 4. ✅ 模型文件组织和命名规范

**问题**：原设计未明确文件组织结构和命名规范。

**改进**：遵循技能标准，清晰的目录结构和命名约定。

#### 文件组织结构：

```
semantic-models/
├── models/                    # TM表模型（独立目录）
│   ├── DatasourceConfigsModel.tm
│   ├── SemanticLayerConfigsModel.tm
│   ├── SessionsModel.tm
│   └── MessagesModel.tm
└── queries/                   # QM查询模型（独立目录）
    ├── datasource-latest.qm
    ├── datasource-list.qm
    ├── semantic-layer-latest.qm
    ├── config-progress.qm
    ├── sessions-active.qm
    └── messages-by-session.qm
```

**改进前**：
```
semantic-models/
├── datasource_configs.tm
├── datasource_configs.qm
├── sessions.tm
└── sessions.qm
```

**问题**：
- TM和QM混在一起，不易维护
- 文件名不统一（下划线 vs 中划线）

#### 命名规范：

| 类型 | 文件名格式 | name字段格式 | 示例 |
|-----|----------|------------|------|
| **TM** | `{ModelName}.tm`（驼峰） | `{TableName}Model` | `DatasourceConfigsModel.tm` |
| **QM** | `{query-id}.qm`（kebab-case） | `queryId`与文件名一致 | `datasource-latest.qm` |

#### TM示例：

```json
{
  "name": "DatasourceConfigsModel",        // ✅ 驼峰 + Model后缀
  "displayName": "数据源配置",
  "tableName": "datasource_configs",       // 实际表名
  "type": "mysql",
  "columns": [...]
}
```

**改进点**：
- `name`：`DatasourceConfigsModel`（遵循技能标准）
- 新增 `tableName` 字段：实际数据库表名

#### QM示例：

```json
{
  "queryId": "datasource-latest",          // ✅ 与文件名一致
  "displayName": "最新数据源配置",
  "tableModel": "DatasourceConfigsModel",  // ✅ 引用TM的name
  "fields": [...],
  "defaultSort": {"field": "created_at", "order": "DESC"},
  "limit": 1
}
```

**改进点**：
- `queryId`：kebab-case，与REST接口的queryId保持一致
- `tableModel`：引用TM的`name`（而非表名）

---

## 设计优势总结

### 1. Git仓库支持

- ✅ **灵活性**：支持公开/私有仓库，适配多种Git平台
- ✅ **安全性**：推荐AccessToken认证，敏感信息加密存储
- ✅ **企业友好**：支持SSH认证，适配企业内部GitLab

### 2. 用户权限管理

- ✅ **渐进式**：Phase 1轻量级，Phase 2可选企业级
- ✅ **标准化**：RBAC模型，符合业界最佳实践
- ✅ **可扩展**：预留Keycloak集成路径

### 3. 接口参数设计

- ✅ **分层清晰**：Controller/Service/Repository/Entity职责分离
- ✅ **易扩展**：二层结构支持新数据源类型无缝扩展
- ✅ **易维护**：Form独立于Entity，减少耦合
- ✅ **前端友好**：清晰的API契约

### 4. 模型组织

- ✅ **清晰性**：models/queries分离，一目了然
- ✅ **一致性**：统一命名规范，减少认知负担
- ✅ **可维护性**：文件组织清晰，易于查找和管理
- ✅ **标准化**：遵循技能规范，与技能工具无缝集成

---

## 下一步行动

审阅通过后：

1. **确认技术选型**
   - [ ] 确认Phase 1使用Spring Security + JWT
   - [ ] 确认Git认证方式优先级（推荐AccessToken）

2. **完善设计细节**
   - [ ] 补充用户权限表设计
   - [ ] 补充Git配置加密方案
   - [ ] 补充Form校验规则

3. **开始实施**
   - [ ] 运行 `/foggy-java-integration` 集成语义层依赖
   - [ ] 运行 `/tm-generate` 生成表模型（遵循新命名规范）
   - [ ] 运行 `/qm-generate` 生成查询模型
   - [ ] 实现 configuration-module（使用Form参数）
   - [ ] 实现 metadata-query-module（支持两层查询接口）

---

**请确认以上改进是否符合预期，有任何进一步的建议请提出。**
