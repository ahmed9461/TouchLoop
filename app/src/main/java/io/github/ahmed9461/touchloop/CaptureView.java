package io.github.ahmed9461.touchloop;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.Arrays;

@SuppressLint("ViewConstructor") // Created in code with recording-specific dimensions and listener.
final class CaptureView extends View {
    interface Listener {
        void recorded(float[] points, long downTime, long upTime);
    }

    private final Listener listener;
    private final int width;
    private final int height;
    private final int slop;
    private final float[] points = new float[Routine.MAX_POINTS * 2];
    private long downTime;
    private boolean tracking;
    private boolean moved;
    private int size;

    CaptureView(Context context, int width, int height, Listener listener) {
        super(context);
        this.width = width;
        this.height = height;
        this.listener = listener;
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        setBackgroundColor(0x20132F2E);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        float x = event.getRawX();
        float y = event.getRawY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                size = 0;
                tracking = true;
                moved = false;
                downTime = event.getEventTime();
                add(x, y, true);
                break;
            case MotionEvent.ACTION_MOVE:
                if (tracking) {
                    float offsetX = x - event.getX();
                    float offsetY = y - event.getY();
                    for (int i = 0; i < event.getHistorySize(); i++) {
                        add(event.getHistoricalX(i) + offsetX, event.getHistoricalY(i) + offsetY, false);
                    }
                    add(x, y, false);
                }
                break;
            case MotionEvent.ACTION_UP:
                if (tracking) {
                    add(x, y, true);
                    tracking = false;
                    listener.recorded(Arrays.copyOf(points, moved ? size : 2), downTime, event.getEventTime());
                    performClick();
                }
                break;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_DOWN:
                tracking = false;
                break;
            default:
                break;
        }
        return true;
    }

    @Override public boolean performClick() {
        return super.performClick();
    }

    private void add(float x, float y, boolean endpoint) {
        x = Math.max(0, Math.min(width - 1, x));
        y = Math.max(0, Math.min(height - 1, y));
        if (size >= 2) {
            float deltaX = x - points[size - 2];
            float deltaY = y - points[size - 1];
            float distanceSquared = deltaX * deltaX + deltaY * deltaY;
            if (distanceSquared == 0 || (!endpoint && distanceSquared < 4)) return;
            moved |= Math.hypot(x - points[0], y - points[1]) >= slop;
        }
        if (size == points.length) {
            for (int i = 4; i < size; i += 4) {
                points[i / 2] = points[i];
                points[i / 2 + 1] = points[i + 1];
            }
            size /= 2;
        }
        points[size++] = x;
        points[size++] = y;
    }
}
