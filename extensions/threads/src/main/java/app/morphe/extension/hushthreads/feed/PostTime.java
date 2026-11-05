/*
 * Copyright 2026 HushThreads contributors
 * https://github.com/SysAdminDoc/HushThreads
 */
package app.morphe.extension.hushthreads.feed;

import android.content.Context;
import android.text.format.DateUtils;

import androidx.annotation.Nullable;

import app.morphe.extension.hushthreads.settings.FamilyNames;
import app.morphe.extension.hushthreads.settings.Settings;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.Setting;

/**
 * The time on a post's header.
 *
 * <p>Threads writes a post's time as "3h" or "2d" while the post is recent and as a date once it's
 * older, in one function the feed, an opened post and its replies share. The patch asks
 * {@link #exact} first thing in that function. With the switch on it answers the date and time the
 * post went up, in the phone's language and its 12 or 24-hour setting, and Threads shows that
 * instead. This year's posts leave the year out.
 *
 * <p>Off, paused, before the settings are ready, for a time no post can have, or a failure in here,
 * it answers null and Threads writes its own. Threads keeps the text of a header it has drawn, so
 * one already on screen changes the next time it's drawn.
 *
 * <p>TalkBack reads an older post's header text as it's shown, but Threads writes a recent post's
 * spoken time ("3 hours ago") with a formatter of its own. The patch hands that to {@link #spoken}, so with the
 * switch on TalkBack reads the same date and time the header shows.
 */
public final class PostTime {
    static final String SHOWN = "post times shown in full";
    /** Prefix for a refusal's count label; one label per fixed reason. */
    static final String LEFT_TO_THREADS = "left the post time to Threads: ";

    /** Date and time, the month short. The year shows only when it isn't this year. */
    static final int FLAGS = DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_ABBREV_MONTH;

    /** 2001-09-09 and 2286-11-20 in seconds: anything outside is no post's time, NaN included. */
    private static final double EARLIEST = 1e9;
    private static final double LATEST = 1e10;

    private PostTime() { }

    /**
     * The text for a post header's time, given in [seconds] since 1970, or null to leave Threads'
     * own. Never throws.
     */
    @Nullable
    public static String exact(@Nullable Context context, double seconds) {
        try {
            HookStatus.invoked(FamilyNames.EXACT_POST_TIMES);
            String off = offBecause();
            if (off == null) off = unusable(context, seconds);
            if (off != null) {
                HookStatus.counted(FamilyNames.EXACT_POST_TIMES, LEFT_TO_THREADS + off);
                return null;
            }
            String text = format(context, seconds);
            if (text == null || text.isEmpty()) {
                HookStatus.counted(FamilyNames.EXACT_POST_TIMES, LEFT_TO_THREADS + "no text for the time");
                return null;
            }
            HookStatus.counted(FamilyNames.EXACT_POST_TIMES, SHOWN);
            return text;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.EXACT_POST_TIMES, "post header time", failure);
            return null;
        }
    }

    /**
     * What TalkBack reads for a recent post's time: the header's text from {@link #exact} while
     * the switch is on, else [threads], the description Threads wrote. Counts nothing, since the
     * header's own call already did. Never throws.
     */
    @Nullable
    public static String spoken(@Nullable String threads, @Nullable Context context, double seconds) {
        try {
            if (offBecause() != null || unusable(context, seconds) != null) return threads;
            String text = format(context, seconds);
            return text == null || text.isEmpty() ? threads : text;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.EXACT_POST_TIMES, "post header spoken time", failure);
            return threads;
        }
    }

    private static String format(Context context, double seconds) {
        return DateUtils.formatDateTime(context, Math.round(seconds * 1000d), FLAGS);
    }

    /** Why the switch doesn't apply now, or null when it's on. Reads no setting before they're ready. */
    @Nullable
    private static String offBecause() {
        if (!Utils.settingsReady()) return "settings not ready";
        if (!Settings.EXACT_POST_TIMES.get()) return Setting.isPaused() ? "HushThreads paused" : "switch off";
        return null;
    }

    /** Why this call can't be answered, or null when it can. */
    @Nullable
    private static String unusable(@Nullable Context context, double seconds) {
        if (context == null) return "no context";
        if (!(seconds >= EARLIEST && seconds <= LATEST)) return "not a post's time";
        return null;
    }
}
