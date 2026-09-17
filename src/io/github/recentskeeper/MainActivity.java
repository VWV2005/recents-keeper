package io.github.recentskeeper;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Picks the packages that survive a swipe or a one-key clean-up. */
public class MainActivity extends Activity {

    private final List<String> packages = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();

    private ListView list;
    private CheckBox invert;
    private CheckBox mode;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.main);

        list = findViewById(R.id.apps);
        invert = findViewById(R.id.invert);
        mode = findViewById(R.id.mode);
        list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);

        SharedPreferences sp = getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE);
        Set<String> saved = splitPkgs(sp.getString(Config.KEY_PKGS, ""));
        invert.setChecked(sp.getBoolean(Config.KEY_INVERT, false));
        mode.setChecked(sp.getInt(Config.KEY_MODE, Config.MODE_SYSTEM) == Config.MODE_SYSTEM);

        loadApps();
        list.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_multiple_choice, labels));
        for (int i = 0; i < packages.size(); i++) {
            list.setItemChecked(i, saved.contains(packages.get(i)));
        }

        findViewById(R.id.save).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
        findViewById(R.id.restart).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                restartLauncher();
            }
        });
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = pm.queryIntentActivities(main, 0);

        final Collator collator = Collator.getInstance();
        List<String[]> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo info : resolved) {
            String pkg = info.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || !seen.add(pkg)) {
                continue;
            }
            rows.add(new String[]{pkg, String.valueOf(info.loadLabel(pm))});
        }
        Collections.sort(rows, new Comparator<String[]>() {
            @Override
            public int compare(String[] a, String[] b) {
                return collator.compare(a[1], b[1]);
            }
        });
        for (String[] row : rows) {
            packages.add(row[0]);
            labels.add(row[1] + "\n" + row[0]);
        }
    }

    private void save() {
        StringBuilder csv = new StringBuilder();
        int count = 0;
        for (int i = 0; i < packages.size(); i++) {
            if (!list.isItemChecked(i)) {
                continue;
            }
            if (count > 0) {
                csv.append(',');
            }
            csv.append(packages.get(i));
            count++;
        }
        boolean inverted = invert.isChecked();
        int selectedMode = mode.isChecked() ? Config.MODE_SYSTEM : Config.MODE_LAUNCHER;
        getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE).edit()
                .putString(Config.KEY_PKGS, csv.toString())
                .putBoolean(Config.KEY_INVERT, inverted)
                .putInt(Config.KEY_MODE, selectedMode)
                .apply();
        publishToSettings(selectedMode + "|" + inverted + "|" + csv);
        Toast.makeText(this, getString(R.string.saved, count), Toast.LENGTH_SHORT).show();
    }

    /**
     * Mirrors the config into Settings.System, which the injected processes can
     * read with no permission and even when this app is not running.
     */
    private void publishToSettings(String value) {
        try {
            if (!Settings.System.canWrite(this)) {
                Toast.makeText(this, R.string.need_write_settings, Toast.LENGTH_LONG).show();
                return;
            }
            Settings.System.putString(getContentResolver(), Config.SETTINGS_KEY, value);
        } catch (Throwable t) {
            Toast.makeText(this, R.string.need_write_settings, Toast.LENGTH_LONG).show();
        }
    }

    private void restartLauncher() {
        try {
            Process process = Runtime.getRuntime()
                    .exec(new String[]{"su", "-c", "am force-stop " + Config.LAUNCHER_PKG});
            int code = process.waitFor();
            Toast.makeText(this,
                    getString(code == 0 ? R.string.restart_done : R.string.restart_failed),
                    Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, getString(R.string.restart_failed), Toast.LENGTH_SHORT).show();
        }
    }

    private static Set<String> splitPkgs(String raw) {
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
