package com.kimju.youcallboard;

/**
 * 호출 확인을 «누가·얼마 만에» 할지 가르는 규칙. 안드로이드 클래스에 기대지 않는 계산만 둔다 —
 * 검사(tests/java/PollGateTest.java)가 javac로 이 파일을 그대로 돌린다.
 *
 * 2026-09-14 18개 반 학교의 «트래픽 오류»: 칠판 한 대가 서비스(2초)와 화면(3초) 두 갈래로 서버에 물어
 * 18대면 초당 15번, 구글 서버 동시 실행이 30개 안팎이었다. 앱이 앞에 떠서 화면이 스스로 묻고 있으면 서비스는 쉬고,
 * 실패가 이어지면 간격을 늘린다.
 */
final class PollGate {
    private PollGate() { }

    /** 화면(웹)이 이 시간 안에 «묻는 중»을 적었으면 살아 있다고 본다. 웹은 3초마다 적는다 — 한 번 빠져도 버티게 7초. */
    static final long WEB_ALIVE_MS = 7000L;
    /** 실패가 이어질 때 늘리는 간격의 상한. 더 길면 서버가 돌아온 뒤에도 호출이 늦게 뜬다. */
    static final long MAX_DELAY_MS = 15000L;

    /**
     * 서비스가 이번 차례를 쉬어도 되는가 — 앱이 앞에 있고, 화면이 방금 전까지 묻고 있었을 때만.
     * 값이 없거나 깨졌거나 시계가 어긋나 보이면 쉬지 않는다(틀려도 «묻는» 쪽으로 틀린다 — 호출을 놓치는 쪽이 아니라).
     */
    static boolean yieldToWeb(boolean inForeground, String webPollAt, long now) {
        if (!inForeground || webPollAt == null) return false;
        long at;
        try { at = Long.parseLong(webPollAt.trim()); } catch (NumberFormatException e) { return false; }
        long age = now - at;
        return age >= 0 && age < WEB_ALIVE_MS;
    }

    /**
     * 이 횟수까지의 실패는 물러나지 않는다(1.3.6). 한 번만 실패해도 물러나면 서버가 «가끔» 실패하는 학교에서 호출이 늦게 떴다
     * (실측: 실패 20%에서 10번 중 9번이 뜨는 시간 4.1초 → 9.0초). 한도에 걸려 계속 실패하면 세 번째부터 물러난다.
     */
    static final int GRACE_FAILS = 2;

    /** 다음 차례까지 기다릴 시간. 실패가 GRACE_FAILS 이하면 기본 간격, 그 뒤로는 기본×2ⁿ(상한 15초). app.js pollDelayMs와 같은 규칙. */
    static long nextDelayMs(long baseMs, int failStreak) {
        if (failStreak <= GRACE_FAILS) return baseMs;
        long d = baseMs;
        for (int i = GRACE_FAILS; i < failStreak && d < MAX_DELAY_MS; i++) d *= 2;
        return Math.min(d, MAX_DELAY_MS);
    }

    /**
     * 이 호출을 화면이 «앱이 앞에 있는 동안» 이미 띄웠는가. 값 형식 "행:시각,행:시각"(app.js rememberWebAlerted).
     * 서비스가 쉬는 사이 화면이 띄운 호출을, 앱이 뒤로 간 뒤 서비스가 새 호출로 보고 칠판을 다시 끌어오지 않게 한다.
     * 앱이 뒤에 있을 때 화면이 띄운 호출은 걸러지지 않는다 — 그 호출은 지금처럼 서비스가 앞으로 가져와야 한다.
     */
    static boolean shownWhileForeground(String webAlerted, int row, boolean inForeground, long leftForegroundAt, long now, long ttlMs) {
        for (long[] e : webAlertedEntries(webAlerted, now, ttlMs)) {
            if (e[0] != row) continue;
            if (inForeground || e[1] <= leftForegroundAt) return true;
        }
        return false;
    }

    /**
     * 화면이 적은 "행:시각,행:시각"에서 믿을 만한 조각만 {행, 시각}으로. 깨진 조각·음수 행·하루 지난 기록·시계가 어긋난 미래 값은 버린다.
     * 서비스가 쉬는 동안 이 목록을 자기 기록(yc_alerted_rows)으로 옮긴다 — 앱이 죽었다 살아나도 이미 띄운 호출로 칠판을 끌어오지 않게.
     */
    static java.util.List<long[]> webAlertedEntries(String webAlerted, long now, long ttlMs) {
        java.util.List<long[]> out = new java.util.ArrayList<>();
        if (webAlerted == null || webAlerted.isEmpty()) return out;
        for (String part : webAlerted.split(",")) {
            int c = part.indexOf(':');
            if (c <= 0) continue;
            try {
                int r = Integer.parseInt(part.substring(0, c).trim());
                long at = Long.parseLong(part.substring(c + 1).trim());
                if (r < 0 || now - at > ttlMs || at > now + 60_000L) continue;
                out.add(new long[] { r, at });
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }
}
