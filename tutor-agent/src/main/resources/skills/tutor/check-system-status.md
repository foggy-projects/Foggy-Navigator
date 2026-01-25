# Skill ID
check-system-status

# Skill标题
检查系统状态

# 触发条件
- 检查状态
- 查看配置
- 系统状态
- 当前进度

# 意图
- check_status
- view_config

# 执行逻辑
1. 调用 checkDatasourceStatus() 检查数据源
2. 调用 checkSemanticLayerStatus() 检查语义层
3. 调用 getConfigProgress() 获取整体进度
4. 生成友好的状态报告

# 输出格式
**系统配置状态**

✅ **已完成**
- [已完成项列表]

⏳ **待配置**
- [待配置项列表]

💡 **建议下一步**: [具体建议]

# 分派条件
仅提供状态信息，不进行分派
