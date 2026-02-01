import { describe, test, expect, beforeAll, afterEach } from 'vitest';
import axios from 'axios';
import { createAuthenticatedClient, CodingAgentClient } from '../src/api-client.js';
import { generateTestUserId, generateTestProjectId, TEST_CONFIG } from '../src/config.js';
import type { Event } from '../src/types.js';

/**
 * 场景 5: E2E OpenHands 真实交互测试
 *
 * 验证完整的 OpenHands 集成流程：
 * 1. 创建 Conversation (启动 OH sandbox)
 * 2. 发送编码任务消息（创建文件 + git commit + push 到唯一分支）
 * 3. 轮询事件直到 Agent 完成
 * 4. 通过 GitLab API 验证文件已提交到分支
 *
 * 环境变量：
 *   SKIP_OPENHANDS_TESTS=true  跳过此测试
 *   GITLAB_TOKEN=xxx           GitLab API Token（验证提交用）
 *   GITLAB_URL=http://...      GitLab 地址
 *   E2E_GIT_REPO_URL=http://...  测试仓库地址
 */
describe('05 - E2E OpenHands 真实交互测试', () => {
  let client: CodingAgentClient;
  let conversationId: string | null = null;
  let testBranchName: string | null = null;

  const userId = generateTestUserId();
  const projectId = generateTestProjectId();

  const GITLAB_URL = TEST_CONFIG.e2e?.gitlabUrl || 'http://gitlib.foggysource.com';
  const GITLAB_TOKEN = process.env.GITLAB_TOKEN || '';
  const GITLAB_PROJECT = TEST_CONFIG.e2e?.gitlabProject || 'test/coding-test';
  // 如果提供了 GITLAB_TOKEN，在 repo URL 中嵌入凭证以便 agent 能够 push
  const GIT_REPO_URL = GITLAB_TOKEN
    ? `http://root:${GITLAB_TOKEN}@gitlib.foggysource.com/${GITLAB_PROJECT}.git`
    : (TEST_CONFIG.e2e?.gitRepoUrl || 'http://gitlib.foggysource.com/test/coding-test');

  beforeAll(async () => {
    if (TEST_CONFIG.skipOpenHandsTests) {
      console.log('Skipping OpenHands E2E tests (SKIP_OPENHANDS_TESTS=true)');
      return;
    }
    client = await createAuthenticatedClient();
  });

  afterEach(async () => {
    if (conversationId && TEST_CONFIG.autoCleanup) {
      try {
        await client.deleteConversation(conversationId);
        console.log(`Cleaned up conversation: ${conversationId}`);
      } catch (error) {
        console.warn(`Failed to cleanup conversation: ${conversationId}`);
      }
      conversationId = null;
    }
  });

  test('完整流程：创建会话 → 发送编码任务 → 等待完成 → 验证 GitLab 提交', async () => {
    if (TEST_CONFIG.skipOpenHandsTests) {
      console.log('Skipped');
      return;
    }

    // 生成唯一分支名，避免冲突
    const timestamp = new Date().toISOString().replace(/[-:T]/g, '').substring(0, 14);
    testBranchName = `test/e2e-${timestamp}`;
    console.log(`Test branch: ${testBranchName}`);

    // Step 1: 创建 Conversation（基于 main 分支）
    console.log('Step 1: Creating conversation...');
    const conversation = await client.createConversation({
      userId,
      projectId,
      gitRepoUrl: GIT_REPO_URL,
      branchName: 'main',
    });
    conversationId = conversation.conversationId;

    console.log(`  conversationId: ${conversation.conversationId}`);
    console.log(`  ohConversationId: ${conversation.ohConversationId}`);
    console.log(`  status: ${conversation.status}`);

    expect(conversation.conversationId).toBeTruthy();
    expect(['STARTING', 'WAITING_FOR_SANDBOX', 'PREPARING_REPOSITORY', 'READY', 'RUNNING'])
      .toContain(conversation.status);

    // Step 2: 等待会话 READY
    if (conversation.status !== 'READY' && conversation.status !== 'RUNNING') {
      console.log('Step 2: Waiting for conversation to become READY...');
      const ready = await pollUntilStatus(
        client, conversationId, ['READY', 'RUNNING'], 120_000
      );
      expect(ready).toBe(true);
    }
    console.log('  Conversation is READY');

    // Step 3: 发送编码任务 - 克隆仓库、创建分支、创建文件、提交、推送
    // Note: OH V1 sandbox doesn't have the repo pre-cloned (we don't pass selectedRepository
    // to avoid git auth issues), so the agent must clone it first.
    console.log('Step 3: Sending coding task...');
    const taskContent = [
      `Please do the following steps in order using bash commands:`,
      `1. Clone the repository: git clone ${GIT_REPO_URL} /tmp/coding-test`,
      `2. cd /tmp/coding-test`,
      `3. Create and checkout a new git branch: git checkout -b ${testBranchName}`,
      `4. Create a file called "helloworld.md" with exactly this content: "Hello World from E2E Test at ${timestamp}"`,
      `5. Run: git add helloworld.md`,
      `6. Run: git config user.email "e2e@test.com" && git config user.name "E2E Test"`,
      `7. Run: git commit -m "E2E test: add helloworld.md"`,
      `8. Run: git push origin "${testBranchName}"`,
      ``,
      `Make sure all commands succeed. Do not skip any step.`,
    ].join('\n');

    const message = await client.sendMessage(conversationId, {
      content: taskContent,
    });

    expect(message).toBeDefined();
    expect(message.messageId).toBeTruthy();
    console.log(`  messageId: ${message.messageId}`);

    // Step 4: 轮询事件，等待 Agent 完成
    console.log('Step 4: Polling events until agent completes...');
    const events = await pollForAgentCompletion(client, conversationId, 240_000);

    console.log(`  Received ${events.length} events`);
    expect(events.length).toBeGreaterThan(0);

    const agentActions = events.filter(e => e.kind === 'AGENT_ACTION');
    const agentObservations = events.filter(e => e.kind === 'AGENT_OBSERVATION');
    console.log(`  Agent actions: ${agentActions.length}`);
    console.log(`  Agent observations: ${agentObservations.length}`);

    // Step 5: 通过 GitLab API 验证文件已提交
    if (GITLAB_TOKEN) {
      console.log('Step 5: Verifying commit via GitLab API...');
      const verified = await verifyGitLabBranch(
        GITLAB_URL, GITLAB_TOKEN, GITLAB_PROJECT, testBranchName, 'helloworld.md'
      );

      if (verified.branchExists) {
        console.log(`  Branch "${testBranchName}" exists on GitLab`);
      } else {
        console.warn(`  Branch "${testBranchName}" NOT found on GitLab (agent may not have pushed)`);
      }

      if (verified.fileExists) {
        console.log(`  File "helloworld.md" found on branch`);
        console.log(`  File content: ${verified.fileContent?.substring(0, 100)}`);
        expect(verified.fileContent).toContain('Hello World');
      } else {
        console.warn('  File "helloworld.md" NOT found on branch');
      }

      // 至少分支应该被创建了
      expect(verified.branchExists).toBe(true);
      expect(verified.fileExists).toBe(true);
    } else {
      console.log('Step 5: Skipped GitLab verification (no GITLAB_TOKEN set)');
      console.log('  Set GITLAB_TOKEN env var to enable commit verification');
    }

    console.log('E2E test completed successfully');
  }, 300_000); // 5 minute timeout

  test('会话创建后应该能查询到状态事件', async () => {
    if (TEST_CONFIG.skipOpenHandsTests) {
      console.log('Skipped');
      return;
    }

    const conversation = await client.createConversation({
      userId,
      projectId,
      gitRepoUrl: GIT_REPO_URL,
      branchName: 'main',
    });
    conversationId = conversation.conversationId;

    await sleep(2000);

    const events = await client.getEvents(conversationId);
    expect(events).toBeDefined();

    const statusEvents = events.filter(e => e.kind === 'CONVERSATION_STATUS');
    expect(statusEvents.length).toBeGreaterThan(0);

    console.log(`Found ${statusEvents.length} status events for new conversation`);
  }, 60_000);

  test('发送消息后应该能看到 MESSAGE_SENT 事件', async () => {
    if (TEST_CONFIG.skipOpenHandsTests) {
      console.log('Skipped');
      return;
    }

    const conversation = await client.createConversation({
      userId,
      projectId,
      gitRepoUrl: GIT_REPO_URL,
      branchName: 'main',
    });
    conversationId = conversation.conversationId;

    if (conversation.status !== 'READY' && conversation.status !== 'RUNNING') {
      await pollUntilStatus(client, conversationId, ['READY', 'RUNNING', 'ERROR'], 120_000);
    }

    await client.sendMessage(conversationId, {
      content: 'List the files in the current directory',
    });

    await sleep(3000);

    const events = await client.getEvents(conversationId);
    const messageSentEvents = events.filter(e => e.kind === 'MESSAGE_SENT');
    expect(messageSentEvents.length).toBeGreaterThan(0);

    console.log(`Found ${messageSentEvents.length} MESSAGE_SENT events`);
  }, 180_000);
});

// ===== Helper Functions =====

async function pollUntilStatus(
  client: CodingAgentClient,
  conversationId: string,
  targetStatuses: string[],
  timeoutMs: number
): Promise<boolean> {
  const startTime = Date.now();

  while (Date.now() - startTime < timeoutMs) {
    try {
      const conv = await client.getConversation(conversationId);
      if (targetStatuses.includes(conv.status)) {
        return true;
      }
      if (conv.status === 'ERROR' || conv.status === 'STOPPED') {
        console.warn(`  Conversation entered terminal state: ${conv.status}`);
        return false;
      }
    } catch (error) {
      // Ignore transient errors during polling
    }
    await sleep(2000);
  }

  console.warn(`  Timed out waiting for status: ${targetStatuses.join(', ')}`);
  return false;
}

async function pollForAgentCompletion(
  client: CodingAgentClient,
  conversationId: string,
  timeoutMs: number
): Promise<Event[]> {
  const allEvents: Event[] = [];
  const startTime = Date.now();
  let lastEventCount = 0;
  let stableCount = 0;

  while (Date.now() - startTime < timeoutMs) {
    try {
      const events = await client.getEvents(conversationId);

      if (events.length > lastEventCount) {
        const newEvents = events.slice(lastEventCount);
        allEvents.push(...newEvents);
        lastEventCount = events.length;
        stableCount = 0;

        for (const event of newEvents) {
          const dataSummary = JSON.stringify(event.data).substring(0, 120);
          console.log(`  [Event] ${event.kind}: ${dataSummary}`);
        }

        const hasTerminal = newEvents.some(e =>
          (e.kind === 'CONVERSATION_STATUS' && e.data?.status === 'IDLE')
          // Only treat ERROR as terminal if it's from the agent (not validation service)
          || (e.kind === 'ERROR' && !e.data?.source?.includes('Validation'))
        );

        if (hasTerminal) {
          console.log('  Agent reached terminal state');
          return allEvents;
        }
      } else {
        stableCount++;
        // 60 seconds with no new events -> assume done
        // LLM responses can take 30-50 seconds, so we need a generous timeout
        if (stableCount > 30) {
          console.log('  No new events for extended period, assuming complete');
          return allEvents;
        }
      }
    } catch (error) {
      // Ignore transient errors during polling
    }

    await sleep(2000);
  }

  console.warn('  Timed out waiting for agent completion');
  return allEvents;
}

interface GitLabVerifyResult {
  branchExists: boolean;
  fileExists: boolean;
  fileContent?: string;
}

async function verifyGitLabBranch(
  gitlabUrl: string,
  token: string,
  projectPath: string,
  branchName: string,
  filePath: string
): Promise<GitLabVerifyResult> {
  const encodedProject = encodeURIComponent(projectPath);
  const encodedBranch = encodeURIComponent(branchName);
  const encodedFile = encodeURIComponent(filePath);

  const headers = { 'PRIVATE-TOKEN': token };
  const result: GitLabVerifyResult = { branchExists: false, fileExists: false };

  // 等待一下让 git push 完全生效
  await sleep(3000);

  // 检查分支是否存在（最多重试 3 次）
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      const branchRes = await axios.get(
        `${gitlabUrl}/api/v4/projects/${encodedProject}/repository/branches/${encodedBranch}`,
        { headers, timeout: 10_000 }
      );
      if (branchRes.status === 200) {
        result.branchExists = true;
        break;
      }
    } catch (error: any) {
      if (error.response?.status === 404) {
        console.log(`  Branch not found yet (attempt ${attempt + 1}/3), retrying...`);
        await sleep(5000);
      } else {
        console.warn(`  GitLab API error checking branch: ${error.message}`);
        break;
      }
    }
  }

  if (!result.branchExists) {
    return result;
  }

  // 检查文件是否存在
  try {
    const fileRes = await axios.get(
      `${gitlabUrl}/api/v4/projects/${encodedProject}/repository/files/${encodedFile}?ref=${encodedBranch}`,
      { headers, timeout: 10_000 }
    );
    if (fileRes.status === 200 && fileRes.data) {
      result.fileExists = true;
      // GitLab 返回 base64 编码的内容
      if (fileRes.data.content) {
        result.fileContent = Buffer.from(fileRes.data.content, 'base64').toString('utf-8');
      }
    }
  } catch (error: any) {
    if (error.response?.status === 404) {
      console.log('  File not found on branch');
    } else {
      console.warn(`  GitLab API error checking file: ${error.message}`);
    }
  }

  return result;
}

function sleep(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms));
}
