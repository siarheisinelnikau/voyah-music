package app.voyahmusic.player.domain;

import static org.junit.Assert.assertEquals;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class TrackOrderTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private Track track(String id, String title, String artist, String album, long addedAt) throws IOException {
        File file = folder.newFile(id + ".mp3");
        file.setLastModified(addedAt);
        return new Track(id, file, title, artist, album, 0);
    }

    private static String ids(List<Track> tracks) {
        return tracks.stream().map(Track::id).collect(Collectors.joining(","));
    }

    @Test public void titlesSortWithoutCaseAndTiesKeepAStableOrder() throws IOException {
        List<Track> tracks = Arrays.asList(
                track("c", "beta", "", "", 1_000), track("a", "Alpha", "", "", 2_000), track("b", "beta", "", "", 3_000));
        assertEquals("a,b,c", ids(TrackOrder.TITLE.sort(tracks)));
    }

    @Test public void artistsGroupTheirAlbumsAndUntaggedTracksGoLast() throws IOException {
        List<Track> tracks = Arrays.asList(
                track("1", "Song", "", "", 1_000),
                track("2", "Zebra", "abba", "Gold", 1_000),
                track("3", "Apple", "Abba", "Arrival", 1_000),
                track("4", "Middle", "Muse", "Drones", 1_000));
        assertEquals("3,2,4,1", ids(TrackOrder.ARTIST.sort(tracks)));
        assertEquals("3,4,2,1", ids(TrackOrder.ALBUM.sort(tracks)));
    }

    @Test public void recentlyAddedTracksComeFirst() throws IOException {
        List<Track> tracks = Arrays.asList(
                track("old", "A", "", "", 1_000_000), track("new", "B", "", "", 3_000_000),
                track("mid", "C", "", "", 2_000_000));
        assertEquals("new,mid,old", ids(TrackOrder.ADDED.sort(tracks)));
    }

    @Test public void unknownKeysFallBackToTitle() {
        assertEquals(TrackOrder.TITLE, TrackOrder.parse(""));
        assertEquals(TrackOrder.TITLE, TrackOrder.parse("shuffle"));
        for (TrackOrder order : TrackOrder.values()) assertEquals(order, TrackOrder.parse(order.key));
    }
}
