/*
 * Copyright 2026 HushThreads contributors
 * https://github.com/SysAdminDoc/HushThreads
 */
package app.morphe.extension.hushthreads.share;

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
public class CreateGroupButtonTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /**
     * The patch is in Morphe Manager's default selection with its switch off; these tests turn it
     * on. Cleared first as well, so a test that stopped half way, or another class, leaves nothing
     * behind.
     */
    @Before public void turnTheSwitchOn() {
        restore();
        Settings.HIDE_CREATE_GROUP_BUTTON.save(true);
    }

    @After public void restore() {
        PauseForTests.resume();
        Settings.HIDE_CREATE_GROUP_BUTTON.resetToDefault();
        HookStatus.clear();
    }

    /** The switch starts off, so a default build keeps the button until it's turned on. */
    @Test public void theSwitchStartsOffAndOnHidesTheButton() {
        assertFalse("the switch starts on", Settings.HIDE_CREATE_GROUP_BUTTON.defaultValue);
        assertTrue(Settings.HIDE_CREATE_GROUP_BUTTON.get());
        assertFalse(CreateGroupButton.show(true));
        assertFalse(CreateGroupButton.show(true));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(FamilyNames.HIDE_CREATE_GROUP_BUTTON + ": invoked 2"));
        assertTrue(report, report.contains(CreateGroupButton.HIDDEN + " 2"));
    }

    /** A bar Threads draws without Create group, one person picked, stays without it and isn't counted as hidden. */
    @Test public void noButtonStaysNoButton() {
        assertFalse(CreateGroupButton.show(false));
        Settings.HIDE_CREATE_GROUP_BUTTON.save(false);
        assertFalse(CreateGroupButton.show(false));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(CreateGroupButton.NONE + " 2"));
        assertFalse(report, report.contains(CreateGroupButton.HIDDEN));
    }

    /** Off, Threads keeps its button. */
    @Test public void offLeavesTheButton() {
        Settings.HIDE_CREATE_GROUP_BUTTON.save(false);
        assertTrue(CreateGroupButton.show(true));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(CreateGroupButton.LEFT_TO_THREADS + "switch off 1"));
    }

    /** Paused or in safe mode, the saved switch stays on and Threads' button comes back. */
    @Test public void pauseAndSafeModeLeaveTheButtonToThreads() {
        PauseForTests.pause(HushThreadsPause.Reason.SWITCH);
        assertTrue(CreateGroupButton.show(true));
        assertTrue(Settings.HIDE_CREATE_GROUP_BUTTON.savedValue());
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(CreateGroupButton.LEFT_TO_THREADS + "HushThreads paused 1"));
        PauseForTests.resume();

        // Safe mode is the pause a crash loop starts.
        PauseForTests.pause(HushThreadsPause.Reason.CRASH_LOOP);
        assertTrue(CreateGroupButton.show(true));
        assertTrue(Settings.HIDE_CREATE_GROUP_BUTTON.savedValue());
    }

    /** Threads draws the bar again each time the share sheet changes, so the switch needs no restart. */
    @Test public void aChangeNeedsNoRestart() {
        assertFalse(Settings.HIDE_CREATE_GROUP_BUTTON.rebootApp);
    }
}
