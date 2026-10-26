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
    static final String MODE_SEARCH = "search"; // '지식' 대화에서 음파 버튼(지식백과)

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
    private boolean freetalkChat;   // true면 프리토킹 전용 대화
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
        // 리액트식: Claude(크롬) 화면이나 창 구성이 바뀌는 순간 바로 다시 확인한다.
        if (mode == null || event == null) return;
        CharSequence pkg = event.getPackageName();
        boolean relevant = event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                || (pkg != null && (targetPkgs.contains(pkg.toString())
                        || pkg.toString().contains("bixby")));
        if (relevant) scheduleTick(EVENT_DEBOUNCE_MS);
    }

    private static final long EVENT_DEBOUNCE_MS = 80;     // 화면 변경이 몰릴 때 묶어서 한 번만 확인
    private static final long SAFETY_POLL_MS = 700;       // 알림이 안 와도 이 간격으로는 확인(안전장치)
    private final Runnable tickRunnable = this::tick;
    private long nextTickAt;

    /** delay 뒤에 확인. 이미 더 빨리 잡힌 확인이 있으면 그대로 둔다. */
    private void scheduleTick(long delay) {
        long at = SystemClock.uptimeMillis() + delay;
        if (nextTickAt > SystemClock.uptimeMillis() && nextTickAt <= at) return;
        handler.removeCallbacks(tickRunnable);
        nextTickAt = at;
        handler.postAtTime(tickRunnable, at);
    }

    /** 빅스비 창이 아직 떠 있는지(떠 있으면 마이크를 붙잡고 있을 수 있다). */
    private boolean bixbyVisible() {
        List<AccessibilityWindowInfo> ws = getWindows();
        if (ws == null) return false;
        for (AccessibilityWindowInfo w : ws) {
            AccessibilityNodeInfo r = w.getRoot();
            if (r != null && r.getPackageName() != null
                    && r.getPackageName().toString().contains("bixby")) return true;
        }
        return false;
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
        freetalkChat = MODE_VOICE.equals(newMode) && Prefs.hasFreetalkChat(this);
        startedAt = SystemClock.uptimeMillis();
        // 고정 대기 없음: 화면 변경 알림이 오면 바로 확인한다. 진단만 화면을 옮길 시간 4초를 준다.
        waitUntil = startedAt + (MODE_DUMP.equals(newMode) ? 4000 : 0);
        nextTickAt = 0;
        stableCenter = null;
        bixbyGoneAt = 0;
        bixby = fromBixby;
        step = openedByLink ? STEP_PRESS_VOICE : STEP_FIND_CHAT;
        menuTried = false;
        scrolls = 0;
        voiceStepSince = 0;
        sidebarClosed = false;
        loadingSince = 0;
        titleWaitSince = 0;
        relinked = false;
        titleMissNoted = false;
        stopClicks = 0;
        log.setLength(0);
        note("시작: " + newMode + (fromBixby ? " (빅스비)" : " (테스트)") + " / " + target.describe() + (openedByLink ? " / 대화 링크로 열기" : ""));
        scheduleTick(150);
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
        nextTickAt = 0;
        if (now < waitUntil) {
            scheduleTick(waitUntil - now);
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
        if (mode != null) scheduleTick(now < waitUntil ? waitUntil - now : SAFETY_POLL_MS);
    }

    // ---------------------------------------------------------------- 시작 흐름

    private long bixbyGoneAt;

    /** 빅스비로 실행했으면 빅스비 창이 사라지고 0.3초 뒤부터 진행(최대 4초 대기). */
    private boolean bixbyReleased(long now) {
        if (!bixby) return true;
        if (bixbyVisible() && now - startedAt < 4000) {
            bixbyGoneAt = 0;
            return false;
        }
        if (bixbyGoneAt == 0) {
            bixbyGoneAt = now;
            note("빅스비 창 닫힘 확인 (" + (now - startedAt) + "ms)");
            scheduleTick(300);
        }
        return now - bixbyGoneAt >= 300;
    }

    private void tickStart(long now) {
        if (now - startedAt > START_TIMEOUT_MS) {
            finishJob(false, step == STEP_FIND_CHAT
                    ? "'" + title() + "' 대화를 찾지 못했어요."
                    : (wantVoiceMode ? "음파 버튼을 찾지 못했어요." : "마이크 버튼을 찾지 못했어요."));
            return;
        }
        if (!bixbyReleased(now)) return;
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
                return;   // 대화가 열리면 화면 변경 알림으로 바로 이어진다
            }
            if (!menuTried) {
                menuTried = true;
                AccessibilityNodeInfo menu = findAny(nodes, MENU_KW, null, true);
                if (menu != null && click(menu)) {
                    note("대화 목록 열기: " + label(menu));
                    return;
                }
                // 메뉴 버튼이 없으면 왼쪽 가장자리에서 밀어서 열어 본다.
                swipeFromLeft();
                note("왼쪽에서 밀어서 대화 목록 열기 시도");
                return;
            }
            if (scrolls < 6) {
                AccessibilityNodeInfo list = findScrollable(nodes);
                if (list != null) {
                    list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                    scrolls++;
                    note("대화 목록 스크롤 " + scrolls);
                    waitUntil = now + 350;   // 스크롤이 끝날 때까지만
                }
            }
            return;
        }

        // STEP_PRESS_VOICE
        // 대화가 아직 불러오는 중이면 버튼 이름이 비어 있으므로 기다린다.
        // 단, 사이드바 등에 '불러오는 중' 문구가 계속 남아 있을 수 있어 최대 4초만 기다린다.
        if (isLoading(nodes)) {
            if (loadingSince == 0) { loadingSince = now; note("불러오는 중 문구 보임 → 최대 4초 대기"); }
            if (now - loadingSince < 4000) return;
        }
        // 지금 화면이 목표 대화인지: 위쪽 제목줄에 대화 이름이 보여야 한다.
        if (!title().isEmpty() && findTitle(nodes, TOP) == null) {
            if (titleWaitSince == 0) titleWaitSince = now;
            long waited = now - titleWaitSince;
            // 입력창 버튼이 그려졌는데(=화면 로딩 끝) 제목이 없으면 다른 화면 → 즉시 링크 다시 열기.
            boolean inputReady = findAny(nodes, VOICE_KW, VOICE_EXCLUDE, true) != null;
            if (viaLink && !relinked && (inputReady || waited > 3000)) {
                // 창이 닫혀 있다 처음 켜지면 링크를 놓치고 첫 화면에 머문다 → 링크를 한 번 더 보낸다.
                relinked = true;
                titleWaitSince = 0;
                Launcher.reopenLastLink(this);
                note("대화 화면이 아님 → 대화 링크 다시 열기 (" + (now - startedAt) + "ms)");
                waitUntil = now + 300;
                return;
            }
            // 창이 닫혀 있다 켜진 경우 claude.ai가 다 뜨는 데 오래 걸리므로 8초까지 기다린다.
            if (viaLink && relinked && waited > 10000) {   // 안전장치
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
        // 위치로 짐작해서 누르지 않는다(엉뚱한 버튼을 누르는 원인). 버튼이 안 보이면
        // 왼쪽 대화 목록이 입력창을 가리고 있는 경우이므로 목록을 닫는다(한 번).
        // (목록이 실제로 열려 있을 때만: '새로 만들기' 항목이 보이면 열린 상태)
        if (!sidebarClosed
                && findAny(nodes, new String[]{"새로 만들기", "new chat"}, null, false) != null) {
            AccessibilityNodeInfo side = findAny(nodes, new String[]{"사이드바", "sidebar"}, null, true);
            if (side != null && click(side)) {
                sidebarClosed = true;
                note("버튼이 가려짐 → 대화 목록 닫기: " + label(side));
                return;
            }
        }
        if (voice != null) {
            // 버튼이 막 그려진 순간에는 반응하지 않을 수 있어, 같은 자리에 0.2초 이상 있을 때 누른다.
            String c = center(voice);
            if (!c.equals(stableCenter)) {
                stableCenter = c;
                stableSince = now;
                scheduleTick(200);
                return;
            }
            if (now - stableSince < 200) return;
            note("버튼 확인까지 " + (now - startedAt) + "ms");
            saveDump();   // 무엇을 눌렀는지 항상 기록(진단용)
        }
        if (voice != null && clickNode(voice)) {
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
        if (!bixbyReleased(now)) return;
        List<AccessibilityNodeInfo> nodes = claudeNodes();
        String custom = Prefs.get(this, Prefs.KEY_END_KW, "");
        AccessibilityNodeInfo end = custom.isEmpty()
                ? findAny(nodes, END_KW, null, true)
                : findAny(nodes, new String[]{custom.toLowerCase(Locale.ROOT)}, null, true);
        if (end != null && stopClicks < 3 && click(end)) {
            stopClicks++;
            note("종료 버튼 누름: " + label(end));
            waitUntil = now + 250;   // 같은 버튼을 두 번 누르지 않게 잠깐만
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
        // 프리토킹 전용 대화는 제목을 비워 둘 수 있다(비어 있으면 제목 확인을 건너뜀).
        if (freetalkChat) return Prefs.get(this, Prefs.KEY_TITLE3, "");
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
        AccessibilityNodeInfo named = findAny(nodes, new String[]{"음성 모드 시작", "start voice mode"}, null, true);
        if (named != null) return named;
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
    private boolean sidebarClosed;
    private String stableCenter;
    private long stableSince;
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

    /** 버튼에 직접 클릭 명령을 보낸다(화면 좌표 터치 없음 → 위에 덮인 목록·메뉴를 누를 일이 없다). */
    private boolean clickNode(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo c = clickableOf(n);
        return c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK);
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
