package io.cleanyourtv.ui;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.Process;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Where launcher-ui/config.sh drops config.json, remote.json and logo PNGs:
 * `content write --uri content://io.cleanyourtv.ui.config/<name>`. Only the ADB shell (and root) may write or delete;
 * the files land in the app's private folder, where no other app can change them.
 */
public class ConfigProvider extends ContentProvider {
    private static final int SHELL_UID = 2000;

    @Override public boolean onCreate() { return true; }

    private static void checkCaller() {
        int uid = Binder.getCallingUid();
        if (uid != SHELL_UID && uid != 0 && uid != Process.myUid()) throw new SecurityException("only the ADB shell can push files");
    }

    private File file(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || !TvConfig.allowedName(name)) throw new FileNotFoundException("not a config file: " + name);
        File dir = TvConfig.dir(getContext());
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, name);
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        checkCaller();
        // Read-only: the settings as changed on the TV, so the computer can keep its config.json in step
        // (`content read --uri content://io.cleanyourtv.ui.config/settings.xml`).
        if ("settings.xml".equals(uri.getLastPathSegment()) && !mode.contains("w")) {
            File prefs = new File(getContext().getApplicationInfo().dataDir, "shared_prefs/settings.xml");
            return ParcelFileDescriptor.open(prefs, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        File f = file(uri);
        int flags = mode.contains("w")
                ? ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE
                : ParcelFileDescriptor.MODE_READ_ONLY;
        return ParcelFileDescriptor.open(f, flags);
    }

    @Override public int delete(Uri uri, String selection, String[] args) {
        checkCaller();
        try {
            return file(uri).delete() ? 1 : 0;
        } catch (FileNotFoundException e) {
            return 0;
        }
    }

    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int update(Uri uri, ContentValues values, String s, String[] a) { return 0; }
}
