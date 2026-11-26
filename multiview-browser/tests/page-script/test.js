// Tests the page script (android/app/src/main/assets/js/inject.js) in
// headless Chromium, the engine Android's WebView is built on.
// Run: npm install && npx playwright install chromium && npm test
const { chromium } = require('playwright');
const http = require('http');
const fs = require('fs');
const path = require('path');

const SITE = path.join(__dirname, 'site');
const ASSETS = process.argv[2] || path.join(__dirname, '../../android/app/src/main/assets');
const server = http.createServer((req, res) => {
  const file = path.join(SITE, req.url.split('?')[0] === '/' ? 'index.html' : req.url.split('?')[0]);
  if (!fs.existsSync(file)) { res.writeHead(404); return res.end(); }
  const data = fs.readFileSync(file);
  const type = file.endsWith('.webm') ? 'video/webm' : 'text/html';
  const range = req.headers.range;
  if (range && type === 'video/webm') {
    const [s, e] = range.replace('bytes=', '').split('-');
    const start = +s, end = e ? +e : data.length - 1;
    res.writeHead(206, { 'Content-Type': type, 'Content-Range': `bytes ${start}-${end}/${data.length}`, 'Accept-Ranges': 'bytes', 'Content-Length': end - start + 1 });
    return res.end(data.subarray(start, end + 1));
  }
  res.writeHead(200, { 'Content-Type': type, 'Accept-Ranges': 'bytes', 'Content-Length': data.length });
  res.end(data);
});

const results = [];
function check(name, ok, detail) { results.push({ name, ok: !!ok, detail }); console.log((ok ? 'PASS ' : 'FAIL ') + name + (ok ? '' : '  -> ' + JSON.stringify(detail))); }
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

(async () => {
  await new Promise(r => server.listen(0, r));
  const base = `http://127.0.0.1:${server.address().port}`;
  const css = fs.readFileSync(path.join(ASSETS, 'css/cosmetic.css'), 'utf8');
  const cfg = { keepAlive: true, guard: true, gpc: true, css, skipAds: true, allow: [] };
  const script = fs.readFileSync(path.join(ASSETS, 'js/inject.js'), 'utf8')
    .replace('__MV_BRIDGE__', '_testbridge').replace('/*__MV_CONFIG__*/ null', JSON.stringify(cfg));
  const stub = `
    window.__mvMsgs = []; window.__mvListeners = [];
    window._testbridge = {
      postMessage(s) { window.__mvMsgs.push(JSON.parse(s)); },
      addEventListener(t, fn) { if (t === 'message') window.__mvListeners.push(fn); }
    };
    window.__mvSend = (o) => window.__mvListeners.forEach(fn => fn({ data: JSON.stringify(o) }));`;

  const browser = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });
  const page = await browser.newPage();
  page.on('pageerror', e => console.log('PAGE ERROR', e.message));
  await page.addInitScript(stub);
  await page.addInitScript(script);
  await page.goto(base + '/');
  await page.waitForFunction(() => document.getElementById('v').readyState >= 1);

  const msgs = () => page.evaluate(() => window.__mvMsgs.slice());
  const send = (o) => page.evaluate((o) => window.__mvSend(o), o);
  const v = (expr) => page.evaluate(`(() => { const v = document.getElementById('v'); return ${expr}; })()`);

  // 1. background-visibility spoofing
  check('document.hidden is false', await page.evaluate(() => document.hidden === false && document.visibilityState === 'visible'));
  await page.evaluate(() => { document.dispatchEvent(new Event('visibilitychange')); window.dispatchEvent(new Event('blur')); });
  check('page never sees visibilitychange / window blur', await page.evaluate(() => window.pageSawVisibility === 0 && !window.pageSawBlur));
  check('document.hasFocus() is true', await page.evaluate(() => document.hasFocus()));
  check('Global Privacy Control signal', await page.evaluate(() => navigator.globalPrivacyControl === true));

  // 2. ad hiding
  check('AdSense box hidden', await page.evaluate(() => getComputedStyle(document.getElementById('ad')).display === 'none'));
  check('GPT ad slot hidden', await page.evaluate(() => getComputedStyle(document.getElementById('div-gpt-ad-123')).display === 'none'));

  // 3. state + sniffing
  await v('v.play()');
  await sleep(1200);
  let m = await msgs();
  const st = m.filter(x => x.type === 'state').pop();
  check('state reports playing video', st && st.playing === 1 && st.top === true && st.main && st.main.src.endsWith('/clip.webm'), st);
  check('state reports sound', st && st.audible === true, st);
  check('state reports on-screen rect', st && st.main.rw > 100 && st.main.rh > 50, st && st.main);
  const media = m.find(x => x.type === 'media');
  check('direct video link found', media && media.src.endsWith('/clip.webm') && media.w === 320 && media.d > 19, media);

  // 4. mute commands
  await send({ cmd: 'mute', v: true });
  check('mute command mutes', await v('v.muted === true'));
  await send({ cmd: 'mute', v: false });
  await sleep(50);
  check('unmute command restores sound', await v('v.muted === false'));

  // 5. pause all / play all
  await send({ cmd: 'pauseAll' });
  await sleep(400);
  check('pauseAll pauses', await v('v.paused'));
  m = await msgs();
  check('state reports 0 playing after pauseAll', m.filter(x => x.type === 'state').pop().playing === 0);
  await send({ cmd: 'playAll' });
  await sleep(400);
  check('playAll resumes', await v('!v.paused'));

  // 6. keep playing (muted) when another tab takes the sound: message first, then pause
  await page.evaluate(() => { window.__mvMsgs.length = 0; });
  await send({ cmd: 'peerAudio' });
  await v('v.pause()');
  await sleep(600);
  m = await msgs();
  check('guard resumes after peer took sound', await v('!v.paused'));
  check('guard mutes the resumed video', await v('v.muted'));
  check('guard tells the app it auto-muted', m.some(x => x.type === 'autoMuted'), m.map(x => x.type));
  await send({ cmd: 'mute', v: false });
  await sleep(100);

  // 6b. pause first, peerAudio arrives a bit later
  await page.evaluate(() => { window.__mvMsgs.length = 0; });
  await sleep(2600);
  await v('v.pause()');
  await sleep(300);
  check('unexplained pause alone is left alone', await v('v.paused'));
  await send({ cmd: 'peerAudio' });
  await sleep(300);
  check('late peerAudio still rescues the paused video', await v('!v.paused && v.muted'));
  await send({ cmd: 'mute', v: false });
  await sleep(100);

  // 7. a pause YOU make is respected
  await sleep(2600);
  await page.mouse.click(5, 5);
  await v('v.pause()');
  await send({ cmd: 'peerAudio' });
  await sleep(700);
  check('user pause is respected', await v('v.paused'));
  await v('v.play()');
  await sleep(300);

  // 8. site tries to unmute a muted tab on its own -> re-muted
  await sleep(1600);
  await send({ cmd: 'mute', v: true });
  await v('(v.muted = false)');
  await sleep(200);
  check('site cannot unmute a muted tab', await v('v.muted === true'));

  // 9. you unmute it yourself -> app is told
  await page.evaluate(() => { window.__mvMsgs.length = 0; });
  await page.mouse.click(5, 5);
  await v('(v.muted = false)');
  await sleep(200);
  m = await msgs();
  check('your own unmute sticks', await v('v.muted === false'));
  check('app told about your unmute', m.some(x => x.type === 'userUnmuted'), m.map(x => x.type));

  // 10. seek (bookmark resume)
  await send({ cmd: 'seek', t: 12.5 });
  await sleep(300);
  check('seek command jumps to saved time', await v('Math.abs(v.currentTime - 12.5) < 1.5'), await v('v.currentTime'));

  // 11. iframe gets its own script and bridge
  const frame = page.frames().find(f => f.url().endsWith('/frame.html'));
  await frame.evaluate(() => document.getElementById('fv').play());
  await sleep(800);
  const fm = await frame.evaluate(() => window.__mvMsgs.filter(x => x.type === 'state').pop());
  check('iframe reports its own video (not top frame)', fm && fm.top === false && fm.playing === 1 && fm.audible === false, fm);

  // 12. new Audio() objects not in the page are tracked
  await page.evaluate(() => { window.__mvMsgs.length = 0; window.a = new Audio('/clip.webm'); window.a.play(); });
  await sleep(800);
  m = await msgs();
  check('detached Audio() tracked', m.filter(x => x.type === 'state').pop().count >= 2, m.filter(x => x.type === 'state').pop());

  // 13. navigating away tells the app the frame is gone
  await page.evaluate(() => { const orig = window._testbridge.postMessage; window._testbridge.postMessage = (s) => { orig(s); if (JSON.parse(s).type === 'gone') sessionStorage.setItem('gone', '1'); }; });
  await page.goto(base + '/frame.html');
  check('frame says goodbye on navigation', await page.evaluate(() => sessionStorage.getItem('gone') === '1'));

  // 14. allowlisted site: no cosmetic hiding
  const page2 = await browser.newPage();
  await page2.addInitScript(stub);
  await page2.addInitScript(script.replace('"allow":[]', '"allow":["127.0.0.1"]'));
  await page2.goto(base + '/');
  check('ad hiding off on allowlisted site', await page2.evaluate(() => getComputedStyle(document.getElementById('ad')).display !== 'none'));

  // 15. no bridge (e.g. feature missing) -> script must not throw
  const page3 = await browser.newPage();
  const errors = [];
  page3.on('pageerror', e => errors.push(e.message));
  await page3.addInitScript(script);
  await page3.goto(base + '/');
  await page3.evaluate(() => document.getElementById('v').play());
  await sleep(500);
  check('works without the bridge object', errors.length === 0, errors);

  await browser.close();
  server.close();
  const failed = results.filter(r => !r.ok).length;
  console.log(`\n${results.length - failed}/${results.length} passed`);
  process.exit(failed ? 1 : 0);
})().catch(e => { console.error(e); process.exit(2); });
