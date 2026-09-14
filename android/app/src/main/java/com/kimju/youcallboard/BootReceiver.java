package com.kimju.youcallboard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 전자칠판을 껐다 켜도 사람이 앱을 다시 실행할 필요가 없도록, 부팅 후 상주 서비스를 자동으로 올린다. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
            || "android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            // Android 15(targetSdk 35+)는 부팅 직후 dataSync 포그라운드 서비스 시작을 막고
            // ForegroundServiceStartNotAllowedException을 던진다. 잡지 않으면 칠판을 켤 때마다 앱이 꺼진다.
            // 그 칠판에서는 부팅 자동 시작만 안 되고, 앱을 한 번 열면 서비스가 올라온다(MainActivity.onCreate).
            try { YouCallService.start(context); }
            catch (Exception e) { android.util.Log.w("BootReceiver", "부팅 자동 시작 실패: " + e); }
        }
    }
}
