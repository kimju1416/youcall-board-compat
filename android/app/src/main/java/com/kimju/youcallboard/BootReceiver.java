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
            YouCallService.start(context);
        }
    }
}
