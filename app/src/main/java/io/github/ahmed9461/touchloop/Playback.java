package io.github.ahmed9461.touchloop;

import android.os.Handler;
import android.os.SystemClock;

final class Playback {
    interface Host {
        String interruption();
        boolean dispatch(Routine.Step step, long duration, Result result);
        void ended(String message);
    }

    interface Result {
        void complete(boolean delivered);
    }

    private static final class Run {
        final Routine routine;
        final long deadline;
        long cycleStart;
        int index;
        boolean dispatched;

        Run(Routine routine, long start) {
            this.routine = routine;
            deadline = start + routine.session;
        }
    }

    private final Handler handler;
    private final Host host;
    private Run active;

    Playback(Handler handler, Host host) {
        this.handler = handler;
        this.host = host;
    }

    boolean running() {
        return active != null;
    }

    boolean hasDispatchedGesture() {
        return active != null && active.dispatched;
    }

    void start(Routine routine) {
        long start = SystemClock.uptimeMillis() + 150;
        active = new Run(routine, start);
        schedule(active, start);
    }

    void stop(String message) {
        if (active == null) return;
        handler.removeCallbacksAndMessages(active);
        active = null;
        host.ended(message);
    }

    private void schedule(Run run, long time) {
        handler.postAtTime(() -> execute(run), run, Math.min(time, run.deadline));
    }

    private void execute(Run run) {
        if (active != run) return;
        String interruption = host.interruption();
        if (interruption != null) {
            stop(interruption);
            return;
        }
        Routine.Step step = run.routine.steps.get(run.index);
        long duration = Timing.stroke(step.duration, run.routine.speed);
        long now = SystemClock.uptimeMillis();
        if (!Timing.fits(now, duration, run.deadline)) {
            stop("اكتملت مدة التشغيل");
            return;
        }
        if (run.index == 0) run.cycleStart = now;
        if (!host.dispatch(step, duration, delivered -> completed(run, delivered))) {
            stop("توقف التشغيل: تعذّر إرسال الإيماءة");
            return;
        }
        run.dispatched = true;
    }

    private void completed(Run run, boolean delivered) {
        if (active != run) return;
        if (!delivered) {
            stop("توقف التشغيل: قاطع النظام أو اللمس إحدى الإيماءات");
            return;
        }
        long now = SystemClock.uptimeMillis();
        run.index++;
        if (run.index == run.routine.steps.size()) {
            run.index = 0;
            schedule(run, Timing.nextCycle(run.cycleStart, now, run.routine.period));
            return;
        }
        Routine.Step next = run.routine.steps.get(run.index);
        schedule(run, now + Timing.gap(next.gap, run.routine.fixedGap, run.routine.speed));
    }
}
