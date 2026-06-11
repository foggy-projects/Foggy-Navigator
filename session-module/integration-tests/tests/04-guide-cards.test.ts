import { describe, test, expect, beforeAll } from 'vitest';
import { createAuthenticatedClient, SessionClient } from '../src/api-client.js';

describe('04 - 引导卡片 (Guide Cards)', () => {
  let client: SessionClient;

  beforeAll(async () => {
    client = await createAuthenticatedClient();
  });

  test('应该返回默认引导卡片', async () => {
    const cards = await client.getGuideCards();

    expect(cards).toBeDefined();
    expect(cards.length).toBe(2);
    expect(cards[0].title).toBe('开始对话');
    expect(cards[1].title).toBe('查看帮助');
  });

  test('应该忽略 agentId 并返回默认卡片', async () => {
    const cards = await client.getGuideCards('test-agent');

    expect(cards).toBeDefined();
    expect(cards.length).toBe(2);
    expect(cards[0].title).toBe('开始对话');
  });
});
