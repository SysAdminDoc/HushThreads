/*
 * Copyright 2026 HushThreads contributors
 * https://github.com/SysAdminDoc/HushThreads
 */
package app.morphe.extension.hushthreads.feed;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.hushthreads.settings.FamilyNames;
import app.morphe.extension.hushthreads.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushThreadsPause;
import app.morphe.extension.shared.settings.PauseForTests;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class SwipeToSearchTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /**
     * The patch is in Morphe Manager's default selection with its switch off; these tests turn it
     * on. Cleared first as well, so a test that stopped half way, or another class, leaves nothing
     * behind.
     */
    @Before public void turnTheSwitchOn() {
        restore();
        Settings.DISABLE_SWIPE_TO_SEARCH.save(true);
    }

    @After public void restore() {
        PauseForTests.resume();
        Settings.DISABLE_SWIPE_TO_SEARCH.resetToDefault();
        HookStatus.clear();
    }

    /** The switch starts off, so a default build keeps Threads' swipe until it's turned on. */
    @Test public void theSwitchStartsOffAndOnStopsTheSwipe() {
        assertFalse("the switch starts on", Settings.DISABLE_SWIPE_TO_SEARCH.defaultValue);
        assertTrue(Settings.DISABLE_SWIPE_TO_SEARCH.get());
        assertFalse(SwipeToSearch.allow(true));
        assertFalse(SwipeToSearch.allow(true));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(FamilyNames.SWIPE_TO_SEARCH + ": invoked 2"));
        assertTrue(report, report.contains(SwipeToSearch.STOPPED + " 2"));
    }

    /**
     * An account without the swipe stays without it, and so does a copy of a state already
     * answered no, neither counted as stopped.
     */
    @Test public void noSwipeStaysNoSwipe() {
        assertFalse(SwipeToSearch.allow(false));
        Settings.DISABLE_SWIPE_TO_SEARCH.save(false);
        assertFalse(SwipeToSearch.allow(false));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(SwipeToSearch.NONE + " 2"));
        assertFalse(report, report.contains(SwipeToSearch.STOPPED));
    }

    /** Off, Threads keeps its swipe. */
    @Test public void offLeavesTheSwipe() {
        Settings.DISABLE_SWIPE_TO_SEARCH.save(false);
        assertTrue(SwipeToSearch.allow(true));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(SwipeToSearch.LEFT_TO_THREADS + "switch off 1"));
    }

    /** Paused or in safe mode, the saved switch stays on and Threads' swipe comes back. */
    @Test public void pauseAndSafeModeLeaveTheSwipeToThreads() {
        PauseForTests.pause(HushThreadsPause.Reason.SWITCH);
        assertTrue(SwipeToSearch.allow(true));
        assertTrue(Settings.DISABLE_SWIPE_TO_SEARCH.savedValue());
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(SwipeToSearch.LEFT_TO_THREADS + "HushThreads paused 1"));
        PauseForTests.resume();

        // Safe mode is the pause a crash loop starts.
        PauseForTests.pause(HushThreadsPause.Reason.CRASH_LOOP);
        assertTrue(SwipeToSearch.allow(true));
        assertTrue(Settings.DISABLE_SWIPE_TO_SEARCH.savedValue());
    }

    /** Threads settles the swipe when it builds the home screen, so the switch asks for a restart when it changes. */
    @Test public void aChangeWaitsForARestart() {
        assertTrue(Settings.DISABLE_SWIPE_TO_SEARCH.rebootApp);
    }
}
