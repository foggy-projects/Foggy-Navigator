# RAG 模块设计文档

> **实施阶段**: Phase 3+
> **本文档作用**: 系统模块设计参考，指导实施

---


## 1. 概述

### 1.1 设计目标
- **灵活配置**: 支持多种 RAG 策略（Naive、Advanced、Modular）
- **场景适配**: 根据应用场景自动选择 Pre-Retrieval 和 Post-Retrieval 策略
- **上下文集成**: 与 Session 上下文深度集成
- **性能优化**: 平衡召回率、准确率和响应速度
- **可扩展性**: 支持自定义检索器、重排序器等组件

### 1.2 核心原则
- **模块化设计**: 每个组件独立可替换
- **策略模式**: 根据场景动态选择策略
- **管道模式**: 支持链式处理
- **适配器模式**: 统一接口，隐藏实现细节

### 1.3 架构层次

```
┌─────────────────────────────────────────────────────────┐
│                    应用层                              │
│              (LangChain4j + 业务逻辑)                   │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│                  RAG 服务层                              │
│              (RAG Service Layer)                       │
│                                                         │
│  ┌──────────────────────────────────────────────┐      │
│  │      RAGService (统一接口)                    │      │
│  └──────────────────────────────────────────────┘      │
│                          ↓                              │
│  ┌──────────────────────────────────────────────┐      │
│  │   SequentialRAGService (顺序 RAG)            │      │
│  │   IterativeRAGService (迭代 RAG)             │      │
│  │   AdaptiveRAGService (自适应 RAG)            │      │
│  └──────────────────────────────────────────────┘      │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│                  RAG 组件层                              │
│              (RAG Component Layer)                      │
│                                                         │
│  ┌──────────────┬──────────────┬──────────────┐        │
│  │ Pre-Retrieval│   Retrieval  │Post-Retrieval│        │
│  │              │              │              │        │
│  │ - Query      │ - Vector     │ - Re-ranking │        │
│  │   Rewriting  │   Search     │ - Filtering  │        │
│  │ - Query      │ - Keyword    │ - Compression│        │
│  │   Expansion  │   Search     │              │        │
│  │ - HyDE       │ - Hybrid     │              │        │
│  └──────────────┴──────────────┴──────────────┘        │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│                  存储层                              │
│         (Vector DB / Knowledge Base)                  │
└─────────────────────────────────────────────────────────┘
```

## 2. 核心接口设计

### 2.1 RAG 服务接口（RAGService）

```java
public interface RAGService {
    
    /**
     * 生成响应
     */
    RAGResponse generateResponse(RAGRequest request);
    
    /**
     * 批量生成响应
     */
    List<RAGResponse> generateResponses(List<RAGRequest> requests);
    
    /**
     * 流式生成响应
     */
    Flux<String> generateResponseStream(RAGRequest request);
    
    /**
     * 获取检索到的文档
     */
    List<Document> getRetrievedDocuments(String requestId);
    
    /**
     * 获取 RAG 统计信息
     */
    RAGStats getStats(String requestId);
}
```

### 2.2 Pre-Retrieval 接口（PreRetrievalService）

```java
public interface PreRetrievalService {
    
    /**
     * 预处理查询
     */
    PreRetrievalResult processQuery(PreRetrievalRequest request);
    
    /**
     * 查询重写
     */
    String rewriteQuery(String query, String userId, List<ChatMessage> context);
    
    /**
     * 查询扩展
     */
    List<String> expandQuery(String query, int maxExpansions);
    
    /**
     * 生成假设性文档（HyDE）
     */
    String generateHypotheticalDocument(String query);
    
    /**
     * 查询分解
     */
    List<String> decomposeQuery(String query);
}
```

### 2.3 Retrieval 接口（RetrievalService）

```java
public interface RetrievalService {
    
    /**
     * 检索文档
     */
    List<Document> retrieve(RetrievalRequest request);
    
    /**
     * 向量检索
     */
    List<Document> vectorSearch(String query, int topK);
    
    /**
     * 关键词检索
     */
    List<Document> keywordSearch(String query, int topK);
    
    /**
     * 混合检索
     */
    List<Document> hybridSearch(String query, int topK);
    
    /**
     * 多路检索
     */
    List<Document> multiPathSearch(List<String> queries, int topK);
}
```

### 2.4 Post-Retrieval 接口（PostRetrievalService）

```java
public interface PostRetrievalService {
    
    /**
     * 后处理检索结果
     */
    PostRetrievalResult processResults(PostRetrievalRequest request);
    
    /**
     * 重排序
     */
    List<Document> reRank(String query, List<Document> documents, int topK);
    
    /**
     * 上下文压缩
     */
    String compressContext(String query, List<Document> documents, int maxTokens);
    
    /**
     * 选择性压缩
     */
    String selectiveCompression(String query, List<Document> documents, int maxTokens);
    
    /**
     * 过滤文档
     */
    List<Document> filterDocuments(List<Document> documents, FilterCriteria criteria);
    
    /**
     * 去重
     */
    List<Document> deduplicate(List<Document> documents);
}
```

## 3. 数据模型设计

### 3.1 请求/响应模型

#### RAGRequest
```java
@Data
public class RAGRequest {
    private String requestId;
    private String query;
    private String userId;
    private String sessionId;
    private RAGStrategy strategy;
    private RAGConfig config;
    private List<ChatMessage> context;
    private Map<String, Object> metadata;
    private Boolean stream;
}
```

#### RAGResponse
```java
@Data
public class RAGResponse {
    private String requestId;
    private String response;
    private List<Document> retrievedDocuments;
    private RAGStats stats;
    private Map<String, Object> metadata;
}
```

#### PreRetrievalRequest
```java
@Data
public class PreRetrievalRequest {
    private String query;
    private String userId;
    private String sessionId;
    private List<ChatMessage> context;
    private PreRetrievalConfig config;
}
```

#### PreRetrievalResult
```java
@Data
public class PreRetrievalResult {
    private String originalQuery;
    private String processedQuery;
    private List<String> expandedQueries;
    private String hypotheticalDocument;
    private List<String> subQueries;
    private Map<String, Object> metadata;
}
```

#### RetrievalRequest
```java
@Data
public class RetrievalRequest {
    private String query;
    private List<String> queries;
    private String userId;
    private String sessionId;
    private RetrievalConfig config;
}
```

#### PostRetrievalRequest
```java
@Data
public class PostRetrievalRequest {
    private String query;
    private List<Document> documents;
    private String userId;
    private String sessionId;
    private PostRetrievalConfig config;
}
```

#### PostRetrievalResult
```java
@Data
public class PostRetrievalResult {
    private List<Document> originalDocuments;
    private List<Document> processedDocuments;
    private String compressedContext;
    private Map<String, Object> metadata;
}
```

### 3.2 配置模型

#### RAGConfig
```java
@Data
public class RAGConfig {
    private RAGStrategy strategy;
    private PreRetrievalConfig preRetrieval;
    private RetrievalConfig retrieval;
    private PostRetrievalConfig postRetrieval;
    private GenerationConfig generation;
}
```

#### PreRetrievalConfig
```java
@Data
public class PreRetrievalConfig {
    private Boolean enabled;
    private Boolean queryRewriting;
    private Boolean queryExpansion;
    private Boolean hyde;
    private Boolean queryDecomposition;
    private Integer maxExpansions;
    private Integer maxSubQueries;
}
```

#### RetrievalConfig
```java
@Data
public class RetrievalConfig {
    private RetrievalType type;
    private Integer topK;
    private Double minScore;
    private Map<String, Object> filters;
    private TimeRange timeRange;
}
```

#### PostRetrievalConfig
```java
@Data
public class PostRetrievalConfig {
    private Boolean enabled;
    private Boolean reRanking;
    private Boolean contextCompression;
    private Boolean filtering;
    private Boolean deduplication;
    private Integer topK;
    private Integer maxTokens;
    private Double minRelevance;
    private Map<String, Object> metadataFilters;
    private TimeRange timeRange;
}
```

#### GenerationConfig
```java
@Data
public class GenerationConfig {
    private String model;
    private Double temperature;
    private Integer maxTokens;
    private Double topP;
    private Integer topK;
    private String systemPrompt;
}
```

### 3.3 核心数据模型

#### Document
```java
@Data
public class Document {
    private String id;
    private String content;
    private String title;
    private String url;
    private Map<String, Object> metadata;
    private float[] embedding;
    private Double score;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

#### RAGStats
```java
@Data
public class RAGStats {
    private String requestId;
    private Long preRetrievalTime;
    private Long retrievalTime;
    private Long postRetrievalTime;
    private Long generationTime;
    private Long totalTime;
    private Integer originalDocumentCount;
    private Integer processedDocumentCount;
    private Integer contextTokenCount;
    private Integer responseTokenCount;
    private Map<String, Object> details;
}
```

### 3.4 枚举类型

#### RAGStrategy
```java
public enum RAGStrategy {
    NAIVE,
    SEQUENTIAL,
    ITERATIVE,
    ADAPTIVE
}
```

#### RetrievalType
```java
public enum RetrievalType {
    VECTOR,
    KEYWORD,
    HYBRID,
    MULTI_PATH
}
```

## 4. Pre-Retrieval 实现

### 4.1 查询重写服务

```java
@Service
public class QueryRewritingService implements PreRetrievalService {
    
    @Autowired
    private LLMService llmService;
    
    @Autowired
    private MemoryService memoryService;
    
    @Override
    public PreRetrievalResult processQuery(PreRetrievalRequest request) {
        String rewrittenQuery = rewriteQuery(
            request.getQuery(),
            request.getUserId(),
            request.getContext()
        );
        
        PreRetrievalResult result = new PreRetrievalResult();
        result.setOriginalQuery(request.getQuery());
        result.setProcessedQuery(rewrittenQuery);
        result.setMetadata(Map.of(
            "rewriting", true,
            "original_query", request.getQuery()
        ));
        
        return result;
    }
    
    @Override
    public String rewriteQuery(String query, String userId, List<ChatMessage> context) {
        // 从用户历史记忆中获取上下文
        List<MemoryEntry> memories = memoryService.searchMemories(
            SearchMemoryRequest.builder()
                .userId(userId)
                .query("recent conversation context")
                .topK(5)
                .build()
        );
        
        // 构建重写提示
        String prompt = buildRewritePrompt(query, context, memories);
        
        // 使用 LLM 重写查询
        String rewrittenQuery = llmService.generate(prompt);
        
        return rewrittenQuery;
    }
    
    private String buildRewritePrompt(String query, List<ChatMessage> context, List<MemoryEntry> memories) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个查询重写专家。请根据对话上下文，重写以下查询，使其更清晰、更具体。\n\n");
        
        if (context != null && !context.isEmpty()) {
            prompt.append("对话上下文：\n");
            for (ChatMessage message : context) {
                prompt.append("- ").append(message.type().name())
                      .append(": ").append(message.text()).append("\n");
            }
            prompt.append("\n");
        }
        
        if (memories != null && !memories.isEmpty()) {
            prompt.append("历史记忆：\n");
            for (MemoryEntry memory : memories) {
                prompt.append("- ").append(memory.getContent()).append("\n");
            }
            prompt.append("\n");
        }
        
        prompt.append("原始查询：").append(query).append("\n");
        prompt.append("\n重写后的查询：");
        
        return prompt.toString();
    }
}
```

### 4.2 查询扩展服务

```java
@Service
public class QueryExpansionService implements PreRetrievalService {
    
    @Autowired
    private LLMService llmService;
    
    @Autowired
    private EmbeddingModel embeddingModel;
    
    @Override
    public PreRetrievalResult processQuery(PreRetrievalRequest request) {
        List<String> expandedQueries = expandQuery(
            request.getQuery(),
            request.getConfig().getMaxExpansions()
        );
        
        PreRetrievalResult result = new PreRetrievalResult();
        result.setOriginalQuery(request.getQuery());
        result.setProcessedQuery(request.getQuery());
        result.setExpandedQueries(expandedQueries);
        result.setMetadata(Map.of(
            "expansion", true,
            "expansion_count", expandedQueries.size()
        ));
        
        return result;
    }
    
    @Override
    public List<String> expandQuery(String query, int maxExpansions) {
        String prompt = String.format(
            "请为以下查询生成 %d 个相关的查询变体，用于提高检索召回率。" +
            "每个查询应该从不同角度或使用不同的表达方式。\n\n" +
            "查询：%s\n\n" +
            "相关查询（每行一个）：",
            maxExpansions,
            query
        );
        
        String response = llmService.generate(prompt);
        
        return parseQueries(response);
    }
    
    private List<String> parseQueries(String response) {
        return Arrays.stream(response.split("\n"))
            .map(String::trim)
            .filter(line -> !line.isEmpty())
            .collect(Collectors.toList());
    }
}
```

### 4.3 HyDE 服务

```java
@Service
public class HyDEService implements PreRetrievalService {
    
    @Autowired
    private LLMService llmService;
    
    @Autowired
    private EmbeddingModel embeddingModel;
    
    @Override
    public PreRetrievalResult processQuery(PreRetrievalRequest request) {
        String hypotheticalDoc = generateHypotheticalDocument(request.getQuery());
        
        PreRetrievalResult result = new PreRetrievalResult();
        result.setOriginalQuery(request.getQuery());
        result.setProcessedQuery(request.getQuery());
        result.setHypotheticalDocument(hypotheticalDoc);
        result.setMetadata(Map.of(
            "hyde", true,
            "hypothetical_doc_length", hypotheticalDoc.length()
        ));
        
        return result;
    }
    
    @Override
    public String generateHypotheticalDocument(String query) {
        String prompt = String.format(
            "请为以下查询生成一个理想的回答文档。这个文档应该包含查询的所有关键信息，" +
            "并且结构清晰、内容准确。\n\n" +
            "查询：%s\n\n" +
            "理想文档：",
            query
        );
        
        return llmService.generate(prompt);
    }
}
```

### 4.4 查询分解服务

```java
@Service
public class QueryDecompositionService implements PreRetrievalService {
    
    @Autowired
    private LLMService llmService;
    
    @Override
    public PreRetrievalResult processQuery(PreRetrievalRequest request) {
        List<String> subQueries = decomposeQuery(request.getQuery());
        
        PreRetrievalResult result = new PreRetrievalResult();
        result.setOriginalQuery(request.getQuery());
        result.setProcessedQuery(request.getQuery());
        result.setSubQueries(subQueries);
        result.setMetadata(Map.of(
            "decomposition", true,
            "sub_query_count", subQueries.size()
        ));
        
        return result;
    }
    
    @Override
    public List<String> decomposeQuery(String query) {
        String prompt = String.format(
            "请将以下复杂查询分解为多个简单的子查询，每个子查询应该可以独立回答。" +
            "子查询应该涵盖原始查询的所有方面。\n\n" +
            "查询：%s\n\n" +
            "子查询（每行一个）：",
            query
        );
        
        String response = llmService.generate(prompt);
        
        return parseQueries(response);
    }
    
    private List<String> parseQueries(String response) {
        return Arrays.stream(response.split("\n"))
            .map(String::trim)
            .filter(line -> !line.isEmpty())
            .collect(Collectors.toList());
    }
}
```

### 4.5 Pre-Retrieval 管道

```java
@Service
public class PreRetrievalPipeline {
    
    @Autowired
    private QueryRewritingService queryRewritingService;
    
    @Autowired
    private QueryExpansionService queryExpansionService;
    
    @Autowired
    private HyDEService hydeService;
    
    @Autowired
    private QueryDecompositionService queryDecompositionService;
    
    /**
     * 执行 Pre-Retrieval 管道
     */
    public PreRetrievalResult execute(PreRetrievalRequest request) {
        PreRetrievalConfig config = request.getConfig();
        PreRetrievalResult result = new PreRetrievalResult();
        result.setOriginalQuery(request.getQuery());
        
        String processedQuery = request.getQuery();
        List<String> expandedQueries = new ArrayList<>();
        String hypotheticalDoc = null;
        List<String> subQueries = new ArrayList<>();
        
        // 查询重写
        if (config.getQueryRewriting()) {
            processedQuery = queryRewritingService.rewriteQuery(
                processedQuery,
                request.getUserId(),
                request.getContext()
            );
        }
        
        // 查询扩展
        if (config.getQueryExpansion()) {
            expandedQueries = queryExpansionService.expandQuery(
                processedQuery,
                config.getMaxExpansions()
            );
        }
        
        // HyDE
        if (config.getHyde()) {
            hypotheticalDoc = hydeService.generateHypotheticalDocument(processedQuery);
        }
        
        // 查询分解
        if (config.getQueryDecomposition()) {
            subQueries = queryDecompositionService.decomposeQuery(processedQuery);
        }
        
        result.setProcessedQuery(processedQuery);
        result.setExpandedQueries(expandedQueries);
        result.setHypotheticalDocument(hypotheticalDoc);
        result.setSubQueries(subQueries);
        
        return result;
    }
}
```

## 5. Retrieval 实现

### 5.1 向量检索服务

```java
@Service
public class VectorRetrievalService implements RetrievalService {
    
    @Autowired
    private VectorStore vectorStore;
    
    @Autowired
    private EmbeddingModel embeddingModel;
    
    @Autowired
    private DocumentRepository documentRepository;
    
    @Override
    public List<Document> retrieve(RetrievalRequest request) {
        return vectorSearch(request.getQuery(), request.getConfig().getTopK());
    }
    
    @Override
    public List<Document> vectorSearch(String query, int topK) {
        // 生成查询向量
        float[] queryEmbedding = embeddingModel.embed(query);
        
        // 向量检索
        List<VectorSearchResult> results = vectorStore.search(queryEmbedding, topK);
        
        // 获取文档详情
        List<String> documentIds = results.stream()
            .map(VectorSearchResult::getId)
            .collect(Collectors.toList());
        
        List<Document> documents = documentRepository.findAllById(documentIds);
        
        // 设置分数
        Map<String, Double> scoreMap = results.stream()
            .collect(Collectors.toMap(
                VectorSearchResult::getId,
                VectorSearchResult::getScore
            ));
        
        documents.forEach(doc -> 
            doc.setScore(scoreMap.get(doc.getId()))
        );
        
        return documents;
    }
}
```

### 5.2 关键词检索服务

```java
@Service
public class KeywordRetrievalService implements RetrievalService {
    
    @Autowired
    private ElasticsearchRepository elasticsearchRepository;
    
    @Autowired
    private DocumentRepository documentRepository;
    
    @Override
    public List<Document> retrieve(RetrievalRequest request) {
        return keywordSearch(request.getQuery(), request.getConfig().getTopK());
    }
    
    @Override
    public List<Document> keywordSearch(String query, int topK) {
        // 使用 Elasticsearch 进行关键词检索
        List<SearchHit<Document>> hits = elasticsearchRepository.search(query, topK);
        
        // 转换为 Document
        return hits.stream()
            .map(SearchHit::getContent)
            .collect(Collectors.toList());
    }
}
```

### 5.3 混合检索服务

```java
@Service
public class HybridRetrievalService implements RetrievalService {
    
    @Autowired
    private VectorRetrievalService vectorRetrievalService;
    
    @Autowired
    private KeywordRetrievalService keywordRetrievalService;
    
    @Override
    public List<Document> retrieve(RetrievalRequest request) {
        return hybridSearch(request.getQuery(), request.getConfig().getTopK());
    }
    
    @Override
    public List<Document> hybridSearch(String query, int topK) {
        // 向量检索
        List<Document> vectorResults = vectorRetrievalService.vectorSearch(query, topK * 2);
        
        // 关键词检索
        List<Document> keywordResults = keywordRetrievalService.keywordSearch(query, topK * 2);
        
        // 融合结果
        return fuseResults(vectorResults, keywordResults, topK);
    }
    
    private List<Document> fuseResults(List<Document> vectorResults, List<Document> keywordResults, int topK) {
        // 使用 Reciprocal Rank Fusion (RRF) 融合
        Map<String, Double> scores = new HashMap<>();
        
        // 向量检索结果
        for (int i = 0; i < vectorResults.size(); i++) {
            String docId = vectorResults.get(i).getId();
            double score = 1.0 / (i + 1 + 60);
            scores.merge(docId, score, Double::sum);
        }
        
        // 关键词检索结果
        for (int i = 0; i < keywordResults.size(); i++) {
            String docId = keywordResults.get(i).getId();
            double score = 1.0 / (i + 1 + 60);
            scores.merge(docId, score, Double::sum);
        }
        
        // 合并文档
        Map<String, Document> documentMap = new HashMap<>();
        vectorResults.forEach(doc -> documentMap.put(doc.getId(), doc));
        keywordResults.forEach(doc -> documentMap.put(doc.getId(), doc));
        
        // 按分数排序
        return documentMap.entrySet().stream()
            .sorted((e1, e2) -> Double.compare(e2.getValue(), e1.getValue()))
            .limit(topK)
            .map(Map.Entry::getValue)
            .collect(Collectors.toList());
    }
}
```

### 5.4 多路检索服务

```java
@Service
public class MultiPathRetrievalService implements RetrievalService {
    
    @Autowired
    private VectorRetrievalService vectorRetrievalService;
    
    @Autowired
    private KeywordRetrievalService keywordRetrievalService;
    
    @Override
    public List<Document> retrieve(RetrievalRequest request) {
        if (request.getQueries() != null && !request.getQueries().isEmpty()) {
            return multiPathSearch(request.getQueries(), request.getConfig().getTopK());
        }
        return Collections.emptyList();
    }
    
    @Override
    public List<Document> multiPathSearch(List<String> queries, int topK) {
        List<Document> allResults = new ArrayList<>();
        
        // 对每个查询进行检索
        for (String query : queries) {
            List<Document> results = vectorRetrievalService.vectorSearch(query, topK);
            allResults.addAll(results);
        }
        
        // 去重和重排序
        return deduplicateAndRerank(allResults, topK);
    }
    
    private List<Document> deduplicateAndRerank(List<Document> documents, int topK) {
        // 去重
        Map<String, Document> uniqueDocs = new LinkedHashMap<>();
        for (Document doc : documents) {
            if (!uniqueDocs.containsKey(doc.getId())) {
                uniqueDocs.put(doc.getId(), doc);
            } else {
                // 合并分数
                double existingScore = uniqueDocs.get(doc.getId()).getScore();
                double newScore = existingScore + doc.getScore();
                uniqueDocs.get(doc.getId()).setScore(newScore);
            }
        }
        
        // 按分数排序
        return uniqueDocs.values().stream()
            .sorted(Comparator.comparingDouble(Document::getScore).reversed())
            .limit(topK)
            .collect(Collectors.toList());
    }
}
```

## 6. Post-Retrieval 实现

### 6.1 重排序服务

```java
@Service
public class ReRankingService implements PostRetrievalService {
    
    @Autowired
    private ReRankModel reRankModel;
    
    @Autowired
    private LLMService llmService;
    
    @Override
    public PostRetrievalResult processResults(PostRetrievalRequest request) {
        List<Document> reRankedDocs = reRank(
            request.getQuery(),
            request.getDocuments(),
            request.getConfig().getTopK()
        );
        
        PostRetrievalResult result = new PostRetrievalResult();
        result.setOriginalDocuments(request.getDocuments());
        result.setProcessedDocuments(reRankedDocs);
        result.setMetadata(Map.of(
            "re_ranking", true,
            "original_count", request.getDocuments().size(),
            "processed_count", reRankedDocs.size()
        ));
        
        return result;
    }
    
    @Override
    public List<Document> reRank(String query, List<Document> documents, int topK) {
        // 使用重排序模型对文档进行评分
        List<ScoredDocument> scoredDocuments = documents.stream()
            .map(doc -> {
                double score = reRankModel.score(query, doc);
                return new ScoredDocument(doc, score);
            })
            .sorted(Comparator.comparingDouble(ScoredDocument::getScore).reversed())
            .collect(Collectors.toList());
        
        // 返回前 topK 个文档
        return scoredDocuments.stream()
            .limit(topK)
            .map(ScoredDocument::getDocument)
            .collect(Collectors.toList());
    }
    
    /**
     * 使用 LLM 进行重排序
     */
    public List<Document> reRankWithLLM(String query, List<Document> documents, int topK) {
        String prompt = buildReRankPrompt(query, documents);
        
        String response = llmService.generate(prompt);
        
        // 解析 LLM 返回的排序结果
        return parseReRankResult(response, documents, topK);
    }
    
    private String buildReRankPrompt(String query, List<Document> documents) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请根据查询对以下文档进行相关性排序，返回最相关的文档编号（从高到低）。\n\n");
        prompt.append("查询：").append(query).append("\n\n");
        prompt.append("文档列表：\n");
        
        for (int i = 0; i < documents.size(); i++) {
            prompt.append(i + 1).append(". ").append(documents.get(i).getContent()).append("\n");
        }
        
        prompt.append("\n排序结果（文档编号，用逗号分隔）：");
        
        return prompt.toString();
    }
    
    private List<Document> parseReRankResult(String response, List<Document> documents, int topK) {
        String[] indices = response.split(",");
        List<Document> reRankedDocs = new ArrayList<>();
        
        for (String indexStr : indices) {
            int index = Integer.parseInt(indexStr.trim()) - 1;
            if (index >= 0 && index < documents.size()) {
                reRankedDocs.add(documents.get(index));
            }
            if (reRankedDocs.size() >= topK) {
                break;
            }
        }
        
        return reRankedDocs;
    }
}
```

### 6.2 上下文压缩服务

```java
@Service
public class ContextCompressionService implements PostRetrievalService {
    
    @Autowired
    private LLMService llmService;
    
    @Autowired
    private TokenCounter tokenCounter;
    
    @Override
    public PostRetrievalResult processResults(PostRetrievalRequest request) {
        String compressedContext = compressContext(
            request.getQuery(),
            request.getDocuments(),
            request.getConfig().getMaxTokens()
        );
        
        PostRetrievalResult result = new PostRetrievalResult();
        result.setOriginalDocuments(request.getDocuments());
        result.setCompressedContext(compressedContext);
        result.setMetadata(Map.of(
            "compression", true,
            "original_count", request.getDocuments().size(),
            "compressed_length", compressedContext.length()
        ));
        
        return result;
    }
    
    @Override
    public String compressContext(String query, List<Document> documents, int maxTokens) {
        // 合并文档
        String combinedContext = documents.stream()
            .map(Document::getContent)
            .collect(Collectors.joining("\n\n"));
        
        // 计算当前 token 数量
        int currentTokens = tokenCounter.count(combinedContext);
        
        // 如果不需要压缩，直接返回
        if (currentTokens <= maxTokens) {
            return combinedContext;
        }
        
        // 使用 LLM 压缩上下文
        String prompt = String.format(
            "请将以下上下文压缩到 %d 个 token 以内，保留与查询最相关的信息。" +
            "压缩后的内容应该保持结构清晰、信息完整。\n\n" +
            "查询：%s\n\n" +
            "上下文：\n%s\n\n" +
            "压缩后的上下文：",
            maxTokens,
            query,
            combinedContext
        );
        
        return llmService.generate(prompt);
    }
    
    @Override
    public String selectiveCompression(String query, List<Document> documents, int maxTokens) {
        // 对每个文档计算相关性分数
        List<ScoredDocument> scoredDocuments = documents.stream()
            .map(doc -> {
                double score = calculateRelevance(query, doc);
                return new ScoredDocument(doc, score);
            })
            .sorted(Comparator.comparingDouble(ScoredDocument::getScore).reversed())
            .collect(Collectors.toList());
        
        // 选择最相关的文档，直到达到 token 限制
        List<Document> selectedDocuments = new ArrayList<>();
        int totalTokens = 0;
        
        for (ScoredDocument scoredDoc : scoredDocuments) {
            Document doc = scoredDoc.getDocument();
            int docTokens = tokenCounter.count(doc.getContent());
            
            if (totalTokens + docTokens <= maxTokens) {
                selectedDocuments.add(doc);
                totalTokens += docTokens;
            } else {
                break;
            }
        }
        
        // 合并选中的文档
        return selectedDocuments.stream()
            .map(Document::getContent)
            .collect(Collectors.joining("\n\n"));
    }
    
    private double calculateRelevance(String query, Document document) {
        // 计算查询和文档的语义相似度
        float[] queryEmbedding = embeddingModel.embed(query);
        float[] docEmbedding = document.getEmbedding();
        
        return cosineSimilarity(queryEmbedding, docEmbedding);
    }
}
```

### 6.3 过滤服务

```java
@Service
public class FilteringService implements PostRetrievalService {
    
    @Autowired
    private DocumentRepository documentRepository;
    
    @Override
    public PostRetrievalResult processResults(PostRetrievalRequest request) {
        List<Document> filteredDocs = filterDocuments(
            request.getDocuments(),
            buildFilterCriteria(request)
        );
        
        PostRetrievalResult result = new PostRetrievalResult();
        result.setOriginalDocuments(request.getDocuments());
        result.setProcessedDocuments(filteredDocs);
        result.setMetadata(Map.of(
            "filtering", true,
            "original_count", request.getDocuments().size(),
            "processed_count", filteredDocs.size()
        ));
        
        return result;
    }
    
    @Override
    public List<Document> filterDocuments(List<Document> documents, FilterCriteria criteria) {
        List<Document> filtered = documents;
        
        // 元数据过滤
        if (criteria.getMetadataFilters() != null) {
            filtered = filtered.stream()
                .filter(doc -> matchesMetadata(doc, criteria.getMetadataFilters()))
                .collect(Collectors.toList());
        }
        
        // 时间过滤
        if (criteria.getTimeRange() != null) {
            filtered = filtered.stream()
                .filter(doc -> {
                    LocalDateTime docTime = doc.getCreatedAt();
                    return docTime.isAfter(criteria.getTimeRange().getStart()) &&
                           docTime.isBefore(criteria.getTimeRange().getEnd());
                })
                .collect(Collectors.toList());
        }
        
        // 相关性过滤
        if (criteria.getMinRelevance() != null) {
            filtered = filtered.stream()
                .filter(doc -> doc.getScore() != null && doc.getScore() >= criteria.getMinRelevance())
                .collect(Collectors.toList());
        }
        
        return filtered;
    }
    
    @Override
    public List<Document> deduplicate(List<Document> documents) {
        Map<String, Document> uniqueDocs = new LinkedHashMap<>();
        
        for (Document doc : documents) {
            String key = doc.getContent().hashCode() + "";
            if (!uniqueDocs.containsKey(key)) {
                uniqueDocs.put(key, doc);
            }
        }
        
        return new ArrayList<>(uniqueDocs.values());
    }
    
    private FilterCriteria buildFilterCriteria(PostRetrievalRequest request) {
        FilterCriteria criteria = new FilterCriteria();
        criteria.setMetadataFilters(request.getConfig().getMetadataFilters());
        criteria.setTimeRange(request.getConfig().getTimeRange());
        criteria.setMinRelevance(request.getConfig().getMinRelevance());
        return criteria;
    }
    
    private boolean matchesMetadata(Document document, Map<String, Object> filters) {
        Map<String, Object> docMetadata = document.getMetadata();
        if (docMetadata == null) {
            return filters.isEmpty();
        }
        
        for (Map.Entry<String, Object> entry : filters.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            
            if (!docMetadata.containsKey(key) || !docMetadata.get(key).equals(value)) {
                return false;
            }
        }
        
        return true;
    }
}
```

### 6.4 Post-Retrieval 管道

```java
@Service
public class PostRetrievalPipeline {
    
    @Autowired
    private ReRankingService reRankingService;
    
    @Autowired
    private ContextCompressionService contextCompressionService;
    
    @Autowired
    private FilteringService filteringService;
    
    /**
     * 执行 Post-Retrieval 管道
     */
    public PostRetrievalResult execute(PostRetrievalRequest request) {
        PostRetrievalConfig config = request.getConfig();
        List<Document> processedDocuments = request.getDocuments();
        
        // 重排序
        if (config.getReRanking()) {
            processedDocuments = reRankingService.reRank(
                request.getQuery(),
                processedDocuments,
                config.getTopK()
            );
        }
        
        // 过滤
        if (config.getFiltering()) {
            FilterCriteria criteria = buildFilterCriteria(config);
            processedDocuments = filteringService.filterDocuments(processedDocuments, criteria);
        }
        
        // 去重
        if (config.getDeduplication()) {
            processedDocuments = filteringService.deduplicate(processedDocuments);
        }
        
        // 上下文压缩
        String compressedContext = null;
        if (config.getContextCompression()) {
            compressedContext = contextCompressionService.compressContext(
                request.getQuery(),
                processedDocuments,
                config.getMaxTokens()
            );
        }
        
        PostRetrievalResult result = new PostRetrievalResult();
        result.setOriginalDocuments(request.getDocuments());
        result.setProcessedDocuments(processedDocuments);
        result.setCompressedContext(compressedContext);
        
        return result;
    }
    
    private FilterCriteria buildFilterCriteria(PostRetrievalConfig config) {
        FilterCriteria criteria = new FilterCriteria();
        criteria.setMetadataFilters(config.getMetadataFilters());
        criteria.setTimeRange(config.getTimeRange());
        criteria.setMinRelevance(config.getMinRelevance());
        return criteria;
    }
}
```

## 7. RAG 服务实现

### 7.1 Sequential RAG 服务

```java
@Service
public class SequentialRAGService implements RAGService {
    
    @Autowired
    private PreRetrievalPipeline preRetrievalPipeline;
    
    @Autowired
    private RetrievalService retrievalService;
    
    @Autowired
    private PostRetrievalPipeline postRetrievalPipeline;
    
    @Autowired
    private LLMService llmService;
    
    @Autowired
    private TokenCounter tokenCounter;
    
    @Override
    public RAGResponse generateResponse(RAGRequest request) {
        long startTime = System.currentTimeMillis();
        RAGStats stats = new RAGStats();
        stats.setRequestId(request.getRequestId());
        
        // 1. Pre-Retrieval
        long preRetrievalStart = System.currentTimeMillis();
        PreRetrievalRequest preRequest = buildPreRetrievalRequest(request);
        PreRetrievalResult preResult = preRetrievalPipeline.execute(preRequest);
        long preRetrievalTime = System.currentTimeMillis() - preRetrievalStart;
        stats.setPreRetrievalTime(preRetrievalTime);
        
        // 2. Retrieval
        long retrievalStart = System.currentTimeMillis();
        RetrievalRequest retrievalRequest = buildRetrievalRequest(request, preResult);
        List<Document> documents = retrievalService.retrieve(retrievalRequest);
        long retrievalTime = System.currentTimeMillis() - retrievalStart;
        stats.setRetrievalTime(retrievalTime);
        stats.setOriginalDocumentCount(documents.size());
        
        // 3. Post-Retrieval
        long postRetrievalStart = System.currentTimeMillis();
        PostRetrievalRequest postRequest = buildPostRetrievalRequest(request, documents);
        PostRetrievalResult postResult = postRetrievalPipeline.execute(postRequest);
        long postRetrievalTime = System.currentTimeMillis() - postRetrievalStart;
        stats.setPostRetrievalTime(postRetrievalTime);
        stats.setProcessedDocumentCount(postResult.getProcessedDocuments().size());
        
        // 4. Generation
        long generationStart = System.currentTimeMillis();
        String context = buildContext(postResult);
        String response = llmService.generate(request.getQuery(), context, request.getConfig().getGeneration());
        long generationTime = System.currentTimeMillis() - generationStart;
        stats.setGenerationTime(generationTime);
        stats.setContextTokenCount(tokenCounter.count(context));
        stats.setResponseTokenCount(tokenCounter.count(response));
        stats.setTotalTime(System.currentTimeMillis() - startTime);
        
        // 构建响应
        RAGResponse ragResponse = new RAGResponse();
        ragResponse.setRequestId(request.getRequestId());
        ragResponse.setResponse(response);
        ragResponse.setRetrievedDocuments(postResult.getProcessedDocuments());
        ragResponse.setStats(stats);
        
        return ragResponse;
    }
    
    @Override
    public List<RAGResponse> generateResponses(List<RAGRequest> requests) {
        return requests.stream()
            .map(this::generateResponse)
            .collect(Collectors.toList());
    }
    
    @Override
    public Flux<String> generateResponseStream(RAGRequest request) {
        return Flux.create(sink -> {
            try {
                RAGResponse response = generateResponse(request);
                sink.next(response.getResponse());
                sink.complete();
            } catch (Exception e) {
                sink.error(e);
            }
        });
    }
    
    @Override
    public List<Document> getRetrievedDocuments(String requestId) {
        // 从缓存或数据库中获取
        return Collections.emptyList();
    }
    
    @Override
    public RAGStats getStats(String requestId) {
        // 从缓存或数据库中获取
        return new RAGStats();
    }
    
    private PreRetrievalRequest buildPreRetrievalRequest(RAGRequest request) {
        PreRetrievalRequest preRequest = new PreRetrievalRequest();
        preRequest.setQuery(request.getQuery());
        preRequest.setUserId(request.getUserId());
        preRequest.setSessionId(request.getSessionId());
        preRequest.setContext(request.getContext());
        preRequest.setConfig(request.getConfig().getPreRetrieval());
        return preRequest;
    }
    
    private RetrievalRequest buildRetrievalRequest(RAGRequest request, PreRetrievalResult preResult) {
        RetrievalRequest retrievalRequest = new RetrievalRequest();
        
        if (preResult.getSubQueries() != null && !preResult.getSubQueries().isEmpty()) {
            retrievalRequest.setQueries(preResult.getSubQueries());
        } else if (preResult.getExpandedQueries() != null && !preResult.getExpandedQueries().isEmpty()) {
            retrievalRequest.setQueries(preResult.getExpandedQueries());
        } else {
            retrievalRequest.setQuery(preResult.getProcessedQuery());
        }
        
        retrievalRequest.setUserId(request.getUserId());
        retrievalRequest.setSessionId(request.getSessionId());
        retrievalRequest.setConfig(request.getConfig().getRetrieval());
        
        return retrievalRequest;
    }
    
    private PostRetrievalRequest buildPostRetrievalRequest(RAGRequest request, List<Document> documents) {
        PostRetrievalRequest postRequest = new PostRetrievalRequest();
        postRequest.setQuery(request.getQuery());
        postRequest.setDocuments(documents);
        postRequest.setUserId(request.getUserId());
        postRequest.setSessionId(request.getSessionId());
        postRequest.setConfig(request.getConfig().getPostRetrieval());
        return postRequest;
    }
    
    private String buildContext(PostRetrievalResult postResult) {
        if (postResult.getCompressedContext() != null) {
            return postResult.getCompressedContext();
        }
        
        return postResult.getProcessedDocuments().stream()
            .map(Document::getContent)
            .collect(Collectors.joining("\n\n"));
    }
}
```

### 7.2 Iterative RAG 服务

```java
@Service
public class IterativeRAGService implements RAGService {
    
    @Autowired
    private SequentialRAGService sequentialRAGService;
    
    @Autowired
    private LLMService llmService;
    
    @Autowired
    private QueryDecompositionService queryDecompositionService;
    
    @Override
    public RAGResponse generateResponse(RAGRequest request) {
        long startTime = System.currentTimeMillis();
        RAGStats stats = new RAGStats();
        stats.setRequestId(request.getRequestId());
        
        // 初始查询
        String currentQuery = request.getQuery();
        List<Document> allDocuments = new ArrayList<>();
        StringBuilder contextBuilder = new StringBuilder();
        int maxIterations = 3;
        
        for (int i = 0; i < maxIterations; i++) {
            // 执行 RAG
            RAGRequest iterationRequest = new RAGRequest();
            iterationRequest.setRequestId(request.getRequestId() + "-iter-" + i);
            iterationRequest.setQuery(currentQuery);
            iterationRequest.setUserId(request.getUserId());
            iterationRequest.setSessionId(request.getSessionId());
            iterationRequest.setConfig(request.getConfig());
            
            RAGResponse response = sequentialRAGService.generateResponse(iterationRequest);
            
            // 收集文档
            allDocuments.addAll(response.getRetrievedDocuments());
            
            // 判断是否需要继续迭代
            if (shouldStopIteration(response, i, maxIterations)) {
                contextBuilder.append(response.getResponse());
                break;
            }
            
            // 生成下一个查询
            currentQuery = generateNextQuery(request.getQuery(), response.getResponse(), contextBuilder.toString());
            contextBuilder.append(response.getResponse()).append("\n\n");
        }
        
        // 最终生成
        String finalContext = contextBuilder.toString();
        String finalResponse = llmService.generate(request.getQuery(), finalContext, request.getConfig().getGeneration());
        
        // 构建响应
        RAGResponse ragResponse = new RAGResponse();
        ragResponse.setRequestId(request.getRequestId());
        ragResponse.setResponse(finalResponse);
        ragResponse.setRetrievedDocuments(allDocuments);
        ragResponse.setStats(stats);
        stats.setTotalTime(System.currentTimeMillis() - startTime);
        
        return ragResponse;
    }
    
    private boolean shouldStopIteration(RAGResponse response, int iteration, int maxIterations) {
        // 如果响应包含明确的答案，停止迭代
        if (response.getResponse().contains("答案是") || 
            response.getResponse().contains("结论是") ||
            response.getResponse().contains("因此")) {
            return true;
        }
        
        // 如果达到最大迭代次数，停止
        if (iteration >= maxIterations - 1) {
            return true;
        }
        
        return false;
    }
    
    private String generateNextQuery(String originalQuery, String currentResponse, String context) {
        String prompt = String.format(
            "基于以下信息，生成下一个查询以获取更完整的答案。\n\n" +
            "原始查询：%s\n\n" +
            "当前回答：%s\n\n" +
            "已有上下文：%s\n\n" +
            "下一个查询：",
            originalQuery,
            currentResponse,
            context
        );
        
        return llmService.generate(prompt);
    }
    
    @Override
    public List<RAGResponse> generateResponses(List<RAGRequest> requests) {
        return requests.stream()
            .map(this::generateResponse)
            .collect(Collectors.toList());
    }
    
    @Override
    public Flux<String> generateResponseStream(RAGRequest request) {
        return Flux.create(sink -> {
            try {
                RAGResponse response = generateResponse(request);
                sink.next(response.getResponse());
                sink.complete();
            } catch (Exception e) {
                sink.error(e);
            }
        });
    }
    
    @Override
    public List<Document> getRetrievedDocuments(String requestId) {
        return Collections.emptyList();
    }
    
    @Override
    public RAGStats getStats(String requestId) {
        return new RAGStats();
    }
}
```

### 7.3 Adaptive RAG 服务

```java
@Service
public class AdaptiveRAGService implements RAGService {
    
    @Autowired
    private SequentialRAGService sequentialRAGService;
    
    @Autowired
    private IterativeRAGService iterativeRAGService;
    
    @Autowired
    private QueryClassifier queryClassifier;
    
    @Autowired
    private SessionAnalyzer sessionAnalyzer;
    
    @Override
    public RAGResponse generateResponse(RAGRequest request) {
        // 分析查询类型
        QueryType queryType = queryClassifier.classify(request.getQuery());
        
        // 分析会话上下文
        SessionContext sessionContext = sessionAnalyzer.analyze(request.getSessionId());
        
        // 选择 RAG 策略
        RAGStrategy strategy = selectStrategy(queryType, sessionContext);
        
        // 更新配置
        updateConfig(request, queryType, sessionContext);
        
        // 执行 RAG
        switch (strategy) {
            case SEQUENTIAL:
                return sequentialRAGService.generateResponse(request);
            case ITERATIVE:
                return iterativeRAGService.generateResponse(request);
            default:
                return sequentialRAGService.generateResponse(request);
        }
    }
    
    private RAGStrategy selectStrategy(QueryType queryType, SessionContext sessionContext) {
        // 复杂查询使用迭代 RAG
        if (queryType == QueryType.COMPLEX || queryType == QueryType.MULTI_HOP) {
            return RAGStrategy.ITERATIVE;
        }
        
        // 多轮对话使用顺序 RAG
        if (sessionContext.getMessageCount() > 1) {
            return RAGStrategy.SEQUENTIAL;
        }
        
        // 默认使用顺序 RAG
        return RAGStrategy.SEQUENTIAL;
    }
    
    private void updateConfig(RAGRequest request, QueryType queryType, SessionContext sessionContext) {
        RAGConfig config = request.getConfig();
        
        // 根据查询类型更新配置
        switch (queryType) {
            case SIMPLE:
                config.getPreRetrieval().setEnabled(false);
                config.getPostRetrieval().setEnabled(false);
                break;
            case CONVERSATIONAL:
                config.getPreRetrieval().setQueryRewriting(true);
                config.getPostRetrieval().setReRanking(true);
                break;
            case COMPLEX:
                config.getPreRetrieval().setQueryDecomposition(true);
                config.getPostRetrieval().setContextCompression(true);
                break;
            case MULTI_HOP:
                config.getPreRetrieval().setQueryExpansion(true);
                config.getPostRetrieval().setReRanking(true);
                break;
        }
        
        // 根据会话上下文更新配置
        if (sessionContext.getMessageCount() > 5) {
            config.getPostRetrieval().setContextCompression(true);
        }
    }
    
    @Override
    public List<RAGResponse> generateResponses(List<RAGRequest> requests) {
        return requests.stream()
            .map(this::generateResponse)
            .collect(Collectors.toList());
    }
    
    @Override
    public Flux<String> generateResponseStream(RAGRequest request) {
        return Flux.create(sink -> {
            try {
                RAGResponse response = generateResponse(request);
                sink.next(response.getResponse());
                sink.complete();
            } catch (Exception e) {
                sink.error(e);
            }
        });
    }
    
    @Override
    public List<Document> getRetrievedDocuments(String requestId) {
        return Collections.emptyList();
    }
    
    @Override
    public RAGStats getStats(String requestId) {
        return new RAGStats();
    }
}
```

## 8. 与 Session 上下文集成

### 8.1 SessionAware RAG 服务

```java
@Service
public class SessionAwareRAGService {
    
    @Autowired
    private AdaptiveRAGService adaptiveRAGService;
    
    @Autowired
    private ShortTermMemoryService shortTermMemoryService;
    
    @Autowired
    private EpisodicMemoryService episodicMemoryService;
    
    /**
     * 基于会话的 RAG
     */
    public String generateResponse(String sessionId, String query) {
        // 1. 获取会话上下文
        List<ChatMessage> recentMessages = shortTermMemoryService.getRecentMessages(sessionId, 10);
        
        // 2. 获取相关情景
        List<Episode> episodes = episodicMemoryService.searchEpisodes(
            SearchEpisodeRequest.builder()
                .sessionId(sessionId)
                .topK(3)
                .build()
        );
        
        // 3. 构建 RAG 请求
        RAGRequest request = buildRAGRequest(sessionId, query, recentMessages, episodes);
        
        // 4. 执行 RAG
        RAGResponse response = adaptiveRAGService.generateResponse(request);
        
        // 5. 保存到短期记忆
        shortTermMemoryService.addMessage(sessionId, AiMessage.from(response.getResponse()));
        
        // 6. 如果重要，保存到情景记忆
        if (isImportant(query, response.getResponse())) {
            saveToEpisodicMemory(sessionId, query, response.getResponse());
        }
        
        return response.getResponse();
    }
    
    private RAGRequest buildRAGRequest(String sessionId, String query, 
                                         List<ChatMessage> messages, List<Episode> episodes) {
        RAGRequest request = new RAGRequest();
        request.setRequestId(UUID.randomUUID().toString());
        request.setQuery(query);
        request.setSessionId(sessionId);
        request.setContext(messages);
        request.setConfig(buildRAGConfig(messages, episodes));
        return request;
    }
    
    private RAGConfig buildRAGConfig(List<ChatMessage> messages, List<Episode> episodes) {
        RAGConfig config = new RAGConfig();
        config.setStrategy(RAGStrategy.ADAPTIVE);
        
        // Pre-Retrieval 配置
        PreRetrievalConfig preConfig = new PreRetrievalConfig();
        preConfig.setEnabled(true);
        
        // 如果有多轮对话，启用查询重写
        if (messages.size() > 1) {
            preConfig.setQueryRewriting(true);
        }
        
        // 如果有相关情景，启用查询扩展
        if (!episodes.isEmpty()) {
            preConfig.setQueryExpansion(true);
        }
        
        config.setPreRetrieval(preConfig);
        
        // Retrieval 配置
        RetrievalConfig retrievalConfig = new RetrievalConfig();
        retrievalConfig.setType(RetrievalType.HYBRID);
        retrievalConfig.setTopK(10);
        config.setRetrieval(retrievalConfig);
        
        // Post-Retrieval 配置
        PostRetrievalConfig postConfig = new PostRetrievalConfig();
        postConfig.setEnabled(true);
        postConfig.setReRanking(true);
        postConfig.setContextCompression(true);
        postConfig.setTopK(5);
        postConfig.setMaxTokens(4000);
        config.setPostRetrieval(postConfig);
        
        // Generation 配置
        GenerationConfig genConfig = new GenerationConfig();
        genConfig.setTemperature(0.7);
        genConfig.setMaxTokens(2000);
        config.setGeneration(genConfig);
        
        return config;
    }
    
    private boolean isImportant(String query, String response) {
        // 判断是否重要
        return query.length() > 50 || 
               response.contains("重要") || 
               response.contains("记住");
    }
    
    private void saveToEpisodicMemory(String sessionId, String query, String response) {
        CreateEpisodeRequest request = new CreateEpisodeRequest();
        request.setSessionId(sessionId);
        request.setType(EpisodeType.CONVERSATION);
        request.setTitle(query.substring(0, Math.min(50, query.length())));
        request.setDescription(response);
        request.setContext(Map.of(
            "query", query,
            "response", response
        ));
        
        episodicMemoryService.createEpisode(request);
    }
}
```

### 8.2 查询分类器

```java
@Service
public class QueryClassifier {
    
    @Autowired
    private LLMService llmService;
    
    /**
     * 分类查询类型
     */
    public QueryType classify(String query) {
        String prompt = String.format(
            "请将以下查询分类为以下类型之一：\n" +
            "- SIMPLE: 简单查询，可以直接回答\n" +
            "- CONVERSATIONAL: 对话式查询，依赖上下文\n" +
            "- COMPLEX: 复杂查询，需要分解\n" +
            "- MULTI_HOP: 多跳查询，需要多次检索\n\n" +
            "查询：%s\n\n" +
            "类型：",
            query
        );
        
        String response = llmService.generate(prompt);
        
        return parseQueryType(response);
    }
    
    private QueryType parseQueryType(String response) {
        String type = response.toUpperCase().trim();
        
        if (type.contains("SIMPLE")) {
            return QueryType.SIMPLE;
        } else if (type.contains("CONVERSATIONAL")) {
            return QueryType.CONVERSATIONAL;
        } else if (type.contains("COMPLEX")) {
            return QueryType.COMPLEX;
        } else if (type.contains("MULTI_HOP") || type.contains("MULTI-HOP")) {
            return QueryType.MULTI_HOP;
        }
        
        return QueryType.SIMPLE;
    }
}
```

### 8.3 会话分析器

```java
@Service
public class SessionAnalyzer {
    
    @Autowired
    private ShortTermMemoryService shortTermMemoryService;
    
    /**
     * 分析会话上下文
     */
    public SessionContext analyze(String sessionId) {
        List<ChatMessage> messages = shortTermMemoryService.getAllMessages(sessionId);
        
        SessionContext context = new SessionContext();
        context.setSessionId(sessionId);
        context.setMessageCount(messages.size());
        
        // 分析会话类型
        context.setSessionType(analyzeSessionType(messages));
        
        // 分析主题
        context.setTopics(analyzeTopics(messages));
        
        // 分析用户意图
        context.setUserIntents(analyzeUserIntents(messages));
        
        return context;
    }
    
    private SessionType analyzeSessionType(List<ChatMessage> messages) {
        if (messages.isEmpty()) {
            return SessionType.NEW;
        }
        
        // 检查是否是任务执行
        boolean isTaskExecution = messages.stream()
            .anyMatch(msg -> msg.text().contains("执行") || 
                               msg.text().contains("完成") ||
                               msg.text().contains("任务"));
        
        if (isTaskExecution) {
            return SessionType.TASK_EXECUTION;
        }
        
        // 检查是否是知识查询
        boolean isKnowledgeQuery = messages.stream()
            .anyMatch(msg -> msg.text().contains("什么是") || 
                               msg.text().contains("如何") ||
                               msg.text().contains("为什么"));
        
        if (isKnowledgeQuery) {
            return SessionType.KNOWLEDGE_QUERY;
        }
        
        return SessionType.CONVERSATION;
    }
    
    private List<String> analyzeTopics(List<ChatMessage> messages) {
        // 简单实现：从消息中提取关键词
        return messages.stream()
            .map(ChatMessage::text)
            .flatMap(text -> Arrays.stream(text.split("\\s+")))
            .filter(word -> word.length() > 3)
            .distinct()
            .limit(5)
            .collect(Collectors.toList());
    }
    
    private List<String> analyzeUserIntents(List<ChatMessage> messages) {
        // 简单实现：从用户消息中提取意图
        return messages.stream()
            .filter(msg -> msg instanceof UserMessage)
            .map(ChatMessage::text)
            .distinct()
            .limit(3)
            .collect(Collectors.toList());
    }
}
```

## 9. 配置管理

### 9.1 配置文件

```yaml
# application.yml
rag:
  # 默认策略
  default-strategy: ADAPTIVE
  
  # Pre-Retrieval 配置
  pre-retrieval:
    enabled: true
    query-rewriting: true
    query-expansion: false
    hyde: false
    query-decomposition: false
    max-expansions: 5
    max-sub-queries: 3
  
  # Retrieval 配置
  retrieval:
    type: HYBRID
    top-k: 10
    min-score: 0.5
    time-range:
      enabled: false
      days: 30
  
  # Post-Retrieval 配置
  post-retrieval:
    enabled: true
    re-ranking: true
    context-compression: true
    filtering: true
    deduplication: true
    top-k: 5
    max-tokens: 4000
    min-relevance: 0.6
  
  # Generation 配置
  generation:
    model: gpt-4
    temperature: 0.7
    max-tokens: 2000
    top-p: 0.9
    top-k: 40
    system-prompt: "你是一个有用的助手，请根据提供的上下文回答问题。"
  
  # 会话配置
  session:
    context-window: 10
    enable-episodic-memory: true
    enable-query-classification: true
```

### 9.2 配置类

```java
@Configuration
@ConfigurationProperties(prefix = "rag")
@Data
public class RAGProperties {
    
    private RAGStrategy defaultStrategy;
    private PreRetrievalProperties preRetrieval;
    private RetrievalProperties retrieval;
    private PostRetrievalProperties postRetrieval;
    private GenerationProperties generation;
    private SessionProperties session;
    
    @Data
    public static class PreRetrievalProperties {
        private Boolean enabled = true;
        private Boolean queryRewriting = false;
        private Boolean queryExpansion = false;
        private Boolean hyde = false;
        private Boolean queryDecomposition = false;
        private Integer maxExpansions = 5;
        private Integer maxSubQueries = 3;
    }
    
    @Data
    public static class RetrievalProperties {
        private RetrievalType type = RetrievalType.HYBRID;
        private Integer topK = 10;
        private Double minScore = 0.5;
        private TimeRangeProperties timeRange;
    }
    
    @Data
    public static class TimeRangeProperties {
        private Boolean enabled = false;
        private Integer days = 30;
    }
    
    @Data
    public static class PostRetrievalProperties {
        private Boolean enabled = true;
        private Boolean reRanking = true;
        private Boolean contextCompression = true;
        private Boolean filtering = true;
        private Boolean deduplication = true;
        private Integer topK = 5;
        private Integer maxTokens = 4000;
        private Double minRelevance = 0.6;
    }
    
    @Data
    public static class GenerationProperties {
        private String model = "gpt-4";
        private Double temperature = 0.7;
        private Integer maxTokens = 2000;
        private Double topP = 0.9;
        private Integer topK = 40;
        private String systemPrompt;
    }
    
    @Data
    public static class SessionProperties {
        private Integer contextWindow = 10;
        private Boolean enableEpisodicMemory = true;
        private Boolean enableQueryClassification = true;
    }
}
```

## 10. API 设计

### 10.1 RAG API

```java
@RestController
@RequestMapping("/api/rag")
public class RAGController {
    
    @Autowired
    private SessionAwareRAGService ragService;
    
    @PostMapping("/generate")
    public ResponseEntity<RAGResponse> generateResponse(@RequestBody RAGRequest request) {
        RAGResponse response = ragService.generateResponse(
            request.getSessionId(),
            request.getQuery()
        );
        return ResponseEntity.ok(response);
    }
    
    @PostMapping("/stream")
    public Flux<String> generateResponseStream(@RequestBody RAGRequest request) {
        return ragService.generateResponseStream(
            request.getSessionId(),
            request.getQuery()
        );
    }
    
    @GetMapping("/documents/{requestId}")
    public ResponseEntity<List<Document>> getRetrievedDocuments(@PathVariable String requestId) {
        List<Document> documents = ragService.getRetrievedDocuments(requestId);
        return ResponseEntity.ok(documents);
    }
    
    @GetMapping("/stats/{requestId}")
    public ResponseEntity<RAGStats> getStats(@PathVariable String requestId) {
        RAGStats stats = ragService.getStats(requestId);
        return ResponseEntity.ok(stats);
    }
}
```

## 11. 数据库设计

### 11.1 文档表

```sql
CREATE TABLE documents (
    id VARCHAR(100) PRIMARY KEY,
    content TEXT NOT NULL,
    title VARCHAR(255),
    url VARCHAR(500),
    metadata JSON,
    embedding VECTOR(1536),
    score DOUBLE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    INDEX idx_content (content(255)),
    INDEX idx_title (title),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 11.2 RAG 请求表

```sql
CREATE TABLE rag_requests (
    id VARCHAR(100) PRIMARY KEY,
    user_id VARCHAR(100) NOT NULL,
    session_id VARCHAR(100),
    query TEXT NOT NULL,
    strategy VARCHAR(50) NOT NULL,
    config JSON,
    response TEXT,
    stats JSON,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    INDEX idx_user_id (user_id),
    INDEX idx_session_id (session_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

## 12. 最佳实践

### 12.1 Pre-Retrieval 最佳实践

1. **查询重写**
   - 使用用户历史对话上下文
   - 保持查询的原始意图
   - 避免过度重写

2. **查询扩展**
   - 生成 3-5 个相关查询
   - 使用领域知识库
   - 控制扩展数量

3. **HyDE**
   - 生成高质量的假设性文档
   - 适用于专业领域
   - 需要额外的 LLM 调用

### 12.2 Post-Retrieval 最佳实践

1. **重排序**
   - 使用专门的重排序模型
   - 也可以使用 LLM
   - 控制重排序的文档数量

2. **上下文压缩**
   - 保留关键信息
   - 避免信息丢失
   - 考虑使用选择性压缩

3. **过滤**
   - 基于元数据过滤
   - 基于时间过滤
   - 基于相关性过滤

### 12.3 场景配置

#### 简单问答
```yaml
rag:
  pre-retrieval:
    enabled: false
  post-retrieval:
    enabled: false
```

#### 多轮对话
```yaml
rag:
  pre-retrieval:
    query-rewriting: true
  post-retrieval:
    re-ranking: true
    context-compression: true
```

#### 专业领域
```yaml
rag:
  pre-retrieval:
    query-expansion: true
    hyde: true
  post-retrieval:
    re-ranking: true
    filtering: true
```

#### 复杂推理
```yaml
rag:
  default-strategy: ITERATIVE
  pre-retrieval:
    query-decomposition: true
  post-retrieval:
    context-compression: true
```

## 13. 实施计划

### Phase 1: 基础设施（1周）
- 设计数据模型
- 创建数据库表
- 实现向量存储
- 实现 Embedding 模型

### Phase 2: 核心组件（2周）
- 实现 Pre-Retrieval 组件
- 实现 Retrieval 组件
- 实现 Post-Retrieval 组件
- 实现 RAG 服务

### Phase 3: 集成与优化（1周）
- 与 Session 集成
- 与 Memory 集成
- 性能优化
- 监控和日志

### Phase 4: 测试与部署（1周）
- 单元测试
- 集成测试
- 性能测试
- 部署到生产环境

## 14. 扩展方向

### 14.1 多模态 RAG
- 支持图像检索
- 支持音频检索
- 支持视频检索

### 14.2 知识图谱 RAG
- 支持知识图谱检索
- 支持多跳推理
- 支持实体链接

### 14.3 分布式 RAG
- 支持分布式检索
- 支持负载均衡
- 支持容错恢复

### 14.4 实时 RAG
- 支持实时索引更新
- 支持流式检索
- 支持增量更新
