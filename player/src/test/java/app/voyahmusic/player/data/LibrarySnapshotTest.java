package app.voyahmusic.player.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import app.voyahmusic.player.domain.Track;
import app.voyahmusic.shared.lan.MusicWire.Folder;

import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class LibrarySnapshotTest {
    private static Track track(String id) {
        return new Track(id, new File(id + ".mp3"), id, "", "", 0);
    }

    private static String ids(List<Track> tracks) {
        return tracks.stream().map(Track::id).collect(Collectors.joining(","));
    }

    private static MusicLibrary.Snapshot library() {
        List<Track> tracks = Arrays.asList(track("a"), track("b"), track("c"), track("d"), track("e"));
        Map<String, Long> locations = new HashMap<>();
        locations.put("b", 1L);
        locations.put("d", 1L);
        locations.put("e", 2L);
        return new MusicLibrary.Snapshot(tracks, Arrays.asList(new Folder(1, "Chill"), new Folder(2, "Road Trip"),
                new Folder(3, "Empty")), locations);
    }

    @Test
    public void aFolderHoldsItsTracksInLibraryOrder() {
        assertEquals("b,d", ids(library().tracksIn(1)));
        assertEquals("e", ids(library().tracksIn(2)));
    }

    @Test
    public void anEmptyFolderHoldsNothing() {
        assertTrue(library().tracksIn(3).isEmpty());
    }

    @Test
    public void tracksOutsideFoldersBelongToFolderZero() {
        assertEquals("a,c", ids(library().tracksIn(0)));
    }

    @Test
    public void everyTrackBelongsToExactlyOneFolder() {
        MusicLibrary.Snapshot library = library();
        int total = library.tracksIn(0).size() + library.tracksIn(1).size() + library.tracksIn(2).size()
                + library.tracksIn(3).size();
        assertEquals(library.tracks().size(), total);
    }
}
