/*
 * Copyright 2026 HushThreads contributors
 * https://github.com/SysAdminDoc/HushThreads
 */
package app.morphe.extension.hushthreads.settings;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.Nullable;

/**
 * Opens Android's file picker for Export settings and Import settings, and takes its answer.
 *
 * <p>The settings page used to ask from its own fragment, and Android hands a fragment's answer
 * back by an internal name the fragment gets when it's added. Threads drops framework fragment
 * state from every activity it rebuilds, so after a font size or theme change, or a low-memory
 * kill, while the picker was up, the settings came back under another name and the picked file
 * went nowhere. This activity is HushThreads' own and keeps nothing in fragments: if Android ends
 * it, Android brings it back to take the answer, and the page takes the file the next time it's on
 * screen.
 *
 * <p>Android takes a picked file's access back when the activity that received it ends, which this
 * one does right away, so it keeps the access with a lasting grant ({@link SettingsFileGrants}).
 * The settings let go of it once the file is read or written. Not exported, and it draws nothing.
 */
public final class SettingsFilePicker extends Activity {
    static final String EXTRA_PICKER = "hushthreads_picker";
    static final String EXTRA_ACTION = "hushthreads_picker_action";
    /** In the saved state once the picker is open, so a rebuilt copy waits for its answer. */
    private static final String ASKED = "hushthreads_picker_asked";

    /**
     * [picker] for [action], opened through this activity, or null when Threads' manifest doesn't
     * list it, as on a Root Mount install that keeps the stock manifest. The page asks from its own
     * fragment then, as it used to.
     */
    @Nullable
    static Intent around(Context context, Intent picker, int action) {
        String own = context.getPackageName();
        Intent intent = new Intent().setClassName(own, SettingsFilePicker.class.getName());
        ResolveInfo screen = context.getPackageManager().resolveActivity(intent, 0);
        if (screen == null || screen.activityInfo == null || !own.equals(screen.activityInfo.packageName)) return null;
        return intent.putExtra(EXTRA_PICKER, picker).putExtra(EXTRA_ACTION, action);
    }

    @Override
    @SuppressWarnings("deprecation") // getParcelableExtra(String, Class) is Android 13 and newer.
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // Rebuilt while the picker is up: its answer still comes here.
        if (state != null && state.getBoolean(ASKED)) return;
        Intent picker = getIntent().getParcelableExtra(EXTRA_PICKER);
        int action = getIntent().getIntExtra(EXTRA_ACTION, 0);
        if (picker == null || !SettingsBackupPreference.isFileAction(action)) {
            finish();
            return;
        }
        try {
            startActivityForResult(picker, action);
        } catch (RuntimeException error) {
            SettingsBackupPreference.couldNotPick(action, error);
            finish();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean(ASKED, true);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        Uri file = resultCode == RESULT_OK && data != null ? data.getData() : null;
        // Kept past this activity's end, and only for a picker that made the access lasting.
        int grant = file == null ? 0 : SettingsFileGrants.take(this, file, data.getFlags());
        SettingsBackupPreference.picked(requestCode, file, grant);
        finish();
    }
}
