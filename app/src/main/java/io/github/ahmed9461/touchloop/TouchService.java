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
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
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
    private static WeakReference<TouchService> instance = new WeakReference<>(null);
    private static WeakReference<Runnable> controlsChanged = new WeakReference<>(null);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Playback playback = new Playback(handler, this);
    private final BroadcastReceiver screenEvents = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            close("توقف التشغيل عند إطفاء الشاشة");
        }
    };
    private WindowManager windows;
    private PowerManager power;
    private KeyguardManager keyguard;
    private RoutineStore store;
    private LinearLayout hud;
    private WindowManager.LayoutParams hudParams;
    private CaptureView capture;
    private WindowManager.LayoutParams captureParams;
    private TextView count;
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
        hud.setGravity(Gravity.CENTER_VERTICAL);
        hud.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        hud.setPadding(dp(4), dp(4), dp(4), dp(4));
        hud.setBackground(Theme.shape(this, Theme.BACK, 16, Theme.STROKE));
        hud.setElevation(dp(6));
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
    private void populateHud() {
        if (hud == null) return;
        hud.removeAllViews();
        TextView grip = hudAction("⠿", Theme.PANEL, Theme.MUTED, () -> {});
        grip.setContentDescription("مقبض سحب الشريط العائم");
        grip.setOnTouchListener(new View.OnTouchListener() {
            private float downX;
            private float downY;
            private int originX;
            private int originY;

            @Override public boolean onTouch(View view, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    downX = event.getRawX();
                    downY = event.getRawY();
                    originX = hudParams.x;
                    originY = hudParams.y;
                } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    hudParams.x = originX + Math.round(event.getRawX() - downX);
                    hudParams.y = originY + Math.round(event.getRawY() - downY);
                    fitHud();
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    view.performClick();
                }
                return true;
            }
        });
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
        }
        LinearLayout bar = hud;
        bar.post(() -> {
            if (hud == bar && bar.isAttachedToWindow()) fitHud();
        });
    }

    private TextView hudAction(String title, int bg, int fg, Runnable command) {
        TextView button = Theme.action(this, title, bg, fg, command);
        button.setPadding(dp(8), 0, dp(8), 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.leftMargin = dp(2);
        params.rightMargin = dp(2);
        hud.addView(button, params);
        return button;
    }

    private void fitHud() {
        DisplayMetrics metrics = displayMetrics();
        int bottom = dp(48);
        hudParams.x = Math.max(0, Math.min(hudParams.x, metrics.widthPixels - hud.getWidth()));
        hudParams.y = Math.max(dp(32), Math.min(hudParams.y, metrics.heightPixels - hud.getHeight() - bottom));
        windows.updateViewLayout(hud, hudParams);
    }

    private void startRecording() {
        if (!power.isInteractive() || keyguard.isKeyguardLocked()) return;
        if (store.list().size() >= RoutineStore.MAX_ROUTINES) {
            say("احذف تسجيلًا قبل إنشاء تسجيل جديد");
            return;
        }
        DisplayMetrics metrics = displayMetrics();
        String name = "تسجيل " + new SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(new Date());
        recording = new Routine(name, metrics.widthPixels, metrics.heightPixels, rotation());
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
        count.setText(getString(R.string.capture_count, recording.steps.size()));
        count.setContentDescription("عدد الخطوات " + recording.steps.size());
    }

    private void saveRecording() {
        if (recording == null) return;
        if (recording.steps.isEmpty()) {
            say("سجّل إيماءة واحدة على الأقل");
            return;
        }
        long duration = recording.sequenceDuration();
        recording.period = Timing.clamp(duration + 500, 2_000, Timing.MAX_PERIOD);
        recording.session = Timing.clamp(duration + 1_000, 30_000, Timing.MAX_SESSION);
        int total = recording.steps.size();
        boolean saved = store.save(recording);
        if (!saved) {
            say("تعذّر حفظ التسجيل");
            return;
        }
        discardRecording();
        say("تم حفظ " + total + " خطوة");
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
            say("سجّل إيماءات أولًا");
            return;
        }
        playing = routine;
        String problem = interruption();
        if (problem == null && routine.sequenceDuration() > routine.session) {
            problem = "زد مدة التشغيل لتتسع للتسلسل كاملًا";
        }
        if (problem != null) {
            playing = null;
            say(problem);
            return;
        }
        playback.start(routine);
        populateHud();
    }

    @Override public String interruption() {
        if (!power.isInteractive() || keyguard.isKeyguardLocked()) return "توقف التشغيل لأن الشاشة مقفلة";
        if (Build.VERSION.SDK_INT >= 29 && power.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_SEVERE) {
            return "توقف التشغيل بسبب حرارة الجهاز";
        }
        if (playing != null) {
            DisplayMetrics metrics = displayMetrics();
            if (metrics.widthPixels != playing.width || metrics.heightPixels != playing.height
                    || rotation() != playing.rotation) {
                return "أعد الشاشة لاتجاه التسجيل ودقته";
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
    private DisplayMetrics displayMetrics() {
        DisplayMetrics metrics = new DisplayMetrics();
        windows.getDefaultDisplay().getRealMetrics(metrics);
        return metrics;
    }

    @SuppressWarnings("deprecation")
    private int rotation() {
        return windows.getDefaultDisplay().getRotation();
    }

    private int dp(int value) {
        return Theme.dp(this, value);
    }

    private void say(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void close(String message) {
        closing = true;
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
        close("توقف التشغيل بسبب تغيّر إعدادات الشاشة");
        hudParams = null;
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
        if (receiverRegistered) unregisterReceiver(screenEvents);
        if (Build.VERSION.SDK_INT >= 29 && thermalRegistered) power.removeThermalStatusListener(thermalListener);
        super.onDestroy();
    }
}
