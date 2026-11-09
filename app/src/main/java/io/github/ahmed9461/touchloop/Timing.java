package io.github.ahmed9461.touchloop;

final class Timing {
    static final long MIN_PERIOD = 500;
    static final long MAX_PERIOD = 600_000;
    static final long MAX_SESSION = 86_400_000;
    static final long MIN_STROKE = 24;
    static final long MAX_STROKE = 3_000;
    static final long RECORDED = -1;

    private Timing() {}

    static long clamp(long value, long low, long high) {
        return Math.max(low, Math.min(high, value));
    }

    static long stroke(long millis, int speed) {
        return clamp(Math.round(millis * 100.0 / speed), MIN_STROKE, MAX_STROKE);
    }

    static long gap(long recorded, long fixed, int speed) {
        return fixed == RECORDED ? Math.round(recorded * 100.0 / speed) : fixed;
    }

    static long nextCycle(long previousStart, long finished, long period) {
        return Math.max(previousStart + period, finished);
    }

    static boolean fits(long start, long duration, long deadline) {
        return start < deadline && duration <= deadline - start;
    }

    static long parseMillis(String raw) {
        StringBuilder normalized = new StringBuilder();
        for (char ch : raw.trim().toCharArray()) {
            int digit = Character.digit(ch, 10);
            if (digit >= 0) normalized.append(digit);
            else if (ch == '٫' || ch == ',') normalized.append('.');
            else normalized.append(ch);
        }
        if (!normalized.toString().matches("[0-9]{1,6}(\\.[0-9]{1,3})?")) {
            throw new IllegalArgumentException("Expected seconds");
        }
        return Math.round(Double.parseDouble(normalized.toString()) * 1_000);
    }
}
