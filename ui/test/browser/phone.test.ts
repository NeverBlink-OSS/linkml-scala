// The playground on a phone. 360px is the width of a Galaxy S10, about the narrowest phone in
// common use.
import { expect, test, type Page } from "@playwright/test";

test.use({ viewport: { width: 360, height: 760 } });

test.beforeEach(async ({ page }) => {
  // Fixed repo stats, so the test does not depend on GitHub.
  await page.route("https://api.github.com/**", (route) =>
    route.fulfill({ json: { stargazers_count: 1234, forks_count: 56 } }),
  );
  await page.goto("/");
});

/** Picks a direction from the narrow layout's direction list. */
async function pickDirection(page: Page, label: string): Promise<void> {
  await page.locator("#dirButton").click();
  await page.getByRole("option", { name: label }).click();
}

/** Picks a target from the target picker. */
async function pickTarget(page: Page, label: string): Promise<void> {
  await page.locator("#targetButton").click();
  await page.getByRole("option", { name: label, exact: true }).click();
}

/** Fails unless every visible element that `controls` picks sits in one row, inside `bar`. */
async function expectInOneRow(page: Page, bar: string, controls: string): Promise<void> {
  const toolbar = await page.locator(bar).boundingBox();
  const boxes = [];
  for (const control of await page.locator(controls).all()) {
    const box = await control.boundingBox();
    if (box) boxes.push(box);
  }
  expect(boxes.length).toBeGreaterThan(0);
  for (const box of boxes) {
    // Each control spans the middle of the first one, so none of them has wrapped to a new row.
    const middle = boxes[0]!.y + boxes[0]!.height / 2;
    expect(box.y).toBeLessThan(middle);
    expect(box.y + box.height).toBeGreaterThan(middle);
    expect(box.x).toBeGreaterThanOrEqual(toolbar!.x);
    expect(box.x + box.width).toBeLessThanOrEqual(toolbar!.x + toolbar!.width);
  }
  // Nothing sticks out of the page sideways.
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(360);
}

function expectToolbarInOneRow(page: Page): Promise<void> {
  return expectInOneRow(page, ".toolbar", ".toolbar > :visible, .toolbar-right > :visible");
}

test("the toolbar fits in one row in both directions, with Auto on and off", async ({ page }) => {
  for (const dir of ["From LinkML", "To LinkML"]) {
    await pickDirection(page, dir);
    await expectToolbarInOneRow(page);

    await page.locator("#settingsToggle").click();
    await page.getByLabel("Auto").uncheck();
    await page.locator("#settingsToggle").click();
    await expect(page.locator("#generateBtn")).toBeVisible();
    await expectToolbarInOneRow(page);

    await page.locator("#settingsToggle").click();
    await page.getByLabel("Auto").check();
    await page.locator("#settingsToggle").click();
    await expect(page.locator("#generateBtn")).toBeHidden();
  }
});

test("the direction is a list like the target picker", async ({ page }) => {
  await expect(page.locator("#dirToggle")).toBeHidden();
  const button = page.locator("#dirButton");
  await expect(button).toHaveText("From LinkML");
  await pickDirection(page, "To LinkML");
  await expect(button).toHaveText("To LinkML");
  await expect(page.locator("#loadFrom")).toHaveText("Load ontology…");

  // And from the keyboard.
  await button.focus();
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("ArrowUp");
  await page.keyboard.press("Enter");
  await expect(button).toHaveText("From LinkML");
  await expect(button).toBeFocused();
});

test("a pair of views is a switch in the Output bar", async ({ page }) => {
  const head = page.locator("#outputPanel .panel-head");
  for (const [target, view] of [["Documentation", "Page"], ["ER diagram", "Diagram"], ["Statistics", "Stats"]]) {
    await pickTarget(page, target!);
    const tab = head.getByRole("button", { name: view, exact: true });
    await expect(tab).toBeVisible();
    await head.getByRole("button", { name: "Code", exact: true }).click();
    await expect(page.locator("#outputEditor")).toBeVisible();
    await tab.click();
    await expect(page.locator("#outputEditor")).toBeHidden();
    // With the help button and the timing beside it, the bar still takes one row.
    await expect(page.locator("#statusPill")).toHaveText(/ms$/);
    await expectInOneRow(page, "#outputPanel .panel-head", "#outputPanel .panel-head > :visible, #outputPanel .panel-actions > :visible");
  }
});

test("the help button links to the docs page of what is on screen", async ({ page }) => {
  const help = page.locator("#outputHelp");
  await expect(help).toHaveAttribute("href", "https://linkml.ohi.dev/docs/generators/docs/");
  await pickTarget(page, "ER diagram");
  await expect(help).toHaveAttribute("href", "https://linkml.ohi.dev/docs/generators/er-diagram/");
  await expect(help).toHaveAccessibleName("ER diagram documentation (opens in a new tab)");
  await pickDirection(page, "To LinkML");
  await expect(help).toHaveAttribute("href", /\/docs\/importers\//);
});

test("the help dialog opens with a link to the docs", async ({ page }) => {
  await page.locator("#helpToggle").click();
  const first = page.locator("#faqDialog .faq-body > :first-child");
  await expect(first).toHaveAttribute("href", "https://linkml.ohi.dev/docs/");
  await expect(first).toBeInViewport();
});

test("a generator's files stay in the tab bar", async ({ page }) => {
  await pickTarget(page, "Scala code");
  const tabs = page.locator("#fileTabs");
  await expect(tabs).toBeVisible();
  await expect(page.locator("#outputPanel .panel-head #fileTabs")).toHaveCount(0);
  await expect(tabs).not.toHaveClass(/file-tabs--switch/);
});

test("the generator settings are in a popup under the settings button", async ({ page }) => {
  const toggle = page.locator("#settingsToggle");
  const auto = page.getByLabel("Auto");
  await expect(auto).toBeHidden();

  await toggle.click();
  await expect(toggle).toHaveAttribute("aria-expanded", "true");
  await expect(auto).toBeVisible();
  // A press outside closes it.
  await page.locator("#inputEditor").click();
  await expect(auto).toBeHidden();
  await expect(toggle).toHaveAttribute("aria-expanded", "false");

  // So does Escape, which hands focus back to the button.
  await toggle.click();
  await auto.focus();
  await page.keyboard.press("Escape");
  await expect(auto).toBeHidden();
  await expect(toggle).toBeFocused();
});

test("the fork count and the footer are left out", async ({ page }) => {
  await expect(page.locator("#repoStars")).toHaveText("1.2k");
  await expect(page.locator("#repoForks")).toBeHidden();
  await expect(page.locator(".app-footer")).toBeHidden();
});

test.describe("on a wide screen", () => {
  test.use({ viewport: { width: 1280, height: 800 } });

  test("the toggle, options and Auto stay in the toolbar", async ({ page }) => {
    await expect(page.locator("#dirToggle")).toBeVisible();
    await expect(page.locator("#dirCombo")).toBeHidden();
    await expect(page.locator("#settingsToggle")).toBeHidden();
    await expect(page.getByLabel("Auto")).toBeVisible();
    await expect(page.locator("#repoForks")).toBeVisible();
    await expect(page.locator(".app-footer")).toBeVisible();
  });

  test("a pair of views stays in the tab bar", async ({ page }) => {
    await pickTarget(page, "ER diagram");
    await expect(page.locator("#fileTabs")).toBeVisible();
    await expect(page.locator("#outputPanel .panel-head #fileTabs")).toHaveCount(0);
  });
});
