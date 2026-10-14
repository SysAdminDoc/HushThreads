/*
 * Copyright 2026 HushThreads contributors
 * https://github.com/SysAdminDoc/HushThreads
 */
package app.morphe.extension.hushthreads.search;

import app.morphe.extension.hushthreads.settings.FamilyNames;
import app.morphe.extension.hushthreads.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.Setting;

/**
 * The Follow suggestions carousel Threads puts on its Search screen.
 *
 * <p>Search builds its screen as a list, one part after another, and adds the carousel only when
 * its list of suggested accounts isn't empty. The patch asks {@link #showCarousel} first thing past
 * that check, and with the switch on Search goes the way it goes when there are no suggestions, so
 * the carousel never joins the list and leaves no gap. Topic chips, trends, search results and the
 * suggestions Threads mixes into its trends list are left as they are, and so is anyone you follow.
 *
 * <p>It's the second switch of Hide suggested users, next to the one that takes suggestion cards
 * out of the feed and off profiles, and each works without the other. Off, paused, before the
 * settings are ready, or a failure in here, and Threads keeps its carousel. A Search screen already
 * built keeps what it drew until Threads builds it again.
 */
public final class SearchSuggestions {
    static final String HIDDEN_CAROUSEL = "kept the Follow suggestions carousel out of Search";
    /** Prefix for a refusal's count label; one label per fixed reason from offBecause(). */
    static final String LEFT_TO_THREADS = "left Search's Follow suggestions to Threads: ";
    /** The outcome last logged. Threads asks each time it builds Search, so a log line marks a change. */
    private static volatile String lastLogged;

    private SearchSuggestions() { }

    /**
     * First thing on the path where Search adds its Follow suggestions carousel. Answers false while
     * the switch is on, so the carousel stays out, and true otherwise. Never throws.
     */
    public static boolean showCarousel() {
        try {
            HookStatus.invoked(FamilyNames.HIDE_SUGGESTED_USERS);
            String off = offBecause();
            HookStatus.counted(FamilyNames.HIDE_SUGGESTED_USERS, off == null ? HIDDEN_CAROUSEL : LEFT_TO_THREADS + off);
            String outcome = off == null ? "Search shows no Follow suggestions" : "Threads shows its Search suggestions, " + off;
            if (!outcome.equals(lastLogged)) {
                lastLogged = outcome;
                Logger.printDebug(() -> "Search suggestions: " + outcome);
            }
            return off != null;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_SUGGESTED_USERS, "search carousel", failure);
            return true;
        }
    }

    /** Why the switch doesn't apply now, or null when it's on. Reads no setting before they're ready. */
    private static String offBecause() {
        if (!Utils.settingsReady()) return "settings not ready";
        if (!Settings.HIDE_SEARCH_SUGGESTIONS.get()) return Setting.isPaused() ? "HushThreads paused" : "switch off";
        return null;
    }
}
