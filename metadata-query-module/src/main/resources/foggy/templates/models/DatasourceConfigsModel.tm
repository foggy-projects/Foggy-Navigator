/**
 * 数据源配置模型
 * @description 系统数据源配置表，存储各类数据库连接信息（MySQL、PostgreSQL、MongoDB等）
 */

export const model = {
    name: 'DatasourceConfigsModel',
    caption: '数据源配置',
    description: '数据源配置维度表，包含数据库类型、连接信息、状态等属性',
    tableName: 'datasource_configs',
    idColumn: 'id',

    properties: [
        {
            column: 'id',
            caption: '配置ID',
            description: '数据源配置的唯一标识符',
            type: 'STRING'
        },
        {
            column: 'tenant_id',
            name: 'tenantId',
            caption: '租户ID',
            description: '多租户场景下的租户标识',
            type: 'STRING'
        },
        {
            column: 'db_type',
            name: 'dbType',
            caption: '数据库类型',
            description: '数据库类型：MySQL、PostgreSQL、Oracle、SQL Server、MongoDB等',
            type: 'STRING'
        },
        {
            column: 'host',
            caption: '主机地址',
            description: '数据库服务器的主机地址或IP',
            type: 'STRING'
        },
        {
            column: 'port',
            caption: '端口号',
            description: '数据库服务器的端口号',
            type: 'INTEGER'
        },
        {
            column: 'database_name',
            name: 'databaseName',
            caption: '数据库名称',
            description: '目标数据库的名称',
            type: 'STRING'
        },
        {
            column: 'username',
            caption: '用户名',
            description: '数据库连接用户名',
            type: 'STRING'
        },
        {
            column: 'status',
            caption: '配置状态',
            description: '配置状态：NOT_STARTED（未开始）、IN_PROGRESS（配置中）、CONFIGURED（已配置）、VALIDATED（已验证）、FAILED（配置失败）',
            type: 'STRING'
        },
        {
            column: 'connection_valid',
            name: 'connectionValid',
            caption: '连接有效性',
            description: '数据源连接测试结果，true表示连接有效',
            type: 'BOOL'
        },
        {
            column: 'description',
            caption: '配置描述',
            description: '数据源配置的文字描述',
            type: 'STRING'
        },
        {
            column: 'created_at',
            name: 'createdAt',
            caption: '创建时间',
            description: '配置创建时间',
            type: 'DATETIME'
        },
        {
            column: 'updated_at',
            name: 'updatedAt',
            caption: '更新时间',
            description: '配置最后更新时间',
            type: 'DATETIME'
        }
    ]
};
