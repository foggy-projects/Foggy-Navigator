# 记忆系统设计文档

> **实施阶段**: Phase 3+ (后续扩展)
> **当前实现**: Phase 1 仅使用短期记忆（最近N条消息）
> **本文档作用**: 系统终态参考，指导后续记忆系统增强

---

## 1. 概述

### 1.1 模块定位
记忆系统是动态 Agent 编排系统的核心组件，负责管理 Agent 的各种记忆类型，包括短期记忆、长期记忆、情景记忆和语义记忆，为 Agent 提供智能的上下文管理和知识检索能力。

### 1.2 设计目标
- **多层级记忆**: 支持短期、长期、情景、语义等多种记忆类型
- **智能检索**: 基于语义相似度和相关性的智能记忆检索
- **高效存储**: 优化存储策略，支持大规模记忆数据
- **自动管理**: 自动记忆压缩、清理和归档
- **可扩展性**: 支持自定义记忆类型和检索策略

### 1.3 核心特性
- 多层级记忆管理
- 向量检索增强（RAG）
- 记忆压缩与优化
- 记忆关联与推理
- 记忆持久化与恢复
- 记忆访问控制

## 2. 记忆类型与层次结构

### 2.1 记忆类型

#### 2.1.1 短期记忆（Short-Term Memory）
**定义**: 存储当前对话或当前任务的上下文信息。

**特点**:
- 容量有限（通常几千到几万Token）
- 访问速度快
- 自动过期和清理
- 专注于当前会话

**存储内容**:
- 当前对话的消息历史
- 当前任务的状态和变量
- 临时计算结果
- 用户最近的输入

**实现方式**:
```java
public interface ShortTermMemory {
    void add(Message message);
    List<Message> getRecent(int count);
    List<Message> getAll();
    void clear();
    int size();
    int getTokenCount();
}
```

#### 2.1.2 长期记忆（Long-Term Memory）
**定义**: 存储跨会话的持久化信息，包括用户偏好、历史交互等。

**特点**:
- 容量大（可存储数百万条记录）
- 持久化存储
- 基于语义检索
- 支持跨会话访问

**存储内容**:
- 用户偏好和设置
- 历史对话摘要
- 重要事件和里程碑
- 用户行为模式

**实现方式**:
```java
public interface LongTermMemory {
    String store(MemoryEntry entry);
    List<MemoryEntry> retrieve(String query, int topK);
    List<MemoryEntry> retrieveByUser(String userId, int topK);
    void update(String id, MemoryEntry entry);
    void delete(String id);
}
```

#### 2.1.3 情景记忆（Episodic Memory）
**定义**: 存储具体的经历和事件，包括时间、地点、参与者等上下文信息。

**特点**:
- 时间序列存储
- 丰富的元数据
- 支持时间范围查询
- 事件关联

**存储内容**:
- 具体的对话事件
- 任务执行过程
- 错误和异常记录
- 重要的决策点

**实现方式**:
```java
public interface EpisodicMemory {
    String store(Episode episode);
    List<Episode> retrieveByTimeRange(LocalDateTime start, LocalDateTime end);
    List<Episode> retrieveBySession(String sessionId);
    List<Episode> retrieveByType(EpisodeType type);
    List<Episode> retrieveRelated(String episodeId);
}
```

#### 2.1.4 语义记忆（Semantic Memory）
**定义**: 存储行业事实、策略、领域知识等结构化知识。

**特点**:
- 结构化存储
- 知识图谱支持
- 推理能力
- 知识更新和演化

**存储内容**:
- 领域知识和规则
- 业务策略和流程
- 常见问题和答案
- 专业术语和概念

**实现方式**:
```java
public interface SemanticMemory {
    String store(Knowledge knowledge);
    List<Knowledge> retrieve(String query, int topK);
    List<Knowledge> retrieveByCategory(String category);
    void update(String id, Knowledge knowledge);
    void delete(String id);
}
```

### 2.2 记忆层次结构

```
┌─────────────────────────────────────────────────────────┐
│                    记忆系统                              │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  ┌──────────────┐  ┌──────────────┐                   │
│  │ 短期记忆      │  │ 语义记忆      │                   │
│  │ (会话上下文)  │  │ (领域知识)    │                   │
│  └──────────────┘  └──────────────┘                   │
│         │                   │                             │
│         ↓                   ↓                             │
│  ┌──────────────┐  ┌──────────────┐                   │
│  │ 情景记忆      │  │ 长期记忆      │                   │
│  │ (事件记录)    │  │ (用户偏好)    │                   │
│  └──────────────┘  └──────────────┘                   │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

## 3. 数据模型设计

### 3.1 记忆条目（MemoryEntry）

```java
@Data
public class MemoryEntry {
    private String id;
    private MemoryType type;
    private String userId;
    private String sessionId;
    private String content;
    private Map<String, Object> metadata;
    private float[] embedding;
    private double importance;
    private int accessCount;
    private LocalDateTime createdAt;
    private LocalDateTime lastAccessedAt;
    private LocalDateTime expiresAt;
    private boolean archived;
}

public enum MemoryType {
    SHORT_TERM,
    LONG_TERM,
    EPISODIC,
    SEMANTIC
}
```

### 3.2 情景记录（Episode）

```java
@Data
public class Episode {
    private String id;
    private String userId;
    private String sessionId;
    private String taskId;
    private EpisodeType type;
    private String title;
    private String description;
    private Map<String, Object> context;
    private List<MemoryEntry> memories;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private List<String> relatedEpisodeIds;
    private double importance;
}

public enum EpisodeType {
    CONVERSATION,
    TASK_EXECUTION,
    ERROR,
    DECISION,
    MILESTONE
}
```

### 3.3 知识条目（Knowledge）

```java
@Data
public class Knowledge {
    private String id;
    private String category;
    private String title;
    private String content;
    private List<String> tags;
    private Map<String, Object> attributes;
    private float[] embedding;
    private double confidence;
    private String source;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String createdBy;
    private boolean verified;
}
```

### 3.4 记忆查询（MemoryQuery）

```java
@Data
public class MemoryQuery {
    private String query;
    private MemoryType type;
    private String userId;
    private String sessionId;
    private int topK;
    private double minScore;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Map<String, Object> filters;
    private boolean includeArchived;
}
```

## 4. 记忆存储与检索

### 4.1 短期记忆实现

```java
@Service
public class InMemoryShortTermMemory implements ShortTermMemory {
    
    private final Map<String, LinkedList<Message>> sessionMemories = new ConcurrentHashMap<>();
    private final int maxMessages;
    private final int maxTokens;
    private final TokenCounter tokenCounter;
    
    public InMemoryShortTermMemory(int maxMessages, int maxTokens) {
        this.maxMessages = maxMessages;
        this.maxTokens = maxTokens;
        this.tokenCounter = new TokenCounter();
    }
    
    @Override
    public void add(Message message) {
        String sessionId = message.getSessionId();
        LinkedList<Message> messages = sessionMemories.computeIfAbsent(
            sessionId, 
            k -> new LinkedList<>()
        );
        
        messages.addLast(message);
        
        // 检查并清理超出限制的消息
        cleanup(sessionId, messages);
    }
    
    @Override
    public List<Message> getRecent(int count) {
        return sessionMemories.values().stream()
            .flatMap(List::stream)
            .sorted(Comparator.comparing(Message::getCreatedAt).reversed())
            .limit(count)
            .sorted(Comparator.comparing(Message::getCreatedAt))
            .collect(Collectors.toList());
    }
    
    @Override
    public List<Message> getAll() {
        return sessionMemories.values().stream()
            .flatMap(List::stream)
            .sorted(Comparator.comparing(Message::getCreatedAt))
            .collect(Collectors.toList());
    }
    
    @Override
    public void clear() {
        sessionMemories.clear();
    }
    
    @Override
    public int size() {
        return sessionMemories.values().stream()
            .mapToInt(List::size)
            .sum();
    }
    
    @Override
    public int getTokenCount() {
        return sessionMemories.values().stream()
            .flatMap(List::stream)
            .mapToInt(msg -> tokenCounter.count(msg.getContent()))
            .sum();
    }
    
    private void cleanup(String sessionId, LinkedList<Message> messages) {
        // 检查消息数量
        while (messages.size() > maxMessages) {
            messages.removeFirst();
        }
        
        // 检查Token数量
        int totalTokens = messages.stream()
            .mapToInt(msg -> tokenCounter.count(msg.getContent()))
            .sum();
        
        while (totalTokens > maxTokens && !messages.isEmpty()) {
            Message removed = messages.removeFirst();
            totalTokens -= tokenCounter.count(removed.getContent());
        }
    }
}
```

### 4.2 长期记忆实现（基于向量数据库）

```java
@Service
public class VectorLongTermMemory implements LongTermMemory {
    
    private final EmbeddingModel embeddingModel;
    private final VectorStore vectorStore;
    private final MemoryRepository memoryRepository;
    private final MemoryCompressor compressor;
    
    @Override
    public String store(MemoryEntry entry) {
        // 生成向量
        float[] embedding = embeddingModel.embed(entry.getContent());
        entry.setEmbedding(embedding);
        
        // 计算重要性
        double importance = calculateImportance(entry);
        entry.setImportance(importance);
        
        // 存储到向量数据库
        String id = vectorStore.add(entry);
        entry.setId(id);
        
        // 存储到关系数据库
        memoryRepository.save(entry);
        
        return id;
    }
    
    @Override
    public List<MemoryEntry> retrieve(String query, int topK) {
        // 生成查询向量
        float[] queryEmbedding = embeddingModel.embed(query);
        
        // 向量检索
        List<VectorSearchResult> results = vectorStore.search(
            queryEmbedding, 
            topK
        );
        
        // 获取完整的记忆条目
        List<String> ids = results.stream()
            .map(VectorSearchResult::getId)
            .collect(Collectors.toList());
        
        return memoryRepository.findAllById(ids).stream()
            .sorted(Comparator.comparingDouble(MemoryEntry::getImportance).reversed())
            .collect(Collectors.toList());
    }
    
    @Override
    public List<MemoryEntry> retrieveByUser(String userId, int topK) {
        return memoryRepository.findByUserId(userId).stream()
            .sorted(Comparator.comparingDouble(MemoryEntry::getImportance).reversed())
            .limit(topK)
            .collect(Collectors.toList());
    }
    
    @Override
    public void update(String id, MemoryEntry entry) {
        // 重新生成向量
        float[] embedding = embeddingModel.embed(entry.getContent());
        entry.setEmbedding(embedding);
        
        // 更新向量数据库
        vectorStore.update(id, entry);
        
        // 更新关系数据库
        memoryRepository.save(entry);
    }
    
    @Override
    public void delete(String id) {
        vectorStore.delete(id);
        memoryRepository.deleteById(id);
    }
    
    private double calculateImportance(MemoryEntry entry) {
        double score = 0.0;
        
        // 访问频率
        score += Math.log(entry.getAccessCount() + 1) * 0.3;
        
        // 时间衰减
        long daysSinceCreation = ChronoUnit.DAYS.between(
            entry.getCreatedAt(),
            LocalDateTime.now()
        );
        score += Math.exp(-daysSinceCreation / 30.0) * 0.3;
        
        // 内容长度
        score += Math.min(entry.getContent().length() / 1000.0, 1.0) * 0.2;
        
        // 元数据权重
        if (entry.getMetadata() != null) {
            score += entry.getMetadata().size() * 0.05;
        }
        
        return score;
    }
}
```

### 4.3 情景记忆实现

```java
@Service
public class DatabaseEpisodicMemory implements EpisodicMemory {
    
    private final EpisodeRepository episodeRepository;
    private final MemoryRepository memoryRepository;
    private final EpisodeAnalyzer analyzer;
    
    @Override
    public String store(Episode episode) {
        // 分析情景
        analyzer.analyze(episode);
        
        // 存储情景
        episodeRepository.save(episode);
        
        // 存储相关的记忆
        if (episode.getMemories() != null) {
            episode.getMemories().forEach(memory -> {
                memory.setEpisodeId(episode.getId());
                memoryRepository.save(memory);
            });
        }
        
        return episode.getId();
    }
    
    @Override
    public List<Episode> retrieveByTimeRange(LocalDateTime start, LocalDateTime end) {
        return episodeRepository.findByStartTimeBetween(start, end);
    }
    
    @Override
    public List<Episode> retrieveBySession(String sessionId) {
        return episodeRepository.findBySessionId(sessionId);
    }
    
    @Override
    public List<Episode> retrieveByType(EpisodeType type) {
        return episodeRepository.findByType(type);
    }
    
    @Override
    public List<Episode> retrieveRelated(String episodeId) {
        Episode episode = episodeRepository.findById(episodeId).orElse(null);
        if (episode == null || episode.getRelatedEpisodeIds() == null) {
            return Collections.emptyList();
        }
        
        return episodeRepository.findAllById(episode.getRelatedEpisodeIds());
    }
}
```

### 4.4 语义记忆实现

```java
@Service
public class VectorSemanticMemory implements SemanticMemory {
    
    private final EmbeddingModel embeddingModel;
    private final VectorStore vectorStore;
    private final KnowledgeRepository knowledgeRepository;
    private final KnowledgeGraph graph;
    
    @Override
    public String store(Knowledge knowledge) {
        // 生成向量
        float[] embedding = embeddingModel.embed(knowledge.getContent());
        knowledge.setEmbedding(embedding);
        
        // 计算置信度
        double confidence = calculateConfidence(knowledge);
        knowledge.setConfidence(confidence);
        
        // 存储到向量数据库
        String id = vectorStore.add(knowledge);
        knowledge.setId(id);
        
        // 存储到关系数据库
        knowledgeRepository.save(knowledge);
        
        // 添加到知识图谱
        graph.addNode(knowledge);
        
        return id;
    }
    
    @Override
    public List<Knowledge> retrieve(String query, int topK) {
        // 生成查询向量
        float[] queryEmbedding = embeddingModel.embed(query);
        
        // 向量检索
        List<VectorSearchResult> results = vectorStore.search(
            queryEmbedding, 
            topK
        );
        
        // 获取完整的知识条目
        List<String> ids = results.stream()
            .map(VectorSearchResult::getId)
            .collect(Collectors.toList());
        
        return knowledgeRepository.findAllById(ids);
    }
    
    @Override
    public List<Knowledge> retrieveByCategory(String category) {
        return knowledgeRepository.findByCategory(category);
    }
    
    @Override
    public void update(String id, Knowledge knowledge) {
        // 重新生成向量
        float[] embedding = embeddingModel.embed(knowledge.getContent());
        knowledge.setEmbedding(embedding);
        
        // 更新向量数据库
        vectorStore.update(id, knowledge);
        
        // 更新关系数据库
        knowledgeRepository.save(knowledge);
        
        // 更新知识图谱
        graph.updateNode(knowledge);
    }
    
    @Override
    public void delete(String id) {
        vectorStore.delete(id);
        knowledgeRepository.deleteById(id);
        graph.removeNode(id);
    }
    
    private double calculateConfidence(Knowledge knowledge) {
        double score = 1.0;
        
        // 来源可信度
        if (knowledge.getSource() != null) {
            score *= getSourceTrustScore(knowledge.getSource());
        }
        
        // 验证状态
        if (knowledge.isVerified()) {
            score *= 1.2;
        }
        
        // 标签数量
        if (knowledge.getTags() != null) {
            score *= (1.0 + knowledge.getTags().size() * 0.1);
        }
        
        return Math.min(score, 1.0);
    }
}
```

## 5. 记忆压缩与优化

### 5.1 记忆压缩策略

```java
public interface MemoryCompressor {
    MemoryEntry compress(List<MemoryEntry> memories);
    MemoryEntry compressByTime(List<MemoryEntry> memories, int hours);
    MemoryEntry compressByImportance(List<MemoryEntry> memories, int topK);
    MemoryEntry compressBySummary(List<MemoryEntry> memories);
}

@Service
public class LLMBasedMemoryCompressor implements MemoryCompressor {
    
    private final ChatLanguageModel llm;
    
    @Override
    public MemoryEntry compress(List<MemoryEntry> memories) {
        String summary = generateSummary(memories);
        
        MemoryEntry compressed = new MemoryEntry();
        compressed.setType(MemoryType.SHORT_TERM);
        compressed.setContent(summary);
        compressed.setMetadata(Map.of(
            "compressed", true,
            "original_count", memories.size(),
            "compressed_at", LocalDateTime.now()
        ));
        
        return compressed;
    }
    
    @Override
    public MemoryEntry compressByTime(List<MemoryEntry> memories, int hours) {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(hours);
        List<MemoryEntry> oldMemories = memories.stream()
            .filter(m -> m.getCreatedAt().isBefore(cutoff))
            .collect(Collectors.toList());
        
        return compress(oldMemories);
    }
    
    @Override
    public MemoryEntry compressByImportance(List<MemoryEntry> memories, int topK) {
        List<MemoryEntry> importantMemories = memories.stream()
            .sorted(Comparator.comparingDouble(MemoryEntry::getImportance).reversed())
            .limit(topK)
            .collect(Collectors.toList());
        
        return compress(importantMemories);
    }
    
    @Override
    public MemoryEntry compressBySummary(List<MemoryEntry> memories) {
        String summary = generateDetailedSummary(memories);
        
        MemoryEntry compressed = new MemoryEntry();
        compressed.setType(MemoryType.SHORT_TERM);
        compressed.setContent(summary);
        compressed.setMetadata(Map.of(
            "compressed", true,
            "original_count", memories.size(),
            "compression_method", "detailed_summary"
        ));
        
        return compressed;
    }
    
    private String generateSummary(List<MemoryEntry> memories) {
        String memoriesText = memories.stream()
            .map(m -> String.format("[%s] %s", m.getCreatedAt(), m.getContent()))
            .collect(Collectors.joining("\n"));
        
        String prompt = String.format("""
            请用中文简要总结以下记忆内容（不超过200字）：
            
            %s
            """, memoriesText);
        
        return llm.generate(prompt);
    }
    
    private String generateDetailedSummary(List<MemoryEntry> memories) {
        String memoriesText = memories.stream()
            .map(m -> String.format("[%s] %s", m.getCreatedAt(), m.getContent()))
            .collect(Collectors.joining("\n"));
        
        String prompt = String.format("""
            请详细总结以下记忆内容，保留关键信息和上下文（不超过500字）：
            
            %s
            """, memoriesText);
        
        return llm.generate(prompt);
    }
}
```

### 5.2 记忆清理策略

```java
@Service
public class MemoryCleaner {
    
    private final MemoryRepository memoryRepository;
    private final VectorStore vectorStore;
    private final MemoryCompressor compressor;
    
    @Scheduled(cron = "0 0 * * * *")  // 每小时执行
    public void cleanExpiredMemories() {
        LocalDateTime now = LocalDateTime.now();
        
        // 清理过期的短期记忆
        List<MemoryEntry> expiredMemories = memoryRepository
            .findByTypeAndExpiresAtBefore(MemoryType.SHORT_TERM, now);
        
        expiredMemories.forEach(memory -> {
            vectorStore.delete(memory.getId());
            memoryRepository.delete(memory);
        });
        
        log.info("Cleaned {} expired memories", expiredMemories.size());
    }
    
    @Scheduled(cron = "0 0 2 * * *")  // 每天凌晨2点执行
    public void archiveOldMemories() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(30);
        
        // 归档30天前的记忆
        List<MemoryEntry> oldMemories = memoryRepository
            .findByTypeAndCreatedAtBeforeAndArchivedFalse(
                MemoryType.LONG_TERM, 
                cutoff
            );
        
        oldMemories.forEach(memory -> {
            memory.setArchived(true);
            memoryRepository.save(memory);
        });
        
        log.info("Archived {} old memories", oldMemories.size());
    }
    
    @Scheduled(cron = "0 0 3 * * *")  // 每天凌晨3点执行
    public void compressLargeMemories() {
        // 压缩超过1000条的记忆
        List<MemoryEntry> largeMemories = memoryRepository
            .findByUserIdAndCountGreaterThan(1000);
        
        largeMemories.forEach(memory -> {
            List<MemoryEntry> userMemories = memoryRepository
                .findByUserId(memory.getUserId());
            
            MemoryEntry compressed = compressor.compress(userMemories);
            compressed.setUserId(memory.getUserId());
            compressed.setType(MemoryType.LONG_TERM);
            
            // 删除旧记忆
            userMemories.forEach(m -> {
                vectorStore.delete(m.getId());
                memoryRepository.delete(m);
            });
            
            // 保存压缩后的记忆
            memoryRepository.save(compressed);
        });
        
        log.info("Compressed memories for {} users", largeMemories.size());
    }
}
```

## 6. 记忆关联与推理

### 6.1 记忆关联分析

```java
@Service
public class MemoryAssociationAnalyzer {
    
    private final EmbeddingModel embeddingModel;
    private final MemoryRepository memoryRepository;
    
    public List<MemoryAssociation> findAssociations(String memoryId, int topK) {
        MemoryEntry target = memoryRepository.findById(memoryId).orElse(null);
        if (target == null) {
            return Collections.emptyList();
        }
        
        // 计算与其他记忆的相似度
        List<MemoryEntry> allMemories = memoryRepository.findAll();
        
        return allMemories.stream()
            .filter(m -> !m.getId().equals(memoryId))
            .map(m -> {
                double similarity = cosineSimilarity(
                    target.getEmbedding(),
                    m.getEmbedding()
                );
                return new MemoryAssociation(memoryId, m.getId(), similarity);
            })
            .filter(assoc -> assoc.getSimilarity() > 0.7)
            .sorted(Comparator.comparingDouble(MemoryAssociation::getSimilarity).reversed())
            .limit(topK)
            .collect(Collectors.toList());
    }
    
    public List<MemoryCluster> clusterMemories(String userId) {
        List<MemoryEntry> memories = memoryRepository.findByUserId(userId);
        
        // 使用聚类算法（如K-means）
        return clusterMemories(memories);
    }
    
    private double cosineSimilarity(float[] v1, float[] v2) {
        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;
        
        for (int i = 0; i < v1.length; i++) {
            dotProduct += v1[i] * v2[i];
            norm1 += v1[i] * v1[i];
            norm2 += v2[i] * v2[i];
        }
        
        return dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }
}

@Data
@AllArgsConstructor
public class MemoryAssociation {
    private String sourceId;
    private String targetId;
    private double similarity;
}

@Data
public class MemoryCluster {
    private String id;
    private List<String> memoryIds;
    private String topic;
    private double confidence;
}
```

### 6.2 记忆推理

```java
@Service
public class MemoryReasoner {
    
    private final ChatLanguageModel llm;
    private final LongTermMemory longTermMemory;
    private final SemanticMemory semanticMemory;
    
    public String reason(String query, String userId) {
        // 检索相关记忆
        List<MemoryEntry> longTermMemories = longTermMemory.retrieveByUser(userId, 5);
        List<Knowledge> semanticMemories = semanticMemory.retrieve(query, 5);
        
        // 构建推理提示
        String prompt = buildReasoningPrompt(query, longTermMemories, semanticMemories);
        
        // 执行推理
        return llm.generate(prompt);
    }
    
    private String buildReasoningPrompt(String query, 
                                       List<MemoryEntry> longTermMemories,
                                       List<Knowledge> semanticMemories) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("基于以下信息进行推理：\n\n");
        
        prompt.append("## 用户历史记忆\n");
        longTermMemories.forEach(m -> {
            prompt.append(String.format("- %s\n", m.getContent()));
        });
        
        prompt.append("\n## 相关知识\n");
        semanticMemories.forEach(k -> {
            prompt.append(String.format("- %s\n", k.getContent()));
        });
        
        prompt.append(String.format("\n## 问题\n%s\n\n请给出推理结果。", query));
        
        return prompt.toString();
    }
}
```

## 7. 与LangChain4j集成

### 7.1 ChatMemory适配器

```java
@Service
public class LangChain4jMemoryAdapter implements ChatMemory {
    
    private final ShortTermMemory shortTermMemory;
    private final LongTermMemory longTermMemory;
    private final String sessionId;
    
    public LangChain4jMemoryAdapter(ShortTermMemory shortTermMemory,
                                     LongTermMemory longTermMemory,
                                     String sessionId) {
        this.shortTermMemory = shortTermMemory;
        this.longTermMemory = longTermMemory;
        this.sessionId = sessionId;
    }
    
    @Override
    public void add(ChatMessage message) {
        MemoryEntry entry = convertToMemoryEntry(message);
        shortTermMemory.add(convertToMessage(message));
        
        // 如果是重要消息，也存储到长期记忆
        if (isImportant(message)) {
            longTermMemory.store(entry);
        }
    }
    
    @Override
    public List<ChatMessage> messages() {
        return shortTermMemory.getAll().stream()
            .map(this::convertToChatMessage)
            .collect(Collectors.toList());
    }
    
    @Override
    public void clear() {
        shortTermMemory.clear();
    }
    
    @Override
    public ChatMemoryId id() {
        return ChatMemoryId.of(sessionId);
    }
    
    private boolean isImportant(ChatMessage message) {
        // 判断消息是否重要
        if (message instanceof UserMessage) {
            String content = ((UserMessage) message).singleText();
            return content.length() > 50 || 
                   content.contains("重要") || 
                   content.contains("记住");
        }
        return false;
    }
}
```

### 7.2 配置类

```java
@Configuration
public class MemoryConfig {
    
    @Bean
    public EmbeddingModel embeddingModel() {
        return OpenAiEmbeddingModel.builder()
            .apiKey(System.getenv("OPENAI_API_KEY"))
            .modelName("text-embedding-ada-002")
            .build();
    }
    
    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel) {
        return new MilvusVectorStore(embeddingModel);
    }
    
    @Bean
    public ShortTermMemory shortTermMemory() {
        return new InMemoryShortTermMemory(100, 4000);
    }
    
    @Bean
    public LongTermMemory longTermMemory(EmbeddingModel embeddingModel,
                                        VectorStore vectorStore) {
        return new VectorLongTermMemory(embeddingModel, vectorStore);
    }
    
    @Bean
    public EpisodicMemory episodicMemory() {
        return new DatabaseEpisodicMemory();
    }
    
    @Bean
    public SemanticMemory semanticMemory(EmbeddingModel embeddingModel,
                                       VectorStore vectorStore) {
        return new VectorSemanticMemory(embeddingModel, vectorStore);
    }
}
```

## 8. 数据库设计

### 8.1 记忆表

```sql
CREATE TABLE memories (
    id VARCHAR(100) PRIMARY KEY,
    type ENUM('SHORT_TERM', 'LONG_TERM', 'EPISODIC', 'SEMANTIC') NOT NULL,
    user_id VARCHAR(100) NOT NULL,
    session_id VARCHAR(100),
    episode_id VARCHAR(100),
    content TEXT NOT NULL,
    metadata JSON,
    importance DOUBLE DEFAULT 0.0,
    access_count INT DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_accessed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NULL,
    archived BOOLEAN DEFAULT FALSE,
    
    INDEX idx_user_id (user_id),
    INDEX idx_session_id (session_id),
    INDEX idx_episode_id (episode_id),
    INDEX idx_type (type),
    INDEX idx_importance (importance),
    INDEX idx_created_at (created_at),
    INDEX idx_expires_at (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 8.2 情景表

```sql
CREATE TABLE episodes (
    id VARCHAR(100) PRIMARY KEY,
    user_id VARCHAR(100) NOT NULL,
    session_id VARCHAR(100),
    task_id VARCHAR(100),
    type ENUM('CONVERSATION', 'TASK_EXECUTION', 'ERROR', 'DECISION', 'MILESTONE') NOT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    context JSON,
    start_time TIMESTAMP NOT NULL,
    end_time TIMESTAMP,
    importance DOUBLE DEFAULT 0.0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    INDEX idx_user_id (user_id),
    INDEX idx_session_id (session_id),
    INDEX idx_task_id (task_id),
    INDEX idx_type (type),
    INDEX idx_start_time (start_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 8.3 知识表

```sql
CREATE TABLE knowledge (
    id VARCHAR(100) PRIMARY KEY,
    category VARCHAR(100) NOT NULL,
    title VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    tags JSON,
    attributes JSON,
    confidence DOUBLE DEFAULT 0.0,
    source VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    verified BOOLEAN DEFAULT FALSE,
    
    INDEX idx_category (category),
    INDEX idx_source (source),
    INDEX idx_created_by (created_by),
    INDEX idx_verified (verified)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 8.4 情景关联表

```sql
CREATE TABLE episode_relations (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    source_episode_id VARCHAR(100) NOT NULL,
    target_episode_id VARCHAR(100) NOT NULL,
    relation_type ENUM('SEQUENTIAL', 'CAUSAL', 'RELATED') NOT NULL,
    strength DOUBLE DEFAULT 0.0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    INDEX idx_source_episode_id (source_episode_id),
    INDEX idx_target_episode_id (target_episode_id),
    
    FOREIGN KEY (source_episode_id) REFERENCES episodes(id) ON DELETE CASCADE,
    FOREIGN KEY (target_episode_id) REFERENCES episodes(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

## 9. API设计

### 9.1 记忆存储API

```java
@RestController
@RequestMapping("/api/memories")
public class MemoryController {
    
    @Autowired
    private LongTermMemory longTermMemory;
    
    @PostMapping
    public ResponseEntity<String> storeMemory(@RequestBody MemoryEntry entry) {
        String id = longTermMemory.store(entry);
        return ResponseEntity.ok(id);
    }
}
```

### 9.2 记忆检索API

```java
@PostMapping("/retrieve")
public ResponseEntity<List<MemoryEntry>> retrieveMemories(
    @RequestBody MemoryQuery query
) {
    List<MemoryEntry> memories = longTermMemory.retrieve(
        query.getQuery(),
        query.getTopK()
    );
    return ResponseEntity.ok(memories);
}

@GetMapping("/user/{userId}")
public ResponseEntity<List<MemoryEntry>> getUserMemories(
    @PathVariable String userId,
    @RequestParam(defaultValue = "10") int topK
) {
    List<MemoryEntry> memories = longTermMemory.retrieveByUser(userId, topK);
    return ResponseEntity.ok(memories);
}
```

### 9.3 情景API

```java
@RestController
@RequestMapping("/api/episodes")
public class EpisodeController {
    
    @Autowired
    private EpisodicMemory episodicMemory;
    
    @PostMapping
    public ResponseEntity<String> storeEpisode(@RequestBody Episode episode) {
        String id = episodicMemory.store(episode);
        return ResponseEntity.ok(id);
    }
    
    @GetMapping("/session/{sessionId}")
    public ResponseEntity<List<Episode>> getSessionEpisodes(
        @PathVariable String sessionId
    ) {
        List<Episode> episodes = episodicMemory.retrieveBySession(sessionId);
        return ResponseEntity.ok(episodes);
    }
}
```

### 9.4 知识API

```java
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {
    
    @Autowired
    private SemanticMemory semanticMemory;
    
    @PostMapping
    public ResponseEntity<String> storeKnowledge(@RequestBody Knowledge knowledge) {
        String id = semanticMemory.store(knowledge);
        return ResponseEntity.ok(id);
    }
    
    @PostMapping("/retrieve")
public ResponseEntity<List<Knowledge>> retrieveKnowledge(
    @RequestBody String query,
    @RequestParam(defaultValue = "5") int topK
) {
    List<Knowledge> knowledgeList = semanticMemory.retrieve(query, topK);
    return ResponseEntity.ok(knowledgeList);
    }
}
```

## 10. 最佳实践

### 10.1 记忆存储
- 为重要的对话和事件创建情景记录
- 定期压缩和归档旧记忆
- 使用合适的元数据标记记忆
- 设置合理的过期时间

### 10.2 记忆检索
- 结合多种检索策略（向量、关键词、时间）
- 使用相关性过滤提高检索质量
- 考虑用户的访问模式优化检索
- 缓存常用的检索结果

### 10.3 记忆管理
- 定期清理过期和低价值记忆
- 监控记忆存储和检索性能
- 分析记忆访问模式优化存储策略
- 提供记忆导出和导入功能

### 10.4 隐私与安全
- 加密敏感的记忆内容
- 实现记忆访问控制
- 提供记忆删除和匿名化功能
- 遵循数据保护法规

## 11. 性能优化

### 11.1 向量检索优化
- 使用合适的索引算法（HNSW、IVF）
- 调整索引参数平衡精度和性能
- 实现向量检索的批量处理
- 使用向量检索的缓存

### 11.2 记忆缓存
- 实现多级缓存策略
- 使用Redis缓存热点记忆
- 实现智能缓存淘汰策略
- 监控缓存命中率

### 11.3 批量操作
- 支持批量存储记忆
- 实现批量检索优化
- 使用异步处理提高吞吐量
- 优化数据库批量操作

## 12. 监控与告警

### 12.1 关键指标
- 记忆存储速率
- 记忆检索延迟
- 向量检索准确率
- 记忆压缩比例
- 缓存命中率

### 12.2 告警规则
- 记忆检索延迟超过阈值
- 向量检索准确率低于阈值
- 记忆存储失败率过高
- 缓存命中率过低

## 13. 实施计划

### Phase 1: 基础框架（2周）
- 数据模型设计与建表
- 短期记忆实现
- 向量数据库集成
- 基础检索功能

### Phase 2: 长期记忆（2周）
- 长期记忆实现
- 情景记忆实现
- 语义记忆实现
- 记忆压缩功能

### Phase 3: 高级特性（2周）
- 记忆关联分析
- 记忆推理
- 记忆清理策略
- 性能优化

### Phase 4: 集成与测试（1周）
- LangChain4j集成
- API实现
- 单元测试
- 集成测试

### Phase 5: 优化与部署（1周）
- 性能调优
- 监控告警
- 文档完善
- 部署上线

## 14. 扩展方向

### 14.1 多模态记忆
- 支持图像记忆
- 支持音频记忆
- 支持视频记忆
- 多模态检索

### 14.2 记忆共享
- 记忆分享功能
- 记忆协作编辑
- 记忆版本控制
- 记忆权限管理

### 14.3 记忆可视化
- 记忆图谱展示
- 记忆时间线
- 记忆聚类可视化
- 记忆关联图

### 14.4 记忆增强
- 记忆预测
- 记忆推荐
- 记忆自动分类
- 记忆智能标签
