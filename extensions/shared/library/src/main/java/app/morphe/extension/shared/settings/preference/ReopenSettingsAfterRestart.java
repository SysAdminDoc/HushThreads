/*
 * Forked from https://github.com/SysAdminDoc/HushPinterest at 44fb9338 (GPL-3.0),
 * modified for HushThreads (Threads), 2026.
 *
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 *
 * Built on SysAdminDoc/hushfeed (GPL-3.0).
 */
package app.morphe.extension.shared.settings.preference;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.HushThreadsPause;
import app.morphe.extension.shared.settings.Setting;

/**
 * The settings screen's restart row, and the way back to the page it was tapped on.
 *
 * <p>Before the app restarts, the page goes into the settings store with the time, as a one-shot
 * marker. When the app starts again it takes the marker: the marker is cleared first, so a page
 * that fails to open is never tried again on every start, and only a marker younger than
 * {@link #MAX_AGE_MILLIS} opens the screen. An old one, from a restart that never finished or a
 * phone that was switched off, is dropped. The time is the phone's time since it booted, which a
 * clock change can't move.
 */
public final class ReopenSettingsAfterRestart {
    static final String PAGE_KEY = "hush_reopen_settings_page";
    static final String TIME_KEY = "hush_reopen_settings_at";

    /** How long a marker stays good: a slow phone's cold start fits easily, and a later start doesn't count. */
    public static final long MAX_AGE_MILLIS = 90_000;

    /** What a restart does. The app's own restart, but for tests, where it would end the test run. */
    public interface Restarter {
        void restart(Context context);
    }

    /** What opens the settings screen on a page, at process start. */
    public interface Opener {
        void open(String page);
    }

    private static final Restarter APP_RESTART = Utils::restartApp;
    private static volatile Restarter restarter = APP_RESTART;

    private ReopenSettingsAfterRestart() {
    }

    /** Swaps the restart for [replacement], or puts the app's own back with null. */
    public static void setRestarterForTests(@Nullable Restarter replacement) {
        restarter = replacement == null ? APP_RESTART : replacement;
    }

    /** Writes the marker for [page] at [now]. Committed, since the process ends right after. */
    public static boolean remember(SharedPreferences store, String page, long now) {
        return store.edit()
                .putString(PAGE_KEY, page == null ? "" : page)
                .putLong(TIME_KEY, now)
                .commit();
    }

    /**
     * Takes the marker: clears it, then answers its page if it was written less than
     * {@link #MAX_AGE_MILLIS} before [now], and null otherwise. A second call answers null.
     */
    @Nullable
    public static String takeFresh(SharedPreferences store, long now) {
        if (!store.contains(PAGE_KEY) && !store.contains(TIME_KEY)) return null;
        String page;
        long at;
        try {
            page = store.getString(PAGE_KEY, null);
            at = store.getLong(TIME_KEY, -1);
        } catch (ClassCastException wrongType) {
            page = null;
            at = -1;
        }
        // Cleared before anything opens, so a screen that fails to open isn't retried on every start.
        store.edit().remove(PAGE_KEY).remove(TIME_KEY).commit();
        long age = now - at;
        if (page == null || at < 0 || age < 0 || age >= MAX_AGE_MILLIS) return null;
        return page;
    }

    /** At process start: takes the marker and, when it's fresh, hands its page to [opener]. */
    public static boolean reopenIfPending(SharedPreferences store, long now, Opener opener) {
        String page = takeFresh(store, now);
        if (page == null) return false;
        opener.open(page);
        return true;
    }

    /** {@link #reopenIfPending} over the settings store, now. Never throws: a start goes on whatever happens here. */
    public static boolean reopenIfPending(Opener opener) {
        try {
            return reopenIfPending(store(), SystemClock.elapsedRealtime(), opener);
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not read where settings were before the restart", ex);
            return false;
        }
    }

    /** The restart row's tap: remembers [page], then restarts the app. */
    public static void restartInto(Context context, String page) {
        try {
            remember(store(), page, SystemClock.elapsedRealtime());
        } catch (Exception ex) {
            // The restart is what was asked for. Landing on the app's first screen is the fallback.
            Logger.printInfo(() -> "Could not remember the settings page before restarting", ex);
        }
        restarter.restart(context);
    }

    /**
     * How many changes a restart would apply: the restart-gated switches changed in this process,
     * with Pause counted once, as the difference between this start and the next. Pause and safe
     * mode are left out of the switch count because Resume clears a pause without going through
     * them, and the next start's pause already says it.
     */
    public static int changesWaiting(Context context) {
        List<String> keys;
        synchronized (AbstractPreferenceFragment.restartPending) {
            keys = new ArrayList<>(AbstractPreferenceFragment.restartPending);
        }
        int count = 0;
        for (String key : keys) {
            if (!BaseSettings.PAUSED.key.equals(key) && !BaseSettings.SAFE_MODE.key.equals(key)) count++;
        }
        if (context != null && HushThreadsPause.isPaused() != HushThreadsPause.pausesNextStart(context)) count++;
        return count;
    }

    private static SharedPreferences store() {
        return Setting.preferences.preferences;
    }
}
