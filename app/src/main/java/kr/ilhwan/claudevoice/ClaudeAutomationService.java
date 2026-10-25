package kr.ilhwan.claudevoice;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Claude 앱 화면을 읽고 버튼을 대신 눌러 주는 접근성 서비스.
 *
 * 시작: (대화 링크로 열었거나) 사이드 메뉴에서 '영어공부' 대화를 찾아 열고 → 입력창 왼쪽 아래 마이크 버튼을 누른다.
 * 종료: 받아쓰기 중지 버튼을 누르고(없으면 뒤로 가기) → 홈 화면으로 나간다.
 * 진단: 지금 Claude 화면의 버튼 이름 목록을 기록한다(버튼을 못 찾을 때 이름을 맞추는 용도).
 */
public class ClaudeAutomationService extends AccessibilityService {

    static final String MODE_START = "start";
    static final String MODE_STOP = "stop";
    static final String MODE_DUMP = "dump";
    static final String MODE_VOICE = "voice";   // 마이크 오른쪽 음파(음성 대화 모드) 버튼
    static final String MODE_SEARCH = "search"; // '지식' 대화에서 음파 버튼(클로드검색)

    static volatile ClaudeAutomationService instance;

    // 입력창 왼쪽 아래 두 번째 '마이크(받아쓰기)' 버튼 후보(앞쪽일수록 우선).
    // 오른쪽의 음파(음성 대화 모드) 버튼은 제외 목록으로 거른다.
    private static final String[] VOICE_KW = {
            "dictate", "dictation", "start dictation", "받아쓰기", "음성 입력", "voice input",
            "microphone", "마이크", "mic", "record", "녹음", "speech to text"
    };
    private static final String[] VOICE_EXCLUDE = {
            "voice mode", "음성 모드", "음성 대화", "start voice"
    };
    // 마이크 오른쪽 음파(음성 대화 모드) 버튼 후보
    private static final String[] VOICEMODE_KW = {
            "voice mode", "start voice", "음성 모드", "음성 대화", "voice"
    };
    private static final String[] VOICEMODE_EXCLUDE = {
            "dictat", "받아쓰기", "음성 입력", "voice input", "microphone", "마이크", "speech to text"
    };
    // 받아쓰기 중지(또는 음성 화면 닫기) 버튼 후보
    private static final String[] END_KW = {
            "stop dictation", "stop recording", "받아쓰기 중지", "녹음 중지", "중지", "정지", "완료",
            "stop", "done", "종료", "끝내기", "end", "close", "닫기"
    };
    // 사이드 메뉴(대화 목록) 열기 버튼 후보
    private static final String[] MENU_KW = {
            "open menu", "open drawer", "open navigation", "sidebar", "chats", "menu",
            "메뉴", "사이드바", "대화 목록", "채팅 목록", "탐색 창 열기"
    };

    private static final long TICK_MS = 500;
    private static final long START_TIMEOUT_MS = 30000;
    private static final long STOP_TIMEOUT_MS = 6000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private String mode;
    private long startedAt;
    private long waitUntil;
    private int step;           // 시작 모드의 진행 단계
    private boolean menuTried;
    private int scrolls;
    private boolean viaLink;
    private boolean bixby;
    private boolean wantVoiceMode;
    private boolean secondChat;     // true면 '지식' 대화  // true면 음파 버튼, false면 마이크 버튼
    private final StringBuilder log = new StringBuilder();

    private static final int STEP_FIND_CHAT = 0;
    private static final int STEP_PRESS_VOICE = 1;

    // ---------------------------------------------------------------- 수명주기

    @Override
    protected void onServiceConnected() {
        instance = this;
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 화면 변화는 tick()에서 주기적으로 확인하므로 이벤트는 쓰지 않는다.
    }

    @Override
    public void onInterrupt() {}

    // ---------------------------------------------------------------- 작업 시작

    private Set<String> targetPkgs = new HashSet<>(Collections.singleton(Prefs.CLAUDE_PKG));

    void begin(String newMode, boolean openedByLink, Target target, boolean fromBixby) {
        targetPkgs = target.windowPackages();
        handler.removeCallbacksAndMessages(null);
        mode = newMode;
        viaLink = openedByLink;
        wantVoiceMode = MODE_VOICE.equals(newMode) || MODE_SEARCH.equals(newMode);
        secondChat = MODE_SEARCH.equals(newMode);
        startedAt = SystemClock.uptimeMillis();
        // Claude 앱이 뜰 시간. 진단은 사용자가 원하는 화면으로 옮길 수 있게 4초 기다린다.
        // 빅스비로 실행하면 빅스비 창이 닫히고 마이크를 놓을 때까지 3초 기다린다.
        waitUntil = startedAt + (MODE_DUMP.equals(newMode) ? 4000 : fromBixby ? 3000 : 1200);
        bixby = fromBixby;
        step = openedByLink ? STEP_PRESS_VOICE : STEP_FIND_CHAT;
        menuTried = false;
        scrolls = 0;
        voiceStepSince = 0;
        loadingSince = 0;
        titleWaitSince = 0;
        relinked = false;
        titleMissNoted = false;
        stopClicks = 0;
        log.setLength(0);
        note("시작: " + newMode + (fromBixby ? " (빅스비)" : " (테스트)") + " / " + target.describe() + (openedByLink ? " / 대화 링크로 열기" : ""));
        handler.postDelayed(this::tick, TICK_MS);
    }

    private void finishJob(boolean ok, String message) {
        note((ok ? "완료: " : "실패: ") + message);
        String stamp = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA).format(new Date());
        Prefs.put(this, Prefs.KEY_LAST_LOG, stamp + "\n" + log);
        if (!ok) {
            saveDump();
            toast(message + "\n설정 앱 > 진단 기록을 확인해 주세요.");
        }
        mode = null;
    }

    private void tick() {
        if (mode == null) return;
        long now = SystemClock.uptimeMillis();
        if (now < waitUntil) {
            handler.postDelayed(this::tick, TICK_MS);
            return;
        }
        try {
            switch (mode) {
                case MODE_START:
                case MODE_VOICE:
                case MODE_SEARCH: tickStart(now); break;
                case MODE_STOP: tickStop(now); break;
                case MODE_DUMP:
                    saveDump();
                    finishJob(true, "진단 기록 저장");
                    toast("Claude 화면 기록을 저장했어요. '클로드 도우미 설정'에서 확인하세요.");
                    break;
            }
        } catch (Exception e) {
            finishJob(false, "오류 " + e.getClass().getSimpleName());
        }
        if (mode != null) handler.postDelayed(this::tick, TICK_MS);
    }

    // ---------------------------------------------------------------- 시작 흐름

    private void tickStart(long now) {
        if (now - startedAt > START_TIMEOUT_MS) {
            finishJob(false, step == STEP_FIND_CHAT
                    ? "'" + title() + "' 대화를 찾지 못했어요."
                    : (wantVoiceMode ? "음파 버튼을 찾지 못했어요." : "마이크 버튼을 찾지 못했어요."));
            return;
        }
        List<AccessibilityNodeInfo> nodes = claudeNodes();
        if (nodes.isEmpty()) return;  // 아직 Claude 화면이 아님

        if (step == STEP_FIND_CHAT) {
            AccessibilityNodeInfo titleNode = findTitle(nodes, BELOW_TOP);
            AccessibilityNodeInfo voice = findVoice(nodes);
            if (voice != null && findTitle(nodes, TOP) != null) {
                note("이미 '" + title() + "' 대화 화면");
                step = STEP_PRESS_VOICE;
                return;
            }
            if (titleNode != null && click(titleNode)) {
                note("'" + title() + "' 대화 열기");
                step = STEP_PRESS_VOICE;
                waitUntil = now + 1500;
                return;
            }
            if (!menuTried) {
                menuTried = true;
                AccessibilityNodeInfo menu = findAny(nodes, MENU_KW, null, true);
                if (menu != null && click(menu)) {
                    note("대화 목록 열기: " + label(menu));
                    waitUntil = now + 900;
                    return;
                }
                // 메뉴 버튼이 없으면 왼쪽 가장자리에서 밀어서 열어 본다.
                swipeFromLeft();
                note("왼쪽에서 밀어서 대화 목록 열기 시도");
                waitUntil = now + 900;
                return;
            }
            if (scrolls < 6) {
                AccessibilityNodeInfo list = findScrollable(nodes);
                if (list != null) {
                    list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                    scrolls++;
                    note("대화 목록 스크롤 " + scrolls);
                    waitUntil = now + 600;
                }
            }
            return;
        }

        // STEP_PRESS_VOICE
        if (bixby && voiceStepSince == 0) {
            voiceStepSince = now;
            waitUntil = now + 1000;   // 채팅 화면이 다 그려질 때까지 한 번 더 대기
            note("빅스비 실행: 1초 추가 대기");
            return;
        }
        // 대화가 아직 불러오는 중이면 버튼 이름이 비어 있으므로 기다린다.
        // 단, 사이드바 등에 '불러오는 중' 문구가 계속 남아 있을 수 있어 최대 4초만 기다린다.
        if (isLoading(nodes)) {
            if (loadingSince == 0) { loadingSince = now; note("불러오는 중 문구 보임 → 최대 4초 대기"); }
            if (now - loadingSince < 4000) return;
        }
        // 지금 화면이 목표 대화인지: 위쪽 제목줄에 대화 이름이 보여야 한다.
        if (findTitle(nodes, TOP) == null) {
            if (titleWaitSince == 0) titleWaitSince = now;
            long waited = now - titleWaitSince;
            if (viaLink && !relinked && waited > 1500) {
                // 창이 닫혀 있다 처음 켜지면 링크를 놓치고 첫 화면에 머문다 → 링크를 한 번 더 보낸다.
                relinked = true;
                titleWaitSince = 0;
                Launcher.reopenLastLink(this);
                note("대화 화면이 아님 → 대화 링크 다시 열기");
                waitUntil = now + 2500;
                return;
            }
            // 창이 닫혀 있다 켜진 경우 claude.ai가 다 뜨는 데 오래 걸리므로 8초까지 기다린다.
            if (viaLink && relinked && waited > 8000) {
                viaLink = false;
                step = STEP_FIND_CHAT;
                menuTried = false;
                titleWaitSince = 0;
                note("링크로도 안 열림 → 대화 목록에서 '" + title() + "' 찾기");
                return;
            }
            if (waited < 4000) return;
            if (!titleMissNoted) { titleMissNoted = true; note("위쪽에 대화 제목이 안 보임 → 그대로 진행"); }
        }
        AccessibilityNodeInfo voice = findVoice(nodes);
        if (voice == null && stepStartedLongAgo(now)) {
            // 이름으로 못 찾으면 위치로: 입력창 아래 줄 왼쪽부터 ＋(1) 마이크(2) 음파(3)
            AccessibilityNodeInfo micByPos = bottomLeftButton(nodes, 1);
            voice = wantVoiceMode ? rightNeighbor(nodes, micByPos) : micByPos;
            if (voice != null) note("이름 대신 위치로 찾음(" + (wantVoiceMode ? "＋ 다음 마이크의 오른쪽" : "＋ 다음 버튼") + ")");
        }
        if (voice != null) saveDump();   // 무엇을 눌렀는지 항상 기록(진단용)
        if (voice != null && (wantVoiceMode ? tap(voice) : click(voice))) {
            note("누른 위치: " + center(voice));
            finishJob(true, (wantVoiceMode ? "음파 버튼 누름: " : "마이크 버튼 누름: ") + label(voice));
            return;
        }
        // 링크로 열었는데 6초가 지나도 버튼이 없으면 목록에서 찾는 방식으로 전환
        if (viaLink && now - startedAt > 6000) {
            viaLink = false;
            step = STEP_FIND_CHAT;
            note("링크로 열기 실패 → 대화 목록에서 찾기");
        }
    }

    // ---------------------------------------------------------------- 종료 흐름

    private int stopClicks;

    /**
     * 종료: 받아쓰기(마이크)든 음성 대화(음파)든 켜져 있는 것을 모두 끈다.
     * 종료·중지 버튼이 보이면 누르고 잠시 뒤 다시 확인(최대 3번) → 더 없으면 뒤로 가기 → 홈 화면.
     */
    private void tickStop(long now) {
        List<AccessibilityNodeInfo> nodes = claudeNodes();
        String custom = Prefs.get(this, Prefs.KEY_END_KW, "");
        AccessibilityNodeInfo end = custom.isEmpty()
                ? findAny(nodes, END_KW, null, true)
                : findAny(nodes, new String[]{custom.toLowerCase(Locale.ROOT)}, null, true);
        if (end != null && stopClicks < 3 && click(end)) {
            stopClicks++;
            note("종료 버튼 누름: " + label(end));
            waitUntil = now + 700;   // 음성 화면이 닫히고 받아쓰기도 남았는지 다시 확인
            return;
        }
        if (stopClicks == 0 && now - startedAt < 2500) return;  // 화면이 뜰 때까지 조금 더 기다림
        // 종료 버튼을 못 찾았거나 다 눌렀으면 뒤로 가기로 남은 화면을 닫고 나간다.
        performGlobalAction(GLOBAL_ACTION_BACK);
        note("뒤로 가기");
        leave(600);
        finishJob(true, "영어공부 종료 (버튼 " + stopClicks + "번)");
    }

    private void leave(long delay) {
        if (!Prefs.goHome(this)) return;
        handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_HOME), delay);
    }

    // ---------------------------------------------------------------- 화면 찾기

    private String title() {
        String def = secondChat ? Prefs.DEFAULT_TITLE2 : Prefs.DEFAULT_TITLE;
        String t = Prefs.get(this, secondChat ? Prefs.KEY_TITLE2 : Prefs.KEY_TITLE, def);
        return t.isEmpty() ? def : t;
    }

    /** Claude 창(크롬 설치 앱이면 크롬이 그리는 웹 화면, 음성 오버레이 포함)의 모든 노드. */
    private List<AccessibilityNodeInfo> claudeNodes() {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows != null) {
            for (AccessibilityWindowInfo w : windows) {
                AccessibilityNodeInfo root = w.getRoot();
                if (isTarget(root)) collect(root, out, 0);
            }
        }
        if (out.isEmpty()) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (isTarget(root)) collect(root, out, 0);
        }
        return out;
    }

    private boolean isTarget(AccessibilityNodeInfo root) {
        return root != null && root.getPackageName() != null
                && targetPkgs.contains(root.getPackageName().toString());
    }

    private void collect(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out, int depth) {
        if (n == null || depth > 60) return;
        out.add(n);
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), out, depth + 1);
    }

    private static String label(AccessibilityNodeInfo n) {
        CharSequence d = n.getContentDescription();
        CharSequence t = n.getText();
        String s = (d != null && d.length() > 0) ? d.toString() : (t != null ? t.toString() : "");
        return s.trim();
    }

    private static final int ANY = 0, TOP = 1, BELOW_TOP = 2;

    private static String squash(String s) {
        return s.replaceAll("\\s+", "");
    }

    /** 대화 제목 찾기(띄어쓰기 무시). where: ANY / TOP(위쪽 제목줄) / BELOW_TOP(목록 등). */
    private AccessibilityNodeInfo findTitle(List<AccessibilityNodeInfo> nodes, int where) {
        String want = squash(title());
        AccessibilityNodeInfo partial = null;
        for (AccessibilityNodeInfo n : nodes) {
            if (!n.isVisibleToUser()) continue;
            String l = squash(label(n));
            if (l.isEmpty() || n.isEditable()) continue;
            if (where == TOP && !inTopArea(n)) continue;
            if (where == BELOW_TOP && inTopArea(n)) continue;
            if (l.equals(want)) return n;
            if (partial == null && l.startsWith(want) && l.length() <= want.length() + 30) partial = n;
        }
        return partial;
    }

    private AccessibilityNodeInfo findVoice(List<AccessibilityNodeInfo> nodes) {
        String custom = Prefs.get(this, Prefs.KEY_VOICE_KW, "");
        if (!custom.isEmpty() && !wantVoiceMode) {
            return findAny(nodes, new String[]{custom.toLowerCase(Locale.ROOT)}, null, true);
        }
        if (!wantVoiceMode) return findAny(nodes, VOICE_KW, VOICE_EXCLUDE, true);
        // 음파 버튼 = 마이크 버튼 바로 오른쪽 버튼(같은 줄). 이름보다 위치를 먼저 본다.
        AccessibilityNodeInfo mic = findAny(nodes, VOICE_KW, VOICE_EXCLUDE, true);
        if (mic != null) {
            AccessibilityNodeInfo right = rightNeighbor(nodes, clickableOf(mic));
            if (right != null) return right;
        }
        // 마이크를 못 찾으면 이름으로: 옵션(⌄)·설정 버튼은 제외
        for (String kw : VOICEMODE_KW) {
            for (AccessibilityNodeInfo n : nodes) {
                if (!n.isVisibleToUser() || n.isEditable()) continue;
                String l = label(n).toLowerCase(Locale.ROOT);
                if (l.isEmpty() || l.length() > 60 || !matches(l, kw)) continue;
                if (containsAny(l, VOICEMODE_EXCLUDE) || containsAny(l, OPTION_WORDS)) continue;
                if (clickableOf(n) != null) return n;
            }
        }
        return null;
    }

    private static final String[] OPTION_WORDS = {
            "option", "setting", "select", "choose", "more", "menu", "dropdown", "expand",
            "옵션", "설정", "선택", "더보기", "메뉴", "펼치기", "목소리"
    };

    /** 기준 버튼과 같은 줄에서 오른쪽으로 가장 가까운 버튼. */
    private AccessibilityNodeInfo rightNeighbor(List<AccessibilityNodeInfo> nodes, AccessibilityNodeInfo base) {
        if (base == null) return null;
        Rect b = new Rect();
        base.getBoundsInScreen(b);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        AccessibilityNodeInfo best = null;
        int bestDx = Integer.MAX_VALUE;
        Rect r = new Rect();
        for (AccessibilityNodeInfo n : nodes) {
            if (!n.isVisibleToUser() || !n.isClickable() || n.isEditable() || n.equals(base)) continue;
            n.getBoundsInScreen(r);
            if (r.width() > dm.widthPixels * 0.2f) continue;
            if (Math.abs(r.centerY() - b.centerY()) > b.height()) continue;
            int dx = r.centerX() - b.centerX();
            if (dx <= b.width() / 3 || dx >= bestDx) continue;
            if (r.contains(b) || b.contains(r)) continue;
            best = n;
            bestDx = dx;
        }
        return best;
    }

    /** 후보 단어 순서대로, 화면에 보이는 버튼 중 이름이 맞는 것을 찾는다. */
    private AccessibilityNodeInfo findAny(List<AccessibilityNodeInfo> nodes, String[] kws,
                                          String[] exclude, boolean needClickable) {
        for (String kw : kws) {
            for (AccessibilityNodeInfo n : nodes) {
                if (!n.isVisibleToUser() || n.isEditable()) continue;
                String l = label(n).toLowerCase(Locale.ROOT);
                if (l.isEmpty() || l.length() > 60) continue;
                if (!matches(l, kw)) continue;
                if (exclude != null && containsAny(l, exclude)) continue;
                if (needClickable && clickableOf(n) == null) continue;
                return n;
            }
        }
        return null;
    }

    /** 영어 단어는 낱말 단위로("end"가 "send"에 걸리지 않게), 한글은 포함 여부로 비교. */
    private static boolean matches(String label, String kw) {
        boolean ascii = kw.chars().allMatch(ch -> ch < 128);
        if (!ascii) return label.contains(kw);
        if (label.equals(kw)) return true;
        String padded = " " + label.replaceAll("[^a-z0-9]+", " ") + " ";
        return padded.contains(" " + kw + " ");
    }

    private static boolean containsAny(String s, String[] words) {
        for (String w : words) if (s.contains(w)) return true;
        return false;
    }

    private boolean inTopArea(AccessibilityNodeInfo n) {
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return r.centerY() < dm.heightPixels * 0.15f;
    }

    private long voiceStepSince;
    private long loadingSince;
    private long titleWaitSince;
    private boolean relinked;
    private boolean titleMissNoted;

    private static final String[] LOADING_WORDS = {"불러오는 중", "로딩", "loading"};

    private static boolean isLoading(List<AccessibilityNodeInfo> nodes) {
        for (AccessibilityNodeInfo n : nodes) {
            if (!n.isVisibleToUser()) continue;
            String l = label(n).toLowerCase(Locale.ROOT);
            if (!l.isEmpty() && l.length() <= 12 && containsAny(l, LOADING_WORDS)) return true;
        }
        return false;
    }

    /** 음성 버튼 단계에 들어온 지 2.5초가 지났는지. */
    private boolean stepStartedLongAgo(long now) {
        if (voiceStepSince == 0) voiceStepSince = now;
        return now - voiceStepSince > 2500;
    }

    /** 화면 아래 25%·왼쪽 60% 안의 버튼들을 왼쪽부터 세어 index번째(0부터)를 고른다. */
    private AccessibilityNodeInfo bottomLeftButton(List<AccessibilityNodeInfo> nodes, int index) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        List<AccessibilityNodeInfo> found = new ArrayList<>();
        List<Integer> xs = new ArrayList<>();
        Rect r = new Rect();
        for (AccessibilityNodeInfo n : nodes) {
            if (!n.isVisibleToUser() || !n.isClickable() || n.isEditable()) continue;
            n.getBoundsInScreen(r);
            if (r.width() > dm.widthPixels * 0.2f) continue;            // 넓은 영역(입력창 등) 제외
            if (r.centerY() < dm.heightPixels * 0.75f) continue;
            if (r.centerX() > dm.widthPixels * 0.6f) continue;
            int i = 0;
            while (i < xs.size() && xs.get(i) < r.centerX()) i++;
            xs.add(i, r.centerX());
            found.add(i, n);
        }
        return found.size() > index ? found.get(index) : null;
    }

    private static AccessibilityNodeInfo findScrollable(List<AccessibilityNodeInfo> nodes) {
        for (AccessibilityNodeInfo n : nodes) if (n.isScrollable() && n.isVisibleToUser()) return n;
        return null;
    }

    private static AccessibilityNodeInfo clickableOf(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; cur != null && i < 6; i++) {
            if (cur.isClickable() && cur.isEnabled()) return cur;
            cur = cur.getParent();
        }
        return null;
    }

    // ---------------------------------------------------------------- 동작

    /** 실제 손가락 터치처럼 버튼 가운데를 톡 친다(웹 화면의 음파 버튼용). */
    private boolean tap(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo c = clickableOf(n);
        Rect r = new Rect();
        (c != null ? c : n).getBoundsInScreen(r);
        if (r.isEmpty()) return click(n);
        Path p = new Path();
        p.moveTo(r.exactCenterX(), r.exactCenterY());
        return dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 80)).build(), null, null);
    }

    private static String center(AccessibilityNodeInfo n) {
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        return "(" + r.centerX() + "," + r.centerY() + ")";
    }

    private boolean click(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo c = clickableOf(n);
        if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        // 클릭 동작이 막혀 있으면 버튼 가운데를 직접 톡 친다.
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (r.isEmpty()) return false;
        Path p = new Path();
        p.moveTo(r.exactCenterX(), r.exactCenterY());
        return dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null);
    }

    private void swipeFromLeft() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float y = dm.heightPixels * 0.4f;
        Path p = new Path();
        p.moveTo(5, y);
        p.lineTo(dm.widthPixels * 0.6f, y);
        dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 250)).build(), null, null);
    }

    // ---------------------------------------------------------------- 진단

    private void saveDump() {
        List<AccessibilityNodeInfo> nodes = claudeNodes();
        StringBuilder sb = new StringBuilder();
        sb.append("Claude 화면 요소 ").append(nodes.size()).append("개\n");
        Rect r = new Rect();
        for (AccessibilityNodeInfo n : nodes) {
            if (!n.isVisibleToUser()) continue;
            String l = label(n);
            String id = n.getViewIdResourceName();
            if (l.isEmpty() && id == null && !n.isClickable()) continue;
            n.getBoundsInScreen(r);
            sb.append(n.isClickable() ? "[버튼] " : "       ")
              .append('"').append(l.length() > 50 ? l.substring(0, 50) + "…" : l).append('"');
            if (id != null) sb.append("  id=").append(id.substring(id.indexOf('/') + 1));
            sb.append("  (").append(r.centerX()).append(',').append(r.centerY()).append(")\n");
            if (sb.length() > 15000) break;
        }
        Prefs.put(this, Prefs.KEY_LAST_DUMP, sb.toString());
    }

    private void note(String s) {
        log.append("• ").append(s).append('\n');
    }

    private void toast(String s) {
        handler.post(() -> Toast.makeText(this, s, Toast.LENGTH_LONG).show());
    }
}
