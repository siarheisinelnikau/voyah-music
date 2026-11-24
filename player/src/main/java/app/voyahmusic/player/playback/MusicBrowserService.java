package app.voyahmusic.player.playback;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.browse.MediaBrowser;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.service.media.MediaBrowserService;

import androidx.media3.common.util.UnstableApi;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hands the player's media session to a media widget, such as the head unit's home screen media card (the launcher,
 * which Open Voyah Controls extends with a Voyah Music source). Only system apps may connect. On connection the player
 * loads the library, so the widget shows the last track; such a widget may start it with the player closed
 * (see {@link #isClient}).
 */
@UnstableApi
public final class MusicBrowserService extends MediaBrowserService {
    private static final String ROOT = "voyah_music";
    private static final Set<String> CLIENTS = ConcurrentHashMap.newKeySet();
    private boolean bound;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            // The playback service lives as long as this binding, so its session token stays valid.
            PlaybackService playback = ((PlaybackService.LocalBinder) binder).service();
            if (getSessionToken() == null) setSessionToken(playback.sessionToken());
            playback.prepareLibrary(() -> { });
        }

        @Override public void onServiceDisconnected(ComponentName name) {}
    };

    /** Whether this caller connected as a media browser; such a widget may start playback in the background. */
    static boolean isClient(String packageName, int uid) {
        return packageName != null && CLIENTS.contains(key(packageName, uid));
    }

    private static String key(String packageName, int uid) {
        return uid + "/" + packageName;
    }

    @Override public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        if (clientUid != Process.SYSTEM_UID) return null;
        CLIENTS.add(key(clientPackageName, clientUid));
        // The player starts only for an accepted client; Android holds the connection until the token is set.
        if (!bound) bound = bindService(new Intent(this, PlaybackService.class), connection, BIND_AUTO_CREATE);
        return new BrowserRoot(ROOT, null);
    }

    @Override public void onLoadChildren(String parentId, Result<List<MediaBrowser.MediaItem>> result) {
        // The widget only needs the session; tracks are chosen in the player.
        result.sendResult(Collections.emptyList());
    }

    @Override public void onDestroy() {
        if (bound) unbindService(connection);
        bound = false;
        super.onDestroy();
    }
}
