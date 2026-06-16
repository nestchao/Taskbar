# Taskbar 浮窗功能实现原理 — 完整技术报告

## 一、总体架构

浮窗功能由 4 个组件协作完成：

| 组件 | 文件 | 作用 |
|------|------|------|
| **FreeformHackHelper** | `helper/FreeformHackHelper.java` | 单例，3 个布尔值追踪当前状态 |
| **InvisibleActivityFreeform** | `activity/InvisibleActivityFreeform.java` | 不可见 Activity，创建自由窗口工作区 |
| **U.java** (工具方法) | `util/U.java` | 所有启动逻辑、窗口模式设置、位置计算 |
| **AndroidManifest.xml** | `app/src/playstore/AndroidManifest.xml` | 声明 Activity 属性和系统 feature |

---

## 二、核心机制：Freeform Hack（自由窗口技巧）

这是整个功能最关键的部分。Android 原生需要在设置中开启 `enable_freeform_support = 1` 后才能使用自由窗口。但即使开启了这个全局开关，系统也需要处于**多窗口模式**中才能正常工作。

### 2.1 什么是 Freeform Hack？

**本质上：启动一个看不见的 Activity 来欺骗系统，让它认为自己处于自由窗口模式。**

这个 Activity（`InvisibleActivityFreeform`）被：
- 定位在**屏幕右下角外 1px**（用户完全看不见）
- 设置为 `singleInstance` 启动模式（全局只有一个实例）
- 声明为 `excludeFromRecents`（不在最近任务中显示）
- 使用 `FLAG_ACTIVITY_LAUNCH_ADJACENT` 强制进入多窗口模式

### 2.2 启动 Hack（`U.java:412-423`）

```java
@TargetApi(Build.VERSION_CODES.N)
public static void startFreeformHack(Context context, boolean checkMultiWindow) {
    Intent freeformHackIntent = new Intent(context, InvisibleActivityFreeform.class);
    freeformHackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
            | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT   // ← 关键：强制分屏/多窗口
            | Intent.FLAG_ACTIVITY_NO_ANIMATION);

    if(checkMultiWindow)
        freeformHackIntent.putExtra("check_multiwindow", true);

    if(canDrawOverlays(context))
        startActivityLowerRight(context, freeformHackIntent);
}
```

**`FLAG_ACTIVITY_LAUNCH_ADJACENT`** 是灵魂所在。这个 flag 告诉系统"我想和旁边的 Activity 一起显示"，系统因此会进入多窗口模式。

### 2.3 定位在屏幕外（`U.java:626-637`）

```java
public static void startActivityLowerRight(Context context, Intent intent) {
    DisplayInfo display = getDisplayInfo(context);
    context.startActivity(intent,
            getActivityOptionsBundle(context, ApplicationType.FREEFORM_HACK, null,
                    display.width,          // left   = 屏幕右边缘
                    display.height,         // top    = 屏幕下边缘
                    display.width + 1,      // right  = 屏幕外 1px
                    display.height + 1      // bottom = 屏幕外 1px
            ));
}
```

窗口设置成 `Rect(width, height, width+1, height+1)` — 在右下角外只有 1px 大小，完全不可见。

### 2.4 InvisibleActivityFreeform 生命周期

#### `onCreate()`（第 73-138 行）

```
onCreate()
  ├── 如果 hack 已激活 → 直接 finish()
  ├── 如果 check_multiwindow 但不在多窗口模式 → 直接 finish()
  ├── 如果设备原生支持自由窗口 (isOverridingFreeformHack) →
  │     仅设置状态变量，立即 finish()
  │     （Android 10+ 或 ChromeOS 不需要实际运行不可见 Activity）
  ├── 设置窗口 flag: FLAG_NOT_TOUCH_MODAL | FLAG_WATCH_OUTSIDE_TOUCH
  │     （让触摸事件穿透到下层 Activity）
  ├── 注册三个 BroadcastReceiver:
  │   ├── appearingReceiver  → doNotHide = true（菜单弹出时不隐藏任务栏）
  │   ├── disappearingReceiver → doNotHide = false
  │   └── finishReceiver    → 收到广播时调用 reallyFinish()
  ├── 设置 freeformHackActive = true
  └── 如果是 CyanogenMod/LineageOS → 显示电源键警告
```

#### `onStart()`（第 172-216 行）

```
onStart()
  ├── 设置 inFreeformWorkspace = true
  ├── 如果 Taskbar 是默认启动器:
  │   ├── 首次运行 → 显示"最近应用"引导对话框
  │   ├── 启动 TaskbarService、StartMenuService、DashboardService
  │   └── 延迟 100ms 发送 ACTION_SHOW_TASKBAR
  └── 发送 ACTION_SHOW_TASKBAR
```

#### `finish()` 被覆写为 no-op（第 252 行）

```java
@Override
public void finish() {}  // 啥也不做，防止意外销毁
```

只有通过 `reallyFinish()`（第 267 行）或广播接收器才能真正结束这个 Activity。

### 2.5 状态追踪（`FreeformHackHelper.java`）

```java
public class FreeformHackHelper {
    private boolean freeformHackActive = false;      // hack 是否生效
    private boolean inFreeformWorkspace = false;      // 是否在自由窗口工作区中
    private boolean touchAbsorberActive = false;      // 触摸吸收器是否激活
}
```

这些状态被 `U.java` 中的多个方法读取，决定如何启动后续应用。

---

## 三、启动应用的完整流程

### 3.1 点击应用图标

用户点击任务栏或开始菜单中的应用 → `U.launchApp()` 被调用。

### 3.2 `launchApp()` 的决策逻辑（`U.java:384-405`）

```java
private static void launchApp(Context context, boolean launchedFromTaskbar,
                              boolean isPersistentShortcut, Runnable runnable) {
    FreeformHackHelper helper = FreeformHackHelper.getInstance();

    if(hasFreeformSupport(context)                              // 设备支持自由窗口
            && (isFreeformModeEnabled(context) || isPersistentShortcut)  // 用户已开启
            && (!helper.isInFreeformWorkspace() || specialLaunch)) {    // 尚未在自由工作区中
        // 先启动 hack（创建自由窗口工作区）
        startFreeformHack(context, true);
        // 延迟后执行真正的启动
        newHandler().postDelayed(runnable,
            helper.isFreeformHackActive() ? 0 : isAndroidR ? 300 : 100);
    } else {
        // 已经在自由工作区中 → 直接启动
        runnable.run();
    }
}
```

**三种情况：**
1. **还没进入自由模式** → 先启动 Freeform Hack（创建不可见 Activity），延迟 100-300ms 后执行真正的 launch
2. **已经处在自由模式** → 直接 launch
3. **设备不支持或用户没开启** → 普通启动（不浮窗）

### 3.3 `continueLaunchingApp()` — 构建 Intent 和 ActivityOptions（`U.java:436-488`）

```java
private static void continueLaunchingApp(Context context, AppEntry entry, ...) {
    Intent intent = new Intent();
    intent.setComponent(ComponentName.unflattenFromString(entry.getComponentName()));
    intent.setAction(Intent.ACTION_MAIN);
    intent.addCategory(Intent.CATEGORY_LAUNCHER);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);

    // Android 7.x 需要额外 flag 让 Activity 保持在 home stack
    if(FreeformHackHelper.getInstance().isInFreeformWorkspace()
            && Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1)
        intent.addFlags(Intent.FLAG_ACTIVITY_TASK_ON_HOME);

    // 如果开启了"强制新窗口"，添加 MULTIPLE_TASK flag
    if(openInNewWindow || pref.getBoolean(PREF_FORCE_NEW_WINDOW, false))
        applyOpenInNewWindow(context, intent);

    // 获取窗口大小（用户预设或上次保存的大小）
    if(windowSize == null)
        windowSize = SavedWindowSizes.getInstance(context).getWindowSize(context, entry.getPackageName());

    // 构建 ActivityOptions Bundle（包含窗口模式和位置）
    Bundle bundle = getActivityOptionsBundle(context, type, windowSize, view);

    // 启动 Activity
    context.startActivity(intent, bundle);
}
```

### 3.4 `getActivityOptionsBundle()` — 窗口大小选择（`U.java:1028-1050`）

```java
private static Bundle getActivityOptionsBundle(Context context, ApplicationType type,
                                                String windowSize, View view) {
    // 如果设备不支持自由窗口，普通启动
    if(!canEnableFreeform(context) || !isFreeformModeEnabled(context))
        return getActivityOptions(view).toBundle();

    switch(windowSize) {
        case "standard":    // 75% 屏幕（仅 Android 11+）
            if(getCurrentApiVersion() > 29.0f)
                return launchMode1(context, type, view, 4);
            break;
        case "large":       // 87.5% 屏幕
            return launchMode1(context, type, view, 8);
        case "fullscreen":  // 全屏（最大化）
            return launchMode2(context, MAXIMIZED, type, view);
        case "half_left":   // 左/上半屏
            return launchMode2(context, LEFT, type, view);
        case "half_right":  // 右/下半屏
            return launchMode2(context, RIGHT, type, view);
        case "phone_size":  // 手机尺寸模拟
            return launchMode3(context, type, view);
    }
    return getActivityOptions(context, type, view).toBundle();
}
```

---

## 四、窗口模式设置（反射调用）

### 4.1 `getActivityOptions()` 设置 Windowing Mode（`U.java:939-1000`）

核心代码通过**反射**调用 `ActivityOptions` 的方法来设置窗口模式：

```java
public static ActivityOptions getActivityOptions(Context context, ApplicationType type, View view) {
    ActivityOptions options;
    if(view != null)
        options = ActivityOptions.makeScaleUpAnimation(view, 0, 0, ...);
    else
        options = ActivityOptions.makeBasic();

    int stackId = -1;
    switch(applicationType) {
        case APP_PORTRAIT:
        case APP_LANDSCAPE:
            if(FreeformHackHelper.getInstance().isFreeformHackActive())
                stackId = getFreeformWindowModeId();  // 自由窗口模式
            else
                stackId = getFullscreenWindowModeId(); // 全屏模式
            break;
        case FREEFORM_HACK:
            stackId = getFreeformWindowModeId();
            break;
    }

    if(stackId != -1) {
        allowReflection();
        Method method = ActivityOptions.class.getMethod(
            getWindowingModeMethodName(), int.class);
        method.invoke(options, stackId);
    }

    return options;
}
```

### 4.2 版本适配（`U.java:1009-1021`）

```java
private static int getFreeformWindowModeId() {
    if(getCurrentApiVersion() >= 28.0f)
        return WINDOWING_MODE_FREEFORM;    // = 5 (Android 9+)
    else
        return FREEFORM_WORKSPACE_STACK_ID; // = 2 (Android 7-8)
}

private static String getWindowingModeMethodName() {
    if(getCurrentApiVersion() >= 28.0f)
        return "setLaunchWindowingMode";    // Android 9+ 公有 API
    else
        return "setLaunchStackId";          // Android 7-8 隐藏 API
}
```

| Android 版本 | 方法 | 模式值 |
|---|---|---|
| 7.x - 8.x | `setLaunchStackId(2)` | `FREEFORM_WORKSPACE_STACK_ID` |
| 9+ | `setLaunchWindowingMode(5)` | `WINDOWING_MODE_FREEFORM` |

`setLaunchWindowingMode` 从 Android 9 开始是**公有 API**（不再需要反射）。`setLaunchStackId` 在 7.x-8.x 上是隐藏 API，通过反射调用。

---

## 五、窗口位置和大小计算

### 5.1 `setLaunchBounds()` — 最终步骤（`U.java:1052-1066`）

```java
private static Bundle getActivityOptionsBundle(Context context, ApplicationType type,
                                                View view,
                                                int left, int top, int right, int bottom) {
    ActivityOptions options = getActivityOptions(context, type, view);
    if(options == null) return null;
    if(Build.VERSION.SDK_INT < Build.VERSION_CODES.N)
        return options.toBundle();

    // 设置窗口位置和大小
    return options.setLaunchBounds(new Rect(left, top, right, bottom)).toBundle();
}
```

`ActivityOptions.setLaunchBounds(Rect)` 是 Android N 引入的 API。这个 Rect 定义了新 Activity 在屏幕上的位置和尺寸。

### 5.2 窗口大小模式

#### Standard（75% 居中）：`launchMode1(context, type, view, 4)`（第 490-504 行）

```java
// factor=4：宽/高各取 1/4 做边距，窗口占 3/4
width1  = display.width / 4;         // 左边距 12.5%
height1 = display.height / 4;        // 上边距 12.5%
width2  = display.width - width1;    // 右边距 12.5%
height2 = display.height - height1;  // 下边距 12.5%
Rect(width1, height1, width2, height2)
```

#### Large（87.5% 居中）：`launchMode1(context, type, view, 8)`（第 490-504 行）

```java
// factor=8：宽/高各取 1/8 做边距，窗口占 7/8
width1  = display.width / 8;         // 左边距 6.25%
...
```

#### Maximized（最大化）：`launchMode2(context, MAXIMIZED, ...)`（第 506-554 行）

计算时考虑状态栏高度和任务栏位置：

```
left   = 0
top    = statusBarHeight
right  = display.width
bottom = display.height
// 减去任务栏占用的空间
if (任务栏在左)  left  += iconSize
if (任务栏在右)  right -= iconSize
if (任务栏在下)  bottom -= iconSize
if (任务栏在上)  top   += iconSize
```

#### Half Left/Right（半屏）：`launchMode2(context, LEFT/RIGHT, ...)`

```
横屏 LEFT:   right = halfLandscape  (左半屏)
横屏 RIGHT:  left  = halfLandscape  (右半屏)
竖屏 LEFT:   bottom = halfPortrait  (上半屏)
竖屏 RIGHT:  top   = halfPortrait   (下半屏)
```

#### Phone Size（手机尺寸模拟）：`launchMode3()`（第 556-574 行）

固定尺寸（320×480dp）居中显示。

---

## 六、"在新窗口中打开"机制（`U.java:2147-2161`）

```java
public static void applyOpenInNewWindow(Context context, Intent intent) {
    if(!isFreeformModeEnabled(context)) return;

    intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);

    ActivityInfo activityInfo = intent.resolveActivityInfo(context.getPackageManager(), 0);
    if(activityInfo != null) {
        switch(activityInfo.launchMode) {
            case ActivityInfo.LAUNCH_SINGLE_TASK:
            case ActivityInfo.LAUNCH_SINGLE_INSTANCE:
                intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
                break;
        }
    }
}
```

`FLAG_ACTIVITY_MULTIPLE_TASK` 允许同一个应用有多个任务实例（多窗口）。

---

## 七、设备支持检测

### 7.1 `hasFreeformSupport()`（`U.java:850-856`）

```java
public static boolean hasFreeformSupport(Context context) {
    return canEnableFreeform(context)                                      // Android N+
            && (context.getPackageManager()
                    .hasSystemFeature(FEATURE_FREEFORM_WINDOW_MANAGEMENT)   // ① 原生支持
            || Settings.Global.getInt(context.getContentResolver(),
                    "enable_freeform_support", 0) != 0                     // ② 全局设置已开启
            || (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1
            && Settings.Global.getInt(context.getContentResolver(),
                    "force_resizable_activities", 0) != 0));              // ③ 7.x 兼容设置
}
```

三种方式之一即可：

| 方式 | 设置途径 |
|---|---|
| ① 系统 Feature | 设备出厂声明支持自由窗口 |
| ② `enable_freeform_support=1` | ADB: `settings put global enable_freeform_support 1` |
| ③ `force_resizable_activities=1` | 开发者选项（仅 Android 7.x） |

### 7.2 `isFreeformModeEnabled()`（`U.java:2128-2133`）

```java
public static boolean isFreeformModeEnabled(Context context) {
    if(isLibrary(context)) return true;
    SharedPreferences pref = getSharedPreferences(context);
    return pref.getBoolean(PREF_DESKTOP_MODE, false)   // 桌面模式
        || pref.getBoolean(PREF_FREEFORM_HACK, false);  // 自由窗口开关
}
```

---

## 八、AndroidManifest 关键声明

```xml
<uses-feature android:name="android.software.freeform_window_management"
              android:required="false" />

<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />

<activity
    android:name=".activity.InvisibleActivityFreeform"
    android:documentLaunchMode="always"
    android:excludeFromRecents="true"
    android:launchMode="singleInstance"
    android:theme="@style/Taskbar.InvisibleActivity"
    android:configChanges="mcc|mnc|locale|touchscreen|keyboard|keyboardHidden|
        navigation|screenLayout|fontScale|uiMode|orientation|screenSize|
        smallestScreenSize|layoutDirection" />
```

关键属性：
- `excludeFromRecents="true"` — 不在最近任务中显示
- `launchMode="singleInstance"` — 全局只有这一个实例
- `documentLaunchMode="always"` — 每次打开都是新文档（安全兜底）

---

## 九、完整时序图

```
用户点击应用图标
    │
    ▼
U.launchApp()
    │
    ├─ hasFreeformSupport()? ─── 否 ──► context.startActivity(intent) // 普通启动
    │
    ├─ isFreeformModeEnabled()? ─ 否 ──► context.startActivity(intent) // 普通启动
    │
    ├─ inFreeformWorkspace()? ── 是 ──► continueLaunchingApp() // 已在自由模式
    │                                       │
    │                                       ▼
    │                               getActivityOptionsBundle()
    │                                   ├─ getActivityOptions()
    │                                   │   └─ setLaunchWindowingMode(5)  // 自由窗口
    │                                   └─ setLaunchBounds(Rect)          // 位置大小
    │                                       │
    │                                       ▼
    │                               context.startActivity(intent, bundle)
    │
    └─ 否 ──► startFreeformHack(context, true)   // 创建自由窗口工作区
                  │
                  ▼
         startActivityLowerRight()
              │
              ▼
         InvisibleActivityFreeform 启动
              │
              ├─ isInMultiWindowMode()? ─── 否 ──► finish()
              │
              ├─ set freeformHackActive = true
              ├─ set inFreeformWorkspace = true
              │
              ▼
         延迟 100-300ms 后执行 continueLaunchingApp()
```

---

## 十、关键 API 总结

| API | 用途 | 最低版本 | 是否公有 |
|-----|------|---------|---------|
| `ActivityOptions.setLaunchBounds(Rect)` | 设置浮窗位置和大小 | API 24 | 公有 |
| `ActivityOptions.setLaunchWindowingMode(int)` | 设置窗口模式为浮窗 | API 28 | **公有** |
| `ActivityOptions.setLaunchStackId(int)` | 同上（旧版） | API 24 | 隐藏（反射） |
| `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY` | 系统悬浮层类型 | API 26 | 公有 |
| `Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT` | 强制进入多窗口模式 | API 24 | 公有 |
| `Intent.FLAG_ACTIVITY_MULTIPLE_TASK` | 允许多实例 | API 24 | 公有 |
| `Settings.Global.putInt("enable_freeform_support", 1)` | 开启自由窗口支持 | API 17 | 需 WRITE_SETTINGS |

---

## 十一、文件索引

| 关键代码 | 路径 |
|---------|------|
| Freeform Hack 启动 | `util/U.java:412-423` |
| Freeform Hack 停止 | `util/U.java:425-433` |
| 屏幕外启动 | `util/U.java:626-637` |
| 应用启动入口 | `util/U.java:384-405` |
| 继续启动流程 | `util/U.java:436-488` |
| 窗口模式设置 | `util/U.java:939-1000` |
| 窗口大小选择 | `util/U.java:1028-1050` |
| setLaunchBounds | `util/U.java:1052-1066` |
| Standard/Large 计算 | `util/U.java:490-504` |
| Maximized/Half 计算 | `util/U.java:506-554` |
| Phone Size 计算 | `util/U.java:556-574` |
| 强制新窗口 | `util/U.java:2147-2161` |
| 支持检测 | `util/U.java:850-856` |
| 开关判断 | `util/U.java:2128-2133` |
| 不可见 Activity | `activity/InvisibleActivityFreeform.java` |
| 状态追踪 | `helper/FreeformHackHelper.java` |
| 常量定义 | `util/Constants.java` |
