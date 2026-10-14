/*
 * Copyright 2026 HushThreads contributors
 * https://github.com/SysAdminDoc/HushThreads
 */
package app.morphe.extension.hushthreads.settings;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.SettingsJson;

/**
 * Keeps a switch nobody touched on the value it had when a later build changes its default.
 *
 * <p>A switch left at its default isn't stored, so it reads the default of whichever build runs,
 * and a new default would turn it without a word. So the main process keeps a receipt in the
 * settings store: the families this build carries and the default of every switch this install
 * has had. When the switches load, one that isn't stored and whose default isn't the receipt's gets
 * the receipt's default saved as its value, before anything reads it, in one commit that also marks
 * it settled in the receipt. Only once that's saved is the receipt replaced with this build's, when
 * Threads' start finishes. A stored switch is the person's choice and is never touched.
 *
 * <p>A switch whose family was never in a build on this phone isn't in the receipt, so it starts on
 * the new build's default, as a new switch does. One whose family a build leaves out stays in the
 * receipt, so it's still known when the family comes back. An install with no receipt, or one this
 * build can't read, isn't guessed at: its switches keep the current defaults, and the receipt
 * starts there.
 *
 * <p>Only stored values and defaults are compared, never what a paused Threads is answered, so a
 * pause or safe mode can't turn into saved choices. Another process runs on the same values in
 * memory and writes nothing. No setting has the receipt's name, so a settings file never carries
 * it and an import can't write it.
 *
 * <p>The two steps are apart because {@link PatchFamily}, which knows the families, loads
 * {@link Settings}: the switches' own loading can't ask it without the two classes loading each
 * other, which two threads could do at once and wait on each other forever.
 */
public final class SettingsUpgrade {
    /** The receipt's key in the settings store. */
    static final String RECEIPT = "hushthreads_settings_receipt";
    /** The receipt shape this build writes and reads. Any other is read as no receipt. */
    static final int SCHEMA = 1;
    /** Far more than a receipt needs: one naming every family is under 1.5 KB. */
    static final int MAX_BYTES = 4096;

    static final String SCHEMA_NAME = "schema";
    static final String FAMILIES_NAME = "families";
    static final String DEFAULTS_NAME = "defaults";

    private static final SettingsJson.Limits LIMITS = new SettingsJson.Limits(3, 128, 64, 64, MAX_BYTES);

    /**
     * Whether this process kept every earlier default it had to, so the receipt naming them may
     * be replaced. Only the main process sets it.
     */
    private static volatile boolean earlierDefaultsSaved;

    private SettingsUpgrade() {
    }

    /** From {@link Settings}' static initializer, once every switch it declares has loaded. */
    static void onSwitchesLoaded(Class<?> holder) {
        earlierDefaultsSaved = false;
        try {
            int kept = keepEarlierDefaults(store(), declaredSwitches(holder));
            earlierDefaultsSaved = kept >= 0 && Utils.isMainProcess();
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Settings upgrade: couldn't compare the switches with the last build", failure);
        }
    }

    /** From the end of Threads' start, in the main process: this build's receipt. */
    static void onThreadsStarted() {
        try {
            // Nothing may have read a switch by the end of Threads' start, and then the switches
            // haven't loaded or been compared. PatchFamily loads them, so ask it first.
            Set<PatchFamily> families = PatchFamily.inThisBuild();
            if (!earlierDefaultsSaved) {
                Logger.printInfo(() -> "Settings upgrade: the receipt waits until the earlier defaults are saved");
                return;
            }
            replaceReceipt(store(), families);
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Settings upgrade: couldn't save the receipt", failure);
        }
    }

    /** The store every switch is saved in. */
    private static SharedPreferences store() {
        return Setting.preferences.preferences;
    }

    /**
     * Gives each of [switches] that isn't stored the default the receipt in [store] names, when
     * that isn't its default now. The main process saves them in one commit first. Another
     * process only runs on them.
     *
     * @return how many switches got their earlier default, or -1 when the commit failed and
     * nothing changed.
     */
    static int keepEarlierDefaults(SharedPreferences store, List<BooleanSetting> switches) {
        Receipt earlier = Receipt.parse(receiptText(store));
        if (earlier == null) return 0;
        Map<BooleanSetting, Boolean> kept = new LinkedHashMap<>();
        for (BooleanSetting setting : switches) {
            Boolean before = earlier.defaults.get(setting.key);
            if (before != null && !before.equals(setting.defaultValue) && !store.contains(setting.key)) {
                kept.put(setting, before);
            }
        }
        if (kept.isEmpty()) return 0;
        if (Utils.isMainProcess()) {
            try {
                SharedPreferences.Editor editor = store.edit();
                // The same commit marks each kept switch with today's default in the receipt. If the
                // full receipt is never written, a switch the person turns to today's default later
                // isn't taken back to the earlier one on the next start.
                Map<String, Boolean> settled = new LinkedHashMap<>(earlier.defaults);
                for (Map.Entry<BooleanSetting, Boolean> entry : kept.entrySet()) {
                    editor.putBoolean(entry.getKey().key, entry.getValue());
                    settled.put(entry.getKey().key, entry.getKey().defaultValue);
                }
                editor.putString(RECEIPT, Receipt.write(earlier.families, settled));
                if (!editor.commit()) throw new IllegalStateException("The settings store refused the commit");
            } catch (RuntimeException failure) {
                // Nothing changes. The receipt stays, so the next start tries again.
                Logger.printException(() -> "Settings upgrade: couldn't keep the earlier defaults", failure);
                return -1;
            }
            Logger.printInfo(() -> "Settings upgrade: kept the earlier default of " + kept.keySet());
        }
        for (Map.Entry<BooleanSetting, Boolean> entry : kept.entrySet()) {
            BooleanSetting.privateSetValue(entry.getKey(), entry.getValue());
        }
        return kept.size();
    }

    /**
     * Replaces the receipt in [store] with this [build]'s families and the defaults of the
     * switches this install has had, and writes nothing when that's what it holds already.
     *
     * @return whether the store holds this build's receipt now.
     */
    static boolean replaceReceipt(SharedPreferences store, Set<PatchFamily> build) {
        if (!Utils.isMainProcess()) return false;
        String text = receiptText(store);
        Receipt earlier = Receipt.parse(text);
        Map<String, Boolean> defaults = new LinkedHashMap<>();
        for (Map.Entry<BooleanSetting, PatchFamily> entry : switches().entrySet()) {
            BooleanSetting setting = entry.getKey();
            PatchFamily family = entry.getValue();
            // Once a switch is stored, or has been in a build, it's this install's: left at this
            // build's default later, that default is what the person had.
            if (family == null || build.contains(family) || store.contains(setting.key)
                    || (earlier != null && earlier.defaults.containsKey(setting.key))) {
                defaults.put(setting.key, setting.defaultValue);
            }
        }
        List<String> families = new ArrayList<>();
        for (PatchFamily family : PatchFamily.values()) {
            if (build.contains(family)) families.add(family.name());
        }
        String receipt = Receipt.write(families, defaults);
        if (receipt.equals(text)) return true;
        if (earlier != null) {
            logFamilies(earlier.families, build);
        } else if (text != null) {
            Logger.printInfo(() -> "Settings upgrade: replaced a receipt this build can't read, without guessing");
        }
        try {
            if (!store.edit().putString(RECEIPT, receipt).commit()) {
                throw new IllegalStateException("The settings store refused the commit");
            }
            return true;
        } catch (RuntimeException failure) {
            // The receipt from before stays, and with it what the next start compares against.
            Logger.printException(() -> "Settings upgrade: couldn't save the receipt", failure);
            return false;
        }
    }

    /** Every switch the receipt can hold, with its family, or null for the settings entry's own. */
    static Map<BooleanSetting, PatchFamily> switches() {
        Map<BooleanSetting, PatchFamily> all = new LinkedHashMap<>();
        for (BooleanSetting setting : PatchFamily.ENTRY_SWITCHES) all.put(setting, null);
        for (PatchFamily family : PatchFamily.values()) {
            for (BooleanSetting setting : family.switches) all.put(setting, family);
        }
        return all;
    }

    /** The switches [holder] declares, read from its fields so its loading needs no other class. */
    static List<BooleanSetting> declaredSwitches(Class<?> holder) {
        List<BooleanSetting> found = new ArrayList<>();
        for (Field field : holder.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (!Modifier.isStatic(modifiers) || !BooleanSetting.class.isAssignableFrom(field.getType())) continue;
            try {
                field.setAccessible(true);
                Object setting = field.get(null);
                if (setting != null) found.add((BooleanSetting) setting);
            } catch (IllegalAccessException unreadable) {
                throw new IllegalStateException(unreadable);
            }
        }
        return found;
    }

    @Nullable
    private static String receiptText(SharedPreferences store) {
        try {
            return store.getString(RECEIPT, null);
        } catch (ClassCastException notText) {
            return null;
        }
    }

    private static void logFamilies(Set<String> before, Set<PatchFamily> build) {
        List<String> added = new ArrayList<>();
        List<String> left = new ArrayList<>(before);
        for (PatchFamily family : build) {
            if (!left.remove(family.name())) added.add(family.name());
        }
        if (!added.isEmpty() || !left.isEmpty()) {
            Logger.printInfo(() -> "Settings upgrade: families added " + added + ", left out " + left);
        }
    }

    /** A receipt as one build wrote it. */
    static final class Receipt {
        /** Family names as the build wrote them, ones this build doesn't know included. */
        final Set<String> families;
        final Map<String, Boolean> defaults;

        private Receipt(Set<String> families, Map<String, Boolean> defaults) {
            this.families = families;
            this.defaults = defaults;
        }

        /** The receipt [text] holds, or null when there's none, or none this build can read. */
        @Nullable
        static Receipt parse(@Nullable String text) {
            if (text == null) return null;
            try {
                JSONObject root = SettingsJson.parseObject(text, LIMITS);
                Object schema = root.opt(SCHEMA_NAME);
                Object families = root.opt(FAMILIES_NAME);
                Object defaults = root.opt(DEFAULTS_NAME);
                if (!(schema instanceof Integer) || (Integer) schema != SCHEMA
                        || !(families instanceof JSONArray) || !(defaults instanceof JSONObject)) {
                    return null;
                }
                Set<String> names = new LinkedHashSet<>();
                JSONArray list = (JSONArray) families;
                for (int index = 0; index < list.length(); index++) {
                    Object name = list.opt(index);
                    if (!(name instanceof String)) return null;
                    names.add((String) name);
                }
                Map<String, Boolean> values = new LinkedHashMap<>();
                JSONObject table = (JSONObject) defaults;
                for (Iterator<String> keys = table.keys(); keys.hasNext(); ) {
                    String key = keys.next();
                    Object value = table.opt(key);
                    if (!(value instanceof Boolean)) return null;
                    values.put(key, (Boolean) value);
                }
                return new Receipt(names, values);
            } catch (IOException | JSONException | RuntimeException unreadable) {
                return null;
            }
        }

        static String write(Collection<String> familyNames, Map<String, Boolean> defaults) {
            try {
                JSONArray families = new JSONArray();
                for (String name : familyNames) families.put(name);
                JSONObject values = new JSONObject();
                for (Map.Entry<String, Boolean> entry : defaults.entrySet()) {
                    values.put(entry.getKey(), entry.getValue().booleanValue());
                }
                return new JSONObject()
                        .put(SCHEMA_NAME, SCHEMA)
                        .put(FAMILIES_NAME, families)
                        .put(DEFAULTS_NAME, values)
                        .toString();
            } catch (JSONException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }
}
