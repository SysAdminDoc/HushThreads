/*
 * Copyright 2026 HushThreads contributors
 * https://github.com/SysAdminDoc/HushThreads
 */
package app.morphe.extension.hushthreads.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.Setting;

/**
 * Threads' start can end before anything has read a HushThreads switch, so {@link Settings} hasn't
 * loaded and nothing has been compared with the receipt. That start still keeps the earlier
 * default and replaces the receipt. Its own SDK gives this class a Robolectric sandbox no other
 * class shares, so {@link Settings} first loads here, as in a fresh process. Nothing before the
 * start may name {@link Settings} or {@link PatchFamily}, which loads it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class SettingsUpgradeLoadOrderTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Settings.HIDE_ADS's key, on by default, spelled out so naming it doesn't load Settings. */
    private static final String HIDE_ADS = "hushthreads_hide_ads";

    @After
    public void restore() {
        Settings.HIDE_ADS.resetToDefault();
    }

    @Test
    public void aStartBeforeAnySwitchIsReadKeepsTheEarlierDefaultAndReplacesTheReceipt() throws Exception {
        assertFalse("Settings loaded before this test, so it can't show a start that comes first",
                earlierDefaultsSaved());
        Map<String, Boolean> earlier = Collections.singletonMap(HIDE_ADS, false);
        String written = SettingsUpgrade.Receipt.write(Collections.singletonList("HIDE_ADS"), earlier);
        store().edit().putString(SettingsUpgrade.RECEIPT, written).commit();

        SettingsUpgrade.onThreadsStarted();

        assertTrue("the earlier default wasn't kept", store().contains(HIDE_ADS));
        assertFalse(store().getBoolean(HIDE_ADS, true));
        assertFalse(Settings.HIDE_ADS.savedValue());
        String replaced = store().getString(SettingsUpgrade.RECEIPT, null);
        assertNotEquals("the receipt waited for a later start", written, replaced);
        SettingsUpgrade.Receipt receipt = SettingsUpgrade.Receipt.parse(replaced);
        assertNotNull("no readable receipt: " + replaced, receipt);
        assertEquals(Settings.HIDE_ADS.defaultValue, receipt.defaults.get(HIDE_ADS));
    }

    private static SharedPreferences store() {
        return Setting.preferences.preferences;
    }

    private static boolean earlierDefaultsSaved() throws ReflectiveOperationException {
        Field saved = SettingsUpgrade.class.getDeclaredField("earlierDefaultsSaved");
        saved.setAccessible(true);
        return saved.getBoolean(null);
    }
}
