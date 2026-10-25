package kr.ilhwan.claudevoice;

import android.app.Activity;
import android.os.Bundle;

/** "클로드검색" 아이콘: '지식' 대화를 열고 음파(음성 대화) 버튼을 누른다. */
public class SearchActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Launcher.run(this, ClaudeAutomationService.MODE_SEARCH, true);
        finish();
    }
}
