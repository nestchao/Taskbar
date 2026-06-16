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
import androidx.annotation.NonNull;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.farmerbb.taskbar.R;
import com.farmerbb.taskbar.util.BlacklistEntry;
import com.farmerbb.taskbar.util.SidebarApps;
import com.farmerbb.taskbar.util.U;

import java.util.List;

import static com.farmerbb.taskbar.util.Constants.*;

public class SidebarAppsAdapter extends ArrayAdapter<BlacklistEntry> {

    public SidebarAppsAdapter(Context context, int layout, List<BlacklistEntry> list) {
        super(context, layout, list);
    }

    @Override
    public @NonNull View getView(int position, View convertView, final @NonNull ViewGroup parent) {
        if(convertView == null)
            convertView = LayoutInflater.from(getContext()).inflate(R.layout.tb_row_blacklist, parent, false);

        final SidebarApps sidebarApps = SidebarApps.getInstance(getContext());
        final BlacklistEntry entry = getItem(position);
        assert entry != null;

        final String componentName = entry.getPackageName();
        final String componentNameAlt = componentName.contains(":") ? componentName.split(":")[0] : componentName;
        final String componentNameAlt2 = componentNameAlt.contains("/") ? componentNameAlt.split("/")[1] : componentNameAlt;

        TextView textView = convertView.findViewById(R.id.name);
        textView.setText(entry.getLabel());

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
}
