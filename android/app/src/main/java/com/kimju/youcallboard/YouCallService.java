package com.kimju.youcallboard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashSet;
import java.util.Set;

/**
 * 윈도우판 유콜 데스크의 "트레이 상주"에 대응하는 안드로이드 구현.
 *
 * 앱 화면이 앞에 없어도(다른 앱을 쓰고 있어도) 계속 호출을 감시하다가,
 * 새 호출이 오면 앱을 화면 앞으로 끌어올린다. 상태바 알림이 트레이 아이콘 역할을 한다.
 *
 * 확인(confirmCall) 처리는 하지 않는다 — 그건 화면에 뜬 웹 쪽이 카운트다운과 함께 담당한다.
 * 이 서비스의 역할은 "호출이 왔으니 화면을 띄워라" 트리거까지다.
 */
public class YouCallService extends Service {

    private static final String TAG = "YouCallService";
    private static final String CH_ONGOING = "youcall_ongoing";  // 상주(트레이) 알림
    // 호출 알림(전체화면 인텐트). 1.3.2부터 무음 채널이다 — 채널 소리는 한 번 만들면 앱이 못 바꾸므로
    // id를 새로 두고, 알람음이 박혀 있던 옛 채널(1.1.17~1.3.1)은 지운다.
    private static final String CH_CALL = "youcall_call_silent";
    private static final String CH_CALL_OLD = "youcall_call";
    // 웹(app.js markSoundedForNative)이 «이 호출은 화면이 소리를 냈다»고 적는 자리. 형식 "행번호:시각".
    private static final String KEY_SOUNDED = "yc_sounded";
    // 화면이 뜨거나 웹이 소리를 냈는지 기다리는 시간. 웹 폴링 3초 + 서버 응답 2~3초를 넉넉히 덮는다.
    private static final long FALLBACK_CHECK_MS = 8000L;
    private static final int NOTI_ONGOING = 1;
    private static final int NOTI_CALL = 2;
    private static final long POLL_MS = 2000L;

    // 화면(웹)과 나눠 쓰는 표시. app.js가 Capacitor Preferences로 같은 저장소(CapacitorStorage)에 적는다.
    // 1.3.3까지는 앱이 앞에 있어도 서비스(2초)와 화면(3초)이 둘 다 서버에 물어, 18개 반 학교에서 구글 한도에 걸렸다(2026-09-14).
    private static final String KEY_WEB_POLL = "yc_web_poll";        // 화면이 호출을 묻고 있는 마지막 시각
    private static final String KEY_WEB_ALERTED = "yc_web_alerted";  // 화면이 띄운 호출 "행:시각,행:시각"
    private static final String KEY_SVC_YIELD = "yc_svc_yield";      // 서비스가 화면에 맡기고 쉰 마지막 시각(「뒤 감시」 표시용)
    /** 이어진 실패 수. 서버가 한도에 걸렸을 때 2초마다 두드리지 않고 세 번째 실패부터 4·8·15초로 늘린다(PollGate.nextDelayMs). */
    private volatile int failStreak = 0;
    /**
     * 실패가 이어져 물러난 동안 다음에 물어도 되는 때(부팅 뒤 흐른 시간 — 벽시계를 바꿔도 안 틀어진다). 0이면 곧바로.
     * 박자(2초)는 그대로 두고 이 시각 전의 차례만 흘려보낸다. 박자 자체를 늘리면 쉬는 동안 늘어난 간격이 남아,
     * 앱이 뒤로 간 뒤 첫 확인이 15초까지 늦었다(1.3.4 검수).
     */
    private volatile long nextPollAt = 0L;
    /** 쉬는 표시(yc_svc_yield)는 8초마다만 적는다 — 저장소 파일을 2초마다 다시 쓰지 않게. 「뒤 감시」는 20초 안이면 «쉬는 중». */
    private static final long YIELD_MARK_MS = 8000L;
    private long lastYieldMark = 0L;
    /** 서비스 기록으로 옮긴 마지막 화면 목록 값 — 값이 바뀔 때만 옮긴다. */
    private String mergedWebAlerted = null;

    // Capacitor Preferences 플러그인이 쓰는 SharedPreferences 파일/키 규칙
    private static final String PREF_FILE = "CapacitorStorage";
    private static final String KEY_SETTINGS = "yc_settings";

    private final Handler handler = new Handler(Looper.getMainLooper());
    /**
     * 이미 알린 호출들. **프로세스가 죽었다 살아나도 기억해야 한다.**
     * 서버는 선생님이 확인하기 전까지 호출을 계속 들고 있는데, 화면을 못 띄우는 상황에서는
     * 웹이 "확인"을 보내지 못한다. 그 상태에서 이 기록이 메모리에만 있으면 —
     * 앱이 되살아날 때마다 같은 호출을 새 호출로 보고 다시 울린다(수업 중이면 재앙이다).
     */
    private final Set<Integer> alertedRows = new HashSet<>();
    private static final String KEY_ALERTED = "yc_alerted_rows";
    private static final int ALERTED_KEEP = 200;   // 오래된 것부터 버린다. 무한히 쌓이지 않게.

    /**
     * 기록을 **행 번호로만** 들고 있으면 안 된다. 유콜 시트를 비우면 행 번호가 1부터 다시
     * 시작하는데, 그때 앱이 "이미 알린 호출"로 착각해 **진짜 호출을 통째로 삼킨다.**
     * 그래서 각 기록에 시각을 함께 남기고 하루가 지나면 버린다.
     * 중복을 막아야 하는 구간(죽었다 살아나는 몇 분)에는 충분히 남아 있고,
     * 다음 날이나 시트를 정리한 뒤에는 깨끗한 상태로 시작한다.
     */
    private static final long ALERTED_TTL_MS = 24L * 60 * 60 * 1000;
    private final java.util.HashMap<Integer, Long> alertedAt = new java.util.HashMap<>();
    private boolean running = false;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private android.os.PowerManager.WakeLock wakeLock;
    private android.net.wifi.WifiManager.WifiLock wifiLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
        // Android 12+는 뒤에서(부팅 직후·시스템이 서비스를 다시 세울 때) 포그라운드 서비스 전환을 막으면 이 줄에서 예외를 던진다.
        // Android 15의 «부팅 직후 dataSync 금지»도 BootReceiver가 아니라 여기서 난다. 잡지 않으면 onCreate에서 앱이 꺼진다.
        // 못 서면 조용히 멈춘다 — 앱을 열거나 앞으로 가져오면(MainActivity) 다시 올라온다.
        try {
            startForeground(NOTI_ONGOING, buildOngoingNotification("호출 대기 중"));
        } catch (Exception e) {
            Log.w(TAG, "포그라운드 서비스 시작 거절 — 앱을 열면 다시 시도: " + e);
            stopSelf();
            return;
        }
        // HDMI 입력 중에는 안드로이드 화면이 꺼진 것과 같아 시스템이 절전에 들어간다.
        // 그러면 이 서비스의 폴링 타이머가 늦춰지고 네트워크도 막혀 호출을 놓친다
        // (증상: 소리도 팝업도 없다가, 화면을 깨우면 밀린 호출이 한꺼번에 뜬다).
        // CPU만 붙잡아 두는 부분 웨이크락으로 감시를 계속한다 — 화면은 켜지 않는다.
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "youcall:poll");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        } catch (Exception e) { Log.w(TAG, "웨이크락 실패: " + e.getMessage()); }

        // CPU를 깨워둬도 **Wi-Fi가 따로 잠든다.** 안드로이드는 화면이 꺼지면 Wi-Fi를 끊는데,
        // 그러면 서비스는 멀쩡히 돌면서 서버에 물어보지 못한다 — 겉보기 증상은 절전과 똑같다.
        // (칠판 업체 확인: 칠판 파워세이브는 꺼져 있다. 그러니 남은 건 안드로이드 쪽 Wi-Fi 절전이다.)
        try {
            android.net.wifi.WifiManager wm =
                (android.net.wifi.WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "youcall:wifi");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Exception e) { Log.w(TAG, "와이파이락 실패: " + e.getMessage()); }

        // 죽기 전에 이미 알린 호출들을 되살린다 — 부활 직후 같은 호출로 다시 울리지 않게.
        try { loadAlerted(getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)); } catch (Exception ignored) { }

        // 이 칠판에 "입력 전환"으로 쓸 만한 것이 있는지 미리 훑어 둔다(설치된 앱 조회는 무거워서 딴 스레드에서).
        // 호출이 오기 전에도 앱 화면에서 확인할 수 있어야, 안 될 칠판인지 바로 가려진다.
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    SourceSwitcher.writeReport(YouCallService.this,
                        getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE), null);
                } catch (Exception ignored) { }
            }
        }).start();

        running = true;
        handler.post(pollTask);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY; // 시스템이 죽여도 다시 살아나 상주를 유지한다
    }

    /**
     * Android 15(targetSdk 35+)는 dataSync 포그라운드 서비스를 하루 6시간까지만 허락하고, 다 차면 이걸 부른다.
     * 몇 초 안에 stopSelf()를 안 하면 «did not stop within its timeout»으로 앱이 강제 종료된다(공식 문서).
     * 멈춰도 앱 화면(웹)은 계속 호출을 확인하고 소리를 낸다. 사용자가 앱을 앞으로 가져오면 6시간이 다시 채워진다.
     */
    @Override
    public void onTimeout(int startId, int fgsType) {
        Log.w(TAG, "dataSync 6시간 한도 — 서비스를 멈춘다(강제 종료 방지)");
        try { stopSelf(); } catch (Exception ignored) { }
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacks(pollTask);
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) { }
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) { }
        // 볼륨을 올린 채 서비스가 죽으면 그 상태로 남는다 — 반드시 되돌리고 나간다.
        try { handler.removeCallbacks(stopRingTask); stopRingTask.run(); } catch (Exception ignored) { }
        super.onDestroy();
    }

    /** 응답이 느릴 때(타임아웃 8초 > 폴링 2초) 요청이 겹쳐 쌓이지 않도록 한 번에 하나만 돈다. */
    private volatile boolean polling = false;

    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            // 앱이 앞에 떠서 화면이 스스로 묻고 있으면 이번 차례는 쉰다 — 같은 칠판이 두 갈래로 서버에 묻지 않게.
            // 앞에 있을 때는 서비스가 호출을 찾아도 할 일이 없었다(알림·대체 알람은 앱이 뒤에 있을 때만) — 호출이 뜨는 속도는 그대로다.
            // 실패가 이어져 물러난 중이면 nextPollAt 전의 차례만 흘려보낸다(0.3초는 박자 오차). 박자는 늘 2초라 뒤로 가면 곧바로 이어받는다.
            if (!polling && !yieldToWeb() && android.os.SystemClock.elapsedRealtime() + 300 >= nextPollAt) {
                polling = true;
                new Thread(new Runnable() {
                    @Override public void run() {
                        try { pollOnce(); } finally { polling = false; }
                    }
                }).start();
            }
            handler.postDelayed(this, POLL_MS);
        }
    };

    /** 화면에 맡기고 쉴 차례인가. 저장소를 못 읽는 등 이상하면 쉬지 않는다(틀려도 «묻는» 쪽으로). */
    private boolean yieldToWeb() {
        try {
            SharedPreferences sp = getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
            long now = System.currentTimeMillis();
            if (!PollGate.yieldToWeb(MainActivity.inForeground, sp.getString(KEY_WEB_POLL, null), now)) return false;
            failStreak = 0; nextPollAt = 0L;   // 쉬는 동안엔 서버 상태를 모른다 — 옛 실패 수가 남아 뒤로 간 뒤 첫 실패에 곧바로 15초 물러나지 않게
            // 앞에서 화면이 띄운 호출을 서비스 기록(yc_alerted_rows)으로 옮긴다 — 1.3.3에서 서비스가 앞에서도 물어 남기던 기록과 같은 뜻.
            // 이렇게 해야 앱이 죽었다 살아나도(메모리의 leftForegroundAt이 사라져도) 이미 띄운 호출로 칠판을 다시 끌어오지 않는다(1.3.4 검수).
            // 이 자리는 폴링 스레드가 돌지 않을 때(!polling)만 불려 alertedRows를 함께 만지지 않는다.
            String wa = sp.getString(KEY_WEB_ALERTED, null);
            if (wa != null && !wa.equals(mergedWebAlerted)) {
                boolean added = false;
                for (long[] e : PollGate.webAlertedEntries(wa, now, ALERTED_TTL_MS)) {
                    int row = (int) e[0];
                    if (alertedRows.add(row)) { alertedAt.put(row, e[1]); added = true; }
                }
                if (added) saveAlerted(sp);
                mergedWebAlerted = wa;
            }
            // 「뒤 감시」가 «멈춤»이 아니라 «쉬는 중»으로 보이게. 저장소 파일 전체를 다시 쓰므로 8초마다만.
            if (now - lastYieldMark >= YIELD_MARK_MS || now < lastYieldMark) {
                lastYieldMark = now;
                sp.edit().putString(KEY_SVC_YIELD, String.valueOf(now)).apply();
                setOngoing("유콜 화면이 호출을 확인하는 중");   // 쉬기 전 «서버 연결 실패» 같은 문구가 남아 있지 않게
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 설정 주소에서 교사용 쿼리(role·k)와 #해시를 뗀다. k는 교사 열쇠라 칠판 요청에 실려 나가면 안 된다.
     * 화면(app.js)이 저장할 때·켤 때 이미 떼지만, 옛 판이 저장해 둔 값은 앱을 한 번 열기 전까지 그대로 남아 있다.
     */
    static String stripTeacherParams(String base) {
        if (base == null) return "";
        String s = base.trim();
        int hash = s.indexOf('#');
        if (hash >= 0) s = s.substring(0, hash);
        int q = s.indexOf('?');
        if (q < 0) return s;
        StringBuilder out = new StringBuilder(s.substring(0, q));
        boolean first = true;
        for (String pair : s.substring(q + 1).split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String name = eq >= 0 ? pair.substring(0, eq) : pair;
            try { name = java.net.URLDecoder.decode(name, "UTF-8"); } catch (Exception ignored) { }
            name = name.toLowerCase(java.util.Locale.ROOT);
            if (name.equals("role") || name.equals("k")) continue;
            out.append(first ? '?' : '&').append(pair);
            first = false;
        }
        return out.toString();
    }

    private void pollOnce() {
        try {
            SharedPreferences sp = getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
            String raw = sp.getString(KEY_SETTINGS, null);
            // 이 서비스가 조용히 멈추는 자리는 전부 상주 알림에 글자로 남긴다.
            // "소리도 팝업도 없이 무반응"이라는 제보를 상태바만 보고 가려내기 위한 것이다.
            if (raw == null) { setOngoing("설정을 기다리는 중 — 앱을 열어 저장해 주세요"); return; }

            JSONObject cfg = new JSONObject(raw);
            String base = stripTeacherParams(cfg.optString("webAppUrl", ""));
            String grade = cfg.optString("grade", "");
            String classNum = cfg.optString("classNum", "");
            if (base.isEmpty() || grade.isEmpty() || classNum.isEmpty()) {
                setOngoing("설정이 비어 있음 — 앱을 열어 주소·학년·반을 저장해 주세요"); return;
            }

            String url = base
                + (base.contains("?") ? "&" : "?")
                + "api=calls"
                + "&grade=" + URLEncoder.encode(grade, "UTF-8")
                + "&classNum=" + URLEncoder.encode(classNum, "UTF-8");

            String body = httpGet(url);
            if (body == null) { markFailed(); setOngoing(grade + "학년 " + classNum + "반 · 서버 연결 실패 (주소 확인)"); return; }

            // 상태바만 보고도 무엇이 막고 있는지 알 수 있어야 한다.
            // 절전 제외가 안 되어 있으면 HDMI를 보는 동안 이 감시가 통째로 멈춘다 — 그게 더 큰 문제라 앞에 쓴다.
            String warn = "";
            if (!isBatteryExempt()) warn += " · ⚠ 절전 제외 필요";
            if (!canOverlay()) warn += " · ⚠ 다른 앱 위에 표시 꺼짐";
            setOngoing(grade + "학년 " + classNum + "반 감시 중" + warn);

            // 서버까지 다녀온 시각을 남긴다. 알림이 안 보이는 칠판에서도
            // 앱을 열면 "뒤에서 언제까지 돌았는지"를 화면으로 확인할 수 있다 —
            // HDMI를 보는 동안 죽어 있었는지 아닌지가 이 값 하나로 갈린다.
            try {
                sp.edit().putString("yc_last_poll", String.valueOf(System.currentTimeMillis())).apply();
            } catch (Exception ignored) { }

            // 화면 자동 꺼짐이 되돌려졌으면 다시 "사용 안 함"으로. (칠판 재부팅이나
            // 다른 앱이 값을 바꿔놓으면 절전 연쇄가 되살아나 증상이 재발한다.)
            if (++sleepGuardTick % 60 == 0) keepScreenTimeoutOff();

            JSONArray calls = new JSONArray(body);
            failStreak = 0; nextPollAt = 0L;   // 서버가 제대로 답했다 — 물러났던 간격을 곧바로 2초로 되돌린다
            if (calls.length() == 0) return;

            // 아직 안 띄운 호출 중 가장 앞의 것
            for (int i = 0; i < calls.length(); i++) {
                JSONObject c = calls.getJSONObject(i);
                int row = c.optInt("row", -1);
                if (row < 0 || alertedRows.contains(row)) continue;

                // 서비스가 쉬는 동안(앱이 앞에 있을 때) 화면이 이미 띄운 호출이면 새 호출로 보지 않는다 —
                // 앱이 뒤로 간 뒤 같은 호출로 칠판을 다시 끌어오지 않게. 1.3.3까지는 서비스가 앞에서도 물어 스스로 기록을 남겼다.
                long nowMs = System.currentTimeMillis();
                boolean shown = PollGate.shownWhileForeground(sp.getString(KEY_WEB_ALERTED, null), row,
                    MainActivity.inForeground, MainActivity.leftForegroundAt, nowMs, ALERTED_TTL_MS);
                alertedRows.add(row);
                alertedAt.put(row, nowMs);
                saveAlerted(sp);          // 죽었다 살아나도 같은 호출로 또 울리지 않도록 즉시 남긴다
                if (shown) continue;
                String name = c.optString("name", "");
                String num = c.optString("num", "");
                String teacher = c.optString("teacher", "");
                String message = c.optString("message", "");
                bringAppToFront(row, num, name, teacher, message);
                break;
            }
        } catch (Exception e) {
            markFailed();             // 로그인 화면(HTML)·한도 초과 안내 같은 깨진 응답도 실패로 센다
            Log.w(TAG, "poll 실패: " + e.getMessage());
            setOngoing("점검 필요: " + e.getClass().getSimpleName());
        }
    }

    /**
     * 실패를 하나 세고, 다음에 물어도 되는 때를 늦춘다(두 번째까지는 2초 그대로, 그 뒤 4→8→15초). 박자는 그대로라 성공하면 곧바로 2초로 돌아온다.
     * 실패가 «끝난» 때부터 잰다 — 보낸 때부터 재면 8초 시간 초과 뒤 곧바로 다시 보내 물러나기가 헛돈다(1.3.4 검수).
     * 벽시계가 아니라 부팅 뒤 흐른 시간이라 시각을 바꿔도 안 틀어진다.
     */
    private void markFailed() {
        failStreak++;
        nextPollAt = android.os.SystemClock.elapsedRealtime() + PollGate.nextDelayMs(POLL_MS, failStreak);
    }

    private boolean canOverlay() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
    }

    /** 저장해 둔 "이미 알린 호출"을 불러온다(하루 지난 것은 버린다). 서비스가 살아날 때 한 번. */
    private void loadAlerted(SharedPreferences sp) {
        try {
            String s = sp.getString(KEY_ALERTED, null);
            if (s == null) return;
            long now = System.currentTimeMillis();
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) {
                Object item = a.get(i);
                if (item instanceof JSONArray) {                 // [행번호, 알린시각]
                    JSONArray pair = (JSONArray) item;
                    int row = pair.getInt(0);
                    long ts = pair.getLong(1);
                    if (now - ts > ALERTED_TTL_MS) continue;     // 오래된 기록은 되살리지 않는다
                    alertedRows.add(row);
                    alertedAt.put(row, ts);
                }
                // 옛 판이 남긴 «숫자만» 기록은 시각을 알 수 없다. 통째로 버린다 —
                // 남겨 두면 시트를 비웠을 때 진짜 호출을 삼킬 수 있다.
            }
        } catch (Exception ignored) { }
    }

    private void saveAlerted(SharedPreferences sp) {
        try {
            long now = System.currentTimeMillis();
            java.util.List<Integer> list = new java.util.ArrayList<>(alertedRows);
            java.util.Collections.sort(list);                       // 행 번호는 커질수록 최신이다
            int from = Math.max(0, list.size() - ALERTED_KEEP);
            JSONArray a = new JSONArray();
            java.util.HashMap<Integer, Long> kept = new java.util.HashMap<>();
            for (int i = from; i < list.size(); i++) {
                int row = list.get(i);
                Long ts = alertedAt.get(row);
                if (ts == null) ts = now;
                if (now - ts > ALERTED_TTL_MS) continue;
                JSONArray pair = new JSONArray();
                pair.put(row).put(ts);
                a.put(pair);
                kept.put(row, ts);
            }
            alertedRows.clear(); alertedRows.addAll(kept.keySet());  // 메모리도 같은 상태로 맞춘다
            alertedAt.clear(); alertedAt.putAll(kept);
            sp.edit().putString(KEY_ALERTED, a.toString()).apply();
        } catch (Exception ignored) { }
    }

    private int sleepGuardTick = 0;

    /** 화면 자동 꺼짐을 "사용 안 함"으로 유지한다. 권한이 없으면 조용히 지나간다. */
    private void keepScreenTimeoutOff() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(this)) return;
            int v = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, 0);
            if (v < Integer.MAX_VALUE) {
                Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, Integer.MAX_VALUE);
                Log.i(TAG, "화면 자동 꺼짐을 다시 껐다(이전 값: " + v + ")");
            }
        } catch (Exception ignored) { }
    }

    private boolean isBatteryExempt() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm == null || pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Exception e) { return true; }
    }

    /** 상주(트레이) 알림 문구를 지금 상태로 바꾼다. 같은 문구면 건드리지 않는다. */
    private String lastOngoing = null;
    private void setOngoing(String text) {
        if (text == null || text.equals(lastOngoing)) return;
        lastOngoing = text;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(NOTI_ONGOING, buildOngoingNotification(text));
        } catch (Exception ignored) { }
    }

    private String httpGet(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL u = new URL(urlStr);
            conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setInstanceFollowRedirects(true); // GAS는 script.googleusercontent.com으로 302된다
            int code = conn.getResponseCode();
            if (code != 200) return null;
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 호출이 왔을 때 앱을 화면 앞으로. 권한이 있으면 즉시 띄우고, 없으면 전체화면 인텐트 알림으로 대체한다. */
    private void bringAppToFront(final int row, String num, String name, String teacher, String message) {
        final Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);

        String title = "📣 " + num + "번 " + name + " 학생 호출";
        String text = (teacher.isEmpty() ? "" : teacher + " 선생님")
            + (message.isEmpty() ? "" : (teacher.isEmpty() ? "" : " · ") + message);

        PendingIntent pi = PendingIntent.getActivity(
            this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            ? new Notification.Builder(this, CH_CALL)
            : new Notification.Builder(this);
        b.setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true); // 잠금/절전 상태면 화면을 바로 띄운다
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) b.setPriority(Notification.PRIORITY_MAX);

        // 화면이 이미 앞에 떠 있으면 알림까지 울릴 필요가 없다(호출 화면이 크게 떠 있고 호출음도 난다).
        // 알림 채널 소리와 웹 호출음이 겹치는 것을 막는다.
        if (!MainActivity.inForeground) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            // 여기서 예외가 나면 이 호출은 이미 «알림»으로 기록된 뒤라(saveAlerted) 화면 띄우기·대체 알람까지 통째로 건너뛴다 — 감싼다
            try { nm.notify(NOTI_CALL, b.build()); } catch (Exception e) { Log.w(TAG, "호출 알림 실패: " + e); }
        }

        // "다른 앱 위에 표시" 권한이 있으면 백그라운드에서도 액티비티를 직접 띄울 수 있다(가장 확실)
        boolean canOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);

        if (!MainActivity.inForeground) {
            // 화면이 앞에 없다 = HDMI를 보고 있을 수 있다. 칠판 입력을 안드로이드로 되돌려 본다.
            // 되돌아가야 우리가 띄우는 호출 화면이 실제로 보인다. 실패해도 해가 없다.
            try {
                String tryLog = SourceSwitcher.tryReturnToAndroid(this);
                SourceSwitcher.writeReport(this, getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE), tryLog);
            } catch (Exception e) { Log.w(TAG, "입력 전환 시도 실패: " + e.getMessage()); }

            // 입력이 바뀌는 데 시간이 걸린다. 곧바로 띄우면 전환 화면에 가려지므로 조금 늦춰 다시 올린다.
            if (canOverlay) {
                handler.postDelayed(new Runnable() {
                    @Override public void run() {
                        try { startActivity(open); } catch (Exception ignored) { }
                    }
                }, 2500L);
            }
        }

        if (canOverlay) {
            try { startActivity(open); } catch (Exception e) { Log.w(TAG, "startActivity 실패: " + e.getMessage()); }
        }

        // 소리는 기본판처럼 «화면(웹)이 사용자가 고른 호출음»으로 낸다. 서비스는 끼어들지 않는다.
        // 다만 HDMI를 보는 칠판처럼 화면도 못 뜨고 웹도 소리를 못 낸 경우엔 알릴 길이 소리뿐이라,
        // 잠시 기다려 둘 다 아니었을 때만 기기 알람음을 대신 울린다(09-03 HDMI 제보 대응을 물러설 자리로 남긴다).
        // 1.1.17~1.3.1은 앱이 앞에 없으면 곧바로 울려서, 화면이 잘 뜨는 칠판이나 뒤에서 웹이 음성을 내는
        // 칠판에서도 «설정하지 않은 소리»가 호출음 위에 겹쳤다(2026-09-14 제보).
        if (!MainActivity.inForeground) {
            final long detectedAt = System.currentTimeMillis();
            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    if (MainActivity.inForeground) { Log.i(TAG, "대체 알람 생략 — 화면이 떴다 (row " + row + ")"); return; }
                    if (webSounded(row, detectedAt)) { Log.i(TAG, "대체 알람 생략 — 웹이 소리를 냈다 (row " + row + ")"); return; }
                    Log.i(TAG, "대체 알람 울림 — 화면도 웹 소리도 없었다 (row " + row + ")");
                    playAlarmOnce();
                }
            }, FALLBACK_CHECK_MS);
        }
    }

    /**
     * 웹(app.js markSoundedForNative)이 이 호출에 소리를 냈다고 적었는지. 값 형식은 "행번호:시각".
     * 웹은 서비스보다 먼저 호출을 알아챌 수도 있으므로 감지 1분 전까지의 표시는 믿는다. 그보다 오래된 것은 다른 호출이다.
     * Capacitor Preferences는 같은 프로세스의 같은 SharedPreferences("CapacitorStorage")에 쓰므로 바로 읽힌다.
     */
    private boolean webSounded(int row, long detectedAt) {
        try {
            String v = getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE).getString(KEY_SOUNDED, null);
            if (v == null) return false;
            int c = v.indexOf(':');
            if (c <= 0) return false;
            int r = Integer.parseInt(v.substring(0, c).trim());
            long at = Long.parseLong(v.substring(c + 1).trim());
            return r == row && at >= detectedAt - 60_000L;
        } catch (Exception e) { return false; }
    }

    /**
     * 화면을 못 띄우는 상황(HDMI 입력 중 등)에서는 소리가 유일한 알림이다.
     * 칠판마다 어느 소리 길이 살아 있는지 모르므로 **여러 길로 동시에 시도한다.**
     *   · 알람 스트림(ALARM) — 보통 마지막까지 살아남는 길
     *   · 알림 스트림(NOTIFICATION) — 알람이 막힌 기기 대비
     *   · 볼륨이 0이면 잠깐 올렸다가 되돌린다(꺼져 있으면 무엇을 해도 안 들린다)
     */
    private android.media.Ringtone ringAlarm, ringNoti;
    private int savedAlarmVol = -1;
    private void playAlarmOnce() {
        try {
            // 호출이 잇따르면 이 메서드가 겹쳐 불린다. 앞의 소리를 먼저 정리하지 않으면
            // 울리던 것이 멈추지 않은 채 새 것이 겹쳐 재생된다.
            stopRing(ringAlarm); stopRing(ringNoti);
            handler.removeCallbacks(stopRingTask);

            android.media.AudioManager am = (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM);
                int cur = am.getStreamVolume(android.media.AudioManager.STREAM_ALARM);
                // savedAlarmVol을 덮어쓰면 «원래 볼륨»을 영영 잃는다 — 아직 복구 전이면 건드리지 않는다.
                if (cur < max * 0.5 && savedAlarmVol < 0) {
                    savedAlarmVol = cur;
                    am.setStreamVolume(android.media.AudioManager.STREAM_ALARM, (int) Math.ceil(max * 0.7), 0);
                }
            }
            ringAlarm = playVia(android.media.RingtoneManager.TYPE_ALARM, android.media.AudioAttributes.USAGE_ALARM);
            ringNoti = playVia(android.media.RingtoneManager.TYPE_NOTIFICATION, android.media.AudioAttributes.USAGE_NOTIFICATION);

            handler.postDelayed(stopRingTask, 10_000L);
        } catch (Exception e) { Log.w(TAG, "호출음 재생 실패: " + e.getMessage()); }
    }

    private android.media.Ringtone playVia(int ringtoneType, int usage) {
        try {
            android.net.Uri u = android.media.RingtoneManager.getDefaultUri(ringtoneType);
            if (u == null) return null;
            android.media.Ringtone r = android.media.RingtoneManager.getRingtone(getApplicationContext(), u);
            if (r == null) return null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                r.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            }
            r.play();
            return r;
        } catch (Exception e) { return null; }
    }

    /** 소리를 멈추고 볼륨을 원래대로. 새 호출이 오면 취소하고 다시 건다. */
    private final Runnable stopRingTask = new Runnable() {
        @Override public void run() {
            stopRing(ringAlarm); stopRing(ringNoti);
            try {
                android.media.AudioManager m = (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
                if (m != null && savedAlarmVol >= 0) {
                    m.setStreamVolume(android.media.AudioManager.STREAM_ALARM, savedAlarmVol, 0);
                    savedAlarmVol = -1;
                }
            } catch (Exception ignored) { }
        }
    };

    private void stopRing(android.media.Ringtone r) {
        try { if (r != null && r.isPlaying()) r.stop(); } catch (Exception ignored) { }
    }

    private Notification buildOngoingNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(
            this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            ? new Notification.Builder(this, CH_ONGOING)
            : new Notification.Builder(this);
        b.setContentTitle("유콜 보드")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(pi);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) b.setPriority(Notification.PRIORITY_MIN);
        return b.build();
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        NotificationChannel ongoing = new NotificationChannel(CH_ONGOING, "유콜 상주", NotificationManager.IMPORTANCE_MIN);
        ongoing.setDescription("호출을 계속 감시하는 중임을 알리는 상태 표시");
        ongoing.setShowBadge(false);
        nm.createNotificationChannel(ongoing);

        // 1.1.17~1.3.1이 쓰던 채널에는 알람음이 박혀 있다. 채널 소리는 앱이 못 바꾸므로 지우고 새 채널로 옮긴다
        // (남겨 두면 설정 앱에 «학생 호출»이 둘 보이고, 옛 판 칠판에서는 알람이 계속 겹친다).
        try { nm.deleteNotificationChannel(CH_CALL_OLD); } catch (Exception e) { Log.w(TAG, "옛 호출 채널 삭제 실패: " + e.getMessage()); }

        NotificationChannel call = new NotificationChannel(CH_CALL, "학생 호출", NotificationManager.IMPORTANCE_HIGH);
        call.setDescription("교무실에서 학생을 호출했을 때 화면을 띄운다");
        call.enableVibration(false);
        // 기본판처럼 무음. 소리는 화면(웹)이 사용자가 고른 호출음으로 낸다.
        // 09-03 HDMI 제보 때 여기 알람음을 넣었더니, 화면이 잘 뜨는 칠판에서도 호출음 위에 알람이 겹쳤다(2026-09-14 제보).
        // HDMI처럼 화면도 웹 소리도 없을 때의 알람은 서비스가 기다렸다가 대신 울린다(bringAppToFront).
        call.setSound(null, null);
        nm.createNotificationChannel(call);
    }

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, YouCallService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
        else ctx.startService(i);
        scheduleRevive(ctx);
    }

    /**
     * 전자칠판이 HDMI로 넘어갈 때 이 앱을 통째로 재우거나 죽이는 기종이 있다.
     * START_STICKY로도 안 살아나는 경우가 있어, 알람으로 1분마다 스스로를 다시 세운다.
     * setExactAndAllowWhileIdle은 절전 중에도 깨어나는 유일한 알람이다.
     */
    static void scheduleRevive(Context ctx) {
        try {
            android.app.AlarmManager am = (android.app.AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(ctx, ReviveReceiver.class);
            PendingIntent pi = PendingIntent.getBroadcast(
                ctx, 77, i,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
            );
            long at = System.currentTimeMillis() + 60_000L;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pi);
            else am.setExact(android.app.AlarmManager.RTC_WAKEUP, at, pi);
        } catch (Exception e) { Log.w(TAG, "부활 알람 실패: " + e.getMessage()); }
    }

    /** 알람이 깨우면 서비스를 다시 세우고 다음 알람을 건다. */
    public static class ReviveReceiver extends android.content.BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            try { YouCallService.start(ctx); } catch (Exception ignored) { }
        }
    }
}
