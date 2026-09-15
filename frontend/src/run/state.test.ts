import { describe, expect, it, beforeEach } from "vitest";
import { mergeEvents, recentRuns, SubmissionKey } from "./state";
import { flowNodes } from "./flow";
import { event, run, runId, tool } from "../test/fixtures";
describe("run state", () => {
  beforeEach(() => sessionStorage.clear());
  it("deduplicates and orders events without mixing runs", () => {
    expect(
      mergeEvents(
        [event(2)],
        [event(1), event(2), { ...event(3), runId: "other" }],
        runId,
      ).map((e) => e.sequence),
    ).toEqual([1, 2]);
  });
  it("keeps repeated tool calls distinct and groups retries by invocation", () => {
    const second = {
      ...tool,
      observationId: "second",
      invocationId: "new",
      retryOf: tool.invocationId,
      attempt: 2,
    };
    const nodes = flowNodes(
      run,
      [tool, second],
      [
        event(2, "tool_started", { observationId: tool.observationId }),
        event(4, "tool_started", { observationId: "second" }),
      ],
    );
    expect(nodes.filter((n) => n.kind === "tool")).toHaveLength(2);
    expect(nodes.find((n) => n.id === "second")?.tool?.retryOf).toBe(
      tool.invocationId,
    );
    expect(nodes.find((n) => n.kind === "approval")?.status).toBe("PENDING");
    expect(nodes.some((n) => n.kind === "action")).toBe(false);
  });
  it("scopes session run IDs by tenant and actor including delimiter collisions", () => {
    recentRuns({ tenantId: "a:b", actorId: "c" }, runId);
    expect(recentRuns({ tenantId: "a", actorId: "b:c" })).toEqual([]);
    expect(recentRuns({ tenantId: "a:b", actorId: "other" })).toEqual([]);
    expect(recentRuns({ tenantId: "a:b", actorId: "c" })).toEqual([runId]);
    expect(Object.values(sessionStorage).join("")).not.toContain(run.question);
  });
  it("retains at most 20 IDs, not arbitrary stored objects", () => {
    const identity = { tenantId: "a", actorId: "b" };
    for (let i = 0; i < 25; i++) recentRuns(identity, crypto.randomUUID());
    expect(recentRuns(identity)).toHaveLength(20);
    sessionStorage.setItem("nexus.learning.runs:a:b", '{"raw":"secret"}');
    expect(recentRuns(identity)).toEqual([]);
  });
  it("uses the same idempotency key only for the same canonical request", () => {
    const keys = new SubmissionKey(),
      first = keys.get("question", ["b", "a"]);
    expect(keys.get("question", ["a", "b"])).toBe(first);
    expect(keys.get("changed", ["a", "b"])).not.toBe(first);
    const last = keys.get("changed", ["a", "b"]);
    keys.clear();
    expect(keys.get("changed", ["a", "b"])).not.toBe(last);
  });
});
