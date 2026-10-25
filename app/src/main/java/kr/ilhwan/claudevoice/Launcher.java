package kr.ilhwan.claudevoice;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

/** Claude 앱을 앞으로 가져오고 접근성 서비스에 할 일을 맡긴다. */
final class Launcher {
    private Launcher() {}

    static void run(Context c, String mode) {
        ClaudeAutomationService svc = ClaudeAutomationService.instance;
        if (svc == null) {
            Toast.makeText(c, "접근성에서 '클로드 도우미'를 켜 주세요.", Toast.LENGTH_LONG).show();
            Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return;
        }
        boolean openedByLink = false;
        if (ClaudeAutomationService.MODE_START.equals(mode)
                || ClaudeAutomationService.MODE_VOICE.equals(mode)) {
            openedByLink = openChatLink(c);
        }
        if (!openedByLink && !openClaude(c)) {
            Toast.makeText(c, "Claude 앱이 설치되어 있지 않습니다.", Toast.LENGTH_LONG).show();
            return;
        }
        svc.begin(mode, openedByLink);
    }

    /** 설정에 대화 링크가 있으면 그 대화로 바로 연다. */
    private static boolean openChatLink(Context c) {
        String url = Prefs.get(c, Prefs.KEY_URL, "");
        if (!url.startsWith("https://claude.ai/")) return false;
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        i.setPackage(Prefs.CLAUDE_PKG);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (i.resolveActivity(c.getPackageManager()) == null) return false;
        try {
            c.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean openClaude(Context c) {
        Intent i = c.getPackageManager().getLaunchIntentForPackage(Prefs.CLAUDE_PKG);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        c.startActivity(i);
        return true;
    }
}
