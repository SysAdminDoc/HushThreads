/*
 * Forked from https://github.com/SysAdminDoc/HushPinterest at 44fb9338 (GPL-3.0),
 * modified for HushThreads (Threads), 2026.
 *
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 *
 * Built on SysAdminDoc/hushfeed (GPL-3.0).
 */
package app.morphe.extension.hushthreads.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.Setting;

/** Quick setups' lists name real switches, none twice, and only the feature switches a settings file carries. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class QuickSetupKeysTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Test
    public void everyKeyIsASwitchInSettingsAndNoListIsEmpty() throws Exception {
        Set<BooleanSetting> declared = new HashSet<>();
        for (Field field : Settings.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && Modifier.isPublic(field.getModifiers())
                    && field.getType() == BooleanSetting.class) {
                declared.add((BooleanSetting) field.get(null));
            }
        }
        assertTrue("found too few switches to compare against: " + declared.size(), declared.size() > 15);
        for (List<BooleanSetting> list : Arrays.asList(QuickSetupKeys.QUIET, QuickSetupKeys.PRIVACY, QuickSetupKeys.DEFAULTS)) {
            assertFalse(list.isEmpty());
            assertEquals("a switch is listed twice: " + list, list.size(), new HashSet<>(list).size());
            for (BooleanSetting setting : list) {
                assertTrue(setting.key + " isn't one of Settings' switches", declared.contains(setting));
                assertSame(setting, Setting.getSettingFromPath(setting.key));
            }
        }
    }

    @Test
    public void quietAndPrivacyOnlyTurnFeatureSwitchesOn() {
        for (List<BooleanSetting> list : Arrays.asList(QuickSetupKeys.QUIET, QuickSetupKeys.PRIVACY)) {
            assertTrue("a choice reaches past the feature switches: " + list, SettingsBackup.ALLOWLIST.containsAll(list));
        }
        for (Map<BooleanSetting, Boolean> choice : Arrays.asList(QuickSetupKeys.quiet(), QuickSetupKeys.privacy())) {
            for (Boolean value : choice.values()) assertTrue(value);
        }
        Map<BooleanSetting, Boolean> defaults = QuickSetupKeys.defaults();
        assertEquals(SettingsBackup.ALLOWLIST.size(), defaults.size());
        for (Map.Entry<BooleanSetting, Boolean> value : defaults.entrySet()) {
            assertEquals(value.getKey().key, value.getKey().defaultValue, value.getValue());
        }
        // Pause, debug and the release check are nobody's quick setup.
        for (BooleanSetting kept : Arrays.asList(BaseSettings.PAUSED, BaseSettings.DEBUG, Settings.CHECK_FOR_RELEASES)) {
            assertFalse(kept.key, defaults.containsKey(kept));
        }
    }
}
