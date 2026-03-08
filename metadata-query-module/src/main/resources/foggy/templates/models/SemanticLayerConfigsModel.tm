/**
 * 语义层配置模型
 * @description 语义层配置表，存储Git仓库、TM/QM模型路径等信息
 */

export const model = {
    name: 'SemanticLayerConfigsModel',
    caption: '语义层配置',
    description: '语义层配置维度表，关联数据源，包含Git仓库信息、模型路径、验证状态等',
    tableName: 'semantic_layer_configs',
    idColumn: 'id',

    dimensions: [
        {
            name: 'datasource',
            caption: '关联数据源',
            description: '关联的数据源配置',
            foreignKey: 'datasource_id',
            targetModel: 'DatasourceConfigsModel'
        }
    ],

    properties: [
        {
            column: 'id',
            caption: '配置ID',
            description: '语义层配置的唯一标识符',
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
            column: 'datasource_id',
            name: 'datasourceId',
            caption: '数据源ID',
            description: '关联的数据源配置ID',
            type: 'STRING'
        },
        {
            column: 'git_repo_url',
            name: 'gitRepoUrl',
            caption: 'Git仓库URL',
            description: 'Git仓库地址，支持公开和私有仓库（GitLab、GitHub、Gitee等）',
            type: 'STRING'
        },
        {
            column: 'git_branch',
            name: 'gitBranch',
            caption: 'Git分支',
            description: 'Git仓库的分支名称，默认为main',
            type: 'STRING'
        },
        {
            column: 'semantic_layer_path',
            name: 'semanticLayerPath',
            caption: '语义层路径',
            description: 'TM/QM模型文件在Git仓库中的相对路径',
            type: 'STRING'
        },
        {
            column: 'model_count',
            name: 'modelCount',
            caption: '模型数量',
            description: '已加载的TM/QM模型文件数量',
            type: 'INTEGER'
        },
        {
            column: 'status',
            caption: '配置状态',
            description: '配置状态：NOT_STARTED（未开始）、IN_PROGRESS（配置中）、CONFIGURED（已配置）、VALIDATED（已验证）、FAILED（配置失败）',
            type: 'STRING'
        },
        {
            column: 'last_validated_at',
            name: 'lastValidatedAt',
            caption: '最后验证时间',
            description: '语义层配置最后一次验证的时间',
            type: 'DATETIME'
        },
        {
            column: 'description',
            caption: '配置描述',
            description: '语义层配置的文字描述',
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
