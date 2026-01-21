# 集成测试说明

## 概述

集成测试使用真实的 Docker 容器进行测试，而不是 mock。

## 前置条件

1. **Docker 已安装并运行**
   ```bash
   docker --version
   docker ps
   ```

2. **Docker Socket 可访问**
   - Linux/Mac: `/var/run/docker.sock`
   - Windows: Docker Desktop 需要启用 "Expose daemon on tcp://localhost:2375"

3. **环境变量（可选）**
   ```bash
   export OPENAI_API_KEY=sk-xxx  # 如果需要测试 LLM 功能
   ```

## 运行集成测试

### 运行所有集成测试
```bash
cd addons/coding-agent
mvn test -Dtest=*IntegrationTest
```

### 运行特定集成测试
```bash
mvn test -Dtest=EnvironmentServiceIntegrationTest
```

### 跳过集成测试（只运行单元测试）
```bash
mvn test -Dtest=!*IntegrationTest
```

## 测试内容

### EnvironmentServiceIntegrationTest

- ✅ `testCreateEnvironment_WithRealDocker` - 使用真实 Docker 创建环境
- ✅ `testGetEnvironment_AfterCreation` - 创建后查询环境
- ✅ `testDestroyEnvironment_WithRealDocker` - 使用真实 Docker 销毁环境
- ✅ `testMultipleEnvironments_Isolation` - 多环境隔离测试

## 测试特点

1. **真实 Docker 操作**
   - 真实创建 OpenHands 容器
   - 真实销毁容器
   - 验证容器状态

2. **自动清理**
   - 每个测试后自动清理创建的环境
   - 避免资源泄漏

3. **环境隔离**
   - 使用 `@ActiveProfiles("test")` 加载测试配置
   - 使用随机端口避免冲突

## 注意事项

1. **测试时间较长**
   - 集成测试需要创建真实容器，比单元测试慢
   - 预计每个测试 10-30 秒

2. **Docker 资源**
   - 确保 Docker 有足够的资源（内存、磁盘）
   - 测试会创建和销毁多个容器

3. **网络要求**
   - 首次运行需要拉取 OpenHands 镜像
   - 确保网络连接正常

## 故障排查

### 问题：Docker 连接失败
```
Could not find a valid Docker environment
```

**解决方案**：
- 确认 Docker 正在运行：`docker ps`
- 检查 Docker Socket 权限
- Windows 用户：确认 Docker Desktop 设置

### 问题：容器启动超时
```
容器启动超时
```

**解决方案**：
- 增加超时时间（在 application-test.yml 中）
- 检查 Docker 资源限制
- 检查镜像是否已下载

### 问题：端口冲突
```
Address already in use
```

**解决方案**：
- 测试配置使用随机端口（`server.port: 0`）
- 如果仍有问题，检查其他服务占用

## CI/CD 集成

在 CI/CD 环境中运行集成测试：

```yaml
# GitHub Actions 示例
- name: Run Integration Tests
  run: mvn test -Dtest=*IntegrationTest
  env:
    DOCKER_HOST: unix:///var/run/docker.sock
```
