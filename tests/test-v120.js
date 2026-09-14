'use strict';
/* 유콜 보드 — 서버 v4.24 대응 검사 (test-v120)

   app.js는 즉시실행 함수 안이라 require로 부를 수 없다. 그래서 «함수 원문을 이름으로 떼어» node vm에 넣고,
   그 함수가 기대는 전역(document·localStorage·api 등)만 가짜로 채워 돌린다.
   검사 파일에 함수 사본을 따로 두지 않는 이유 — 사본은 앱이 바뀌어도 그대로라 «검사는 통과, 앱은 고장»이 된다.
   떼는 규칙: app.js의 최상위 함수는 0칸 들여쓰기로 시작하고 0칸의 `}`로 끝난다(한 줄짜리는 그 줄).

     node tests/test-v120.js                                이 저장소 www/js/app.js
     node tests/test-v120.js <app.js> [<YouCallService.java>]  다른 사본(고치기 전 원본 등)과 대조

   «오늘»에 기대면 주말에 거짓 실패가 난다 — 날짜는 전부 가짜 Date로 고정한다. */
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const FLAVOR = 'compat';    // 'android' = 기본판, 'compat' = 호환판(전용 코드 회귀 검사가 더 돈다)
const ROOT = path.join(__dirname, '..');
const APP = process.argv[2] || path.join(ROOT, 'www', 'js', 'app.js');
const JAVA = process.argv[3] || path.join(ROOT, 'android', 'app', 'src', 'main', 'java', 'com', 'kimju', 'youcallboard', 'YouCallService.java');
const SRC = fs.readFileSync(APP, 'utf8').replace(/\r\n/g, '\n');
const JSRC = fs.readFileSync(JAVA, 'utf8').replace(/\r\n/g, '\n');
const LINES = SRC.split('\n');

/* ---------- 원문 떼기 ---------- */
function fn(name) {
  const re = new RegExp('^(async )?function ' + name + '\\s*\\(');
  const i = LINES.findIndex(l => re.test(l));
  if (i < 0) throw new Error('app.js에 함수가 없음: ' + name);
  const first = LINES[i];
  const open = (first.match(/\{/g) || []).length, close = (first.match(/\}/g) || []).length;
  if (open > 0 && open === close) return first;
  for (let j = i + 1; j < LINES.length; j++) if (/^\}/.test(LINES[j])) return LINES.slice(i, j + 1).join('\n');
  throw new Error('함수 끝을 못 찾음: ' + name);
}
function varLine(name) {
  const re = new RegExp('^var ' + name + '\\s*=');
  const i = LINES.findIndex(l => re.test(l));
  if (i < 0) throw new Error('app.js에 변수가 없음: ' + name);
  if (/;\s*(\/\/.*)?$/.test(LINES[i])) return LINES[i];
  for (let j = i + 1; j < LINES.length; j++) if (/^\};?/.test(LINES[j])) return LINES.slice(i, j + 1).join('\n');
  throw new Error('변수 끝을 못 찾음: ' + name);
}
function has(name) { try { fn(name); return true; } catch (e) { return false; } }

/* ---------- 가짜 환경 ---------- */
function escText(s) { return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;'); }
// innerHTML을 새로 넣으면 자식이 비워지는 실제 DOM 동작까지 흉내 낸다 — 다시 그릴 때 칸이 쌓이는 결함을 잡으려고.
class El {
  constructor(tag) {
    this.tagName = tag; this.children = []; this._html = ''; this.id = ''; this.className = ''; this.title = '';
    this.style = { setProperty() {}, getPropertyValue() { return ''; } };
    const self = this;
    this.classList = {
      add(c) { if (!this.contains(c)) self.className = (self.className ? self.className + ' ' : '') + c; },
      remove(c) { self.className = self.className.split(/\s+/).filter(x => x && x !== c).join(' '); },
      contains(c) { return self.className.split(/\s+/).indexOf(c) >= 0; }
    };
  }
  set innerHTML(v) { this._html = String(v); this.children = []; }
  get innerHTML() { return this._html + this.children.map(c => c.outerHTML).join(''); }
  set textContent(v) { this._html = escText(v); this.children = []; }
  get textContent() { return this._html; }
  get outerHTML() {
    return '<' + this.tagName + (this.className ? ' class="' + this.className + '"' : '') + (this.id ? ' id="' + this.id + '"' : '') + '>' + this.innerHTML + '</' + this.tagName + '>';
  }
  appendChild(c) { this.children.push(c); return c; }
  querySelector() { return null; }
}
function makeDoc() {
  const reg = {};
  ['periodRow', 'weekWrap', 'mealList'].forEach(id => { reg[id] = new El('div'); reg[id].id = id; });
  return { reg, getElementById: id => reg[id] || null, createElement: t => new El(t), querySelectorAll: () => [], querySelector: () => null };
}
function makeStorage(init) {
  const m = Object.assign({}, init || {});
  return { m, getItem: k => (k in m ? m[k] : null), setItem: (k, v) => { m[k] = String(v); }, removeItem: k => { delete m[k]; } };
}
const PRELUDE = [
  'var SETTINGS = null, SCHEDULE = [];',
  'var _todaySubjects = {}, _todayItems = {}, _todayLoaded = false, _todayCount = 0, _todayLastPeriod = 0, _weekHasData = false;',
  'var _lastTodayList = null, _lastWeekMap = null;',
  'function fitWeekBox() {} function fitMealBox() {} function refitAll() {}',
  // 가짜 Date — 인자 없이 만들면 __NOW(검사가 정한 시각)
  'var __NOW = 0; (function () { var R = Date; class D extends R { constructor(...a) { if (a.length) super(...a); else super(__NOW); } static now() { return __NOW; } } globalThis.Date = D; })();'
].join('\n');

function sandbox(names, opts) {
  opts = opts || {};
  const doc = makeDoc();
  const c = Object.assign({ console, URL, setTimeout, clearTimeout, setImmediate, document: doc, localStorage: makeStorage(opts.storage) }, opts.globals || {});
  vm.createContext(c);
  vm.runInContext(PRELUDE, c);
  const parts = [];
  (opts.vars || []).forEach(v => parts.push(varLine(v)));
  // 시정 저장 키는 있으면 늘 싣는다(없는 옛 코드에서는 기능 검사가 제 이유로 실패하게)
  try { parts.push(varLine('PERIOD_CONFIG_KEY')); } catch (e) { /* 옛 코드 */ }
  names.forEach(n => parts.push(fn(n)));
  // escHtml도 있으면 싣는다 — «escHtml 없음»으로 실패하면 고치기 전 코드의 진짜 결함(이스케이프·교체 표시)이 가려진다
  (opts.optional || []).concat(names.indexOf('escHtml') < 0 ? ['escHtml'] : []).forEach(n => { if (has(n)) parts.push(fn(n)); });
  if (opts.after) parts.push(opts.after);
  vm.runInContext(parts.join('\n'), c, { filename: 'app.js 조각' });
  c.__doc = doc;
  return c;
}
const RENDER = ['toMinutes', 'buildSchedule', 'ymdKey', 'renderPeriodRow', 'renderWeek', 'currentPeriodStatus'];
function renderBox(extra, opts) {
  opts = opts || {};
  const c = sandbox(RENDER.concat(extra || []), { vars: ['PERIOD_CONFIG'], optional: ['effectiveSchedule', 'validPeriodConfig', 'applyPeriodConfig'], storage: opts.storage, after: 'SCHEDULE = buildSchedule(PERIOD_CONFIG);' });
  c.__NOW = new Date(2026, 8, 15, 10, 23).getTime();   // 2026-09-15(화) 10:23 — 3교시
  return c;
}
function weekKeys() {   // 가짜 «오늘»(9/15 화)이 든 주의 월~금
  return ['20260914', '20260915', '20260916', '20260917', '20260918'];
}
function subjWeek(n) {
  const subj = ['국어', '수학', '영어', '과학', '사회', '체육', '음악'];
  const w = {};
  weekKeys().forEach((k, i) => { w[k] = subj.slice(0, n || 7).map((s, j) => ({ period: j + 1, subject: subj[(i + j) % 7] })); });
  return w;
}
function todayList(n) { return subjWeek(n)['20260915'].map(x => Object.assign({}, x)); }
const flush = () => new Promise(r => setImmediate(r));

/* ---------- 검사 틀 ---------- */
const results = [];
function check(name, body) { results.push({ name, body }); }
function ok(cond, msg) { if (!cond) throw new Error(msg); }
function same(a, b, msg) {
  const A = JSON.stringify(a), B = JSON.stringify(b);
  if (A !== B) throw new Error(msg + '\n      받은 값: ' + A + '\n      기대 값: ' + B);
}

/* ===== 1. 주소 위생 ===== */
check('1-1 cleanWebAppUrl: 교사 주소의 role·k·#해시를 떼고 https를 붙인다', () => {
  const c = sandbox(['normalizeWebAppUrl', 'cleanWebAppUrl']);
  same(c.cleanWebAppUrl('script.google.com/macros/s/AKfy/exec?role=teacher&k=S3CRET#top'), 'https://script.google.com/macros/s/AKfy/exec', '교사 주소');
  same(c.cleanWebAppUrl('https://script.google.com/macros/s/X/exec?k=1&foo=bar&role=teacher'), 'https://script.google.com/macros/s/X/exec?foo=bar', '다른 쿼리는 남긴다');
  same(c.cleanWebAppUrl('https://a.b/exec?K=1&%6B=2&Role=teacher'), 'https://a.b/exec', '대소문자·인코딩된 이름');
  same(c.cleanWebAppUrl(' https://a.b/exec \n'), 'https://a.b/exec', '앞뒤 공백');
  same(c.cleanWebAppUrl('   '), '', '빈 주소');
  same(c.cleanWebAppUrl('https://a.b/exec'), 'https://a.b/exec', '깨끗한 주소는 그대로');
});
check('1-2 buildUrl: 저장값에 k가 남아 있어도 요청에 싣지 않는다', () => {
  const c = sandbox(['normalizeWebAppUrl', 'buildUrl'], { optional: ['cleanWebAppUrl'] });
  const u = c.buildUrl('https://script.google.com/macros/s/X/exec?role=teacher&k=S3CRET#h', { api: 'calls', grade: '3', classNum: '2' });
  ok(u.indexOf('S3CRET') < 0 && !/[?&]k=/.test(u), 'k가 실렸다: ' + u);
  ok(!/role=/.test(u) && u.indexOf('#') < 0, 'role·해시가 남았다: ' + u);
  ok(/api=calls/.test(u) && /grade=3/.test(u), 'api 파라미터가 빠졌다: ' + u);
});
check('1-3 Java 서비스: 요청 주소를 만들 때 role·k를 떼는 도우미를 쓴다 (글자 검사)', () => {
  ok(/static String stripTeacherParams\(String /.test(JSRC), 'stripTeacherParams 도우미가 없다');
  ok(/stripTeacherParams\(cfg\.optString\("webAppUrl"/.test(JSRC), '설정 주소에 도우미를 거치지 않는다');
});

/* ===== 2. 학년·반 정규화 ===== */
check('2-1 normalizeClassNo: 전각·«학년»·«반»·공백을 다듬고 1~2자리 숫자만 받는다', () => {
  const c = sandbox(['normalizeClassNo']);
  const cases = [['３', '3'], ['3학년', '3'], ['2반', '2'], [' 03 ', '3'], ['０２반', '2'], ['3 학년', '3'], ['12', '12'], [3, '3'],
    ['a', ''], ['3-2', ''], ['', ''], ['123', ''], ['0', ''], ['삼', ''], [null, '']];
  cases.forEach(([inp, want]) => same(c.normalizeClassNo(inp), want, '입력 ' + JSON.stringify(inp)));
});
check('2-2 시작 시 옛 저장값 정리: k 주소·«3학년»을 고쳐 localStorage와 네이티브 저장소에 다시 쓴다', async () => {
  const sets = [];
  let nativeVal = null;
  const P = { set: o => { sets.push(o); nativeVal = o.value; return Promise.resolve(); }, get: () => Promise.resolve({ value: nativeVal }) };
  const stored = { webAppUrl: 'https://script.google.com/macros/s/X/exec?role=teacher&k=S3CRET', grade: '3학년', classNum: '２반', mode: 'standby', volume: 7 };
  const c = sandbox(['loadSettings', 'saveSettingsLocal', 'normalizeWebAppUrl', 'cleanWebAppUrl', 'normalizeClassNo', 'repairStoredSettings', 'isConfigured'], {
    vars: ['STORE_KEY', 'DEFAULTS'], optional: ['syncNativeSettings'],
    globals: { window: { Capacitor: { Plugins: { Preferences: P } } } },
    storage: { yc_settings: JSON.stringify(stored) },
    after: FLAVOR === 'compat' ? 'var _nativeSyncState = { ok: null, why: "" };' : ''
  });
  c.SETTINGS = c.repairStoredSettings(c.loadSettings());
  await flush(); await flush();
  const saved = JSON.parse(c.localStorage.getItem('yc_settings'));
  same([saved.webAppUrl, saved.grade, saved.classNum, saved.volume], ['https://script.google.com/macros/s/X/exec', '3', '2', 7], 'localStorage 정리');
  ok(sets.length >= 1, '네이티브 저장소(Preferences.set)에 다시 쓰지 않았다');
  const last = JSON.parse(sets[sets.length - 1].value);
  ok(last.webAppUrl.indexOf('S3CRET') < 0 && last.grade === '3', '네이티브 저장소에 정리 전 값: ' + sets[sets.length - 1].value);
  ok(c.isConfigured(), '정리한 설정이 «설정됨»이 아니다');
});
check('2-3 고칠 수 없는 학년·반(«a»)은 지우지 않고 미설정으로 본다', () => {
  const P = { set: () => Promise.resolve(), get: () => Promise.resolve({ value: 'x' }) };
  const stored = { webAppUrl: 'https://a.b/exec', grade: 'a', classNum: '2' };
  const c = sandbox(['loadSettings', 'saveSettingsLocal', 'normalizeWebAppUrl', 'cleanWebAppUrl', 'normalizeClassNo', 'repairStoredSettings', 'isConfigured'], {
    vars: ['STORE_KEY', 'DEFAULTS'], optional: ['syncNativeSettings'],
    globals: { window: { Capacitor: { Plugins: { Preferences: P } } } },
    storage: { yc_settings: JSON.stringify(stored) },
    after: FLAVOR === 'compat' ? 'var _nativeSyncState = { ok: null, why: "" };' : ''
  });
  c.SETTINGS = c.repairStoredSettings(c.loadSettings());
  ok(!c.isConfigured(), '«a»인데 설정됨으로 본다');
  same(JSON.parse(c.localStorage.getItem('yc_settings')).grade, 'a', '저장값을 지웠다');
});

/* ===== 3. 호출 확인(confirm) ===== */
function apiBox() {
  const calls = [];
  const c = sandbox([], { vars: ['api'], after: '' });
  c.callApi = (u, name, params, ms) => { calls.push({ name, params, ms }); return Promise.resolve({ ok: true, data: [] }); };
  return { c, calls };
}
check('3-1 api.confirmCall: row와 함께 grade·classNum을 보낸다 / TTS만 15초 제한', () => {
  const { c, calls } = apiBox();
  c.api.confirmCall('https://a.b/exec', 7, '3', '2');
  same(calls[0].name, 'confirm', 'confirm 호출');
  same([calls[0].params.row, calls[0].params.grade, calls[0].params.classNum], [7, '3', '2'], 'confirm 파라미터');
  c.api.getTts('https://a.b/exec', '홍길동 학생');
  same([calls[1].name, calls[1].ms], ['tts', 15000], 'TTS 제한 시간');
  c.api.getCalls('https://a.b/exec', '3', '2');
  ok(calls[2].ms === undefined, '호출 목록은 기본 8초 그대로여야 한다');
});
function retryBox(responses) {
  const waits = [], sent = [];
  const c = sandbox(['confirmWithRetry'], { vars: ['CONFIRM_RETRY_MS'] });
  c.wait = ms => { waits.push(ms); return Promise.resolve(); };
  c.api = { confirmCall: (u, row, g, cn) => { sent.push([row, g, cn]); return Promise.resolve(responses[Math.min(sent.length - 1, responses.length - 1)]); } };
  return { c, waits, sent };
}
check('3-2 확인 재시도: 통신 실패면 2초·5초 뒤 다시 보내 성공하면 멈춘다', async () => {
  const { c, waits, sent } = retryBox([{ ok: false, error: '시간 초과' }, { ok: false, error: '시간 초과' }, { ok: true, data: { ok: true } }]);
  const r = await c.confirmWithRetry('https://a.b/exec', 9, '3', '2');
  same(sent.length, 3, '보낸 횟수'); same(waits, [2000, 5000], '기다린 시간'); same(r.ok, true, '결과');
  same(sent[0], [9, '3', '2'], '재시도에도 반 정보');
});
check('3-3 확인 재시도: 계속 실패하면 최대 3번(2·5·10초)까지만', async () => {
  const { c, waits, sent } = retryBox([{ ok: false, error: '끊김' }]);
  const r = await c.confirmWithRetry('https://a.b/exec', 9, '3', '2');
  same(sent.length, 4, '첫 시도 + 재시도 3번'); same(waits, [2000, 5000, 10000], '기다린 시간'); same(r.ok, false, '결과');
});
check('3-4 확인 재시도: 서버가 ok:false(다른 반 등)를 주면 다시 보내지 않는다', async () => {
  const { c, waits, sent } = retryBox([{ ok: true, data: { ok: false, msg: '다른 반 호출입니다' } }]);
  await c.confirmWithRetry('https://a.b/exec', 9, '3', '2');
  same(sent.length, 1, '보낸 횟수'); same(waits, [], '기다림 없음');
});
// serverCalls: 배열이면 늘 그 목록, 함수면 (몇 번째 요청) → 응답 Promise
function tickBox(serverCalls) {
  const log = { confirm: [], standby: 0, alert: [], prefs: [], getCalls: 0 };
  const P = { set: o => { log.prefs.push(o); return Promise.resolve(); } };
  // 1.3.4 부하 줄이기 변수는 있으면 싣는다(옛 app.js와 대조할 때는 없다)
  const newVars = ['POLL_MS', 'WEB_POLL_KEY', 'pollBusy'].filter(v => { try { varLine(v); return true; } catch (e) { return false; } });
  const c = sandbox(['tick', 'isConfigured'], {
    vars: newVars,
    optional: ['normalizeClassNo', 'confirmWithRetry', 'nativePrefs', 'pollDelayMs', 'markWebPolling', 'rememberWebAlerted'],
    globals: { window: { Capacitor: { Plugins: { Preferences: P } } } },
    after: 'var current = null, alertedRows = {}, autoDismissSec = 30;'
  });
  vm.runInContext('var CONFIRM_RETRY_MS = [2000, 5000, 10000];', c);
  c.__NOW = new Date(2026, 8, 15, 10, 23).getTime();
  c.SETTINGS = { webAppUrl: 'https://a.b/exec', grade: '3', classNum: '2', mode: 'standby', showStandby: true };
  c.wait = () => Promise.resolve();
  c.api = {
    getCalls: () => { log.getCalls++; return typeof serverCalls === 'function' ? serverCalls(log.getCalls) : Promise.resolve({ ok: true, data: serverCalls }); },
    confirmCall: function () { log.confirm.push([].slice.call(arguments)); return Promise.resolve({ ok: true, data: { ok: true } }); }
  };
  c.showStandby = () => { log.standby++; };
  c.showAlert = p => { log.alert.push(p.call.row); };
  return { c, log };
}
check('3-5 화면 고착: 마감된 호출이 서버 목록에 남아 있어도 대기화면으로 돌아간다', async () => {
  const { c, log } = tickBox([{ row: 5, num: 12, name: '홍길동' }]);
  vm.runInContext('alertedRows[5] = true; current = { row: 5, num: 12, name: "홍길동", deadlineAt: __NOW - 1, totalSec: 30 };', c);
  await c.tick(); await flush();
  ok(vm.runInContext('current', c) === null, '마감된 호출이 current에 남았다');
  same(log.confirm.length >= 1 ? log.confirm[0].slice(1) : null, [5, '3', '2'], '확인에 행·학년·반');
  same(log.standby, 1, '대기화면 복귀 횟수');
  same(log.alert, [], '이미 알린 호출을 다시 띄웠다');
});
check('3-6 새 호출은 띄우고, 목록이 비면 대기화면 (기존 동작 유지)', async () => {
  const a = tickBox([{ row: 8, num: 3, name: '김철수' }]);
  await a.c.tick();
  same(a.log.alert, [8], '새 호출 표시');
  const b = tickBox([]);
  await b.c.tick();
  same(b.log.standby, 1, '빈 목록이면 대기화면');
});
check('3-7 Java 서비스: 폴링이 겹치지 않게 한 번에 하나만 돈다 (글자 검사)', () => {
  ok(/private volatile boolean polling/.test(JSRC), 'volatile polling 표식이 없다');
  ok(/if \(!polling[^\n]*\) \{\s*polling = true;/.test(JSRC), '겹침 방지 분기가 없다');
  ok(/finally \{ polling = false; \}/.test(JSRC), '끝나면 polling을 풀지 않는다');
});

/* ===== 4. 교체 표시 + 이스케이프 ===== */
check('4-1 오늘 줄: 바뀐 교시에 chg·«교체»·«원래 과목», 같은 과목이면 원래 줄 생략', () => {
  const c = renderBox();
  const list = todayList(7);
  list[2] = { period: 3, subject: '체육', changed: true, orig: '과학', teacher: '박교사', origTeacher: '김교사' };
  list[4] = { period: 5, subject: '사회', changed: true, orig: '사회' };
  c.renderPeriodRow(list);
  const row = c.__doc.reg.periodRow;
  const p3 = row.children.find(e => e.id === 'p-3'), p5 = row.children.find(e => e.id === 'p-5'), p1 = row.children.find(e => e.id === 'p-1');
  ok(p3 && /\bchg\b/.test(p3.className), '3교시 chg 클래스: ' + (p3 && p3.className));
  ok(/<span class="chg-tag">교체<\/span>/.test(p3.innerHTML), '«교체» 태그: ' + p3.innerHTML);
  ok(/<div class="po">원래 과학<\/div>/.test(p3.innerHTML), '«원래 과목» 줄: ' + p3.innerHTML);
  ok(/\bchg\b/.test(p5.className) && p5.innerHTML.indexOf('class="po"') < 0, '과목이 같으면 원래 줄 생략: ' + p5.innerHTML);
  ok(!/\bchg\b/.test(p1.className) && p1.innerHTML.indexOf('교체') < 0, '안 바뀐 교시에 표시가 붙었다');
});
check('4-2 오늘 줄: 과목명·원래 과목을 HTML 이스케이프한다', () => {
  const c = renderBox();
  c.renderPeriodRow([{ period: 1, subject: '<img src=x onerror=alert(1)>', changed: true, orig: '국어&"수학"<b>' }]);
  const h = c.__doc.reg.periodRow.innerHTML;
  ok(h.indexOf('<img') < 0 && h.indexOf('&lt;img src=x onerror=alert(1)&gt;') >= 0, '과목명: ' + h);
  ok(h.indexOf('<b>') < 0 && h.indexOf('국어&amp;&quot;수학&quot;&lt;b&gt;') >= 0, '원래 과목: ' + h);
});
check('4-3 오늘 줄: 같은 교시가 여러 줄이면 «과목이 있는 첫 줄»', () => {
  const c = renderBox();
  c.renderPeriodRow([{ period: 1, subject: '국어' }, { period: 1, subject: '수학' }, { period: 2, subject: '' }, { period: 2, subject: '과학' }]);
  const row = c.__doc.reg.periodRow;
  const ps = id => (row.children.find(e => e.id === id).innerHTML.match(/<div class="ps">(.*?)<\/div>/) || [])[1];
  same([ps('p-1'), ps('p-2')], ['국어', '과학'], '1·2교시 과목');
});
check('4-4 오늘 줄: 다시 그려도 칸이 쌓이지 않는다(innerHTML 비움)', () => {
  const c = renderBox();
  c.renderPeriodRow(todayList(7));
  const n1 = c.__doc.reg.periodRow.children.length;
  c.renderPeriodRow(todayList(7));
  same(c.__doc.reg.periodRow.children.length, n1, '칸 수');
  same(n1, 8, '7교시 + 점심');
});
check('4-5 주간표: 교체 칸 chg + title(원래 과목, 이스케이프) + 범례', () => {
  const c = renderBox();
  const w = subjWeek(7);
  w['20260915'][2] = { period: 3, subject: '체육', changed: true, orig: '<과학>' };
  w['20260917'][0] = { period: 1, subject: '미술', changed: true, orig: '국어' };
  c.renderWeek(w);
  const h = c.__doc.reg.weekWrap.innerHTML;
  same((h.match(/<td class="[^"]*\bchg\b[^"]*"/g) || []).length, 2, '교체 칸 수');
  ok(h.indexOf('<td class="today-col chg" title="원래 &lt;과학&gt;">체육</td>') >= 0, '오늘 교체 칸: ' + h);
  ok(h.indexOf('title="원래 국어"') >= 0, '목요일 교체 칸 title');
  ok(/^<div class="week-legend"><span class="sw"><\/span>이번 주 교체 수업<\/div><table/.test(h), '범례가 표 위에 없다');
  c.renderWeek(subjWeek(7));
  ok(c.__doc.reg.weekWrap.innerHTML.indexOf('week-legend') < 0, '교체가 없는데 범례가 남았다');
});
check('4-6 주간표: 과목 이스케이프 + 같은 교시 여러 줄이면 «과목이 있는 첫 줄»', () => {
  const c = renderBox();
  const w = subjWeek(7);
  w['20260914'] = [{ period: 1, subject: '' }, { period: 1, subject: '국어' }, { period: 2, subject: '<script>' }, { period: 2, subject: '영어' }];
  c.renderWeek(w);
  const h = c.__doc.reg.weekWrap.innerHTML;
  const firstRow = h.match(/<td class="pnum">1<\/td><td>(.*?)<\/td>/);
  same(firstRow && firstRow[1], '국어', '월 1교시');
  ok(h.indexOf('<script>') < 0 && h.indexOf('<td>&lt;script&gt;</td>') >= 0, '월 2교시 이스케이프: ' + h.slice(0, 400));
});
check('4-7 급식: 반찬 이름을 이스케이프한다', () => {
  const c = sandbox(['renderMeal']);
  c.renderMeal([{ type: '중식', kcal: '700', dishes: ['<b>제육</b>', '김치&깍두기'], allergy: [] }]);
  const slot = c.__doc.reg.mealList.children[0];
  const mm = slot.children.find(e => e.className === 'mm');
  same(mm.innerHTML, '&lt;b&gt;제육&lt;/b&gt;<br>김치&amp;깍두기', '반찬 줄');
});
check('4-8 style.css: 웹 칠판의 교체 표시 규칙이 옮겨져 있다 (글자 검사)', () => {
  const css = fs.readFileSync(path.join(path.dirname(APP), '..', 'style.css'), 'utf8');
  ['.period.chg {', '.period.chg.now {', '.period .chg-tag {', '.period .po {', '.period.now .po {', 'table.week td.chg {', '.week-legend {', '.week-legend .sw {']
    .forEach(sel => ok(css.indexOf(sel) >= 0, 'CSS 규칙 없음: ' + sel));
});

/* ===== 5. 지금 무슨 시간인지 ===== */
const at = (h, m) => h * 60 + m;
check('5-1 주말이면 «주말»', () => {
  const c = renderBox();
  c.renderPeriodRow(todayList(7)); c.renderWeek(subjWeek(7));
  const sat = new Date(2026, 8, 19, 10, 0);
  same(c.currentPeriodStatus(at(10, 0), sat).label, '주말', '토요일 10시(날짜 전달)');
  c.__NOW = sat.getTime();
  same(c.currentPeriodStatus(at(10, 0)).label, '주말', '토요일 10시(날짜 생략)');
});
check('5-2 이번 주는 있는데 오늘만 비었으면 «오늘은 수업이 없어요»', () => {
  const c = renderBox();
  const w = subjWeek(7); w['20260915'] = [];
  c.renderWeek(w); c.renderPeriodRow([]);
  same(c.currentPeriodStatus(at(10, 0), new Date(2026, 8, 15, 10, 0)).label, '오늘은 수업이 없어요', '재량휴업일');
});
check('5-2b 오늘 조회만 실패(빈 목록)해도 주간표에 오늘 과목이 있으면 수업 있는 날(서버 검수 2026-09-11)', () => {
  const c = renderBox();
  c.renderWeek(subjWeek(6)); c.renderPeriodRow([]);
  same(c.currentPeriodStatus(at(10, 0), new Date(2026, 8, 15, 10, 0)).label, '2교시 수업중', '나이스 오류로 오늘만 빈 목록');
  same(c.currentPeriodStatus(at(15, 12), new Date(2026, 8, 15, 15, 12)).label, '방과후', '마지막 교시는 주간표의 오늘(6교시)');
});
check('5-3 나이스 미연결(이번 주가 통째로 빔)이면 «수업 없음» 판정을 하지 않는다', () => {
  const c = renderBox();
  c.renderWeek({}); c.renderPeriodRow([]);
  same(c.currentPeriodStatus(at(10, 0), new Date(2026, 8, 15, 10, 0)).label, '2교시 수업중', '빈 주');
  const c2 = renderBox();
  c2.renderWeek({ '20260914': [], '20260915': [] }); c2.renderPeriodRow([]);
  same(c2.currentPeriodStatus(at(10, 0), new Date(2026, 8, 15, 10, 0)).label, '2교시 수업중', '날짜 칸만 있고 과목이 없는 주');
});
check('5-4 오늘 시간표의 마지막 교시까지만 (6교시 날 15:10은 방과후)', () => {
  const c = renderBox();
  c.renderWeek(subjWeek(7)); c.renderPeriodRow(todayList(6));
  const tue = new Date(2026, 8, 15, 15, 10);
  same(c.currentPeriodStatus(at(14, 10), tue).label, '6교시 수업중', '14:10');
  same(c.currentPeriodStatus(at(15, 10), tue).label, '방과후', '15:10');
  same(c.__doc.reg.periodRow.children.length, 7, '오늘 줄도 6교시 + 점심');
});
check('5-5 통신 실패(null)는 «받았는데 빔»([])과 구분한다', () => {
  const c = renderBox();
  c.renderWeek(subjWeek(7)); c.renderPeriodRow(null);
  same(c.currentPeriodStatus(at(10, 0), new Date(2026, 8, 15, 10, 0)).label, '2교시 수업중', '못 받은 날을 수업 없음으로 보지 않는다');
  ok(c.__doc.reg.periodRow.innerHTML.indexOf('불러오지 못했') >= 0, '못 받음 안내: ' + c.__doc.reg.periodRow.innerHTML);
});
check('5-6 refreshMeal: 한 번도 못 받은 시간표는 null로, 받은 뒤 실패하면 직전값 유지(last-good)', async () => {
  const seen = [];
  let fail = true;
  const c = sandbox(['refreshMeal', 'isConfigured'], { vars: ['POLL_MS', 'pollTimer', 'lastMeal'], optional: ['normalizeClassNo'] });
  c.SETTINGS = { webAppUrl: 'https://a.b/exec', grade: '3', classNum: '2' };
  c.setTimeout = () => 1; c.clearTimeout = () => {};
  const T = todayList(7), W = subjWeek(7);
  c.api = {
    getMeal: () => Promise.resolve(fail ? { ok: false } : { ok: true, data: [] }),
    getTimetable: (u, g, cn, scope) => Promise.resolve(fail ? { ok: false, error: '끊김' } : { ok: true, data: scope === 'week' ? W : T })
  };
  c.onBoardData = d => seen.push(d);
  await c.refreshMeal();
  same([seen[0].todayTimetable, seen[0].weekTimetable], [null, null], '처음 실패');
  fail = false; await c.refreshMeal();
  same(seen[1].todayTimetable.length, 7, '성공');
  fail = true; await c.refreshMeal();
  same(seen[2].todayTimetable.length, 7, '다시 실패해도 직전값');
});

/* ===== 6. periodConfig ===== */
const DEF = { start: '08:50', periodLen: 45, breakLen: 10, lunchAfter: 4, lunchLen: 50, maxPeriod: 7 };
check('6-1 validPeriodConfig: 모양이 맞는 시정만 받는다', () => {
  const c = renderBox();
  same(c.validPeriodConfig(DEF), DEF, '기본 시정');
  same(c.validPeriodConfig(Object.assign({}, DEF, { start: '8:50' })).start, '08:50', 'H:MM도 받아 HH:MM으로');
  same(c.validPeriodConfig(Object.assign({}, DEF, { lunchAfter: 0 })).lunchAfter, 0, '점심 없음(0)');
  same(c.validPeriodConfig(Object.assign({}, DEF, { maxPeriod: '6' })).maxPeriod, 6, '숫자 글자');
  [null, 'x', {}, Object.assign({}, DEF, { start: '8시' }), Object.assign({}, DEF, { start: '25:00' }), Object.assign({}, DEF, { maxPeriod: 0 }),
    Object.assign({}, DEF, { maxPeriod: 11 }), Object.assign({}, DEF, { maxPeriod: 'abc' }), Object.assign({}, DEF, { periodLen: '' }),
    Object.assign({}, DEF, { breakLen: -1 }), Object.assign({}, DEF, { lunchLen: 2.5 })]
    .forEach((bad, i) => same(c.validPeriodConfig(bad), null, '틀린 시정 #' + i + ' ' + JSON.stringify(bad)));
});
check('6-2 applyPeriodConfig: 바뀌면 시정 재계산 + 오늘·주간표 다시 그림 + yc_period_config 저장', () => {
  const c = renderBox();
  c.renderPeriodRow(todayList(7)); c.renderWeek(subjWeek(7));
  same(c.__doc.reg.periodRow.children.length, 8, '처음 7교시 + 점심');
  c.applyPeriodConfig(Object.assign({}, DEF, { maxPeriod: 6 }));
  same(c.SCHEDULE.filter(s => s.type === 'period').length, 6, 'SCHEDULE 재계산');
  same(c.__doc.reg.periodRow.children.length, 7, '오늘 줄 다시 그림');
  same((c.__doc.reg.weekWrap.innerHTML.match(/<td class="pnum">/g) || []).length, 6, '주간표 다시 그림');
  same(JSON.parse(c.localStorage.getItem('yc_period_config')).maxPeriod, 6, '마지막 정상값 저장');
  c.applyPeriodConfig({ start: 'abc', maxPeriod: 3 });
  same(c.PERIOD_CONFIG.maxPeriod, 6, '틀린 시정은 무시');
  same(JSON.parse(c.localStorage.getItem('yc_period_config')).maxPeriod, 6, '틀린 시정을 저장하지 않는다');
});
check('6-3 시작 시 저장해 둔 시정을 먼저 읽는다', () => {
  const stored = Object.assign({}, DEF, { start: '09:00', maxPeriod: 6 });
  const c = sandbox(['loadStoredPeriodConfig', 'validPeriodConfig'], { vars: ['PERIOD_CONFIG'], storage: { yc_period_config: JSON.stringify(stored) } });
  c.loadStoredPeriodConfig();
  same([c.PERIOD_CONFIG.start, c.PERIOD_CONFIG.maxPeriod], ['09:00', 6], '저장값 반영');
  const c2 = sandbox(['loadStoredPeriodConfig', 'validPeriodConfig'], { vars: ['PERIOD_CONFIG'], storage: { yc_period_config: '{깨짐' } });
  c2.loadStoredPeriodConfig();
  same(c2.PERIOD_CONFIG, DEF, '깨진 저장값이면 기본 시정');
});
check('6-4 onBoardData: 받은 periodConfig를 applyPeriodConfig로 넘긴다', () => {
  ok(/function onBoardData[\s\S]*?applyPeriodConfig\(data\.board\.periodConfig\)/.test(SRC), 'onBoardData가 applyPeriodConfig를 쓰지 않는다');
  ok(!/PERIOD_CONFIG = data\.board\.periodConfig/.test(SRC), '형태 검사 없이 그대로 넣는 줄이 남았다');
});
check('6-5 startPolling: board(시정)를 받은 뒤에 급식·시간표를 부른다 / board가 터져도 폴링은 시작', async () => {
  const order = [];
  let resolveBoard, rejectBoard;
  const c = sandbox(['startPolling'], { vars: ['POLL_MS', 'pollTimer'] });
  c.setInterval = () => 1; c.clearInterval = () => {};
  c.refreshMeal = () => { order.push('meal'); };
  c.tick = () => { order.push('tick'); };
  c.refreshBoard = () => { order.push('board'); return new Promise((res, rej) => { resolveBoard = res; rejectBoard = rej; }); };
  c.startPolling();
  same(order, ['board'], 'board 응답 전');
  resolveBoard(); await flush();
  ok(order.indexOf('meal') > 0 && order.indexOf('tick') > 0, 'board 뒤에 meal·tick: ' + order.join(','));
  order.length = 0;
  c.startPolling(); rejectBoard(new Error('터짐')); await flush();
  ok(order.indexOf('meal') >= 0 && order.indexOf('tick') >= 0, 'board가 터지면 폴링이 멈춘다: ' + order.join(','));
});

/* ===== 7·8. 기타 ===== */
if (FLAVOR === 'android') {
  check('8-1 build-release.sh 표식이 이번 app.js에 들어 있다(아무것도 안 재는 표식 방지)', () => {
    const sh = fs.readFileSync(path.join(ROOT, 'build-release.sh'), 'utf8');
    const m = sh.match(/MARKER="\$\{YC_MARKER:-([^}]*)\}"/);
    ok(m, 'MARKER 줄을 못 찾음');
    ok(m[1] === 'function cleanWebAppUrl', '표식이 이번 수정 문자열이 아니다: ' + m[1]);
    ok(SRC.indexOf(m[1]) >= 0, 'app.js에 표식 없음: ' + m[1]);
  });
}
if (FLAVOR === 'compat') {
  check('C-1 호환판 전용 코드가 살아 있다(기본판 www로 덮어쓰기 방지)', () => {
    ['syncNativeSettings', 'showLastPollBadge', 'showSourceReport', 'showNativeSyncWarning'].forEach(n => ok(has(n), '호환판 전용 함수가 사라짐: ' + n));
    ok(/syncNativeSettings\(JSON\.stringify\(SETTINGS\)/.test(SRC), '켤 때 네이티브 저장소 재동기화가 사라짐');
    ['loadAlerted', 'saveAlerted', 'SourceSwitcher.tryReturnToAndroid'].forEach(n => ok(JSRC.indexOf(n) >= 0, 'Java 전용 코드가 사라짐: ' + n));
  });
  check('C-2 README가 «www는 기본판과 동일»이라고 하지 않는다', () => {
    const md = fs.readFileSync(path.join(ROOT, 'README.md'), 'utf8');
    ok(md.indexOf('기본판과 **내용이 동일**') < 0, 'README 171행 문구가 그대로다');
  });

  /* 2026-09-14 제보 — 기본판에서 옮긴 칠판이 «다른 앱 위에 표시 권한이 없어졌다»·«설정 안 한 소리가 더 난다».
     기본판의 동작(겹쳐 띄우기 안내가 먼저·호출 채널 무음·서비스는 소리 안 냄)을 기본값으로 되살렸는지 잰다. */
  const MAIN_SRC = fs.readFileSync(path.join(path.dirname(JAVA), 'MainActivity.java'), 'utf8').replace(/\r\n/g, '\n');
  check('C-3 첫 실행 안내는 «다른 앱 위에 표시»를 먼저 묻는다(화면 꺼짐 안내에 막히지 않게)', () => {
    ok(/if \(!askOverlayPermissionIfNeeded\(\)\)\s*if \(!askScreenTimeoutIfNeeded\(\)\)\s*askBatteryExemptionIfNeeded\(\);/.test(MAIN_SRC), '안내 순서가 겹쳐 띄우기 → 화면 꺼짐 → 절전 제외가 아니다');
    ok(MAIN_SRC.indexOf('켜지 않아도 호출 알림은 화면 위쪽에 표시') < 0, '«알림은 뜬다»는 옛 문구가 남아 있다');
    ok(MAIN_SRC.indexOf('「유콜 보드 (호환)」') >= 0, '안내창에 이 앱 이름이 없다');
  });
  check('C-4 서비스는 앱이 앞에 없다고 곧바로 알람을 울리지 않는다 — 기다렸다가 화면도 웹 소리도 없을 때만', () => {
    ok(!/if \(!MainActivity\.inForeground\) playAlarmOnce\(\);/.test(JSRC), '곧바로 울리던 1.3.1 코드가 남아 있다');
    const at = JSRC.indexOf('private void bringAppToFront(');
    ok(at >= 0, 'bringAppToFront가 없다');
    const body = JSRC.slice(at, JSRC.indexOf('\n    }\n', at));
    const d = body.indexOf('postDelayed(new Runnable()', body.indexOf('FALLBACK') >= 0 ? 0 : 0);
    const alarm = body.lastIndexOf('playAlarmOnce()');
    ok(alarm > 0, '대체 알람(playAlarmOnce) 자체가 사라졌다 — HDMI 칠판 대응은 남겨야 한다');
    const tail = body.slice(body.lastIndexOf('postDelayed(', alarm), alarm);
    ok(/FALLBACK_CHECK_MS/.test(body.slice(alarm)), '알람이 기다린 뒤(FALLBACK_CHECK_MS)에 울리지 않는다');
    ok(/MainActivity\.inForeground/.test(tail) && /webSounded\(row/.test(tail), '울리기 전에 화면·웹 소리를 확인하지 않는다');
    ok(d >= 0, '지연 실행이 없다');
    ok(/bringAppToFront\(row, /.test(JSRC), '호출 행 번호를 넘기지 않는다');
  });
  check('C-5 호출 알림 채널은 무음 새 채널 · 알람음이 박힌 옛 채널은 지운다', () => {
    const m = JSRC.match(/String CH_CALL = "([^"]+)"/);
    ok(m && m[1] !== 'youcall_call', '채널 id가 옛것 그대로다(이미 깔린 칠판은 소리가 안 바뀐다): ' + (m && m[1]));
    ok(/deleteNotificationChannel\(CH_CALL_OLD\)/.test(JSRC) && /CH_CALL_OLD = "youcall_call"/.test(JSRC), '옛 채널을 지우지 않는다');
    ok(/call\.setSound\(null, null\)/.test(JSRC), '호출 채널이 무음이 아니다');
    ok(!/call\.setSound\(alarm/.test(JSRC), '호출 채널에 알람음이 남아 있다');
  });
  check('C-6 웹은 소리를 낼 수 있을 때만 «소리 냈음»을 적고, 서비스와 같은 키·형식을 쓴다', () => {
    const sets = [];
    const P = { set(o) { sets.push(o); return Promise.resolve(); } };
    const c = sandbox(['markSoundedForNative'], { globals: { window: { Capacitor: { Plugins: { Preferences: P } } }, audioCtx: { state: 'running' } } });
    vm.runInContext('__NOW = 1789000000000', c);
    ok(c.markSoundedForNative(12) === true && sets.length === 1, '소리가 나는 상태인데 안 적었다');
    same(sets[0], { key: 'yc_sounded', value: '12:1789000000000' }, '적은 값');
    c.audioCtx = { state: 'suspended' };
    ok(c.markSoundedForNative(13) === false && sets.length === 1, '소리가 막힌 상태(suspended)인데 적었다 — 서비스가 대신 울리지 못한다');
    c.audioCtx = null;
    ok(c.markSoundedForNative(14) === false && sets.length === 1, 'AudioContext가 없는데 적었다');
    c.audioCtx = { state: 'running' };
    ok(c.markSoundedForNative(null) === false && sets.length === 1, '행 번호 없이 적었다');
    c.window = {};
    ok(c.markSoundedForNative(15) === false, '앱 밖(Capacitor 없음)에서 터지거나 적었다');
    ok(/KEY_SOUNDED = "yc_sounded"/.test(JSRC) && /indexOf\(':'\)/.test(JSRC), '서비스가 같은 키·«행:시각» 형식을 읽지 않는다');
    ok(/playAlertNTimes\([^\n]*call\.row\)/.test(fn('showAlert')), '호출 화면이 행 번호를 소리 재생에 넘기지 않는다');
    ok(/if \(i === 0\) markSoundedForNative\(row\)/.test(fn('playAlertNTimes')), '첫 호출음 뒤에 표시를 적지 않는다');
  });

  /* 2026-09-14 «설정 메뉴가 없다» 제보 — 제조사가 설정 화면을 뺀 칠판에서 startActivity가 ActivityNotFoundException을 던지면
     잡지 않은 자리에서 앱이 꺼진다. Android 15(targetSdk 35+) 포그라운드 서비스 제약 두 가지도 같은 «꺼짐» 갈래다. */
  check('C-7 칠판에 없는 화면·Android 15 제약으로 앱이 꺼지지 않는다', () => {
    const lines = MAIN_SRC.split('\n');
    lines.forEach((l, i) => {
      if (l.indexOf('startActivity(') < 0 || /private boolean openFirstAvailable/.test(l)) return;
      const back = lines.slice(Math.max(0, i - 3), i + 1).join('\n');
      ok(/try \{[^\n]*$|try \{ startActivity\(|try \{\s*\n/.test(back) && !/\} catch \(Exception ignored\) \{\s*\n\s*startActivity\(/.test(back),
        'MainActivity.java ' + (i + 1) + '줄 startActivity가 try 밖이다: ' + l.trim());
    });
    ok(/private boolean openFirstAvailable\(Intent\.\.\. intents\)/.test(MAIN_SRC) && /try \{ startActivity\(i\); return true; \} catch \(Exception ignored\)/.test(MAIN_SRC), '설정 화면을 차례로 열어 보는 도우미가 없다');
    ok(/if \(!opened\)/.test(MAIN_SRC) && MAIN_SRC.indexOf('이 칠판에서는 설정 화면을 열 수 없습니다') >= 0, '설정 화면이 없을 때 안내가 없다');
    const BOOT = fs.readFileSync(path.join(path.dirname(JAVA), 'BootReceiver.java'), 'utf8');
    ok(/try \{ YouCallService\.start\(context\); \}\s*catch \(Exception/.test(BOOT), 'BootReceiver가 서비스 시작 예외를 잡지 않는다(Android 15 부팅 때 꺼짐)');
    ok(/public void onTimeout\(int startId, int fgsType\)[\s\S]{0,300}stopSelf\(\)/.test(JSRC), 'onTimeout에서 stopSelf를 하지 않는다(Android 15 dataSync 6시간 뒤 꺼짐)');
    // 검수(2026-09-14): Android 15 부팅 금지·12+ 뒤 재시작 거절은 BootReceiver가 아니라 서비스의 startForeground에서 던진다
    const jl = JSRC.split('\n');
    jl.forEach((l, i) => {
      if (!/\bstartForeground\(/.test(l) || /startForegroundService/.test(l) || /^\s*(\/\/|\*)/.test(l)) return;
      ok(/try \{\s*$/.test(jl[i - 1] || '') || /try \{ startForeground\(/.test(l), 'YouCallService.java ' + (i + 1) + '줄 startForeground가 try 밖이다: ' + l.trim());
    });
    ok(/포그라운드 서비스 시작 거절[\s\S]{0,120}stopSelf\(\);\s*return;/.test(JSRC), 'startForeground가 거절되면 멈추고 빠져나가야 한다');
    ok(/public void onResume\(\)[\s\S]{0,600}try \{ YouCallService\.start\(this\); \} catch \(Exception ignored\)/.test(MAIN_SRC), '앱을 앞으로 가져와도 멈춘 서비스를 다시 세우지 않는다(singleTask라 onCreate가 안 불림)');
    ok(/try \{ nm\.notify\(NOTI_CALL, b\.build\(\)\); \}/.test(JSRC), '호출 알림 notify가 try 밖이다 — 예외 나면 «이미 알림» 기록만 남고 화면·알람을 건너뛴다');
  });

  /* 2026-09-14 18개 반 학교의 «트래픽 오류» — 칠판 한 대가 서비스(2초)·화면(3초) 두 갈래로 서버에 물어 구글 한도에 걸렸다.
     1.3.4: 앱이 앞에서 화면이 묻는 동안 서비스는 쉬고 / 앞 요청이 안 끝나면 겹쳐 보내지 않고 / 실패가 이어지면 물러나고 / 박자를 흩뜨린다.
     «호출이 뜨는 속도는 그대로»와 «쉬다가 호출을 놓치지 않는다»를 함께 잰다. */
  let JAVA_DELAYS = null;
  check('L-1 PollGate 규칙(서비스가 쉬는 조건·물러나는 간격·이미 띄운 호출) — javac로 실제 실행', () => {
    const { spawnSync } = require('child_process');
    const os = require('os');
    const out = fs.mkdtempSync(path.join(os.tmpdir(), 'yc-pollgate-'));
    try {
      const gate = path.join(path.dirname(JAVA), 'PollGate.java');
      const test = path.join(ROOT, 'tests', 'java', 'PollGateTest.java');
      ok(fs.existsSync(gate), 'PollGate.java가 없다: ' + gate);
      const jc = spawnSync('javac', ['-encoding', 'UTF-8', '-d', out, gate, test], { encoding: 'utf8' });
      ok(!jc.error, '자바 컴파일러(javac)를 못 찾아 못 쟀다: ' + (jc.error && jc.error.message));
      ok(jc.status === 0, 'javac 실패:\n' + jc.stderr);
      const run = spawnSync('java', ['-Dstdout.encoding=UTF-8', '-cp', out, 'com.kimju.youcallboard.PollGateTest'], { encoding: 'utf8' });
      ok(run.status === 0, 'PollGate 검사 실패:\n' + run.stdout + run.stderr);
      const tbl = spawnSync('java', ['-cp', out, 'com.kimju.youcallboard.PollGateTest', 'delays'], { encoding: 'utf8' });
      JAVA_DELAYS = JSON.parse(tbl.stdout.trim());
    } finally {
      fs.rmSync(out, { recursive: true, force: true });
    }
  });
  check('L-2 화면: 앞 요청이 안 끝나면 겹쳐 보내지 않고, 쉬는 차례에도 «묻는 중»을 적는다', async () => {
    let release = null;
    const { c, log } = tickBox(() => new Promise(r => { release = r; }));
    const T0 = 1789000000000;
    // 응답이 안 온 요청을 await하면 검사가 거기서 멈춘다 — tick은 부르기만 하고 flush로 동기 부분만 돌린다
    c.__NOW = T0; c.tick(); await flush();
    c.__NOW = T0 + 3000; c.tick(); await flush();
    c.__NOW = T0 + 6000; c.tick(); await flush();
    same(log.getCalls, 1, '응답 전에 보낸 요청 수');
    const polls = log.prefs.filter(o => o.key === 'yc_web_poll');
    same(polls.length, 3, '«묻는 중» 표시 횟수(요청을 쉰 차례 포함)');
    same(polls[2].value, String(T0 + 6000), '표시 값은 지금 시각');
    release({ ok: true, data: [] }); await flush(); await flush();
    c.__NOW = T0 + 9000; c.tick(); await flush();
    same(log.getCalls, 2, '응답이 온 뒤 다음 차례에는 다시 묻는다');
    release({ ok: true, data: [] }); await flush();
  });
  check('L-3 화면: 실패가 이어지면 3→6→12→15초로 물러나고 성공하면 3초로 — 서비스(PollGate)와 같은 간격표', async () => {
    let fail = true;
    const { c, log } = tickBox(() => Promise.resolve(fail ? { ok: false, error: 'HTTP 500' } : { ok: true, data: [] }));
    const T0 = 1789000000000, sentAt = [];
    for (let t = 0; t <= 60000; t += 3000) {
      c.__NOW = T0 + t;
      const before = log.getCalls;
      await c.tick(); await flush();
      if (log.getCalls > before) sentAt.push(t);
    }
    same(sentAt, [0, 6000, 18000, 33000, 48000], '실패가 이어질 때 보낸 시각(3초 박자 위)');
    fail = false;
    c.__NOW = T0 + 63000; await c.tick(); await flush();
    const b = log.getCalls;
    c.__NOW = T0 + 66000; await c.tick(); await flush();
    same(log.getCalls - b, 1, '성공한 뒤에는 바로 다음 3초 차례에 묻는다');
    ok(JAVA_DELAYS, 'L-1이 자바 간격표를 못 만들어 대조하지 못했다');
    const F = [0, 1, 2, 3, 4, 5, 6, 7, 8];
    same(F.map(f => c.pollDelayMs(3000, f)), JAVA_DELAYS['3000'], 'app.js pollDelayMs(3초) = PollGate.nextDelayMs');
    same(F.map(f => c.pollDelayMs(2000, f)), JAVA_DELAYS['2000'], 'app.js pollDelayMs(2초) = PollGate.nextDelayMs');
  });
  check('L-4 화면이 띄운 호출을 «행:시각»으로 서비스에 알린다 (최근 30개·같은 행은 한 번)', async () => {
    const { c, log } = tickBox([{ row: 41, num: 3, name: '가상학생' }]);
    c.__NOW = 1789000000000;
    await c.tick(); await flush();
    same(log.alert, [41], '호출 표시');
    const w = () => log.prefs.filter(o => o.key === 'yc_web_alerted');
    same(w().length, 1, '알린 횟수');
    same(w()[0].value, '41:1789000000000', '적은 값');
    await c.tick(); await flush();
    same(w().length, 1, '이미 띄운 호출을 다시 알리지 않는다');
    for (let r = 100; r < 140; r++) c.rememberWebAlerted(r);
    let parts = w().pop().value.split(',');
    same(parts.length, 30, '최근 30개만');
    same(parts[29].split(':')[0], '139', '맨 끝이 가장 최근');
    c.rememberWebAlerted(120);
    parts = w().pop().value.split(',');
    same(parts.filter(x => x.indexOf('120:') === 0).length, 1, '같은 행은 한 번만');
    same(parts[29].split(':')[0], '120', '다시 띄운 행은 맨 끝으로');
  });
  check('L-5 서비스: 앞에서 화면이 묻는 동안 쉬고·실패하면 물러나고·앞에서 이미 띄운 호출로 칠판을 다시 끌어오지 않는다 (글자 검사)', () => {
    const GATE = fs.readFileSync(path.join(path.dirname(JAVA), 'PollGate.java'), 'utf8');
    ok(/if \(!polling && !yieldToWeb\(\) && android\.os\.SystemClock\.elapsedRealtime\(\) \+ 300 >= nextPollAt\) \{\s*polling = true;/.test(JSRC), '서비스가 쉬는 분기·물러난 차례 흘려보내기가 없다');
    ok(/PollGate\.yieldToWeb\(MainActivity\.inForeground, sp\.getString\(KEY_WEB_POLL, null\), now\)/.test(JSRC), '쉬는 조건이 «앱이 앞에 있음 + 화면 표시»가 아니다');
    // 검수(1.3.4): 박자 자체를 늘리면 쉬는 동안 늘어난 간격이 남아 뒤로 간 뒤 첫 확인이 15초 늦었다 — 박자는 늘 2초
    ok(/private final Runnable pollTask[\s\S]*?handler\.postDelayed\(this, POLL_MS\);/.test(JSRC) && !/postDelayed\(this, PollGate\.nextDelayMs/.test(JSRC), '박자가 2초 그대로가 아니다');
    // 검수(1.3.4): 보낸 때부터 재면 8초 시간 초과 뒤 곧바로 다시 보내 물러나기가 헛돈다 — 실패가 «끝난» 때부터, 벽시계가 아닌 부팅 뒤 흐른 시간으로
    ok(/private void markFailed\(\) \{\s*failStreak\+\+;\s*nextPollAt = android\.os\.SystemClock\.elapsedRealtime\(\) \+ PollGate\.nextDelayMs\(POLL_MS, failStreak\);/.test(JSRC), '물러날 때를 실패가 끝난 때부터·부팅 뒤 흐른 시간으로 정하지 않는다');
    ok(/if \(body == null\) \{ markFailed\(\);/.test(JSRC), '연결 실패를 세지 않는다');
    ok(/new JSONArray\(body\);\s*failStreak = 0; nextPollAt = 0L;/.test(JSRC), '제대로 답하면 간격을 되돌리지 않는다');
    ok(/catch \(Exception e\) \{\s*markFailed\(\);/.test(JSRC), '깨진 응답(로그인 화면 등)을 실패로 세지 않는다');
    ok(/if \(!PollGate\.yieldToWeb\([^\n]*\)\) return false;\s*failStreak = 0; nextPollAt = 0L;/.test(JSRC), '쉬는 동안 옛 실패 수가 남아 뒤로 간 뒤 첫 실패에 곧바로 15초 물러난다');
    // 검수(1.3.4): 쉬는 동안 화면이 띄운 호출을 서비스 기록으로 옮겨야 앱이 죽었다 살아나도 칠판을 다시 끌어오지 않는다
    ok(/PollGate\.webAlertedEntries\(wa, now, ALERTED_TTL_MS\)/.test(JSRC) && /if \(added\) saveAlerted\(sp\);/.test(JSRC) && /mergedWebAlerted = wa;/.test(JSRC), '쉬는 동안 화면이 띄운 호출을 서비스 기록(yc_alerted_rows)으로 옮기지 않는다');
    ok(/now - lastYieldMark >= YIELD_MARK_MS/.test(JSRC), '쉬는 표시를 2초마다 저장소에 다시 쓴다');
    ok(/PollGate\.shownWhileForeground\(sp\.getString\(KEY_WEB_ALERTED, null\), row,\s*MainActivity\.inForeground, MainActivity\.leftForegroundAt/.test(JSRC), '화면이 앞에서 띄운 호출을 가르지 않는다');
    const loop = JSRC.slice(JSRC.indexOf('PollGate.shownWhileForeground('), JSRC.indexOf('bringAppToFront(row,'));
    ok(/saveAlerted\(sp\);[^\n]*\n\s*if \(shown\) continue;/.test(loop), '이미 띄운 호출도 기록은 남기고, 칠판을 끌어오기 전에 건너뛰어야 한다');
    ok(/onPause\(\) \{ leftForegroundAt = System\.currentTimeMillis\(\); inForeground = false;/.test(MAIN_SRC), '뒤로 간 시각을 inForeground보다 먼저 적지 않는다');
    [['KEY_WEB_POLL', 'WEB_POLL_KEY', 'yc_web_poll'], ['KEY_WEB_ALERTED', 'WEB_ALERTED_KEY', 'yc_web_alerted'], ['KEY_SVC_YIELD', 'SVC_YIELD_KEY', 'yc_svc_yield']].forEach(([j, w, v]) => {
      ok(new RegExp(j + ' = "' + v + '"').test(JSRC), 'Java ' + j + '가 ' + v + '가 아니다');
      ok(new RegExp(w + " = '" + v + "'").test(SRC), 'app.js ' + w + '가 ' + v + '가 아니다');
    });
    ok(/MAX_DELAY_MS = 15000L/.test(GATE) && /POLL_MAX_MS = 15000/.test(SRC), '물러나는 상한이 양쪽에서 15초가 아니다');
    ok(/WEB_ALIVE_MS = 7000L/.test(GATE) && /var POLL_MS = 3000/.test(SRC), '화면 박자(3초)보다 넉넉한 생존 판정(7초)이 아니다');
  });
  check('L-6 급식·시간표 셋을 한꺼번에 보내지 않고 차례로 (켤 때·30분마다 몰림 줄이기)', async () => {
    const pending = [];
    const c = sandbox(['refreshMeal', 'isConfigured'], { vars: ['POLL_MS', 'pollTimer', 'lastMeal'], optional: ['normalizeClassNo'] });
    c.SETTINGS = { webAppUrl: 'https://a.b/exec', grade: '3', classNum: '2' };
    c.setTimeout = () => 1; c.clearTimeout = () => {};
    const req = name => new Promise(r => pending.push({ name, r }));
    c.api = { getMeal: () => req('meal'), getTimetable: (u, g, cn, scope) => req(scope) };
    const seen = [];
    c.onBoardData = d => seen.push(d);
    const done = c.refreshMeal();
    await flush();
    same(pending.map(x => x.name), ['meal'], '첫 응답 전에 보낸 요청');
    pending[0].r({ ok: true, data: [] }); await flush(); await flush();
    same(pending.map(x => x.name), ['meal', 'today'], '두 번째 요청');
    pending[1].r({ ok: true, data: todayList(7) }); await flush(); await flush();
    same(pending.map(x => x.name), ['meal', 'today', 'week'], '세 번째 요청');
    pending[2].r({ ok: true, data: subjWeek(7) }); await done;
    same([seen.length, seen[0].todayTimetable.length], [1, 7], '셋 다 받은 뒤 한 번 그린다');
  });
  check('L-7 켤 때 3초 박자·3분·30분 주기를 칠판마다 흩뜨린다 / 첫 확인은 곧바로', async () => {
    function run(r) {
      const intervals = [], timeouts = [], order = [];
      const c = sandbox(['startPolling'], { vars: ['POLL_MS', 'pollTimer'] });
      vm.runInContext('Math.random = function () { return ' + r + '; };', c);
      c.setInterval = (f, ms) => { intervals.push(ms); return 1; }; c.clearInterval = () => {};
      c.setTimeout = (f, ms) => { timeouts.push({ f, ms }); return 2; }; c.clearTimeout = () => {};
      c.refreshMeal = () => order.push('meal'); c.tick = () => order.push('tick');
      c.refreshBoard = () => Promise.resolve();
      c.startPolling();
      return { c, intervals, timeouts, order };
    }
    const hi = run(0.999); await flush();
    ok(hi.order.indexOf('tick') >= 0, '첫 확인을 곧바로 하지 않는다');
    same(hi.timeouts.length, 1, '3초 박자 시작 예약');
    ok(hi.timeouts[0].ms >= 2990 && hi.timeouts[0].ms < 3000, '3초 박자 시작을 0~3초 흩뜨리지 않는다: ' + hi.timeouts[0].ms);
    ok(hi.intervals.indexOf(3000) < 0, '흩뜨리기 전에 3초 박자가 이미 돈다');
    const ticksBefore = hi.order.filter(x => x === 'tick').length;
    hi.timeouts[0].f();
    ok(hi.intervals.indexOf(3000) >= 0, '흩뜨린 뒤 3초 박자로 돌지 않는다');
    same(hi.order.filter(x => x === 'tick').length - ticksBefore, 1, '박자를 여는 순간에도 한 번 묻는다(첫 확인과 두 번째 사이가 3초를 넘지 않게)');
    ok(hi.intervals.some(ms => ms > 180000 && ms < 200000), '3분 주기를 흩뜨리지 않는다: ' + hi.intervals.join(','));
    ok(hi.intervals.some(ms => ms > 1800000 && ms < 1920000), '30분 주기를 흩뜨리지 않는다: ' + hi.intervals.join(','));
    const lo = run(0); await flush();
    same(lo.timeouts[0].ms, 0, '난수 0이면 곧바로');
    ok(lo.intervals.indexOf(180000) >= 0 && lo.intervals.indexOf(1800000) >= 0, '난수 0이면 원래 주기: ' + lo.intervals.join(','));
    ok(/if \(pollStartTimer\) \{ clearTimeout\(pollStartTimer\); pollStartTimer = null; \}/.test(fn('startPolling')), '다시 시작할 때 예약된 3초 박자 시작을 지우지 않는다(박자가 둘로 겹친다)');
  });
  check('L-8 「뒤 감시」: 서비스가 화면에 맡기고 쉬는 중이면 붉게 경고하지 않고, 쉬는 표시가 끊기면 예전처럼 마지막 시각', async () => {
    const vals = {};
    const P = { get: o => Promise.resolve({ value: vals[o.key] == null ? null : vals[o.key] }) };
    const reg = {};
    const head = { insertBefore: el => { reg[el.id] = el; } };
    const c = sandbox(['showLastPollBadge'], {
      vars: ['WEB_POLL_KEY'],
      globals: { window: { Capacitor: { Plugins: { Preferences: P } } }, setInterval: () => 1, showSourceReport: () => {} }
    });
    c.document = {
      querySelector: s => (s === '.hright' ? head : null),
      getElementById: id => reg[id] || null,
      createElement: t => { const e = new El(t); e.addEventListener = () => {}; return e; }
    };
    const NOW = 1789000000000;
    c.__NOW = NOW;
    const show = async () => { c.showLastPollBadge(); await flush(); await flush(); return reg.pollBadge; };
    vals.yc_last_poll = String(NOW - 600000); vals.yc_svc_yield = String(NOW - 2000);
    let el = await show();
    same([el.textContent, el.style.color], ['뒤 감시: 쉬는 중 · 마지막 10분 전', ''], '서비스가 쉬는 중 — 뒤에서 마지막으로 다녀온 시각도 함께(진단)');
    vals.yc_svc_yield = String(NOW - 15000);
    el = await show();
    same([el.textContent, el.style.color], ['뒤 감시: 쉬는 중 · 마지막 10분 전', ''], '쉬는 표시는 8초마다 적히니 15초 전이어도 쉬는 중');
    vals.yc_svc_yield = String(NOW - 60000);
    el = await show();
    same([el.textContent, el.style.color], ['뒤 감시: 10분 전', '#b03030'], '쉬는 표시도 끊긴 서비스는 마지막 시각·붉게');
    delete vals.yc_svc_yield; vals.yc_last_poll = String(NOW - 5000);
    el = await show();
    same([el.textContent, el.style.color], ['뒤 감시: 5초 전', ''], '평소(앱이 뒤에 있어 서비스가 묻는 중)');
  });
  check('L-9 화면: 시계가 뒤로 가도 멈추지 않고 / 목록이 아닌 답도 실패로 세고 / 상주형에서 마감 뒤 대기 중인 다음 호출을 놓치지 않는다', async () => {
    const T0 = 1789000000000;
    // (1) 물러난 중에 시계가 1시간 뒤로 — 예전 값대로면 1시간 동안 안 묻는데 «묻는 중»은 적혀 서비스도 쉰다
    const a = tickBox(() => Promise.resolve({ ok: false, error: 'HTTP 500' }));
    a.c.__NOW = T0; await a.c.tick(); await flush();
    a.c.__NOW = T0 - 3600000;
    const a1 = a.log.getCalls;
    await a.c.tick(); await flush();
    same(a.log.getCalls - a1, 1, '시계가 1시간 뒤로 가도 곧바로 다시 묻는다');
    // (2) 200인데 목록(배열)이 아닌 답 — 서비스는 JSONArray로 못 읽어 실패로 센다. 화면도 같게.
    const b = tickBox(() => Promise.resolve({ ok: true, data: { ok: false, msg: '알 수 없는 api' } }));
    b.c.__NOW = T0; await b.c.tick(); await flush();
    const b1 = b.log.getCalls;
    b.c.__NOW = T0 + 3000; await b.c.tick(); await flush();
    same(b.log.getCalls - b1, 0, '목록이 아닌 답 뒤에는 물러난다');
    same(b.log.standby, 1, '목록이 아닌 답이면 대기화면(예전 동작 유지)');
    // (3) 상주형 칠판에서 요청이 돌아오기 전에 호출 화면이 마감 — 확인은 곧바로 보내고, 화면은 응답을 받은 뒤 정한다(1.3.3과 같게).
    //     먼저 닫으면 상주형이 내려가 버려, 응답에 실려 온 «대기 중인 다음 호출»이 뒤에서 떠 안 보인다(1.3.4 재검수)
    let release = null;
    const d = tickBox(() => new Promise(r => { release = r; }));
    d.c.SETTINGS.mode = 'tray';
    d.c.__NOW = T0; d.c.tick(); await flush();
    vm.runInContext('alertedRows[5] = true; current = { row: 5, num: 12, name: "가상학생", deadlineAt: __NOW - 1, totalSec: 30 };', d.c);
    d.c.__NOW = T0 + 3000; d.c.tick(); await flush();
    same(d.log.confirm.length, 1, '마감 확인(confirm)은 곧바로 보낸다');
    same(d.log.getCalls, 1, '겹쳐 묻지는 않는다');
    same([d.log.standby, d.log.alert.length], [0, 0], '응답 전에는 호출 화면을 닫지 않는다(상주형이 내려가지 않게)');
    release({ ok: true, data: [{ row: 6, num: 7, name: '가상학생2' }] }); await flush(); await flush();
    same(d.log.alert, [6], '응답에 실린 대기 중인 다음 호출을 곧바로 띄운다');
    same(d.log.standby, 0, '다음 호출이 있으면 대기화면으로 가지 않는다');
  });
  check('L-10 켤 때 지난번 화면 목록을 읽어 합친다 (새로고침 뒤 첫 호출 한 건으로 목록을 통째로 덮어쓰지 않게)', async () => {
    const sets = [];
    let stored = '7:1789000000000,깨짐,8:1789000001000';
    let resolveGet = null;
    const P = { get: () => new Promise(r => { resolveGet = () => r({ value: stored }); }), set: o => { sets.push(o); return Promise.resolve(); } };
    const c = sandbox(['nativePrefs', 'rememberWebAlerted', 'loadWebAlerted'], { vars: ['WEB_POLL_KEY', 'pollBusy'], globals: { window: { Capacitor: { Plugins: { Preferences: P } } } } });
    c.__NOW = 1789000005000;
    c.loadWebAlerted();
    c.rememberWebAlerted(9);            // 읽기가 끝나기 전에 새 호출이 먼저 와도
    resolveGet(); await flush(); await flush();
    c.__NOW = 1789000006000;
    c.rememberWebAlerted(10);
    same(sets.pop().value, '7:1789000000000,8:1789000001000,9:1789000005000,10:1789000006000', '읽은 목록 + 먼저 온 호출 + 새 호출');
    ok(/loadWebAlerted\(\);\s*\n\s*startPolling\(\);/.test(SRC), '켤 때 목록을 읽고 나서 폴링을 시작하지 않는다');
  });

  /* 2026-09-14 전수 점검(1.3.5) — 서버 오류일 때 화면이 사실과 다르게 말하던 두 곳 */
  check('L-11 급식을 한 번도 못 받았으면 «없어요»가 아니라 «불러오지 못했어요» / 받은 뒤 실패하면 직전값', async () => {
    const seen = [];
    let fail = true;
    const c = sandbox(['refreshMeal', 'isConfigured'], { vars: ['POLL_MS', 'pollTimer', 'lastMeal'], optional: ['normalizeClassNo'] });
    c.SETTINGS = { webAppUrl: 'https://a.b/exec', grade: '3', classNum: '2' };
    c.setTimeout = () => 1; c.clearTimeout = () => {};
    const M = [{ type: '중식', dishes: ['밥', '국'], kcal: '700', allergy: [] }];
    c.api = { getMeal: () => Promise.resolve(fail ? { ok: false, error: 'HTTP 500' } : { ok: true, data: M }), getTimetable: () => Promise.resolve({ ok: false, error: 'HTTP 500' }) };
    c.onBoardData = d => seen.push(d);
    await c.refreshMeal();
    same(seen[0].meal, null, '처음 실패한 급식은 null(못 받음)이어야 한다');
    fail = false; await c.refreshMeal();
    same(seen[1].meal, M, '성공');
    fail = true; await c.refreshMeal();
    same(seen[2].meal, M, '받은 뒤 실패하면 직전값 유지');
    c.api.getMeal = () => Promise.resolve({ ok: true, data: { ok: false, msg: '알 수 없는 api' } });
    await c.refreshMeal();
    same(seen[3].meal, M, '200인데 목록이 아닌 답은 받은 것으로 치지 않는다(직전 급식을 덮지 않음)');
    const r = sandbox(['renderMeal']);
    r.renderMeal(null);
    ok(r.__doc.reg.mealList.innerHTML.indexOf('불러오지 못했') >= 0, '못 받음(null) 문구: ' + r.__doc.reg.mealList.innerHTML);
    r.renderMeal([]);
    ok(r.__doc.reg.mealList.innerHTML.indexOf('오늘은 급식이 없어요') >= 0, '받았는데 빈 날 문구: ' + r.__doc.reg.mealList.innerHTML);
  });
  check('L-12 음성: 서버가 audio에 «ERROR:…»(구글 음성 실패)를 주면 해독하지 않고 «음성 준비 실패»', async () => {
    const st = [];
    let atobCalled = false;
    const c = sandbox(['speakAsync'], { vars: ['_ttsSource'], globals: { atob: () => { atobCalled = true; throw new Error('atob가 불렸다'); } } });
    vm.runInContext('_ttsToken = 1;', c);
    c.setTtsStatus = m => st.push(m);
    c.SETTINGS = { webAppUrl: 'https://a.b/exec' };
    c.api = { getTts: () => Promise.resolve({ ok: true, data: { audio: 'ERROR:403' } }) };
    await c.speakAsync('가상학생', 1);
    ok(!atobCalled, '실패 표시를 base64로 해독하려 했다');
    same(st[st.length - 1], '⚠️ 음성 준비 실패', '마지막 상태 문구');
  });
}

/* ---------- 실행 ---------- */
// 끝나지 않는 await(응답이 오지 않는 가짜 요청)에 걸리면 노드가 «결과» 줄 없이 조용히 끝난다 — 통과처럼 보이지 않게 실패로 끝낸다
let running = null;
process.on('beforeExit', () => {
  if (!running) return;
  console.log('  멈춤  ' + running + '\n      끝나지 않는 await에 걸려 검사가 멈췄다 — 뒤 검사는 돌지 않았다');
  process.exit(1);
});
(async () => {
  let pass = 0, fail = 0;
  console.log('[' + FLAVOR + '] ' + APP);
  for (const t of results) {
    running = t.name;
    try { await t.body(); pass++; console.log('  통과  ' + t.name); }
    catch (e) { fail++; console.log('  실패  ' + t.name + '\n      ' + String(e && e.message || e).split('\n').join('\n      ')); }
  }
  running = null;
  console.log('\n결과: 통과 ' + pass + ' / 실패 ' + fail + ' (전체 ' + results.length + ')');
  process.exit(fail ? 1 : 0);
})();
