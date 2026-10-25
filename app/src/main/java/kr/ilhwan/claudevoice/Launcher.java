package kr.ilhwan.claudevoice;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

/** Claude(크롬 설치 앱 또는 공식 앱)를 앞으로 가져오고 접근성 서비스에 할 일을 맡긴다. */
final class Launcher {
    private Launcher() {}

    static void run(Context c, String mode) {
        run(c, mode, false);
    }

    /** fromBixby: 빅스비·아이콘으로 실행. 빅스비가 마이크를 놓고 화면이 다 뜰 때까지 더 기다린다. */
    static void run(Context c, String mode, boolean fromBixby) {
        ClaudeAutomationService svc = ClaudeAutomationService.instance;
        if (svc == null) {
            Toast.makeText(c, "접근성에서 '클로드 도우미'를 켜 주세요.", Toast.LENGTH_LONG).show();
            Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return;
        }
        Target t = Target.resolve(c);
        if (t == null) {
            Toast.makeText(c, "Claude를 찾지 못했어요. 크롬에서 claude.ai를 '앱 설치'했는지 확인해 주세요.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        boolean openedByLink = false;
        if (ClaudeAutomationService.MODE_START.equals(mode)
                || ClaudeAutomationService.MODE_VOICE.equals(mode)) {
            openedByLink = openChatLink(c, t, Prefs.KEY_URL);
        } else if (ClaudeAutomationService.MODE_SEARCH.equals(mode)) {
            openedByLink = openChatLink(c, t, Prefs.KEY_URL2);
        }
        if (!openedByLink && !open(c, t)) {
            Toast.makeText(c, "Claude를 열지 못했어요.", Toast.LENGTH_LONG).show();
            return;
        }
        svc.begin(mode, openedByLink, t, fromBixby);
    }

    /** 설정에 대화 링크가 있으면 그 대화로 바로 연다. */
    private static boolean openChatLink(Context c, Target t, String urlKey) {
        String url = Prefs.get(c, urlKey, "");
        if (url.startsWith("claude.ai/")) url = "https://" + url;
        if (!url.startsWith("https://claude.ai/")) return false;
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        i.setPackage(t.pkg);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (i.resolveActivity(c.getPackageManager()) == null) return false;
        try {
            c.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean open(Context c, Target t) {
        Intent i = c.getPackageManager().getLaunchIntentForPackage(t.pkg);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        c.startActivity(i);
        return true;
    }
}
