package app.voyahmusic.player.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class IntroPacingTest {
    private static final long FRAME = 16_666_667L;

    @Test public void easingStartsAndEndsInPlaceAndStandardNeverOvershoots() {
        for (IntroPacing.Easing easing : new IntroPacing.Easing[] {
                IntroPacing.Easing.STANDARD, IntroPacing.Easing.DECELERATE, IntroPacing.Easing.SPRING,
                IntroPacing.Easing.BACK, IntroPacing.Easing.ACCELERATE}) {
            assertEquals(0f, easing.at(0f), 0f);
            assertEquals(1f, easing.at(1f), 0f);
            assertEquals(0f, easing.at(-1f), 0f);
            assertEquals(1f, easing.at(2f), 0f);
        }
        float previous = 0f;
        for (int step = 1; step <= 100; step++) {
            float value = IntroPacing.Easing.STANDARD.at(step / 100f);
            assertTrue(value >= previous);
            assertTrue(value <= 1f);
            previous = value;
        }
        assertEquals(.5f, new IntroPacing.Easing(.25f, .25f, .75f, .75f).at(.5f), 1e-4f);
    }

    @Test public void overshootingCurvesPeakOnceAndSettle() {
        assertPeak(IntroPacing.Easing.SPRING, 1.005f, 1.02f);
        assertPeak(IntroPacing.Easing.BACK, 1.07f, 1.13f);
    }

    private static void assertPeak(IntroPacing.Easing easing, float low, float high) {
        float peak = 0f;
        int peakStep = 0;
        for (int step = 0; step <= 200; step++) {
            float value = easing.at(step / 200f);
            if (value > peak) {
                peak = value;
                peakStep = step;
            }
        }
        assertTrue(peak > low);
        assertTrue(peak < high);
        // After the peak the curve only falls back toward its end.
        for (int step = peakStep + 1; step <= 200; step++) assertTrue(easing.at(step / 200f) <= peak);
    }

    @Test public void theFirstStartAndEveryReturnFromOutsideTheAppAreArrivals() {
        IntroPacing.Arrivals arrivals = new IntroPacing.Arrivals();
        assertTrue(arrivals.onStart());
        // Another app or the home screen covers the activity.
        arrivals.onStop(false);
        assertTrue(arrivals.onStart());
        arrivals.onStop(false);
        assertTrue(arrivals.onStart());
        // A start without a stop in between brings nothing back.
        assertFalse(arrivals.onStart());
    }

    @Test public void configurationChangesAndOwnScreensAreNotArrivals() {
        IntroPacing.Arrivals arrivals = new IntroPacing.Arrivals();
        assertTrue(arrivals.onStart());
        arrivals.onStop(true);
        assertFalse(arrivals.onStart());
        arrivals.handOff();
        arrivals.onStop(false);
        assertFalse(arrivals.onStart());
        // The hand-off covers one visit only; leaving for the home screen afterwards counts again.
        arrivals.onStop(false);
        assertTrue(arrivals.onStart());
    }

    @Test public void accelerateLeavesFasterThanItStarts() {
        float early = IntroPacing.Easing.ACCELERATE.at(.25f);
        float late = 1f - IntroPacing.Easing.ACCELERATE.at(.75f);
        assertTrue(early < .25f);
        assertTrue(late < .75f - .25f);
        float previous = 0f;
        for (int step = 1; step <= 100; step++) {
            float value = IntroPacing.Easing.ACCELERATE.at(step / 100f);
            assertTrue(value >= previous);
            previous = value;
        }
    }

    @Test public void spanClampsToTheStage() {
        assertEquals(0f, IntroPacing.span(100f, 200f, 300f), 0f);
        assertEquals(.5f, IntroPacing.span(250f, 200f, 300f), 1e-6f);
        assertEquals(1f, IntroPacing.span(400f, 200f, 300f), 0f);
        assertEquals(1f, IntroPacing.span(300f, 300f, 300f), 0f);
    }

    @Test public void settleWaitsForSteadyFramesAndRestartsAfterAStall() {
        IntroPacing.Settle settle = new IntroPacing.Settle(FRAME, 3);
        long time = 0L;
        assertFalse(settle.onFrame(time));
        assertFalse(settle.onFrame(time += FRAME));
        assertFalse(settle.onFrame(time += 2 * FRAME));
        assertFalse(settle.onFrame(time += 6 * FRAME));
        assertFalse(settle.onFrame(time += FRAME));
        assertFalse(settle.onFrame(time += FRAME));
        assertTrue(settle.onFrame(time + FRAME));
    }

    @Test public void clockTurnsAStallIntoAShortPause() {
        IntroPacing.Clock clock = new IntroPacing.Clock(FRAME, 3_000_000_000L);
        assertEquals(0L, clock.onFrame(5_000L));
        assertEquals(FRAME, clock.onFrame(5_000L + FRAME));
        // Half a second without frames moves the intro on by two frames only.
        assertEquals(3 * FRAME, clock.onFrame(5_000L + FRAME + 500_000_000L));
    }

    @Test public void clockNeverFallsMoreThanHalfThePlanBehind() {
        long planned = 1_000_000_000L;
        IntroPacing.Clock clock = new IntroPacing.Clock(FRAME, planned);
        clock.onFrame(0L);
        long intro = 0L;
        for (long time = 100_000_000L; time <= 2_000_000_000L; time += 100_000_000L) intro = clock.onFrame(time);
        assertEquals(2_000_000_000L - planned / 2, intro);
    }

    @Test public void musicStagesPlayInOrderAndTheEntranceEndsBeforeTheHold() {
        assertTrue(MusicIntro.Timeline.BODY_START < MusicIntro.Timeline.BODY_VISIBLE);
        // The playback bar fills up to the first beat, and the charge lands with it.
        assertTrue(Math.abs(MusicIntro.Timeline.RULE_DRAWN - MusicIntro.Timeline.BEAT) <= 40f);
        assertTrue(Math.abs(MusicIntro.Timeline.CHARGE_START - MusicIntro.Timeline.BEAT) <= 40f);
        assertTrue(MusicIntro.Timeline.BEAT < MusicIntro.Timeline.BEAT_SECOND);
        assertTrue(MusicIntro.Timeline.SPREAD_END < MusicIntro.Timeline.BEAT);
        float lastBar = MusicIntro.Timeline.BARS_START + 5 * MusicIntro.Timeline.BAR_STAGGER
                + MusicIntro.Timeline.BAR_RISE;
        assertTrue(lastBar < MusicIntro.Timeline.BEAT);
        float charged = MusicIntro.Timeline.CHARGE_END + 5 * MusicIntro.Timeline.CHARGE_STAGGER;
        float pulses = MusicIntro.Timeline.BEAT_SECOND + MusicIntro.Timeline.PULSE_LENGTH;
        float settled = Math.max(MusicIntro.Timeline.SPIN_END, MusicIntro.Timeline.GLINT_END);
        assertTrue(Math.max(charged, Math.max(pulses, settled)) < MusicIntro.Timeline.EXIT_START);
        // The iris opens while the mark flies off, and the whole intro stays short enough to play on every return.
        assertTrue(MusicIntro.Timeline.EXIT_START < MusicIntro.Timeline.OPEN_START);
        assertTrue(MusicIntro.Timeline.OPEN_START < MusicIntro.Timeline.EXIT_END);
        assertTrue(MusicIntro.Timeline.EXIT_END < MusicIntro.Timeline.END);
        assertTrue(MusicIntro.Timeline.END <= 1_800f);
        // The dance starts once the bars have risen, and the thumb is still on the bar at the warm-up point.
        assertTrue(MusicIntro.Timeline.BARS_START + 120f < MusicIntro.Timeline.DANCE_START);
        assertTrue(MusicIntro.Timeline.RULE_DRAWN < MusicIntro.Timeline.WARM);
        assertTrue(MusicIntro.Timeline.WARM < MusicIntro.Timeline.THUMB_GONE);
    }

    @Test public void aBeatRisesQuicklyAndFadesOut() {
        assertEquals(0f, MusicIntro.bump(0f), 0f);
        assertEquals(1f, MusicIntro.bump(.3f), 1e-4f);
        assertTrue(MusicIntro.bump(.15f) > MusicIntro.bump(.65f));
        assertEquals(0f, MusicIntro.bump(1f), 0f);
    }
}
