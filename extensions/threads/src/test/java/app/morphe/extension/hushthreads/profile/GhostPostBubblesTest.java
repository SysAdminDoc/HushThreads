/*
 * Copyright 2026 HushThreads contributors
 * https://github.com/SysAdminDoc/HushThreads
 */
package app.morphe.extension.hushthreads.profile;

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
public class GhostPostBubblesTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /**
     * The patch is in Morphe Manager's default selection with its switch off; these tests turn it
     * on. Cleared first as well, so a test that stopped half way, or another class, leaves nothing
     * behind.
     */
    @Before public void turnTheSwitchOn() {
        restore();
        Settings.HIDE_GHOST_POST_BUBBLES.save(true);
    }

    @After public void restore() {
        PauseForTests.resume();
        Settings.HIDE_GHOST_POST_BUBBLES.resetToDefault();
        HookStatus.clear();
    }

    /** The switch starts off, so a default build keeps the bubbles until it's turned on. */
    @Test public void theSwitchStartsOffAndOnHidesBothBubbles() {
        assertFalse("the switch starts on", Settings.HIDE_GHOST_POST_BUBBLES.defaultValue);
        assertTrue(Settings.HIDE_GHOST_POST_BUBBLES.get());
        assertFalse(GhostPostBubbles.showOnProfile(true));
        assertFalse(GhostPostBubbles.showTray(true));
        assertFalse(GhostPostBubbles.showTray(true));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(FamilyNames.HIDE_GHOST_POST_BUBBLES + ": invoked 3"));
        assertTrue(report, report.contains(GhostPostBubbles.HIDDEN_ON_PROFILE + " 1"));
        assertTrue(report, report.contains(GhostPostBubbles.HIDDEN_IN_FEED + " 2"));
    }

    /** A bubble Threads doesn't show stays hidden, and isn't counted as one hidden. */
    @Test public void noBubbleStaysNoBubble() {
        assertFalse(GhostPostBubbles.showOnProfile(false));
        Settings.HIDE_GHOST_POST_BUBBLES.save(false);
        assertFalse(GhostPostBubbles.showOnProfile(false));
        assertFalse(GhostPostBubbles.showTray(false));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(GhostPostBubbles.NONE + " 3"));
        assertFalse(report, report.contains(GhostPostBubbles.HIDDEN_ON_PROFILE));
        assertFalse(report, report.contains(GhostPostBubbles.HIDDEN_IN_FEED));
    }

    /** Off, Threads keeps its bubbles. */
    @Test public void offLeavesTheBubbles() {
        Settings.HIDE_GHOST_POST_BUBBLES.save(false);
        assertTrue(GhostPostBubbles.showOnProfile(true));
        assertTrue(GhostPostBubbles.showTray(true));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(GhostPostBubbles.LEFT_TO_THREADS + "switch off 2"));
    }

    /** Paused or in safe mode, the saved switch stays on and Threads' bubbles come back. */
    @Test public void pauseAndSafeModeLeaveTheBubblesToThreads() {
        PauseForTests.pause(HushThreadsPause.Reason.SWITCH);
        assertTrue(GhostPostBubbles.showOnProfile(true));
        assertTrue(GhostPostBubbles.showTray(true));
        assertTrue(Settings.HIDE_GHOST_POST_BUBBLES.savedValue());
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(GhostPostBubbles.LEFT_TO_THREADS + "HushThreads paused 2"));
        PauseForTests.resume();

        // Safe mode is the pause a crash loop starts.
        PauseForTests.pause(HushThreadsPause.Reason.CRASH_LOOP);
        assertTrue(GhostPostBubbles.showOnProfile(true));
        assertTrue(GhostPostBubbles.showTray(true));
        assertTrue(Settings.HIDE_GHOST_POST_BUBBLES.savedValue());
    }

    /** Threads keeps what it has drawn, so the switch asks for a restart when it changes. */
    @Test public void aChangeWaitsForARestart() {
        assertTrue(Settings.HIDE_GHOST_POST_BUBBLES.rebootApp);
    }
}
