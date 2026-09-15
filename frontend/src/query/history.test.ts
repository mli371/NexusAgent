import { expect, it } from "vitest";
import { queryRequest, queryResponse, withResolution } from "../test/queryFixtures";
import { recentHistory, sameScope } from "./history";
import type { QueryTurn } from "./types";

const turn = (question: string): QueryTurn => ({ traceId: crypto.randomUUID(), request: { ...queryRequest, question }, status: "completed",
  events: [], response: queryResponse() });

it("selects the latest three completed answered turns in chronological order within the same session and scope", () => {
  const turns = [turn("one"), turn("two"), turn("three"), turn("four"), turn("foreign"), turn("failed"), turn("empty")];
  turns[4].request.sessionId = "other"; turns[5].status = "failed"; turns[6].response!.answerStatus = "insufficient_context";
  expect(recentHistory(turns, queryRequest).map(t => t.question)).toEqual(["two", "three", "four"]);
  expect(recentHistory(turns, { ...queryRequest, scope: "documents", documentIds: ["x"] })).toEqual([]);
});
it("retains a standalone anchor across repeated short follow-ups without carrying context or recursive requests", () => {
  const previous = turn("What about them?");
  previous.response = withResolution(queryResponse(), { ...previous.request, history: [{ question: "Launches?", answer: "Products", answerTruncated: false }] }, "Apple launch differences?");
  const history = recentHistory([previous], queryRequest);
  expect(history[0].question).toBe("Apple launch differences?");
  expect(Object.keys(history[0]).sort()).toEqual(["answer", "answerTruncated", "question"]);
});
it("bounds answer excerpts without breaking surrogate pairs and excludes clarification/refusal/cancelled turns", () => {
  const previous = turn("q"); previous.response!.answer = "a".repeat(999) + "😀rest";
  const answer = recentHistory([previous], queryRequest)[0];
  expect(answer.answer).toHaveLength(999); expect(answer.answerTruncated).toBe(true);
  for (const status of ["needs_clarification", "refused"] as const) {
    previous.response!.answerStatus = status; expect(recentHistory([previous], queryRequest)).toEqual([]);
  }
  previous.status = "interrupted"; previous.response!.answerStatus = "answered";
  expect(recentHistory([previous], queryRequest)).toEqual([]);
});
it("compares document scope as a set; parameters are not history identity", () => {
  expect(sameScope({ scope: "documents", documentIds: ["b", "a"] }, { scope: "documents", documentIds: ["a", "b", "a"] })).toBe(true);
  expect(sameScope({ scope: "documents", documentIds: ["b", "a"] }, { scope: "documents", documentIds: ["a"] })).toBe(false);
  expect(recentHistory([turn("q")], { ...queryRequest, topK: 10 } as typeof queryRequest)).toHaveLength(1);
});
