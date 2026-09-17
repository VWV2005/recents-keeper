package io.github.vwv2005.recentskeeper;

import android.content.ContentResolver;
import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Resolves the protected-package set inside an injected process.
 *
 * Lookups run on hot paths - in system_server that can mean while the window
 * manager global lock is held - so the decision itself never performs I/O. The
 * value is read from Settings.System, which any process may read without a
 * permission and which does not depend on this module's own process being
 * alive; ColorOS refuses to start a background app to serve a provider query,
 * so a provider is deliberately not used.
 *
 * A per-process cache of the last successfully loaded value covers the window
 * right after boot, before the settings provider is up.
 *
 * Whatever cannot be read stays "not protected", so a broken configuration
 * channel degrades to stock Android behaviour instead of breaking the process
 * we are injected into.
 */
public final class ConfigReader {

    private static final long TTL_MS = 3000L;
    private static final long POLL_MS = 5000L;
    private static final long READY_TTL_MS = 6L * 60 * 60 * 1000;
    private static final String CACHE_NAME = "recents-keeper-cache.txt";
    private static final String FALLBACK_CACHE_DIR = "/data/system";

    private static volatile long loadedAt;
    private static volatile boolean loaded;
    private static volatile boolean invert;
    private static volatile int mode = Config.MODE_SYSTEM;
    private static volatile Set<String> packages = Collections.emptySet();
    private static volatile String lastProblem = "";
    private static volatile long readyUptime;

    private ConfigReader() {
    }

    public static boolean isProtected(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return false;
        }
        boolean listed = packages.contains(pkg);
        // Inverted mode protects everything the user did not tick.
        return invert ? !listed : listed;
    }

    public static int mode() {
        return mode;
    }

    /**
     * True when system_server has confirmed, during this boot and recently, that
     * its kill-suppression hooks are live. Uptime is compared rather than
     * wall-clock so a stamp left over from a previous boot - which is larger than
     * the current uptime - is rejected.
     */
    public static boolean systemHooksReady() {
        long now = SystemClock.elapsedRealtime();
        long stamp = readyUptime;
        return stamp > 0 && stamp <= now && now - stamp < READY_TTL_MS;
    }

    /** Launcher path: a TTL-guarded read, never called while holding a system lock. */
    public static void maybeRefresh(Context ctx) {
        long now = SystemClock.elapsedRealtime();
        if (now - loadedAt < TTL_MS) {
            return;
        }
        loadedAt = now;
        refresh(ctx);
    }

    /** system_server path: keep settings I/O off the hook callbacks entirely. */
    public static void startPoller(final Context ctx) {
        if (ctx == null) {
            Hooks.log("no system context, config polling disabled");
            return;
        }
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        refresh(ctx);
                    } catch (Throwable t) {
                        Hooks.log("config poll failed: " + t);
                    }
                    try {
                        Thread.sleep(POLL_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "recents-keeper-config");
        thread.setDaemon(true);
        thread.start();
    }

    private static void refresh(Context ctx) {
        if (loadFromSettings(ctx)) {
            reportLoaded("settings");
            writeCache(ctx);
            return;
        }
        if (loadFromCache(ctx)) {
            reportLoaded("cache");
            return;
        }
        noteProblem("config unavailable, keeping mode=" + mode);
    }

    private static boolean loadFromSettings(Context ctx) {
        if (ctx == null) {
            return false;
        }
        try {
            ContentResolver resolver = ctx.getContentResolver();
            String ready = Settings.System.getString(resolver, Config.SETTINGS_READY_KEY);
            try {
                readyUptime = ready == null || ready.isEmpty() ? 0 : Long.parseLong(ready.trim());
            } catch (NumberFormatException ignored) {
                readyUptime = 0;
            }
            String raw = Settings.System.getString(resolver, Config.SETTINGS_KEY);
            if (raw == null || raw.isEmpty()) {
                return false;
            }
            String[] parts = raw.split("\\|", 3);
            if (parts.length < 3) {
                return false;
            }
            apply(Integer.parseInt(parts[0].trim()), Boolean.parseBoolean(parts[1].trim()),
                    parts[2]);
            return !packages.isEmpty();
        } catch (Throwable t) {
            noteProblem("settings read failed: " + t);
            return false;
        }
    }

    private static void apply(int newMode, boolean newInvert, String rawPackages) {
        mode = newMode;
        invert = newInvert;
        packages = parse(rawPackages);
    }

    private static void reportLoaded(String via) {
        boolean firstLoad = !loaded;
        loaded = true;
        if (firstLoad) {
            Hooks.log("config loaded via " + via + ": mode=" + mode
                    + " invert=" + invert + " n=" + packages.size());
        }
    }

    /** Logs only when the problem changes, so a broken channel cannot flood the log. */
    private static void noteProblem(String message) {
        if (!message.equals(lastProblem)) {
            lastProblem = message;
            Hooks.log(message);
        }
    }

    private static void writeCache(Context ctx) {
        FileOutputStream out = null;
        try {
            File file = cacheFile(ctx);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            out = new FileOutputStream(file);
            out.write((mode + "|" + invert + "|" + join(packages)).getBytes("UTF-8"));
        } catch (Throwable t) {
            Hooks.log("config cache write failed: " + t);
        } finally {
            close(out);
        }
    }

    private static boolean loadFromCache(Context ctx) {
        FileInputStream in = null;
        try {
            File file = cacheFile(ctx);
            if (!file.exists()) {
                return false;
            }
            in = new FileInputStream(file);
            byte[] buffer = new byte[(int) file.length()];
            int read = in.read(buffer);
            if (read <= 0) {
                return false;
            }
            String[] parts = new String(buffer, 0, read, "UTF-8").split("\\|", 3);
            if (parts.length < 3) {
                return false;
            }
            apply(Integer.parseInt(parts[0].trim()), Boolean.parseBoolean(parts[1].trim()),
                    parts[2]);
            return !packages.isEmpty();
        } catch (Throwable t) {
            noteProblem("config cache read failed: " + t);
            return false;
        } finally {
            close(in);
        }
    }

    /** Each process caches in a directory it can write. */
    private static File cacheFile(Context ctx) {
        File dir = null;
        if (ctx != null) {
            try {
                dir = ctx.getFilesDir();
            } catch (Throwable ignored) {
                // fall back to the system data dir, writable by system_server
            }
        }
        if (dir == null) {
            dir = new File(FALLBACK_CACHE_DIR);
        }
        return new File(dir, CACHE_NAME);
    }

    private static void close(java.io.Closeable stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (Throwable ignored) {
                // nothing useful to do
            }
        }
    }

    private static String join(Set<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) {
                out.append(',');
            }
            out.append(value);
        }
        return out.toString();
    }

    private static Set<String> parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> out = new HashSet<>();
        for (String part : raw.split(",")) {
            String pkg = part.trim();
            if (!pkg.isEmpty()) {
                out.add(pkg);
            }
        }
        return out;
    }
}
