import { BackendClient } from "./backend-client.js";
import { loadConfig } from "./config.js";
import { ScriptedSession } from "./scripted-session.js";
import { work } from "./worker.js";
import { WorkerError } from "./protocol.js";

async function main(): Promise<void> {
  const config = loadConfig();
  const controller = new AbortController();
  process.once("SIGINT", () => controller.abort());
  process.once("SIGTERM", () => controller.abort());
  const factory = async () => new ScriptedSession();
  if (config.mode === "pi") {
    const { createPiRuntime, PiSession } = await import("./pi-session.js");
    const runtime = await createPiRuntime(config);
    const piFactory = async () => new PiSession(config, runtime);
    console.info(JSON.stringify({ event: "worker_started", mode: config.mode }));
    await work(config, new BackendClient(config), piFactory, controller.signal, process.argv.includes("--once"));
  } else {
    console.info(JSON.stringify({ event: "worker_started", mode: config.mode }));
    await work(config, new BackendClient(config), factory, controller.signal, process.argv.includes("--once"));
  }
}

main().catch(error => {
  console.error(JSON.stringify({ event: "worker_stopped", code: error instanceof WorkerError ? error.code : "WORKER_ERROR" }));
  process.exitCode = 1;
});
