# Browser tests (Playwright)

`admin-form-test.js` drives the admin page in a real Chromium and asserts the
form controls (slider labels, toggles, segmented control, steppers). Run it with
Playwright available:

    cd tools/pw && npm i playwright   # or PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 npm i playwright
    node admin-form-test.js           # uses /usr/bin/chromium via executablePath

The MCP server `@playwright/mcp` is also configured in opencode for interactive
driving.
