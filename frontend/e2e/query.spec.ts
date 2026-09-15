import { test, expect, type Page } from "@playwright/test";
import { document, documentId } from "../src/test/fixtures";
import { capabilities, embeddingStatus, queryEvents, queryResponse, semanticResponse, coverageResponse, sse, withResolution } from "../src/test/queryFixtures";
import type { QueryRequest, StageEvent } from "../src/query/types";

const missingId = "77777777-7777-4777-8777-777777777777";
async function backend(page: Page, mode: "ready" | "offline" | "interrupted" | "held" | "denied" | "cache" | "semantic" | "followUp" | "coverage" | "excluded" = "ready") {
  const calls: QueryRequest[] = [];
  let rebuilds = 0, release: (() => void) | undefined;
  await page.route("**/api/v1/**", async route => {
    const req = route.request(), path = new URL(req.url()).pathname;
    if (path === "/api/v1/query/capabilities") return route.fulfill({ json: mode === "offline"
      ? { ...capabilities, liveQueryReady: false, activeAnswerGenerator: "local-template" } : capabilities });
    if (path === "/api/v1/documents") return route.fulfill({ json: req.headers()["x-tenant-id"] === "default"
      ? [document, { ...document, id: missingId, originalFilename: "not-ready.md" }] : [] });
    if (path.endsWith("/embedding-status")) return route.fulfill({ json: path.includes(missingId)
      ? { ...embeddingStatus, documentId: missingId, complete: false, matchingChildChunkCount: 0, mismatchedChildChunkCount: 3 } : embeddingStatus });
    if (path.endsWith("/embed")) { rebuilds++; return route.fulfill({ json: embeddingStatus }); }
    if (path === "/api/v1/query/stream") {
      const input = req.postDataJSON() as QueryRequest; calls.push(input);
      expect(req.headers()["x-tenant-id"]).toBe("default");
      expect(req.headers()["x-actor-id"]).toBe("anonymous");
      if (mode === "denied") return route.fulfill({ status: 404, json: { code: "DOCUMENT_NOT_ACCESSIBLE", message: "Access changed" } });
      if (mode === "held") await new Promise<void>(resolve => { release = resolve; });
      const response = mode === "coverage" || mode === "excluded" ? coverageResponse(req.headers()["x-trace-id"], mode === "excluded")
        : queryResponse(req.headers()["x-trace-id"], input.scope);
      if (mode === "cache") {
        response.retrievalCacheStatus = calls.length === 1 ? "miss" : "hit";
        const reused = new Set(["query_embedding", "vector_search", "full_text_search", "rrf_fusion", "reranking", "child_selection", "parent_expansion", "context_building"]);
        response.stages = response.stages.filter(s => response.retrievalCacheStatus !== "hit" || !reused.has(s.stage) || s.status === "succeeded")
          .map((s, index): StageEvent => ({ ...s, sequence: index + 1, ...(s.stage === "cache_lookup"
            ? { status: "succeeded" as const, summary: { cacheStatus: response.retrievalCacheStatus, reason: "fixture" } }
            : response.retrievalCacheStatus === "hit" && reused.has(s.stage)
              ? { status: "skipped" as const, durationMs: 0, summary: { reason: "cache_reuse" } } : {}) }));
      }
      if (mode === "semantic") {
        Object.assign(response, calls.length > 1 ? semanticResponse(req.headers()["x-trace-id"], input.scope) : { retrievalCacheStatus: "miss" });
      }
      withResolution(response, input, mode === "followUp" && input.history?.length ? "苹果近几年秋季发布会有哪些差异？" : input.question,
        mode === "followUp" && input.question === "那个呢？" ? "你指的是哪一款产品？" : undefined);
      const events = queryEvents(response);
      return route.fulfill({ contentType: "text/event-stream", body: sse(mode === "interrupted" ? events.slice(0, -1) : events) }).catch(() => undefined);
    }
    return route.fulfill({ status: 404, json: { code: "NOT_FOUND", message: "No fixture for this route" } });
  });
  return { calls, get rebuilds() { return rebuilds; }, release: () => release?.() };
}
async function ask(page: Page, question = "What does the synthetic policy require?") {
  await page.getByRole("textbox", { name: "知识库问题" }).fill(question);
  await page.getByRole("checkbox", { name: /允许外发/ }).check();
  await page.getByRole("button", { name: "发送问题", exact: true }).click();
}
async function noOverflow(page: Page) {
  expect(await page.evaluate(() => window.document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  const footer = await page.locator(".qa-page > .bottom").boundingBox();
  const content = await page.locator(".qa-workspace .mobile-active").boundingBox();
  if (footer && content) expect(footer.y).toBeGreaterThanOrEqual(content.y + content.height - 1);
}

test("default library scope, real event diagram, retrieval inspector and child evidence", async ({ page }) => {
  const api = await backend(page);
  await page.goto("/");
  await expect(page.getByRole("button", { name: "整个知识库" })).toHaveAttribute("aria-pressed", "true");
  await ask(page);
  await expect(page.locator(".qa-answer-text")).toContainText("Approval is required");
  expect(api.calls).toHaveLength(1); expect(api.calls[0]).toMatchObject({ scope: "library", documentIds: [], debug: true });
  await expect(page.getByText("检索 1 / 3 份文档 · 缓存已绕过")).toBeVisible();
  await page.locator(".qa-exclusions summary").click();
  await expect(page.getByText("模型不匹配：1")).toBeVisible();
  await expect(page.locator('[data-stage="cache_lookup"]')).toHaveAttribute("data-state", "skipped");
  await page.locator('[data-stage="rrf_fusion"]').click();
  await expect(page.locator("#query-detail")).toContainText('"source": "both"');
  await page.getByRole("tab", { name: "源码职责" }).click();
  await expect(page.locator("#query-detail")).toContainText("RrfFusionService.java");
  await page.getByRole("button", { name: "[C1]", exact: true }).click();
  await expect(page.getByTestId("citation-evidence").locator("mark")).toHaveText("Approval is required");
  await page.locator(".qa-graph").evaluate(el => { el.scrollTop = 215; });
  await noOverflow(page);
  await page.screenshot({ path: "test-results/qa-desktop.png", fullPage: true });
  await page.setViewportSize({ width: 1920, height: 1080 }); await noOverflow(page);
});

test("coverage-first shows all five children before parent expansion on desktop and mobile", async ({ page }) => {
  const api = await backend(page, "coverage");
  await page.goto("/"); await ask(page, "苹果这几年发布会有哪些差异？");
  await expect(page.locator(".qa-chat .qa-coverage-summary")).toContainText("证据纳入 5 / 5 个父块");
  expect(api.calls[0].contextBudgetChars).toBe(4000);
  await expect(page.locator(".qa-chat .qa-coverage")).toContainText("答案引用 1 项");
  await page.locator('[data-stage="child_selection"]').click();
  const detail = page.locator(".qa-inspector .qa-coverage");
  await expect(detail.locator(".qa-allocation")).toHaveCount(5);
  await detail.getByText("apple-2025.md", { exact: true }).click();
  await expect(detail).toContainText("Synthetic 2025 evidence");
  await expect(detail).toContainText("child 400 → parent 800 / 2000");
  await noOverflow(page);
  await page.screenshot({ path: "test-results/coverage-desktop.png", fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await noOverflow(page);
  await page.screenshot({ path: "test-results/coverage-mobile.png", fullPage: true });
  expect(api.calls).toHaveLength(1);
});

test("budget exclusions identify the file and distinguish missing evidence from missing library documents", async ({ page }) => {
  await backend(page, "excluded"); await page.goto("/"); await ask(page);
  await expect(page.locator(".qa-chat .qa-coverage-summary")).toContainText("预算未纳入 1 个");
  await page.locator(".qa-chat .qa-coverage summary").click();
  await expect(page.locator(".qa-chat .qa-coverage")).toContainText("apple-2025.md");
  await expect(page.locator(".qa-chat .qa-coverage")).toContainText("剩余预算不足以保留完整 child");
  await page.setViewportSize({ width: 390, height: 844 }); await noOverflow(page);
  await page.screenshot({ path: "test-results/coverage-excluded-mobile.png", fullPage: true });
});
test("cache miss then hit preserves live answer stage and labels reused candidates on desktop and mobile", async ({ page }) => {
  const api = await backend(page, "cache");
  await page.goto("/"); await ask(page);
  await expect(page.getByText(/上下文缓存未命中/)).toBeVisible();
  await ask(page);
  await expect(page.getByText(/上下文缓存命中/)).toBeVisible();
  expect(api.calls).toHaveLength(2);
  await expect(page.locator('[data-stage="vector_search"]')).toHaveText(/缓存复用.*未执行/);
  await expect(page.locator('[data-stage="answer_generation"]')).toHaveAttribute("data-state", "succeeded");
  await page.locator('[data-stage="rrf_fusion"]').click();
  await expect(page.getByText(/来自上下文缓存/)).toBeVisible();
  await noOverflow(page);
  await page.screenshot({ path: "test-results/cache-hit-desktop.png", fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await noOverflow(page);
  await page.screenshot({ path: "test-results/cache-hit-mobile.png", fullPage: true });
});
test("semantic hit labels the source query and actual embedding on desktop and mobile", async ({ page }) => {
  const api = await backend(page, "semantic");
  await page.goto("/"); await ask(page);
  await expect(page.getByText(/上下文缓存未命中/)).toBeVisible();
  await ask(page, "Which requirement applies to the synthetic policy?");
  await expect(page.getByText("上下文缓存命中（语义）", { exact: false })).toBeVisible();
  expect(api.calls).toHaveLength(2);
  expect(api.calls[0].question).not.toBe(api.calls[1].question);
  await expect(page.locator('[data-stage="query_embedding"]')).toHaveAttribute("data-state", "succeeded");
  await expect(page.locator('[data-stage="vector_search"]')).toHaveText(/缓存复用.*未执行/);
  await page.locator('[data-stage="semantic_cache_lookup"]').click();
  await expect(page.locator("#query-detail")).toContainText('"similarity": 0.98');
  await noOverflow(page);
  await page.screenshot({ path: "test-results/semantic-hit-desktop.png", fullPage: true });
  await page.locator('[data-stage="reranking"]').click();
  await expect(page.getByText(/历史检索与重排分数不属于本次问题/)).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole("button", { name: "执行流程", exact: true }).click();
  await noOverflow(page);
  await page.locator('[data-stage="semantic_cache_lookup"]').scrollIntoViewIfNeeded();
  await page.screenshot({ path: "test-results/semantic-hit-mobile.png", fullPage: true });
});
test("selected-document mode only sends ready documents; replacement requires explicit confirmation", async ({ page }) => {
  const api = await backend(page);
  await page.goto("/");
  await page.getByRole("button", { name: "指定文档", exact: true }).click();
  await page.getByRole("button", { name: "选择文档", exact: true }).click();
  await expect(page.getByRole("checkbox", { name: "选择 not-ready.md" })).toBeDisabled();
  await page.getByRole("checkbox", { name: "选择 synthetic-policy.md" }).check();
  page.once("dialog", d => d.dismiss());
  await page.getByRole("button", { name: "重建向量", exact: true }).first().click();
  expect(api.rebuilds).toBe(0);
  page.once("dialog", d => d.accept());
  await page.getByRole("button", { name: "重建向量", exact: true }).first().click();
  await expect.poll(() => api.rebuilds).toBe(1);
  await page.getByRole("button", { name: "完成选择" }).click();
  await ask(page); await expect(page.locator(".qa-answer-text")).toBeVisible();
  expect(api.calls[0]).toMatchObject({ scope: "documents", documentIds: [documentId] });
});
test("unconfigured backend never falls back to template answers", async ({ page }) => {
  const api = await backend(page, "offline");
  await page.goto("/");
  await page.getByRole("textbox", { name: "知识库问题" }).fill("Question");
  await page.getByRole("checkbox", { name: /允许外发/ }).check();
  await expect(page.getByRole("button", { name: "发送问题", exact: true })).toBeDisabled();
  await expect(page.getByText(/不会自动退回模板/)).toBeVisible();
  expect(api.calls).toHaveLength(0);
});
test("disconnection leaves no answer and does not automatically repeat the POST", async ({ page }) => {
  const api = await backend(page, "interrupted");
  await page.goto("/"); await ask(page);
  await expect(page.getByRole("alert")).toContainText("QUERY_STREAM_INTERRUPTED");
  await expect(page.locator(".qa-answer-text")).toHaveCount(0);
  expect(api.calls).toHaveLength(1);
});
test("access denial clears earlier evidence", async ({ page }) => {
  const api = await backend(page, "denied");
  await page.goto("/"); await ask(page);
  await expect(page.getByRole("alert")).toContainText("已清空问答记录");
  await expect(page.locator(".qa-turn")).toHaveCount(0);
  expect(api.calls).toHaveLength(1);
});
test("identity change fences off a late old-identity result", async ({ page }) => {
  const api = await backend(page, "held");
  await page.goto("/"); await ask(page);
  await expect.poll(() => api.calls.length).toBe(1);
  await page.getByRole("button", { name: /default \/ anonymous/ }).click();
  await page.getByRole("textbox", { name: "Tenant", exact: true }).fill("tenant-b");
  page.once("dialog", d => d.accept());
  await page.getByRole("button", { name: "切换身份", exact: true }).click();
  api.release();
  await expect(page.getByRole("button", { name: /tenant-b \/ anonymous/ })).toBeVisible();
  await expect(page.locator(".qa-turn")).toHaveCount(0);
  await page.getByRole("button", { name: "文档与准备" }).click();
  await expect(page.getByText("当前身份暂无可见文档")).toBeVisible();
});
test("unready document handoff uses the existing Pi workbench without auto-submission", async ({ page }) => {
  await backend(page); await page.goto("/");
  await page.getByRole("button", { name: "文档与准备" }).click();
  await page.getByRole("button", { name: "处理文档", exact: true }).click();
  await expect(page.getByRole("button", { name: "提交任务", exact: true })).toBeVisible();
  await expect(page.locator("textarea")).toHaveValue(/Approve processing:/);
  await page.getByRole("button", { name: "1 份文档", exact: true }).click();
  await expect(page.locator(".document-row").filter({ hasText: "not-ready.md" }).getByRole("checkbox")).toBeChecked();
  await page.getByRole("button", { name: "完成选择" }).click();
  await page.getByRole("button", { name: /default \/ anonymous/ }).click();
  await page.getByRole("textbox", { name: "Tenant", exact: true }).fill("tenant-b");
  await page.getByRole("button", { name: "切换身份", exact: true }).click();
  await expect(page.getByRole("button", { name: "0 份文档", exact: true })).toBeVisible();
});
test("mobile chat, diagram and citation views stay within their viewport", async ({ page }) => {
  await backend(page); await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/"); await ask(page);
  await expect(page.locator(".qa-answer-text")).toBeVisible();
  await noOverflow(page);
  await page.screenshot({ path: "test-results/qa-mobile-chat.png", fullPage: true });
  await page.getByRole("button", { name: "[C1]", exact: true }).click();
  await expect(page.getByTestId("citation-evidence").locator("mark")).toBeVisible();
  await noOverflow(page);
  await page.screenshot({ path: "test-results/qa-mobile-citation.png", fullPage: true });
  await page.getByRole("button", { name: "执行流程", exact: true }).click();
  await expect(page.locator('[data-stage="access_check"]')).toBeVisible();
  await page.screenshot({ path: "test-results/qa-mobile-flow.png", fullPage: true });
  await page.setViewportSize({ width: 320, height: 740 }); await noOverflow(page);
});

test("page follow-ups carry three bounded anchors, show resolution and ask for clarification without retrieval", async ({ page }) => {
  const api = await backend(page, "followUp");
  await page.goto("/"); await ask(page, "苹果发布会有哪些亮点？");
  await expect(page.locator(".qa-turn")).toHaveCount(1);
  await expect(page.locator(".qa-answer-text")).toHaveCount(1);
  expect(api.calls[0].history).toEqual([]);
  await ask(page, "主要有哪些差异？"); await expect(page.locator(".qa-answer-text")).toHaveCount(2);
  expect(api.calls[1].history?.[0].question).toBe("苹果发布会有哪些亮点？");
  await page.locator('[data-stage="query_resolution"]').click();
  await expect(page.locator("#query-detail")).toContainText("苹果近几年秋季发布会有哪些差异？");
  await noOverflow(page); await page.screenshot({ path: "test-results/follow-up-desktop.png", fullPage: true });
  await ask(page, "再详细一点"); await expect(page.locator(".qa-answer-text")).toHaveCount(3);
  expect(api.calls[2].history?.[1].question).toBe("苹果近几年秋季发布会有哪些差异？");
  await ask(page, "继续比较"); await expect(page.locator(".qa-answer-text")).toHaveCount(4);
  await ask(page, "那个呢？"); await expect(page.getByText("请补充问题", { exact: true })).toBeVisible();
  expect(api.calls[4].history).toHaveLength(3);
  await expect(page.locator('[data-stage="answer_generation"]')).toHaveAttribute("data-state", "skipped");
  await expect(page.locator(".qa-turn").last().locator(".citation-marker")).toHaveCount(0);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole("button", { name: "执行流程", exact: true }).click();
  await page.locator('[data-stage="query_resolution"]').click();
  await expect(page.locator("#query-detail")).toContainText("needs_clarification");
  await noOverflow(page); await page.screenshot({ path: "test-results/follow-up-mobile.png", fullPage: true });
});

test("parameter changes keep history; scope changes, clear and refresh start a fresh page session", async ({ page }) => {
  const api = await backend(page); await page.goto("/"); await ask(page);
  await expect(page.locator(".qa-answer-text")).toHaveCount(1);
  await page.locator(".qa-settings summary").click(); await page.getByRole("spinbutton", { name: "Top K", exact: true }).fill("6");
  await ask(page); await expect(page.locator(".qa-answer-text")).toHaveCount(2);
  expect(api.calls[1].history).toHaveLength(1); expect(api.calls[1].sessionId).toBe(api.calls[0].sessionId);
  await page.getByRole("button", { name: "指定文档", exact: true }).click();
  await expect(page.locator(".qa-turn")).toHaveCount(0);
  await page.getByRole("button", { name: "整个知识库", exact: true }).click(); await ask(page);
  await expect(page.locator(".qa-answer-text")).toHaveCount(1);
  expect(api.calls[2].sessionId).not.toBe(api.calls[1].sessionId); expect(api.calls[2].history).toEqual([]);
  await page.getByRole("button", { name: "清空本页问答" }).click(); await ask(page);
  await expect(page.locator(".qa-answer-text")).toHaveCount(1); expect(api.calls[3].sessionId).not.toBe(api.calls[2].sessionId);
  await page.reload(); await expect(page.locator(".qa-turn")).toHaveCount(0); await ask(page);
  await expect(page.locator(".qa-answer-text")).toHaveCount(1); expect(api.calls[4].history).toEqual([]);
  expect(api.calls[4].sessionId).not.toBe(api.calls[3].sessionId);
});
