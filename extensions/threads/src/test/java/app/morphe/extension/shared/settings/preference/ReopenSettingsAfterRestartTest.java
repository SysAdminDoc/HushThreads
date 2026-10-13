/*
 * Forked from https://github.com/SysAdminDoc/HushPinterest at 44fb9338 (GPL-3.0),
 * modified for HushThreads (Threads), 2026.
 *
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 *
 * Built on SysAdminDoc/hushfeed (GPL-3.0).
 */
package app.morphe.extension.shared.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.PauseForTests;
import app.morphe.extension.shared.settings.Setting;

/**
 * The marker the restart row leaves for the next start: written before the restart, taken once,
 * cleared before anything opens, and only good for {@link ReopenSettingsAfterRestart#MAX_AGE_MILLIS}.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ReopenSettingsAfterRestartTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private SharedPreferences store;

    @Before
    public void emptyStore() {
        store = RuntimeEnvironment.getApplication().getSharedPreferences("reopen-settings-test", Context.MODE_PRIVATE);
        store.edit().clear().commit();
        PauseForTests.resume();
    }

    @After
    public void restore() {
        AbstractPreferenceFragment.restartPending.clear();
        BaseSettings.PAUSED.resetToDefault();
        PauseForTests.resume();
    }

    @Test
    public void aFreshMarkerComesBackOnceAndIsGone() {
        assertTrue(ReopenSettingsAfterRestart.remember(store, "Links", 10_000));
        assertEquals("Links", ReopenSettingsAfterRestart.takeFresh(store, 10_000 + 89_999));
        assertMarkerGone();
        assertNull("a marker was taken twice", ReopenSettingsAfterRestart.takeFresh(store, 10_000 + 89_999));
        // The first page is a page too: an empty route opens the screen, it doesn't drop the marker.
        ReopenSettingsAfterRestart.remember(store, "", 5);
        assertEquals("", ReopenSettingsAfterRestart.takeFresh(store, 6));
    }

    @Test
    public void anOldMarkerIsDroppedAndCleared() {
        ReopenSettingsAfterRestart.remember(store, "Feed", 1_000);
        assertNull(ReopenSettingsAfterRestart.takeFresh(store, 1_000 + ReopenSettingsAfterRestart.MAX_AGE_MILLIS));
        assertMarkerGone();
        // Written before the phone restarted, it reads as from the future, and is no good either.
        ReopenSettingsAfterRestart.remember(store, "Feed", 50_000);
        assertNull(ReopenSettingsAfterRestart.takeFresh(store, 2_000));
        assertMarkerGone();
        // Half a marker, or one of the wrong type, is cleared too.
        store.edit().putLong(ReopenSettingsAfterRestart.TIME_KEY, 1).commit();
        assertNull(ReopenSettingsAfterRestart.takeFresh(store, 2));
        assertMarkerGone();
        store.edit().putInt(ReopenSettingsAfterRestart.PAGE_KEY, 3).putString(ReopenSettingsAfterRestart.TIME_KEY, "x").commit();
        assertNull(ReopenSettingsAfterRestart.takeFresh(store, 2));
        assertMarkerGone();
        assertNull("no marker opens nothing", ReopenSettingsAfterRestart.takeFresh(store, 2));
    }

    @Test
    public void theMarkerIsClearedBeforeTheScreenOpens() {
        ReopenSettingsAfterRestart.remember(store, "Pause, backup and diagnostics", 100);
        List<String> opened = new ArrayList<>();
        assertTrue(ReopenSettingsAfterRestart.reopenIfPending(store, 200, page -> {
            assertMarkerGone();
            opened.add(page);
        }));
        assertEquals(java.util.Collections.singletonList("Pause, backup and diagnostics"), opened);

        // A screen that fails to open isn't tried again on the next start.
        ReopenSettingsAfterRestart.remember(store, "Feed", 100);
        try {
            ReopenSettingsAfterRestart.reopenIfPending(store, 200, page -> { throw new IllegalStateException("no activity"); });
            fail("the opener's failure was swallowed");
        } catch (IllegalStateException expected) {
            assertMarkerGone();
        }
        assertFalse(ReopenSettingsAfterRestart.reopenIfPending(store, 200, page -> fail("opened " + page)));
    }

    @Test
    public void theRestartRowWritesTheMarkerThenRestarts() {
        List<Context> restarted = new ArrayList<>();
        ReopenSettingsAfterRestart.setRestarterForTests(context -> {
            // Written and committed before the restart, which ends the process.
            assertTrue(Setting.preferences.preferences.contains(ReopenSettingsAfterRestart.PAGE_KEY));
            restarted.add(context);
        });
        Context app = RuntimeEnvironment.getApplication();
        ReopenSettingsAfterRestart.restartInto(app, "more");
        assertEquals(java.util.Collections.singletonList(app), restarted);
        assertEquals("more", ReopenSettingsAfterRestart.takeFresh(Setting.preferences.preferences, SystemClock.elapsedRealtime()));
    }

    @Test
    public void changesWaitingCountsSwitchesAndAPauseOnce() {
        Context app = RuntimeEnvironment.getApplication();
        assertEquals(0, ReopenSettingsAfterRestart.changesWaiting(app));
        AbstractPreferenceFragment.restartPending.add("hushthreads_pure_black");
        AbstractPreferenceFragment.restartPending.add("hushthreads_hide_instagram_button");
        assertEquals(2, ReopenSettingsAfterRestart.changesWaiting(app));
        // Pause switched on: its key is owed, and the next start differs from this one. One change.
        BaseSettings.PAUSED.save(true);
        AbstractPreferenceFragment.restartPending.add(BaseSettings.PAUSED.key);
        assertEquals(3, ReopenSettingsAfterRestart.changesWaiting(app));
        AbstractPreferenceFragment.restartPending.clear();
        assertEquals("a pause the switch didn't record still counts", 1, ReopenSettingsAfterRestart.changesWaiting(app));
        BaseSettings.PAUSED.save(false);
        assertEquals(0, ReopenSettingsAfterRestart.changesWaiting(app));
    }

    private void assertMarkerGone() {
        assertFalse(store.contains(ReopenSettingsAfterRestart.PAGE_KEY));
        assertFalse(store.contains(ReopenSettingsAfterRestart.TIME_KEY));
    }
}
