package kr.ilhwan.claudevoice;

import android.app.Activity;
import android.os.Bundle;

/** "영어회화 시작" 아이콘. 화면 없이 바로 작업을 넘기고 닫힌다. */
public class StartActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Launcher.run(this, ClaudeAutomationService.MODE_START);
        finish();
    }
}
