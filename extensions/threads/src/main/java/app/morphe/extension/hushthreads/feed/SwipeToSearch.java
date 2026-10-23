/*
 * Copyright 2026 HushThreads contributors
 * https://github.com/SysAdminDoc/HushThreads
 */
package app.morphe.extension.hushthreads.feed;

import app.morphe.extension.hushthreads.settings.FamilyNames;
import app.morphe.extension.hushthreads.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.Setting;

/**
 * The swipe from the feed into Search.
 *
 * <p>On some accounts Threads puts Search beside the feed in one pager on the home screen, so a
 * sideways swipe on the feed slides into Search. A server flag settles it when Threads builds the
 * home screen's state, and the state keeps the answer, through every copy of it, for as long as
 * the screen lives. An account without the flag gets the feed alone, and its Search button opens
 * Search as a screen of its own. The patch hands {@link #allow} the answer as the state takes it.
 * With the switch on the answer is no, and Threads builds the home screen it builds for an account
 * without the swipe.
 *
 * <p>Off, paused, before the settings are ready, or a failure in here, and Threads' own answer goes
 * back unchanged. Threads keeps the home screen it built until it restarts, so a change of the
 * switch shows after a restart.
 */
public final class SwipeToSearch {
    static final String STOPPED = "kept Search out of the feed's swipe";
    /** Counted when Threads' answer was already no, on an account without the swipe or a copy of a stopped state. */
    static final String NONE = "no swipe to Search to stop";
    /** Prefix for a refusal's count label; one label per fixed reason from offBecause(). */
    static final String LEFT_TO_THREADS = "left the swipe to Search to Threads: ";
    /** The outcome last logged. Each copy of the home screen's state asks again, so a log line marks a change. */
    private static volatile String lastLogged;

    private SwipeToSearch() { }

    /**
     * As the home screen's state takes Threads' answer to whether Search sits a swipe from the
     * feed. Answers false while the switch is on, and Threads' answer otherwise. Never throws.
     */
    public static boolean allow(boolean threads) {
        try {
            HookStatus.invoked(FamilyNames.SWIPE_TO_SEARCH);
            if (!threads) {
                HookStatus.counted(FamilyNames.SWIPE_TO_SEARCH, NONE);
                return false;
            }
            String off = offBecause();
            HookStatus.counted(FamilyNames.SWIPE_TO_SEARCH, off == null ? STOPPED : LEFT_TO_THREADS + off);
            String outcome = off == null ? "the feed doesn't swipe into Search" : "Threads keeps its swipe into Search, " + off;
            if (!outcome.equals(lastLogged)) {
                lastLogged = outcome;
                Logger.printDebug(() -> "Swipe to Search: " + outcome);
            }
            return off != null;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SWIPE_TO_SEARCH, "home screen state", failure);
            return threads;
        }
    }

    /** Why the switch doesn't apply now, or null when it's on. Reads no setting before they're ready. */
    private static String offBecause() {
        if (!Utils.settingsReady()) return "settings not ready";
        if (!Settings.DISABLE_SWIPE_TO_SEARCH.get()) return Setting.isPaused() ? "HushThreads paused" : "switch off";
        return null;
    }
}
