import { describe, test, expect, beforeAll } from 'vitest';
import { createAuthenticatedClient, SessionClient } from '../src/api-client.js';

describe('04 - 引导卡片 (Guide Cards)', () => {
  let client: SessionClient;

  beforeAll(async () => {
    client = await createAuthenticatedClient();
  });

  test('应该返回 tutor-agent 的引导卡片', async () => {
    const cards = await client.getGuideCards('tutor-agent');

    expect(cards).toBeDefined();
    expect(cards.length).toBe(3);
    expect(cards[0].title).toBe('数据查询');
    expect(cards[0].description).toBeTruthy();
    expect(cards[0].icon).toBeTruthy();
    expect(cards[1].title).toBe('报表生成');
    expect(cards[2].title).toBe('数据建模');
  });

  test('应该返回默认引导卡片', async () => {
    const cards = await client.getGuideCards();

    expect(cards).toBeDefined();
    expect(cards.length).toBe(2);
    expect(cards[0].title).toBe('开始对话');
    expect(cards[1].title).toBe('查看帮助');
  });

  test('应该为未知 agentId 返回默认卡片', async () => {
    const cards = await client.getGuideCards('unknown-agent');

    expect(cards).toBeDefined();
    expect(cards.length).toBe(2);
    expect(cards[0].title).toBe('开始对话');
  });
});
