import { test, expect } from "@playwright/test";

test("real upload, persisted observations and two separate processing approvals", async ({
  page,
}) => {
  const content =
    "# Synthetic workbench policy\n\n" +
    "This fictional policy is only a browser integration fixture. Device setup requires a recorded approval. ".repeat(
      20,
    );
  await page.goto("/?view=documents");
  await page.getByRole("button", { name: "选择文档", exact: true }).click();
  await page.getByLabel("上传文档", { exact: true }).setInputFiles({
    name: "workbench-integration-fixture.md",
    mimeType: "text/markdown",
    buffer: Buffer.from(content),
  });
  await expect(page.getByRole("checkbox")).toHaveCount(1);
  await page.getByRole("checkbox").check();
  await page.getByRole("button", { name: "完成选择" }).click();
  await page.getByRole("button", { name: "填入处理申请" }).click();
  await page.getByRole("button", { name: "提交任务" }).click();

  const approval = page.locator(".approval");
  await expect(approval).toContainText("生成父子分块", { timeout: 30_000 });
  await expect(page.locator(".runbar")).toContainText("等待审批");
  await page
    .getByRole("button", { name: /检查文档处理状态/ })
    .first()
    .click();
  await page.getByRole("tab", { name: "输出", exact: true }).click();
  await expect(page.locator("#detail-content")).toContainText(
    "CHUNKING_REQUIRED",
  );
  await page.screenshot({
    path: "test-results/live-chunk-approval.png",
    fullPage: true,
  });
  await page.getByRole("button", { name: "批准此操作" }).click();

  await expect(approval).toContainText("补齐缺失的子块 embedding", {
    timeout: 30_000,
  });
  await expect(page.locator(".runbar")).toContainText("等待审批");
  await page.screenshot({
    path: "test-results/live-embedding-approval.png",
    fullPage: true,
  });
  await page.getByRole("button", { name: "批准此操作" }).click();

  await expect(page.locator(".runbar")).toContainText("已完成", {
    timeout: 30_000,
  });
  await expect(page.locator(".report")).toContainText("HEALTHY");
  await expect(page.locator(".report")).toContainText("no model was called");
  await expect(page.locator(".page-error")).toHaveCount(0);
  await expect(
    page.locator(".node").filter({ hasText: "ApprovedRetryService.execute()" }),
  ).toHaveCount(2);
  await page.screenshot({
    path: "test-results/live-completed.png",
    fullPage: true,
  });
  await page.reload();
  await expect(page.locator(".report")).toContainText("HEALTHY");
  await expect(page.locator(".runbar")).toContainText("已完成");
});
