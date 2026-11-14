package app.morphed.extension.twitch.settings;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import app.morphed.extension.twitch.patches.DebugLog;
import app.morphed.extension.twitch.unlock.QualityUnlock;

/**
 * Picks a WireGuard config file (a VPN provider's .conf) and adds it to the VPN servers. Has no screen of its own:
 * it opens the system's file picker and closes once the file is read. Declared in the app's manifest by the patch.
 */
public final class VpnImportActivity extends Activity {
    private static final int PICK = 1;
    private static final int MAX_BYTES = 64 * 1024;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved != null) return; // the picker is open already
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("*/*");
        try {
            startActivityForResult(pick, PICK);
        } catch (Exception e) {
            finishWith(null, "The file picker couldn't be opened: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK) return;
        Uri uri = data == null ? null : data.getData();
        if (resultCode != RESULT_OK || uri == null) {
            finish(); // nothing picked
            return;
        }
        String name = displayName(uri);
        try {
            String text = read(uri);
            String kept = QualityUnlock.servers().add(name, text);
            QualityUnlock.serverAdded();
            DebugLog.log("VPN servers: added " + kept + " from a file");
            finishWith(kept, null);
        } catch (Exception e) {
            finishWith(null, "Not added: " + e.getMessage());
        }
    }

    private void finishWith(String added, String problem) {
        VpnServersPage.added(added, problem);
        Toast.makeText(this, problem != null ? problem : "Added " + added, Toast.LENGTH_LONG).show();
        finish();
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.isEmpty()) return name;
            }
        } catch (Exception ignored) {
            // the name is only for showing: a plain one below
        }
        return "server";
    }

    private String read(Uri uri) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("the file couldn't be opened");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() > MAX_BYTES) throw new IOException("the file is too big to be a WireGuard config");
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
