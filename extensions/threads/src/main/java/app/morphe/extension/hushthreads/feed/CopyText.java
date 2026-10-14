/*
 * Copyright 2026 HushThreads contributors
 * https://github.com/SysAdminDoc/HushThreads
 */
package app.morphe.extension.hushthreads.feed;

import app.morphe.extension.hushthreads.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.Setting;

/**
 * What a long press on a post's text does (#9).
 *
 * <p>Threads lays out a post's text in one lambda of its post row, for the feed, profiles and an
 * opened post alike. Its long press opens Threads' Copy text sheet, which copies the whole post,
 * links included. An opened post hands the row an override for that long press with no action in
 * it, and the row puts a press that does nothing in its place, so on 451 holding an opened post's
 * text does nothing. Threads' text selection, which some accounts get on opened posts, picks out
 * words instead and leaves links behind.
 *
 * <p>With the switch on, {@link #longPress} drops an override that has no action, so the row
 * keeps its Copy text press, and {@link #selection} answers no to PostText's selection flag. An
 * override that brings its own action stays as it is. Off, paused, before the settings are ready,
 * or a failure in here, and Threads' own values go back unchanged.
 */
public final class CopyText {
    /** The name its hooks report under in Hook status. The settings patch carries them, so no family owns them. */
    public static final String NAME = "Copy text on long press";
    static final String KEPT = "kept Copy text on an opened post";
    static final String UNSELECTED = "turned text selection off";
    /** Prefix for a refusal's count label; one label per fixed reason from offBecause(). */
    static final String LEFT_TO_THREADS = "left the long press to Threads: ";
    /** The outcome last logged. Threads asks every time a post's text recomposes, so a log line marks a change. */
    private static volatile String lastLogged;

    private CopyText() { }

    /**
     * Called with the long press override a post row was handed and the action in it, just
     * before the row checks for an override. Only an override with no action is a question: any
     * other comes back as it is, uncounted. Answers null, so the row keeps its own Copy text
     * press, while the switch is on. Never throws.
     */
    public static Object longPress(Object override, Object action) {
        if (override == null || action != null) return override;
        try {
            HookStatus.invoked(NAME);
            String off = offBecause();
            HookStatus.counted(NAME, off == null ? KEPT : LEFT_TO_THREADS + off);
            log(off == null ? "holding an opened post's text opens Copy text"
                    : "holding an opened post's text is left to Threads, " + off);
            return off == null ? null : override;
        } catch (Throwable failure) {
            HookStatus.threw(NAME, "post long press", failure);
            return override;
        }
    }

    /**
     * First thing in PostText, with whether Threads turned text selection on for this post. Only
     * a yes is a question: no stays no, uncounted. Answers no while the switch is on, so the long
     * press goes to Copy text. A failure hands back Threads' answer, as it would be unpatched.
     */
    public static boolean selection(boolean threads) {
        if (!threads) return false;
        try {
            HookStatus.invoked(NAME);
            String off = offBecause();
            HookStatus.counted(NAME, off == null ? UNSELECTED : LEFT_TO_THREADS + off);
            log(off == null ? "post text selection is off, Copy text instead"
                    : "post text selection is Threads' own, " + off);
            return off != null;
        } catch (Throwable failure) {
            HookStatus.threw(NAME, "post text selection", failure);
            return true;
        }
    }

    private static void log(String outcome) {
        if (outcome.equals(lastLogged)) return;
        lastLogged = outcome;
        Logger.printDebug(() -> "Copy text: " + outcome);
    }

    /** Why the switch doesn't apply now, or null when it's on. Reads no setting before they're ready. */
    private static String offBecause() {
        if (!Utils.settingsReady()) return "settings not ready";
        if (!Settings.COPY_TEXT.get()) return Setting.isPaused() ? "HushThreads paused" : "switch off";
        return null;
    }
}
