/*
 * Forked from https://github.com/SysAdminDoc/Hushfacebook at a788c516 (GPL-3.0),
 * modified for HushThreads (Threads), 2026.
 *
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.hushthreads.settings;

import android.content.Context;
import android.database.DataSetObserver;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.BaseAdapter;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.preference.SwitchPreference;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.HushThreadsPause;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;
import app.morphe.extension.shared.settings.preference.ReopenSettingsAfterRestart;

/**
 * A view of the complete preference model. Filtering never removes preferences from their
 * screen, so imports, dependencies, persistence and restart notices still reach hidden pages.
 */
@SuppressWarnings("deprecation")
final class SettingsNavigation extends BaseAdapter {
    private static final String STATE = "hushthreads_navigation";
    /** Marks the Pause, Resume or Undo button placed under a status row's text, so a rebind replaces it. */
    private static final String STATUS_ACTION = "hushthreads_status_action";
    /** The restart row's key. Like the other rows only this view draws, it stores nothing. */
    static final String RESTART = "action_restart_to_apply";
    /** The home page's Quick setups row: Quiet, Privacy or Back to defaults in one tap ({@link QuickSetupKeys}). */
    static final String QUICK_SETUPS = "action_quick_setups";
    /** Just above Support: a new GitHub bug report with this install's versions filled in ({@link ReportProblemForm}). */
    static final String REPORT = "action_report_problem";
    private final HushThreadsPreferenceFragment page;
    private final SettingsDialog host;
    private final ListView list;
    private final PreferenceScreen screen;
    private final ListAdapter source;
    private final List<Section> sections = new ArrayList<>();
    private final List<Preference> visible = new ArrayList<>();
    private final Preference browse;
    /**
     * The home page's second heading, over Appearance, Links, Updates and the other pages that
     * used to sit on a More settings page of their own. Every page is one tap from home: Hush
     * settings go at most one level deep.
     */
    private final Preference moreHeading;
    private final Preference quickSetups;
    private final Preference report;
    /** The home page's last row, which opens HushThreads' Ko-fi page in a browser. */
    private final Preference support;
    private final Preference empty;
    /** The line a category or search page starts with while a pause applies to it. */
    private final Preference pageStatus;
    /** Whether that line is showing, with its Resume or Undo. */
    private boolean pageAction;
    /**
     * Shown on every page while a change waits for a restart: a tap restarts Threads, which opens
     * this screen again on the page the row was tapped on ({@link ReopenSettingsAfterRestart}).
     */
    private final HushThreadsPreferenceFragment.Row restart;
    /** Keeps the shared fragment's restart toast off this screen, which shows the restart row instead. */
    private final AbstractPreferenceFragment.RestartFeedbackPresenter restartFeedback;
    /** The page the last rebuild drew, so a row coming or going on that same page keeps the list's place. */
    private String rebuiltPage;
    private String route = "";
    private String query = "";
    /**
     * The row Back returns to. With a screen reader on it takes accessibility focus once it's back
     * on screen: on the S22 TalkBack otherwise held the whole screen, and the person's place in
     * the list was lost.
     */
    private Preference refocus;
    private int homePosition;
    private int homeOffset;

    private final DataSetObserver changes = new DataSetObserver() {
        @Override public void onChanged() { rebuild(); }
        @Override public void onInvalidated() { rebuild(); }
    };

    SettingsNavigation(HushThreadsPreferenceFragment page, SettingsDialog host, Bundle saved) {
        this.page = page;
        this.host = host;
        screen = page.getPreferenceScreen();
        list = page.getView().findViewById(android.R.id.list);
        source = screen.getRootAdapter();
        Context context = screen.getContext();
        // Stable English route IDs survive a locale change; the displayed names are localized.
        String feedDetail = PatchFamily.HIDE_SUGGESTED_USERS.inBuild()
                ? (PatchFamily.HIDE_ADS.inBuild() ? L10n.t("Ads and suggested accounts in your feed")
                : L10n.t("Suggested accounts in your feed"))
                : PatchFamily.HIDE_ADS.inBuild() ? L10n.t("Sponsored posts in For you and Following")
                : PatchFamily.RETURN_REFRESH.inBuild() ? L10n.t("Where you left off in your feed")
                : PatchFamily.VIDEO_AUTOPLAY.inBuild() ? L10n.t("Videos in your feed")
                : L10n.t("Sponsored posts in For you and Following");
        section("Feed", L10n.t("Feed"), feedDetail, SettingsIcons.FEED, true);
        section("Privacy", L10n.t("Privacy"), L10n.t("Tracking in links and analytics uploads"), SettingsIcons.BLOCK, true);
        section("Downloads", L10n.t("Downloads"), L10n.t("Photos and videos you save from posts"), SettingsIcons.DOWNLOADS, true);
        section("Appearance", L10n.t("Appearance"), null, SettingsIcons.APPEARANCE, false);
        section("Posts", L10n.t("Posts"), L10n.t("Copy text when you hold a post"), SettingsIcons.CHAT, true);
        section("Links", L10n.t("Links"), null, SettingsIcons.LINKS, false);
        section("Updates", L10n.t("Updates"), null, SettingsIcons.UPDATES, false);
        section("Set when you patched", L10n.t("Set when you patched"), null, SettingsIcons.CALENDAR, false);
        section("Pause, backup and diagnostics", L10n.t("Pause, backup and diagnostics"), null, SettingsIcons.STORAGE, false);
        section("About", L10n.t("About"), null, SettingsIcons.ABOUT, false);
        browse = new HushThreadsPreferenceFragment.Heading(context);
        browse.setTitle(L10n.t("Browse settings"));
        moreHeading = new HushThreadsPreferenceFragment.Heading(context);
        moreHeading.setTitle(L10n.t("More settings"));
        quickSetups = link(context, L10n.t("Quick setups"),
                L10n.t("Turn on a set of switches in one tap, or put every switch back the way it started. Press and hold to hide this row."),
                SettingsIcons.BUILD);
        quickSetups.setKey(QUICK_SETUPS);
        quickSetups.setOnPreferenceClickListener(ignored -> { page.showQuickSetups(); return true; });
        ((HushThreadsPreferenceFragment.Row) quickSetups).longPress = page::hideQuickSetups;
        report = link(context, L10n.t("Report a problem"),
                L10n.t("Opens a GitHub report with your versions and settings filled in. Add what happened."), SettingsIcons.BUG);
        report.setKey(REPORT);
        report.setOnPreferenceClickListener(row -> { ReportProblemForm.open(row.getContext(), screen); return true; });
        support = link(context, L10n.t("Support HushThreads"), null, SettingsIcons.HEART);
        support.setKey(HushThreadsPreferenceFragment.SUPPORT);
        support.setOnPreferenceClickListener(row -> { HushThreadsPreferenceFragment.openSupport(row.getContext()); return true; });
        empty = new HushThreadsPreferenceFragment.Row(context);
        empty.setTitle(L10n.t("No matching settings"));
        empty.setSummary(L10n.t("Try a different word or clear the search."));
        empty.setSelectable(false);
        empty.setPersistent(false);
        pageStatus = new HushThreadsPreferenceFragment.Row(context);
        pageStatus.setSelectable(false);
        pageStatus.setPersistent(false);
        restart = new HushThreadsPreferenceFragment.Row(context);
        restart.setKey(RESTART);
        restart.setPersistent(false);
        // A tap restarts at once, so the row goes without a chevron.
        restart.actsAtOnce = true;
        restart.setSummary(L10n.t("Threads closes and reopens on this page."));
        restart.setIcon(SettingsIcons.icon(context, SettingsIcons.UPDATES, palette().heading));
        restart.setOnPreferenceClickListener(row -> {
            ReopenSettingsAfterRestart.restartInto(row.getContext(), route);
            return true;
        });
        // The row appears as the switch flips, and the screen's live status reads its count to a
        // screen reader, since the row can land above the part of the list in view. This only
        // keeps the shared fragment's toast off the screen.
        restartFeedback = (shown, message) -> { };
        AbstractPreferenceFragment.setRestartFeedbackPresenter(restartFeedback);
        Bundle state = saved == null ? null : saved.getBundle(STATE);
        if (state != null) {
            route = state.getString("route", "");
            query = state.getString("query", "");
            homePosition = state.getInt("homePosition");
            homeOffset = state.getInt("homeOffset");
        } else {
            // Nothing saved: the page the screen was asked to open on, such as the one a restart
            // from the restart row left.
            route = host.startPage();
        }
        // A route that names no page, such as "more" saved before More settings joined the home
        // page, opens home.
        if (selected() == null) route = "";
        source.registerDataSetObserver(changes);
        list.setAdapter(this);
        list.setOnItemClickListener((parent, view, position, id) -> {
            Preference item = getItem(position);
            int original = originalPosition(item);
            if (original >= 0) {
                // PreferenceScreen indexes its OWN adapter, not the adapter on the ListView.
                screen.onItemClick(parent, view, original, source.getItemId(original));
            } else if (item.getOnPreferenceClickListener() != null) {
                item.getOnPreferenceClickListener().onPreferenceClick(item);
            }
        });
        // A long press takes Quick setups off the home page; a row on the Pause, backup and
        // diagnostics page brings it back. A row with no long press of its own leaves it alone.
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            Runnable longPress = HushThreadsPreferenceFragment.RowSemantics.longPressOf(getItem(position));
            if (longPress == null) return false;
            longPress.run();
            return true;
        });
        // That listener makes the list itself long-clickable, and a screen reader then stops on the
        // whole list to offer a long press. The list keeps it for touch, and RowSemantics offers it
        // on the one row that has one.
        list.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setLongClickable(false);
                info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK);
            }
        });
        rebuild();
        host.showPage(title(), route.isEmpty(), query);
        if (state != null) showAt(state.getInt("position"), state.getInt("offset"));
    }

    private void section(String id, String title, String detail, String icon, boolean primary) {
        for (Preference candidate : page.sections()) {
            if (!title.contentEquals(candidate.getTitle())) continue;
            Preference link = link(screen.getContext(), title, detail, icon);
            link.setKey("section_" + id);
            link.setOnPreferenceClickListener(ignored -> { navigate(id); return true; });
            sections.add(new Section(id, (PreferenceCategory) candidate, link, primary));
            return;
        }
    }

    private static Preference link(Context context, String title, String summary, String icon) {
        Preference row = new HushThreadsPreferenceFragment.Row(context);
        row.setTitle(title);
        row.setSummary(summary);
        row.setIcon(SettingsIcons.icon(context, icon, palette().summary));
        row.setPersistent(false);
        return row;
    }

    private Section selected() {
        for (Section section : sections) if (section.id.equals(route)) return section;
        return null;
    }

    private CharSequence title() {
        Section section = selected();
        return section == null ? "HushThreads" : section.category.getTitle();
    }

    void navigate(String destination) {
        refocus = null;
        rememberIndex();
        query = "";
        route = destination;
        if (selected() == null) route = "";
        rebuild();
        host.showPage(title(), route.isEmpty(), query);
        showAt(0, 0);
        list.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
    }

    boolean open(Preference preference) {
        for (Section section : sections) {
            if (preference != section.category && preference.getParent() != section.category) continue;
            navigate(section.id);
            // A link to one setting lands on its row rather than the top of a long page.
            int row = visible.indexOf(preference);
            if (row > 0) showAt(row, 0);
            return true;
        }
        return false;
    }

    void search(String text) {
        if (query.equals(text)) return;
        refocus = null;
        if (query.isEmpty()) rememberIndex();
        query = text;
        route = "";
        rebuild();
        showAt(query.isEmpty() ? homePosition : 0, query.isEmpty() ? homeOffset : 0);
    }

    boolean back() {
        if (!query.isEmpty()) {
            search("");
            host.showPage(title(), true, "");
            return true;
        }
        if (route.isEmpty()) return false;
        Section section = selected();
        // Every page opens from home, so Back goes home, to the place the list was left.
        route = "";
        rebuild();
        host.showPage(title(), true, query);
        showAt(homePosition, homeOffset);
        refocus = section != null ? section.link : null;
        return true;
    }

    private void showAt(int position, int offset) {
        // A header resize otherwise syncs the old page's visible children back over this
        // selection in touch mode. Rebind when changing pages; normal preference updates
        // still keep their current views and scroll position through notifyDataSetChanged.
        list.setAdapter(this);
        list.setSelectionFromTop(position, offset);
    }

    private void rememberIndex() {
        View first = list.getChildAt(0);
        int offset = first == null ? 0 : first.getTop();
        if (route.isEmpty() && query.isEmpty()) {
            homePosition = list.getFirstVisiblePosition();
            homeOffset = offset;
        }
    }

    /** The page in view: a section's route, or "" for home. */
    String route() {
        return route;
    }

    void save(Bundle out) {
        Bundle state = new Bundle();
        state.putString("route", route);
        state.putString("query", query);
        state.putInt("homePosition", homePosition);
        state.putInt("homeOffset", homeOffset);
        state.putInt("position", list.getFirstVisiblePosition());
        View first = list.getChildAt(0);
        state.putInt("offset", first == null ? 0 : first.getTop());
        out.putBundle(STATE, state);
    }

    void close() {
        source.unregisterDataSetObserver(changes);
        AbstractPreferenceFragment.clearRestartFeedbackPresenter(restartFeedback);
    }

    /** A change that waits for a restart was made or taken back: the restart row comes, goes or counts again. */
    void restartChanged() { rebuild(); }

    /** Quick setups was hidden or brought back: the home page shows it or leaves it out from now on. */
    void quickSetupsChanged() { rebuild(); }

    private void rebuild() {
        int restartWas = visible.indexOf(restart);
        boolean pageStatusWas = visible.contains(pageStatus);
        visible.clear();
        String terms = normalized(query).trim();
        Section selected = selected();
        if (!terms.isEmpty()) {
            for (Section section : sections) {
                boolean headingAdded = false;
                for (int i = 0; i < section.category.getPreferenceCount(); i++) {
                    Preference row = section.category.getPreference(i);
                    if ("hushthreads_saves_heading".equals(row.getKey())) continue;
                    String text = normalized(section.category.getTitle() + " " + row.getTitle() + " " + row.getSummary());
                    boolean matches = true;
                    for (String term : terms.split("\\s+")) if (!text.contains(term)) matches = false;
                    if (!matches) continue;
                    if (!headingAdded) visible.add(section.category);
                    headingAdded = true;
                    visible.add(row);
                }
            }
            host.showResults(visible.size() - headings());
            if (visible.isEmpty()) visible.add(empty);
            else addPageStatus();
        } else if (selected != null) {
            for (int i = 0; i < selected.category.getPreferenceCount(); i++) visible.add(selected.category.getPreference(i));
            addPageStatus();
        } else {
            visible.add(screen.getPreference(0));
            // Hushfacebook 814acd23: show the missing-default row under the overview card.
            Preference missing = screen.findPreference(HushThreadsPreferenceFragment.MISSING_DEFAULTS);
            if (missing != null) visible.add(missing);
            if (!BaseSettings.HIDE_QUICK_SETUPS.savedValue()) visible.add(quickSetups);
            // Each heading only over pages this build has: a build with none of the feature
            // patches shows no empty Browse settings heading.
            boolean anyPrimary = false;
            boolean anyMore = false;
            for (Section section : sections) {
                if (section.primary) anyPrimary = true;
                else anyMore = true;
            }
            if (anyPrimary) visible.add(browse);
            for (Section section : sections) if (section.primary) visible.add(section.link);
            if (anyMore) visible.add(moreHeading);
            for (Section section : sections) if (!section.primary) visible.add(section.link);
            // Report a problem sits just above Support, which stays last.
            visible.add(report);
            visible.add(support);
        }
        addRestart();
        if (terms.isEmpty()) host.showResults(-1);
        notifyDataSetChanged();
        keepPlace(restartWas, pageStatusWas);
    }

    /**
     * The restart row, on whatever page is showing, while a change waits for a restart: under the
     * home page's status card or a page's pause line, and otherwise first. Its title counts the
     * changes a restart applies, and the screen's live status holds the same words.
     */
    private void addRestart() {
        Context context = screen.getContext();
        int count = ReopenSettingsAfterRestart.changesWaiting(context);
        if (count <= 0) {
            host.showRestartStatus(null);
            return;
        }
        restart.setTitle(L10n.quantity(context, count, "Restart Threads to apply this change",
                "Restart Threads to apply %1$d changes", count));
        // Every rebuild comes through here, and only a new count is a change to read.
        host.showRestartStatus(restart.getTitle());
        boolean underStatus = !visible.isEmpty()
                && (visible.get(0) == pageStatus || visible.get(0) == screen.getPreference(0));
        visible.add(underStatus ? 1 : 0, restart);
    }

    /**
     * The restart row comes and goes above the rows in view when a switch is flipped. On the same
     * page, with nothing else above moving, the list moves with it, so the row that was tapped
     * stays where it was. A row added at the top of what's in view is left to push the list down,
     * so it's seen. At the top of the page a list that hasn't been laid out yet, or one a keyboard
     * moves through, is bound again: the selection it holds would otherwise land on the new row
     * and leave a pause line above it out of view, with its Undo.
     */
    private void keepPlace(int restartWas, boolean pageStatusWas) {
        String pageWas = rebuiltPage;
        rebuiltPage = route + '\n' + query;
        int restartNow = visible.indexOf(restart);
        if (!rebuiltPage.equals(pageWas) || (restartWas < 0) == (restartNow < 0) || list.getAdapter() != this) return;
        int first = list.getFirstVisiblePosition();
        if (first == 0) {
            if (list.getChildCount() == 0 || (restartNow >= 0 && !list.isInTouchMode())) list.setAdapter(this);
            return;
        }
        if (pageStatusWas != visible.contains(pageStatus) || list.getChildCount() == 0) return;
        // The list places a row this far under its own top padding.
        int top = list.getChildAt(0).getTop() - list.getPaddingTop();
        if (restartNow >= 0 && restartNow < first) list.setSelectionFromTop(first + 1, top);
        else if (restartWas >= 0 && restartWas < first) list.setSelectionFromTop(first - 1, top);
    }

    private int headings() {
        int count = 0;
        for (Preference item : visible) if (item instanceof PreferenceCategory) count++;
        return count;
    }

    /**
     * Starts a category or search page with a short line while HushThreads is paused or a pause
     * or its end waits for a restart, when the page shows a switch Pause reaches. On a paused start
     * the switches keep showing what was saved, which read as active without it. Nothing here
     * changes a saved choice. A restart owed for anything else is the restart row's to say.
     */
    private void addPageStatus() {
        Context context = screen.getContext();
        boolean paused = HushThreadsPause.isPaused();
        boolean nextPaused = HushThreadsPause.pausesNextStart(context);
        pageAction = (paused || nextPaused) && showsPausable();
        if (!pageAction) return;
        pageStatus.setTitle(paused ? L10n.t("HushThreads is paused") : L10n.t("HushThreads is on"));
        // Worded like the Pause switch: Debug logging and what was set when patching stay in.
        pageStatus.setSummary(paused == nextPaused
                ? L10n.t("Until you resume, every switch but Debug logging acts as if it were off. "
                + "Changes made when you patched stay in.")
                : paused ? L10n.t("HushThreads turns back on when Threads restarts.")
                : L10n.t("HushThreads pauses when Threads restarts."));
        pageStatus.setIcon(SettingsIcons.icon(context, paused ? SettingsIcons.PAUSE : SettingsIcons.PATCHED,
                paused ? palette().summary : palette().heading));
        visible.add(0, pageStatus);
    }

    /** Whether the page shows a setting Pause answers for, or the Pause switch itself. */
    private boolean showsPausable() {
        for (Preference row : visible) {
            Setting<?> setting = row.hasKey() ? Setting.getSettingFromPath(row.getKey()) : null;
            if (setting != null && (!setting.isKeptWhenPaused() || setting == BaseSettings.PAUSED)) return true;
        }
        return false;
    }

    private static String normalized(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
    }

    private int originalPosition(Preference item) {
        for (int i = 0; i < source.getCount(); i++) if (source.getItem(i) == item) return i;
        return -1;
    }

    @Override public int getCount() { return visible.size(); }
    @Override public Preference getItem(int position) { return visible.get(position); }
    @Override public long getItemId(int position) { return System.identityHashCode(getItem(position)); }
    @Override public boolean hasStableIds() { return true; }
    @Override public boolean areAllItemsEnabled() { return false; }
    @Override public boolean isEnabled(int position) { return getItem(position).isEnabled() && getItem(position).isSelectable(); }
    // Preference creates several incompatible layouts. Never recycle one kind into another.
    @Override public int getItemViewType(int position) { return IGNORE_ITEM_VIEW_TYPE; }

    @Override public View getView(int position, View recycled, ViewGroup parent) {
        Preference item = getItem(position);
        View row = item.getView(null, parent);
        if (item == refocus) {
            refocus = null;
            // Posted from the new row, it runs after this layout has put the row in the window.
            row.post(() -> row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null));
        }
        if (item == support) palette().paintSurface(row, item, 24);
        if (item == screen.getPreference(0)) {
            TextView title = row.findViewById(android.R.id.title);
            if (title != null) title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
        }
        if (item == screen.getPreference(0)) {
            palette().paintStatus(row);
            bindStatus(row);
        }
        if (item == pageStatus && pageAction) {
            bindAction(row, HushThreadsPause.isPaused(), HushThreadsPause.pausesNextStart(screen.getContext()));
        }
        return row;
    }

    private void bindStatus(View row) {
        boolean nextPaused = HushThreadsPause.pausesNextStart(screen.getContext());
        boolean paused = HushThreadsPause.isPaused();
        TextView summary = row.findViewById(android.R.id.summary);
        if (!paused && !nextPaused && ReleaseCheck.statusLine() == null) {
            summary.setText(L10n.t("Your controls are active."));
        } else if (paused && nextPaused && HushThreadsPause.reason() == HushThreadsPause.Reason.SWITCH
                && !markerLeft()) {
            // A marker Resume couldn't remove keeps the card's own line, which says what to do.
            summary.setText(L10n.t("Your choices are saved. Tap Resume, then restart Threads."));
        }
        bindAction(row, paused, nextPaused);
    }

    /**
     * Pause, Resume or Undo in [row], whichever changes the next start. The list keeps its place,
     * so the page the button is on stays where it was.
     */
    private void bindAction(View row, boolean paused, boolean nextPaused) {
        ViewGroup frame = row.findViewById(android.R.id.widget_frame);
        frame.removeAllViews();
        // A frame that matches the row's height never adds to it, so the row was as tall as its two
        // lines of text and the button inside was held under its 48dp floor (39.5dp on a Galaxy S22).
        ViewGroup.LayoutParams frameSize = frame.getLayoutParams();
        if (frameSize != null && frameSize.height == ViewGroup.LayoutParams.MATCH_PARENT) {
            frameSize.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            frame.setLayoutParams(frameSize);
        }
        Button action = new Button(screen.getContext());
        action.setText(paused != nextPaused ? L10n.t("Undo") : nextPaused ? L10n.t("Resume") : L10n.t("Pause"));
        action.setAllCaps(false);
        action.setTextSize(14);
        action.setTextColor(palette().heading);
        palette().paintStatusAction(action);
        action.setMinWidth(dp(48));
        action.setMinHeight(dp(48));
        action.setMinimumWidth(dp(48));
        action.setMinimumHeight(dp(48));
        action.setOnClickListener(ignored -> {
            if (nextPaused) {
                page.resumeFromOverview();
            } else {
                Preference pause = page.findPreference(BaseSettings.PAUSED.key);
                if (pause instanceof SwitchPreference) ((SwitchPreference) pause).setChecked(!nextPaused);
            }
            rebuild();
        });
        // Large text needs the full column. Keep recovery above the summary, which can include
        // a long build identity or an error, so the action stays in view before scrolling.
        TextView summary = row.findViewById(android.R.id.summary);
        if (screen.getContext().getResources().getConfiguration().fontScale >= 1.5f
                && summary != null && summary.getParent() instanceof android.widget.RelativeLayout) {
            android.widget.RelativeLayout.LayoutParams place = new android.widget.RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            action.setId(View.generateViewId());
            place.addRule(android.widget.RelativeLayout.BELOW, android.R.id.title);
            place.addRule(android.widget.RelativeLayout.ALIGN_PARENT_START);
            ViewGroup column = (ViewGroup) summary.getParent();
            View earlier = column.findViewWithTag(STATUS_ACTION);
            if (earlier != null) column.removeView(earlier);
            action.setTag(STATUS_ACTION);
            column.addView(action, place);
            android.widget.RelativeLayout.LayoutParams summaryPlace =
                    (android.widget.RelativeLayout.LayoutParams) summary.getLayoutParams();
            summaryPlace.addRule(android.widget.RelativeLayout.BELOW, action.getId());
            summary.setLayoutParams(summaryPlace);
            frame.setVisibility(View.GONE);
            return;
        }
        frame.addView(action, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        frame.setVisibility(View.VISIBLE);
    }

    private boolean markerLeft() {
        java.io.File marker = HushThreadsPause.markerFile(screen.getContext());
        return marker != null && marker.exists();
    }

    private int dp(int value) { return Math.round(value * screen.getContext().getResources().getDisplayMetrics().density); }

    private static ScreenColors palette() { return ScreenColors.DEFAULT; }

    private static final class Section {
        final String id;
        final PreferenceCategory category;
        final Preference link;
        final boolean primary;
        Section(String id, PreferenceCategory category, Preference link, boolean primary) {
            this.id = id;
            this.category = category;
            this.link = link;
            this.primary = primary;
        }
    }
}
