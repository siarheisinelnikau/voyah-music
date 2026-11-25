package app.voyahmusic.player.ui;

/** Sizes the search field that opens from the library header's search button, free of Android types for JVM tests. */
final class SearchWidth {
    private SearchWidth() {}

    /**
     * The width of the open field, which grows leftward from the button over the library title. It leaves the whole
     * title readable when that still gives the field at least {@code min}, is never wider than {@code max}, and never
     * reaches past the start of the title: on a narrow header the title gives way and ends in an ellipsis.
     *
     * @param button the width of the closed search button
     * @param title the width of the title's space next to the button
     * @param text the width of the title's text
     * @param gap the space kept between the title's text and the field
     */
    static int open(int button, int title, int text, int gap, int min, int max) {
        int room = button + Math.max(0, title);
        int spare = room - text - gap;
        return Math.max(button, Math.min(room, Math.max(min, Math.min(spare, max))));
    }
}
