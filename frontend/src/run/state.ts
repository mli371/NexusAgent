import type { Identity, RunEvent } from "../api/types";
import { isUuid } from "../api/types";

export function mergeEvents(
  old: RunEvent[],
  added: RunEvent[],
  runId: string,
): RunEvent[] {
  const bySequence = new Map(
    old.filter((e) => e.runId === runId).map((e) => [e.sequence, e]),
  );
  for (const event of added) {
    if (event.runId === runId && !bySequence.has(event.sequence))
      bySequence.set(event.sequence, event);
  }
  return [...bySequence.values()].sort((a, b) => a.sequence - b.sequence);
}
export function recentRuns(identity: Identity, add?: string): string[] {
  try {
    const key = `nexus.learning.runs:${encodeURIComponent(identity.tenantId)}:${encodeURIComponent(identity.actorId)}`;
    const raw: unknown = JSON.parse(sessionStorage.getItem(key) ?? "[]");
    const ids = Array.isArray(raw)
      ? (raw.filter((v) => typeof v === "string" && isUuid(v)) as string[])
      : [];
    const next = [...new Set(add ? [add, ...ids] : ids)].slice(0, 20);
    if (add) sessionStorage.setItem(key, JSON.stringify(next));
    return next;
  } catch {
    return add ? [add] : [];
  }
}
export class SubmissionKey {
  private last?: { signature: string; key: string };
  get(question: string, documentIds: string[]) {
    const signature = JSON.stringify({
      question,
      documentIds: [...new Set(documentIds)].sort(),
    });
    if (this.last?.signature !== signature)
      this.last = { signature, key: crypto.randomUUID() };
    return this.last.key;
  }
  clear() {
    this.last = undefined;
  }
}
