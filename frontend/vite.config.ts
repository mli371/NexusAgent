import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

const root = fileURLToPath(new URL(".", import.meta.url));
const target = new URL(
  process.env.NEXUS_FRONTEND_API_TARGET ?? "http://127.0.0.1:8080",
);
if (
  target.protocol !== "http:" ||
  !["localhost", "127.0.0.1", "[::1]"].includes(target.hostname) ||
  target.username ||
  target.password ||
  target.pathname !== "/" ||
  target.search ||
  target.hash
) {
  throw new Error("NEXUS_FRONTEND_API_TARGET must be a loopback HTTP origin");
}

export default defineConfig({
  root,
  envDir: root,
  envPrefix: "NEXUS_PUBLIC_",
  plugins: [react()],
  server: {
    host: "127.0.0.1",
    port: 5173,
    strictPort: true,
    fs: { strict: true, allow: [root] },
    proxy: {
      "^/api/v1(?:/|$)": { target: target.origin, changeOrigin: false },
    },
  },
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test/setup.ts"],
    include: ["src/**/*.test.{ts,tsx}"],
  },
});
