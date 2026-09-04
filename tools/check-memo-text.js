/* 호환판 www를 실제 fetch 경로로 태워 「빈 메모 안내 문구」를 실측한다.
   정적 서버 8791 + 가짜 GAS 8792가 먼저 떠 있어야 한다. */
const { chromium } = require('/c/Users/USER/Downloads/프로젝트/youcall-promo/node_modules/playwright'.replace(/^\/c/, 'C:'));
const OLD = '설정 시트';
const NEW = '우리 반 공지';

(async () => {
  const fetchCase = async (c) => {
    const r = await fetch('http://localhost:8792/__set?case=' + c);
    return r.text();
  };
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 1366, height: 768 } });
  const page = await ctx.newPage();
  await page.addInitScript(() => {
    localStorage.setItem('yc_settings', JSON.stringify({
      webAppUrl: 'http://localhost:8792/exec', grade: '1', classNum: '2'
    }));
  });

  let fail = 0;
  for (const c of ['empty', 'merged']) {
    await fetchCase(c);
    await page.goto('http://localhost:8791/index.html', { waitUntil: 'networkidle' });
    await page.waitForTimeout(3500);
    const memo = await page.evaluate(() => {
      const el = document.getElementById('memo') || document.querySelector('.memo, #classMemo, [id*=memo i]');
      return el ? { text: el.textContent.trim(), cls: el.className } : null;
    });
    const shot = 'tools/shot-' + c + '.png';
    await page.screenshot({ path: shot });
    console.log('[' + c + '] memo=', JSON.stringify(memo), '→', shot);
    if (c === 'empty') {
      if (!memo || memo.text.indexOf(NEW) < 0) { console.log('  ✗ 새 문구가 안 보인다'); fail++; }
      else if (memo.text.indexOf(OLD) >= 0) { console.log('  ✗ 옛 문구가 남아 있다'); fail++; }
      else console.log('  ✓ 새 문구 정상');
    } else {
      if (!memo || memo.text.indexOf('리코더') < 0) { console.log('  ✗ 실제 메모가 안 그려진다'); fail++; }
      else console.log('  ✓ 실제 메모 정상');
    }
  }
  await browser.close();
  console.log(fail === 0 ? '\n결과: 전부 통과' : '\n결과: 실패 ' + fail + '건');
  process.exit(fail === 0 ? 0 : 1);
})();
