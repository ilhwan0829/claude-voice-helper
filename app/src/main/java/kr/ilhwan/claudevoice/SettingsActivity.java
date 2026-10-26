package kr.ilhwan.claudevoice;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** 설정·테스트·진단 화면. 레이아웃 파일 없이 코드로 구성. */
public class SettingsActivity extends Activity {

    private TextView status, lastLog, lastDump;
    private EditText title, url, voiceKw, endKw, title2, url2;
    private CheckBox goHome;
    private TextView target;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = dp(16);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, pad * 2);

        col.addView(heading("클로드 도우미 " + version()));
        col.addView(body("빅스비에게 \"딕테이션 열어줘\"(마이크), \"프리토킹 열어줘\"(음파), "
                + "\"척척박사 열어줘\"(지식 대화·음파), \"공부 끝 열어줘\"라고 말하면 해당 Claude 대화를 열고 버튼을 대신 누릅니다."));

        status = body("");
        status.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(status);
        col.addView(button("접근성 설정 열기", v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));

        col.addView(heading("대화 설정"));
        title = field(col, "대화 제목", Prefs.get(this, Prefs.KEY_TITLE, Prefs.DEFAULT_TITLE), false);
        target = body("");
        col.addView(target);
        url = field(col, "대화 링크 (크롬 설치 앱이면 꼭 입력) — 크롬에서 영어공부 대화를 열고 주소창의 claude.ai/chat/… 주소를 복사해 붙여 넣기",
                Prefs.get(this, Prefs.KEY_URL, ""), true);
        col.addView(heading("척척박사 대화 (지식)"));
        title2 = field(col, "대화 제목", Prefs.get(this, Prefs.KEY_TITLE2, Prefs.DEFAULT_TITLE2), false);
        url2 = field(col, "대화 링크 — 지식 대화를 열고 주소창의 claude.ai/… 주소를 복사해 붙여 넣기",
                Prefs.get(this, Prefs.KEY_URL2, ""), true);
        col.addView(heading("고급"));
        voiceKw = field(col, "(선택) 음성 버튼 이름 — 자동으로 못 찾을 때만, 진단 기록의 이름을 그대로 입력",
                Prefs.get(this, Prefs.KEY_VOICE_KW, ""), false);
        endKw = field(col, "(선택) 종료 버튼 이름", Prefs.get(this, Prefs.KEY_END_KW, ""), false);
        goHome = new CheckBox(this);
        goHome.setText("종료 후 홈 화면으로 나가기");
        goHome.setChecked(Prefs.goHome(this));
        col.addView(goHome);
        col.addView(button("저장", v -> save()));

        col.addView(heading("테스트"));
        col.addView(button("시작 테스트", v -> { save(); Launcher.run(this, ClaudeAutomationService.MODE_START); }));
        col.addView(button("척척박사(지식·음파) 테스트", v -> { save(); Launcher.run(this, ClaudeAutomationService.MODE_SEARCH); }));
        col.addView(button("프리토킹(음파) 테스트", v -> { save(); Launcher.run(this, ClaudeAutomationService.MODE_VOICE); }));
        col.addView(button("종료 테스트", v -> { save(); Launcher.run(this, ClaudeAutomationService.MODE_STOP); }));
        col.addView(body("진단: 누르면 Claude가 열립니다. 4초 안에 음성 모드 화면 등 확인할 화면으로 옮겨 두면 "
                + "그 화면의 버튼 이름을 기록합니다."));
        col.addView(button("진단: Claude 화면 기록", v -> Launcher.run(this, ClaudeAutomationService.MODE_DUMP)));

        col.addView(heading("마지막 실행 결과"));
        lastLog = mono();
        col.addView(lastLog);
        col.addView(heading("진단 기록"));
        col.addView(button("실행 결과 + 진단 기록 함께 복사", v ->
                copy(version() + "\n[실행 결과]\n" + lastLog.getText() + "\n[진단 기록]\n" + lastDump.getText())));
        lastDump = mono();
        col.addView(lastDump);

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean on = ClaudeAutomationService.instance != null;
        status.setText(on ? "● 접근성 서비스 켜짐" : "○ 접근성 서비스 꺼짐 — 아래 버튼에서 '클로드 도우미'를 켜 주세요");
        status.setTextColor(on ? 0xFF2E7D4F : 0xFFB3261E);
        Target t = Target.resolve(this);
        target.setText(t == null ? "대상: Claude를 찾지 못함 (크롬에서 claude.ai 앱 설치 필요)" : "대상: " + t.describe());
        lastLog.setText(Prefs.get(this, Prefs.KEY_LAST_LOG, "(아직 없음)"));
        lastDump.setText(Prefs.get(this, Prefs.KEY_LAST_DUMP, "(아직 없음)"));
    }

    private void save() {
        Prefs.put(this, Prefs.KEY_TITLE, title.getText().toString().trim());
        Prefs.put(this, Prefs.KEY_TITLE2, title2.getText().toString().trim());
        Prefs.put(this, Prefs.KEY_URL2, url2.getText().toString().trim());
        Prefs.put(this, Prefs.KEY_URL, url.getText().toString().trim());
        Prefs.put(this, Prefs.KEY_VOICE_KW, voiceKw.getText().toString().trim());
        Prefs.put(this, Prefs.KEY_END_KW, endKw.getText().toString().trim());
        Prefs.sp(this).edit().putBoolean(Prefs.KEY_GO_HOME, goHome.isChecked()).apply();
        Toast.makeText(this, "저장했습니다.", Toast.LENGTH_SHORT).show();
    }

    private String version() {
        try {
            return "v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private void copy(String s) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("claude-dump", s));
        Toast.makeText(this, "복사했습니다. Claude 대화에 붙여 넣어 주세요.", Toast.LENGTH_SHORT).show();
    }

    // ---- 작은 UI 도우미들

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private TextView heading(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(20), 0, dp(6));
        return t;
    }

    private TextView body(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(15);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private TextView mono() {
        TextView t = body("");
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextSize(12);
        t.setTextIsSelectable(true);
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(s);
        btn.setAllCaps(false);
        btn.setOnClickListener(l);
        return btn;
    }

    private EditText field(LinearLayout col, String label, String value, boolean isUrl) {
        TextView l = body(label);
        l.setTextSize(13);
        col.addView(l);
        EditText e = new EditText(this);
        e.setText(value);
        e.setSingleLine(true);
        if (isUrl) e.setInputType(InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_CLASS_TEXT);
        col.addView(e);
        return e;
    }
}
