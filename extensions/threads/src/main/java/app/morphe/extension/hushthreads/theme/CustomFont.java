/*
 * Copyright 2026 HushThreads contributors
 * https://github.com/SysAdminDoc/HushThreads
 */
package app.morphe.extension.hushthreads.theme;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.os.CancellationSignal;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.hushthreads.settings.FamilyNames;
import app.morphe.extension.hushthreads.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.Setting;

/**
 * Threads' text in a font file the person picks (#28).
 *
 * <p>Threads loads its fonts through one repository, by an asset file's name, an Android font
 * family's name or a font resource, and keeps what it loaded. The patch hands each load's result
 * to this class with the name it loaded it by. While the switch is on and a font file is kept, a
 * text font (Threads' own Optimistic and Prism Sans, and Android's sans-serif families) comes back
 * as the picked font, at the weight and slant Threads loaded. Icon and logo fonts stay as they are.
 *
 * <p>The picked file is copied into Threads' own files, so it keeps working after the original is
 * moved or deleted. A variable font gets its weight from its own axis, a fixed one is made bolder
 * or slanted by Android where Threads asks for that.
 *
 * <p>Off, paused, before the settings are ready, with no file kept, a file Android can't load, or a
 * failure in here, and Threads keeps its own font. Threads keeps the fonts it loaded, so a change
 * shows after a restart.
 */
public final class CustomFont {
    /** The kept copy of the picked font, in Threads' files. */
    static final String KEPT = "hushthreads_custom_font";
    /** The picked file's name as its app gave it, beside the copy. */
    static final String KEPT_NAME = "hushthreads_custom_font.name";
    /** A copy still being written, put in the kept one's place once it loads. */
    static final String PART = "hushthreads_custom_font.part";
    /** Larger than any font file people use for text, collections of Chinese, Japanese and Korean included. */
    static final long MAX_BYTES = 64L * 1024 * 1024;
    /** The size a picked file may reach: {@link #MAX_BYTES}, unless a test sets its own. */
    static volatile long maxBytes = MAX_BYTES;

    /** Count label for a font swapped. */
    static final String SWAPPED = "used the picked font";
    /** Prefix for a refusal's count label; one label per fixed reason. */
    static final String LEFT_TO_THREADS = "left the font to Threads: ";

    /** How a picked file went. */
    public enum Outcome { KEPT, TOO_LARGE, NOT_A_FONT, UNREADABLE }

    /** Makes a typeface from a font file. Android's, unless a test stands in. */
    interface Loader {
        /** The font in [file] at [weight], slanted when [italic], or null when Android can't load it. */
        @Nullable
        Typeface load(File file, int weight, boolean italic);
    }

    static final Loader ANDROID = (file, weight, italic) -> {
        Typeface.Builder builder = new Typeface.Builder(file);
        // A variable font draws the weight itself. A fixed one is left as it is, and
        // Typeface.create below makes it bolder or slanted where it has to.
        if (variable(file)) builder.setFontVariationSettings("'wght' " + weight).setWeight(weight);
        Typeface base = builder.build();
        return base == null ? null : Typeface.create(base, weight, italic);
    };

    static volatile Loader loader = ANDROID;

    /** The picked font at each weight and slant asked for, by weight * 2 plus one when slanted. */
    private static final Map<Integer, Typeface> made = new ConcurrentHashMap<>();
    /** The kept file wouldn't load, so it isn't tried again for every font Threads asks for. */
    private static volatile boolean unusable;
    @Nullable
    private static volatile String lastLogged;

    private CustomFont() { }

    /** After Threads loads the asset font [name] into [loaded]. Never throws. */
    public static Typeface assetFont(@Nullable Typeface loaded, @Nullable String name) {
        try {
            return swap(loaded, name);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CUSTOM_FONT, "custom font", failure);
            return loaded;
        }
    }

    /** After Threads loads Android's font family [name] into [loaded]. Never throws. */
    public static Typeface systemFont(@Nullable Typeface loaded, @Nullable String name) {
        try {
            return swap(loaded, name);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CUSTOM_FONT, "custom font", failure);
            return loaded;
        }
    }

    /** After Threads loads the font resource [id] into [loaded]. Never throws. */
    public static Typeface resourceFont(@Nullable Typeface loaded, int id) {
        try {
            Context context = Utils.getContext();
            String name = null;
            if (context != null) {
                try {
                    name = context.getResources().getResourceEntryName(id);
                } catch (Resources.NotFoundException unknown) {
                    // Not one of Threads' resources, so not one of its text fonts.
                }
            }
            return swap(loaded, name);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CUSTOM_FONT, "custom font", failure);
            return loaded;
        }
    }

    private static Typeface swap(@Nullable Typeface loaded, @Nullable String name) {
        HookStatus.invoked(FamilyNames.CUSTOM_FONT);
        String off = offBecause();
        if (off == null && !isTextFont(name)) off = "not a text font";
        Typeface own = null;
        if (off == null) {
            own = picked(loaded);
            if (own == null) off = unusable ? "the font file won't load" : "no font file picked";
        }
        HookStatus.counted(FamilyNames.CUSTOM_FONT, off == null ? SWAPPED : LEFT_TO_THREADS + off);
        log(off);
        return own != null ? own : loaded;
    }

    /** Why the switch doesn't apply now, or null when it's on. Reads no setting before they're ready. */
    @Nullable
    private static String offBecause() {
        if (!Utils.settingsReady()) return "settings not ready";
        if (!Settings.CUSTOM_FONT.get()) return Setting.isPaused() ? "HushThreads paused" : "switch off";
        return null;
    }

    /**
     * Whether [name] is a font Threads sets text in: its own Optimistic and Prism Sans (IGUI is
     * Prism Sans' file name), and Android's sans-serif families. Icon fonts, logo fonts and the
     * others aren't.
     */
    static boolean isTextFont(@Nullable String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.startsWith("sans-serif") || lower.contains("optimistic") || lower.startsWith("igui")
                || lower.startsWith("prism");
    }

    /** The picked font at [loaded]'s weight and slant, or null when none is kept or it won't load. */
    @Nullable
    private static Typeface picked(@Nullable Typeface loaded) {
        if (unusable) return null;
        int weight = loaded != null ? loaded.getWeight() : 400;
        // Typeface.create takes 1 to 1000 and throws on anything else.
        if (weight < 1 || weight > 1000) weight = 400;
        boolean italic = loaded != null && loaded.isItalic();
        int key = weight * 2 + (italic ? 1 : 0);
        Typeface ready = made.get(key);
        if (ready != null) return ready;
        Context context = Utils.getContext();
        if (context == null) return null;
        File file = new File(context.getFilesDir(), KEPT);
        if (!file.isFile()) return null;
        Typeface typeface = loader.load(file, weight, italic);
        if (typeface == null) {
            unusable = true;
            return null;
        }
        made.put(key, typeface);
        return typeface;
    }

    /**
     * Whether the font in [file] has a weight axis of its own: its table list names "fvar". A
     * collection's first font is the one Android loads, so that's the one read.
     */
    static boolean variable(File file) {
        try (RandomAccessFile font = new RandomAccessFile(file, "r")) {
            long start = 0;
            if (font.length() < 12) return false;
            if (font.readInt() == 0x74746366) { // "ttcf"
                font.seek(12);
                start = font.readInt() & 0xffffffffL;
            }
            font.seek(start + 4);
            int tables = font.readUnsignedShort();
            for (int index = 0; index < tables; index++) {
                font.seek(start + 12 + 16L * index);
                if (font.readInt() == 0x66766172) return true; // "fvar"
            }
            return false;
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }

    /**
     * Copies a picked font from [in] into Threads' files under [name], in place of any kept one, once
     * Android loads it. Nothing changes when it's too large, isn't a font, can't be read, or
     * [cancel] is cancelled first. Never on the main thread.
     */
    public static Outcome keep(Context context, InputStream in, @Nullable String name, CancellationSignal cancel) {
        File folder = context.getFilesDir();
        File part = new File(folder, PART);
        try {
            long total = 0;
            // Closed before anything deletes it: Windows, where the tests run, keeps an open file.
            try (OutputStream out = new FileOutputStream(part)) {
                byte[] buffer = new byte[64 * 1024];
                for (int read; (read = in.read(buffer)) != -1; ) {
                    total += read;
                    if (total > maxBytes) break;
                    out.write(buffer, 0, read);
                }
            }
            if (total > maxBytes) return dropped(part, Outcome.TOO_LARGE);
            if (loader.load(part, 400, false) == null) return dropped(part, Outcome.NOT_A_FONT);
            if (cancel.isCanceled()) return dropped(part, Outcome.UNREADABLE);
            File kept = new File(folder, KEPT);
            if (!part.renameTo(kept) && !(kept.delete() && part.renameTo(kept))) return dropped(part, Outcome.UNREADABLE);
        } catch (IOException | RuntimeException failure) {
            Logger.printInfo(() -> "Font file not copied: " + failure.getClass().getSimpleName());
            return dropped(part, Outcome.UNREADABLE);
        }
        File label = new File(folder, KEPT_NAME);
        if (name == null || name.trim().isEmpty()) {
            //noinspection ResultOfMethodCallIgnored
            label.delete();
        } else {
            try (OutputStream out = new FileOutputStream(label)) {
                out.write(name.trim().getBytes(StandardCharsets.UTF_8));
            } catch (IOException failure) {
                // The font is kept all the same; its row names it generically.
                //noinspection ResultOfMethodCallIgnored
                label.delete();
            }
        }
        made.clear();
        unusable = false;
        Logger.printDebug(() -> "Custom font: a new font file is kept");
        return Outcome.KEPT;
    }

    private static Outcome dropped(File part, Outcome outcome) {
        //noinspection ResultOfMethodCallIgnored
        part.delete();
        return outcome;
    }

    /**
     * The kept font's name as its app gave it, an empty string for a kept font with no name, or
     * null when none is kept.
     */
    @Nullable
    public static String keptName(Context context) {
        File folder = context.getFilesDir();
        if (!new File(folder, KEPT).isFile()) return null;
        File label = new File(folder, KEPT_NAME);
        if (!label.isFile() || label.length() > 4096) return "";
        try (InputStream in = new FileInputStream(label)) {
            byte[] bytes = new byte[(int) label.length()];
            int at = 0;
            for (int read; at < bytes.length && (read = in.read(bytes, at, bytes.length - at)) != -1; ) at += read;
            return new String(bytes, 0, at, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            return "";
        }
    }

    private static void log(@Nullable String off) {
        String outcome = off == null ? "swapped" : "left to Threads, " + off;
        if (outcome.equals(lastLogged)) return;
        lastLogged = outcome;
        Logger.printDebug(() -> "Custom font: " + outcome);
    }

    /** Forgets every font made and puts Android's loader back. Tests only. */
    static void forget() {
        made.clear();
        unusable = false;
        lastLogged = null;
        loader = ANDROID;
    }
}
