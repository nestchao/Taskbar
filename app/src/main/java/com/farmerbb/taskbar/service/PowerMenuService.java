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

package com.farmerbb.taskbar.service;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

import com.farmerbb.taskbar.R;
import com.farmerbb.taskbar.util.U;

import java.io.File;

import static com.farmerbb.taskbar.util.Constants.*;

public class PowerMenuService extends AccessibilityService {

    private long lastWatchdogCheck = 0;

    private final BroadcastReceiver powerMenuReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if(!performGlobalAction(intent.getIntExtra(EXTRA_ACTION, -1))) {
                U.showToast(PowerMenuService.this, R.string.tb_lock_device_not_supported);
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        runWatchdogCheck();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastWatchdogCheck > 10000) {
            lastWatchdogCheck = currentTime;
            runWatchdogCheck();
        }
    }

    @Override
    public void onInterrupt() {}

    private void runWatchdogCheck() {
        Context context = getApplicationContext();
        try {
            File stateFile = new File(context.getFilesDir(), "taskbar_active");
            if (stateFile.exists()) {
                if (!U.isServiceRunning(context, NotificationService.class)) {
                    Intent startIntent = new Intent(context, NotificationService.class);
                    startIntent.putExtra(EXTRA_START_SERVICES, true);
                    U.startForegroundService(context, startIntent);
                }
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onCreate() {
        super.onCreate();

        U.registerReceiver(this, powerMenuReceiver, ACTION_ACCESSIBILITY_ACTION);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        U.unregisterReceiver(this, powerMenuReceiver);
    }
}