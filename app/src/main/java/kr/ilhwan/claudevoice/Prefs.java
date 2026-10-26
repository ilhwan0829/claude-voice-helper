package kr.ilhwan.claudevoice;

import android.content.Context;
import android.content.SharedPreferences;

/** 사용자가 설정 화면에서 바꿀 수 있는 값들. */
final class Prefs {
    static final String CLAUDE_PKG = "com.anthropic.claude";

    static final String KEY_TITLE = "chat_title";       // 이동할 대화 제목
    static final String KEY_URL = "chat_url";           // (선택) 대화 링크 https://claude.ai/chat/...
    static final String KEY_VOICE_KW = "voice_kw";      // (선택) 음성 버튼 이름 직접 지정
    static final String KEY_END_KW = "end_kw";          // (선택) 종료 버튼 이름 직접 지정
    static final String KEY_GO_HOME = "go_home";        // 종료 후 홈 화면으로
    static final String KEY_LAST_DUMP = "last_dump";    // 진단 기록
    static final String KEY_LAST_LOG = "last_log";      // 마지막 실행 결과

    static final String DEFAULT_TITLE = "영어공부";

    // '지식백과'용 두 번째 대화(지식)
    static final String KEY_TITLE2 = "chat_title_knowledge";
    static final String KEY_URL2 = "chat_url_knowledge";
    static final String DEFAULT_TITLE2 = "지식";

    private Prefs() {}

    static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    static String get(Context c, String key, String def) {
        String v = sp(c).getString(key, def);
        return v == null ? def : v.trim();
    }

    static void put(Context c, String key, String value) {
        sp(c).edit().putString(key, value).apply();
    }

    static boolean goHome(Context c) {
        return sp(c).getBoolean(KEY_GO_HOME, true);
    }
}
