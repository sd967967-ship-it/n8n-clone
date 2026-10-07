import { test, expect } from "@playwright/test";

test.describe("canvas smoke @smoke", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/");
  });

  test("renders palette, canvas and inspector", async ({ page }) => {
    await expect(
      page.getByRole("heading", { name: "Automation Studio" })
    ).toBeVisible();
    await expect(page.getByTestId("canvas")).toBeVisible();
    await expect(
      page.getByRole("button", { name: "Run Workflow" })
    ).toBeVisible();
    for (const t of [
      "manual_trigger",
      "http_request",
      "llm",
      "condition",
      "transform",
      "output",
      "app_action",
      "app_trigger",
    ]) {
      await expect(page.getByTestId(`palette-${t}`)).toBeVisible();
    }
  });

  test("click-to-add drops a node on the canvas", async ({ page }) => {
    await expect(page.getByTestId("node-count")).toHaveText("0 node(s) on canvas");
    await page.getByTestId("palette-http_request").click();
    await expect(page.getByTestId("node-count")).toHaveText("1 node(s) on canvas");
  });
});
