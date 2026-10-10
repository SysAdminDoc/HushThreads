/*
 * Copyright 2026 HushThreads contributors
 * https://github.com/SysAdminDoc/HushThreads
 */
package app.morphe.extension.hushthreads.profile;

import app.morphe.extension.hushthreads.settings.FamilyNames;
import app.morphe.extension.hushthreads.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.Setting;

/**
 * The ghost post bubbles on profile pictures.
 *
 * <p>Threads draws a ghost post as a thought bubble on its author's picture in two places: on a
 * profile's header, and in a row of pictures at the top of the main feed. Each sits in a Compose
 * AnimatedVisibility, and Threads already hands it false when there's nothing to show, so the
 * bubble or the row takes no room. The patch asks {@link #showOnProfile} for the header bubble's
 * flag first thing in its function, and {@link #showTray} for the row's flag right before it goes
 * in. With the switch on both answer no. The picture under the bubble, its tap, and ghost posts in
 * the feed stay as Threads has them.
 *
 * <p>Off, paused, before the settings are ready, or a failure in here, and Threads' own flag goes
 * back unchanged. Threads keeps what it has drawn until something changes in it, so a change of
 * the switch shows after a restart.
 */
public final class GhostPostBubbles {
    static final String HIDDEN_ON_PROFILE = "hid a profile's ghost post bubble";
    static final String HIDDEN_IN_FEED = "hid the feed's row of ghost post bubbles";
    /** Counted when Threads itself showed nothing, so there was nothing to hide. */
    static final String NONE = "Threads showed no ghost post bubble";
    /** Prefix for a refusal's count label; one label per fixed reason from offBecause(). */
    static final String LEFT_TO_THREADS = "left ghost post bubbles to Threads: ";
    /** The outcome last logged. Threads asks each time it draws a bubble, so a log line marks a change. */
    private static volatile String lastLogged;

    private GhostPostBubbles() { }

    /**
     * First thing in the profile header's ghost post bubble, with Threads' answer to whether it
     * shows. Answers false while the switch is on, and Threads' answer otherwise. Never throws.
     */
    public static boolean showOnProfile(boolean threads) {
        return answer(threads, HIDDEN_ON_PROFILE, "profile header");
    }

    /**
     * Right before the main feed hands its row of ghost post bubbles to AnimatedVisibility, with
     * Threads' answer to whether the row shows. Answers false while the switch is on, and Threads'
     * answer otherwise. Never throws.
     */
    public static boolean showTray(boolean threads) {
        return answer(threads, HIDDEN_IN_FEED, "main feed");
    }

    private static boolean answer(boolean threads, String hidden, String where) {
        try {
            HookStatus.invoked(FamilyNames.HIDE_GHOST_POST_BUBBLES);
            if (!threads) {
                HookStatus.counted(FamilyNames.HIDE_GHOST_POST_BUBBLES, NONE);
                return false;
            }
            String off = offBecause();
            HookStatus.counted(FamilyNames.HIDE_GHOST_POST_BUBBLES, off == null ? hidden : LEFT_TO_THREADS + off);
            String outcome = off == null ? "ghost post bubbles hidden" : "Threads shows its ghost post bubbles, " + off;
            if (!outcome.equals(lastLogged)) {
                lastLogged = outcome;
                Logger.printDebug(() -> "Ghost post bubbles: " + outcome);
            }
            return off != null;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_GHOST_POST_BUBBLES, where, failure);
            return threads;
        }
    }

    /** Why the switch doesn't apply now, or null when it's on. Reads no setting before they're ready. */
    private static String offBecause() {
        if (!Utils.settingsReady()) return "settings not ready";
        if (!Settings.HIDE_GHOST_POST_BUBBLES.get()) return Setting.isPaused() ? "HushThreads paused" : "switch off";
        return null;
    }
}
