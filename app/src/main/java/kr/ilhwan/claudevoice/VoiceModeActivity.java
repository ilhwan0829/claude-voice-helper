package kr.ilhwan.claudevoice;

import android.app.Activity;
import android.os.Bundle;

/** "영어회화모드" 아이콘: 영어공부 대화를 열고 마이크 오른쪽 음파(음성 대화) 버튼을 누른다. */
public class VoiceModeActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Launcher.run(this, ClaudeAutomationService.MODE_VOICE);
        finish();
    }
}
