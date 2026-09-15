import { useEffect, useState } from "react";
import type { BackendClient } from "../api/client";
import { errorText } from "../api/client";
import type { Run, RunEvent, ToolSummary } from "../api/types";
import { monitorRun } from "./monitor";
import { mergeEvents } from "./state";

export function useRun(api: BackendClient, runId: string, revision: number) {
  const [run, setRun] = useState<Run>();
  const [tools, setTools] = useState<ToolSummary[]>([]);
  const [events, setEvents] = useState<RunEvent[]>([]);
  const [connection, setConnection] = useState("未连接");
  const [error, setError] = useState("");
  const [denied, setDenied] = useState(false);
  useEffect(() => {
    const abort = new AbortController();
    setRun(undefined);
    setTools([]);
    setEvents([]);
    setError("");
    setDenied(false);
    setConnection("未连接");
    if (runId)
      void monitorRun(api, runId, abort.signal, {
        snapshot: (value, rows) => {
          setRun(value);
          setTools(rows);
          setError("");
        },
        events: (added) => setEvents((old) => mergeEvents(old, added, runId)),
        connection: setConnection,
        error: (e, denied) => {
          setError(errorText(e));
          setDenied(denied);
          if (denied) {
            setRun(undefined);
            setTools([]);
            setEvents([]);
          }
        },
      });
    return () => abort.abort();
  }, [api, runId, revision]);
  return { run, tools, events, connection, error, denied };
}
