package io.github.recentskeeper;

/**
 * Contract shared between the settings UI and the code injected into the
 * launcher and into system_server.
 *
 * There is deliberately no cross-process provider here: the configuration
 * travels through Settings.System, which any process can read without holding a
 * permission and which keeps working when this app is not running.
 */
public final class Config {

    /** Our own package, i.e. the owner of the SharedPreferences below. */
    public static final String MODULE_PACKAGE = "io.github.recentskeeper";

    public static final String PREFS = "prefs";
    public static final String KEY_INVERT = "invert";
    public static final String KEY_PKGS = "pkgs";
    public static final String KEY_MODE = "mode";

    /**
     * Format: {@code mode|invert|pkg1,pkg2}.
     *
     * Settings.System is world readable by design, so which apps are protected
     * can be inferred by any app on the device, and any app holding
     * WRITE_SETTINGS could overwrite the value. That is a property of this
     * channel rather than something the module introduces.
     */
    public static final String SETTINGS_KEY = "rk_config";

    /**
     * Heartbeat written by system_server once its hooks are installed. The
     * launcher only asks the system to drop a card when this is fresh, because
     * dropping the card also requests a process kill that system_server has to
     * suppress - without that suppression the app would die.
     */
    public static final String SETTINGS_READY_KEY = "rk_system_ready";

    /** The OPPO / OnePlus / realme launcher that owns the Quickstep recents UI. */
    public static final String LAUNCHER_PKG = "com.android.launcher";
    /** LSPosed reports system_server under this package name. */
    public static final String SYSTEM_PKG = "android";

    /**
     * Intercept in the launcher process only. The launcher never asks the system
     * to remove the task, so the process survives - but the task stays in the
     * system's recents list, which means the card reappears later.
     */
    public static final int MODE_LAUNCHER = 0;
    /**
     * Let the launcher remove the task normally and suppress the process kill in
     * system_server instead. The card disappears for good and the process lives.
     */
    public static final int MODE_SYSTEM = 1;

    private Config() {
    }
}
