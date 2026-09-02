package com.kimju.youcallboard;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.WindowManager;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 상시 대기화면 용도라 화면이 꺼지면 안 된다.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // 호출이 오면 화면이 꺼져 있거나 잠겨 있어도 위로 올라와야 한다.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            );
        }

        // 화면을 닫아도 호출 감시가 계속되도록 상주 서비스를 올린다(윈도우판의 트레이 상주와 같은 역할).
        YouCallService.start(this);

        // 한 번에 하나만 안내한다(다이얼로그가 겹치면 아무도 안 읽는다).
        if (!askOverlayPermissionIfNeeded()) askBatteryExemptionIfNeeded();
    }

    /**
     * HDMI 입력 중에는 안드로이드 화면이 꺼진 것과 같아 시스템이 절전(Doze)에 들어간다.
     * 그러면 상주 서비스의 네트워크가 끊겨 호출을 아예 못 받고, 화면을 깨우는 순간
     * 밀린 호출이 한꺼번에 뜬다. 이 앱을 절전 대상에서 빼야 계속 감시할 수 있다.
     */
    private void askBatteryExemptionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) return;
        } catch (Exception e) { return; }

        new AlertDialog.Builder(this)
            .setTitle("한 가지 설정이 더 필요합니다")
            .setMessage(
                "HDMI(노트북·중앙방송)를 보고 있는 동안에도 호출을 받으려면\n"
                    + "이 앱을 '절전 대상에서 제외'해야 합니다.\n\n"
                    + "설정하지 않으면 화면을 다른 데로 돌린 동안 호출이 오지 않다가,\n"
                    + "홈으로 나올 때 밀린 호출이 한꺼번에 뜹니다."
            )
            .setPositiveButton("설정 열기", (d, w) -> {
                try {
                    Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Exception ignored) {
                    try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
                    catch (Exception ignored2) { }
                }
            })
            .setNegativeButton("나중에", null)
            .show();
    }

    /**
     * 다른 앱(수업자료·인터넷 등)을 쓰는 중에 호출이 오면 이 화면이 스스로 앞으로 나와야 하는데,
     * 안드로이드는 그걸 '다른 앱 위에 표시' 권한이 있을 때만 허용한다.
     * 사용자가 직접 켜야 하는 권한이라 앱에서 설정 화면까지 안내한다.
     */
    private boolean askOverlayPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false;
        if (Settings.canDrawOverlays(this)) return false;

        new AlertDialog.Builder(this)
            .setTitle("한 가지 설정이 더 필요합니다")
            .setMessage(
                "다른 화면(수업자료·인터넷 등)을 보고 있을 때도 호출이 자동으로 뜨게 하려면\n"
                    + "'다른 앱 위에 표시' 권한을 켜야 합니다.\n\n"
                    + "설정 열기 → 유콜 보드 → 허용으로 바꿔주세요.\n"
                    + "(켜지 않아도 호출 알림은 화면 위쪽에 표시됩니다)"
            )
            .setPositiveButton("설정 열기", (d, w) -> {
                try {
                    startActivity(new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())
                    ));
                } catch (Exception ignored) {
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
                }
            })
            .setNegativeButton("나중에", null)
            .show();
        return true;
    }
}
