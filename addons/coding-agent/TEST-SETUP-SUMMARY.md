# Coding Agent 测试环境设置总结

## 📋 设置完成清单

本文档记录了 Coding Agent 前端 E2E 测试环境的完整设置。

### ✅ 已完成的工作

#### 1. 后端服务配置

- [x] 修改日志配置，统一输出到 `logs/backend.log`
  - 文件: `addons/coding-agent/src/main/resources/application.yml`
  - 文件: `launcher/src/main/resources/application.yml`

- [x] 配置 LLM API (glm-4.7 模型)
  - 文件: `launcher/src/main/resources/application-docker.yml`
  - API Key: `sk-40590e5709aa4a779c93c89c5c8c70d4`
  - API Base URL: `https://dashscope.aliyuncs.com/compatible-mode/v1`
  - Model: `glm-4.7`

- [x] 创建启动脚本（使用 Java 17）
  - 文件: `start-launcher.ps1`
  - 功能: 自动启动后端服务并等待就绪

#### 2. 测试框架设置

- [x] 创建测试目录结构
  ```
  addons/coding-agent/tests/
  ├── README.md
  ├── playwright-cli-test-guide.md
  ├── package.json
  ├── run-test.bat
  ├── e2e/
  │   └── test-create-file.mjs
  └── screenshots/
  ```

- [x] 安装测试依赖
  - Playwright 1.58.0
  - Chromium 浏览器

- [x] 创建自动化测试脚本
  - 文件: `tests/e2e/test-create-file.mjs`
  - 功能: 半自动化测试（自动登录 + 手动操作指导）

#### 3. 测试文档

- [x] 创建详细测试指南
  - `tests/README.md` - 完整的测试套件说明
  - `tests/playwright-cli-test-guide.md` - Playwright CLI 使用指南

- [x] 创建快速启动脚本
  - `tests/run-test.bat` - Windows 批处理脚本
  - 自动检查后端状态并启动测试

## 📁 关键文件位置

### 配置文件

| 文件 | 路径 | 用途 |
|------|------|------|
| 后端配置 | `addons/coding-agent/src/main/resources/application.yml` | 开发环境配置 |
| Docker配置 | `launcher/src/main/resources/application-docker.yml` | 生产环境配置（含LLM配置） |
| 启动脚本 | `start-launcher.ps1` | 后端服务启动脚本 |
| 环境变量 | `addons/coding-agent/.env` | LLM API 配置（已弃用，改用 YAML）|
| OpenHands配置 | `addons/openhands/.env` | OpenHands 环境配置 |

### 测试文件

| 文件 | 路径 | 用途 |
|------|------|------|
| 测试主README | `addons/coding-agent/tests/README.md` | 测试套件完整说明 |
| CLI指南 | `addons/coding-agent/tests/playwright-cli-test-guide.md` | Playwright CLI 测试步骤 |
| 测试脚本 | `addons/coding-agent/tests/e2e/test-create-file.mjs` | 创建文件测试 |
| 快速启动 | `addons/coding-agent/tests/run-test.bat` | Windows 快速测试脚本 |
| 依赖配置 | `addons/coding-agent/tests/package.json` | Node.js 依赖 |

## 🚀 快速开始

### 第一次运行测试

```bash
# 1. 启动后端服务
cd D:\foggy-projects\Foggy-Navigator
powershell -ExecutionPolicy Bypass -File start-launcher.ps1

# 2. 运行测试（新窗口）
cd addons\coding-agent\tests
run-test.bat
```

### 后续测试

```bash
cd D:\foggy-projects\Foggy-Navigator\addons\coding-agent\tests
run-test.bat
```

## 🧪 测试场景说明

### 创建文件测试

**测试目标**: 验证通过自然语言指令创建文件的完整流程

**测试流程**:
1. 访问前端 (`http://localhost:8112`)
2. 登录系统 (root/root123)
3. 创建新会话 ("测试会话-创建文件")
4. 发送指令: "请创建一个名为 test-hello.txt 的文件，内容为 helloworld"
5. 等待 OpenHands Agent 执行
6. 验证文件创建成功

**涉及组件**:
- 前端: Vue 3 应用
- 后端: Spring Boot + JPA
- 容器: Docker + OpenHands
- LLM: GLM-4.7 (阿里云通义千问)

## 🔧 技术栈

### 后端
- Java 17
- Spring Boot 3.4.2
- Spring Data JPA
- MySQL 8.0
- Docker Java Client
- JWT 认证

### 前端
- Vue 3
- Vue Router
- Axios
- Element Plus (推测)

### 测试
- Playwright 1.58.0
- playwright-cli 0.0.61
- Node.js / JavaScript (ES Module)

### AI/Agent
- OpenHands (All Hands AI)
- GLM-4.7 模型
- 阿里云 DashScope API

## 📊 测试结果记录

测试执行后，结果保存在：

- **截图**: `tests/e2e/screenshots/`
  - `01-initial-page.png` - 初始页面
  - `02-login-page.png` - 登录页面
  - `03-after-login.png` - 登录后主界面

- **日志**: `logs/backend.log` - 后端运行日志

- **容器日志**: 通过 Docker 查看
  ```bash
  docker ps  # 查看运行中的容器
  docker logs <container_id>  # 查看容器日志
  ```

## 🐛 已知问题

### 1. 浏览器 403 错误

**现象**: 控制台显示部分资源返回 403

**影响**: 不影响主要功能

**原因**: 可能是 Spring Security 或 CORS 配置

**状态**: 可暂时忽略

### 2. 测试脚本为半自动模式

**现象**: 需要手动执行部分操作（创建会话、发送消息）

**原因**: 前端 UI 选择器可能随开发变化

**解决方案**: 已提供详细的手动操作指导

## 🔄 后续改进计划

- [ ] 实现完全自动化的 E2E 测试
- [ ] 添加更多测试场景（编辑文件、删除文件、Git 操作等）
- [ ] 集成 CI/CD 流程
- [ ] 添加性能测试
- [ ] 创建测试报告生成器

## 📞 支持

如遇到问题，请检查：

1. **后端健康检查**: `curl http://localhost:8112/actuator/health`
2. **Docker 状态**: `docker ps`
3. **日志文件**: `logs/backend.log`, `logs/backend-error.log`
4. **测试文档**: `tests/README.md`

---

**创建时间**: 2026-01-29
**最后更新**: 2026-01-29
**版本**: 1.0.0
