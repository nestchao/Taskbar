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

package com.farmerbb.taskbar.adapter;

import android.content.Context;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SectionIndexer;
import android.widget.TextView;

import com.farmerbb.taskbar.R;
import com.farmerbb.taskbar.util.BlacklistEntry;
import com.farmerbb.taskbar.util.IconCache;
import com.farmerbb.taskbar.util.SidebarApps;
import com.farmerbb.taskbar.util.U;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.farmerbb.taskbar.util.Constants.*;

public class SidebarAppsAdapter extends ArrayAdapter<BlacklistEntry> implements SectionIndexer {

    private final Map<String, LauncherActivityInfo> launcherInfoMap;
    private final HashMap<String, Integer> sectionPositions = new HashMap<>();
    private final String[] sections;

    public SidebarAppsAdapter(Context context, int layout, List<BlacklistEntry> list) {
        super(context, layout, list);

        this.launcherInfoMap = new HashMap<>();

        for(BlacklistEntry entry : list) {
            String key = normalizePackageName(entry.getPackageName());
            if(key != null && !launcherInfoMap.containsKey(key))
                launcherInfoMap.put(key, null);
        }

        int count = getCount();
        String lastSection = "";
        for(int i = 0; i < count; i++) {
            BlacklistEntry entry = getItem(i);
            if(entry == null) continue;
            String label = entry.getLabel();
            String section = label.length() > 0
                    ? label.substring(0, 1).toUpperCase()
                    : "";
            if(section.length() > 0 && section.charAt(0) >= 'A' && section.charAt(0) <= 'Z') {
                if(!lastSection.equals(section)) {
                    sectionPositions.put(section, i);
                    lastSection = section;
                }
            } else if(!lastSection.equals("#")) {
                sectionPositions.put("#", i);
                lastSection = "#";
            }
        }

        sections = sectionPositions.keySet().toArray(new String[0]);
        java.util.Arrays.sort(sections, (a, b) -> {
            if("#".equals(a)) return 1;
            if("#".equals(b)) return -1;
            return a.compareTo(b);
        });
    }

    public void putLauncherInfo(String key, LauncherActivityInfo info) {
        if(launcherInfoMap.containsKey(key))
            launcherInfoMap.put(key, info);
    }

    public Map<String, LauncherActivityInfo> getLauncherInfoMap() {
        return launcherInfoMap;
    }

    private String normalizePackageName(String componentName) {
        String result = componentName.contains(":") ? componentName.split(":")[0] : componentName;
        return result.contains("/") ? result.split("/")[0] : result;
    }

    @Override
    public @NonNull View getView(int position, View convertView, final @NonNull ViewGroup parent) {
        if(convertView == null)
            convertView = LayoutInflater.from(getContext()).inflate(R.layout.tb_row_sidebar_apps, parent, false);

        final SidebarApps sidebarApps = SidebarApps.getInstance(getContext());
        final BlacklistEntry entry = getItem(position);
        assert entry != null;

        final String componentName = entry.getPackageName();
        final String componentNameAlt = componentName.contains(":") ? componentName.split(":")[0] : componentName;
        final String componentNameAlt2 = componentNameAlt.contains("/") ? componentNameAlt.split("/")[1] : componentNameAlt;

        ImageView iconView = convertView.findViewById(R.id.icon);
        TextView textView = convertView.findViewById(R.id.name);
        textView.setText(entry.getLabel());

        String packageName = normalizePackageName(componentName);
        LauncherActivityInfo info = launcherInfoMap.get(packageName);
        if(info != null) {
            PackageManager pm = getContext().getPackageManager();
            Drawable icon = IconCache.getInstance(getContext()).getIcon(getContext(), pm, info);
            iconView.setImageDrawable(icon);
        } else {
            iconView.setImageDrawable(null);
        }

        final CheckBox checkBox = convertView.findViewById(R.id.checkbox);
        checkBox.setChecked(sidebarApps.isSidebarApp(componentName)
                || sidebarApps.isSidebarApp(componentNameAlt)
                || sidebarApps.isSidebarApp(componentNameAlt2));

        LinearLayout layout = convertView.findViewById(R.id.entry);
        layout.setOnClickListener(view -> {
            if(sidebarApps.isSidebarApp(componentName)) {
                sidebarApps.removeSidebarApp(getContext(), componentName);
                checkBox.setChecked(false);
            } else if(sidebarApps.isSidebarApp(componentNameAlt)) {
                sidebarApps.removeSidebarApp(getContext(), componentNameAlt);
                checkBox.setChecked(false);
            } else if(sidebarApps.isSidebarApp(componentNameAlt2)) {
                sidebarApps.removeSidebarApp(getContext(), componentNameAlt2);
                checkBox.setChecked(false);
            } else {
                sidebarApps.addSidebarApp(getContext(), entry);
                checkBox.setChecked(true);
            }

            U.sendBroadcast(getContext(), ACTION_UPDATE_SWITCH);
        });

        return convertView;
    }

    @Override
    public Object[] getSections() {
        return sections;
    }

    @Override
    public int getPositionForSection(int sectionIndex) {
        if(sectionIndex < 0 || sectionIndex >= sections.length)
            return 0;

        String section = sections[sectionIndex];
        Integer pos = sectionPositions.get(section);
        return pos != null ? pos : 0;
    }

    @Override
    public int getSectionForPosition(int position) {
        if(position < 0 || sections.length == 0)
            return 0;

        BlacklistEntry entry = getItem(position);
        if(entry == null) return 0;

        String label = entry.getLabel();
        String section = label.length() > 0
                ? label.substring(0, 1).toUpperCase()
                : "#";
        if(section.charAt(0) < 'A' || section.charAt(0) > 'Z')
            section = "#";

        for(int i = 0; i < sections.length; i++) {
            if(sections[i].equals(section))
                return i;
        }

        return 0;
    }
}
