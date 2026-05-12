// Types
export type {
  ChatMessage,
  TaskStatus,
  AgentTask,
  NavigatorAction,
  NavigatorChatConfig,
  NavigatorChatMode,
  BusinessSuspensionDecisionPayload,
  BusinessSuspensionDialogModel,
  BusinessSuspensionStatus,
  BusinessSuspensionType,
  SkillFrameBlock,
  ToolExecutionBlock,
} from './types'

// Composable (headless — for custom UI)
export { useNavigatorChat } from './composables/useNavigatorChat'
export type { UseNavigatorChat } from './composables/useNavigatorChat'

// API client (for advanced usage)
export { NavigatorApi } from './api/navigatorApi'

// Component (ready-to-use UI)
export { default as NavigatorChat } from './components/NavigatorChat.vue'
