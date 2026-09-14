package com.kimju.youcallboard;

/* PollGate 규칙 검사 — 안드로이드 없이 javac로 돈다(tests/test-v120.js L-1이 부른다).
     javac -encoding UTF-8 -d <폴더> PollGate.java PollGateTest.java
     java -cp <폴더> com.kimju.youcallboard.PollGateTest          규칙 검사
     java -cp <폴더> com.kimju.youcallboard.PollGateTest delays   간격 표(JSON) — app.js pollDelayMs와 대조용 */
public class PollGateTest {
    static int pass = 0, fail = 0;

    static void eq(Object got, Object want, String msg) {
        if (String.valueOf(got).equals(String.valueOf(want))) { pass++; return; }
        fail++;
        System.out.println("  실패  " + msg + "  받은 값=" + got + "  기대=" + want);
    }

    public static void main(String[] args) {
        if (args.length > 0 && "delays".equals(args[0])) {
            StringBuilder sb = new StringBuilder("{");
            long[] bases = { 2000L, 3000L };
            for (int b = 0; b < bases.length; b++) {
                if (b > 0) sb.append(',');
                sb.append('"').append(bases[b]).append("\":[");
                for (int f = 0; f <= 8; f++) { if (f > 0) sb.append(','); sb.append(PollGate.nextDelayMs(bases[b], f)); }
                sb.append(']');
            }
            System.out.println(sb.append('}'));
            return;
        }

        long now = 1_789_000_000_000L;
        long day = 24L * 60 * 60 * 1000;

        // 1. 서비스가 쉬는가 — 앱이 앞에 있고 화면이 7초 안에 물었을 때만
        eq(PollGate.yieldToWeb(true, String.valueOf(now - 1000), now), true, "앞에 있고 화면이 1초 전에 물음 → 쉰다");
        eq(PollGate.yieldToWeb(true, String.valueOf(now - 6999), now), true, "6.999초 전 → 쉰다");
        eq(PollGate.yieldToWeb(true, String.valueOf(now - 7000), now), false, "7초 전(화면이 멈춘 듯) → 묻는다");
        eq(PollGate.yieldToWeb(true, String.valueOf(now), now), true, "방금 → 쉰다");
        eq(PollGate.yieldToWeb(false, String.valueOf(now - 1000), now), false, "앱이 뒤에 있음(HDMI 등) → 묻는다");
        eq(PollGate.yieldToWeb(true, null, now), false, "표시 없음(옛 화면·첫 실행) → 묻는다");
        eq(PollGate.yieldToWeb(true, "", now), false, "빈 값 → 묻는다");
        eq(PollGate.yieldToWeb(true, "abc", now), false, "깨진 값 → 묻는다");
        eq(PollGate.yieldToWeb(true, String.valueOf(now + 5000), now), false, "미래 시각(시계가 되돌아감) → 묻는다");
        eq(PollGate.yieldToWeb(true, " " + (now - 10) + " ", now), true, "앞뒤 공백은 떼고 읽는다");

        // 2. 실패가 이어질 때 간격
        eq(PollGate.nextDelayMs(2000, 0), 2000, "서비스 실패 0 → 2초");
        // 1.3.6: 두 번째 실패까지는 물러나지 않는다(가끔 실패하는 서버에서 호출이 늦게 뜨던 것)
        eq(PollGate.nextDelayMs(2000, 1), 2000, "서비스 실패 1 → 2초 그대로");
        eq(PollGate.nextDelayMs(2000, 2), 2000, "서비스 실패 2 → 2초 그대로");
        eq(PollGate.nextDelayMs(2000, 3), 4000, "서비스 실패 3 → 4초");
        eq(PollGate.nextDelayMs(2000, 4), 8000, "서비스 실패 4 → 8초");
        eq(PollGate.nextDelayMs(2000, 5), 15000, "서비스 실패 5 → 15초(상한)");
        eq(PollGate.nextDelayMs(2000, 50), 15000, "오래 실패해도 15초");
        eq(PollGate.nextDelayMs(2000, Integer.MAX_VALUE), 15000, "넘침 없이 15초");
        eq(PollGate.nextDelayMs(2000, -3), 2000, "음수 → 기본");
        eq(PollGate.nextDelayMs(3000, 2), 3000, "화면 실패 2 → 3초 그대로");
        eq(PollGate.nextDelayMs(3000, 3), 6000, "화면 실패 3 → 6초");
        eq(PollGate.nextDelayMs(3000, 4), 12000, "화면 실패 4 → 12초");
        eq(PollGate.nextDelayMs(3000, 5), 15000, "화면 실패 5 → 15초");

        // 3. 화면이 앞에서 이미 띄운 호출인가
        String list = "5:" + (now - 60_000) + ",7:" + (now - 1000);
        long ttl = day;
        eq(PollGate.shownWhileForeground(list, 7, true, 0, now, ttl), true, "지금 앞에 있고 화면이 띄운 호출 → 이미 알림");
        eq(PollGate.shownWhileForeground(list, 7, false, now - 500, now, ttl), true, "화면이 띄운 뒤(1초 전) 앱이 뒤로 감(0.5초 전) → 이미 알림");
        eq(PollGate.shownWhileForeground(list, 7, false, now - 2000, now, ttl), false, "앱이 뒤로 간 뒤(2초 전)에 화면이 띄움 → 서비스가 앞으로 가져와야");
        eq(PollGate.shownWhileForeground(list, 5, false, now - 500, now, ttl), true, "1분 전에 앞에서 띄운 호출");
        eq(PollGate.shownWhileForeground(list, 9, true, now, now, ttl), false, "목록에 없는 호출 → 새 호출");
        eq(PollGate.shownWhileForeground(list, 7, false, 0, now, ttl), false, "한 번도 앞에 없었음(상주형) → 새 호출");
        eq(PollGate.shownWhileForeground("7:" + (now - day - 1), 7, true, now, now, ttl), false, "하루 지난 기록(시트를 비워 행 번호가 다시 쓰임) → 새 호출");
        eq(PollGate.shownWhileForeground("7:" + (now + 120_000), 7, true, now, now, ttl), false, "시계가 어긋난 미래 기록 → 믿지 않음");
        eq(PollGate.shownWhileForeground(null, 7, true, now, now, ttl), false, "표시 없음");
        eq(PollGate.shownWhileForeground("", 7, true, now, now, ttl), false, "빈 값");
        eq(PollGate.shownWhileForeground("x:y,,7,:5,7:abc, 7 : " + (now - 10) + " ", 7, true, 0, now, ttl), true, "깨진 조각은 건너뛰고 멀쩡한 조각은 읽는다");
        eq(PollGate.shownWhileForeground("x:y,,7,:5", 7, true, 0, now, ttl), false, "깨진 조각뿐 → 새 호출");
        eq(PollGate.shownWhileForeground("17:" + (now - 10), 7, true, 0, now, ttl), false, "행 17을 7로 착각하지 않는다");

        // 4. 화면 목록에서 믿을 만한 조각만 (서비스가 쉬는 동안 자기 기록으로 옮기는 값)
        java.util.List<long[]> en = PollGate.webAlertedEntries("5:" + (now - 1000) + ",x:1,,-3:" + (now - 10) + ",8:" + (now - day - 5) + ",9:" + (now + 120_000) + ", 12 : " + (now - 20), now, ttl);
        eq(en.size(), 2, "깨진 조각·음수 행·하루 지난 기록·미래 기록은 버리고 2개");
        eq(en.size() > 0 ? en.get(0)[0] + ":" + en.get(0)[1] : "", "5:" + (now - 1000), "첫 조각 행·시각");
        eq(en.size() > 1 ? String.valueOf(en.get(1)[0]) : "", "12", "공백 섞인 조각도 읽는다");
        eq(PollGate.webAlertedEntries(null, now, ttl).size(), 0, "표시 없음 → 빈 목록");

        System.out.println("PollGate 결과: 통과 " + pass + " / 실패 " + fail);
        System.exit(fail == 0 ? 0 : 1);
    }
}
