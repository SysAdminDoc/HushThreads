/*
 * Copyright 2026 HushThreads contributors
 * https://github.com/SysAdminDoc/HushThreads
 */
package app.morphe.extension.hushthreads.settings;

import app.morphe.extension.shared.L10n;

/**
 * A long press on the Threads logo at the top of the home feed opens the HushThreads screen.
 * Threads makes its logo clickable with Compose's clickable, and the settings patch sends that one
 * call here instead, to Compose's combined clickable with the same arguments: Threads' own tap stays
 * the tap, and {@link #LONG_PRESS} becomes the long press. Nothing here asks whether HushThreads is
 * paused, since its screen is where it's resumed.
 */
@SuppressWarnings("unused")
public final class ThreadsLogo {
    /**
     * The one long press every draw of the logo gets. Compose compares a clickable's handlers each
     * time the header is drawn, and the same one each time leaves the logo's click as it was.
     */
    static final LongPress LONG_PRESS = new LongPress();

    private ThreadsLogo() {
    }

    /** TalkBack names the action in the current app language each time the header is drawn. */
    public static String longClickLabel() {
        return L10n.t("Open HushThreads settings");
    }

    /**
     * Written by the settings patch, and called by Threads' feed header where it makes the logo
     * clickable: Compose's combined clickable with Threads' interaction source, indication, modifier,
     * role, label, tap and enabled flag, {@link #LONG_PRESS} as the long press, and no double tap.
     * Every parameter but the label and the flag is one of Threads' own types, which this build
     * can't name. Until the patch writes it, the logo just keeps its modifier.
     */
    public static Object clickable(Object interactionSource, Object indication, Object modifier, Object role,
                                   String onClickLabel, Object onClick, boolean enabled) {
        return modifier;
    }

    /**
     * The logo's long press. The settings patch makes it a {@code kotlin.jvm.functions.Function0},
     * which this build can't name, and Compose calls {@link #invoke()} when the logo is long-pressed.
     */
    public static final class LongPress {
        public Object invoke() {
            SettingsEntry.openFromLogo();
            return null;
        }
    }
}
