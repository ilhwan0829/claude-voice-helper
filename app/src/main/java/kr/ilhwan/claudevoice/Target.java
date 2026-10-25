package kr.ilhwan.claudevoice;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 어떤 Claude를 열지 정한다.
 * 크롬에서 '앱 설치'한 Claude(웹앱, org.chromium.webapk.…)가 있으면 그것을 우선 쓰고,
 * 없으면 Claude 공식 앱(com.anthropic.claude)을 쓴다.
 */
final class Target {
    static final String CHROME_PKG = "com.android.chrome";

    final String pkg;       // 실행할 패키지
    final boolean isWeb;    // 크롬 설치 앱 여부
    final String label;

    private Target(String pkg, boolean isWeb, String label) {
        this.pkg = pkg;
        this.isWeb = isWeb;
        this.label = label;
    }

    /** 화면을 읽어도 되는 패키지들. 웹앱 화면은 크롬이 그리므로 크롬도 포함. */
    Set<String> windowPackages() {
        Set<String> s = new HashSet<>();
        s.add(pkg);
        if (isWeb) s.add(CHROME_PKG);
        return s;
    }

    static Target resolve(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(main, 0);
        Target web = null;
        for (ResolveInfo ri : apps) {
            String p = ri.activityInfo.packageName;
            if (p.equals(c.getPackageName()) || p.equals(Prefs.CLAUDE_PKG)) continue;
            CharSequence l = ri.loadLabel(pm);
            String label = l == null ? "" : l.toString();
            if (!label.toLowerCase(Locale.ROOT).contains("claude")) continue;
            boolean webApk = p.startsWith("org.chromium.webapk") || p.contains(".webapk");
            if (webApk) return new Target(p, true, label);
            if (web == null) web = new Target(p, true, label);
        }
        if (web != null) return web;
        if (pm.getLaunchIntentForPackage(Prefs.CLAUDE_PKG) != null) {
            return new Target(Prefs.CLAUDE_PKG, false, "Claude 앱");
        }
        return null;
    }

    String describe() {
        return (isWeb ? "크롬 설치 앱: " : "공식 앱: ") + label + " (" + pkg + ")";
    }
}
