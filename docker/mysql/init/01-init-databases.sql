-- Coding Agent 数据库初始化脚本
-- 该脚本会在容器首次启动时自动执行
-- 注意：表结构由 JPA 自动创建，这里只负责创建数据库和用户

-- 创建数据库
CREATE DATABASE IF NOT EXISTS `coding_agent` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS `coding_agent_test` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 创建用户并授权
CREATE USER IF NOT EXISTS 'foggy'@'%' IDENTIFIED BY 'foggy@123';
GRANT ALL PRIVILEGES ON coding_agent.* TO 'foggy'@'%';
GRANT ALL PRIVILEGES ON coding_agent_test.* TO 'foggy'@'%';

-- 刷新权限
FLUSH PRIVILEGES;

SELECT 'Database initialization completed! Tables will be created automatically by JPA.' AS message;
