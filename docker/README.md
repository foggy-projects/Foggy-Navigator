# Foggy Navigator Docker 环境

本目录包含 Foggy Navigator 开发和测试所需的 Docker 环境配置。

## 服务列表

| 服务 | 端口 | 用户名 | 密码 | 说明 |
|------|------|--------|------|------|
| MySQL | 13309 | root | foggy@root123 | 主数据库 |
| MySQL | 13309 | foggy | foggy@123 | 应用用户 |
| GitLab | 80, 443, 2222 | root | 首次访问设置 | Git 仓库管理 |
| phpMyAdmin | 8081 | root | foggy@root123 | MySQL 管理工具 |

## 快速开始

### 1. 启动所有服务

```bash
cd docker
docker-compose up -d
```

### 2. 查看服务状态

```bash
docker-compose ps
```

### 3. 查看日志

```bash
# 查看所有服务日志
docker-compose logs -f

# 查看特定服务日志
docker-compose logs -f mysql
docker-compose logs -f gitlab
```

### 4. 停止服务

```bash
docker-compose stop
```

### 5. 停止并删除服务

```bash
docker-compose down
```

⚠️ **注意**: 使用 `docker-compose down -v` 会删除所有数据卷，慎用！

## 服务详情

### MySQL

#### 连接信息

- **主机**: localhost
- **端口**: 13309
- **数据库**: coding_agent
- **用户名**: foggy
- **密码**: foggy@123

#### Spring Boot 配置

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:13309/coding_agent?useUnicode=true&characterEncoding=utf8mb4&useSSL=false&serverTimezone=Asia/Shanghai
    username: foggy
    password: foggy@123
    driver-class-name: com.mysql.cj.jdbc.Driver
```

#### 数据库管理

**使用 MySQL 命令行**:
```bash
# 进入 MySQL 容器
docker exec -it foggy-navigator-mysql mysql -u foggy -p

# 或使用 root 用户
docker exec -it foggy-navigator-mysql mysql -u root -p
```

**使用 phpMyAdmin**:
- 访问: http://localhost:8081
- 用户名: root
- 密码: foggy@root123

#### 数据库备份

```bash
# 备份数据库
docker exec foggy-navigator-mysql mysqldump -u root -pfoggy@root123 coding_agent > backup.sql

# 恢复数据库
docker exec -i foggy-navigator-mysql mysql -u root -pfoggy@root123 coding_agent < backup.sql
```

---

### GitLab

#### 首次访问

1. 等待 GitLab 完全启动（可能需要 2-5 分钟）

```bash
# 监控启动状态
docker-compose logs -f gitlab
```

2. 访问 http://localhost（或 http://gitlab.foggy.local）

3. 首次访问会要求设置 root 用户密码

4. 使用 root 账号和设置的密码登录

#### 查看初始 root 密码

如果忘记设置密码，可以查看自动生成的初始密码：

```bash
docker exec -it foggy-navigator-gitlab grep 'Password:' /etc/gitlab/initial_root_password
```

⚠️ **注意**: 初始密码文件会在 24 小时后自动删除。

#### 创建测试仓库

1. 登录 GitLab
2. 点击 "New project"
3. 创建测试仓库（如 semantic-layer-test）
4. 克隆仓库用于测试

```bash
git clone http://localhost/root/semantic-layer-test.git
```

#### SSH 配置

GitLab SSH 端口映射到宿主机的 2222 端口：

```bash
# 配置 SSH
git remote set-url origin ssh://git@localhost:2222/root/semantic-layer-test.git

# 或使用 HTTP
git remote set-url origin http://localhost/root/semantic-layer-test.git
```

#### hosts 配置（可选）

为了更好的使用体验，可以添加 hosts 配置：

**Windows**: 编辑 `C:\Windows\System32\drivers\etc\hosts`

**Linux/Mac**: 编辑 `/etc/hosts`

添加：
```
127.0.0.1 gitlab.foggy.local
```

---

## 数据持久化

所有数据都存储在 Docker 数据卷中：

```bash
# 查看数据卷
docker volume ls | grep foggy-navigator

# 数据卷列表
# foggy-navigator_mysql_data      - MySQL 数据
# foggy-navigator_gitlab_config   - GitLab 配置
# foggy-navigator_gitlab_logs     - GitLab 日志
# foggy-navigator_gitlab_data     - GitLab 数据
```

### 备份数据卷

```bash
# 备份 MySQL 数据卷
docker run --rm -v foggy-navigator_mysql_data:/data -v $(pwd):/backup alpine tar czf /backup/mysql-backup.tar.gz -C /data .

# 备份 GitLab 数据卷
docker run --rm -v foggy-navigator_gitlab_data:/data -v $(pwd):/backup alpine tar czf /backup/gitlab-backup.tar.gz -C /data .
```

### 恢复数据卷

```bash
# 恢复 MySQL 数据卷
docker run --rm -v foggy-navigator_mysql_data:/data -v $(pwd):/backup alpine tar xzf /backup/mysql-backup.tar.gz -C /data

# 恢复 GitLab 数据卷
docker run --rm -v foggy-navigator_gitlab_data:/data -v $(pwd):/backup alpine tar xzf /backup/gitlab-backup.tar.gz -C /data
```

---

## 性能优化

### 资源限制

如果需要限制资源使用，可以在 `docker-compose.yml` 中添加：

```yaml
services:
  mysql:
    deploy:
      resources:
        limits:
          cpus: '2'
          memory: 2G
        reservations:
          cpus: '1'
          memory: 512M
```

### GitLab 优化

GitLab 是资源密集型服务，建议至少分配：
- CPU: 2 核
- 内存: 4GB

可以在 `docker-compose.yml` 中调整 GitLab 配置：

```yaml
puma['worker_processes'] = 2  # 减少 worker 数量
sidekiq['max_concurrency'] = 10  # 降低并发数
```

---

## 故障排查

### MySQL 无法启动

1. 检查端口是否被占用：
```bash
netstat -ano | findstr 13309
```

2. 查看日志：
```bash
docker-compose logs mysql
```

3. 重新初始化（会删除数据）：
```bash
docker-compose down -v
docker-compose up -d mysql
```

### GitLab 启动缓慢

GitLab 首次启动需要初始化，可能需要 5-10 分钟。可以监控日志：

```bash
docker-compose logs -f gitlab
```

等待看到类似信息：
```
gitlab-ctl reconfigure complete
```

### 磁盘空间不足

查看 Docker 磁盘使用：

```bash
docker system df
```

清理未使用的资源：

```bash
docker system prune -a
```

---

## 网络配置

所有服务运行在 `foggy-network` 网络中：

```bash
# 查看网络
docker network inspect foggy-navigator_foggy-network

# 服务间可以通过服务名访问
# 例如：mysql:3306, gitlab:80
```

---

## 安全建议

⚠️ **生产环境请务必修改默认密码！**

1. 修改 MySQL root 密码：
```bash
docker exec -it foggy-navigator-mysql mysql -u root -pfoggy@root123
ALTER USER 'root'@'%' IDENTIFIED BY 'your-secure-password';
FLUSH PRIVILEGES;
```

2. 修改 GitLab root 密码：
   - 登录 GitLab
   - 进入 Settings → Password

3. 限制网络访问：
   - 只暴露必要的端口
   - 使用防火墙规则
   - 配置 SSL/TLS

---

## 常用命令

```bash
# 启动服务
docker-compose up -d

# 停止服务
docker-compose stop

# 重启服务
docker-compose restart

# 删除服务（保留数据卷）
docker-compose down

# 删除服务和数据卷
docker-compose down -v

# 查看服务状态
docker-compose ps

# 查看日志
docker-compose logs -f [service-name]

# 进入容器
docker exec -it [container-name] /bin/bash

# 重新构建并启动
docker-compose up -d --build

# 只启动特定服务
docker-compose up -d mysql
docker-compose up -d gitlab
```

---

## 参考资料

- [MySQL Docker 官方文档](https://hub.docker.com/_/mysql)
- [GitLab Docker 官方文档](https://docs.gitlab.com/ee/install/docker.html)
- [Docker Compose 文档](https://docs.docker.com/compose/)
