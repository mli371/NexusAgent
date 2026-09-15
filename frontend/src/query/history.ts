import type { ConversationTurn, QueryRequest, QueryTurn } from "./types";

export function sameScope(left: Pick<QueryRequest, "scope" | "documentIds">, right: Pick<QueryRequest, "scope" | "documentIds">): boolean {
  const ids = (values: string[]) => [...new Set(values)].sort().join(",");
  return left.scope === right.scope && (left.scope === "library" || ids(left.documentIds) === ids(right.documentIds));
}

export function recentHistory(turns: QueryTurn[], request: Pick<QueryRequest, "sessionId" | "scope" | "documentIds">): ConversationTurn[] {
  return turns.filter(turn => turn.status === "completed" && turn.response?.answerStatus === "answered"
    && turn.request.sessionId === request.sessionId && sameScope(turn.request, request)).slice(-3).map(turn => {
    const text = turn.response!.answer;
    let end = Math.min(1000, text.length);
    if (end < text.length && /[\uD800-\uDBFF]/.test(text[end - 1]) && /[\uDC00-\uDFFF]/.test(text[end])) end--;
    return { question: turn.response!.queryResolution?.resolvedQuestion ?? turn.request.question,
      answer: text.slice(0, end), answerTruncated: end < text.length };
  });
}
