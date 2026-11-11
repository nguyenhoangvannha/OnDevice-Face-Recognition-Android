// Headless-Chrome check of the stranger screen against mock_api.py. See README.md in this folder.
const puppeteer = require("puppeteer-core");
const assert = (c, m) => { if (!c) { console.error("FAIL: " + m); process.exitCode = 1; } else console.log("ok: " + m); };
(async () => {
  const browser = await puppeteer.launch({ executablePath: process.env.CHROME || "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", headless: true });
  const page = await browser.newPage();
  const dialogs = [];
  page.on("dialog", async (d) => { dialogs.push(d.message()); await d.accept(); });
  const external = [];
  page.on("request", (r) => { if (!r.url().startsWith("http://127.0.0.1:8765")) external.push(r.url()); });
  await page.goto("http://127.0.0.1:8765/");
  await page.evaluate(() => sessionStorage.setItem("facenetCoprocessorToken", "mock-token"));
  await page.reload();
  await page.waitForFunction(() => document.querySelectorAll("#strangers tr").length === 3 && document.querySelectorAll("select.stranger-person option").length >= 3);
  const rows = await page.$$eval("#strangers tr", (trs) => trs.map((tr) => Array.from(tr.children).slice(0, 3).map((td) => td.textContent)));
  assert(JSON.stringify(rows[0]) === '["Person_03","5","4"]', "row shows name/appearances/embeddings " + JSON.stringify(rows[0]));
  const opts = await page.$$eval("#strangers tr:first-child select option", (o) => o.map((x) => x.textContent));
  assert(opts.some((t) => t.startsWith("<img")), "XSS name rendered as text in dropdown");
  assert(await page.evaluate(() => document.querySelectorAll("#strangers img, #persons img, select img").length === 0), "no injected <img>");
  assert(await page.$eval("#stranger-threshold", (e) => e.value) === "0.70", "settings threshold shown");
  assert((await page.$eval("#stranger-limits", (e) => e.textContent)).includes("200 groups"), "limits shown");
  // Assign Person_03 to Nha (id 9)
  await page.select("#strangers tr:first-child select", "9");
  await page.evaluate(() => [...document.querySelectorAll("#strangers tr:first-child button")].find((b) => b.textContent === "Assign").click());
  await page.waitForFunction(() => document.querySelectorAll("#strangers tr").length === 2);
  assert((await page.$eval("#strangers-msg", (e) => e.textContent)).includes("Person_03 → Added to Nha"), "assign message");
  // Create new person from Person_04
  await page.type("#strangers tr:first-child input", "  Chu_Ba ");
  await page.evaluate(() => [...document.querySelectorAll("#strangers tr:first-child button")].find((b) => b.textContent === "Create").click());
  await page.waitForFunction(() => document.querySelectorAll("#strangers tr").length === 1);
  assert((await page.$eval("#strangers-msg", (e) => e.textContent)).includes("Created Chu_Ba (id 20)"), "create message");
  assert(await page.evaluate(() => [...document.querySelectorAll("#persons td")].some((t) => t.textContent === "Chu_Ba")), "persons table refreshed");
  // Empty name / no person selected → error, no request
  await page.evaluate(() => [...document.querySelectorAll("#strangers tr:first-child button")].find((b) => b.textContent === "Assign").click());
  await new Promise((r) => setTimeout(r, 200));
  assert((await page.$eval("#strangers-msg", (e) => e.className)) === "error", "assign without selection is an error");
  // Delete Person_05
  await page.evaluate(() => [...document.querySelectorAll("#strangers tr:first-child button")].find((b) => b.textContent === "Delete").click());
  await page.waitForFunction(() => document.querySelectorAll("#strangers tr").length === 0);
  // Settings save
  await page.$eval("#stranger-threshold", (e) => { e.value = "0.72"; });
  await page.click("#stranger-settings-form button");
  await page.waitForFunction(() => document.getElementById("stranger-settings-msg").textContent.includes("0.72"));
  await page.click("#strangers-delete-all");
  await page.waitForFunction(() => document.getElementById("strangers-msg").textContent.startsWith("Deleted"));
  const log = await (await fetch("http://127.0.0.1:8765/__log")).json();
  const writes = log.filter((l) => l[0] !== "GET").map((l) => l[0] + " " + l[1] + " " + JSON.stringify(l[2]));
  console.log(writes.join("\n"));
  assert(writes.includes('POST /api/strangers/3/promote {"person_id":9}'), "promote by person_id");
  assert(writes.includes('POST /api/strangers/4/promote {"name":"Chu_Ba"}'), "promote by trimmed name");
  assert(writes.includes("DELETE /api/strangers/5 null"), "delete one");
  assert(writes.includes('PUT /api/settings/strangers {"threshold":0.72}'), "save threshold");
  assert(writes.includes("DELETE /api/strangers null"), "delete all");
  assert(dialogs.length === 4, "4 confirms: " + JSON.stringify(dialogs));
  assert(external.length === 0, "no external requests");
  assert(await page.evaluate(() => window.__xss === undefined), "no XSS executed");
  await browser.close();
})().catch((e) => { console.error(e); process.exit(1); });
