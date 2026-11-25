package app.voyahmusic.player.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PlayerStageTest {
    @Test
    public void drawsWhilePlaybackIsWanted() {
        assertTrue(PlayerStage.drawing(true, true, "spectrum"));
        assertTrue(PlayerStage.drawing(true, true, "wave"));
    }

    @Test
    public void staysOnThroughTheBufferingBetweenTracks() {
        // Between two tracks the decoder stops for a moment while playback is still wanted.
        assertTrue(PlayerStage.drawing(true, true, "spectrum"));
    }

    @Test
    public void hidesWhenPausedOffOrDisconnected() {
        assertFalse(PlayerStage.drawing(true, false, "spectrum"));
        assertFalse(PlayerStage.drawing(true, true, "off"));
        assertFalse(PlayerStage.drawing(false, true, "spectrum"));
    }
}
