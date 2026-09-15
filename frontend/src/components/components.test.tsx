import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { ApprovalPanel } from "./ApprovalPanel";
import { Inspector } from "./Inspector";
import { approval, run, tool } from "../test/fixtures";
import { BackendClient } from "../api/client";
it("approval does not invent successful execution and calls only the selected decision", () => {
  const decide = vi.fn();
  render(
    <ApprovalPanel approval={approval} busy={false} onDecision={decide} />,
  );
  fireEvent.click(screen.getByRole("button", { name: "批准此操作" }));
  expect(decide).toHaveBeenCalledExactlyOnceWith("APPROVE");
  expect(screen.getByText("待人工确认")).toBeInTheDocument();
  expect(screen.queryByText("已完成")).not.toBeInTheDocument();
});
it("expired and busy approvals cannot be submitted", () => {
  const { rerender } = render(
    <ApprovalPanel
      approval={{ ...approval, expiresAt: "2000-01-01T00:00:00Z" }}
      busy={false}
      onDecision={vi.fn()}
    />,
  );
  expect(screen.getByRole("button", { name: "批准此操作" })).toBeDisabled();
  rerender(<ApprovalPanel approval={approval} busy onDecision={vi.fn()} />);
  expect(screen.getByRole("button", { name: "拒绝" })).toBeDisabled();
});
it("renders untrusted reasons as text, not executable markup", () => {
  const { container } = render(
    <ApprovalPanel
      approval={{ ...approval, reason: "<img src=x onerror=alert(1)>" }}
      busy={false}
      onDecision={vi.fn()}
    />,
  );
  expect(container.querySelector("img")).toBeNull();
  expect(screen.getByText("<img src=x onerror=alert(1)>")).toBeInTheDocument();
});
it("shows actual tool details and labels source explanations as static", async () => {
  const api = new BackendClient({ tenantId: "default", actorId: "anonymous" });
  vi.spyOn(api, "detail").mockResolvedValue(tool);
  render(
    <Inspector
      api={api}
      run={run}
      active
      revoke={vi.fn()}
      node={{
        id: tool.observationId,
        kind: "tool",
        title: "检查文档",
        subtitle: tool.toolName,
        status: tool.status,
        sequence: 2,
        tool,
      }}
    />,
  );
  fireEvent.click(screen.getByRole("tab", { name: "输出" }));
  await waitFor(() =>
    expect(screen.getByText(/CHUNKING_REQUIRED/)).toBeInTheDocument(),
  );
  fireEvent.click(screen.getByRole("tab", { name: "源码职责" }));
  expect(screen.getByText(/静态映射/)).toBeInTheDocument();
  expect(
    screen.getByText(/DocumentDiagnosticsService.java/),
  ).toBeInTheDocument();
});
