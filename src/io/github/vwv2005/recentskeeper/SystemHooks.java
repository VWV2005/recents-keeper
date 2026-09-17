package io.github.vwv2005.recentskeeper;

import android.content.ComponentName;
import android.content.Intent;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * system_server side of "let the card go, keep the process".
 *
 * On ColorOS a recents swipe ends up in the window manager, which first drops
 * the task from the recents list and only then decides whether to kill the
 * process. Both flags below are the kill decision, so clearing them yields
 * exactly the stock behaviour minus the process death:
 *
 * <ul>
 *   <li>{@code ActivityTaskSupervisor.removeTask(Task, boolean, boolean, String)}
 *       - the launcher's per-task removal, identified by reason "remove-task".</li>
 *   <li>{@code ActivityTaskSupervisor.cleanUpRemovedTask(Task, boolean, boolean)}
 *       - safety net for any other caller; the recents-list removal happens
 *       before this flag is consulted, so the card still disappears.</li>
 * </ul>
 *
 * Every callback is wrapped: a throw here would take down system_server, and a
 * config that cannot be read simply means "not protected", i.e. stock behaviour.
 */
final class SystemHooks {

    private static final String SUPERVISOR = "com.android.server.wm.ActivityTaskSupervisor";
    private static final String WM_TASK = "com.android.server.wm.Task";
    private static final String REMOVE_TASK_REASON = "remove-task";
    private static final String ATHENA_LOCAL_SERVICE =
            "com.android.server.am.OplusAthenaAmManager$LocalService";
    /** Force-stopping a package that does not exist is a no-op. */
    private static final String NEUTRALISED_PACKAGE = "io.github.vwv2005.recentskeeper.neutralised";
    private static final long READY_PUBLISH_MS = 60000L;
    /** Packages that run one-key clean-up on this ROM. */
    private static final String[] CLEANER_PACKAGES = {
            "com.oplus.athena",
            "com.oplus.cleaner",
            "com.coloros.phonemanager",
            "com.oplus.safecenter",
    };
    private static volatile Set<Integer> cleanerUids;

    private SystemHooks() {
    }

    static void install(ClassLoader cl) {
        Hooks.setLoader(cl);
        ConfigReader.startPoller(Hooks.systemContext());
        Class<?> taskClass = XposedHelpers.findClass(WM_TASK, cl);
        Class<?> supervisorClass = XposedHelpers.findClass(SUPERVISOR, cl);
        hookRemoveTask(supervisorClass, taskClass);
        hookCleanUpRemovedTask(supervisorClass, taskClass);
        hookAthenaForceStop(cl);
        hookCleanerForceStop(cl);
        startReadyPublisher(cl);
        Hooks.log("system side ready");
    }

    /**
     * One-key clean-up leaves the launcher as an intent for the OPPO cleaner,
     * which force-stops the packages itself. That lands in the very same
     * {@code forceStopPackage} the Settings "force stop" button uses, so the
     * caller is what separates them: only the cleaner's own uid is filtered.
     */
    private static void hookCleanerForceStop(ClassLoader cl) {
        try {
            Class<?> ams = XposedHelpers.findClass(
                    "com.android.server.am.ActivityManagerService", cl);
            XposedHelpers.findAndHookMethod(ams, "forceStopPackage", String.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (ConfigReader.mode() != Config.MODE_SYSTEM) {
                                    return;
                                }
                                String pkg = (String) param.args[0];
                                if (pkg == null || !ConfigReader.isProtected(pkg)) {
                                    return;
                                }
                                int callingUid = android.os.Binder.getCallingUid();
                                Hooks.log("forceStopPackage(" + pkg + ") requested by uid="
                                        + callingUid);
                                android.content.Context ctx = Hooks.systemContext();
                                if (ctx != null && isCleaner(ctx, callingUid)) {
                                    param.setResult(null);
                                    Hooks.log("suppressed cleaner force-stop of " + pkg);
                                }
                            } catch (Throwable t) {
                                Hooks.log("forceStopPackage hook error: " + t);
                            }
                        }
                    });
            Hooks.log("hooked forceStopPackage");
        } catch (Throwable t) {
            Hooks.log("forceStopPackage hook unavailable: " + t);
        }
    }

    private static boolean isCleaner(android.content.Context ctx, int uid) {
        if (cleanerUids == null) {
            Set<Integer> uids = new HashSet<>();
            for (String pkg : CLEANER_PACKAGES) {
                try {
                    uids.add(ctx.getPackageManager().getPackageUid(pkg, 0));
                } catch (Throwable ignored) {
                    // package not present on this ROM
                }
            }
            cleanerUids = uids;
            Hooks.log("cleaner uids: " + uids);
        }
        return cleanerUids.contains(uid);
    }

    /**
     * Keeps telling the launcher, through Settings.System, that the kill
     * suppression below is live, so it knows it is safe to drop a card. Settings
     * is not writable this early in boot, hence the periodic retry.
     */
    private static void startReadyPublisher(final ClassLoader cl) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        publishReady(cl);
                    } catch (Throwable t) {
                        Hooks.log("ready flag publish failed: " + t);
                    }
                    try {
                        Thread.sleep(READY_PUBLISH_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "recentskeeper-ready");
        thread.setDaemon(true);
        thread.start();
    }

    private static void publishReady(ClassLoader cl) {
        android.content.Context ctx = Hooks.systemContext();
        if (ctx == null) {
            return;
        }
        long uptime = android.os.SystemClock.elapsedRealtime();
        Class<?> settingsSystem =
                XposedHelpers.findClass("android.provider.Settings$System", cl);
        XposedHelpers.callStaticMethod(settingsSystem, "putStringForUser",
                ctx.getContentResolver(), Config.SETTINGS_READY_KEY, String.valueOf(uptime), 0);
    }

    /**
     * The launcher also hands the swiped task to the OPPO cleaner, whose kill
     * finally lands here as a plain package force-stop. A package name that does
     * not exist is a harmless no-op for the framework.
     */
    private static void hookAthenaForceStop(ClassLoader cl) {
        try {
            Class<?> cleaner = XposedHelpers.findClass(ATHENA_LOCAL_SERVICE, cl);
            XposedHelpers.findAndHookMethod(cleaner, "forceStopPackages",
                    List.class, List.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (ConfigReader.mode() != Config.MODE_SYSTEM) {
                                    return;
                                }
                                for (int i = 0; i < param.args.length; i++) {
                                    neutralise(param.args[i]);
                                }
                            } catch (Throwable t) {
                                Hooks.log("athena hook error: " + t);
                            }
                        }
                    });
            Hooks.log("hooked athena forceStopPackages");
        } catch (Throwable t) {
            Hooks.log("athena hook unavailable: " + t);
        }
    }

    /**
     * Replaces protected package names with a placeholder rather than dropping
     * them: these lists are positional (package alongside its userId), so
     * removing an entry would shift every later pair and force-stop the wrong
     * apps.
     */
    @SuppressWarnings("unchecked")
    private static void neutralise(Object value) {
        if (!(value instanceof List)) {
            return;
        }
        List<Object> list = (List<Object>) value;
        for (int i = 0; i < list.size(); i++) {
            Object element = list.get(i);
            if (element instanceof String && ConfigReader.isProtected((String) element)) {
                try {
                    list.set(i, NEUTRALISED_PACKAGE);
                    Hooks.log("athena clean neutralised for " + element);
                } catch (Throwable t) {
                    Hooks.log("athena list not mutable: " + t);
                }
            }
        }
    }

    private static void hookRemoveTask(Class<?> supervisorClass, Class<?> taskClass) {
        XposedHelpers.findAndHookMethod(supervisorClass, "removeTask", taskClass,
                boolean.class, boolean.class, String.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (ConfigReader.mode() != Config.MODE_SYSTEM) {
                                return;
                            }
                            String pkg = packageOfTask(param.args[0]);
                            if (pkg == null || !ConfigReader.isProtected(pkg)) {
                                return;
                            }
                            Hooks.log("removeTask pkg=" + pkg
                                    + " killProcess=" + param.args[1]
                                    + " removeFromRecents=" + param.args[2]
                                    + " reason=" + param.args[3]);
                            if (REMOVE_TASK_REASON.equals(param.args[3])) {
                                param.args[1] = Boolean.FALSE;
                                Hooks.log("suppressed kill (removeTask) for " + pkg);
                            }
                        } catch (Throwable t) {
                            Hooks.log("removeTask hook error: " + t);
                        }
                    }
                });
    }

    private static void hookCleanUpRemovedTask(Class<?> supervisorClass, Class<?> taskClass) {
        XposedHelpers.findAndHookMethod(supervisorClass, "cleanUpRemovedTask", taskClass,
                boolean.class, boolean.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (ConfigReader.mode() != Config.MODE_SYSTEM) {
                                return;
                            }
                            String pkg = packageOfTask(param.args[0]);
                            if (pkg == null || !ConfigReader.isProtected(pkg)) {
                                return;
                            }
                            Hooks.log("cleanUpRemovedTask pkg=" + pkg
                                    + " killProcess=" + param.args[1]
                                    + " removeFromRecents=" + param.args[2]);
                            param.args[1] = Boolean.FALSE;
                            Hooks.log("suppressed kill (cleanUpRemovedTask) for " + pkg);
                        } catch (Throwable t) {
                            Hooks.log("cleanUpRemovedTask hook error: " + t);
                        }
                    }
                });
    }

    /**
     * Resolves the owning package of a window-manager Task. Several shapes exist
     * across versions, so every known route is tried before giving up - giving up
     * only means the package is treated as unprotected.
     */
    private static String packageOfTask(Object task) {
        if (task == null) {
            return null;
        }
        String pkg = callString(task, "getBasePackageName");
        if (pkg != null) {
            return pkg;
        }
        try {
            Object activity = XposedHelpers.getObjectField(task, "realActivity");
            if (activity instanceof ComponentName) {
                String name = ((ComponentName) activity).getPackageName();
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
        } catch (Throwable ignored) {
            // try the next shape
        }
        try {
            Object intent = XposedHelpers.callMethod(task, "getBaseIntent");
            if (intent instanceof Intent) {
                ComponentName component = ((Intent) intent).getComponent();
                if (component != null) {
                    return component.getPackageName();
                }
            }
        } catch (Throwable ignored) {
            // try the next shape
        }
        // affinity looks like "10388:com.example.app"
        String affinity = fieldString(task, "affinity");
        if (affinity != null) {
            int colon = affinity.indexOf(':');
            if (colon >= 0 && colon + 1 < affinity.length()) {
                return affinity.substring(colon + 1);
            }
        }
        return null;
    }

    private static String callString(Object target, String method) {
        try {
            Object value = XposedHelpers.callMethod(target, method);
            return value instanceof String && !((String) value).isEmpty() ? (String) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String fieldString(Object target, String field) {
        try {
            Object value = XposedHelpers.getObjectField(target, field);
            return value instanceof String && !((String) value).isEmpty() ? (String) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
