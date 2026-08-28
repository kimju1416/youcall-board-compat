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

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
        startForeground(NOTI_ONGOING, buildOngoingNotification("호출 대기 중"));
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
            if (raw == null) return; // 아직 설정 전

            JSONObject cfg = new JSONObject(raw);
            String base = cfg.optString("webAppUrl", "");
            String grade = cfg.optString("grade", "");
            String classNum = cfg.optString("classNum", "");
            if (base.isEmpty() || grade.isEmpty() || classNum.isEmpty()) return;

            String url = base
                + (base.contains("?") ? "&" : "?")
                + "api=calls"
                + "&grade=" + URLEncoder.encode(grade, "UTF-8")
                + "&classNum=" + URLEncoder.encode(classNum, "UTF-8");

            String body = httpGet(url);
            if (body == null) return;

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
        }
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

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(NOTI_CALL, b.build());

        // "다른 앱 위에 표시" 권한이 있으면 백그라운드에서도 액티비티를 직접 띄울 수 있다(가장 확실)
        boolean canOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
        if (canOverlay) {
            try { startActivity(open); } catch (Exception e) { Log.w(TAG, "startActivity 실패: " + e.getMessage()); }
        }
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
        call.setSound(null, null); // 소리는 화면에 뜬 앱이 설정된 호출음으로 재생한다
        nm.createNotificationChannel(call);
    }

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, YouCallService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
        else ctx.startService(i);
    }
}
