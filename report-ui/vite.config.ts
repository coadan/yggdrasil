import mdx from "@mdx-js/rollup";
import react from "@vitejs/plugin-react";
import { fileURLToPath, URL } from "node:url";
import { defineConfig } from "vitest/config";

export default defineConfig({
  base: "./",
  plugins: [mdx(), react()],
  server: {
    fs: {
      allow: [fileURLToPath(new URL("..", import.meta.url))]
    }
  },
  build: {
    outDir: "../resources/agraph/report-ui",
    emptyOutDir: true
  },
  test: {
    environment: "jsdom",
    setupFiles: ["src/setupTests.ts"]
  }
});
