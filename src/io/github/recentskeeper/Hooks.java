package io.github.recentskeeper;

import android.app.AndroidAppHelper;
import android.content.Context;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Launcher-process side of the module.
 *
 * A recents swipe reaches two independent kill routes in the OPPO launcher:
 * the AOSP {@code ActivityManagerWrapper.removeTask} call, and a request to the
 * Athena / OSense cleaner built by {@code KillAppWrapper.forceStopTasks}. The
 * Task array handed to the latter is the list of tasks to destroy, so protected
 * packages are dropped from it here, which cuts that route off at the source.
 * The AOSP route is neutralised in system_server instead (see SystemHooks),
 * because the launcher's call is also what removes the card from Recents.
 */
final class Hooks {

    private static final String TAG = "RecentsKeeper";
    private static final String KILL_APP_WRAPPER = "com.oplus.quickstep.memory.KillAppWrapper";
    private static final String AM_WRAPPER = "com.android.systemui.shared.system.ActivityManagerWrapper";
    private static final String TASK = "com.android.systemui.shared.recents.model.Task";

    /** taskId -> package, so the launcher's removeTask(int) call can be attributed in logs. */
    private static final Map<Integer, String> TASK_PACKAGES =
            new LinkedHashMap<Integer, String>(64, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, String> eldest) {
                    return size() > 128;
                }
            };

    private Hooks() {
    }

    static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    /**
     * Escape hatch checked before any hook is installed.
     *
     * Neither system_server nor the launcher can read /data/local/tmp, but both
     * can read system properties, so a property is the signal that survives a
     * broken UI: {@code su -c setprop persist.recentskeeper.disable 1}.
     */
    static boolean disabled() {
        for (String key : new String[]{"persist.recentskeeper.disable", "recentskeeper.disable"}) {
            try {
                Class<?> properties = Class.forName("android.os.SystemProperties");
                Object value = XposedHelpers.callStaticMethod(properties, "get", key);
                if (value instanceof String && !((String) value).isEmpty()
                        && !"0".equals(value)) {
                    return true;
                }
            } catch (Throwable ignored) {
                // property not readable, try the next signal
            }
        }
        try {
            return new java.io.File("/data/local/tmp/recentskeeper-disable").exists();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** A context able to read the settings, whichever process we ended up in. */
    static Context appContext() {
        Context ctx = AndroidAppHelper.currentApplication();
        if (ctx != null) {
            return ctx;
        }
        return systemContext();
    }

    /**
     * The context that exists in system_server, where there is no Application
     * instance. Two independent routes are tried, because this is also called
     * from the ready-publisher thread and the system context may not have been
     * published yet the first time round.
     */
    static Context systemContext() {
        Context ctx = initialApplication();
        if (ctx != null) {
            return ctx;
        }
        return activityThreadContext();
    }

    private static Context initialApplication() {
        try {
            Class<?> globals = XposedHelpers.findClass("android.app.AppGlobals", loader);
            Object application = XposedHelpers.callStaticMethod(globals, "getInitialApplication");
            return application instanceof Context ? (Context) application : null;
        } catch (Throwable t) {
            log("initial application unavailable: " + t);
            return null;
        }
    }

    private static Context activityThreadContext() {
        try {
            Class<?> threadClass = XposedHelpers.findClass("android.app.ActivityThread", loader);
            Object thread = XposedHelpers.callStaticMethod(threadClass, "currentActivityThread");
            if (thread == null) {
                return null;
            }
            Object ctx = XposedHelpers.callMethod(thread, "getSystemContext");
            return ctx instanceof Context ? (Context) ctx : null;
        } catch (Throwable t) {
            log("system context unavailable: " + t);
            return null;
        }
    }

    /** Set while hooking, used to reach the host's own classes later. */
    private static ClassLoader loader;

    static void setLoader(ClassLoader classLoader) {
        loader = classLoader;
    }

    static void installInLauncher(ClassLoader cl) {
        setLoader(cl);
        Class<?> taskClass = XposedHelpers.findClass(TASK, cl);
        Class<?> killClass = XposedHelpers.findClass(KILL_APP_WRAPPER, cl);

        hookCanRemoveTask(killClass, taskClass);
        log("hooked canRemoveTask");
        hookForceStopTasks(killClass, taskClass);
        log("hooked forceStopTasks");
        try {
            hookRemoveTaskCall(cl);
            log("hooked ActivityManagerWrapper.removeTask");
        } catch (Throwable t) {
            log("ActivityManagerWrapper.removeTask hook skipped: " + t);
        }
    }

    /**
     * The launcher's own veto check before it removes a task. Also records the
     * taskId -> package mapping used to attribute the later removeTask(int) call.
     */
    private static void hookCanRemoveTask(Class<?> killClass, Class<?> taskClass) {
        XposedHelpers.findAndHookMethod(killClass, "canRemoveTask", taskClass,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object task = param.args[0];
                            String pkg = packageOf(task);
                            remember(task, pkg);
                            if (pkg == null || !Boolean.TRUE.equals(param.getResult())) {
                                return;
                            }
                            ConfigReader.maybeRefresh(appContext());
                            boolean isProtected = ConfigReader.isProtected(pkg);
                            log("swipe " + pkg + " protected=" + isProtected
                                    + " mode=" + ConfigReader.mode());
                            if (ConfigReader.mode() != Config.MODE_LAUNCHER) {
                                return;
                            }
                            if (isProtected) {
                                param.setResult(Boolean.FALSE);
                                log("kept alive at launcher gate: " + pkg);
                            }
                        } catch (Throwable t) {
                            log("canRemoveTask hook error: " + t);
                        }
                    }
                });
    }

    /**
     * The Athena / OSense kill list. This one carries the tasks to destroy, so
     * protected packages are removed from it. (Its sibling
     * clearAllTasksExcept carries the opposite - the tasks to keep - and is
     * therefore left untouched.)
     */
    private static void hookForceStopTasks(Class<?> killClass, Class<?> taskClass) {
        Class<?> arrayClass = Array.newInstance(taskClass, 0).getClass();
        XposedHelpers.findAndHookMethod(killClass, "forceStopTasks", Context.class, arrayClass,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Object original = param.args[1];
                            ConfigReader.maybeRefresh(appContext());
                            Object filtered = withoutProtected(original);
                            if (filtered != original) {
                                param.args[1] = filtered;
                                log("forceStopTasks list trimmed "
                                        + Array.getLength(original) + " -> "
                                        + Array.getLength(filtered));
                            }
                        } catch (Throwable t) {
                            log("forceStopTasks hook error: " + t);
                        }
                    }
                });
    }

    /** Pure instrumentation: confirms whether the AOSP route is taken at all. */
    private static void hookRemoveTaskCall(ClassLoader cl) {
        Class<?> cls = XposedHelpers.findClass(AM_WRAPPER, cl);
        XposedHelpers.findAndHookMethod(cls, "removeTask", int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    int id = (Integer) param.args[0];
                    String pkg;
                    synchronized (TASK_PACKAGES) {
                        pkg = TASK_PACKAGES.get(id);
                    }
                    if (pkg == null) {
                        return;
                    }
                    log("ATMS route: removeTask(" + id + ") " + pkg
                            + (ConfigReader.isProtected(pkg) ? " [protected]" : ""));
                } catch (Throwable t) {
                    log("removeTask hook error: " + t);
                }
            }
        });
    }

    private static Object withoutProtected(Object tasks) {
        if (tasks == null) {
            return null;
        }
        int length = Array.getLength(tasks);
        List<Object> keep = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            Object task = Array.get(tasks, i);
            String pkg = packageOf(task);
            if (pkg != null && ConfigReader.isProtected(pkg)) {
                log("dropped from forceStop list: " + pkg);
                dropCard(task);
            } else {
                keep.add(task);
            }
        }
        if (keep.size() == length) {
            return tasks;
        }
        Object out = Array.newInstance(tasks.getClass().getComponentType(), keep.size());
        for (int i = 0; i < keep.size(); i++) {
            Array.set(out, i, keep.get(i));
        }
        return out;
    }

    /**
     * Asks the system to drop the task from Recents, which is what makes the
     * card disappear for good. That call also requests the process kill, which
     * SystemHooks suppresses - so it is only made once system_server has
     * confirmed those hooks are live. Otherwise the card is left in place, which
     * keeps the app alive but leaves the task in Recents.
     */
    private static void dropCard(Object task) {
        if (ConfigReader.mode() != Config.MODE_SYSTEM) {
            return;
        }
        if (!ConfigReader.systemHooksReady()) {
            log("system hooks not confirmed, card left in place");
            return;
        }
        Integer id = taskId(task);
        if (id == null || loader == null) {
            return;
        }
        try {
            Class<?> wrapper = XposedHelpers.findClass(AM_WRAPPER, loader);
            Object instance = XposedHelpers.callStaticMethod(wrapper, "getInstance");
            XposedHelpers.callMethod(instance, "removeTask", id);
            log("asked system to drop the card: " + id);
        } catch (Throwable t) {
            log("card removal failed: " + t);
        }
    }

    private static void remember(Object task, String pkg) {
        if (task == null || pkg == null) {
            return;
        }
        Integer id = taskId(task);
        if (id == null) {
            return;
        }
        synchronized (TASK_PACKAGES) {
            TASK_PACKAGES.put(id, pkg);
        }
    }

    private static Integer taskId(Object task) {
        try {
            Object id = XposedHelpers.callMethod(task, "getId");
            if (id instanceof Integer) {
                return (Integer) id;
            }
        } catch (Throwable ignored) {
            // fall through to the raw field
        }
        try {
            Object key = XposedHelpers.getObjectField(task, "key");
            if (key != null) {
                return XposedHelpers.getIntField(key, "id");
            }
        } catch (Throwable ignored) {
            // give up
        }
        return null;
    }

    /** Reads the package name off a recents Task via its TaskKey. */
    static String packageOf(Object task) {
        if (task == null) {
            return null;
        }
        try {
            Object key = XposedHelpers.getObjectField(task, "key");
            if (key == null) {
                return null;
            }
            try {
                Object name = XposedHelpers.callMethod(key, "getPackageName");
                if (name instanceof String) {
                    return (String) name;
                }
            } catch (Throwable ignored) {
                // fall through to the raw field
            }
            Object field = XposedHelpers.getObjectField(key, "packageName");
            return field instanceof String ? (String) field : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
