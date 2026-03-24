-- ============================================================
-- 统一 Agent 任务分发重构 — 表重命名迁移脚本
-- 日期: 2026-03-24
-- 说明: WorkingDirectoryEntity 从 claude-worker-agent 移到
--       navigator-common，表名从 claude_working_directories
--       改为 working_directories
--
-- 执行前提:
--   1. 通知用户暂停添加工作目录
--   2. 备份 claude_working_directories 表
--   3. 确认应用已停止或不会写入该表
--
-- 执行后:
--   1. 部署新版本应用（JPA 会识别 working_directories 表）
--   2. 恢复用户操作
-- ============================================================

-- Step 1: 备份（安全起见，copy 一份）
CREATE TABLE claude_working_directories_bak
    AS SELECT * FROM claude_working_directories;

-- Step 2: 重命名（零数据丢失，只改元数据）
RENAME TABLE claude_working_directories TO working_directories;

-- Step 3: 验证
SELECT COUNT(*) AS row_count FROM working_directories;
SELECT COUNT(*) AS backup_count FROM claude_working_directories_bak;

-- ============================================================
-- 回滚脚本（如果需要回退）:
--   RENAME TABLE working_directories TO claude_working_directories;
--   DROP TABLE IF EXISTS claude_working_directories_bak;
-- ============================================================
