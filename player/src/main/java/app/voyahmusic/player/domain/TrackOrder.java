package app.voyahmusic.player.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** The orders the library lists its tracks in. Playback follows the same order, so next plays the row below. */
public enum TrackOrder {
    TITLE("title"), ARTIST("artist"), ALBUM("album"), ADDED("added");

    /** The value kept in the preferences. */
    public final String key;

    TrackOrder(String key) {
        this.key = key;
    }

    public static TrackOrder parse(String key) {
        for (TrackOrder order : values()) {
            if (order.key.equals(key)) return order;
        }
        return TITLE;
    }

    /** Returns the tracks in this order; ties fall back to the title and then to the track's stable id. */
    public List<Track> sort(List<Track> tracks) {
        Comparator<Track> byTitle = Comparator.comparing(Track::title, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Track::id);
        Comparator<Track> order = switch (this) {
            case TITLE -> byTitle;
            case ARTIST -> tag(Track::artist).thenComparing(tag(Track::album)).thenComparing(byTitle);
            case ALBUM -> tag(Track::album).thenComparing(byTitle);
            case ADDED -> {
                // A track's file is written once when it arrives, so its time is when it was added.
                Map<String, Long> added = new HashMap<>();
                for (Track track : tracks) added.put(track.id(), track.file().lastModified());
                yield Comparator.comparingLong((Track track) -> added.get(track.id())).reversed().thenComparing(byTitle);
            }
        };
        List<Track> sorted = new ArrayList<>(tracks);
        sorted.sort(order);
        return Collections.unmodifiableList(sorted);
    }

    /** Orders by a tag, with untagged tracks after the tagged ones. */
    private static Comparator<Track> tag(Function<Track, String> field) {
        return Comparator.<Track, Boolean>comparing(track -> field.apply(track).isEmpty())
                .thenComparing(field, String.CASE_INSENSITIVE_ORDER);
    }
}
