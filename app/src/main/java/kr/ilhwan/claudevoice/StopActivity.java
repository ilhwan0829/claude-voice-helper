package kr.ilhwan.claudevoice;

import android.app.Activity;
import android.os.Bundle;

/** "영어회화 종료" 아이콘. */
public class StopActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Launcher.run(this, ClaudeAutomationService.MODE_STOP, true);
        finish();
    }
}
