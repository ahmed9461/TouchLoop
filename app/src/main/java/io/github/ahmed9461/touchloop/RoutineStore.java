package io.github.ahmed9461.touchloop;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;

final class RoutineStore {
    static final int MAX_ROUTINES = 30;
    static final int MIN_TOOLBAR_SIZE = 75;
    static final int MAX_TOOLBAR_SIZE = 125;
    static final int MIN_TOOLBAR_OPACITY = 35;
    private final SharedPreferences prefs;

    RoutineStore(Context context) {
        prefs = context.getSharedPreferences("touchloop", Context.MODE_PRIVATE);
    }

    ArrayList<Routine> list() {
        ArrayList<Routine> routines = new ArrayList<>();
        try {
            JSONArray data = new JSONArray(prefs.getString("routines", "[]"));
            for (int i = 0; i < Math.min(data.length(), MAX_ROUTINES); i++) {
                try {
                    routines.add(Routine.decode(data.getJSONObject(i)));
                } catch (JSONException error) {
                    Log.w("TouchLoop", "Skipped malformed routine", error);
                }
            }
        } catch (JSONException error) {
            Log.w("TouchLoop", "Could not read routines", error);
        }
        return routines;
    }

    Routine selected() {
        String id = prefs.getString("selected", "");
        ArrayList<Routine> routines = list();
        for (Routine routine : routines) if (routine.id.equals(id)) return routine;
        return routines.isEmpty() ? null : routines.get(0);
    }

    void select(Routine routine) {
        prefs.edit().putString("selected", routine.id).apply();
    }

    boolean save(Routine routine) {
        ArrayList<Routine> routines = list();
        for (int i = 0; i < routines.size(); i++) {
            if (routines.get(i).id.equals(routine.id)) {
                routines.set(i, routine);
                return write(routines, routine.id);
            }
        }
        if (routines.size() >= MAX_ROUTINES) return false;
        routines.add(0, routine);
        return write(routines, routine.id);
    }

    void delete(Routine routine) {
        ArrayList<Routine> routines = list();
        routines.removeIf(entry -> entry.id.equals(routine.id));
        write(routines, routines.isEmpty() ? "" : routines.get(0).id);
    }

    boolean liveCapture() {
        return prefs.getBoolean("liveCapture", false);
    }

    void setLiveCapture(boolean live) {
        prefs.edit().putBoolean("liveCapture", live).apply();
    }

    int toolbarSize() {
        return prefs.getInt("toolbarSize", 100);
    }

    int toolbarOpacity() {
        return prefs.getInt("toolbarOpacity", 100);
    }

    void setToolbarAppearance(int size, int opacity) {
        prefs.edit().putInt("toolbarSize", size).putInt("toolbarOpacity", opacity).apply();
    }

    private boolean write(ArrayList<Routine> routines, String selected) {
        JSONArray data = new JSONArray();
        try {
            for (Routine routine : routines) data.put(routine.encode());
        } catch (JSONException error) {
            Log.w("TouchLoop", "Could not encode routines", error);
            return false;
        }
        prefs.edit().putString("routines", data.toString()).putString("selected", selected).apply();
        return true;
    }
}
