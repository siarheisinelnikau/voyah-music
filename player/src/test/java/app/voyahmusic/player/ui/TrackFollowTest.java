package app.voyahmusic.player.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import org.junit.Test;

public class TrackFollowTest {
    private static final Function<String, String> TRACKS_ONLY = row -> row.startsWith("folder:") ? null : row;

    @Test
    public void opensOnThePlayingTrack() {
        assertEquals(TrackFollow.Move.SHOW, TrackFollow.move(true, false, false, 7));
        assertEquals(TrackFollow.Move.SHOW, TrackFollow.move(true, true, true, 0));
    }

    @Test
    public void opensWhereItIsWithoutThePlayingTrack() {
        assertEquals(TrackFollow.Move.STAY, TrackFollow.move(true, false, false, -1));
        assertEquals(TrackFollow.Move.STAY, TrackFollow.move(true, false, true, -1));
    }

    @Test
    public void aNewSearchStartsAtTheTop() {
        assertEquals(TrackFollow.Move.TOP, TrackFollow.move(false, true, false, 4));
        assertEquals(TrackFollow.Move.TOP, TrackFollow.move(false, true, true, 4));
        assertEquals(TrackFollow.Move.TOP, TrackFollow.move(false, true, false, -1));
    }

    @Test
    public void aNewTrackIsFollowedOnlyWhenTheListHasIt() {
        assertEquals(TrackFollow.Move.FOLLOW, TrackFollow.move(false, false, true, 12));
        assertEquals(TrackFollow.Move.STAY, TrackFollow.move(false, false, true, -1));
        assertEquals(TrackFollow.Move.STAY, TrackFollow.move(false, false, false, 12));
    }

    @Test
    public void rowsSkipFoldersAndMissingTracks() {
        List<String> rows = Arrays.asList("folder:1", "folder:2", "a", "b", "c");
        assertEquals(2, TrackFollow.rowOf(rows, "a", TRACKS_ONLY));
        assertEquals(4, TrackFollow.rowOf(rows, "c", TRACKS_ONLY));
        assertEquals(-1, TrackFollow.rowOf(rows, "z", TRACKS_ONLY));
        assertEquals(-1, TrackFollow.rowOf(rows, "", TRACKS_ONLY));
        assertEquals(-1, TrackFollow.rowOf(rows, null, TRACKS_ONLY));
        assertEquals(-1, TrackFollow.rowOf(Collections.<String>emptyList(), "a", TRACKS_ONLY));
    }

    @Test
    public void aShownTrackKeepsTheRowAboveInView() {
        assertEquals(0, TrackFollow.firstRow(0));
        assertEquals(0, TrackFollow.firstRow(1));
        assertEquals(5, TrackFollow.firstRow(6));
    }

    @Test
    public void onlyARowInsideTheListIsFullyVisible() {
        assertTrue(TrackFollow.fullyVisible(0, 93, 400));
        assertTrue(TrackFollow.fullyVisible(307, 400, 400));
        assertFalse(TrackFollow.fullyVisible(-1, 92, 400));
        assertFalse(TrackFollow.fullyVisible(310, 401, 400));
    }

    @Test
    public void theListNeverMovesUnderTheUser() {
        assertTrue(TrackFollow.mayMove(false, false, false));
        assertFalse(TrackFollow.mayMove(true, false, false));
        assertFalse(TrackFollow.mayMove(false, true, false));
        assertTrue(TrackFollow.mayMove(false, true, true));
        assertFalse(TrackFollow.mayMove(true, true, true));
    }
}
