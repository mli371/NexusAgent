import { defineConfig } from "@playwright/test";
const port = Number(process.env.NEXUS_TEST_FRONTEND_PORT ?? "5176");
if (!Number.isInteger(port) || port < 1024 || port > 65535)
  throw new Error("Invalid test frontend port");
const live = process.env.NEXUS_WORKBENCH_LIVE === "1";
export default defineConfig({
  testDir: "./e2e",
  testMatch: live ? "**/live.spec.ts" : ["**/workbench.spec.ts", "**/query.spec.ts"],
  timeout: live ? 90000 : 20000,
  workers: 1,
  fullyParallel: false,
  use: {
    baseURL: `http://127.0.0.1:${port}`,
    channel: process.env.CI ? undefined : "chrome",
    viewport: { width: 1440, height: 1000 },
    screenshot: "only-on-failure",
  },
  webServer: {
    command: `npm run dev -- --port ${port}`,
    url: `http://127.0.0.1:${port}`,
    reuseExistingServer: false,
    timeout: 30000,
  },
});
