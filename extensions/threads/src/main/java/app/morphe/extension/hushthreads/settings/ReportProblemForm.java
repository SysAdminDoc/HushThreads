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

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.preference.TwoStatePreference;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.preference.ReportProblem;

/**
 * HushThreads' half of Report a problem: the repository, its bug form's field ids
 * (.github/ISSUE_TEMPLATE/bug_report.yml) and the names that go in the versions line.
 * {@link ReportProblem} builds the address.
 */
@SuppressWarnings("deprecation")
final class ReportProblemForm {
    /** owner/name, read off the Source code link so the two can't drift apart. */
    static final String REPOSITORY = HushThreadsPreferenceFragment.SOURCE_URL
            .substring(HushThreadsPreferenceFragment.SOURCE_URL.indexOf("github.com/") + "github.com/".length());

    /** Where the reports live, for the message when no app opens the link. */
    static final String ISSUES_ADDRESS = HushThreadsPreferenceFragment.SOURCE_ADDRESS + "/issues";

    /** The bug form and the ids of the fields filled in. A renamed id in the form has to change here too. */
    static final ReportProblem.Form FORM = new ReportProblem.Form(REPOSITORY, "bug_report.yml",
            "versions", "device", "bug_description", "additional_context");

    private ReportProblemForm() {
    }

    /** The titles of the switches that are on, as the screen shows them and in its order. */
    static List<String> switchesOn(PreferenceGroup group) {
        List<String> titles = new ArrayList<>();
        collect(group, titles);
        return titles;
    }

    private static void collect(PreferenceGroup group, List<String> titles) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference row = group.getPreference(i);
            if (row instanceof PreferenceGroup) {
                collect((PreferenceGroup) row, titles);
            } else if (row instanceof TwoStatePreference && ((TwoStatePreference) row).isChecked()
                    && row.hasKey() && Setting.getSettingFromPath(row.getKey()) != null && row.getTitle() != null) {
                titles.add(row.getTitle().toString());
            }
        }
    }

    /** The new bug report's address, with this install's facts and [screen]'s switches filled in. */
    static String issueUrl(PreferenceGroup screen) {
        return ReportProblem.issueUrl(FORM, ReportProblem.facts("HushThreads", "Threads", switchesOn(screen)));
    }

    /** Opens the report in a browser, in a task of its own, or says no app here can. */
    static void open(Context context, PreferenceGroup screen) {
        try {
            context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(issueUrl(screen)))
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException missing) {
            // No browser, or none switched on. Uncaught, Android's exception closed Threads.
            Logger.printInfo(() -> "No app opened the bug report link");
            Utils.showToastLong(L10n.f("No app on this phone can open the link. The address is %1$s.",
                    L10n.isolate(ISSUES_ADDRESS)));
        }
    }
}
