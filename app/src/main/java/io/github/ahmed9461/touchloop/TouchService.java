package io.github.ahmed9461.touchloop;

import android.accessibilityservice.AccessibilityService;
import android.annotation.SuppressLint;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class TouchService extends AccessibilityService implements Playback.Host {
    private record Screen(int width, int height, int rotation, int densityDpi) {
        boolean matches(Routine routine) {
            return width == routine.width && height == routine.height && rotation == routine.rotation;
        }
    }

    private static WeakReference<TouchService> instance = new WeakReference<>(null);
    private static WeakReference<Runnable> controlsChanged = new WeakReference<>(null);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Playback playback = new Playback(handler, this);
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) {}
        @Override public void onDisplayRemoved(int id) {}
        @Override public void onDisplayChanged(int id) {
            if (id == Display.DEFAULT_DISPLAY) refreshScreen();
        }
    };
    private final BroadcastReceiver screenEvents = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            close("توقف التشغيل عند إطفاء الشاشة");
        }
    };
    private WindowManager windows;
    private DisplayManager displays;
    private Screen screen;
    private PowerManager power;
    private KeyguardManager keyguard;
    private RoutineStore store;
    private LinearLayout hud;
    private LinearLayout hudButtons;
    private WindowManager.LayoutParams hudParams;
    private CaptureView capture;
    private WindowManager.LayoutParams captureParams;
    private TextView count;
    private boolean folded;
    private float toolbarScale;
    private String playbackNotice;
    private Routine recording;
    private Routine playing;
    private boolean liveCapture;
    private boolean closing;
    private boolean receiverRegistered;
    private boolean thermalRegistered;
    private long lastUp;
    private PowerManager.OnThermalStatusChangedListener thermalListener;

    static TouchService current() {
        return instance.get();
    }

    static void observeControls(Runnable listener) {
        controlsChanged = new WeakReference<>(listener);
    }

    private static void publish(TouchService service) {
        instance = new WeakReference<>(service);
        Runnable listener = controlsChanged.get();
        if (listener != null) listener.run();
    }

    void hideControls() {
        close(null);
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        windows = getSystemService(WindowManager.class);
        if (displays == null) {
            displays = getSystemService(DisplayManager.class);
            screen = readScreen();
            displays.registerDisplayListener(displayListener, handler);
        }
        power = getSystemService(PowerManager.class);
        keyguard = getSystemService(KeyguardManager.class);
        store = new RoutineStore(this);
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenEvents, filter, RECEIVER_NOT_EXPORTED);
            else registerReceiver(screenEvents, filter);
            receiverRegistered = true;
        }
        if (Build.VERSION.SDK_INT >= 29 && !thermalRegistered) {
            thermalListener = status -> {
                if (status >= PowerManager.THERMAL_STATUS_SEVERE) playback.stop("توقف التشغيل بسبب حرارة الجهاز");
            };
            power.addThermalStatusListener(getMainExecutor(), thermalListener);
            thermalRegistered = true;
        }
        publish(this);
    }

    boolean showControls() {
        if (hud != null) return true;
        hud = new LinearLayout(this);
        hud.setOrientation(LinearLayout.VERTICAL);
        hud.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        hud.setPadding(dp(4), dp(4), dp(4), dp(4));
        hud.setBackground(Theme.shape(this, Theme.BACK, 16, Theme.STROKE));
        hud.setElevation(dp(6));
        hudButtons = new LinearLayout(this);
        hudButtons.setGravity(Gravity.CENTER_VERTICAL);
        hud.addView(hudButtons, new LinearLayout.LayoutParams(-2, -2));
        hud.addOnLayoutChangeListener((bar, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (hud == bar) positionHud(hudParams.x, hudParams.y);
        });
        if (hudParams == null) {
            hudParams = overlay(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT);
            hudParams.setTitle("TouchLoop controls");
            hudParams.x = dp(8);
            hudParams.y = dp(64);
        }
        populateHud();
        if (addOverlay(hud, hudParams)) return true;
        hud = null;
        return false;
    }

    @SuppressLint("ClickableViewAccessibility") // The drag grip also calls performClick on release.
    void populateHud() {
        if (hud == null) return;
        toolbarScale = effectiveToolbarScale();
        float alpha = store.toolbarOpacity() / 100f;
        if (hudParams.alpha != alpha) {
            hudParams.alpha = alpha;
            if (hud.isAttachedToWindow()) windows.updateViewLayout(hud, hudParams);
        }
        count = null;
        hudButtons.removeAllViews();
        if (hud.getChildCount() > 1) hud.removeViewAt(1);
        int gripColor = folded && recording != null ? Theme.MINT
                : folded && playback.running() ? Theme.DANGER : Theme.MUTED;
        TextView grip = hudAction("⠿", Theme.PANEL, gripColor, () -> {
            folded = !folded;
            populateHud();
        });
        grip.setContentDescription(folded ? "فتح الشريط العائم. اسحب المقبض لتحريكه"
                : "طي الشريط العائم. اسحب المقبض لتحريكه");
        grip.setOnTouchListener(new View.OnTouchListener() {
            private final int slop = ViewConfiguration.get(TouchService.this).getScaledTouchSlop();
            private float downX;
            private float downY;
            private int originX;
            private int originY;
            private boolean dragging;

            @Override public boolean onTouch(View view, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    downX = event.getRawX();
                    downY = event.getRawY();
                    originX = hudParams.x;
                    originY = hudParams.y;
                    dragging = false;
                } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    float dx = event.getRawX() - downX;
                    float dy = event.getRawY() - downY;
                    dragging |= Math.hypot(dx, dy) > slop;
                    if (dragging) positionHud(originX + Math.round(dx), originY + Math.round(dy));
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    if (!dragging && Math.hypot(event.getRawX() - downX, event.getRawY() - downY) <= slop) {
                        view.performClick();
                    }
                }
                return true;
            }
        });
        if (folded) return;
        if (recording != null) {
            count = hudAction("", Theme.PANEL, Theme.MUTED, () -> {});
            count.setClickable(false);
            count.setFocusable(false);
            updateCount();
            hudAction("احفظ", Theme.MINT, Theme.DARK_MINT, this::saveRecording);
            hudAction("إلغاء", Theme.PANEL, Theme.DANGER, this::discardRecording);
        } else if (playback.running()) {
            hudAction("إيقاف", Theme.DANGER, Theme.BACK, () -> playback.stop("تم الإيقاف"));
        } else {
            hudAction("سجّل", Theme.MINT, Theme.DARK_MINT, this::startRecording);
            hudAction("شغّل", Theme.PANEL, Theme.INK, this::startPlayback);
            hudAction("إدارة", Theme.PANEL, Theme.INK, this::openApp);
            TextView hide = hudAction("×", Theme.PANEL, Theme.MUTED, () -> close(null));
            hide.setContentDescription("إخفاء الشريط العائم");
            if (playbackNotice != null) {
                TextView notice = Theme.text(this, playbackNotice, 13, Theme.DANGER, true);
                notice.setTextDirection(View.TEXT_DIRECTION_RTL);
                notice.setGravity(Gravity.END);
                notice.setPadding(dp(8), dp(3), dp(8), dp(3));
                notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
                hud.addView(notice, new LinearLayout.LayoutParams(-1, -2));
            }
        }
    }

    private TextView hudAction(String title, int bg, int fg, Runnable command) {
        TextView button = Theme.action(this, title, bg, fg, command);
        Theme.sizeToolbarButton(this, button, toolbarScale);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.leftMargin = dp(2);
        params.rightMargin = dp(2);
        hudButtons.addView(button, params);
        return button;
    }

    private float effectiveToolbarScale() {
        // Five 48 dp buttons plus the horizontal margins and panel padding.
        return Math.min(store.toolbarSize() / 100f, (screen.width - dp(28)) / (float) dp(240));
    }

    private void positionHud(int x, int y) {
        if (!hud.isAttachedToWindow()) return;
        int maxX = Math.max(0, screen.width - hud.getWidth());
        int maxY = Math.max(0, screen.height - hud.getHeight() - dp(48));
        int left = Math.max(0, Math.min(x, maxX));
        int top = Math.max(Math.min(dp(32), maxY), Math.min(y, maxY));
        if (left == hudParams.x && top == hudParams.y) return;
        hudParams.x = left;
        hudParams.y = top;
        windows.updateViewLayout(hud, hudParams);
    }

    private void startRecording() {
        if (!power.isInteractive() || keyguard.isKeyguardLocked()) return;
        if (store.list().size() >= RoutineStore.MAX_ROUTINES) {
            say("احذف تسجيلًا قبل إنشاء تسجيل جديد");
            return;
        }
        refreshScreen();
        playbackNotice = null;
        String name = "تسجيل " + new SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(new Date());
        recording = new Routine(name, screen.width, screen.height, screen.rotation);
        lastUp = 0;
        liveCapture = store.liveCapture();
        capture = new CaptureView(this, recording.width, recording.height, this::recorded);
        captureParams = overlay(-1, -1);
        captureParams.setTitle("TouchLoop capture");
        removeOverlay(hud);
        hud = null;
        if (!addOverlay(capture, captureParams) || !showControls()) {
            discardRecording();
            showControls();
            return;
        }
        say(liveCapture ? "سجّل بإصبع واحد وانتظر تطبيق كل إيماءة" : "نفّذ الإيماءات ثم اضغط احفظ");
    }

    private void recorded(float[] points, long down, long up) {
        refreshScreen();
        if (recording == null) return;
        long gap = lastUp == 0 ? 0 : down - lastUp;
        lastUp = up;
        Routine.Step step = new Routine.Step(points, up - down, gap);
        recording.steps.add(step);
        if (recording.steps.size() == Routine.MAX_STEPS) {
            saveRecording();
            return;
        }
        updateCount();
        if (liveCapture) forwardRecordedStep(step);
    }

    private void forwardRecordedStep(Routine.Step step) {
        CaptureView view = capture;
        captureParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        windows.updateViewLayout(view, captureParams);
        view.setVisibility(View.INVISIBLE);
        handler.postDelayed(() -> {
            if (view != capture) return;
            if (!dispatch(step, step.duration, delivered -> restoreCapture(view, delivered))) {
                restoreCapture(view, false);
            }
        }, 65);
    }

    private void restoreCapture(CaptureView view, boolean delivered) {
        if (capture != view) return;
        captureParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        windows.updateViewLayout(view, captureParams);
        view.setVisibility(View.VISIBLE);
        if (!delivered) say("حُفظت الإيماءة لكن النظام لم يطبقها أثناء التسجيل");
    }

    private void updateCount() {
        if (count == null) return;
        count.setText(getString(R.string.capture_count, recording.steps.size()));
        count.setContentDescription("عدد الخطوات " + recording.steps.size());
    }

    private void saveRecording() {
        if (recording == null) return;
        if (recording.steps.isEmpty()) {
            say("سجّل إيماءة واحدة على الأقل");
            return;
        }
        int total = recording.steps.size();
        if (!persistRecording()) {
            say("تعذّر حفظ التسجيل");
            return;
        }
        discardRecording();
        say("تم حفظ " + total + " خطوة");
    }

    private boolean persistRecording() {
        long duration = recording.sequenceDuration();
        recording.period = Timing.clamp(duration + 500, 2_000, Timing.MAX_PERIOD);
        recording.session = Timing.clamp(duration + 1_000, 30_000, Timing.MAX_SESSION);
        return store.save(recording);
    }

    private void discardRecording() {
        removeOverlay(capture);
        capture = null;
        captureParams = null;
        count = null;
        recording = null;
        if (!closing) populateHud();
    }

    private void startPlayback() {
        Routine routine = store.selected();
        if (routine == null) {
            showPlaybackNotice("سجّل إيماءات أولًا");
            return;
        }
        playing = routine;
        String problem = interruption();
        if (problem == null && routine.sequenceDuration() > routine.session) {
            problem = "زد مدة التشغيل لتتسع للتسلسل كاملًا";
        }
        if (problem != null) {
            playing = null;
            showPlaybackNotice(problem);
            return;
        }
        playbackNotice = null;
        playback.start(routine);
        populateHud();
    }

    private void showPlaybackNotice(String message) {
        playbackNotice = message;
        populateHud();
    }

    @Override public String interruption() {
        if (!power.isInteractive() || keyguard.isKeyguardLocked()) return "توقف التشغيل لأن الشاشة مقفلة";
        if (Build.VERSION.SDK_INT >= 29 && power.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_SEVERE) {
            return "توقف التشغيل بسبب حرارة الجهاز";
        }
        Routine active = playing != null ? playing : recording;
        if (active != null) {
            Screen current = readScreen();
            if (current.rotation != active.rotation) {
                return "جهة التشغيل المطلوبة: " + active.orientationLabel();
            }
            if (!current.matches(active)) {
                return "دقة التسجيل المطلوبة: " + active.width + " × " + active.height;
            }
        }
        return null;
    }

    @Override public boolean dispatch(Routine.Step step, long duration, Playback.Result result) {
        if (hud == null || interruption() != null) return false;
        int[] location = new int[2];
        hud.getLocationOnScreen(location);
        float[] points = step.points;
        if (points[0] >= location[0] && points[0] < location[0] + hud.getWidth()
                && points[1] >= location[1] && points[1] < location[1] + hud.getHeight()) {
            say("حرّك الشريط العائم بعيدًا عن موضع الإيماءة");
            return false;
        }
        Path path = new Path();
        path.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, duration)).build();
        return dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription description) {
                result.complete(true);
            }

            @Override public void onCancelled(GestureDescription description) {
                result.complete(false);
            }
        }, handler);
    }

    @Override public void ended(String message) {
        playing = null;
        if (!closing) populateHud();
        if (message != null) say(message);
    }

    private void openApp() {
        close(null);
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }

    @SuppressLint("RtlHardcoded") // Touch coordinates use the physical left edge as their origin.
    private WindowManager.LayoutParams overlay(int width, int height) {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(width, height,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        return params;
    }

    private boolean addOverlay(View view, WindowManager.LayoutParams params) {
        try {
            windows.addView(view, params);
            return true;
        } catch (WindowManager.BadTokenException | WindowManager.InvalidDisplayException error) {
            Log.w("TouchLoop", "Overlay unavailable", error);
            say("تعذّر إظهار التحكم العائم");
            return false;
        }
    }

    private void removeOverlay(View view) {
        if (view != null && view.isAttachedToWindow()) windows.removeViewImmediate(view);
    }

    @SuppressWarnings("deprecation")
    private Screen readScreen() {
        Display display = displays.getDisplay(Display.DEFAULT_DISPLAY);
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        return new Screen(metrics.widthPixels, metrics.heightPixels, display.getRotation(), metrics.densityDpi);
    }

    private void refreshScreen() {
        if (displays == null) return;
        Screen current = readScreen();
        if (current.equals(screen)) return;
        screen = current;
        if (playbackNotice != null) {
            playbackNotice = null;
            populateHud();
        }
        playback.stop("توقف التشغيل بسبب تغيّر اتجاه الشاشة أو دقتها");
        if (recording != null) {
            boolean hasSteps = !recording.steps.isEmpty();
            boolean saved = hasSteps && persistRecording();
            discardRecording();
            if (saved) say("حُفظت الخطوات المسجّلة قبل تغيّر الشاشة");
            else if (hasSteps) say("تعذّر حفظ الخطوات المسجّلة");
        }
        if (hud != null) {
            if (toolbarScale != effectiveToolbarScale()) populateHud();
            positionHud(hudParams.x, hudParams.y);
        }
    }

    private int dp(int value) {
        return Theme.dp(this, value);
    }

    private void say(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void close(String message) {
        closing = true;
        playbackNotice = null;
        playback.stop(message);
        discardRecording();
        removeOverlay(hud);
        hud = null;
        closing = false;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override public void onInterrupt() {
        close("أوقف النظام التحكم العائم");
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        refreshScreen();
    }

    @Override public boolean onUnbind(Intent intent) {
        close(null);
        if (current() == this) publish(null);
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        if (current() == this) publish(null);
        close(null);
        handler.removeCallbacksAndMessages(null);
        if (displays != null) displays.unregisterDisplayListener(displayListener);
        if (receiverRegistered) unregisterReceiver(screenEvents);
        if (Build.VERSION.SDK_INT >= 29 && thermalRegistered) power.removeThermalStatusListener(thermalListener);
        super.onDestroy();
    }
}
