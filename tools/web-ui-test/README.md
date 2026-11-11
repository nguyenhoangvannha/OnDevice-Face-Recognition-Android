# Browser test for the web test page

Not part of Gradle/CI (needs Chrome). Uses a fake API, no phone, no real token.

```bash
cd tools/web-ui-test
npm install --no-save puppeteer-core@23      # node_modules is git-ignored
python3 mock_api.py ../../app/src/main/resources/coprocessor-web &
node strangers.test.js                       # CHROME=/path/to/chrome if not on macOS
kill %1
```

Checks the Strangers screen: list, assign to a known person (`person_id`), create a person (trimmed `name`), delete one,
delete all, stranger threshold, a confirm dialog before every write, no external requests, and that a person named
`<img src=x onerror=...>` is shown as text (no HTML injection).
