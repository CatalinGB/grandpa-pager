const { chromium } = require("playwright");
const path = require("path");
const { pathToFileURL } = require("url");

// Portable: derive the page URL from this file's location, and let the browser
// binary be overridden (CHROME) for machines where Chromium is elsewhere.
const PAGE = pathToFileURL(path.join(__dirname, "..", "..", "admin.html")).href + "?demo=1";
const CHROME = process.env.CHROME || "/usr/bin/chromium";

(async () => {
  const browser = await chromium.launch({ executablePath: CHROME, args: ["--no-sandbox"] });
  const page = await browser.newPage({ viewport: { width: 430, height: 900 } });
  const errors = [];
  page.on("console", (m) => { if (m.type() === "error") errors.push(m.text()); });
  page.on("pageerror", (e) => errors.push("pageerror: " + e.message));

  await page.goto(PAGE);
  await page.waitForSelector("#app");
  await page.locator("#jsonBox").evaluate((e) => { e.open = true; });

  const slider = page.locator('input[data-path="display.name_size_sp"]');
  const label = () => slider.evaluate((el) => el.nextElementSibling.textContent);

  // programmatic input
  await slider.evaluate((el) => { el.value = 99; el.dispatchEvent(new Event("input", { bubbles: true })); });
  console.log("label after programmatic input:", await label());

  // REAL drag with the mouse (what a user does)
  const box = await slider.boundingBox();
  await page.mouse.move(box.x + 6, box.y + box.height / 2);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width * 0.75, box.y + box.height / 2, { steps: 12 });
  await page.mouse.up();
  console.log("label after REAL drag:", await label());

  // switch
  await page.locator('button[data-path="kiosk.locked"]').click();
  console.log("kiosk class after click:", await page.locator('button[data-path="kiosk.locked"]').getAttribute("class"));

  // segmented orientation
  await page.locator('button[data-val="portrait"]').click();
  // stepper
  await page.locator('#sec-defaults button').nth(1).click();

  const json = await page.locator("#jsonPreview").textContent();
  const cfg = JSON.parse(json);
  console.log("cfg.kiosk.locked =", cfg.kiosk.locked);
  console.log("cfg.display.orientation =", cfg.display.orientation);
  console.log("cfg.defaults.duration_minutes =", cfg.defaults.duration_minutes);
  console.log("cfg.display.name_size_sp =", cfg.display.name_size_sp);
  console.log("console errors:", errors.length ? errors : "none");

  await page.screenshot({ path: "/tmp/opencode/pw_admin.png", fullPage: true });
  await browser.close();
})();
