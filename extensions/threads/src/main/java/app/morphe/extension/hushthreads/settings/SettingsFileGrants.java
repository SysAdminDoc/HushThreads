/*
 * Copyright 2026 HushThreads contributors
 * https://github.com/SysAdminDoc/HushThreads
 */
package app.morphe.extension.hushthreads.settings;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.annotation.Nullable;

import java.util.HashSet;
import java.util.Set;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * The lasting access {@link SettingsFilePicker} takes to a picked settings file, and its end.
 *
 * <p>Android keeps a lasting grant through process ends and restarts. The settings let go of it
 * once the file is read or written, but a process Android ends before that never gets there, and
 * Threads would keep access to the user's file for good. So each grant is written down before it's
 * taken and crossed off when it's let go, and each start of Threads lets go of what an earlier
 * process left. Android keeps one grant per file and app, so the list holds files, not takes.
 */
final class SettingsFileGrants {
    static final String LEDGER = "hushthreads_settings_files";
    static final String HELD = "held";
    private static final int ACCESS = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;

    private static final Object LOCK = new Object();
    /** Files this process took, which the sweep after a start leaves alone. */
    private static final Set<String> takenHere = new HashSet<>();

    private SettingsFileGrants() {
    }

    /**
     * Keeps the access a picker's answer gave to [file], when the picker made it lasting, and
     * answers the access kept. Zero when it didn't, or when the list couldn't be written: the file
     * is then read or written while Android still allows it.
     */
    static int take(Context context, Uri file, int flags) {
        int grant = flags & ACCESS;
        if (grant == 0 || (flags & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) == 0) return 0;
        String name = file.toString();
        synchronized (LOCK) {
            // Written down first, so a process that ends right after the take leaves it listed.
            int listed = list(context, name, true);
            if (listed == FAILED) return 0;
            try {
                context.getContentResolver().takePersistableUriPermission(file, grant);
                takenHere.add(name);
                return grant;
            } catch (RuntimeException refused) {
                Logger.printInfo(() -> "Settings file: no lasting access to the file: " + refused.getClass().getSimpleName());
                // Only an entry this take added: one already there is an earlier process's or this one's.
                if (listed == CHANGED) list(context, name, false);
                return 0;
            }
        }
    }

    /**
     * Lets go of the lasting access on [file], if [grant] says one was taken, and crosses it off.
     * Read and write both: Android keeps one grant per file with every take's access merged in.
     */
    static void release(@Nullable Context context, Uri file, int grant) {
        if (grant == 0 || context == null) return;
        String name = file.toString();
        synchronized (LOCK) {
            try {
                context.getContentResolver().releasePersistableUriPermission(file, ACCESS);
            } catch (RuntimeException error) {
                Logger.printInfo(() -> "Settings file: couldn't let go of its access: " + error.getClass().getSimpleName());
            }
            takenHere.remove(name);
            list(context, name, false);
        }
    }

    /**
     * Threads started. Lets go, on a worker, of every file an earlier process kept and never let go
     * of. A file this process took meanwhile, as when Android started it to hand a picker's answer
     * to a rebuilt {@link SettingsFilePicker}, stays. Never throws.
     */
    static void sweepAfterStart(@Nullable Context context) {
        try {
            if (context == null || !Utils.isMainProcess()) return;
            Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            Utils.runOnBackgroundThread(() -> sweep(application));
        } catch (Throwable t) {
            Logger.printException(() -> "Settings file: could not start letting go of old file access", t);
        }
    }

    private static void sweep(Context application) {
        synchronized (LOCK) {
            try {
                SharedPreferences ledger = application.getSharedPreferences(LEDGER, Context.MODE_PRIVATE);
                Set<String> held = ledger.getStringSet(HELD, null);
                if (held == null || held.isEmpty()) return;
                Set<String> kept = new HashSet<>();
                int released = 0;
                for (String name : held) {
                    if (takenHere.contains(name)) {
                        kept.add(name);
                        continue;
                    }
                    try {
                        application.getContentResolver().releasePersistableUriPermission(Uri.parse(name), ACCESS);
                        released++;
                    } catch (RuntimeException gone) {
                        // Android has no grant for it anymore: nothing to let go of.
                    }
                }
                SharedPreferences.Editor update = ledger.edit();
                if (kept.isEmpty()) update.remove(HELD);
                else update.putStringSet(HELD, kept);
                if (!update.commit()) Logger.printInfo(() -> "Settings file: couldn't update the list of file access");
                int count = released;
                Logger.printInfo(() -> "Settings file: let go of " + count + " file(s) an earlier start kept");
            } catch (Throwable t) {
                Logger.printException(() -> "Settings file: could not let go of old file access", t);
            }
        }
    }

    private static final int FAILED = -1;
    private static final int UNCHANGED = 0;
    private static final int CHANGED = 1;

    /** Adds or removes [name]: CHANGED or UNCHANGED once the list on disk says so, FAILED if it can't. */
    private static int list(Context context, String name, boolean add) {
        try {
            SharedPreferences ledger = context.getApplicationContext() != null
                    ? context.getApplicationContext().getSharedPreferences(LEDGER, Context.MODE_PRIVATE)
                    : context.getSharedPreferences(LEDGER, Context.MODE_PRIVATE);
            Set<String> held = new HashSet<>(ledger.getStringSet(HELD, new HashSet<>()));
            boolean changed = add ? held.add(name) : held.remove(name);
            if (!changed) return UNCHANGED;
            SharedPreferences.Editor update = ledger.edit();
            if (held.isEmpty()) update.remove(HELD);
            else update.putStringSet(HELD, held);
            // commit(), not apply(): the entry exists for a process that ends right after this.
            if (update.commit()) return CHANGED;
            Logger.printInfo(() -> "Settings file: couldn't write the list of file access");
        } catch (RuntimeException error) {
            Logger.printInfo(() -> "Settings file: couldn't read the list of file access: " + error.getClass().getSimpleName());
        }
        return FAILED;
    }

    /** As after Threads ended: this process took nothing. For tests. */
    static void forgetProcessForTests() {
        synchronized (LOCK) {
            takenHere.clear();
        }
    }
}
