/*
 * Copyright 2026 HushThreads contributors
 * https://github.com/SysAdminDoc/HushThreads
 */
package app.morphe.extension.hushthreads.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.net.Uri;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowContentResolver;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.Utils;

/** File access survives process death, while failed preference writes can survive only in memory. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 36}, shadows = SettingsFileGrantsTest.Resolver.class)
public class SettingsFileGrantsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private static final int FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;
    private static final Uri FILE = Uri.parse("content://settings-grant-test/private-backup.json");
    private final Ledger ledger = new Ledger();
    private Context context;

    @Before public void prepare() {
        Resolver.resetFailures();
        SettingsFileGrants.forgetProcessForTests();
        context = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return SettingsFileGrants.LEDGER.equals(name) ? ledger.preferences : super.getSharedPreferences(name, mode);
            }
        };
    }

    @After public void restore() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        Resolver.resetFailures();
        SettingsFileGrants.forgetProcessForTests();
    }

    private void processEnded() {
        SettingsFileGrants.forgetProcessForTests();
        ledger.memory = Ledger.copy(ledger.disk);
    }

    private void sweep() throws Exception {
        SettingsFileGrants.sweepAfterStart(context);
        Utils.awaitBackgroundTasksForTests();
    }

    @Test public void sameUriRetryPersistsTheEntryBeforeTakingAccess() throws Exception {
        ledger.failedCommits = 1;
        assertEquals(0, SettingsFileGrants.take(context, FILE, FLAGS));
        assertTrue(ledger.memoryHas(FILE));
        assertFalse(ledger.diskHas(FILE));
        assertEquals(0, Resolver.takes);

        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, SettingsFileGrants.take(context, FILE, FLAGS));
        assertTrue("the retry took access without a durable entry", ledger.diskHas(FILE));
        processEnded();
        sweep();
        assertTrue(context.getContentResolver().getPersistedUriPermissions().isEmpty());
        assertFalse(ledger.diskHas(FILE));
    }

    @Test public void releaseAndStartupFailuresKeepTheirRetryRecord() throws Exception {
        for (boolean startup : new boolean[]{false, true}) {
            for (int inspection = 0; inspection < 3; inspection++) {
                assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, SettingsFileGrants.take(context, FILE, FLAGS));
                Resolver.refuseRelease = true;
                Resolver.refuseInspection = inspection == 1;
                Resolver.nullInspection = inspection == 2;
                if (startup) {
                    processEnded();
                    sweep();
                } else {
                    SettingsFileGrants.release(context, FILE, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
                assertTrue("unconfirmed release lost its durable retry", ledger.diskHas(FILE));
                processEnded();
                sweep();
                assertTrue(ledger.diskHas(FILE));

                Resolver.resetFailures();
                sweep();
                assertTrue(context.getContentResolver().getPersistedUriPermissions().isEmpty());
                assertFalse(ledger.diskHas(FILE));
            }
        }
    }

    @Test public void confirmedAbsenceRetiresOnlyTheOwnedEntry() {
        Uri other = Uri.parse("content://unrelated-provider/keep.json");
        SettingsFileGrants.take(context, FILE, FLAGS);
        context.getContentResolver().takePersistableUriPermission(other, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.getContentResolver().releasePersistableUriPermission(FILE, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Resolver.refuseRelease = true;

        SettingsFileGrants.release(context, FILE, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        assertFalse(ledger.diskHas(FILE));
        List<UriPermission> remaining = context.getContentResolver().getPersistedUriPermissions();
        assertEquals(1, remaining.size());
        assertEquals(other, remaining.get(0).getUri());
    }

    @Test public void failedLedgerRemovalIsRetriedAfterDiskReload() throws Exception {
        SettingsFileGrants.take(context, FILE, FLAGS);
        ledger.failedCommits = 1;
        SettingsFileGrants.release(context, FILE, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        assertTrue(context.getContentResolver().getPersistedUriPermissions().isEmpty());
        assertFalse(ledger.memoryHas(FILE));
        assertTrue(ledger.diskHas(FILE));

        processEnded();
        sweep();
        assertFalse(ledger.diskHas(FILE));
    }

    @Test public void aTakeThatThrowsAfterLandingKeepsAnUnconfirmedGrantListed() throws Exception {
        Resolver.refuseTakeAfterLanding = true;
        Resolver.refuseRelease = true;
        assertEquals(0, SettingsFileGrants.take(context, FILE, FLAGS));
        assertEquals(1, context.getContentResolver().getPersistedUriPermissions().size());
        assertTrue("an uncertain take lost the access it might have acquired", ledger.diskHas(FILE));

        processEnded();
        Resolver.resetFailures();
        sweep();
        assertTrue(context.getContentResolver().getPersistedUriPermissions().isEmpty());
        assertFalse(ledger.diskHas(FILE));
    }

    /** Robolectric otherwise never refuses these system-service operations. */
    @Implements(ContentResolver.class)
    public static class Resolver extends ShadowContentResolver {
        static boolean refuseRelease;
        static boolean refuseInspection;
        static boolean nullInspection;
        static boolean refuseTakeAfterLanding;
        static int takes;

        static void resetFailures() {
            refuseRelease = refuseInspection = nullInspection = refuseTakeAfterLanding = false;
            takes = 0;
        }

        @Implementation @Override protected void takePersistableUriPermission(Uri uri, int flags) {
            takes++;
            super.takePersistableUriPermission(uri, flags);
            if (refuseTakeAfterLanding) throw new IllegalStateException("injected lost take response");
        }

        @Implementation @Override protected void releasePersistableUriPermission(Uri uri, int flags) {
            if (refuseRelease) throw new IllegalStateException("injected grant service failure");
            super.releasePersistableUriPermission(uri, flags);
        }

        @Implementation @Override protected List<UriPermission> getPersistedUriPermissions() {
            if (refuseInspection) throw new SecurityException("injected grant inspection refusal");
            return nullInspection ? null : super.getPersistedUriPermissions();
        }
    }

    /** String-set preferences whose failed commit updates memory but leaves durable bytes alone. */
    private static final class Ledger {
        Map<String, Set<String>> memory = new HashMap<>();
        Map<String, Set<String>> disk = new HashMap<>();
        int failedCommits;
        final SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getStringSet":
                            Set<String> value = memory.get(args[0]);
                            return value == null ? args[1] : new HashSet<>(value);
                        case "edit": return editor();
                        default: throw new UnsupportedOperationException(method.getName());
                    }
                });

        @SuppressWarnings("unchecked")
        private SharedPreferences.Editor editor() {
            Map<String, Set<String>> staged = new HashMap<>();
            return (SharedPreferences.Editor) Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                    new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "putStringSet":
                                staged.put((String) args[0], args[1] == null ? null : new HashSet<>((Set<String>) args[1]));
                                return proxy;
                            case "remove": staged.put((String) args[0], null); return proxy;
                            case "commit":
                                for (Map.Entry<String, Set<String>> entry : staged.entrySet()) {
                                    if (entry.getValue() == null) memory.remove(entry.getKey());
                                    else memory.put(entry.getKey(), new HashSet<>(entry.getValue()));
                                }
                                if (failedCommits > 0) { failedCommits--; return false; }
                                disk = copy(memory);
                                return true;
                            default: throw new UnsupportedOperationException(method.getName());
                        }
                    });
        }

        boolean memoryHas(Uri uri) { return has(memory, uri); }
        boolean diskHas(Uri uri) { return has(disk, uri); }
        private boolean has(Map<String, Set<String>> store, Uri uri) {
            Set<String> held = store.get(SettingsFileGrants.HELD);
            return held != null && held.contains(uri.toString());
        }
        static Map<String, Set<String>> copy(Map<String, Set<String>> store) {
            Map<String, Set<String>> result = new HashMap<>();
            for (Map.Entry<String, Set<String>> entry : store.entrySet()) result.put(entry.getKey(), new HashSet<>(entry.getValue()));
            return result;
        }
    }
}
