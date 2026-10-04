/**
 * 手机视口预览：按某 Android 15 手机 的真实参数（436x971 CSS px / DPR 2.75）渲染 DSH。
 * 目的只是"看看手机上会看到什么"，不参与 APK 构建。
 *
 * 用法： node preview-mobile.mjs <url> <out.png> [width] [height] [dpr]
 */
import pw from 'playwright-core';
import fs from 'node:fs';
const { chromium } = pw;

const cfg = fs.readFileSync('./config.env', 'utf8');
const pick = (k) =>
  (cfg.match(new RegExp('^' + k + '=(.*)$', 'm')) || [])[1]?.trim().replace(/^["']|["']$/g, '');
// browserless 的 CDP 入口固定是 ws://<endpoint>/chromium，且必须带 token 查询参数
const ws = pick('BROWSER_CDP').replace(/^http/, 'ws').replace(/\/+$/, '')
  + '/chromium?token=' + encodeURIComponent(pick('BROWSER_TOKEN'));

const [url, out, w = '436', h = '971', dpr = '2.75'] = process.argv.slice(2);

const browser = await chromium.connectOverCDP(ws);
const context = await browser.newContext({
  viewport: { width: Number(w), height: Number(h) },
  deviceScaleFactor: Number(dpr),
  isMobile: true,
  hasTouch: true,
  userAgent:
    'Mozilla/5.0 (Linux; Android 15; Android15Device) AppleWebKit/537.36 (KHTML, like Gecko) ' +
    'Chrome/140.0.0.0 Mobile Safari/537.36',
});
const page = await context.newPage();
const errs = [];
page.on('pageerror', (e) => errs.push('pageerror: ' + e.message));
// @file 形式：容器看不到本机文件系统，只能把 HTML 内容塞进去
if (url.startsWith('@')) {
  const fsx = await import('node:fs');
  await page.setContent(fsx.readFileSync(url.slice(1), 'utf8'), { waitUntil: 'load' });
} else {
  await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 60000 });
}
await page.waitForTimeout(4000);
const info = await page.evaluate(() => ({
  viewport: [window.innerWidth, window.innerHeight],
  dpr: window.devicePixelRatio,
  scrollW: document.documentElement.scrollWidth,
  bodyBg: getComputedStyle(document.body).backgroundColor,
}));
await page.screenshot({ path: out });
console.log(JSON.stringify({ ...info, errs }, null, 2));
await page.close();
await context.close();
await browser.close();
