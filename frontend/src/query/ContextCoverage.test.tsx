import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ContextCoverage } from "./ContextCoverage";
import { queryResponse, semanticResponse, withResolution, queryRequest } from "../test/queryFixtures";

describe("context coverage diagnostics", () => {
  it("shows selected evidence separately from answer citations, then full child before parent", () => {
    const response = queryResponse(); response.citations = [];
    render(<ContextCoverage response={response} detail />);
    expect(screen.getByText(/证据纳入 1 \/ 1/)).toBeInTheDocument();
    expect(screen.getByText(/答案引用 0 项/)).toBeInTheDocument();
    fireEvent.click(screen.getByText("synthetic-policy.md"));
    expect(screen.getByText("Approval is required")).toBeInTheDocument();
    expect(screen.getByText(/child 20 → parent 56 \/ 100/)).toBeInTheDocument();
  });
  it("distinguishes budget exclusions from duplicate parent exclusions without giving them citations", () => {
    const response = queryResponse(), m = response.contextDebug.debugMetadata;
    m.representativeChildCount = 2; m.skippedBudgetCount = 1; m.skippedDuplicateParentCount = 1; m.rerankedCandidateCount = 3;
    m.allocations!.push({ ...m.allocations![0], childChunkId: "other", rerankedRank: 2, originalFilename: "excluded-year.md",
      status: "CHILD_EXCEEDS_REMAINING_BUDGET", allocatedChars: 0, parentTruncated: false });
    m.allocations!.push({ ...m.allocations![0], childChunkId: "duplicate", rerankedRank: 3,
      status: "DUPLICATE_PARENT", allocatedChars: 0, parentTruncated: false });
    render(<ContextCoverage response={response} detail />);
    fireEvent.click(screen.getByText("未纳入候选明细：2 项"));
    expect(screen.getByText(/剩余预算不足以保留完整 child/)).toBeInTheDocument();
    expect(screen.getByText(/同父块去重 · child/)).toBeInTheDocument();
    expect(screen.getByText("excluded-year.md")).toBeInTheDocument();
  });
  it("labels cache allocations and hides coverage for clarification or legacy metadata", () => {
    const { rerender } = render(<ContextCoverage response={semanticResponse()} />);
    expect(screen.getByText(/缓存分配/)).toBeInTheDocument();
    rerender(<ContextCoverage response={withResolution(queryResponse(), queryRequest, "", "Which document?")} />);
    expect(screen.queryByLabelText("上下文覆盖")).not.toBeInTheDocument();
    const legacy = queryResponse(); legacy.contextDebug.debugMetadata = {};
    rerender(<ContextCoverage response={legacy} />);
    expect(screen.queryByLabelText("上下文覆盖")).not.toBeInTheDocument();
  });
});
