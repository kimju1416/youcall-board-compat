package com.kimju.youcallboard;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 전자칠판이 HDMI(노트북·중앙방송)를 보여주는 동안에는 안드로이드 화면이 어디에도 안 나온다.
 * 앱이 호출 화면을 띄워도 못 보는 이유다. 이걸 풀려면 **칠판의 입력을 안드로이드로 되돌려야** 하는데,
 * 안드로이드 표준에는 그런 명령이 없고 제조사마다 방식이 다르며 문서도 없다.
 *
 * 그래서 **기기에서 직접 찾는다.** 칠판에도 「신호 소스」를 담당하는 앱이 반드시 설치돼 있으므로,
 * 설치된 시스템 앱들을 훑어 그 후보를 골라내고 호출이 올 때 순서대로 시도한다.
 * 맞는 게 하나라도 있으면 칠판이 스스로 안드로이드로 돌아온다.
 *
 * **안전이 우선이다.** 수업 중에 엉뚱한 앱이 뜨면 안 되므로
 *   · 제조사가 심은 **시스템 앱만** 본다(사용자가 설치한 앱은 절대 건드리지 않는다),
 *   · 이름이 입력/소스 전환으로 읽히는 것만 남기고,
 *   · 대놓고 위험한 이름(설정 전체·공장초기화·업데이트 등)은 걸러내며,
 *   · 무엇을 찾았고 무엇을 시도했는지 전부 기록해 앱 화면에서 확인할 수 있게 한다.
 */
final class SourceSwitcher {

    private static final String TAG = "SourceSwitcher";

    /** 찾은 후보와 시도 결과를 여기에 남긴다. 앱 화면(설정)에서 그대로 읽어 보여준다. */
    static final String KEY_REPORT = "yc_source_report";

    /**
     * "입력 전환"으로 볼 이름들. **반드시 복합어여야 한다.**
     * 처음에는 "source" 하나만으로 걸렀다가 구글 플레이서비스의 `SourceNfcHandlerActivity`를
     * 실행해 버렸다(검증에서 잡음). 수업 중에 엉뚱한 화면이 뜨는 것은 무반응보다 나쁘다.
     */
    private static final String[] HINTS = {
        "inputsource", "sourceinput", "sourcemenu", "sourcelist", "sourceselect",
        "sourceswitch", "switchsource", "changesource", "selectsource",
        "signalsource", "signalinput", "inputselect", "inputswitch", "selectinput",
        "hdmiswitch", "hdmiinput", "tvsource", "tvinput", "passthrough"
    };

    /** 이런 낱말이 보이면 건드리지 않는다. 잘못 열면 수업을 망친다. */
    private static final String[] BLOCK = {
        "factory", "reset", "wipe", "recovery", "update", "upgrade", "flash",
        "provision", "setupwizard", "install", "uninstall", "developer",
        "wifi", "bluetooth", "account", "password", "lock", "storage",
        "nfc", "auth", "d2d", "backup", "restore", "sync", "gms", "playstore"
    };

    /**
     * 이 패키지들에는 애초에 입력 전환이 없다. 제조사 앱만 보려는 것이므로 통째로 제외한다.
     * (구글/안드로이드 표준 앱이 이름만 비슷해 걸리는 것을 막는다.)
     */
    private static final String[] BLOCK_PKG = {
        "com.google.", "com.android.vending", "com.android.settings",
        "com.android.systemui", "com.android.providers", "com.android.server"
    };

    /**
     * 안드로이드 TV 계열이 쓰는 표준 통로. 칠판이 이 프레임워크 위에 있으면 이것만으로 전환된다.
     * 없으면 조용히 실패한다(해가 없다).
     */
    private static final String[] TV_PASSTHROUGH_URIS = {
        "content://android.media.tv/passthrough",
    };

    /**
     * 여러 제조사 펌웨어에서 쓰인다고 알려진 브로드캐스트들. 받는 쪽이 없으면 그냥 무시되므로
     * 쏘아 보는 것 자체는 안전하다. 맞는 게 있으면 그 즉시 입력이 바뀐다.
     */
    private static final String[] BROADCASTS = {
        "android.intent.action.SOURCE_CHANGED",
        "android.intent.action.CHANGE_SOURCE",
        "com.android.action.CHANGE_SOURCE",
        "com.android.action.SOURCE_CHANGE",
        "com.tv.action.SOURCE_ANDROID",
        "android.intent.action.TV_SOURCE_ANDROID",
    };

    private SourceSwitcher() { }

    /**
     * 칠판 입력을 안드로이드로 되돌리도록 시도한다. **무엇 하나 성공하지 못해도 해가 없다.**
     * @return 시도한 것들의 사람이 읽을 수 있는 기록
     */
    static String tryReturnToAndroid(Context ctx) {
        StringBuilder log = new StringBuilder();

        // ① 알려진 브로드캐스트를 쏜다. 받는 쪽이 없으면 아무 일도 일어나지 않는다.
        for (String action : BROADCASTS) {
            try {
                ctx.sendBroadcast(new Intent(action));
                log.append("broadcast ").append(action).append(" / 보냄\n");
            } catch (Exception e) {
                log.append("broadcast ").append(action).append(" / 실패\n");
            }
        }

        // ② 안드로이드 TV 표준 통로
        for (String uri : TV_PASSTHROUGH_URIS) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (i.resolveActivity(ctx.getPackageManager()) != null) {
                    ctx.startActivity(i);
                    log.append("tv passthrough / 실행\n");
                }
            } catch (Exception ignored) { }
        }

        // ③ 기기에서 찾아낸 "입력 전환" 후보들을 순서대로 연다.
        //    가장 그럴듯한 것부터 하나만 시도한다 — 여러 개를 연달아 띄우면 화면이 어지러워진다.
        List<ComponentName> cands = findCandidates(ctx);
        if (!cands.isEmpty()) {
            ComponentName cn = cands.get(0);
            try {
                Intent i = new Intent();
                i.setComponent(cn);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                ctx.startActivity(i);
                log.append("activity ").append(cn.flattenToShortString()).append(" / 실행\n");
            } catch (Exception e) {
                log.append("activity ").append(cn.flattenToShortString()).append(" / 실패: ")
                   .append(e.getClass().getSimpleName()).append("\n");
            }
        } else {
            log.append("입력 전환 후보를 찾지 못함\n");
        }

        Log.i(TAG, log.toString());
        return log.toString();
    }

    /**
     * 설치된 **시스템 앱** 중 입력 전환을 담당할 만한 액티비티를 찾는다.
     * 사용자가 설치한 앱은 아예 보지 않는다.
     */
    static List<ComponentName> findCandidates(Context ctx) {
        List<ComponentName> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        try {
            PackageManager pm = ctx.getPackageManager();
            List<PackageInfo> pkgs = pm.getInstalledPackages(PackageManager.GET_ACTIVITIES);
            for (PackageInfo p : pkgs) {
                if (p.applicationInfo == null || p.activities == null) continue;
                boolean isSystem = (p.applicationInfo.flags
                    & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
                if (!isSystem) continue;                       // 제조사가 심은 것만 본다
                if (p.packageName.equals(ctx.getPackageName())) continue;
                if (blockedPkg(p.packageName)) continue;       // 구글·안드로이드 표준 앱은 아예 제외

                for (ActivityInfo a : p.activities) {
                    if (!a.exported || !a.enabled) continue;   // 밖에서 열 수 있는 것만
                    String hay = (p.packageName + "." + a.name).toLowerCase();
                    if (blocked(hay)) continue;
                    if (!hinted(hay)) continue;
                    String key = p.packageName + "/" + a.name;
                    if (seen.add(key)) out.add(new ComponentName(p.packageName, a.name));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "후보 탐색 실패: " + e.getMessage());
        }
        return out;
    }

    private static boolean hinted(String hay) {
        for (String h : HINTS) if (hay.contains(h)) return true;
        return false;
    }

    private static boolean blocked(String hay) {
        for (String b : BLOCK) if (hay.contains(b)) return true;
        return false;
    }

    private static boolean blockedPkg(String pkg) {
        String p = pkg.toLowerCase();
        for (String b : BLOCK_PKG) if (p.startsWith(b) || p.equals(b)) return true;
        return false;
    }

    /**
     * 이 칠판에서 무엇을 찾았는지 사람이 읽을 수 있게 정리해 저장한다.
     * 앱 화면에서 그대로 보여주므로, 자동 전환이 안 되더라도
     * **무엇을 시도하면 되는지**는 알 수 있다(다음 판에서 정확히 겨냥할 수 있다).
     */
    static void writeReport(Context ctx, android.content.SharedPreferences sp, String lastTry) {
        try {
            String keepTry = "";
            if (lastTry == null) {                       // 시작할 때는 후보만 갱신하고 시도 기록은 보존한다
                try {
                    JSONObject prev = new JSONObject(sp.getString(KEY_REPORT, "{}"));
                    keepTry = prev.optString("lastTry", "");
                } catch (Exception ignored) { }
            }
            JSONObject o = new JSONObject();
            JSONArray arr = new JSONArray();
            for (ComponentName cn : findCandidates(ctx)) arr.put(cn.flattenToShortString());
            o.put("candidates", arr);
            o.put("lastTry", lastTry == null ? keepTry : lastTry);
            o.put("at", System.currentTimeMillis());
            sp.edit().putString(KEY_REPORT, o.toString()).apply();
        } catch (Exception ignored) { }
    }
}
