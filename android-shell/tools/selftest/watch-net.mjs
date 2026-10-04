/**
 * 验证注入脚本的 __dshWatchNet —— 它决定「正在同步…」气泡何时出现/何时变"同步完成"。
 *
 * 为什么是网络驱动：实测锁屏解锁（会话本来没在动、没有任何流量）也会弹气泡 1.5 秒，
 * 因为老实现是"先无条件弹出来再判断"。现在只有**真的观测到流量**才会提示。
 *
 * 三个场景：
 *  A. 全程无网络活动            → **一个回调都不能发**（壳也就不显示任何东西）
 *  B. fetch 流吐 2s 后停        → 期间 busy，最后一片后 ~500ms 出 done，之后不再回调
 *  C. 传输途中主线程被阻塞 1.5s → 阻塞不影响判定（分片在主线程恢复后照样被读到）
 *
 * 跑法：node /path/to/dsh-shell/android-shell/tools/selftest/watch-net.mjs
 */
import pw from 'playwright-core';
const { chromium } = pw;
import fs from 'node:fs';

const src = fs.readFileSync('/path/to/dsh-shell/android-shell/app/java/app/dsh/shell/MainActivity.java', 'utf8');
const blk = src.slice(src.indexOf('THEME_SCRIPT ='), src.indexOf('private WebView web;'))
  .split('\n').filter((l) => !l.trim().startsWith('//')).join('\n');
const lits = [...blk.matchAll(/"((?:[^"\\]|\\.)*)"/g)].map((m) => JSON.parse('"' + m[1] + '"'));
const themeScript = lits.join('');
console.log(`注入脚本 ${themeScript.length} 字符 | 网络探针 ${themeScript.includes('ReadableStream.prototype.getReader')} | watchNet ${themeScript.includes('__dshWatchNet')}`);

const cfg = fs.readFileSync('./config.env', 'utf8');
const pick = (k) => (cfg.match(new RegExp('^' + k + '=(.*)$', 'm')) || [])[1]?.trim().replace(/^["']|["']$/g, '');
const ws = pick('BROWSER_CDP').replace(/^http/, 'ws').replace(/\/+$/, '') + '/chromium?token=' + encodeURIComponent(pick('BROWSER_TOKEN'));

let pass = 0, fail = 0;
const check = (name, ok, detail = '') => {
  if (ok) { pass++; console.log(`  ✅ ${name}`); } else { fail++; console.log(`  ❌ ${name} ${detail}`); }
};

const browser = await chromium.connectOverCDP(ws);
const ctx = await browser.newContext();
const page = await ctx.newPage();
await page.setContent('<html><body><div id="app" style="height:600px"></div></body></html>');
await page.evaluate(themeScript);
await page.evaluate(() => {
  window.__ev = [];
  window.DshBridge = {
    sync: (seq, kind) => window.__ev.push([seq, kind, Math.round(performance.now())]),
    ready: () => {}, note: () => {}, setBackground: () => {},
  };
  // 统一的"造一条会吐 n 片、间隔 gapMs 的流"的工具
  window.__feed = (n, gapMs) => {
    const rs = new ReadableStream({
      start(c) {
        let i = 0;
        const t = setInterval(() => {
          if (i++ < n) { c.enqueue(new Uint8Array([1])); window.__last = Math.round(performance.now() - window.__t0); }
          else { clearInterval(t); c.close(); }
        }, gapMs);
      },
    });
    const rd = rs.getReader();
    (function pump() { rd.read().then((r) => { if (!r.done) pump(); }); })();
  };
});

// ── A. 全程无网络活动 ─────────────────────────────────────────
console.log('\nA. 全程无网络活动（模拟锁屏解锁、会话没在动）');
await page.evaluate(() => { window.__ev = []; window.__t0 = performance.now(); window.__dshWatchNet(1); });
await page.waitForTimeout(4500);
let ev = await page.evaluate(() => window.__ev);
console.log(`  回调: ${JSON.stringify(ev)}`);
check('无流量时一个回调都不发（不再无脑弹气泡）', ev.filter((e) => e[0] === 1).length === 0, `实际 ${ev.length} 个`);

// ── B. 流吐 2s 后停 ──────────────────────────────────────────
console.log('\nB. fetch 流吐 2s 后停止（模拟加载会话历史）');
await page.evaluate(() => { window.__ev = []; window.__t0 = performance.now(); window.__dshWatchNet(2); window.__feed(10, 200); });
await page.waitForTimeout(6000);
ev = await page.evaluate(() => window.__ev);
const b = ev.filter((e) => e[0] === 2);
const busy = b.filter((e) => e[1] === 'busy');
const done = b.filter((e) => e[1] === 'done');
console.log(`  busy ${busy.length} 次，done ${done.length} 次；最后一片 @${(await page.evaluate(() => window.__last))}ms`);
check('有流量时发 busy（气泡该出现）', busy.length > 0, `busy=${busy.length}`);
check('传完后发 done', done.length === 1, `done=${done.length}`);
const last = await page.evaluate(() => window.__last);
const doneAt = done[0] ? done[0][2] - Math.round(await page.evaluate(() => window.__t0)) : null;
check('done 在最后一片之后 ~500ms', doneAt !== null && doneAt - last >= 400 && doneAt - last < 1200,
  `最后一片@${last} done@${doneAt}`);
check('done 之后不再回调', b.filter((e) => e[2] > (done[0]?.[2] ?? 0)).length === 0);

// ── C. 传输途中主线程被阻塞 1.5s ─────────────────────────────
console.log('\nC. 传输途中主线程被阻塞 1.5s');
await page.evaluate(() => {
  window.__ev = []; window.__t0 = performance.now(); window.__dshWatchNet(3);
  const rs = new ReadableStream({
    start(c) {
      let i = 0;
      const t = setInterval(() => {
        if (i++ < 10) c.enqueue(new Uint8Array([1]));
        else { clearInterval(t); c.close(); }
      }, 200);
    },
  });
  const rd = rs.getReader();
  (function pump() { rd.read().then((r) => { if (!r.done) pump(); }); })();
  const until = performance.now() + 1500;
  while (performance.now() < until) { /* 阻塞主线程 */ }
});
await page.waitForTimeout(6000);
ev = await page.evaluate(() => window.__ev);
const c = ev.filter((e) => e[0] === 3);
check('阻塞期间/之后仍能正确报 busy+done', c.filter((e) => e[1] === 'busy').length > 0 && c.filter((e) => e[1] === 'done').length === 1,
  JSON.stringify(c.slice(0, 3)));
check('seq 原样回传', c.every((e) => e[0] === 3));

await browser.close();
console.log(`\n结果：${pass} 通过 / ${fail} 失败`);
process.exit(fail === 0 ? 0 : 1);
