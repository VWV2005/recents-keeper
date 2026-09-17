package io.github.recentskeeper;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class ModuleEntry implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            if (Hooks.disabled()) {
                Hooks.log("kill switch set, skipping " + lpparam.packageName);
                return;
            }
            if (Config.LAUNCHER_PKG.equals(lpparam.packageName)) {
                Hooks.installInLauncher(lpparam.classLoader);
                Hooks.log("installed in " + lpparam.packageName);
            } else if (Config.SYSTEM_PKG.equals(lpparam.packageName)) {
                SystemHooks.install(lpparam.classLoader);
            }
        } catch (Throwable t) {
            Hooks.log("install failed for " + lpparam.packageName + ": " + t);
        }
    }
}
