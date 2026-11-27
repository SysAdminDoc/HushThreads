/*
 * Copyright 2026 HushThreads contributors
 * https://github.com/SysAdminDoc/HushThreads
 */
package app.morphe.extension.hushthreads.share;

import app.morphe.extension.hushthreads.settings.FamilyNames;
import app.morphe.extension.hushthreads.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.Setting;

/**
 * The Create group button on the share sheet.
 *
 * <p>Pick two people or more on a post's share sheet and Threads shows Send separately and, under
 * it, Create group. One Compose function draws that bar, and a flag Threads hands it says whether
 * the Create group button goes in. The patch asks {@link #show} for that flag right where Threads
 * checks it, after the send button is drawn. With the switch on the answer is no, and Threads takes
 * the path it already has with one person picked, so only Create group goes: Send separately keeps
 * its label and still sends to each person on their own.
 *
 * <p>Off, paused, before the settings are ready, or a failure in here, and Threads' own flag goes
 * back unchanged. The bar is drawn again each time the sheet changes, so a change of the switch
 * shows the next time the share sheet opens.
 */
public final class CreateGroupButton {
    static final String HIDDEN = "hid the share sheet's Create group button";
    /** Counted when Threads itself drew no Create group button, so there was nothing to hide. */
    static final String NONE = "Threads drew no Create group button";
    /** Prefix for a refusal's count label; one label per fixed reason from offBecause(). */
    static final String LEFT_TO_THREADS = "left the Create group button to Threads: ";
    /** The outcome last logged. Threads asks each time it draws the bar, so a log line marks a change. */
    private static volatile String lastLogged;

    private CreateGroupButton() { }

    /**
     * Right before the share sheet's bar checks whether to draw Create group, with Threads' answer.
     * Answers false while the switch is on, and Threads' answer otherwise. Never throws.
     */
    public static boolean show(boolean threads) {
        try {
            HookStatus.invoked(FamilyNames.HIDE_CREATE_GROUP_BUTTON);
            if (!threads) {
                HookStatus.counted(FamilyNames.HIDE_CREATE_GROUP_BUTTON, NONE);
                return false;
            }
            String off = offBecause();
            HookStatus.counted(FamilyNames.HIDE_CREATE_GROUP_BUTTON, off == null ? HIDDEN : LEFT_TO_THREADS + off);
            String outcome = off == null ? "the share sheet shows no Create group button"
                    : "Threads shows its Create group button, " + off;
            if (!outcome.equals(lastLogged)) {
                lastLogged = outcome;
                Logger.printDebug(() -> "Create group button: " + outcome);
            }
            return off != null;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_CREATE_GROUP_BUTTON, "share sheet", failure);
            return threads;
        }
    }

    /** Why the switch doesn't apply now, or null when it's on. Reads no setting before they're ready. */
    private static String offBecause() {
        if (!Utils.settingsReady()) return "settings not ready";
        if (!Settings.HIDE_CREATE_GROUP_BUTTON.get()) return Setting.isPaused() ? "HushThreads paused" : "switch off";
        return null;
    }
}
