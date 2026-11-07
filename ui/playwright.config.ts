// Browser tests for the playground: `npm run test:browser`. They run against public/ as built by
// `npm run build`, served by test/serve.mjs.
import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "test/browser",
  reporter: "list",
  use: { baseURL: "http://localhost:4174" },
  webServer: {
    command: "node test/serve.mjs 4174",
    port: 4174,
    reuseExistingServer: !process.env.CI,
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
