package io.github.ahmed9461.touchloop;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;

public final class MainActivity extends Activity {
    private RoutineStore store;
    private LinearLayout content;
    private boolean home = true;
    private final Runnable controlsChanged = () -> {
        if (home) showHome();
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new RoutineStore(this);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        showHome();
    }

    @Override protected void onResume() {
        super.onResume();
        TouchService.observeControls(controlsChanged);
        if (home) showHome();
    }

    @Override protected void onPause() {
        TouchService.observeControls(null);
        super.onPause();
    }

    private void screen(String label, String title, String subtitle) {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Theme.BACK);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        } else {
            root.setFitsSystemWindows(true);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        content = vertical();
        content.setFocusableInTouchMode(true);
        content.setPadding(dp(20), dp(22), dp(20), dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        add(content, text(label, 13, Theme.MINT, true), 4);
        add(content, text(title, 34, Theme.INK, true), 2);
        add(content, text(subtitle, 15, Theme.MUTED, false), 20);
        setContentView(root);
        content.requestFocus();
    }

    private void showHome() {
        home = true;
        screen("TOUCHLOOP  /  استوديو الإيماءات", "لمسة", "سجّل حركاتك واضبط إيقاع تكرارها.");
        boolean connected = TouchService.current() != null;
        LinearLayout controls = card();
        add(controls, text(connected ? "● التحكم جاهز" : "○ يلزم تفعيل التحكم", 15, Theme.MINT, true), 8);
        if (connected) {
            add(controls, text("افتح الشاشة التي تريد تسجيلها، ثم استخدم الشريط العائم للتسجيل أو التشغيل.",
                    14, Theme.MUTED, false), 12);
            add(controls, button("إظهار الشريط العائم", true, this::showFloating), 0);
        } else {
            add(controls, text("من إعدادات إمكانية الوصول فعّل «لمسة» ضمن التطبيقات المثبّتة، ثم ارجع هنا.",
                    14, Theme.MUTED, false), 12);
            add(controls, button("فتح إعدادات إمكانية الوصول", true, this::openAccessibility), 0);
        }
        add(content, controls, 16);

        ArrayList<Routine> routines = store.list();
        Routine selected = store.selected();
        add(content, text("التسجيلات  ·  " + routines.size(), 19, Theme.INK, true), 8);
        if (routines.isEmpty()) {
            LinearLayout empty = card();
            add(empty, text("ابدأ بتسجيلك الأول", 17, Theme.INK, true), 4);
            add(empty, text("اضغط «سجّل» على الشريط العائم، نفّذ نقراتك وسحباتك بإصبع واحد ثم اضغط «احفظ».",
                    14, Theme.MUTED, false), 0);
            add(content, empty, 16);
        }
        for (Routine routine : routines) {
            LinearLayout entry = card();
            boolean active = selected != null && selected.id.equals(routine.id);
            add(entry, text(routine.name, 18, Theme.INK, true), 3);
            String detail = routine.steps.size() + " خطوة  •  كل " + seconds(routine.period) + " ث"
                    + (routine.width > routine.height ? "  •  أفقي" : "  •  عمودي")
                    + (active ? "  •  مختار للتشغيل" : "");
            add(entry, text(detail, 14, active ? Theme.MINT : Theme.MUTED, false), 8);
            LinearLayout actions = row();
            place(actions, button("تعديل", false, () -> showEditor(routine)));
            if (!active) place(actions, button("اختيار للتشغيل", true, () -> {
                store.select(routine);
                showHome();
            }));
            add(entry, actions, 0);
            add(content, entry, 10);
        }

        LinearLayout options = card();
        Switch live = new Switch(this);
        live.setText("طبّق الإيماءة أثناء تسجيلها");
        live.setTextColor(Theme.INK);
        live.setTextSize(15);
        live.setMinHeight(dp(48));
        live.setChecked(store.liveCapture());
        live.setOnCheckedChangeListener((button, checked) -> store.setLiveCapture(checked));
        add(options, live, 3);
        add(options, text("عند التفعيل انتظر عودة التظليل بعد كل خطوة. عند الإيقاف يسجّل التطبيق الإحداثيات فقط.",
                13, Theme.MUTED, false), 10);
        add(options, button("فتح مساحة التجربة", false, this::showPractice), 0);
        add(content, options, 12);
        add(content, text("تُحفظ الإيماءات محليًا على جهازك.", 13, Theme.MUTED, false), 0);
    }

    private void showFloating() {
        TouchService service = TouchService.current();
        if (service == null) openAccessibility();
        else if (service.showControls()) moveTaskToBack(true);
    }

    private void openAccessibility() {
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    private void showEditor(Routine routine) {
        home = false;
        screen("محرّر التسجيل", "ضبط التكرار", "حدّد موعد بدء كل دورة وكيف تنفّذ خطواتها.");
        LinearLayout editor = card();
        EditText name = input(editor, "اسم التسجيل", routine.name, false);
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(60)});
        EditText period = input(editor, "التكرار من بداية دورة إلى بداية التالية (ثانية)",
                seconds(routine.period), true);
        LinearLayout presets = row();
        preset(presets, period, "0.5");
        preset(presets, period, "2");
        preset(presets, period, "60");
        preset(presets, period, "600");
        add(editor, presets, 10);
        EditText duration = input(editor, "مدة التشغيل الإجمالية (ثانية)", seconds(routine.session), true);
        EditText gap = input(editor, "فاصل ثابت بين الخطوات (ثانية، اختياري)",
                routine.fixedGap == Timing.RECORDED ? "" : seconds(routine.fixedGap), true);
        gap.setHint("فارغ = توقيت التسجيل");
        EditText speed = input(editor, "سرعة الإيماءات والتوقيت المسجّل (%)", String.valueOf(routine.speed), true);
        add(editor, text("المدة الحالية للتسلسل نحو " + seconds(routine.sequenceDuration())
                + " ث. إذا انتهت الدورة بعد موعد التالية، تبدأ التالية بعد اكتمالها.",
                13, Theme.MUTED, false), 12);
        add(editor, button("حفظ الإعدادات", true, () -> {
            if (saveEditor(routine, name, period, duration, gap, speed)) {
                say("تم حفظ الإعدادات واختيار التسجيل");
                showHome();
            }
        }), 0);
        add(content, editor, 18);

        add(content, text("خطوات التسجيل  ·  " + routine.steps.size(), 19, Theme.INK, true), 8);
        for (int i = 0; i < routine.steps.size(); i++) {
            int index = i;
            Routine.Step step = routine.steps.get(i);
            String kind = step.points.length > 2 ? "سحب" : step.duration >= 500 ? "ضغط مطوّل" : "نقرة";
            String title = (i + 1) + ". " + kind + "  ·  " + seconds(step.duration) + " ث";
            add(content, button(title, false, () -> {
                if (saveEditor(routine, name, period, duration, gap, speed)) editStep(routine, index);
            }), 5);
        }
        add(content, button("حذف التسجيل", false, () -> new AlertDialog.Builder(this)
                .setMessage("حذف «" + routine.name + "»؟")
                .setPositiveButton("حذف", (dialog, which) -> {
                    store.delete(routine);
                    showHome();
                }).setNegativeButton("رجوع", null).show()), 8);
        add(content, button("رجوع إلى التسجيلات", false, this::showHome), 0);
    }

    private boolean saveEditor(Routine routine, EditText name, EditText period, EditText duration,
            EditText gap, EditText speed) {
        String title = name.getText().toString().trim();
        if (title.isEmpty()) {
            name.setError("أدخل اسم التسجيل");
            name.requestFocus();
            return false;
        }
        try {
            long cycle = secondsField(period, Timing.MIN_PERIOD, Timing.MAX_PERIOD);
            long session = secondsField(duration, 1_000, Timing.MAX_SESSION);
            long fixedGap = gap.length() == 0 ? Timing.RECORDED : secondsField(gap, 0, Timing.MAX_PERIOD);
            long scaledSpeed = secondsField(speed, 25_000, 300_000);
            if (scaledSpeed % 1_000 != 0) {
                speed.setError("أدخل نسبة صحيحة من 25 إلى 300");
                speed.requestFocus();
                return false;
            }
            routine.name = title;
            routine.period = cycle;
            routine.session = session;
            routine.fixedGap = fixedGap;
            routine.speed = (int) (scaledSpeed / 1_000);
            if (store.save(routine)) return true;
            say("تعذّر حفظ التسجيل");
        } catch (IllegalArgumentException ignored) {
            // The field reports the valid range.
        }
        return false;
    }

    private void editStep(Routine routine, int index) {
        Routine.Step step = routine.steps.get(index);
        LinearLayout fields = vertical();
        fields.setPadding(dp(22), dp(12), dp(22), dp(8));
        EditText duration = input(fields, "مدة اللمس (ثانية)", seconds(step.duration), true);
        EditText gap = input(fields, "الانتظار قبل الخطوة (ثانية)", seconds(step.gap), true);
        if (index == 0) gap.setEnabled(false);
        LinearLayout actions = row();
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("الخطوة " + (index + 1))
                .setView(fields).setNegativeButton("رجوع", null).setPositiveButton("حفظ", null).create();
        if (index > 0) place(actions, button("نقل لأعلى", false, () -> moveStep(routine, index, index - 1, dialog)));
        if (index < routine.steps.size() - 1) place(actions,
                button("نقل لأسفل", false, () -> moveStep(routine, index, index + 1, dialog)));
        add(fields, actions, 6);
        if (routine.steps.size() > 1) add(fields, button("حذف الخطوة", false, () -> {
            routine.steps.remove(index);
            store.save(routine);
            dialog.dismiss();
            showEditor(routine);
        }), 0);
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                long millis = secondsField(duration, Timing.MIN_STROKE, Timing.MAX_STROKE);
                long wait = secondsField(gap, 0, Timing.MAX_PERIOD);
                step.duration = millis;
                step.gap = wait;
                store.save(routine);
                dialog.dismiss();
                showEditor(routine);
            } catch (IllegalArgumentException ignored) {
                // Leave the dialog open for correction.
            }
        });
    }

    private void moveStep(Routine routine, int from, int to, AlertDialog dialog) {
        Collections.swap(routine.steps, from, to);
        store.save(routine);
        dialog.dismiss();
        showEditor(routine);
    }

    private void showPractice() {
        home = false;
        screen("اختبار الإيماءات", "مساحة التجربة", "سجّل على اللوحة ثم شغّل التسجيل. يتغير العداد عند وصول الإيماءات.");
        add(content, button("إظهار التحكم هنا", true, () -> {
            TouchService service = TouchService.current();
            if (service == null) openAccessibility();
            else service.showControls();
        }), 12);
        PracticePad pad = new PracticePad();
        add(content, pad, 12);
        add(content, button("تصفير العدّاد", false, pad::clear), 8);
        add(content, button("رجوع إلى التسجيلات", false, () -> {
            TouchService service = TouchService.current();
            if (service != null) service.hideControls();
            showHome();
        }), 0);
    }

    private final class PracticePad extends TextView {
        private int taps;
        private int swipes;
        private float startX;
        private float startY;

        PracticePad() {
            super(MainActivity.this);
            setTextColor(Theme.INK);
            setTextSize(19);
            setGravity(Gravity.CENTER);
            setMinHeight(dp(248));
            setBackground(Theme.shape(MainActivity.this, Theme.DARK_MINT, 20, Theme.MINT));
            setClickable(true);
            update();
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                startX = event.getRawX();
                startY = event.getRawY();
                getParent().requestDisallowInterceptTouchEvent(true);
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                if (Math.hypot(event.getRawX() - startX, event.getRawY() - startY) >= dp(20)) {
                    swipes++;
                    update();
                } else {
                    performClick();
                }
            }
            return true;
        }

        @Override public boolean performClick() {
            super.performClick();
            taps++;
            update();
            return true;
        }

        void clear() {
            taps = 0;
            swipes = 0;
            update();
        }

        private void update() {
            setText(getString(R.string.practice_count, taps, swipes));
        }
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (home) super.onBackPressed();
        else {
            TouchService service = TouchService.current();
            if (service != null) service.hideControls();
            showHome();
        }
    }

    private long secondsField(EditText field, long minimum, long maximum) {
        try {
            long value = Timing.parseMillis(field.getText().toString());
            if (value >= minimum && value <= maximum) return value;
        } catch (IllegalArgumentException ignored) {
            // Use the same range message for malformed and out-of-range values.
        }
        field.setError("أدخل قيمة من " + seconds(minimum) + " إلى " + seconds(maximum));
        field.requestFocus();
        throw new IllegalArgumentException("Invalid field");
    }

    private String seconds(long millis) {
        String value = String.format(Locale.ROOT, "%.3f", millis / 1000.0);
        return value.replaceFirst("\\.?0+$", "");
    }

    private void preset(LinearLayout row, EditText target, String value) {
        place(row, button(value, false, () -> target.setText(value)));
    }

    private EditText input(LinearLayout group, String label, String value, boolean numeric) {
        EditText field = new EditText(this);
        field.setId(View.generateViewId());
        TextView caption = text(label, 13, Theme.MUTED, false);
        caption.setLabelFor(field.getId());
        add(group, caption, 3);
        field.setTextColor(Theme.INK);
        field.setHintTextColor(Theme.MUTED);
        field.setTextSize(16);
        field.setSingleLine(true);
        field.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setText(value);
        field.setTextDirection(numeric ? View.TEXT_DIRECTION_LTR : View.TEXT_DIRECTION_FIRST_STRONG);
        field.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        field.setMinHeight(dp(48));
        field.setPadding(dp(10), 0, dp(10), 0);
        field.setBackground(Theme.shape(this, Theme.BACK, 10, Theme.STROKE));
        add(group, field, 10);
        return field;
    }

    private LinearLayout vertical() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        return box;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout card() {
        LinearLayout box = vertical();
        box.setPadding(dp(14), dp(14), dp(14), dp(14));
        box.setBackground(Theme.shape(this, Theme.PANEL, 18, Theme.STROKE));
        return box;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        return Theme.text(this, value, size, color, bold);
    }

    private TextView button(String title, boolean primary, Runnable command) {
        return Theme.action(this, title, primary ? Theme.MINT : Theme.DARK_MINT,
                primary ? Theme.DARK_MINT : Theme.INK, command);
    }

    private void add(LinearLayout parent, View view, int gap) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(gap);
        parent.addView(view, params);
    }

    private void place(LinearLayout row, View view) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.setMarginEnd(dp(3));
        row.addView(view, params);
    }

    private int dp(int value) {
        return Theme.dp(this, value);
    }

    private void say(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }
}
