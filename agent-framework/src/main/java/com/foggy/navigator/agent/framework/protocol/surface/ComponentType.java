package com.foggy.navigator.agent.framework.protocol.surface;

/**
 * 预定义组件类型（白名单，安全）
 * 参考A2UI协议设计
 */
public enum ComponentType {
    // 布局组件
    CONTAINER,
    ROW,
    COLUMN,
    CARD,
    MODAL,
    TABS,
    ACCORDION,

    // 展示组件
    TEXT,
    MARKDOWN,
    IMAGE,
    ICON,
    BADGE,
    PROGRESS,
    DIVIDER,

    // 交互组件
    BUTTON,
    LINK,
    INPUT,
    TEXTAREA,
    SELECT,
    CHECKBOX,
    RADIO,
    SWITCH,
    FORM,

    // 数据组件
    TABLE,
    LIST,
    TREE,
    CHART,
    CODE_BLOCK,
    JSON_VIEWER,

    // Agent专用组件
    THINKING_INDICATOR,
    TOOL_RESULT,
    AGENT_CARD,
    MESSAGE_BUBBLE,
    SUGGESTION_CHIPS
}
