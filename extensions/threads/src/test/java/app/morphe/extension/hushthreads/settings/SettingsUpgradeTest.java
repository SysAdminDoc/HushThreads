/*
 * Copyright 2026 HushThreads contributors
 * https://github.com/SysAdminDoc/HushThreads
 */
package app.morphe.extension.hushthreads.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.FailingStore;
import app.morphe.extension.shared.settings.HushThreadsPause;
import app.morphe.extension.shared.settings.PauseForTests;
import app.morphe.extension.shared.settings.Setting;

/**
 * A later build's new default never turns a switch nobody stored. Each start is played the way
 * Threads runs it: the switches load (onSwitchesLoaded), then Threads' start ends
 * (onThreadsStarted). Code defaults can't change inside one test run, so an earlier build is the
 * receipt it left: one naming a different default for a switch is a build whose default changed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class SettingsUpgradeTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final Set<PatchFamily> EVERY_FAMILY = EnumSet.allOf(PatchFamily.class);

    @Before
    public void fromDefaults() {
        for (BooleanSetting setting : SettingsUpgrade.switches().keySet()) setting.resetToDefault();
        PatchFamily.inBuildForTests = EVERY_FAMILY;
    }

    @After
    public void restore() {
        PauseForTests.resume();
        for (BooleanSetting setting : SettingsUpgrade.switches().keySet()) setting.resetToDefault();
        PatchFamily.inBuildForTests = null;
    }

    @Test
    public void theReceiptCoversExactlyTheSwitchesSettingsDeclares() {
        assertEquals(new HashSet<>(SettingsUpgrade.declaredSwitches(Settings.class)),
                SettingsUpgrade.switches().keySet());
        Set<BooleanSetting> filed = new HashSet<>(SettingsBackup.ALLOWLIST);
        filed.add(Settings.CHECK_FOR_RELEASES);
        assertEquals("a switch that isn't a person's choice is in the receipt", filed, SettingsUpgrade.switches().keySet());
    }

    /** No receipt: nothing is guessed, and the receipt starts from this build. */
    @Test
    public void anInstallWithNoReceiptKeepsTodaysDefaultsAndStartsOne() {
        assertTrue(Settings.HIDE_ADS.save(false));
        Map<String, ?> before = new HashMap<>(store().getAll());

        start(EVERY_FAMILY);

        Map<String, Object> after = new HashMap<>(store().getAll());
        assertNotNull("no receipt was kept", after.remove(SettingsUpgrade.RECEIPT));
        assertEquals("a switch was saved without a receipt to go on", before, after);
        assertFalse(Settings.HIDE_ADS.savedValue());
        SettingsUpgrade.Receipt receipt = receipt();
        assertEquals(EVERY_FAMILY.size(), receipt.families.size());
        for (BooleanSetting setting : SettingsUpgrade.switches().keySet()) {
            assertEquals(setting.key, setting.defaultValue, receipt.defaults.get(setting.key));
        }
        assertTrue("the receipt for every family is past its bound",
                receiptText().getBytes(StandardCharsets.UTF_8).length < SettingsUpgrade.MAX_BYTES / 2);
    }

    /**
     * A default that went from off to on, and one that went from on to off, both keep the earlier
     * value for a switch nobody stored. A stored switch stays as stored either way.
     */
    @Test
    public void aChangedDefaultKeepsTheEarlierOneBothWays() {
        Map<String, Boolean> earlier = todaysDefaults();
        earlier.put(Settings.HIDE_ADS.key, false);                // on today
        earlier.put(Settings.PURE_BLACK.key, true);               // off today
        earlier.put(Settings.SANITIZE_SHARING_LINKS.key, false);  // on today, stored on below
        writeReceipt(EVERY_FAMILY, earlier);
        store().edit().putBoolean(Settings.SANITIZE_SHARING_LINKS.key, true).commit();
        assertTrue(Settings.DISABLE_ANALYTICS.save(false));

        start(EVERY_FAMILY);

        assertStored(Settings.HIDE_ADS, false);
        assertStored(Settings.PURE_BLACK, true);
        assertStored(Settings.SANITIZE_SHARING_LINKS, true);
        assertStored(Settings.DISABLE_ANALYTICS, false);
        assertFalse(store().contains(Settings.HIDE_SUGGESTED_USERS.key));
        assertEquals("the receipt still names the earlier default", todaysDefaults(), receipt().defaults);
    }

    /** A second start of the same build finds nothing to do and opens no editor. */
    @Test
    public void repeatedStartsWriteNothing() {
        Map<String, Boolean> earlier = todaysDefaults();
        earlier.put(Settings.PURE_BLACK.key, true);
        writeReceipt(EVERY_FAMILY, earlier);
        start(EVERY_FAMILY);
        Map<String, ?> settled = new HashMap<>(store().getAll());

        try (FailingStore store = FailingStore.install(FailingStore.Fault.EDIT_THROWS)) {
            start(EVERY_FAMILY);
            start(EVERY_FAMILY);
            assertEquals("a repeated start opened an editor", 0, store.editors.get());
        }
        assertEquals(settled, store().getAll());
        assertTrue(Settings.PURE_BLACK.savedValue());
    }

    /**
     * A family no build on this phone carried isn't in the receipt, so its switch starts on the
     * new build's default when the family arrives. A switch new to the receipt does the same.
     */
    @Test
    public void aFamilyOrSwitchNoBuildHadGetsTheNewDefault() {
        Set<PatchFamily> without = EnumSet.allOf(PatchFamily.class);
        without.remove(PatchFamily.PURE_BLACK);
        Map<String, Boolean> earlier = todaysDefaults();
        earlier.remove(Settings.PURE_BLACK.key);
        earlier.remove(Settings.COPY_TEXT.key);
        writeReceipt(without, earlier);

        start(without);
        assertFalse("a family this phone never had is in the receipt",
                receipt().defaults.containsKey(Settings.PURE_BLACK.key));
        assertTrue("the entry's own new switch isn't in the receipt",
                receipt().defaults.containsKey(Settings.COPY_TEXT.key));

        start(EVERY_FAMILY);
        assertFalse(store().contains(Settings.PURE_BLACK.key));
        assertFalse(store().contains(Settings.COPY_TEXT.key));
        assertEquals(Settings.PURE_BLACK.defaultValue, receipt().defaults.get(Settings.PURE_BLACK.key));
    }

    /**
     * A family a build leaves out stays in the receipt, so its earlier default is still known
     * when a later build brings it back.
     */
    @Test
    public void aFamilyLeftOutAndBroughtBackKeepsItsEarlierDefault() {
        Set<PatchFamily> without = EnumSet.allOf(PatchFamily.class);
        without.remove(PatchFamily.HIDE_INSTAGRAM_BUTTON);
        without.remove(PatchFamily.VIDEO_AUTOPLAY);
        Map<String, Boolean> earlier = todaysDefaults();
        earlier.put(Settings.HIDE_INSTAGRAM_BUTTON.key, true);
        writeReceipt(EVERY_FAMILY, earlier);

        start(without);
        assertStored(Settings.HIDE_INSTAGRAM_BUTTON, true);
        assertTrue("a left-out family's switch fell out of the receipt",
                receipt().defaults.containsKey(Settings.DISABLE_VIDEO_AUTOPLAY.key));
        assertFalse(receipt().families.contains(PatchFamily.VIDEO_AUTOPLAY.name()));

        start(EVERY_FAMILY);
        assertStored(Settings.HIDE_INSTAGRAM_BUTTON, true);
        assertFalse(store().contains(Settings.DISABLE_VIDEO_AUTOPLAY.key));
        assertTrue(receipt().families.contains(PatchFamily.VIDEO_AUTOPLAY.name()));
    }

    /** Paused or in safe mode, every switch answers off. None of that becomes a saved value. */
    @Test
    public void pauseAndSafeModeDontTurnIntoSavedChoices() {
        for (HushThreadsPause.Reason reason : new HushThreadsPause.Reason[]{
                HushThreadsPause.Reason.SWITCH, HushThreadsPause.Reason.CRASH_LOOP}) {
            store().edit().clear().commit();
            for (BooleanSetting setting : SettingsUpgrade.switches().keySet()) setting.resetToDefault();
            Map<String, Boolean> earlier = todaysDefaults();
            earlier.put(Settings.PURE_BLACK.key, true);
            writeReceipt(EVERY_FAMILY, earlier);
            assertTrue(Settings.SAVE_MEDIA.save(false));
            PauseForTests.pause(reason);
            try {
                start(EVERY_FAMILY);
                assertFalse("paused, the switch should answer off", Settings.PURE_BLACK.get());
            } finally {
                PauseForTests.resume();
            }

            Set<String> stored = new HashSet<>(store().getAll().keySet());
            stored.remove(SettingsUpgrade.RECEIPT);
            assertEquals(reason + " stored", new HashSet<>(Arrays.asList(
                    Settings.PURE_BLACK.key, Settings.SAVE_MEDIA.key)), stored);
            assertStored(Settings.PURE_BLACK, true);
            assertStored(Settings.SAVE_MEDIA, false);
            assertTrue(Settings.HIDE_ADS.savedValue());
        }
    }

    /**
     * A commit that fails changes nothing, keeps the receipt it needed and holds the new one back.
     * The next start finishes the job. A receipt commit that fails after the defaults landed is
     * finished the same way, with nothing kept twice.
     */
    @Test
    public void anInterruptedUpgradeChangesNothingAndTheNextStartFinishesIt() {
        for (FailingStore.Fault fault : new FailingStore.Fault[]{FailingStore.Fault.COMMIT_THROWS,
                FailingStore.Fault.LOST, FailingStore.Fault.EDIT_THROWS, FailingStore.Fault.STAGE_THROWS}) {
            store().edit().clear().commit();
            for (BooleanSetting setting : SettingsUpgrade.switches().keySet()) setting.resetToDefault();
            Map<String, Boolean> earlier = todaysDefaults();
            earlier.put(Settings.HIDE_ADS.key, false);
            writeReceipt(EVERY_FAMILY, earlier);
            String receipt = receiptText();

            try (FailingStore ignored = FailingStore.install(fault)) {
                start(EVERY_FAMILY);
            }
            assertFalse(fault + " saved the switch", store().contains(Settings.HIDE_ADS.key));
            assertTrue(fault + " changed the running value", Settings.HIDE_ADS.savedValue());
            assertEquals(fault + " replaced the receipt the next start needs", receipt, receiptText());

            start(EVERY_FAMILY);
            assertStored(Settings.HIDE_ADS, false);
            assertEquals(todaysDefaults(), receipt().defaults);
        }

        // The kept default lands and the full receipt doesn't. The person then turns the switch to
        // today's default, which isn't stored, and the next start mustn't take it back.
        store().edit().clear().commit();
        Settings.HIDE_ADS.resetToDefault();
        Set<PatchFamily> earlierBuild = EnumSet.allOf(PatchFamily.class);
        earlierBuild.remove(PatchFamily.PURE_BLACK);
        Map<String, Boolean> earlier = todaysDefaults();
        earlier.put(Settings.HIDE_ADS.key, false);
        writeReceipt(earlierBuild, earlier);
        try (FailingStore ignored = FailingStore.install(FailingStore.Fault.NONE, FailingStore.Fault.COMMIT_THROWS)) {
            start(EVERY_FAMILY);
        }
        assertStored(Settings.HIDE_ADS, false);
        assertEquals("the failed receipt commit still replaced the families", earlierBuild.size(), receipt().families.size());
        assertEquals(Settings.HIDE_ADS.defaultValue, receipt().defaults.get(Settings.HIDE_ADS.key));
        assertTrue(Settings.HIDE_ADS.save(true));
        assertFalse(store().contains(Settings.HIDE_ADS.key));

        start(EVERY_FAMILY);
        assertFalse("the earlier default came back over the person's choice", store().contains(Settings.HIDE_ADS.key));
        assertTrue(Settings.HIDE_ADS.savedValue());
        assertEquals(EVERY_FAMILY.size(), receipt().families.size());
    }

    /**
     * Back to an older build and up again: the older one reads the newer receipt, keeps what it
     * knows and drops what it doesn't, and its own receipt is one the newer build reads. A receipt
     * in a newer shape is read as none.
     */
    @Test
    public void aDowngradeAndReUpgradeKeepEveryChoice() throws Exception {
        Map<String, Boolean> newer = todaysDefaults();
        newer.put(Settings.HIDE_ADS.key, false);
        newer.put("hushthreads_a_later_switch", true);
        JSONObject root = receiptJson(EVERY_FAMILY, newer);
        root.getJSONArray(SettingsUpgrade.FAMILIES_NAME).put("A_LATER_FAMILY");
        store().edit().putString(SettingsUpgrade.RECEIPT, root.toString()).commit();

        start(EVERY_FAMILY);
        assertStored(Settings.HIDE_ADS, false);
        assertFalse(receipt().defaults.containsKey("hushthreads_a_later_switch"));
        assertFalse(receipt().families.contains("A_LATER_FAMILY"));
        assertFalse(store().contains("hushthreads_a_later_switch"));

        // Up again: the newer build's receipt comes back, and the choice is stored by now.
        store().edit().putString(SettingsUpgrade.RECEIPT, root.toString()).commit();
        start(EVERY_FAMILY);
        assertStored(Settings.HIDE_ADS, false);

        store().edit().clear().commit();
        Settings.HIDE_ADS.resetToDefault();
        JSONObject laterShape = receiptJson(EVERY_FAMILY, newer).put(SettingsUpgrade.SCHEMA_NAME, SettingsUpgrade.SCHEMA + 1);
        store().edit().putString(SettingsUpgrade.RECEIPT, laterShape.toString()).commit();
        start(EVERY_FAMILY);
        assertFalse("a receipt in a newer shape was guessed at", store().contains(Settings.HIDE_ADS.key));
        assertEquals(todaysDefaults(), receipt().defaults);
    }

    /** A receipt this build can't read is never guessed at, and a readable one replaces it. */
    @Test
    public void aReceiptThisBuildCantReadIsntGuessedAt() throws Exception {
        String key = Settings.HIDE_ADS.key;
        String good = receiptJson(EVERY_FAMILY, Map.of(key, false)).toString();
        StringBuilder huge = new StringBuilder(good.substring(0, good.length() - 2));
        while (huge.length() <= SettingsUpgrade.MAX_BYTES) huge.append(",\"k").append(huge.length()).append("\":true");
        huge.append("}}");
        String[] unreadable = {
                "not json",
                good.substring(0, good.length() / 2),
                huge.toString(),
                "{\"schema\":1,\"families\":[],\"defaults\":{\"" + key + "\":false,\"" + key + "\":false}}",
                "{\"schema\":1,\"families\":[],\"defaults\":{\"" + key + "\":\"false\"}}",
                "{\"schema\":1,\"families\":[1],\"defaults\":{\"" + key + "\":false}}",
                "{\"schema\":\"1\",\"families\":[],\"defaults\":{\"" + key + "\":false}}",
                "{\"schema\":0,\"families\":[],\"defaults\":{\"" + key + "\":false}}",
                "{\"families\":[],\"defaults\":{\"" + key + "\":false}}",
        };
        assertNotNull("the control receipt isn't readable", SettingsUpgrade.Receipt.parse(good));
        for (String text : unreadable) {
            store().edit().clear().putString(SettingsUpgrade.RECEIPT, text).commit();
            start(EVERY_FAMILY);
            assertFalse("guessed from " + text, store().contains(key));
            assertTrue(Settings.HIDE_ADS.savedValue());
            assertEquals(todaysDefaults(), receipt().defaults);
        }
        store().edit().clear().putBoolean(SettingsUpgrade.RECEIPT, true).commit();
        start(EVERY_FAMILY);
        assertFalse(store().contains(key));
        assertEquals(todaysDefaults(), receipt().defaults);
    }

    /** Another of Threads' processes runs on the earlier defaults and writes nothing. */
    @Test
    public void anotherProcessRunsOnTheEarlierDefaultsAndWritesNothing() {
        Map<String, Boolean> earlier = todaysDefaults();
        earlier.put(Settings.HIDE_ADS.key, false);
        writeReceipt(EVERY_FAMILY, earlier);
        Map<String, ?> before = new HashMap<>(store().getAll());
        ApplicationInfo info = RuntimeEnvironment.getApplication().getApplicationInfo();
        String mainProcess = info.processName;
        info.processName = RuntimeEnvironment.getApplication().getPackageName() + ":fbns";
        try {
            start(EVERY_FAMILY);
            assertFalse("the push process doesn't run on the earlier default", Settings.HIDE_ADS.savedValue());
        } finally {
            info.processName = mainProcess;
        }
        assertEquals("another process wrote to the settings store", before, store().getAll());
    }

    /** The receipt is HushThreads' own: a settings file never carries it, and can't write it. */
    @Test
    public void settingsFilesNeitherCarryNorWriteTheReceipt() throws Exception {
        start(EVERY_FAMILY);
        String receipt = receiptText();
        assertFalse(SettingsBackup.create().contains(SettingsUpgrade.RECEIPT));

        String file = "{\"format\":\"hushthreads-settings\",\"schema\":1,\"settings\":{\""
                + SettingsUpgrade.RECEIPT + "\":true,\"" + Settings.HIDE_ADS.key + "\":false}}";
        SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file);
        assertEquals(1, snapshot.unknown);
        assertEquals(1, SettingsBackup.apply(snapshot));
        assertEquals(receipt, receiptText());
    }

    // Helpers

    /** One start of the main process: the switches load, then Threads' start ends. */
    private static void start(Set<PatchFamily> build) {
        PatchFamily.inBuildForTests = build;
        SettingsUpgrade.onSwitchesLoaded(Settings.class);
        SettingsUpgrade.onThreadsStarted();
    }

    private static SharedPreferences store() {
        return Setting.preferences.preferences;
    }

    private static Map<String, Boolean> todaysDefaults() {
        Map<String, Boolean> defaults = new LinkedHashMap<>();
        for (BooleanSetting setting : SettingsUpgrade.switches().keySet()) defaults.put(setting.key, setting.defaultValue);
        return defaults;
    }

    private static List<String> names(Set<PatchFamily> build) {
        List<String> names = new ArrayList<>();
        for (PatchFamily family : build) names.add(family.name());
        return names;
    }

    private static JSONObject receiptJson(Set<PatchFamily> build, Map<String, Boolean> defaults) throws Exception {
        return new JSONObject(SettingsUpgrade.Receipt.write(names(build), defaults));
    }

    private static void writeReceipt(Set<PatchFamily> build, Map<String, Boolean> defaults) {
        store().edit().putString(SettingsUpgrade.RECEIPT, SettingsUpgrade.Receipt.write(names(build), defaults)).commit();
    }

    private static String receiptText() {
        return store().getString(SettingsUpgrade.RECEIPT, null);
    }

    private static SettingsUpgrade.Receipt receipt() {
        SettingsUpgrade.Receipt receipt = SettingsUpgrade.Receipt.parse(receiptText());
        assertNotNull("no readable receipt: " + receiptText(), receipt);
        return receipt;
    }

    private static void assertStored(BooleanSetting setting, boolean value) {
        assertTrue(setting.key + " isn't stored", store().contains(setting.key));
        assertEquals(setting.key + " stored", value, store().getBoolean(setting.key, !value));
        assertEquals(setting.key + " running", value, setting.savedValue());
    }
}
