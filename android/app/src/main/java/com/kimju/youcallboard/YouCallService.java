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
    private static final String CH_CALL = "youcall_call";        // 호출 알림(전체화면 인텐트)
    private static final int NOTI_ONGOING = 1;
    private static final int NOTI_CALL = 2;
    private static final long POLL_MS = 2000L;

    // Capacitor Preferences 플러그인이 쓰는 SharedPreferences 파일/키 규칙
    private static final String PREF_FILE = "CapacitorStorage";
    private static final String KEY_SETTINGS = "yc_settings";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<Integer> alertedRows = new HashSet<>();
    private boolean running = false;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private android.os.PowerManager.WakeLock wakeLock;
    private android.net.wifi.WifiManager.WifiLock wifiLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
        startForeground(NOTI_ONGOING, buildOngoingNotification("호출 대기 중"));
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

        running = true;
        handler.post(pollTask);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY; // 시스템이 죽여도 다시 살아나 상주를 유지한다
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacks(pollTask);
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) { }
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) { }
        super.onDestroy();
    }

    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            new Thread(new Runnable() {
                @Override public void run() { pollOnce(); }
            }).start();
            handler.postDelayed(this, POLL_MS);
        }
    };

    private void pollOnce() {
        try {
            SharedPreferences sp = getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
            String raw = sp.getString(KEY_SETTINGS, null);
            // 이 서비스가 조용히 멈추는 자리는 전부 상주 알림에 글자로 남긴다.
            // "소리도 팝업도 없이 무반응"이라는 제보를 상태바만 보고 가려내기 위한 것이다.
            if (raw == null) { setOngoing("설정을 기다리는 중 — 앱을 열어 저장해 주세요"); return; }

            JSONObject cfg = new JSONObject(raw);
            String base = cfg.optString("webAppUrl", "");
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
            if (body == null) { setOngoing(grade + "학년 " + classNum + "반 · 서버 연결 실패 (주소 확인)"); return; }

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

            JSONArray calls = new JSONArray(body);
            if (calls.length() == 0) return;

            // 아직 안 띄운 호출 중 가장 앞의 것
            for (int i = 0; i < calls.length(); i++) {
                JSONObject c = calls.getJSONObject(i);
                int row = c.optInt("row", -1);
                if (row < 0 || alertedRows.contains(row)) continue;

                alertedRows.add(row);
                String name = c.optString("name", "");
                String num = c.optString("num", "");
                String teacher = c.optString("teacher", "");
                String message = c.optString("message", "");
                bringAppToFront(num, name, teacher, message);
                break;
            }
        } catch (Exception e) {
            Log.w(TAG, "poll 실패: " + e.getMessage());
            setOngoing("점검 필요: " + e.getClass().getSimpleName());
        }
    }

    private boolean canOverlay() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
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
    private void bringAppToFront(String num, String name, String teacher, String message) {
        Intent open = new Intent(this, MainActivity.class);
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
            nm.notify(NOTI_CALL, b.build());
        }

        // "다른 앱 위에 표시" 권한이 있으면 백그라운드에서도 액티비티를 직접 띄울 수 있다(가장 확실)
        boolean canOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
        if (canOverlay) {
            try { startActivity(open); } catch (Exception e) { Log.w(TAG, "startActivity 실패: " + e.getMessage()); }
        }

        // 화면이 떠 있으면 웹이 사용자가 고른 호출음을 낸다 — 그때는 겹치지 않게 조용히 있는다.
        // 화면을 못 띄우는 상황(HDMI 입력 중 등)에서만 서비스가 직접 울린다. 그때는 소리가 유일한 알림이다.
        if (!MainActivity.inForeground) playAlarmOnce();
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
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM);
                int cur = am.getStreamVolume(android.media.AudioManager.STREAM_ALARM);
                if (cur < max * 0.5) {           // 너무 작으면 호출을 놓친다
                    savedAlarmVol = cur;
                    am.setStreamVolume(android.media.AudioManager.STREAM_ALARM, (int) Math.ceil(max * 0.7), 0);
                }
            }
            ringAlarm = playVia(android.media.RingtoneManager.TYPE_ALARM, android.media.AudioAttributes.USAGE_ALARM);
            ringNoti = playVia(android.media.RingtoneManager.TYPE_NOTIFICATION, android.media.AudioAttributes.USAGE_NOTIFICATION);

            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    stopRing(ringAlarm); stopRing(ringNoti);
                    try {   // 볼륨을 건드렸으면 되돌린다
                        android.media.AudioManager m = (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
                        if (m != null && savedAlarmVol >= 0) {
                            m.setStreamVolume(android.media.AudioManager.STREAM_ALARM, savedAlarmVol, 0);
                            savedAlarmVol = -1;
                        }
                    } catch (Exception ignored) { }
                }
            }, 10_000L);
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

        NotificationChannel call = new NotificationChannel(CH_CALL, "학생 호출", NotificationManager.IMPORTANCE_HIGH);
        call.setDescription("교무실에서 학생을 호출했을 때 화면을 띄운다");
        call.enableVibration(false);
        // 예전에는 여기서 소리를 껐다(setSound(null, null)) — "소리는 화면에 뜬 앱이 낸다"는 전제였다.
        // 그런데 HDMI(노트북·중앙방송)를 보고 있으면 앱이 화면에 못 떠서 **소리도 안 난다**(실제 제보).
        // 화면을 못 띄우는 상황일수록 소리가 유일한 알림이므로, 알람 속성으로 직접 울린다.
        try {
            android.net.Uri alarm = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM);
            if (alarm == null) alarm = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION);
            android.media.AudioAttributes attrs = new android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
            call.setSound(alarm, attrs);
        } catch (Exception e) { Log.w(TAG, "호출음 채널 설정 실패: " + e.getMessage()); }
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
