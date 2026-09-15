import { existsSync, readFileSync } from "node:fs";
import { parseEnv } from "node:util";
import { fileURLToPath } from "node:url";
import { WorkerError } from "./protocol.js";

export interface Config {
  apiUrl: string;
  workerToken: string;
  mode: "scripted" | "pi";
  provider: string;
  model: string;
  apiKey: string;
}

export const projectRoot = fileURLToPath(new URL("../../../../", import.meta.url));

export function loadConfig(env: NodeJS.ProcessEnv = process.env, envFile = `${projectRoot}.env`): Config {
  const file = existsSync(envFile) ? parseEnv(readFileSync(envFile, "utf8")) : {};
  const values = { ...file, ...env };
  const mode = values.NEXUS_AGENT_WORKER_MODE ?? "scripted";
  const workerToken = values.NEXUS_AGENT_WORKER_TOKEN ?? "";
  const provider = values.NEXUS_LLM_PROVIDER ?? "";
  const model = values.NEXUS_LLM_MODEL ?? "";
  const apiKey = values.NEXUS_LLM_API_KEY ?? "";
  const apiUrl = values.NEXUS_AGENT_API_URL ?? "http://localhost:8080";
  const url = new URL(apiUrl);
  if (!["http:", "https:"].includes(url.protocol) || url.username || url.password || url.search || url.hash
      || url.pathname !== "/" || workerToken.length < 32 || workerToken.length > 512
      || !["scripted", "pi"].includes(mode) || (mode === "pi" && (!provider || !model || !apiKey))) {
    throw new WorkerError("CONFIGURATION_ERROR");
  }
  return { apiUrl: url.origin, workerToken, mode: mode as Config["mode"], provider, model, apiKey };
}
