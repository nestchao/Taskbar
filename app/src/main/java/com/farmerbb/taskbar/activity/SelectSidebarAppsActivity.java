/* Copyright 2016 Braden Farmer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.farmerbb.taskbar.activity;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;

import androidx.appcompat.app.AppCompatActivity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;

import com.farmerbb.taskbar.R;
import com.farmerbb.taskbar.adapter.SidebarAppsAdapter;
import com.farmerbb.taskbar.util.BlacklistEntry;
import com.farmerbb.taskbar.util.U;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.farmerbb.taskbar.util.Constants.*;

public class SelectSidebarAppsActivity extends AppCompatActivity {

    private AppListGenerator appListGenerator;
    private ProgressBar progressBar;
    private LinearLayout layout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        boolean noShadow = getIntent().hasExtra("no_shadow");

        if(savedInstanceState == null) {
            setContentView(R.layout.tb_activity_sidebar_apps);
            setFinishOnTouchOutside(false);
            setTitle(R.string.tb_sidebar_apps);

            if(noShadow) {
                WindowManager.LayoutParams params = getWindow().getAttributes();
                params.dimAmount = 0;
                getWindow().setAttributes(params);

                if(U.isChromeOs(this) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1)
                    getWindow().setElevation(0);
            }

            SharedPreferences pref = U.getSharedPreferences(this);
            if(!pref.getBoolean(PREF_COLLAPSED, false)) {
                U.sendBroadcast(this, ACTION_HIDE_TASKBAR);
            }

            progressBar = findViewById(R.id.progress_bar);
            layout = findViewById(R.id.sidebar_apps_layout);
            appListGenerator = new AppListGenerator();
            appListGenerator.execute();
        }
    }

    @Override
    public void finish() {
        if(appListGenerator != null && appListGenerator.getStatus() == AsyncTask.Status.RUNNING)
            appListGenerator.cancel(true);

        super.finish();
    }

    private final class AppListGenerator extends AsyncTask<Void, Void, SidebarAppsAdapter> {
        @SuppressWarnings("Convert2streamapi")
        @Override
        protected SidebarAppsAdapter doInBackground(Void... params) {
            UserManager userManager = (UserManager) getSystemService(Context.USER_SERVICE);
            LauncherApps launcherApps = (LauncherApps) getSystemService(Context.LAUNCHER_APPS_SERVICE);

            final List<UserHandle> userHandles = userManager.getUserProfiles();
            final List<LauncherActivityInfo> list = new ArrayList<>();

            for(UserHandle handle : userHandles) {
                list.addAll(launcherApps.getActivityList(null, handle));
            }

            // Remove any uninstalled apps from the sidebar apps list
            com.farmerbb.taskbar.util.SidebarApps sidebarApps =
                    com.farmerbb.taskbar.util.SidebarApps.getInstance(SelectSidebarAppsActivity.this);
            List<String> sidebarAppsList = new ArrayList<>();
            List<String> installedApps = new ArrayList<>();

            for(BlacklistEntry entry : sidebarApps.getSidebarApps()) {
                sidebarAppsList.add(entry.getPackageName());
            }

            for(LauncherActivityInfo appInfo : list) {
                installedApps.add(appInfo.getApplicationInfo().packageName + "/" + appInfo.getName()
                        + ":" + userManager.getSerialNumberForUser(appInfo.getUser()));
                installedApps.add(appInfo.getApplicationInfo().packageName + "/" + appInfo.getName());
                installedApps.add(appInfo.getName());
            }

            for(String packageName : sidebarAppsList) {
                if(!installedApps.contains(packageName))
                    sidebarApps.removeSidebarApp(SelectSidebarAppsActivity.this, packageName);
            }

            Collections.sort(list, (ai1, ai2) -> {
                String label1;
                String label2;

                try {
                    label1 = ai1.getLabel().toString();
                    label2 = ai2.getLabel().toString();
                } catch (OutOfMemoryError e) {
                    System.gc();

                    label1 = ai1.getApplicationInfo().packageName;
                    label2 = ai2.getApplicationInfo().packageName;
                }

                return Collator.getInstance().compare(label1, label2);
            });

            final List<BlacklistEntry> entries = new ArrayList<>();
            for(LauncherActivityInfo appInfo : list) {
                String label;

                try {
                    label = appInfo.getLabel().toString();
                } catch (OutOfMemoryError e) {
                    System.gc();

                    label = appInfo.getApplicationInfo().packageName;
                }

                entries.add(new BlacklistEntry(
                        appInfo.getApplicationInfo().packageName + "/" + appInfo.getName()
                                + ":" + userManager.getSerialNumberForUser(appInfo.getUser()),
                        label));
            }

            return new SidebarAppsAdapter(SelectSidebarAppsActivity.this,
                    R.layout.tb_row_blacklist, entries);
        }

        @Override
        protected void onPostExecute(SidebarAppsAdapter adapter) {
            ListView appList = findViewById(R.id.sidebar_apps_list);
            appList.setAdapter(adapter);

            layout.setVisibility(View.VISIBLE);
            progressBar.setVisibility(View.GONE);
            setFinishOnTouchOutside(true);
        }
    }
}
