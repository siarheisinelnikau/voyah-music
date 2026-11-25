package app.voyahmusic.player.ui;

/** Decides when the player draws the music in place of the emblem, free of Android types for JVM tests. */
final class PlayerStage {
    private PlayerStage() {}

    /**
     * Whether the visualization shows. It follows the wish to play rather than the decoder, so the short buffering
     * between two tracks keeps it on screen instead of flashing the emblem or, in the compact layout, folding its strip
     * away and moving the controls; pausing, an error or the end of the queue still hides it.
     */
    static boolean drawing(boolean connected, boolean wantsPlay, String mode) {
        return connected && wantsPlay && !"off".equals(mode);
    }
}
