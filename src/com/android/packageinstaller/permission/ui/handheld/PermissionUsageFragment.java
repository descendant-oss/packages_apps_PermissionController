/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.packageinstaller.permission.ui.handheld;

import static java.lang.annotation.RetentionPolicy.SOURCE;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MINUTES;

import android.app.ActionBar;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.fragment.app.DialogFragment;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;

import com.android.packageinstaller.permission.model.AppPermissionGroup;
import com.android.packageinstaller.permission.model.AppPermissionUsage;
import com.android.packageinstaller.permission.model.AppPermissionUsage.GroupUsage;
import com.android.packageinstaller.permission.model.PermissionApps;
import com.android.packageinstaller.permission.model.PermissionApps.PermissionApp;
import com.android.packageinstaller.permission.model.PermissionUsages;
import com.android.packageinstaller.permission.ui.AdjustUserSensitiveActivity;
import com.android.packageinstaller.permission.utils.Utils;
import com.android.permissioncontroller.R;
import com.android.settingslib.HelpUtils;

import java.lang.annotation.Retention;
import java.text.Collator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Show the usage of all apps of all permission groups.
 *
 * <p>Shows a filterable list of app usage of permission groups, each of which links to
 * AppPermissionsFragment.
 */
public class PermissionUsageFragment extends SettingsWithLargeHeader implements
        PermissionUsages.PermissionsUsagesChangeCallback {
    private static final String LOG_TAG = "PermissionUsageFragment";

    @Retention(SOURCE)
    @IntDef(value = {SORT_RECENT, SORT_RECENT_APPS})
    @interface SortOption {}
    static final int SORT_RECENT = 1;
    static final int SORT_RECENT_APPS = 2;

    private static final int MENU_SORT_BY_APP = MENU_HIDE_SYSTEM + 1;
    private static final int MENU_SORT_BY_TIME = MENU_HIDE_SYSTEM + 2;
    private static final int MENU_FILTER_BY_PERMISSIONS = MENU_HIDE_SYSTEM + 3;
    private static final int MENU_FILTER_BY_TIME = MENU_HIDE_SYSTEM + 4;
    private static final int MENU_REFRESH = MENU_HIDE_SYSTEM + 5;
    private static final int MENU_ADJUST_USER_SENSITIVE = MENU_HIDE_SYSTEM + 6;

    private static final String KEY_SHOW_SYSTEM_PREFS = "_show_system";
    private static final String SHOW_SYSTEM_KEY = PermissionUsageFragment.class.getName()
            + KEY_SHOW_SYSTEM_PREFS;
    private static final String KEY_PERMS_INDEX = "_perms_index";
    private static final String PERMS_INDEX_KEY = PermissionUsageFragment.class.getName()
            + KEY_PERMS_INDEX;
    private static final String KEY_TIME_INDEX = "_time_index";
    private static final String TIME_INDEX_KEY = PermissionUsageFragment.class.getName()
            + KEY_TIME_INDEX;
    private static final String KEY_SORT = "_sort";
    private static final String SORT_KEY = PermissionUsageFragment.class.getName()
            + KEY_SORT;
    private static final String KEY_FINISHED_INITIAL_LOAD = "_finished_initial_load";
    private static final String FINISHED_INITIAL_LOAD_KEY = PermissionUsageFragment.class.getName()
            + KEY_FINISHED_INITIAL_LOAD;

    private @NonNull PermissionUsages mPermissionUsages;

    private Collator mCollator;

    private @NonNull List<TimeFilterItem> mFilterTimes;
    private int mFilterTimeIndex;
    private String mFilterGroup;
    private @SortOption int mSort;

    private boolean mShowSystem;
    private boolean mHasSystemApps;
    private MenuItem mShowSystemMenu;
    private MenuItem mHideSystemMenu;
    private MenuItem mSortByApp;
    private MenuItem mSortByTime;

    private ArrayMap<String, Integer> mGroupAppCounts;

    private boolean mFinishedInitialLoad;

    /**
     * Only used to restore permission selection state or use the passed permission after onCreate.
     * Once the first list of groups is reported, this becomes invalid.
     */
    private String mSavedGroupName;

    /**
     * @return A new fragment
     */
    public static @NonNull PermissionUsageFragment newInstance(@Nullable String groupName,
            long numMillis) {
        PermissionUsageFragment fragment = new PermissionUsageFragment();
        Bundle arguments = new Bundle();
        if (groupName != null) {
            arguments.putString(Intent.EXTRA_PERMISSION_GROUP_NAME, groupName);
        }
        arguments.putLong(Intent.EXTRA_DURATION_MILLIS, numMillis);
        fragment.setArguments(arguments);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mSort = SORT_RECENT_APPS;
        initializeTimeFilter();
        if (savedInstanceState != null) {
            mShowSystem = savedInstanceState.getBoolean(SHOW_SYSTEM_KEY);
            mSavedGroupName = savedInstanceState.getString(PERMS_INDEX_KEY);
            mFilterTimeIndex = savedInstanceState.getInt(TIME_INDEX_KEY);
            mSort = savedInstanceState.getInt(SORT_KEY);
            mFinishedInitialLoad = savedInstanceState.getBoolean(FINISHED_INITIAL_LOAD_KEY);
        }

        setLoading(true, false);
        setHasOptionsMenu(true);
        ActionBar ab = getActivity().getActionBar();
        if (ab != null) {
            ab.setDisplayHomeAsUpEnabled(true);
        }

        if (mSavedGroupName == null) {
            mSavedGroupName = getArguments().getString(Intent.EXTRA_PERMISSION_GROUP_NAME);
        }

        Context context = getPreferenceManager().getContext();
        mFilterGroup = null;
        mCollator = Collator.getInstance(
                context.getResources().getConfiguration().getLocales().get(0));
        mPermissionUsages = new PermissionUsages(context);
    }

    @Override
    public void onStart() {
        super.onStart();
        getActivity().setTitle(R.string.permission_usage_title);

        if (mFinishedInitialLoad) {
            setProgressBarVisible(true);
        }

        reloadData();
    }

    /**
     * Initialize the time filter to show the smallest entry greater than the time passed in as an
     * argument.  If nothing is passed, this simply initializes the possible values.
     */
    private void initializeTimeFilter() {
        Context context = getPreferenceManager().getContext();
        mFilterTimes = new ArrayList<>();
        mFilterTimes.add(new TimeFilterItem(Long.MAX_VALUE,
                context.getString(R.string.permission_usage_any_time),
                R.string.permission_usage_list_title_any_time));
        mFilterTimes.add(new TimeFilterItem(DAYS.toMillis(7),
                context.getString(R.string.permission_usage_last_7_days),
                R.string.permission_usage_list_title_last_7_days));
        mFilterTimes.add(new TimeFilterItem(DAYS.toMillis(1),
                context.getString(R.string.permission_usage_last_day),
                R.string.permission_usage_list_title_last_day));
        mFilterTimes.add(new TimeFilterItem(HOURS.toMillis(1),
                context.getString(R.string.permission_usage_last_hour),
                R.string.permission_usage_list_title_last_hour));
        mFilterTimes.add(new TimeFilterItem(MINUTES.toMillis(15),
                context.getString(R.string.permission_usage_last_15_minutes),
                R.string.permission_usage_list_title_last_15_minutes));
        mFilterTimes.add(new TimeFilterItem(MINUTES.toMillis(1),
                context.getString(R.string.permission_usage_last_minute),
                R.string.permission_usage_list_title_last_minute));

        long numMillis = getArguments().getLong(Intent.EXTRA_DURATION_MILLIS);
        long supremum = Long.MAX_VALUE;
        int supremumIndex = -1;
        int numTimes = mFilterTimes.size();
        for (int i = 0; i < numTimes; i++) {
            long curTime = mFilterTimes.get(i).getTime();
            if (curTime >= numMillis && curTime <= supremum) {
                supremum = curTime;
                supremumIndex = i;
            }
        }
        if (supremumIndex != -1) {
            mFilterTimeIndex = supremumIndex;
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(SHOW_SYSTEM_KEY, mShowSystem);
        outState.putString(PERMS_INDEX_KEY, mFilterGroup);
        outState.putInt(TIME_INDEX_KEY, mFilterTimeIndex);
        outState.putInt(SORT_KEY, mSort);
        outState.putBoolean(FINISHED_INITIAL_LOAD_KEY, mFinishedInitialLoad);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        mSortByApp = menu.add(Menu.NONE, MENU_SORT_BY_APP, Menu.NONE, R.string.sort_by_app);
        mSortByTime = menu.add(Menu.NONE, MENU_SORT_BY_TIME, Menu.NONE, R.string.sort_by_time);
        menu.add(Menu.NONE, MENU_FILTER_BY_PERMISSIONS, Menu.NONE, R.string.filter_by_permissions);
        menu.add(Menu.NONE, MENU_FILTER_BY_TIME, Menu.NONE, R.string.filter_by_time);
        if (mHasSystemApps) {
            mShowSystemMenu = menu.add(Menu.NONE, MENU_SHOW_SYSTEM, Menu.NONE,
                    R.string.menu_show_system);
            mHideSystemMenu = menu.add(Menu.NONE, MENU_HIDE_SYSTEM, Menu.NONE,
                    R.string.menu_hide_system);
        }

        menu.add(Menu.NONE, MENU_ADJUST_USER_SENSITIVE, Menu.NONE,
                R.string.menu_adjust_user_sensitive);

        HelpUtils.prepareHelpMenuItem(getActivity(), menu, R.string.help_permission_usage,
                getClass().getName());
        MenuItem refresh = menu.add(Menu.NONE, MENU_REFRESH, Menu.NONE,
                R.string.permission_usage_refresh);
        refresh.setIcon(R.drawable.ic_refresh);
        refresh.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        updateMenu();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                getActivity().finish();
                return true;
            case MENU_SORT_BY_APP:
                mSort = SORT_RECENT_APPS;
                updateUI();
                updateMenu();
                break;
            case MENU_SORT_BY_TIME:
                mSort = SORT_RECENT;
                updateUI();
                updateMenu();
                break;
            case MENU_FILTER_BY_PERMISSIONS:
                showPermissionFilterDialog();
                break;
            case MENU_FILTER_BY_TIME:
                showTimeFilterDialog();
                break;
            case MENU_SHOW_SYSTEM:
            case MENU_HIDE_SYSTEM:
                mShowSystem = item.getItemId() == MENU_SHOW_SYSTEM;
                // We already loaded all data, so don't reload
                updateUI();
                updateMenu();
                break;
            case MENU_REFRESH:
                reloadData();
                break;
            case MENU_ADJUST_USER_SENSITIVE:
                getActivity().startActivity(
                        new Intent(getContext(), AdjustUserSensitiveActivity.class));
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    private void updateMenu() {
        if (mHasSystemApps) {
            mShowSystemMenu.setVisible(!mShowSystem);
            mHideSystemMenu.setVisible(mShowSystem);
        }
        mSortByApp.setVisible(mSort != SORT_RECENT_APPS);
        mSortByTime.setVisible(mSort != SORT_RECENT);
    }

    @Override
    public void onPermissionUsagesChanged() {
        if (!Utils.isPermissionsHubEnabled()) {
            setLoading(false, true);
            return;
        }
        if (mPermissionUsages.getUsages().isEmpty()) {
            return;
        }

        // Use the saved permission group or the one passed as an argument, if applicable.
        if (mSavedGroupName != null && mFilterGroup == null) {
            if (getGroup(mSavedGroupName) != null) {
                mFilterGroup = mSavedGroupName;
                mSavedGroupName = null;
            }
        }

        updateUI();
    }

    @Override
    public int getEmptyViewString() {
        return R.string.no_permission_usages;
    }

    private void updateUI() {
        final List<AppPermissionUsage> appPermissionUsages =
                new ArrayList<>(mPermissionUsages.getUsages());
        if (appPermissionUsages.isEmpty() || getActivity() == null) {
            return;
        }
        Context context = getActivity();

        PreferenceScreen screen = getPreferenceScreen();
        if (screen == null) {
            screen = getPreferenceManager().createPreferenceScreen(context);
            setPreferenceScreen(screen);
        }
        screen.removeAll();

        mHasSystemApps = false;

        final TimeFilterItem timeFilterItem = mFilterTimes.get(mFilterTimeIndex);
        long curTime = System.currentTimeMillis();
        long startTime = (timeFilterItem == null ? 0 : (curTime - timeFilterItem.getTime()));

        List<Pair<AppPermissionUsage, GroupUsage>> usages = new ArrayList<>();
        mGroupAppCounts = new ArrayMap<>();
        ArrayList<PermissionApp> permApps = new ArrayList<>();
        int numApps = appPermissionUsages.size();
        for (int appNum = 0; appNum < numApps; appNum++) {
            AppPermissionUsage appUsage = appPermissionUsages.get(appNum);
            boolean used = false;
            List<GroupUsage> appGroups = appUsage.getGroupUsages();
            int numGroups = appGroups.size();
            for (int groupNum = 0; groupNum < numGroups; groupNum++) {
                GroupUsage groupUsage = appGroups.get(groupNum);

                if (groupUsage.getAccessCount() <= 0
                        || groupUsage.getLastAccessTime() < startTime) {
                    continue;
                }
                final boolean isSystemApp = !Utils.isGroupOrBgGroupUserSensitive(
                        groupUsage.getGroup());
                if (!mHasSystemApps) {
                    if (isSystemApp) {
                        mHasSystemApps = true;
                        getActivity().invalidateOptionsMenu();
                    }
                }
                if (isSystemApp && !mShowSystem) {
                    continue;
                }

                usages.add(Pair.create(appUsage, appGroups.get(groupNum)));
                used = true;

                addGroupUser(groupUsage.getGroup().getName());
            }
            if (used) {
                permApps.add(appUsage.getApp());
                addGroupUser(null);
            }
        }

        // Update header.
        if (mFilterGroup == null) {
            hideHeader();
        } else {
            AppPermissionGroup group = getGroup(mFilterGroup);
            if (group != null) {
                setHeader(Utils.applyTint(context, context.getDrawable(group.getIconResId()),
                        android.R.attr.colorControlNormal),
                        context.getString(R.string.app_permission_usage_filter_label,
                                group.getLabel()), null);
                setSummary(context.getString(R.string.app_permission_usage_remove_filter), v -> {
                    mFilterGroup = null;
                    // We already loaded all data, so don't reload
                    updateUI();
                });
            }
        }

        // Add the preference header.
        PreferenceCategory category = new PreferenceCategory(context);
        screen.addPreference(category);
        if (timeFilterItem != null) {
            category.setTitle(timeFilterItem.getListTitleRes());
        }

        // Sort the apps.
        if (mSort == SORT_RECENT) {
            usages.sort(PermissionUsageFragment::compareAccessRecency);
        } else if (mSort == SORT_RECENT_APPS) {
            usages.sort(PermissionUsageFragment::compareAccessAppRecency);
        } else {
            Log.w(LOG_TAG, "Unexpected sort option: " + mSort);
        }

        usages.removeIf((Pair<AppPermissionUsage, GroupUsage> usage) -> mFilterGroup != null
                && !mFilterGroup.equals(usage.second.getGroup().getName()));

        // If there are no entries, don't show anything.
        if (permApps.isEmpty()) {
            screen.removeAll();
        }

        new PermissionApps.AppDataLoader(context, () -> {
            ExpandablePreferenceGroup parent = null;
            AppPermissionUsage lastAppPermissionUsage = null;
            String lastAccessTimeString = null;
            List<CharSequence> groups = new ArrayList<>();

            final int numUsages = usages.size();
            for (int usageNum = 0; usageNum < numUsages; usageNum++) {
                final Pair<AppPermissionUsage, GroupUsage> usage = usages.get(usageNum);
                AppPermissionUsage appPermissionUsage = usage.first;
                GroupUsage groupUsage = usage.second;

                String accessTimeString = Utils.getAbsoluteLastUsageString(context, groupUsage);

                if (lastAppPermissionUsage != appPermissionUsage || (mSort == SORT_RECENT
                        && !accessTimeString.equals(lastAccessTimeString))) {
                    setPermissionSummary(parent, groups);
                    // Add a "parent" entry for the app that will expand to the individual entries.
                    parent = createExpandablePreferenceGroup(context, appPermissionUsage,
                            mSort == SORT_RECENT ? accessTimeString : null);
                    category.addPreference(parent);
                    lastAppPermissionUsage = appPermissionUsage;
                    groups = new ArrayList<>();
                }

                parent.addPreference(createPermissionUsagePreference(context, appPermissionUsage,
                        groupUsage, accessTimeString));
                groups.add(groupUsage.getGroup().getLabel());
                lastAccessTimeString = accessTimeString;
            }

            setPermissionSummary(parent, groups);

            setLoading(false, true);
            mFinishedInitialLoad = true;
            setProgressBarVisible(false);
        }).execute(permApps.toArray(new PermissionApps.PermissionApp[permApps.size()]));
    }

    private void addGroupUser(String app) {
        Integer count = mGroupAppCounts.get(app);
        if (count == null) {
            mGroupAppCounts.put(app, 1);
        } else {
            mGroupAppCounts.put(app, count + 1);
        }
    }

    private void setPermissionSummary(@NonNull ExpandablePreferenceGroup pref,
            @NonNull List<CharSequence> groups) {
        if (pref == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        int numGroups = groups.size();
        for (int i = 0; i < numGroups; i++) {
            sb.append(groups.get(i));
            if (i < numGroups - 1) {
                sb.append(getString(R.string.item_separator));
            }
        }
        pref.setSummary(sb.toString());
    }

    /**
     * Reloads the data to show.
     */
    private void reloadData() {
        final TimeFilterItem timeFilterItem = mFilterTimes.get(mFilterTimeIndex);
        final long filterTimeBeginMillis = Math.max(System.currentTimeMillis()
                - timeFilterItem.getTime(), Instant.EPOCH.toEpochMilli());
        mPermissionUsages.load(null /*filterPackageName*/, null /*filterPermissionGroups*/,
                filterTimeBeginMillis, Long.MAX_VALUE, PermissionUsages.USAGE_FLAG_LAST
                        | PermissionUsages.USAGE_FLAG_HISTORICAL, getActivity().getLoaderManager(),
                false /*getUiInfo*/, false /*getNonPlatformPermissions*/, this /*callback*/,
                false /*sync*/);
        if (mFinishedInitialLoad) {
            setProgressBarVisible(true);
        }
    }

    /**
     * Create an expandable preference group that can hold children.
     *
     * @param context the context
     * @param appPermissionUsage the permission usage for an app
     *
     * @return the expandable preference group.
     */
    private ExpandablePreferenceGroup createExpandablePreferenceGroup(@NonNull Context context,
            @NonNull AppPermissionUsage appPermissionUsage, @Nullable String summaryString) {
        ExpandablePreferenceGroup preference = new ExpandablePreferenceGroup(context);
        preference.setTitle(appPermissionUsage.getApp().getLabel());
        preference.setIcon(appPermissionUsage.getApp().getIcon());
        if (summaryString != null) {
            preference.setSummary(summaryString);
        }
        return preference;
    }

    /**
     * Create a preference representing an app's use of a permission
     *
     * @param context the context
     * @param appPermissionUsage the permission usage for the app
     * @param groupUsage the permission item to add
     * @param accessTimeStr the string representing the access time
     *
     * @return the Preference
     */
    private PermissionControlPreference createPermissionUsagePreference(@NonNull Context context,
            @NonNull AppPermissionUsage appPermissionUsage,
            @NonNull GroupUsage groupUsage, @NonNull String accessTimeStr) {
        final PermissionControlPreference pref = new PermissionControlPreference(context,
                groupUsage.getGroup());

        final AppPermissionGroup group = groupUsage.getGroup();
        pref.setTitle(group.getLabel());
        pref.setUsageSummary(groupUsage, accessTimeStr);
        pref.setTitleIcons(Collections.singletonList(group.getIconResId()));
        pref.setKey(group.getApp().packageName + "," + group.getName());
        pref.useSmallerIcon();
        pref.setRightIcon(context.getDrawable(R.drawable.ic_settings));
        return pref;
    }

    /**
     * Compare two usages by whichever app was used most recently.  If the two represent the same
     * app, sort by which group was used most recently.
     *
     * Can be used as a {@link java.util.Comparator}.
     *
     * @param x a usage.
     * @param y a usage.
     *
     * @return see {@link java.util.Comparator#compare(Object, Object)}.
     */
    private static int compareAccessAppRecency(@NonNull Pair<AppPermissionUsage, GroupUsage> x,
            @NonNull Pair<AppPermissionUsage, GroupUsage> y) {
        if (x.first.getApp().getKey().equals(y.first.getApp().getKey())) {
            return compareAccessTime(x.second, y.second);
        }
        return compareAccessTime(x.first, y.first);
    }

    /**
     * Compare two usages by their access time.
     *
     * Can be used as a {@link java.util.Comparator}.
     *
     * @param x a usage.
     * @param y a usage.
     *
     * @return see {@link java.util.Comparator#compare(Object, Object)}.
     */
    private static int compareAccessTime(@NonNull Pair<AppPermissionUsage, GroupUsage> x,
            @NonNull Pair<AppPermissionUsage, GroupUsage> y) {
        return compareAccessTime(x.second, y.second);
    }

    /**
     * Compare two usages by their access time.
     *
     * Can be used as a {@link java.util.Comparator}.
     *
     * @param x a usage.
     * @param y a usage.
     *
     * @return see {@link java.util.Comparator#compare(Object, Object)}.
     */
    private static int compareAccessTime(@NonNull GroupUsage x, @NonNull GroupUsage y) {
        final int timeDiff = compareLong(x.getLastAccessTime(), y.getLastAccessTime());
        if (timeDiff != 0) {
            return timeDiff;
        }
        // Make sure we lose no data if same
        return x.hashCode() - y.hashCode();
    }

    /**
     * Compare two AppPermissionUsage by their access time.
     *
     * Can be used as a {@link java.util.Comparator}.
     *
     * @param x an AppPermissionUsage.
     * @param y an AppPermissionUsage.
     *
     * @return see {@link java.util.Comparator#compare(Object, Object)}.
     */
    private static int compareAccessTime(@NonNull AppPermissionUsage x,
            @NonNull AppPermissionUsage y) {
        final int timeDiff = compareLong(x.getLastAccessTime(), y.getLastAccessTime());
        if (timeDiff != 0) {
            return timeDiff;
        }
        // Make sure we lose no data if same
        return x.hashCode() - y.hashCode();
    }

    /**
     * Compare two longs.
     *
     * Can be used as a {@link java.util.Comparator}.
     *
     * @param x the first long.
     * @param y the second long.
     *
     * @return see {@link java.util.Comparator#compare(Object, Object)}.
     */
    private static int compareLong(long x, long y) {
        if (x > y) {
            return -1;
        } else if (x < y) {
            return 1;
        }
        return 0;
    }

    /**
     * Compare two usages by recency of access.
     *
     * Can be used as a {@link java.util.Comparator}.
     *
     * @param x a usage.
     * @param y a usage.
     *
     * @return see {@link java.util.Comparator#compare(Object, Object)}.
     */
    private static int compareAccessRecency(@NonNull Pair<AppPermissionUsage, GroupUsage> x,
            @NonNull Pair<AppPermissionUsage, GroupUsage> y) {
        final int timeDiff = compareAccessTime(x, y);
        if (timeDiff != 0) {
            return timeDiff;
        }
        // Make sure we lose no data if same
        return x.hashCode() - y.hashCode();
    }

    /**
     * Get the permission groups declared by the OS.
     *
     * @return a list of the permission groups declared by the OS.
     */
    private @NonNull List<AppPermissionGroup> getOSPermissionGroups() {
        final List<AppPermissionGroup> groups = new ArrayList<>();
        final Set<String> seenGroups = new ArraySet<>();
        final List<AppPermissionUsage> appUsages = mPermissionUsages.getUsages();
        final int numGroups = appUsages.size();
        for (int i = 0; i < numGroups; i++) {
            final AppPermissionUsage appUsage = appUsages.get(i);
            final List<GroupUsage> groupUsages = appUsage.getGroupUsages();
            final int groupUsageCount = groupUsages.size();
            for (int j = 0; j < groupUsageCount; j++) {
                final GroupUsage groupUsage = groupUsages.get(j);
                if (Utils.isModernPermissionGroup(groupUsage.getGroup().getName())) {
                    if (seenGroups.add(groupUsage.getGroup().getName())) {
                        groups.add(groupUsage.getGroup());
                    }
                }
            }
        }
        return groups;
    }

    /**
     * Get an AppPermissionGroup that represents the given permission group (and an arbitrary app).
     *
     * @param groupName The name of the permission group.
     *
     * @return an AppPermissionGroup rerepsenting the given permission group or null if no such
     * AppPermissionGroup is found.
     */
    private @Nullable AppPermissionGroup getGroup(@NonNull String groupName) {
        List<AppPermissionGroup> groups = getOSPermissionGroups();
        int numGroups = groups.size();
        for (int i = 0; i < numGroups; i++) {
            if (groups.get(i).getName().equals(groupName)) {
                return groups.get(i);
            }
        }
        return null;
    }

    /**
     * Show a dialog that allows selecting a permission group by which to filter the entries.
     */
    private void showPermissionFilterDialog() {
        Context context = getPreferenceManager().getContext();

        // Get the permission labels.
        List<AppPermissionGroup> groups = getOSPermissionGroups();
        groups.sort(
                (x, y) -> mCollator.compare(x.getLabel().toString(), y.getLabel().toString()));

        // Create the dialog entries.
        String[] groupNames = new String[groups.size() + 1];
        CharSequence[] groupLabels = new CharSequence[groupNames.length];
        int[] groupAccessCounts = new int[groupNames.length];
        groupNames[0] = null;
        groupLabels[0] = context.getString(R.string.permission_usage_any_permission);
        groupAccessCounts[0] = mGroupAppCounts.get(null);
        int selection = 0;
        int numGroups = groups.size();
        for (int i = 0; i < numGroups; i++) {
            AppPermissionGroup group = groups.get(i);
            groupNames[i + 1] = group.getName();
            groupLabels[i + 1] = group.getLabel();
            Integer appCount = mGroupAppCounts.get(group.getName());
            if (appCount == null) {
                appCount = 0;
            }
            groupAccessCounts[i + 1] = appCount;
            if (group.getName().equals(mFilterGroup)) {
                selection = i + 1;
            }
        }

        // Create the dialog
        Bundle args = new Bundle();
        args.putCharSequence(PermissionsFilterDialog.TITLE,
                context.getString(R.string.filter_by_title));
        args.putCharSequenceArray(PermissionsFilterDialog.ELEMS, groupLabels);
        args.putInt(PermissionsFilterDialog.SELECTION, selection);
        args.putStringArray(PermissionsFilterDialog.GROUPS, groupNames);
        args.putIntArray(PermissionsFilterDialog.ACCESS_COUNTS, groupAccessCounts);
        PermissionsFilterDialog chooserDialog = new PermissionsFilterDialog();
        chooserDialog.setArguments(args);
        chooserDialog.setTargetFragment(this, 0);
        chooserDialog.show(getFragmentManager().beginTransaction(),
                PermissionsFilterDialog.class.getName());
    }

    /**
     * Callback when the user selects a permission group by which to filter.
     *
     * @param selectedGroup The PermissionGroup to use to filter entries, or null if we should show
     *                      all entries.
     */
    private void onPermissionGroupSelected(@Nullable String selectedGroup) {
        mFilterGroup = selectedGroup;
        // We already loaded all data, so don't reload
        updateUI();
    }

    /**
     * A dialog that allows the user to select a permission group by which to filter entries.
     *
     * @see #showPermissionFilterDialog()
     */
    public static class PermissionsFilterDialog extends DialogFragment {
        private static final String TITLE = PermissionsFilterDialog.class.getName() + ".arg.title";
        private static final String ELEMS = PermissionsFilterDialog.class.getName() + ".arg.elems";
        private static final String SELECTION = PermissionsFilterDialog.class.getName()
                + ".arg.selection";
        private static final String GROUPS = PermissionsFilterDialog.class.getName()
                + ".arg.groups";
        private static final String ACCESS_COUNTS = PermissionsFilterDialog.class.getName()
                + ".arg.access_counts";

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            AlertDialog.Builder b = new AlertDialog.Builder(getActivity())
                    .setView(createDialogView());

            return b.create();
        }

        private @NonNull View createDialogView() {
            PermissionUsageFragment fragment = (PermissionUsageFragment) getTargetFragment();
            CharSequence[] elems = getArguments().getCharSequenceArray(ELEMS);
            String[] groups = getArguments().getStringArray(GROUPS);
            int[] accessCounts = getArguments().getIntArray(ACCESS_COUNTS);
            int selectedIndex = getArguments().getInt(SELECTION);

            LayoutInflater layoutInflater = LayoutInflater.from(fragment.getActivity());
            View view = layoutInflater.inflate(R.layout.permission_filter_dialog, null);
            ViewGroup itemsListView = view.requireViewById(R.id.items_container);

            ((TextView) view.requireViewById(R.id.title)).setText(
                    getArguments().getCharSequence(TITLE));

            for (int i = 0; i < elems.length; i++) {
                String groupName = groups[i];
                View itemView = layoutInflater.inflate(R.layout.permission_filter_dialog_item,
                        itemsListView, false);

                ((TextView) itemView.requireViewById(R.id.title)).setText(elems[i]);
                ((TextView) itemView.requireViewById(R.id.summary)).setText(
                        getActivity().getResources().getQuantityString(
                                R.plurals.permission_usage_permission_filter_subtitle,
                                accessCounts[i], accessCounts[i]));

                itemView.setOnClickListener((v) -> {
                    dismissAllowingStateLoss();
                    fragment.onPermissionGroupSelected(groupName);
                });

                RadioButton radioButton = itemView.requireViewById(R.id.radio_button);
                radioButton.setChecked(i == selectedIndex);
                radioButton.setOnClickListener((v) -> {
                    dismissAllowingStateLoss();
                    fragment.onPermissionGroupSelected(groupName);
                });

                itemsListView.addView(itemView);
            }

            return view;
        }
    }

    private void showTimeFilterDialog() {
        Context context = getPreferenceManager().getContext();

        CharSequence[] labels = new CharSequence[mFilterTimes.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = mFilterTimes.get(i).getLabel();
        }

        // Create the dialog
        Bundle args = new Bundle();
        args.putCharSequence(TimeFilterDialog.TITLE,
                context.getString(R.string.filter_by_title));
        args.putCharSequenceArray(TimeFilterDialog.ELEMS, labels);
        args.putInt(TimeFilterDialog.SELECTION, mFilterTimeIndex);
        TimeFilterDialog chooserDialog = new TimeFilterDialog();
        chooserDialog.setArguments(args);
        chooserDialog.setTargetFragment(this, 0);
        chooserDialog.show(getFragmentManager().beginTransaction(),
                TimeFilterDialog.class.getName());
    }

    /**
     * Callback when the user selects a time by which to filter.
     *
     * @param selectedIndex The index of the dialog option selected by the user.
     */
    private void onTimeSelected(int selectedIndex) {
        mFilterTimeIndex = selectedIndex;
        reloadData();
    }

    /**
     * A dialog that allows the user to select a time by which to filter entries.
     *
     * @see #showTimeFilterDialog()
     */
    public static class TimeFilterDialog extends DialogFragment {
        private static final String TITLE = TimeFilterDialog.class.getName() + ".arg.title";
        private static final String ELEMS = TimeFilterDialog.class.getName() + ".arg.elems";
        private static final String SELECTION = TimeFilterDialog.class.getName() + ".arg.selection";

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            PermissionUsageFragment fragment = (PermissionUsageFragment) getTargetFragment();
            CharSequence[] elems = getArguments().getCharSequenceArray(ELEMS);
            AlertDialog.Builder b = new AlertDialog.Builder(getActivity())
                    .setTitle(getArguments().getCharSequence(TITLE))
                    .setSingleChoiceItems(elems, getArguments().getInt(SELECTION),
                            (dialog, which) -> {
                                dismissAllowingStateLoss();
                                fragment.onTimeSelected(which);
                            }
                    );

            return b.create();
        }
    }

    /**
     * A class representing a given time, e.g., "in the last hour".
     */
    private static class TimeFilterItem {
        private final long mTime;
        private final @NonNull String mLabel;
        private final @StringRes int mListTitleRes;

        TimeFilterItem(long time, @NonNull String label, @StringRes int listTitleRes) {
            mTime = time;
            mLabel = label;
            mListTitleRes = listTitleRes;
        }

        /**
         * Get the time represented by this object in milliseconds.
         *
         * @return the time represented by this object.
         */
        public long getTime() {
            return mTime;
        }

        public @NonNull String getLabel() {
            return mLabel;
        }

        public @StringRes int getListTitleRes() {
            return mListTitleRes;
        }
    }
}
