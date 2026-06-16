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

package com.farmerbb.taskbar.util;

import android.content.Context;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class SidebarApps implements Serializable {
    static final long serialVersionUID = 3246181272918152265L;

    private List<BlacklistEntry> sidebarApps = new ArrayList<>();

    private static SidebarApps theInstance;

    private SidebarApps() {}

    public List<BlacklistEntry> getSidebarApps() {
        return sidebarApps;
    }

    public void addSidebarApp(Context context, BlacklistEntry entry) {
        sidebarApps.add(entry);
        save(context);
    }

    public void removeSidebarApp(Context context, String packageName) {
        int number = -1;

        for(int i = 0; i < sidebarApps.size(); i++) {
            if(sidebarApps.get(i).getPackageName().equals(packageName)) {
                number = i;
                break;
            }
        }

        if(number != -1) sidebarApps.remove(number);

        save(context);
    }

    public boolean isSidebarApp(String packageName) {
        for(int i = 0; i < sidebarApps.size(); i++) {
            if(sidebarApps.get(i).getPackageName().equals(packageName))
                return true;
        }

        return false;
    }

    public void clear(Context context) {
        sidebarApps.clear();
        save(context);
    }

    private boolean save(Context context) {
        try {
            FileOutputStream fileOutputStream = context.openFileOutput("SidebarApps", Context.MODE_PRIVATE);
            ObjectOutputStream objectOutputStream = new ObjectOutputStream(fileOutputStream);

            objectOutputStream.writeObject(this);

            objectOutputStream.close();
            fileOutputStream.close();

            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static SidebarApps getInstance(Context context) {
        if(theInstance == null)
            try {
                FileInputStream fileInputStream = context.openFileInput("SidebarApps");
                ObjectInputStream objectInputStream = new ObjectInputStream(fileInputStream);

                theInstance = (SidebarApps) objectInputStream.readObject();

                objectInputStream.close();
                fileInputStream.close();
            } catch (IOException | ClassNotFoundException e) {
                theInstance = new SidebarApps();
            }

        return theInstance;
    }
}
