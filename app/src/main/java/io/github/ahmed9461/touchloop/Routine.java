package io.github.ahmed9461.touchloop;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.UUID;

final class Routine {
    static final int MAX_STEPS = 100;
    static final int MAX_POINTS = 128;

    static final class Step {
        final float[] points;
        long duration;
        long gap;

        Step(float[] points, long duration, long gap) {
            this.points = points;
            this.duration = Timing.clamp(duration, Timing.MIN_STROKE, Timing.MAX_STROKE);
            this.gap = Timing.clamp(gap, 0, Timing.MAX_PERIOD);
        }

        JSONObject encode() throws JSONException {
            JSONArray coords = new JSONArray();
            for (float point : points) coords.put((double) point);
            return new JSONObject().put("points", coords).put("duration", duration).put("gap", gap);
        }

        static Step decode(JSONObject data, int width, int height) throws JSONException {
            JSONArray coords = data.getJSONArray("points");
            int size = coords.length();
            if (size < 2 || size > MAX_POINTS * 2 || size % 2 != 0) {
                throw new JSONException("Invalid path length");
            }
            float[] points = new float[size];
            for (int i = 0; i < size; i++) {
                double value = coords.getDouble(i);
                if (!(value >= 0 && value < (i % 2 == 0 ? width : height))) {
                    throw new JSONException("Invalid coordinate");
                }
                points[i] = (float) value;
            }
            return new Step(points, data.getLong("duration"), data.getLong("gap"));
        }
    }

    final String id;
    final int width;
    final int height;
    final int rotation;
    final ArrayList<Step> steps = new ArrayList<>();
    String name;
    long period = 2_000;
    long session = 30_000;
    long fixedGap = Timing.RECORDED;
    int speed = 100;

    Routine(String name, int width, int height, int rotation) {
        this(UUID.randomUUID().toString(), name, width, height, rotation);
    }

    private Routine(String id, String name, int width, int height, int rotation) {
        this.id = id;
        this.name = name;
        this.width = width;
        this.height = height;
        this.rotation = rotation;
    }

    long sequenceDuration() {
        return sequenceDuration(fixedGap, speed);
    }

    long sequenceDuration(long gap, int speedPercent) {
        long total = 0;
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            if (i > 0) total += Timing.gap(step.gap, gap, speedPercent);
            total += Timing.stroke(step.duration, speedPercent);
        }
        return total;
    }

    JSONObject encode() throws JSONException {
        JSONArray entries = new JSONArray();
        for (Step step : steps) entries.put(step.encode());
        return new JSONObject().put("id", id).put("name", name)
                .put("width", width).put("height", height).put("rotation", rotation)
                .put("period", period).put("session", session)
                .put("fixedGap", fixedGap).put("speed", speed).put("steps", entries);
    }

    static Routine decode(JSONObject data) throws JSONException {
        Routine routine = new Routine(data.getString("id"), data.getString("name"),
                data.getInt("width"), data.getInt("height"), data.getInt("rotation"));
        routine.period = Timing.clamp(data.optLong("period", 2_000), Timing.MIN_PERIOD, Timing.MAX_PERIOD);
        routine.session = Timing.clamp(data.optLong("session", 30_000), 1_000, Timing.MAX_SESSION);
        routine.fixedGap = Timing.clamp(data.optLong("fixedGap", Timing.RECORDED), Timing.RECORDED, Timing.MAX_PERIOD);
        routine.speed = (int) Timing.clamp(data.optInt("speed", 100), 25, 300);
        JSONArray entries = data.getJSONArray("steps");
        if (entries.length() == 0 || entries.length() > MAX_STEPS) throw new JSONException("Invalid steps");
        for (int i = 0; i < entries.length(); i++) {
            routine.steps.add(Step.decode(entries.getJSONObject(i), routine.width, routine.height));
        }
        return routine;
    }
}
