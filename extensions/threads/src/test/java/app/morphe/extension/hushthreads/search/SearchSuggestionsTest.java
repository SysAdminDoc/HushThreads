/*
 * Copyright 2026 HushThreads contributors
 * https://github.com/SysAdminDoc/HushThreads
 */
package app.morphe.extension.hushthreads.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.util.Collections;

import app.morphe.extension.hushthreads.profile.ProfileSuggestions;
import app.morphe.extension.hushthreads.settings.FamilyNames;
import app.morphe.extension.hushthreads.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushThreadsPause;
import app.morphe.extension.shared.settings.PauseForTests;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class SearchSuggestionsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Cleared first as well, so a test that stopped half way, or another class, leaves nothing behind. */
    @Before public void startClean() {
        restore();
    }

    @After public void restore() {
        ThrowingSettingsRead.fail = false;
        PauseForTests.resume();
        Settings.HIDE_SEARCH_SUGGESTIONS.resetToDefault();
        Settings.HIDE_SUGGESTED_USERS.resetToDefault();
        HookStatus.clear();
    }

    /** The switch starts on like the rest of Hide suggested users, so a default build keeps the carousel out of Search. */
    @Test public void theSwitchStartsOnAndKeepsTheCarouselOut() {
        assertTrue("the switch starts off", Settings.HIDE_SEARCH_SUGGESTIONS.defaultValue);
        assertFalse(SearchSuggestions.showCarousel());
        assertFalse(SearchSuggestions.showCarousel());
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(FamilyNames.HIDE_SUGGESTED_USERS + ": invoked 2"));
        assertTrue(report, report.contains(SearchSuggestions.HIDDEN_CAROUSEL + " 2"));
        assertEquals(Collections.emptyList(), HookStatus.missing(FamilyNames.HIDE_SUGGESTED_USERS));
    }

    /** Off, Threads adds its carousel, and the report says why. */
    @Test public void offLeavesTheCarouselToThreads() {
        Settings.HIDE_SEARCH_SUGGESTIONS.save(false);
        assertTrue(SearchSuggestions.showCarousel());
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(SearchSuggestions.LEFT_TO_THREADS + "switch off 1"));
        assertFalse(report, report.contains(SearchSuggestions.HIDDEN_CAROUSEL));
    }

    /**
     * The two switches of Hide suggested users answer apart: Search follows its own, whatever the
     * feed and profile switch says, and that one doesn't follow Search's.
     */
    @Test public void eachSwitchWorksWithoutTheOther() {
        Settings.HIDE_SUGGESTED_USERS.save(false);
        assertFalse(SearchSuggestions.showCarousel());
        assertTrue(ProfileSuggestions.showRow());
        Settings.HIDE_SUGGESTED_USERS.save(true);
        Settings.HIDE_SEARCH_SUGGESTIONS.save(false);
        assertTrue(SearchSuggestions.showCarousel());
        assertFalse(ProfileSuggestions.showRow());
    }

    /** Paused or in safe mode, the saved switch stays on and Threads' carousel comes back. */
    @Test public void pauseAndSafeModeLeaveTheCarouselToThreads() {
        PauseForTests.pause(HushThreadsPause.Reason.SWITCH);
        assertTrue(SearchSuggestions.showCarousel());
        assertTrue(Settings.HIDE_SEARCH_SUGGESTIONS.savedValue());
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(SearchSuggestions.LEFT_TO_THREADS + "HushThreads paused 1"));
        PauseForTests.resume();

        // Safe mode is the pause a crash loop starts.
        PauseForTests.pause(HushThreadsPause.Reason.CRASH_LOOP);
        assertTrue(SearchSuggestions.showCarousel());
        assertTrue(Settings.HIDE_SEARCH_SUGGESTIONS.savedValue());
    }

    /** A failure inside the hook lets Threads add its carousel, and the report names the hook. */
    @Test @Config(shadows = ThrowingSettingsRead.class, instrumentedPackages = "app.morphe.extension.shared")
    public void aFailureInTheHookLeavesTheCarousel() {
        try {
            ThrowingSettingsRead.fail = true;
            assertTrue(SearchSuggestions.showCarousel());
        } finally {
            ThrowingSettingsRead.fail = false;
        }
        assertEquals(Collections.singletonList("a working 'search carousel' hook (it threw "
                        + IllegalStateException.class.getName() + ")"),
                HookStatus.missing(FamilyNames.HIDE_SUGGESTED_USERS));
    }

    /** Threads asks again each time it builds Search, so a change needs no restart. */
    @Test public void aChangeNeedsNoRestart() {
        assertFalse(Settings.HIDE_SEARCH_SUGGESTIONS.rebootApp);
        Settings.HIDE_SEARCH_SUGGESTIONS.save(false);
        assertTrue(SearchSuggestions.showCarousel());
        Settings.HIDE_SEARCH_SUGGESTIONS.save(true);
        assertFalse(SearchSuggestions.showCarousel());
    }

    @Implements(Utils.class)
    public static class ThrowingSettingsRead {
        static boolean fail;

        @Implementation
        protected static boolean settingsReady() {
            if (fail) throw new IllegalStateException("settings unavailable");
            return true;
        }
    }
}
