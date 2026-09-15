import type { QueryTurn } from "./types";

export const lessons = {
  access_check: { title: "访问范围", method: "LiveQueryGuard.librarySnapshot / access", file: "query/live/LiveQueryGuard.java", why: "后端按 tenant 与 PRIVATE owner 过滤；全库模式读取可访问文档的就绪快照。前端选择不是授权凭据。" },
  embedding_readiness: { title: "向量就绪", method: "LiveQueryGuard.selectReady / readiness", file: "query/live/LiveQueryGuard.java", why: "检查所有 child 的 provider、model、dimension。全库排除未就绪文档，指定模式则要求全部就绪。" },
  cache_lookup: { title: "缓存策略", method: "LiveContextService.load", file: "query/live/LiveContextService.java", why: "按 tenant、actor、文档版本及检索参数复用有界上下文。命中仍复查数据库权限和证据，仍调用回答模型；Redis 故障则重新检索。" },
  query_embedding: { title: "问题向量", method: "EmbeddingService.embed", file: "embeddings/application/EmbeddingService.java", why: "问题与 child 使用同一模型空间。向量用于相似度搜索，不作为文本发送给回答模型。" },
  vector_search: { title: "向量检索", method: "VectorSearchRepository.search", file: "embeddings/repository/VectorSearchRepository.java", why: "按 cosine distance 升序。SQL 在 LIMIT 前过滤文档范围、tenant/owner 和模型标识。" },
  full_text_search: { title: "全文检索", method: "FullTextSearchRepository.search", file: "retrieval/repository/FullTextSearchRepository.java", why: "使用 PostgreSQL English 全文搜索，按 ts_rank_cd 降序；和问题向量分支并发。" },
  rrf_fusion: { title: "RRF 融合", method: "RrfFusionService.fuse", file: "retrieval/application/RrfFusionService.java", why: "按 child ID 合并排名，score = Σ 1/(k + rank)，默认 k=60，rank 从 1 开始；不比较两类原始分数。" },
  reranking: { title: "候选重排", method: "DeterministicHeuristicReranker.rerank", file: "context/application/DeterministicHeuristicReranker.java", why: "结合关键词、来源与多样性重新排序。这是启发式，不是训练得到的 cross-encoder。" },
  parent_expansion: { title: "父块展开", method: "ParentContextExpansionService.expand", file: "context/application/ParentContextExpansionService.java", why: "child 是检索单位；根据 parentChunkId 取回较完整语境，并再次确认文档访问。" },
  context_building: { title: "证据上下文", method: "ContextBuilder.buildResult", file: "context/application/ContextBuilder.java", why: "按父块去重、围绕命中 child 裁剪。预算是正文字符数，引用标题另有开销，不是模型 token 数。" },
  answer_generation: { title: "模型回答", method: "OpenAiAnswerGenerator.generate", file: "query/application/OpenAiAnswerGenerator.java", why: "只外发问题与有界证据。空证据跳过调用；真实调用失败不回退模板，也不自动重试收费请求。" },
  citation_validation: { title: "引用校验", method: "AnswerCitationValidator.validate", file: "query/live/AnswerCitationValidator.java", why: "校验正文 marker 与声明引用集合，关联 selected child 和 expanded parent。结构一致不代表逐句事实性验证。" },
} as const;
export type StageId = keyof typeof lessons;
export const stageIds = Object.keys(lessons) as StageId[];

export function stageData(id: StageId, turn?: QueryTurn) {
  const result = turn?.response;
  const stage = turn?.events.filter(e => e.stage?.stage === id).at(-1)?.stage;
  const output = result ? ({
    access_check: result.scope,
    embedding_readiness: result.scope,
    cache_lookup: { retrievalCacheStatus: result.retrievalCacheStatus, ...stage?.summary },
    vector_search: result.retrievalDebug?.vectorCandidates,
    full_text_search: result.retrievalDebug?.fullTextCandidates,
    rrf_fusion: result.retrievalDebug?.fusedCandidates,
    reranking: result.contextDebug.rerankedCandidates,
    parent_expansion: result.contextDebug.expandedParentContexts,
    context_building: { ...result.contextDebug.debugMetadata, finalContextText: result.finalContextText },
    answer_generation: { answer: result.answer, answerStatus: result.answerStatus, model: result.answerModel },
    citation_validation: result.citations,
    query_embedding: stage?.summary,
  }[id]) : stage?.summary;
  return { stage, output };
}
