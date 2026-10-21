// CI runs `--browsers=ChromeHeadless`, and karma cannot redefine a built-in launcher name,
// so containers/root (where Chrome refuses to sandbox) get a CHROME_BIN wrapper adding --no-sandbox.
// Normal CI runners (non-root) use the stock binary untouched.
const fs = require('fs');
const os = require('os');
const path = require('path');

function findChrome() {
  if (process.env.CHROME_BIN) return process.env.CHROME_BIN;
  const root = '/opt/pw-browsers';
  if (!fs.existsSync(root)) return undefined;
  for (const d of fs.readdirSync(root).filter((n) => n.startsWith('chromium-')).sort().reverse()) {
    const p = path.join(root, d, 'chrome-linux', 'chrome');
    if (fs.existsSync(p)) return p;
  }
  return undefined;
}

const chrome = findChrome();
if (chrome && process.getuid && process.getuid() === 0) {
  const wrapper = path.join(os.tmpdir(), 'finpath-chrome-nosandbox.sh');
  fs.writeFileSync(wrapper, `#!/bin/sh\nexec "${chrome}" --no-sandbox --disable-gpu --disable-dev-shm-usage "$@"\n`, { mode: 0o755 });
  process.env.CHROME_BIN = wrapper;
} else if (chrome) {
  process.env.CHROME_BIN = chrome;
}

module.exports = function (config) {
  config.set({
    basePath: '',
    frameworks: ['jasmine', '@angular-devkit/build-angular'],
    plugins: [
      require('karma-jasmine'),
      require('karma-chrome-launcher'),
      require('karma-jasmine-html-reporter'),
      require('karma-coverage'),
      require('@angular-devkit/build-angular/plugins/karma'),
    ],
    client: { jasmine: { random: true }, clearContext: false },
    jasmineHtmlReporter: { suppressAll: true },
    coverageReporter: { dir: require('path').join(__dirname, './coverage/web'), subdir: '.', reporters: [{ type: 'html' }, { type: 'text-summary' }] },
    reporters: ['progress', 'kjhtml'],
    browsers: ['ChromeHeadless'],
    restartOnFileChange: true,
  });
};
