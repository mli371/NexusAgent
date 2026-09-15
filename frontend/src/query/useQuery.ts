import { useEffect, useRef, useState } from "react";
import { ApiError, BackendClient, errorText } from "../api/client";
import { streamQuery } from "./client";
import type { QueryRequest, QueryTurn } from "./types";

export function useQuery(api: BackendClient) {
  const [turns, setTurns] = useState<QueryTurn[]>([]);
  const [selected, setSelected] = useState("");
  const [running, setRunning] = useState(false);
  const [error, setError] = useState("");
  const [sessionId, setSessionId] = useState(() => crypto.randomUUID());
  const active = useRef<AbortController | undefined>(undefined);
  const generation = useRef(0);
  useEffect(() => {
    setTurns([]); setSelected(""); setError(""); setRunning(false); setSessionId(crypto.randomUUID()); active.current = undefined;
    return () => { generation.current++; active.current?.abort(); };
  }, [api]);
  const submit = async (request: QueryRequest, model: string) => {
    if (active.current) return;
    const abort = new AbortController(), traceId = crypto.randomUUID(), version = ++generation.current;
    active.current = abort;
    setError(""); setRunning(true); setSelected(traceId);
    // Retain the displayed turn, not another copy of the preceding turns' text.
    const { history, ...displayRequest } = request;
    setTurns(old => [...old.slice(-11), { traceId, request: displayRequest, historyTurnsSent: history?.length ?? 0, status: "running", events: [] }]);
    const update = (f: (turn: QueryTurn) => QueryTurn) => {
      if (!abort.signal.aborted && generation.current === version)
        setTurns(old => old.map(turn => turn.traceId === traceId ? f(turn) : turn));
    };
    try {
      await streamQuery(api, request, traceId, model, abort.signal, event => update(turn => ({
        ...turn, events: [...turn.events, event],
        status: event.type === "completed" ? "completed" : turn.status,
        response: event.response ?? turn.response,
      })));
    } catch (failure) {
      if (abort.signal.aborted || version !== generation.current) return;
      const message = errorText(failure);
      if (failure instanceof ApiError && failure.accessDenied) {
        setTurns([]); setSelected(""); setSessionId(crypto.randomUUID()); setError(`访问范围已改变，已清空问答记录。${message}`);
      } else update(turn => ({ ...turn, status: "failed", error: message, response: undefined }));
    } finally {
      if (version === generation.current) { active.current = undefined; setRunning(false); }
    }
  };
  const stop = () => {
    generation.current++; active.current?.abort(); active.current = undefined; setRunning(false);
    setTurns(old => old.map(turn => turn.status === "running" ? { ...turn, status: "interrupted", response: undefined } : turn));
  };
  const clear = () => { stop(); setTurns([]); setSelected(""); setError(""); setSessionId(crypto.randomUUID()); };
  return { turns, selected, select: setSelected, running, error, sessionId, submit, stop, clear,
    current: turns.find(turn => turn.traceId === selected) ?? turns.at(-1) };
}
