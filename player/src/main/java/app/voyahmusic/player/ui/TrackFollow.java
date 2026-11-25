package app.voyahmusic.player.ui;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Decides how the track list follows the playing track, free of Android types so that it runs in JVM tests.
 *
 * <p>When the player comes on screen or a folder opens, the list shows the playing track at once if it has it; a new
 * search starts at the top. When playback moves to another track, the list scrolls to it from where it is. A track that
 * is already fully visible leaves the list where it is, and a shown track keeps one row of context above it.
 */
final class TrackFollow {
    private TrackFollow() {}

    enum Move {
        /** Leave the list where it is. */
        STAY,
        /** Show the top of the list. */
        TOP,
        /** Jump to the track unless it is fully visible, because the list has just come on screen. */
        SHOW,
        /** Scroll to the track from where the list is, unless it is fully visible. */
        FOLLOW
    }

    /**
     * How the list moves after a render. {@code opened} is the first render that knows both the library and the
     * playback after the list came on screen; the other flags say what changed since the previous render, and
     * {@code row} is the playing track's row in the list or -1.
     */
    static Move move(boolean opened, boolean newQuery, boolean newTrack, int row) {
        if (opened) return row < 0 ? Move.STAY : Move.SHOW;
        if (newQuery) return Move.TOP;
        if (newTrack && row >= 0) return Move.FOLLOW;
        return Move.STAY;
    }

    /** The row of the track with {@code id} among {@code rows}, or -1; {@code idOf} returns null for folder rows. */
    static <T> int rowOf(List<T> rows, String id, Function<T, String> idOf) {
        if (id == null || id.isEmpty()) return -1;
        for (int index = 0; index < rows.size(); index++) {
            if (Objects.equals(idOf.apply(rows.get(index)), id)) return index;
        }
        return -1;
    }

    /** The row to put at the top so that {@code row} has the row above it in view. */
    static int firstRow(int row) {
        return Math.max(0, row - 1);
    }

    /** Whether a row from {@code top} to {@code bottom} lies inside a list of {@code height} pixels. */
    static boolean fullyVisible(int top, int bottom, int height) {
        return top >= 0 && bottom <= height;
    }

    /**
     * Whether the list may move on its own: never while a finger is on it or while it scrolls under the user's
     * control or from a fling; a scroll the list started itself may be redirected.
     */
    static boolean mayMove(boolean touching, boolean scrolling, boolean ownScroll) {
        return !touching && (!scrolling || ownScroll);
    }
}
