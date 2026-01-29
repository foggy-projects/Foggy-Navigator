# 配置文件安全性改进说明

## ✅ 已完成的安全改进

### 1. 移除敏感信息的硬编码

**问题**: 之前在配置文件中硬编码了 LLM API Key 等敏感信息

**解决**:
- 恢复使用环境变量占位符 `${OPENAI_API_KEY:default}`
- 从 Git 跟踪中移除敏感配置文件
- 添加配置文件模板（.example 文件）

### 2. 更新 .gitignore

添加了以下规则，防止敏感配置被提交：

```gitignore
# Configuration files with sensitive data
**/application-docker.yml
**/src/main/resources/application-docker.yml
**/src/test/resources/application-docker.yml
```

### 3. 创建配置模板

创建了不包含敏感信息的模板文件：

- `launcher/src/main/resources/application-docker.yml.example`
- `addons/coding-agent/src/main/resources/application-docker.yml.example`

## 📋 Git 状态说明

当前 Git 状态：

```
M  .gitignore                                          # 更新：添加敏感文件规则
D  addons/coding-agent/src/main/resources/application-docker.yml   # 删除：从跟踪中移除
D  launcher/src/main/resources/application-docker.yml              # 删除：从跟踪中移除
?? addons/coding-agent/src/main/resources/application-docker.yml.example   # 新增：配置模板
?? launcher/src/main/resources/application-docker.yml.example              # 新增：配置模板
?? CONFIG-SETUP.md                                     # 新增：配置设置指南
```

**重要说明**:
- 本地的 `application-docker.yml` 文件仍然存在，只是不再被 Git 跟踪
- 以后修改这些文件不会出现在 `git status` 中
- 其他开发者需要从 `.example` 文件复制并配置自己的环境

## 🔐 敏感信息保护措施

### 已移除的硬编码信息

- ❌ LLM API Key: `sk-40590e5709aa4a779c93c89c5c8c70d4`
- ✅ 改用环境变量: `${OPENAI_API_KEY:sk-test-key}`

### 仍需注意的敏感信息

以下信息建议在生产环境也改用环境变量：

```yaml
# 数据库密码
spring:
  datasource:
    password: 'foggy@123'  # 建议改为 ${DB_PASSWORD:foggy@123}

# JWT 密钥
jwt:
  secret: foggy-navigator-jwt-secret-key-change-in-production  # 生产环境必须修改

# ROOT 账号密码
system:
  root:
    password: root123  # 建议改为 ${ROOT_PASSWORD:root123}
```

## 🚀 团队成员操作指南

### 首次克隆代码后

```bash
# 1. 复制配置模板
cd launcher/src/main/resources
cp application-docker.yml.example application-docker.yml

cd ../../../addons/coding-agent/src/main/resources
cp application-docker.yml.example application-docker.yml

# 2. 配置环境变量（参考 CONFIG-SETUP.md）
# Windows PowerShell:
$env:OPENAI_API_KEY = "your-actual-api-key"
$env:OPENAI_API_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"

# 或者直接编辑 application-docker.yml（不会被提交）
```

### 更新代码后

```bash
# 检查是否有新的配置模板文件
git status

# 如果有 .example 文件更新，同步到本地配置
diff launcher/src/main/resources/application-docker.yml.example \
     launcher/src/main/resources/application-docker.yml

# 手动合并新增的配置项
```

## ⚠️ 重要提醒

### 提交代码前检查

在每次 `git commit` 前，请确认：

1. **没有硬编码敏感信息**
   ```bash
   # 搜索可能的 API Key 模式
   git diff --cached | grep -i "sk-[a-zA-Z0-9]"
   git diff --cached | grep -i "api.*key.*:"
   ```

2. **配置文件使用环境变量**
   ```yaml
   # 正确 ✅
   api-key: ${OPENAI_API_KEY:default-value}

   # 错误 ❌
   api-key: sk-40590e5709aa4a779c93c89c5c8c70d4
   ```

3. **更新了 .example 模板文件**
   - 新增配置项需要同步到 .example 文件
   - .example 文件应该使用占位符或示例值

### 如果不小心提交了敏感信息

如果已经提交了包含敏感信息的代码：

```bash
# 1. 立即修改敏感信息（如更换 API Key）

# 2. 从 Git 历史中移除（如果还未推送）
git reset --soft HEAD~1  # 撤销最后一次提交
# 修改文件，移除敏感信息
git add .
git commit -m "Fix: Remove sensitive data from config"

# 3. 如果已经推送，考虑使用 git filter-branch 或 BFG Repo-Cleaner
# 但这会重写历史，需要团队协调
```

## 📚 相关文档

- [CONFIG-SETUP.md](./CONFIG-SETUP.md) - 详细的配置设置指南
- [TEST-SETUP-SUMMARY.md](./addons/coding-agent/TEST-SETUP-SUMMARY.md) - 测试环境设置
- [.gitignore](./.gitignore) - Git 忽略规则

## 🔄 后续优化建议

1. **使用配置管理服务**
   - Spring Cloud Config Server
   - HashiCorp Vault
   - AWS Secrets Manager / Azure Key Vault

2. **加密敏感配置**
   - Jasypt (Java Simplified Encryption)
   - Spring Cloud Config 加密功能

3. **定期审查**
   - 定期 review .gitignore 规则
   - 使用 git-secrets 等工具自动检测
   - 定期轮换 API Key 等凭证

---

**创建时间**: 2026-01-29
**影响范围**: 配置文件管理、团队协作流程
**优先级**: 高（涉及安全）
