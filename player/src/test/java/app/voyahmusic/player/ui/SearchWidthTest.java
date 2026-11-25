package app.voyahmusic.player.ui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SearchWidthTest {
    private static int open(int title, int text) {
        return SearchWidth.open(72, title, text, 18, 330, 540);
    }

    @Test
    public void aWideHeaderGivesTheLargestField() {
        assertEquals(540, open(900, 130));
    }

    @Test
    public void theWholeTitleStaysReadableWhileTheFieldIsLargeEnough() {
        // 72 + 600 - 130 - 18 leaves 524 for the field.
        assertEquals(524, open(600, 130));
    }

    @Test
    public void theTitleGivesWayBelowTheSmallestField() {
        assertEquals(330, open(320, 130));
    }

    @Test
    public void theFieldNeverReachesPastTheTitle() {
        assertEquals(272, open(200, 130));
        assertEquals(72, open(0, 130));
        assertEquals(72, open(-5, 130));
    }
}
