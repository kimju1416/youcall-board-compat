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

        askOverlayPermissionIfNeeded();
    }

    /**
     * 다른 앱(수업자료·인터넷 등)을 쓰는 중에 호출이 오면 이 화면이 스스로 앞으로 나와야 하는데,
     * 안드로이드는 그걸 '다른 앱 위에 표시' 권한이 있을 때만 허용한다.
     * 사용자가 직접 켜야 하는 권한이라 앱에서 설정 화면까지 안내한다.
     */
    private void askOverlayPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        if (Settings.canDrawOverlays(this)) return;

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
    }
}
