package com.foggy.navigator.agent.framework.protocol;

/**
 * 消息类型枚举
 * 定义Agent与前端之间的所有消息类型
 */
public enum MessageType {

    // ===== 文本流 =====
    TEXT_CHUNK,             // 流式文本片段
    TEXT_COMPLETE,          // 文本完成

    // ===== 服务端工具调用 =====
    TOOL_CALL_START,        // 工具调用开始
    TOOL_CALL_RESULT,       // 工具调用结果
    TOOL_CALL_ERROR,        // 工具调用错误

    // ===== 客户端工具调用（Client-side Skills） =====
    CLIENT_TOOL_CALL,       // 请求客户端执行工具（type=CLIENT的Skill）
    CLIENT_TOOL_RESULT,     // 客户端工具执行结果

    // ===== UI渲染（参考A2UI） =====
    SURFACE_UPDATE,         // UI结构更新
    DATA_MODEL_UPDATE,      // 数据模型更新

    // ===== 路由跳转 =====
    ROUTE_REQUEST,          // 路由请求
    ROUTE_CONFIRM,          // 路由确认

    // ===== 用户交互 =====
    USER_ACTION_REQUEST,    // 请求用户操作
    CONFIRMATION_REQUEST,   // 请求用户确认
    FORM_REQUEST,           // 请求表单填写

    // ===== 状态同步 =====
    STATE_SYNC,             // 状态同步
    THINKING,               // 思考中状态
    ERROR,                  // 错误

    // ===== 生命周期 =====
    SESSION_START,          // 会话开始
    SESSION_END,            // 会话结束
    HEARTBEAT               // 心跳
}
