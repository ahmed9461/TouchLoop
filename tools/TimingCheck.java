package io.github.ahmed9461.touchloop;

public final class TimingCheck {
    public static void main(String[] args) {
        equal(500, Timing.parseMillis("0.5"));
        equal(500, Timing.parseMillis("٠٫٥"));
        equal(1_250, Timing.parseMillis("1,25"));
        equal(600_000, Timing.parseMillis("600"));
        equal(86_400_000, Timing.parseMillis("86400"));
        rejects("-1");
        rejects("1e3");
        rejects("1.0001");
        rejects("");

        equal(100, Timing.stroke(200, 200));
        equal(24, Timing.stroke(30, 300));
        equal(3_000, Timing.stroke(2_000, 25));
        equal(125, Timing.gap(250, Timing.RECORDED, 200));
        equal(250, Timing.gap(100, 250, 200));
        equal(1_500, Timing.nextCycle(1_000, 1_250, Timing.MIN_PERIOD));
        equal(601_000, Timing.nextCycle(1_000, 1_250, Timing.MAX_PERIOD));
        equal(1_800, Timing.nextCycle(1_000, 1_800, Timing.MIN_PERIOD));
        equal(144_832, Timing.twoCyclesDuration(1_832, 143_000));
        equal(4_000, Timing.twoCyclesDuration(2_000, 500));
        if (!Timing.fits(1_000, 100, 1_100) || Timing.fits(1_000, 101, 1_100)
                || Timing.fits(1_100, 24, 1_100)) {
            throw new AssertionError("Session boundary");
        }
        System.out.println("TouchLoop timing checks passed");
    }

    private static void equal(long expected, long actual) {
        if (expected != actual) throw new AssertionError(expected + " != " + actual);
    }

    private static void rejects(String value) {
        try {
            Timing.parseMillis(value);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Accepted " + value);
    }
}
