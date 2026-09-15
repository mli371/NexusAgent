import { fireEvent, render, screen } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { AnswerText, evidenceParts, QueryInspector } from "./QueryInspector";
import { QueryFlow } from "./QueryFlow";
import { citation, parent, queryEvents, queryRequest, queryResponse, semanticResponse, traceId } from "../test/queryFixtures";
import type { QueryTurn } from "./types";

it("uses global exclusive UTF-16 offsets to highlight the child inside a trimmed parent", () => {
  expect(evidenceParts(parent, citation).match).toBe("Approval is required");
  expect(evidenceParts({ ...parent, charStart: 10, charEnd: 17, text: "A😀BCDE" }, { ...citation, charStart: 11, charEnd: 14 }).match).toBe("😀B");
  const turn: QueryTurn = { traceId, request: queryRequest, status: "completed", events: queryEvents(), response: queryResponse() };
  render(<QueryInspector turn={turn} stageId="context_building" citation={citation} active onCloseCitation={vi.fn()} />);
  expect(screen.getByTestId("citation-evidence").querySelector("mark")).toHaveTextContent("Approval is required");
  expect(screen.getByText("父块已裁剪", { exact: false })).toBeVisible();
});
it("renders untrusted model text as text, and only known citation markers are interactive", () => {
  const click = vi.fn();
  const { container } = render(<AnswerText text={'<img src=x onerror="alert(1)"> [C1] [C99]'} citations={[citation]} onCitation={click} />);
  expect(container.querySelector("img")).toBeNull();
  fireEvent.click(screen.getByRole("button", { name: "[C1]" }));
  expect(click).toHaveBeenCalledWith(citation);
  expect(screen.queryByRole("button", { name: "[C99]" })).toBeNull();
});
it("keeps parallel branch states independent and does not invent completion", () => {
  const events = queryEvents().slice(0, 9);
  const turn: QueryTurn = { traceId, request: queryRequest, status: "running", events };
  const { container } = render(<QueryFlow turn={turn} selection="query_embedding" onSelect={vi.fn()} active />);
  expect(container.querySelector('[data-stage="query_embedding"]')).toHaveAttribute("data-state", "running");
  expect(container.querySelector('[data-stage="full_text_search"]')).toHaveAttribute("data-state", "succeeded");
  expect(container.querySelector('[data-stage="vector_search"]')).toHaveAttribute("data-state", "pending");
});

it("labels reused stages as not executed and identifies cached candidate data", () => {
  const response = queryResponse(); response.retrievalCacheStatus = "hit";
  response.stages = response.stages.filter(s => s.stage === "vector_search").slice(0, 1)
    .map(s => ({ ...s, status: "skipped", durationMs: 0, summary: { reason: "cache_reuse" } }));
  const turn: QueryTurn = { traceId, request: queryRequest, status: "completed", response, events: queryEvents(response) };
  const { container } = render(<><QueryFlow turn={turn} selection="vector_search" onSelect={vi.fn()} active />
    <QueryInspector turn={turn} stageId="vector_search" active onCloseCitation={vi.fn()} /></>);
  expect(container.querySelector('[data-stage="vector_search"]')).toHaveTextContent("缓存复用未执行");
  expect(container.querySelector('[data-stage="vector_search"]')).not.toHaveTextContent("0 ms");
  expect(screen.getByText(/来自上下文缓存/)).toBeVisible();
});

it("shows semantic similarity without hiding the embedding call or relabeling historical scores", () => {
  const response = semanticResponse();
  const turn: QueryTurn = { traceId, request: queryRequest, status: "completed", response, events: queryEvents(response) };
  const { container, rerender } = render(<><QueryFlow turn={turn} selection="semantic_cache_lookup" onSelect={vi.fn()} active />
    <QueryInspector turn={turn} stageId="semantic_cache_lookup" active onCloseCitation={vi.fn()} /></>);
  expect(container.querySelector('[data-stage="semantic_cache_lookup"]')).toHaveTextContent("语义命中");
  expect(container.querySelector('[data-stage="query_embedding"]')).toHaveAttribute("data-state", "succeeded");
  expect(container.querySelector('[data-stage="vector_search"]')).toHaveTextContent("缓存复用未执行");
  expect(container.querySelector("#query-detail")).toHaveTextContent('"similarity": 0.98');
  rerender(<QueryInspector turn={turn} stageId="reranking" active onCloseCitation={vi.fn()} />);
  expect(screen.getByText(/历史检索与重排分数不属于本次问题/)).toBeVisible();
});
