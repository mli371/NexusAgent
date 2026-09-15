import type { QueryTurn } from "./types";

export const lessons = {
  query_resolution: { title: "问题补全", method: "OpenAiQuestionResolver.resolve", file: "query/application/OpenAiQuestionResolver.java", why: "首问跳过；最近最多三轮仅帮助补全指代，不是事实证据。独立问题进入缓存和检索；歧义请求澄清，失败不自动重试。" },
  access_check: { title: "访问范围", method: "LiveQueryGuard.librarySnapshot / access", file: "query/live/LiveQueryGuard.java", why: "后端按 tenant 与 PRIVATE owner 过滤；全库模式读取可访问文档的就绪快照。前端选择不是授权凭据。" },
  embedding_readiness: { title: "向量就绪", method: "LiveQueryGuard.selectReady / readiness", file: "query/live/LiveQueryGuard.java", why: "检查所有 child 的 provider、model、dimension。全库排除未就绪文档，指定模式则要求全部就绪。" },
  cache_lookup: { title: "精确缓存", method: "LiveContextService.load", file: "query/live/LiveContextService.java", why: "问题文本、tenant、actor、文档版本及检索参数完全匹配时复用上下文。命中不生成问题向量，仍复查数据库权限与证据并调用回答模型。" },
  semantic_cache_lookup: { title: "语义缓存", method: "LiveContextService.semanticLookup", file: "query/live/LiveContextService.java", why: "同一访问范围内比较有界历史问题向量，检查阈值及词面约束。命中不转存或续期；相似度不是正确率，历史候选分数不是本次计算。" },
  query_embedding: { title: "问题向量", method: "EmbeddingService.embed", file: "embeddings/application/EmbeddingService.java", why: "问题与 child 使用同一模型空间。向量用于相似度搜索，不作为文本发送给回答模型。" },
  vector_search: { title: "向量检索", method: "VectorSearchRepository.search", file: "embeddings/repository/VectorSearchRepository.java", why: "按 cosine distance 升序。SQL 在 LIMIT 前过滤文档范围、tenant/owner 和模型标识。" },
  full_text_search: { title: "全文检索", method: "FullTextSearchRepository.search", file: "retrieval/repository/FullTextSearchRepository.java", why: "使用 PostgreSQL English 全文搜索，按 ts_rank_cd 降序，与向量检索并发。语义缓存未命中时向量已经生成，不再重复调用 embedding。" },
  rrf_fusion: { title: "RRF 融合", method: "RrfFusionService.fuse", file: "retrieval/application/RrfFusionService.java", why: "按 child ID 合并排名，score = Σ 1/(k + rank)，默认 k=60，rank 从 1 开始；不比较两类原始分数。" },
  reranking: { title: "候选重排", method: "DeterministicHeuristicReranker.rerank", file: "context/application/DeterministicHeuristicReranker.java", why: "结合关键词、来源与多样性重新排序。这是启发式，不是训练得到的 cross-encoder。" },
  child_selection: { title: "Child 选择", method: "ContextBudgetAllocator.select", file: "context/application/ContextBudgetAllocator.java", why: "加载授权候选，按 parent 去重，先预留完整 child 证据。原文都装不下时明确排除，不用残缺片段假装覆盖。" },
  parent_expansion: { title: "父块展开", method: "ContextBudgetAllocator.expand", file: "context/application/ContextBudgetAllocator.java", why: "完整 child 先获得保留空间，再均衡扩展周边 parent。短 parent 未用额度重新分配，不让高排名大父块吃完预算。" },
  context_building: { title: "证据上下文", method: "ContextBuilder.buildResult", file: "context/application/ContextBuilder.java", why: "组装本次已纳入证据和引用，记录裁剪与排除原因。正文字符预算不含引用标题，不是 token 数；候选覆盖不等于知识库完整覆盖。" },
  answer_generation: { title: "模型回答", method: "OpenAiAnswerGenerator.generate", file: "query/application/OpenAiAnswerGenerator.java", why: "只外发问题与有界证据。空证据跳过调用；真实调用失败不回退模板，也不自动重试收费请求。" },
  citation_validation: { title: "引用校验", method: "AnswerCitationValidator.validate", file: "query/live/AnswerCitationValidator.java", why: "校验正文 marker 与声明引用集合，关联 selected child 和 expanded parent。结构一致不代表逐句事实性验证。" },
} as const;
export type StageId = keyof typeof lessons;
export const stageIds = Object.keys(lessons) as StageId[];

export function stageData(id: StageId, turn?: QueryTurn) {
  const result = turn?.response;
  const stage = turn?.events.filter(e => e.stage?.stage === id).at(-1)?.stage;
  const output = result ? ({
    query_resolution: result.queryResolution ?? stage?.summary,
    access_check: result.scope,
    embedding_readiness: result.scope,
    cache_lookup: { retrievalCacheStatus: result.retrievalCacheStatus, ...stage?.summary },
    semantic_cache_lookup: stage?.summary,
    vector_search: result.retrievalDebug?.vectorCandidates,
    full_text_search: result.retrievalDebug?.fullTextCandidates,
    rrf_fusion: result.retrievalDebug?.fusedCandidates,
    reranking: result.contextDebug.rerankedCandidates,
    child_selection: { selectedChildChunks: result.contextDebug.selectedChildChunks, ...result.contextDebug.debugMetadata },
    parent_expansion: result.contextDebug.expandedParentContexts,
    context_building: { ...result.contextDebug.debugMetadata, finalContextText: result.finalContextText },
    answer_generation: { answer: result.answer, answerStatus: result.answerStatus, model: result.answerModel },
    citation_validation: result.citations,
    query_embedding: stage?.summary,
  }[id]) : stage?.summary;
  return { stage, output };
}
