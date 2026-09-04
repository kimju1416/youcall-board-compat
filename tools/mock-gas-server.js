/* 유콜 보드(APK·EXE) 검증용 가짜 GAS 서버.
   실제 `?api=board&grade=..&classNum=..` 요청을 그대로 받아, v4.16 서버가 내려주는 것과
   «같은 모양»의 응답을 돌려준다 — 전교 공지와 반별 공지가 ' · '로 합쳐지고, 글자 크기는 큰 쪽.
   클라이언트를 고치지 않아도 되는지 확인하는 것이 목적이라 응답 키를 서버와 똑같이 맞춘다.

   실행: node mock-server.js [포트]   (기본 8792)
   시나리오 전환: /__set?case=merged|empty|long|single
*/
'use strict';
const http = require('http');
const url = require('url');

const PORT = parseInt(process.argv[2] || '8792', 10);

const PERIOD = { start: '08:50', periodLen: 45, breakLen: 10, lunchAfter: 4, lunchLen: 50, maxPeriod: 7 };

// 서버의 _composeBoardNotice와 같은 규칙으로 미리 만들어 둔 응답들
const CASES = {
  // 전교 공지 + 그 반 공지가 합쳐진 상태 (이번 판에서 새로 생기는 모양)
  merged: {
    notice: '오늘 하교 후 교문 공사 — 후문으로 나가세요 · 5교시 체육은 체육관에서',
    classMemo: '전교 안내 · 리코더 챙겨오기',
    noticeStep: 4, memoStep: 3
  },
  // 담임이 공지를 지운 상태
  empty: { notice: '', classMemo: '', noticeStep: 1, memoStep: 1 },
  // 아주 긴 공지 — 잘리지 않고 자동 축소되는지
  long: {
    notice: '전교 공지입니다 · ' + '가나다라마바사아자차 '.repeat(12),
    classMemo: '메모도 깁니다 ' + '하나둘셋넷다섯 '.repeat(10),
    noticeStep: 5, memoStep: 5
  },
  // 반별 공지 하나만 (합쳐지지 않은 평범한 경우)
  single: { notice: '5교시 체육은 체육관에서', classMemo: '리코더 챙겨오기', noticeStep: 2, memoStep: 1 }
};

let current = 'merged';

function boardBody() {
  const c = CASES[current];
  return {
    schoolName: '광평중학교',
    notice: c.notice,
    classMemo: c.classMemo,
    noticeStep: c.noticeStep,
    memoStep: c.memoStep,
    autoDismiss: 20,
    theme: 1,
    periodConfig: PERIOD,
    agenda: [{ title: '연수', dday: 69 }]
  };
}

const server = http.createServer((req, res) => {
  const q = url.parse(req.url, true).query;
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  res.setHeader('Cache-Control', 'no-store');

  if (req.url.indexOf('/__set') === 0) {
    if (q.case && CASES[q.case]) current = q.case;
    return res.end(JSON.stringify({ ok: true, current: current }));
  }

  const api = q.api;
  if (api === 'board') return res.end(JSON.stringify(boardBody()));
  if (api === 'meal') return res.end(JSON.stringify([]));
  if (api === 'timetable') return res.end(JSON.stringify(q.scope === 'week' ? {} : []));
  if (api === 'calls') return res.end(JSON.stringify([]));
  if (api === 'confirm') return res.end(JSON.stringify({ ok: true }));
  if (api === 'tts') return res.end(JSON.stringify({ audio: '' }));
  res.end(JSON.stringify({ ok: false, msg: '알 수 없는 api: ' + api }));
});

server.listen(PORT, () => console.log('mock GAS on http://localhost:' + PORT + ' (case=' + current + ')'));
