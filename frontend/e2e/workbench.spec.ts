import { test, expect, type Page } from "@playwright/test";
import {
  approval,
  approvalId,
  document,
  documentId,
  event,
  run,
  runId,
  tool,
} from "../src/test/fixtures";
import type { Run } from "../src/api/types";

async function backend(page: Page) {
  let state: Run = structuredClone(run),
    decisions: string[] = [],
    created = 0;
  const events = [
    event(1),
    event(2, "started"),
    event(3, "tool_started", { observationId: tool.observationId }),
    event(4, "tool_completed", { observationId: tool.observationId }),
    event(5, "approval_requested", { approvalId }),
  ];
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request(),
      url = new URL(request.url()),
      path = url.pathname;
    const headers = request.headers();
    if (
      headers["x-tenant-id"] !== "default" ||
      headers["x-actor-id"] !== "anonymous"
    ) {
      return route.fulfill({
        status: path === "/api/v1/documents" ? 200 : 404,
        json:
          path === "/api/v1/documents"
            ? []
            : { code: "NOT_FOUND", message: "hidden" },
      });
    }
    if (path === "/api/v1/documents")
      return route.fulfill({
        status: request.method() === "POST" ? 201 : 200,
        json: request.method() === "POST" ? document : [document],
      });
    if (path === `/api/v1/documents/${documentId}`)
      return route.fulfill({ json: document });
    if (path === "/api/v1/agent/runs" && request.method() === "POST") {
      created++;
      state.question = request.postDataJSON().question;
      return route.fulfill({ status: 202, json: state });
    }
    if (path.endsWith("/events/stream")) {
      const after = Number(url.searchParams.get("afterSequence") ?? 0);
      return route.fulfill({
        contentType: "text/event-stream",
        body: events
          .filter((e) => e.sequence > after)
          .map(
            (e) =>
              `id: ${e.sequence}\nevent: ${e.eventType}\ndata: ${JSON.stringify(e)}\n\n`,
          )
          .join(""),
      });
    }
    if (path.endsWith("/events")) {
      const after = Number(url.searchParams.get("afterSequence") ?? 0),
        rows = events.filter((e) => e.sequence > after);
      return route.fulfill({
        json: {
          runId,
          traceId: run.traceId,
          events: rows,
          nextSequence: rows.at(-1)?.sequence ?? after,
          hasMore: false,
        },
      });
    }
    if (path.endsWith("/tools"))
      return route.fulfill({
        json: { tools: [tool], hasMore: false, nextOffset: 1 },
      });
    if (path.endsWith(`/tools/${tool.observationId}`))
      return route.fulfill({ json: tool });
    if (path.endsWith(`/approvals/${approvalId}`)) {
      decisions.push(request.postDataJSON().decision);
      if (request.postDataJSON().decision === "REJECT")
        return route.fulfill({
          status: 409,
          json: {
            code: "STATE_CONFLICT",
            message: "Approval is no longer pending",
          },
        });
      state = {
        ...state,
        status: "SUCCEEDED",
        pendingApproval: undefined,
        approvals: [
          { ...approval, status: "APPROVED", decidedBy: "anonymous" },
        ],
        actionResults: [
          {
            executionId: "execution-fixture",
            approvalId,
            documentId,
            action: "CHUNK",
            status: "SUCCEEDED",
            errorCode: null,
            ingestionJobId: "job-fixture",
          },
        ],
        report: { summary: "合成浏览器测试报告", findings: [], unresolved: [] },
      };
      events.push(
        event(6, "action_started", { approvalId }),
        event(7, "action_completed", { approvalId }),
        event(8, "completed"),
      );
      return route.fulfill({ json: state.approvals![0] });
    }
    if (path.endsWith("/cancel")) {
      state = {
        ...state,
        cancellationRequested: true,
        cancellationPending: true,
      };
      return route.fulfill({ json: state });
    }
    if (path === `/api/v1/agent/runs/${runId}`)
      return route.fulfill({ json: state });
    return route.fulfill({ status: 404, json: { code: "NOT_FOUND" } });
  });
  return {
    decisions,
    get created() {
      return created;
    },
  };
}
async function open(page: Page) {
  await page.goto("/?view=documents");
  await page.getByRole("textbox", { name: "打开 runId" }).fill(runId);
  await page.getByRole("button", { name: "打开任务", exact: true }).click();
  await expect(page.getByRole("button", { name: "批准此操作" })).toBeVisible();
}
test("upload, choose document, create task, inspect and approve using scoped requests", async ({
  page,
}) => {
  const stub = await backend(page);
  await page.goto("/?view=documents");
  await page.getByRole("button", { name: "选择文档", exact: true }).click();
  await page
    .getByLabel("上传文档", { exact: true })
    .setInputFiles({
      name: "synthetic-policy.md",
      mimeType: "text/markdown",
      buffer: Buffer.from("# Synthetic\nNo private data."),
    });
  await expect(page.getByRole("checkbox")).toHaveCount(1);
  await page.getByRole("checkbox").check();
  await page.getByRole("button", { name: "完成选择" }).click();
  await page.getByRole("button", { name: "填入处理申请" }).click();
  await page.getByRole("button", { name: "提交任务" }).click();
  await expect(page.getByRole("button", { name: "批准此操作" })).toBeVisible();
  expect(stub.created).toBe(1);
  await page.getByRole("button", { name: /检查文档处理状态/ }).click();
  await page.getByRole("tab", { name: "输出", exact: true }).click();
  await expect(page.locator("#detail-content")).toContainText(
    "CHUNKING_REQUIRED",
  );
  await page.screenshot({
    path: "test-results/desktop-workbench.png",
    fullPage: true,
  });
  await page.getByRole("button", { name: "批准此操作" }).click();
  await expect(
    page.getByText("合成浏览器测试报告", { exact: true }),
  ).toBeVisible();
  expect(stub.decisions).toEqual(["APPROVE"]);
  await expect(page.locator(".node")).toHaveCount(6);
  await page.reload();
  await expect(
    page.getByText("合成浏览器测试报告", { exact: true }),
  ).toBeVisible();
});
test("conflicting approval is reported, not silently retried", async ({
  page,
}) => {
  const stub = await backend(page);
  await open(page);
  await page.getByRole("button", { name: "拒绝", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("STATE_CONFLICT");
  expect(stub.decisions).toEqual(["REJECT"]);
  await expect(page.getByRole("button", { name: "批准此操作" })).toBeVisible();
});
test("identity switch removes task content and sends new headers", async ({
  page,
}) => {
  await backend(page);
  await open(page);
  await page.getByRole("button", { name: /default \/ anonymous/ }).click();
  await page
    .getByRole("textbox", { name: "Tenant", exact: true })
    .fill("tenant-b");
  await page.getByRole("button", { name: "切换身份", exact: true }).click();
  await expect(page.getByText(run.question!, { exact: true })).toHaveCount(0);
  await expect(page.locator(".node")).toHaveCount(0);
  await page.getByRole("button", { name: "选择文档", exact: true }).click();
  await expect(page.getByText("当前身份暂无可见文档")).toBeVisible();
});
test("cancellation remains pending and mobile views do not overflow", async ({
  page,
}) => {
  await backend(page);
  await open(page);
  page.on("dialog", (dialog) => dialog.accept());
  await page.getByRole("button", { name: "请求取消", exact: true }).click();
  await expect(page.getByText(/取消已请求，等待后端确认/)).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  await page
    .getByRole("navigation", { name: "工作区" })
    .getByRole("button", { name: "执行流程" })
    .click();
  await expect(
    page.getByRole("button", { name: /检查文档处理状态/ }),
  ).toBeVisible();
  await page.screenshot({
    path: "test-results/mobile-flow.png",
    fullPage: true,
  });
  for (const name of ["对话", "执行流程", "调用详情"]) {
    await page
      .getByRole("navigation", { name: "工作区" })
      .getByRole("button", { name })
      .click();
    expect(
      await page.evaluate(
        () => window.document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
  }
});
