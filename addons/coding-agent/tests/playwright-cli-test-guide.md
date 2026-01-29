# Playwright CLI 测试指南

## 测试场景：创建文件测试

本测试验证 Coding Agent 的基本功能：通过 OpenHands Agent 创建文件并验证结果。

## 前置条件

1. 后端服务已启动（端口 8112）
2. MySQL 数据库已启动（端口 13309）
3. 已安装 playwright-cli（版本 0.0.61+）

## 测试步骤

### 方式1：使用 playwright-cli 交互式测试

```bash
# 1. 打开浏览器并导航到前端页面
playwright-cli open http://localhost:8112

# 在打开的浏览器中手动执行以下步骤：
# - 登录（用户名: root, 密码: root123）
# - 点击"创建会话"按钮
# - 填写会话名称: "测试会话-创建文件"
# - 等待容器启动（约10-20秒）
# - 在消息输入框中输入: "请创建一个名为 test-hello.txt 的文件，内容为 helloworld"
# - 点击发送，等待 Agent 执行（约30-60秒）
# - 查看响应消息确认文件创建成功
```

### 方式2：使用 playwright-cli 自动化脚本

```bash
# 进入测试目录
cd addons/coding-agent/tests

# 安装依赖（如果尚未安装）
npm install

# 运行自动化测试
npm run test:create-file
```

### 方式3：使用 playwright-cli session 录制

```bash
# 1. 启动录制会话
playwright-cli session-start http://localhost:8112 --session-name test-create-file

# 2. 在打开的浏览器中执行测试操作（手动）

# 3. 导出录制的操作
playwright-cli session-export test-create-file --output tests/e2e/recordings/create-file.json

# 4. 回放录制的操作
playwright-cli session-replay tests/e2e/recordings/create-file.json
```

## 预期结果

1. **登录成功**: 页面跳转到主界面，显示会话列表
2. **会话创建成功**: 新会话出现在列表中，状态为"运行中"
3. **容器启动成功**: 可以看到容器日志输出
4. **消息发送成功**: 消息显示在对话框中
5. **Agent 执行成功**:
   - Agent 响应消息中包含执行过程
   - 显示文件创建命令（如 `echo "helloworld" > test-hello.txt`）
   - 显示执行结果（成功/失败）
6. **文件验证成功**: 可以通过文件浏览器或命令查看文件内容为 "helloworld"

## 测试验证点

- [ ] 用户能够成功登录
- [ ] 用户能够创建新会话
- [ ] OpenHands 容器成功启动
- [ ] 用户消息成功发送到 Agent
- [ ] Agent 正确理解任务并执行
- [ ] 文件成功创建且内容正确
- [ ] 前后端通信正常（无错误日志）

## 故障排查

### 1. 无法登录
- 检查后端服务是否启动: `curl http://localhost:8112/actuator/health`
- 检查数据库连接: 查看日志 `logs/backend.log`
- 确认用户凭证正确: root/root123

### 2. 容器启动失败
- 检查 Docker 是否运行: `docker ps`
- 查看容器日志: 在前端界面查看"容器日志"标签
- 检查 OpenHands 镜像是否存在: `docker images | grep openhands`

### 3. Agent 不响应
- 检查 LLM API 配置:
  - API Key: 在 application-docker.yml 中配置
  - API Base URL: https://dashscope.aliyuncs.com/compatible-mode/v1
  - Model: glm-4.7
- 查看后端日志中的 API 调用错误
- 检查网络连接是否正常

### 4. 文件创建失败
- 查看 Agent 响应消息中的错误信息
- 检查容器内的工作目录权限
- 确认任务描述清晰明确

## 相关文件

- 后端服务: `launcher/target/launcher-1.0.0-SNAPSHOT.jar`
- 配置文件: `launcher/src/main/resources/application-docker.yml`
- 启动脚本: `start-launcher.ps1`
- 自动化测试: `tests/e2e/test-create-file.mjs`
- 测试截图: `tests/e2e/screenshots/`

## API 配置

本测试使用以下 LLM 配置：

```yaml
foggy:
  coding-agent:
    openhands:
      api-key: sk-40590e5709aa4a779c93c89c5c8c70d4
      model-name: glm-4.7
      api-base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
```

**注意**: 这些配置在 `launcher/src/main/resources/application-docker.yml` 中设置。

## 测试报告

测试完成后，生成测试报告：

```bash
# 查看测试日志
cat logs/backend.log

# 查看容器日志
docker logs <container_id>

# 查看测试截图
ls -la tests/e2e/screenshots/
```
