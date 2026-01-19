# 记忆适配层设计文档

## 1. 概述

### 1.1 设计目标
- **接口抽象**: 定义统一的记忆接口，隐藏底层实现细节
- **可插拔**: 支持多种记忆实现（MEM0、自研、第三方）
- **易切换**: 通过配置即可切换不同的记忆实现
- **向后兼容**: 保持接口稳定，不影响上层应用
- **可扩展**: 支持自定义记忆实现

### 1.2 核心原则
- **依赖倒置**: 高层模块不依赖低层模块，都依赖抽象
- **开闭原则**: 对扩展开放，对修改关闭
- **里氏替换**: 子类可以替换父类而不影响正确性
- **接口隔离**: 接口应该小而专一

### 1.3 架构层次

```
┌─────────────────────────────────────────────────────────┐
│                    应用层                              │
│              (LangChain4j + 业务逻辑)                   │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│                  记忆适配层                              │
│              (Memory Adapter Layer)                    │
│                                                         │
│  ┌──────────────────────────────────────────────┐      │
│  │         MemoryService (统一接口)              │      │
│  └──────────────────────────────────────────────┘      │
│                          ↓                              │
│  ┌──────────────┬──────────────┬──────────────┐ │
│  │  MEM0 适配器  │  自研适配器    │  其他适配器    │ │
│  └──────────────┴──────────────┴──────────────┘ │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│                  存储层                              │
│         (MEM0 / Vector DB / Relational DB)            │
└─────────────────────────────────────────────────────────┘
```

## 2. 核心接口设计

### 2.1 记忆服务接口（MemoryService）

```java
public interface MemoryService {
    
    /**
     * 添加记忆
     */
    String addMemory(AddMemoryRequest request);
    
    /**
     * 批量添加记忆
     */
    List<String> addMemories(List<AddMemoryRequest> requests);
    
    /**
     * 搜索记忆
     */
    List<MemoryEntry> searchMemories(SearchMemoryRequest request);
    
    /**
     * 获取所有记忆
     */
    List<MemoryEntry> getAllMemories(String userId);
    
    /**
     * 获取记忆详情
     */
    MemoryEntry getMemory(String memoryId);
    
    /**
     * 更新记忆
     */
    void updateMemory(String memoryId, UpdateMemoryRequest request);
    
    /**
     * 删除记忆
     */
    void deleteMemory(String memoryId);
    
    /**
     * 删除所有记忆
     */
    void deleteAllMemories(String userId);
    
    /**
     * 获取记忆统计
     */
    MemoryStats getStats(String userId);
}
```

### 2.2 短期记忆接口（ShortTermMemoryService）

```java
public interface ShortTermMemoryService {
    
    /**
     * 添加消息
     */
    void addMessage(String sessionId, ChatMessage message);
    
    /**
     * 获取最近消息
     */
    List<ChatMessage> getRecentMessages(String sessionId, int count);
    
    /**
     * 获取所有消息
     */
    List<ChatMessage> getAllMessages(String sessionId);
    
    /**
     * 清空消息
     */
    void clearMessages(String sessionId);
    
    /**
     * 获取消息数量
     */
    int getMessageCount(String sessionId);
    
    /**
     * 获取 Token 数量
     */
    int getTokenCount(String sessionId);
}
```

### 2.3 情景记忆接口（EpisodicMemoryService）

```java
public interface EpisodicMemoryService {
    
    /**
     * 创建情景
     */
    String createEpisode(CreateEpisodeRequest request);
    
    /**
     * 获取情景
     */
    Episode getEpisode(String episodeId);
    
    /**
     * 搜索情景
     */
    List<Episode> searchEpisodes(SearchEpisodeRequest request);
    
    /**
     * 获取用户情景
     */
    List<Episode> getUserEpisodes(String userId);
    
    /**
     * 获取会话情景
     */
    List<Episode> getSessionEpisodes(String sessionId);
    
    /**
     * 更新情景
     */
    void updateEpisode(String episodeId, UpdateEpisodeRequest request);
    
    /**
     * 删除情景
     */
    void deleteEpisode(String episodeId);
}
```

### 2.4 语义记忆接口（SemanticMemoryService）

```java
public interface SemanticMemoryService {
    
    /**
     * 添加知识
     */
    String addKnowledge(AddKnowledgeRequest request);
    
    /**
     * 批量添加知识
     */
    List<String> addKnowledges(List<AddKnowledgeRequest> requests);
    
    /**
     * 搜索知识
     */
    List<KnowledgeEntry> searchKnowledge(SearchKnowledgeRequest request);
    
    /**
     * 获取知识
     */
    KnowledgeEntry getKnowledge(String knowledgeId);
    
    /**
     * 按分类获取知识
     */
    List<KnowledgeEntry> getKnowledgeByCategory(String category);
    
    /**
     * 更新知识
     */
    void updateKnowledge(String knowledgeId, UpdateKnowledgeRequest request);
    
    /**
     * 删除知识
     */
    void deleteKnowledge(String knowledgeId);
    
    /**
     * 验证知识
     */
    void verifyKnowledge(String knowledgeId, boolean verified);
}
```

## 3. 数据模型设计

### 3.1 请求/响应模型

#### AddMemoryRequest
```java
@Data
public class AddMemoryRequest {
    private String content;
    private String userId;
    private String sessionId;
    private Map<String, Object> metadata;
    private MemoryType type;
    private Double importance;
}
```

#### UpdateMemoryRequest
```java
@Data
public class UpdateMemoryRequest {
    private String content;
    private Map<String, Object> metadata;
    private Double importance;
}
```

#### SearchMemoryRequest
```java
@Data
public class SearchMemoryRequest {
    private String query;
    private String userId;
    private String sessionId;
    private MemoryType type;
    private Integer topK;
    private Double minScore;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Map<String, Object> filters;
    private Boolean includeArchived;
}
```

#### CreateEpisodeRequest
```java
@Data
public class CreateEpisodeRequest {
    private String userId;
    private String sessionId;
    private String taskId;
    private EpisodeType type;
    private String title;
    private String description;
    private Map<String, Object> context;
    private List<String> relatedEpisodeIds;
}
```

#### UpdateEpisodeRequest
```java
@Data
public class UpdateEpisodeRequest {
    private String title;
    private String description;
    private Map<String, Object> context;
    private Double importance;
}
```

#### SearchEpisodeRequest
```java
@Data
public class SearchEpisodeRequest {
    private String userId;
    private String sessionId;
    private EpisodeType type;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer topK;
}
```

#### AddKnowledgeRequest
```java
@Data
public class AddKnowledgeRequest {
    private String category;
    private String title;
    private String content;
    private List<String> tags;
    private Map<String, Object> attributes;
    private String source;
    private String createdBy;
}
```

#### UpdateKnowledgeRequest
```java
@Data
public class UpdateKnowledgeRequest {
    private String category;
    private String title;
    private String content;
    private List<String> tags;
    private Map<String, Object> attributes;
}
```

#### SearchKnowledgeRequest
```java
@Data
public class SearchKnowledgeRequest {
    private String query;
    private String category;
    private List<String> tags;
    private Integer topK;
    private Double minConfidence;
    private Boolean verifiedOnly;
}
```

### 3.2 核心数据模型

#### MemoryEntry
```java
@Data
public class MemoryEntry {
    private String id;
    private MemoryType type;
    private String userId;
    private String sessionId;
    private String content;
    private Map<String, Object> metadata;
    private Double importance;
    private Integer accessCount;
    private LocalDateTime createdAt;
    private LocalDateTime lastAccessedAt;
    private LocalDateTime expiresAt;
    private Boolean archived;
}
```

#### Episode
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
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private List<String> relatedEpisodeIds;
    private Double importance;
    private LocalDateTime createdAt;
}
```

#### KnowledgeEntry
```java
@Data
public class KnowledgeEntry {
    private String id;
    private String category;
    private String title;
    private String content;
    private List<String> tags;
    private Map<String, Object> attributes;
    private Double confidence;
    private String source;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String createdBy;
    private Boolean verified;
}
```

#### MemoryStats
```java
@Data
public class MemoryStats {
    private String userId;
    private Long totalMemories;
    private Long activeMemories;
    private Long archivedMemories;
    private Double avgImportance;
    private LocalDateTime lastAccessAt;
    private Map<MemoryType, Long> memoriesByType;
}
```

### 3.3 枚举类型

#### MemoryType
```java
public enum MemoryType {
    SHORT_TERM,
    LONG_TERM,
    EPISODIC,
    SEMANTIC
}
```

#### EpisodeType
```java
public enum EpisodeType {
    CONVERSATION,
    TASK_EXECUTION,
    ERROR,
    DECISION,
    MILESTONE
}
```

## 4. MEM0 适配器实现

### 4.1 MEM0 客户端

```java
@Service
public class Mem0Client {
    
    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String apiKey;
    
    public Mem0Client(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.restTemplate = new RestTemplate();
        
        // 配置请求头
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null && !apiKey.isEmpty()) {
            headers.set("Authorization", "Bearer " + apiKey);
        }
        restTemplate.setInterceptors((request, body, execution) -> {
            request.getHeaders().addAll(headers);
            return execution.execute(request, body);
        });
    }
    
    /**
     * 添加记忆
     */
    public Mem0Memory addMemory(String content, String userId, Map<String, Object> metadata) {
        String url = baseUrl + "/v1/memories";
        
        Map<String, Object> request = new HashMap<>();
        request.put("content", content);
        request.put("user_id", userId);
        if (metadata != null) {
            request.put("metadata", metadata);
        }
        
        return restTemplate.postForObject(url, request, Mem0Memory.class);
    }
    
    /**
     * 批量添加记忆
     */
    public List<Mem0Memory> addMemories(List<String> contents, String userId) {
        String url = baseUrl + "/v1/memories/batch";
        
        List<Map<String, Object>> memories = contents.stream()
            .map(content -> {
                Map<String, Object> mem = new HashMap<>();
                mem.put("content", content);
                mem.put("user_id", userId);
                return mem;
            })
            .collect(Collectors.toList());
        
        Map<String, Object> request = new HashMap<>();
        request.put("memories", memories);
        
        Mem0Memory[] response = restTemplate.postForObject(url, request, Mem0Memory[].class);
        return Arrays.asList(response);
    }
    
    /**
     * 搜索记忆
     */
    public List<Mem0Memory> searchMemories(String query, String userId, int limit) {
        String url = baseUrl + "/v1/memories/search";
        
        Map<String, Object> request = new HashMap<>();
        request.put("query", query);
        request.put("user_id", userId);
        request.put("limit", limit);
        
        Mem0Memory[] response = restTemplate.postForObject(url, request, Mem0Memory[].class);
        return Arrays.asList(response);
    }
    
    /**
     * 获取所有记忆
     */
    public List<Mem0Memory> getAllMemories(String userId) {
        String url = baseUrl + "/v1/memories?user_id=" + userId;
        
        Mem0Memory[] response = restTemplate.getForObject(url, Mem0Memory[].class);
        return Arrays.asList(response);
    }
    
    /**
     * 获取记忆详情
     */
    public Mem0Memory getMemory(String memoryId) {
        String url = baseUrl + "/v1/memories/" + memoryId;
        return restTemplate.getForObject(url, Mem0Memory.class);
    }
    
    /**
     * 更新记忆
     */
    public Mem0Memory updateMemory(String memoryId, String content) {
        String url = baseUrl + "/v1/memories/" + memoryId;
        
        Map<String, Object> request = new HashMap<>();
        request.put("content", content);
        
        return restTemplate.patchForObject(url, request, Mem0Memory.class);
    }
    
    /**
     * 删除记忆
     */
    public void deleteMemory(String memoryId) {
        String url = baseUrl + "/v1/memories/" + memoryId;
        restTemplate.delete(url);
    }
    
    /**
     * 删除所有记忆
     */
    public void deleteAllMemories(String userId) {
        String url = baseUrl + "/v1/memories?user_id=" + userId;
        restTemplate.delete(url);
    }
}

@Data
public class Mem0Memory {
    private String id;
    private String content;
    private String userId;
    private Map<String, Object> metadata;
    private Double score;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

### 4.2 MEM0 适配器

```java
@Service
@ConditionalOnProperty(name = "memory.provider", havingValue = "mem0")
public class Mem0MemoryAdapter implements MemoryService {
    
    private final Mem0Client mem0Client;
    private final MemoryMapper mapper;
    
    public Mem0MemoryAdapter(Mem0Client mem0Client, MemoryMapper mapper) {
        this.mem0Client = mem0Client;
        this.mapper = mapper;
    }
    
    @Override
    public String addMemory(AddMemoryRequest request) {
        Mem0Memory mem0Memory = mem0Client.addMemory(
            request.getContent(),
            request.getUserId(),
            request.getMetadata()
        );
        return mem0Memory.getId();
    }
    
    @Override
    public List<String> addMemories(List<AddMemoryRequest> requests) {
        Map<String, List<AddMemoryRequest>> grouped = requests.stream()
            .collect(Collectors.groupingBy(AddMemoryRequest::getUserId));
        
        List<String> allIds = new ArrayList<>();
        for (Map.Entry<String, List<AddMemoryRequest>> entry : grouped.entrySet()) {
            String userId = entry.getKey();
            List<String> contents = entry.getValue().stream()
                .map(AddMemoryRequest::getContent)
                .collect(Collectors.toList());
            
            List<Mem0Memory> mem0Memories = mem0Client.addMemories(contents, userId);
            allIds.addAll(mem0Memories.stream()
                .map(Mem0Memory::getId)
                .collect(Collectors.toList()));
        }
        
        return allIds;
    }
    
    @Override
    public List<MemoryEntry> searchMemories(SearchMemoryRequest request) {
        List<Mem0Memory> mem0Memories;
        
        if (request.getQuery() != null) {
            // 语义搜索
            mem0Memories = mem0Client.searchMemories(
                request.getQuery(),
                request.getUserId(),
                request.getTopK() != null ? request.getTopK() : 10
            );
        } else {
            // 获取所有记忆
            mem0Memories = mem0Client.getAllMemories(request.getUserId());
        }
        
        // 转换并过滤
        return mem0Memories.stream()
            .map(mapper::toMemoryEntry)
            .filter(entry -> {
                // 应用过滤条件
                if (request.getType() != null && !entry.getType().equals(request.getType())) {
                    return false;
                }
                if (request.getMinScore() != null && entry.getImportance() < request.getMinScore()) {
                    return false;
                }
                if (request.getStartTime() != null && entry.getCreatedAt().isBefore(request.getStartTime())) {
                    return false;
                }
                if (request.getEndTime() != null && entry.getCreatedAt().isAfter(request.getEndTime())) {
                    return false;
                }
                if (request.getIncludeArchived() != null && !request.getIncludeArchived() && entry.getArchived()) {
                    return false;
                }
                return true;
            })
            .limit(request.getTopK() != null ? request.getTopK() : Integer.MAX_VALUE)
            .collect(Collectors.toList());
    }
    
    @Override
    public List<MemoryEntry> getAllMemories(String userId) {
        List<Mem0Memory> mem0Memories = mem0Client.getAllMemories(userId);
        return mem0Memories.stream()
            .map(mapper::toMemoryEntry)
            .collect(Collectors.toList());
    }
    
    @Override
    public MemoryEntry getMemory(String memoryId) {
        Mem0Memory mem0Memory = mem0Client.getMemory(memoryId);
        return mapper.toMemoryEntry(mem0Memory);
    }
    
    @Override
    public void updateMemory(String memoryId, UpdateMemoryRequest request) {
        if (request.getContent() != null) {
            mem0Client.updateMemory(memoryId, request.getContent());
        }
        // MEM0 不支持直接更新 metadata，需要通过其他方式实现
    }
    
    @Override
    public void deleteMemory(String memoryId) {
        mem0Client.deleteMemory(memoryId);
    }
    
    @Override
    public void deleteAllMemories(String userId) {
        mem0Client.deleteAllMemories(userId);
    }
    
    @Override
    public MemoryStats getStats(String userId) {
        List<MemoryEntry> memories = getAllMemories(userId);
        
        MemoryStats stats = new MemoryStats();
        stats.setUserId(userId);
        stats.setTotalMemories((long) memories.size());
        stats.setActiveMemories(memories.stream()
            .filter(m -> !m.getArchived())
            .count());
        stats.setArchivedMemories(memories.stream()
            .filter(MemoryEntry::getArchived)
            .count());
        stats.setAvgImportance(memories.stream()
            .mapToDouble(m -> m.getImportance() != null ? m.getImportance() : 0.0)
            .average()
            .orElse(0.0));
        stats.setLastAccessAt(memories.stream()
            .map(MemoryEntry::getLastAccessedAt)
            .max(LocalDateTime::compareTo)
            .orElse(null));
        
        Map<MemoryType, Long> byType = memories.stream()
            .collect(Collectors.groupingBy(
                MemoryEntry::getType,
                Collectors.counting()
            ));
        stats.setMemoriesByType(byType);
        
        return stats;
    }
}
```

### 4.3 短期记忆适配器（基于 LangChain4j）

```java
@Service
@ConditionalOnProperty(name = "memory.provider", havingValue = "mem0")
public class LangChain4jShortTermMemoryAdapter implements ShortTermMemoryService {
    
    private final Map<String, ChatMemory> sessionMemories = new ConcurrentHashMap<>();
    private final int maxMessages;
    private final int maxTokens;
    private final TokenCounter tokenCounter;
    
    public LangChain4jShortTermMemoryAdapter(
        @Value("${memory.short-term.max-messages:100}") int maxMessages,
        @Value("${memory.short-term.max-tokens:4000}") int maxTokens
    ) {
        this.maxMessages = maxMessages;
        this.maxTokens = maxTokens;
        this.tokenCounter = new TokenCounter();
    }
    
    @Override
    public void addMessage(String sessionId, ChatMessage message) {
        ChatMemory memory = sessionMemories.computeIfAbsent(
            sessionId,
            id -> MessageWindowChatMemory.builder()
                .maxMessages(maxMessages)
                .id(id)
                .build()
        );
        
        memory.add(message);
    }
    
    @Override
    public List<ChatMessage> getRecentMessages(String sessionId, int count) {
        ChatMemory memory = sessionMemories.get(sessionId);
        if (memory == null) {
            return Collections.emptyList();
        }
        
        List<ChatMessage> allMessages = memory.messages();
        int fromIndex = Math.max(0, allMessages.size() - count);
        return allMessages.subList(fromIndex, allMessages.size());
    }
    
    @Override
    public List<ChatMessage> getAllMessages(String sessionId) {
        ChatMemory memory = sessionMemories.get(sessionId);
        return memory != null ? memory.messages() : Collections.emptyList();
    }
    
    @Override
    public void clearMessages(String sessionId) {
        ChatMemory memory = sessionMemories.get(sessionId);
        if (memory != null) {
            memory.clear();
        }
    }
    
    @Override
    public int getMessageCount(String sessionId) {
        ChatMemory memory = sessionMemories.get(sessionId);
        return memory != null ? memory.messages().size() : 0;
    }
    
    @Override
    public int getTokenCount(String sessionId) {
        ChatMemory memory = sessionMemories.get(sessionId);
        if (memory == null) {
            return 0;
        }
        
        return memory.messages().stream()
            .mapToInt(msg -> tokenCounter.count(msg.text()))
            .sum();
    }
}
```

### 4.4 情景记忆适配器（基于自研）

```java
@Service
@ConditionalOnProperty(name = "memory.provider", havingValue = "mem0")
public class DatabaseEpisodicMemoryAdapter implements EpisodicMemoryService {
    
    private final EpisodeRepository episodeRepository;
    private final EpisodeMapper mapper;
    
    @Override
    public String createEpisode(CreateEpisodeRequest request) {
        Episode episode = mapper.toEpisode(request);
        episode.setCreatedAt(LocalDateTime.now());
        episode.setStartTime(LocalDateTime.now());
        
        Episode saved = episodeRepository.save(episode);
        return saved.getId();
    }
    
    @Override
    public Episode getEpisode(String episodeId) {
        return episodeRepository.findById(episodeId).orElse(null);
    }
    
    @Override
    public List<Episode> searchEpisodes(SearchEpisodeRequest request) {
        Specification<Episode> spec = Specification.where(null);
        
        if (request.getUserId() != null) {
            spec = spec.and((root, query, cb) ->
                cb.equal(root.get("userId"), request.getUserId())
            );
        }
        
        if (request.getSessionId() != null) {
            spec = spec.and((root, query, cb) ->
                cb.equal(root.get("sessionId"), request.getSessionId())
            );
        }
        
        if (request.getType() != null) {
            spec = spec.and((root, query, cb) ->
                cb.equal(root.get("type"), request.getType())
            );
        }
        
        if (request.getStartTime() != null) {
            spec = spec.and((root, query, cb) ->
                cb.greaterThanOrEqualTo(root.get("startTime"), request.getStartTime())
            );
        }
        
        if (request.getEndTime() != null) {
            spec = spec.and((root, query, cb) ->
                cb.lessThanOrEqualTo(root.get("startTime"), request.getEndTime())
            );
        }
        
        List<Episode> episodes = episodeRepository.findAll(spec);
        
        if (request.getTopK() != null) {
            episodes = episodes.stream()
                .limit(request.getTopK())
                .collect(Collectors.toList());
        }
        
        return episodes;
    }
    
    @Override
    public List<Episode> getUserEpisodes(String userId) {
        return episodeRepository.findByUserIdOrderByStartTimeDesc(userId);
    }
    
    @Override
    public List<Episode> getSessionEpisodes(String sessionId) {
        return episodeRepository.findBySessionIdOrderByStartTimeAsc(sessionId);
    }
    
    @Override
    public void updateEpisode(String episodeId, UpdateEpisodeRequest request) {
        Episode episode = episodeRepository.findById(episodeId).orElse(null);
        if (episode == null) {
            throw new IllegalArgumentException("Episode not found: " + episodeId);
        }
        
        if (request.getTitle() != null) {
            episode.setTitle(request.getTitle());
        }
        if (request.getDescription() != null) {
            episode.setDescription(request.getDescription());
        }
        if (request.getContext() != null) {
            episode.setContext(request.getContext());
        }
        if (request.getImportance() != null) {
            episode.setImportance(request.getImportance());
        }
        
        episodeRepository.save(episode);
    }
    
    @Override
    public void deleteEpisode(String episodeId) {
        episodeRepository.deleteById(episodeId);
    }
}
```

### 4.5 语义记忆适配器（基于自研）

```java
@Service
@ConditionalOnProperty(name = "memory.provider", havingValue = "mem0")
public class VectorSemanticMemoryAdapter implements SemanticMemoryService {
    
    private final KnowledgeRepository knowledgeRepository;
    private final EmbeddingModel embeddingModel;
    private final VectorStore vectorStore;
    private final KnowledgeMapper mapper;
    
    @Override
    public String addKnowledge(AddKnowledgeRequest request) {
        Knowledge knowledge = mapper.toKnowledge(request);
        
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
        Knowledge saved = knowledgeRepository.save(knowledge);
        return saved.getId();
    }
    
    @Override
    public List<String> addKnowledges(List<AddKnowledgeRequest> requests) {
        List<String> ids = new ArrayList<>();
        for (AddKnowledgeRequest request : requests) {
            ids.add(addKnowledge(request));
        }
        return ids;
    }
    
    @Override
    public List<KnowledgeEntry> searchKnowledge(SearchKnowledgeRequest request) {
        List<Knowledge> knowledges;
        
        if (request.getQuery() != null) {
            // 向量搜索
            float[] queryEmbedding = embeddingModel.embed(request.getQuery());
            List<VectorSearchResult> results = vectorStore.search(
                queryEmbedding,
                request.getTopK() != null ? request.getTopK() : 10
            );
            
            List<String> ids = results.stream()
                .map(VectorSearchResult::getId)
                .collect(Collectors.toList());
            
            knowledges = knowledgeRepository.findAllById(ids);
        } else {
            // 按分类搜索
            if (request.getCategory() != null) {
                knowledges = knowledgeRepository.findByCategory(request.getCategory());
            } else {
                knowledges = knowledgeRepository.findAll();
            }
        }
        
        // 转换并过滤
        return knowledges.stream()
            .map(mapper::toKnowledgeEntry)
            .filter(entry -> {
                if (request.getMinConfidence() != null && entry.getConfidence() < request.getMinConfidence()) {
                    return false;
                }
                if (request.getVerifiedOnly() != null && request.getVerifiedOnly() && !entry.getVerified()) {
                    return false;
                }
                if (request.getTags() != null && !request.getTags().isEmpty()) {
                    return entry.getTags() != null && 
                           entry.getTags().stream().anyMatch(request.getTags()::contains);
                }
                return true;
            })
            .collect(Collectors.toList());
    }
    
    @Override
    public KnowledgeEntry getKnowledge(String knowledgeId) {
        Knowledge knowledge = knowledgeRepository.findById(knowledgeId).orElse(null);
        return knowledge != null ? mapper.toKnowledgeEntry(knowledge) : null;
    }
    
    @Override
    public List<KnowledgeEntry> getKnowledgeByCategory(String category) {
        List<Knowledge> knowledges = knowledgeRepository.findByCategory(category);
        return knowledges.stream()
            .map(mapper::toKnowledgeEntry)
            .collect(Collectors.toList());
    }
    
    @Override
    public void updateKnowledge(String knowledgeId, UpdateKnowledgeRequest request) {
        Knowledge knowledge = knowledgeRepository.findById(knowledgeId).orElse(null);
        if (knowledge == null) {
            throw new IllegalArgumentException("Knowledge not found: " + knowledgeId);
        }
        
        if (request.getCategory() != null) {
            knowledge.setCategory(request.getCategory());
        }
        if (request.getTitle() != null) {
            knowledge.setTitle(request.getTitle());
        }
        if (request.getContent() != null) {
            knowledge.setContent(request.getContent());
            // 重新生成向量
            float[] embedding = embeddingModel.embed(request.getContent());
            knowledge.setEmbedding(embedding);
        }
        if (request.getTags() != null) {
            knowledge.setTags(request.getTags());
        }
        if (request.getAttributes() != null) {
            knowledge.setAttributes(request.getAttributes());
        }
        
        knowledge.setUpdatedAt(LocalDateTime.now());
        knowledgeRepository.save(knowledge);
        
        // 更新向量数据库
        vectorStore.update(knowledgeId, knowledge);
    }
    
    @Override
    public void deleteKnowledge(String knowledgeId) {
        vectorStore.delete(knowledgeId);
        knowledgeRepository.deleteById(knowledgeId);
    }
    
    @Override
    public void verifyKnowledge(String knowledgeId, boolean verified) {
        Knowledge knowledge = knowledgeRepository.findById(knowledgeId).orElse(null);
        if (knowledge == null) {
            throw new IllegalArgumentException("Knowledge not found: " + knowledgeId);
        }
        
        knowledge.setVerified(verified);
        knowledgeRepository.save(knowledge);
    }
    
    private double calculateConfidence(Knowledge knowledge) {
        double score = 1.0;
        
        if (knowledge.getSource() != null) {
            score *= getSourceTrustScore(knowledge.getSource());
        }
        
        if (knowledge.isVerified()) {
            score *= 1.2;
        }
        
        if (knowledge.getTags() != null) {
            score *= (1.0 + knowledge.getTags().size() * 0.1);
        }
        
        return Math.min(score, 1.0);
    }
    
    private double getSourceTrustScore(String source) {
        // 根据来源计算信任度
        Map<String, Double> trustScores = Map.of(
            "official", 1.0,
            "expert", 0.9,
            "user", 0.7,
            "ai", 0.6
        );
        return trustScores.getOrDefault(source.toLowerCase(), 0.5);
    }
}
```

## 5. 自研适配器实现

### 5.1 自研记忆适配器

```java
@Service
@ConditionalOnProperty(name = "memory.provider", havingValue = "custom")
public class CustomMemoryAdapter implements MemoryService {
    
    private final MemoryRepository memoryRepository;
    private final EmbeddingModel embeddingModel;
    private final VectorStore vectorStore;
    private final MemoryMapper mapper;
    
    @Override
    public String addMemory(AddMemoryRequest request) {
        Memory memory = mapper.toMemory(request);
        
        // 生成向量
        float[] embedding = embeddingModel.embed(memory.getContent());
        memory.setEmbedding(embedding);
        
        // 计算重要性
        double importance = calculateImportance(memory);
        memory.setImportance(importance);
        
        // 存储到向量数据库
        String id = vectorStore.add(memory);
        memory.setId(id);
        
        // 存储到关系数据库
        Memory saved = memoryRepository.save(memory);
        return saved.getId();
    }
    
    @Override
    public List<String> addMemories(List<AddMemoryRequest> requests) {
        List<String> ids = new ArrayList<>();
        for (AddMemoryRequest request : requests) {
            ids.add(addMemory(request));
        }
        return ids;
    }
    
    @Override
    public List<MemoryEntry> searchMemories(SearchMemoryRequest request) {
        List<Memory> memories;
        
        if (request.getQuery() != null) {
            // 向量搜索
            float[] queryEmbedding = embeddingModel.embed(request.getQuery());
            List<VectorSearchResult> results = vectorStore.search(
                queryEmbedding,
                request.getTopK() != null ? request.getTopK() : 10
            );
            
            List<String> ids = results.stream()
                .map(VectorSearchResult::getId)
                .collect(Collectors.toList());
            
            memories = memoryRepository.findAllById(ids);
        } else {
            // 按用户搜索
            memories = memoryRepository.findByUserId(request.getUserId());
        }
        
        // 转换并过滤
        return memories.stream()
            .map(mapper::toMemoryEntry)
            .filter(entry -> applyFilters(entry, request))
            .limit(request.getTopK() != null ? request.getTopK() : Integer.MAX_VALUE)
            .collect(Collectors.toList());
    }
    
    @Override
    public List<MemoryEntry> getAllMemories(String userId) {
        List<Memory> memories = memoryRepository.findByUserId(userId);
        return memories.stream()
            .map(mapper::toMemoryEntry)
            .collect(Collectors.toList());
    }
    
    @Override
    public MemoryEntry getMemory(String memoryId) {
        Memory memory = memoryRepository.findById(memoryId).orElse(null);
        return memory != null ? mapper.toMemoryEntry(memory) : null;
    }
    
    @Override
    public void updateMemory(String memoryId, UpdateMemoryRequest request) {
        Memory memory = memoryRepository.findById(memoryId).orElse(null);
        if (memory == null) {
            throw new IllegalArgumentException("Memory not found: " + memoryId);
        }
        
        if (request.getContent() != null) {
            memory.setContent(request.getContent());
            // 重新生成向量
            float[] embedding = embeddingModel.embed(request.getContent());
            memory.setEmbedding(embedding);
        }
        if (request.getMetadata() != null) {
            memory.setMetadata(request.getMetadata());
        }
        if (request.getImportance() != null) {
            memory.setImportance(request.getImportance());
        }
        
        memory.setLastAccessedAt(LocalDateTime.now());
        memoryRepository.save(memory);
        
        // 更新向量数据库
        vectorStore.update(memoryId, memory);
    }
    
    @Override
    public void deleteMemory(String memoryId) {
        vectorStore.delete(memoryId);
        memoryRepository.deleteById(memoryId);
    }
    
    @Override
    public void deleteAllMemories(String userId) {
        List<Memory> memories = memoryRepository.findByUserId(userId);
        memories.forEach(memory -> {
            vectorStore.delete(memory.getId());
            memoryRepository.delete(memory);
        });
    }
    
    @Override
    public MemoryStats getStats(String userId) {
        List<Memory> memories = memoryRepository.findByUserId(userId);
        
        MemoryStats stats = new MemoryStats();
        stats.setUserId(userId);
        stats.setTotalMemories((long) memories.size());
        stats.setActiveMemories(memories.stream()
            .filter(m -> !m.getArchived())
            .count());
        stats.setArchivedMemories(memories.stream()
            .filter(Memory::getArchived)
            .count());
        stats.setAvgImportance(memories.stream()
            .mapToDouble(m -> m.getImportance() != null ? m.getImportance() : 0.0)
            .average()
            .orElse(0.0));
        stats.setLastAccessAt(memories.stream()
            .map(Memory::getLastAccessedAt)
            .max(LocalDateTime::compareTo)
            .orElse(null));
        
        Map<MemoryType, Long> byType = memories.stream()
            .collect(Collectors.groupingBy(
                Memory::getType,
                Collectors.counting()
            ));
        stats.setMemoriesByType(byType);
        
        return stats;
    }
    
    private boolean applyFilters(MemoryEntry entry, SearchMemoryRequest request) {
        if (request.getType() != null && !entry.getType().equals(request.getType())) {
            return false;
        }
        if (request.getMinScore() != null && entry.getImportance() < request.getMinScore()) {
            return false;
        }
        if (request.getStartTime() != null && entry.getCreatedAt().isBefore(request.getStartTime())) {
            return false;
        }
        if (request.getEndTime() != null && entry.getCreatedAt().isAfter(request.getEndTime())) {
            return false;
        }
        if (request.getIncludeArchived() != null && !request.getIncludeArchived() && entry.getArchived()) {
            return false;
        }
        return true;
    }
    
    private double calculateImportance(Memory memory) {
        double score = 0.0;
        
        // 访问频率
        score += Math.log(memory.getAccessCount() + 1) * 0.3;
        
        // 时间衰减
        long daysSinceCreation = ChronoUnit.DAYS.between(
            memory.getCreatedAt(),
            LocalDateTime.now()
        );
        score += Math.exp(-daysSinceCreation / 30.0) * 0.3;
        
        // 内容长度
        score += Math.min(memory.getContent().length() / 1000.0, 1.0) * 0.2;
        
        // 元数据权重
        if (memory.getMetadata() != null) {
            score += memory.getMetadata().size() * 0.05;
        }
        
        return score;
    }
}
```

## 6. 配置管理

### 6.1 配置文件

```yaml
# application.yml
memory:
  # 记忆提供者: mem0, custom
  provider: mem0
  
  # MEM0 配置
  mem0:
    base-url: http://localhost:8000
    api-key: ${MEM0_API_KEY:}
    timeout: 30000
  
  # 自研配置
  custom:
    vector-store:
      type: qdrant  # qdrant, milvus, pgvector
      url: http://localhost:6333
    embedding-model:
      type: openai  # openai, huggingface
      model: text-embedding-ada-002
      api-key: ${OPENAI_API_KEY:}
  
  # 短期记忆配置
  short-term:
    max-messages: 100
    max-tokens: 4000
  
  # 长期记忆配置
  long-term:
    max-age-days: 365
    compression-enabled: true
    compression-threshold: 1000
```

### 6.2 配置类

```java
@Configuration
@ConfigurationProperties(prefix = "memory")
@Data
public class MemoryProperties {
    
    private String provider;
    private Mem0Properties mem0;
    private CustomProperties custom;
    private ShortTermProperties shortTerm;
    private LongTermProperties longTerm;
    
    @Data
    public static class Mem0Properties {
        private String baseUrl;
        private String apiKey;
        private Integer timeout = 30000;
    }
    
    @Data
    public static class CustomProperties {
        private VectorStoreProperties vectorStore;
        private EmbeddingModelProperties embeddingModel;
    }
    
    @Data
    public static class VectorStoreProperties {
        private String type;
        private String url;
        private Map<String, Object> config;
    }
    
    @Data
    public static class EmbeddingModelProperties {
        private String type;
        private String model;
        private String apiKey;
    }
    
    @Data
    public static class ShortTermProperties {
        private Integer maxMessages = 100;
        private Integer maxTokens = 4000;
    }
    
    @Data
    public static class LongTermProperties {
        private Integer maxAgeDays = 365;
        private Boolean compressionEnabled = true;
        private Integer compressionThreshold = 1000;
    }
}
```

### 6.3 自动配置类

```java
@Configuration
@EnableConfigurationProperties(MemoryProperties.class)
public class MemoryAutoConfiguration {
    
    @Bean
    @ConditionalOnProperty(name = "memory.provider", havingValue = "mem0")
    public Mem0Client mem0Client(MemoryProperties properties) {
        return new Mem0Client(
            properties.getMem0().getBaseUrl(),
            properties.getMem0().getApiKey()
        );
    }
    
    @Bean
    @ConditionalOnProperty(name = "memory.provider", havingValue = "mem0")
    public MemoryMapper mem0MemoryMapper() {
        return new Mem0MemoryMapper();
    }
    
    @Bean
    @ConditionalOnProperty(name = "memory.provider", havingValue = "custom")
    public EmbeddingModel embeddingModel(MemoryProperties properties) {
        String type = properties.getCustom().getEmbeddingModel().getType();
        
        if ("openai".equalsIgnoreCase(type)) {
            return OpenAiEmbeddingModel.builder()
                .apiKey(properties.getCustom().getEmbeddingModel().getApiKey())
                .modelName(properties.getCustom().getEmbeddingModel().getModel())
                .build();
        }
        
        throw new IllegalArgumentException("Unsupported embedding model type: " + type);
    }
    
    @Bean
    @ConditionalOnProperty(name = "memory.provider", havingValue = "custom")
    public VectorStore vectorStore(MemoryProperties properties, EmbeddingModel embeddingModel) {
        String type = properties.getCustom().getVectorStore().getType();
        
        if ("qdrant".equalsIgnoreCase(type)) {
            return new QdrantVectorStore(properties.getCustom().getVectorStore().getUrl());
        } else if ("milvus".equalsIgnoreCase(type)) {
            return new MilvusVectorStore(properties.getCustom().getVectorStore().getUrl());
        } else if ("pgvector".equalsIgnoreCase(type)) {
            return new PgVectorStore();
        }
        
        throw new IllegalArgumentException("Unsupported vector store type: " + type);
    }
    
    @Bean
    @ConditionalOnProperty(name = "memory.provider", havingValue = "custom")
    public MemoryMapper customMemoryMapper() {
        return new CustomMemoryMapper();
    }
}
```

## 7. 与 LangChain4j 集成

### 7.1 ChatMemory 适配器

```java
@Service
public class MemoryServiceChatMemoryAdapter implements ChatMemory {
    
    private final MemoryService memoryService;
    private final ShortTermMemoryService shortTermMemoryService;
    private final String sessionId;
    private final String userId;
    
    public MemoryServiceChatMemoryAdapter(
        MemoryService memoryService,
        ShortTermMemoryService shortTermMemoryService,
        String sessionId,
        String userId
    ) {
        this.memoryService = memoryService;
        this.shortTermMemoryService = shortTermMemoryService;
        this.sessionId = sessionId;
        this.userId = userId;
    }
    
    @Override
    public void add(ChatMessage message) {
        // 添加到短期记忆
        shortTermMemoryService.addMessage(sessionId, message);
        
        // 如果是重要消息，添加到长期记忆
        if (isImportant(message)) {
            AddMemoryRequest request = new AddMemoryRequest();
            request.setContent(convertMessageToString(message));
            request.setUserId(userId);
            request.setSessionId(sessionId);
            request.setType(MemoryType.LONG_TERM);
            request.setMetadata(Map.of(
                "message_type", message.type().name(),
                "important", true
            ));
            
            memoryService.addMemory(request);
        }
    }
    
    @Override
    public List<ChatMessage> messages() {
        // 获取短期记忆
        List<ChatMessage> shortTermMessages = shortTermMemoryService.getAllMessages(sessionId);
        
        // 搜索相关的长期记忆
        SearchMemoryRequest searchRequest = new SearchMemoryRequest();
        searchRequest.setUserId(userId);
        searchRequest.setSessionId(sessionId);
        searchRequest.setQuery("recent conversation");
        searchRequest.setTopK(5);
        
        List<MemoryEntry> longTermMemories = memoryService.searchMemories(searchRequest);
        
        // 合并结果
        List<ChatMessage> allMessages = new ArrayList<>(shortTermMessages);
        allMessages.addAll(longTermMemories.stream()
            .map(this::convertToChatMessage)
            .collect(Collectors.toList()));
        
        return allMessages;
    }
    
    @Override
    public void clear() {
        shortTermMemoryService.clearMessages(sessionId);
    }
    
    @Override
    public ChatMemoryId id() {
        return ChatMemoryId.of(sessionId);
    }
    
    private boolean isImportant(ChatMessage message) {
        if (message instanceof UserMessage) {
            String content = ((UserMessage) message).singleText();
            return content.length() > 50 || 
                   content.contains("重要") || 
                   content.contains("记住") ||
                   content.contains("偏好");
        }
        return false;
    }
    
    private String convertMessageToString(ChatMessage message) {
        if (message instanceof UserMessage) {
            return "User: " + ((UserMessage) message).singleText();
        } else if (message instanceof AiMessage) {
            return "Assistant: " + ((AiMessage) message).text();
        } else if (message instanceof SystemMessage) {
            return "System: " + ((SystemMessage) message).text();
        }
        return message.toString();
    }
    
    private ChatMessage convertToChatMessage(MemoryEntry entry) {
        String content = entry.getContent();
        String messageType = (String) entry.getMetadata().get("message_type");
        
        if ("USER".equals(messageType)) {
            return UserMessage.from(content.substring("User: ".length()));
        } else if ("ASSISTANT".equals(messageType)) {
            return AiMessage.from(content.substring("Assistant: ".length()));
        } else {
            return SystemMessage.from(content);
        }
    }
}
```

### 7.2 ChatMemory Provider

```java
@Service
public class MemoryServiceChatMemoryProvider implements ChatMemoryProvider {
    
    private final MemoryService memoryService;
    private final ShortTermMemoryService shortTermMemoryService;
    
    public MemoryServiceChatMemoryProvider(
        MemoryService memoryService,
        ShortTermMemoryService shortTermMemoryService
    ) {
        this.memoryService = memoryService;
        this.shortTermMemoryService = shortTermMemoryService;
    }
    
    @Override
    public ChatMemory get(ChatMemoryId memoryId) {
        String sessionId = memoryId.id();
        String userId = extractUserIdFromSessionId(sessionId);
        
        return new MemoryServiceChatMemoryAdapter(
            memoryService,
            shortTermMemoryService,
            sessionId,
            userId
        );
    }
    
    private String extractUserIdFromSessionId(String sessionId) {
        // 从 sessionId 中提取 userId
        // 例如: "session-user123-456" -> "user123"
        String[] parts = sessionId.split("-");
        return parts.length > 1 ? parts[1] : sessionId;
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
    embedding VECTOR(1536),
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
    embedding VECTOR(1536),
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

## 9. API 设计

### 9.1 记忆 API

```java
@RestController
@RequestMapping("/api/memories")
public class MemoryController {
    
    @Autowired
    private MemoryService memoryService;
    
    @PostMapping
    public ResponseEntity<String> addMemory(@RequestBody AddMemoryRequest request) {
        String id = memoryService.addMemory(request);
        return ResponseEntity.ok(id);
    }
    
    @PostMapping("/batch")
    public ResponseEntity<List<String>> addMemories(@RequestBody List<AddMemoryRequest> requests) {
        List<String> ids = memoryService.addMemories(requests);
        return ResponseEntity.ok(ids);
    }
    
    @PostMapping("/search")
    public ResponseEntity<List<MemoryEntry>> searchMemories(@RequestBody SearchMemoryRequest request) {
        List<MemoryEntry> memories = memoryService.searchMemories(request);
        return ResponseEntity.ok(memories);
    }
    
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<MemoryEntry>> getUserMemories(@PathVariable String userId) {
        List<MemoryEntry> memories = memoryService.getAllMemories(userId);
        return ResponseEntity.ok(memories);
    }
    
    @GetMapping("/{memoryId}")
    public ResponseEntity<MemoryEntry> getMemory(@PathVariable String memoryId) {
        MemoryEntry memory = memoryService.getMemory(memoryId);
        return ResponseEntity.ok(memory);
    }
    
    @PutMapping("/{memoryId}")
    public ResponseEntity<Void> updateMemory(
        @PathVariable String memoryId,
        @RequestBody UpdateMemoryRequest request
    ) {
        memoryService.updateMemory(memoryId, request);
        return ResponseEntity.ok().build();
    }
    
    @DeleteMapping("/{memoryId}")
    public ResponseEntity<Void> deleteMemory(@PathVariable String memoryId) {
        memoryService.deleteMemory(memoryId);
        return ResponseEntity.ok().build();
    }
    
    @DeleteMapping("/user/{userId}")
    public ResponseEntity<Void> deleteUserMemories(@PathVariable String userId) {
        memoryService.deleteAllMemories(userId);
        return ResponseEntity.ok().build();
    }
    
    @GetMapping("/user/{userId}/stats")
    public ResponseEntity<MemoryStats> getUserStats(@PathVariable String userId) {
        MemoryStats stats = memoryService.getStats(userId);
        return ResponseEntity.ok(stats);
    }
}
```

### 9.2 情景 API

```java
@RestController
@RequestMapping("/api/episodes")
public class EpisodeController {
    
    @Autowired
    private EpisodicMemoryService episodicMemoryService;
    
    @PostMapping
    public ResponseEntity<String> createEpisode(@RequestBody CreateEpisodeRequest request) {
        String id = episodicMemoryService.createEpisode(request);
        return ResponseEntity.ok(id);
    }
    
    @GetMapping("/{episodeId}")
    public ResponseEntity<Episode> getEpisode(@PathVariable String episodeId) {
        Episode episode = episodicMemoryService.getEpisode(episodeId);
        return ResponseEntity.ok(episode);
    }
    
    @PostMapping("/search")
    public ResponseEntity<List<Episode>> searchEpisodes(@RequestBody SearchEpisodeRequest request) {
        List<Episode> episodes = episodicMemoryService.searchEpisodes(request);
        return ResponseEntity.ok(episodes);
    }
    
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<Episode>> getUserEpisodes(@PathVariable String userId) {
        List<Episode> episodes = episodicMemoryService.getUserEpisodes(userId);
        return ResponseEntity.ok(episodes);
    }
    
    @GetMapping("/session/{sessionId}")
    public ResponseEntity<List<Episode>> getSessionEpisodes(@PathVariable String sessionId) {
        List<Episode> episodes = episodicMemoryService.getSessionEpisodes(sessionId);
        return ResponseEntity.ok(episodes);
    }
    
    @PutMapping("/{episodeId}")
    public ResponseEntity<Void> updateEpisode(
        @PathVariable String episodeId,
        @RequestBody UpdateEpisodeRequest request
    ) {
        episodicMemoryService.updateEpisode(episodeId, request);
        return ResponseEntity.ok().build();
    }
    
    @DeleteMapping("/{episodeId}")
    public ResponseEntity<Void> deleteEpisode(@PathVariable String episodeId) {
        episodicMemoryService.deleteEpisode(episodeId);
        return ResponseEntity.ok().build();
    }
}
```

### 9.3 知识 API

```java
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {
    
    @Autowired
    private SemanticMemoryService semanticMemoryService;
    
    @PostMapping
    public ResponseEntity<String> addKnowledge(@RequestBody AddKnowledgeRequest request) {
        String id = semanticMemoryService.addKnowledge(request);
        return ResponseEntity.ok(id);
    }
    
    @PostMapping("/batch")
    public ResponseEntity<List<String>> addKnowledges(@RequestBody List<AddKnowledgeRequest> requests) {
        List<String> ids = semanticMemoryService.addKnowledges(requests);
        return ResponseEntity.ok(ids);
    }
    
    @PostMapping("/search")
    public ResponseEntity<List<KnowledgeEntry>> searchKnowledge(@RequestBody SearchKnowledgeRequest request) {
        List<KnowledgeEntry> knowledges = semanticMemoryService.searchKnowledge(request);
        return ResponseEntity.ok(knowledges);
    }
    
    @GetMapping("/{knowledgeId}")
    public ResponseEntity<KnowledgeEntry> getKnowledge(@PathVariable String knowledgeId) {
        KnowledgeEntry knowledge = semanticMemoryService.getKnowledge(knowledgeId);
        return ResponseEntity.ok(knowledge);
    }
    
    @GetMapping("/category/{category}")
    public ResponseEntity<List<KnowledgeEntry>> getKnowledgeByCategory(@PathVariable String category) {
        List<KnowledgeEntry> knowledges = semanticMemoryService.getKnowledgeByCategory(category);
        return ResponseEntity.ok(knowledges);
    }
    
    @PutMapping("/{knowledgeId}")
    public ResponseEntity<Void> updateKnowledge(
        @PathVariable String knowledgeId,
        @RequestBody UpdateKnowledgeRequest request
    ) {
        semanticMemoryService.updateKnowledge(knowledgeId, request);
        return ResponseEntity.ok().build();
    }
    
    @DeleteMapping("/{knowledgeId}")
    public ResponseEntity<Void> deleteKnowledge(@PathVariable String knowledgeId) {
        semanticMemoryService.deleteKnowledge(knowledgeId);
        return ResponseEntity.ok().build();
    }
    
    @PutMapping("/{knowledgeId}/verify")
    public ResponseEntity<Void> verifyKnowledge(
        @PathVariable String knowledgeId,
        @RequestParam boolean verified
    ) {
        semanticMemoryService.verifyKnowledge(knowledgeId, verified);
        return ResponseEntity.ok().build();
    }
}
```

## 10. 切换策略

### 10.1 配置切换

```yaml
# 切换到 MEM0
memory:
  provider: mem0
  mem0:
    base-url: http://localhost:8000
    api-key: your-api-key

# 切换到自研
memory:
  provider: custom
  custom:
    vector-store:
      type: qdrant
      url: http://localhost:6333
    embedding-model:
      type: openai
      model: text-embedding-ada-002
      api-key: your-api-key
```

### 10.2 运行时切换

```java
@Service
public class MemoryServiceFactory {
    
    private final Map<String, MemoryService> memoryServices;
    
    @Autowired
    public MemoryServiceFactory(List<MemoryService> services) {
        this.memoryServices = services.stream()
            .collect(Collectors.toMap(
                service -> service.getClass().getAnnotation(ConditionalOnProperty.class).havingValue(),
                Function.identity()
            ));
    }
    
    public MemoryService getMemoryService(String provider) {
        MemoryService service = memoryServices.get(provider);
        if (service == null) {
            throw new IllegalArgumentException("Unsupported memory provider: " + provider);
        }
        return service;
    }
}
```

### 10.3 数据迁移

```java
@Service
public class MemoryMigrationService {
    
    @Autowired
    private MemoryService sourceService;
    
    @Autowired
    private MemoryService targetService;
    
    public void migrate(String userId) {
        // 从源服务获取所有记忆
        List<MemoryEntry> memories = sourceService.getAllMemories(userId);
        
        // 批量添加到目标服务
        List<AddMemoryRequest> requests = memories.stream()
            .map(memory -> {
                AddMemoryRequest request = new AddMemoryRequest();
                request.setContent(memory.getContent());
                request.setUserId(memory.getUserId());
                request.setSessionId(memory.getSessionId());
                request.setType(memory.getType());
                request.setMetadata(memory.getMetadata());
                request.setImportance(memory.getImportance());
                return request;
            })
            .collect(Collectors.toList());
        
        targetService.addMemories(requests);
        
        // 删除源服务中的记忆
        sourceService.deleteAllMemories(userId);
    }
}
```

## 11. 最佳实践

### 11.1 接口设计
- 保持接口简洁和稳定
- 使用明确的参数和返回类型
- 提供完整的文档
- 考虑向后兼容性

### 11.2 适配器实现
- 实现完整的接口方法
- 处理异常情况
- 提供详细的日志
- 支持性能监控

### 11.3 配置管理
- 使用配置文件管理
- 支持环境变量
- 提供默认值
- 支持配置验证

### 11.4 测试策略
- 编写接口测试
- 编写适配器测试
- 编写集成测试
- 支持多种实现的测试

## 12. 监控与日志

### 12.1 监控指标

```java
@Aspect
@Component
public class MemoryServiceMonitor {
    
    @Around("execution(* com.example.memory.MemoryService.*(..))")
    public Object monitor(ProceedingJoinPoint joinPoint) throws Throwable {
        String methodName = joinPoint.getSignature().getName();
        long startTime = System.currentTimeMillis();
        
        try {
            Object result = joinPoint.proceed();
            long duration = System.currentTimeMillis() - startTime;
            
            // 记录指标
            Metrics.counter("memory.service.calls", 
                "method", methodName, 
                "status", "success"
            ).increment();
            Metrics.timer("memory.service.duration", 
                "method", methodName
            ).record(duration, TimeUnit.MILLISECONDS);
            
            return result;
        } catch (Exception e) {
            Metrics.counter("memory.service.calls", 
                "method", methodName, 
                "status", "error"
            ).increment();
            throw e;
        }
    }
}
```

### 12.2 日志记录

```java
@Slf4j
public class Mem0MemoryAdapter implements MemoryService {
    
    @Override
    public String addMemory(AddMemoryRequest request) {
        log.info("Adding memory for user: {}, content: {}", 
            request.getUserId(), 
            request.getContent());
        
        try {
            String id = mem0Client.addMemory(
                request.getContent(),
                request.getUserId(),
                request.getMetadata()
            ).getId();
            
            log.info("Memory added successfully: {}", id);
            return id;
        } catch (Exception e) {
            log.error("Failed to add memory for user: {}", 
                request.getUserId(), e);
            throw e;
        }
    }
}
```

## 13. 实施计划

### Phase 1: 接口定义（1周）
- 定义核心接口
- 定义数据模型
- 编写接口文档
- 设计配置方案

### Phase 2: MEM0 适配器（2周）
- 实现 MEM0 客户端
- 实现 MEM0 适配器
- 实现短期记忆适配器
- 实现情景记忆适配器
- 实现语义记忆适配器

### Phase 3: 自研适配器（2周）
- 实现向量存储
- 实现嵌入模型
- 实现自研适配器
- 实现数据迁移工具

### Phase 4: 集成与测试（1周）
- 与 LangChain4j 集成
- 编写单元测试
- 编写集成测试
- 性能测试

### Phase 5: 文档与部署（1周）
- 编写使用文档
- 编写迁移指南
- 部署到生产环境
- 监控和优化

## 14. 扩展方向

### 14.1 多租户支持
- 支持租户隔离
- 支持租户级别的配置
- 支持租户级别的监控

### 14.2 缓存优化
- 实现多级缓存
- 实现智能缓存淘汰
- 支持缓存预热

### 14.3 性能优化
- 批量操作优化
- 并发操作优化
- 查询优化

### 14.4 安全增强
- 数据加密
- 访问控制
- 审计日志
