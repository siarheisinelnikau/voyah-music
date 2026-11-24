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

/**
 * Hands the player's media session to a media widget, such as the head unit's home screen media card (the launcher,
 * which Open Voyah Controls extends with a Voyah Music source). Only system apps may connect. On connection the player
 * loads the library, so the widget shows the last track and can start it with the player closed.
 */
@UnstableApi
public final class MusicBrowserService extends MediaBrowserService {
    private static final String ROOT = "voyah_music";
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

    @Override public void onCreate() {
        super.onCreate();
        bound = bindService(new Intent(this, PlaybackService.class), connection, BIND_AUTO_CREATE);
    }

    @Override public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        return clientUid == Process.SYSTEM_UID ? new BrowserRoot(ROOT, null) : null;
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
