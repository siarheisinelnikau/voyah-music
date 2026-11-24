package app.voyahmusic.player.ui;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LightingColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;

import app.voyahmusic.shared.R;
import app.voyahmusic.shared.ui.UiKit;

import java.util.function.Consumer;

import static app.voyahmusic.player.ui.IntroPacing.Easing;
import static app.voyahmusic.player.ui.IntroPacing.clamp;
import static app.voyahmusic.player.ui.IntroPacing.ease;
import static app.voyahmusic.player.ui.IntroPacing.span;

/**
 * The startup intro of Voyah Music, in the language of the Open Voyah Controls intro: a steel mark on the dark
 * backdrop, quiet corner lights, a glint across the metal, an accent charge, a pulse and a still hold before the
 * mark leaves toward the viewer.
 *
 * <p>The mark is a record. It spins up and slows until the note on its label stands upright, while equalizer bars
 * rise on both sides like the wings of the Open Voyah Controls emblem, dance for a moment and settle into an arch.
 * A playback bar fills under the name; when it reaches the end the beat lands twice: the accent charges the steel
 * from the record outward, the mark thumps and two rings leave the record.
 *
 * <p>The intro covers the activity window from its first frame, so the player lays itself out underneath. It plays
 * once per process and only for a launch from the home screen; a tap closes it.
 */
@SuppressLint("ViewConstructor") // Created in code with the activity's run settings.
final class MusicIntro extends View {
    /** The stages in milliseconds of intro time. */
    static final class Timeline {
        private Timeline() {}

        static final float BODY_START = 120;
        static final float BODY_VISIBLE = 600;
        static final float SPIN_END = 1500;
        /** The record turns this many times before it rests. */
        static final float SPIN_TURNS = 1.25f;
        static final float BARS_START = 420;
        static final float BAR_STAGGER = 40;
        static final float BAR_RISE = 460;
        static final float BAR_VISIBLE = 220;
        static final float SPREAD_END = 980;
        static final float DANCE_START = 760;
        static final float TITLE_START = 560;
        static final float TITLE_VISIBLE = 900;
        static final float RULE_START = 700;
        static final float RULE_DRAWN = 1240;
        static final float THUMB_GONE = 1540;
        static final float CAPTION_START = 860;
        static final float CAPTION_VISIBLE = 1200;
        static final float GLINT_START = 640;
        static final float GLINT_END = 1820;
        static final float BEAT = 1260;
        static final float BEAT_SECOND = 1460;
        static final float THUMP = 220;
        static final float CHARGE_START = 1240;
        static final float CHARGE_FULL = 1420;
        static final float CHARGE_FADE = 1580;
        static final float CHARGE_END = 1980;
        /** Each bar charges this much later than the one nearer the record. */
        static final float CHARGE_STAGGER = 45;
        static final float PULSE_LENGTH = 680;
        static final float EXIT_START = 2320;
        static final float EXIT_END = 2700;
        static final float BACKDROP_START = 2540;
        static final float END = 2900;
        /** A tap fades everything out over this time. */
        static final float DISMISS = 220;
        /** A point at which nearly every part is drawn, used to prepare the drawing before the intro shows. */
        static final float WARM = 1500;
    }

    /** The extra that holds a debuggable build's intro at one point of its timeline, for screenshots. */
    static final String DEBUG_PHASE = "intro_millis";

    private static final int STEADY_FRAMES = 8;
    private static final long SETTLE_LIMIT_NANOS = 700_000_000L;
    private static final float CHARGE_DEPTH = .82f;
    private static final int WARM_LIGHT = 0xFFE1C992;

    // The mark in its own units: 160 wide and 100 tall, the record in the middle.
    private static final float WIDTH = 160f;
    private static final float HEIGHT = 100f;
    private static final float CX = 80f;
    private static final float CY = 50f;
    private static final float DISC = 38f;
    private static final float LABEL = 14.5f;
    private static final float BAR_WIDTH = 3.6f;
    private static final float BAR_FIRST = 46f;
    private static final float BAR_STEP = 6.4f;
    private static final float[] BAR_HEIGHTS = {34f, 27f, 21f, 15.5f, 11f, 7.5f};
    private static final float[] BAR_OPACITY = {1f, .92f, .84f, .76f, .68f, .6f};
    // Two detuned waves per bar make the short dance look like music rather than a pattern.
    private static final float[] DANCE_SLOW = {3.1f, 2.4f, 3.7f, 2.8f, 3.4f, 2.2f};
    private static final float[] DANCE_FAST = {5.3f, 4.6f, 6.1f, 4.9f, 5.7f, 4.2f};
    private static final float[] DANCE_PHASE = {.10f, .55f, .30f, .80f, .20f, .65f};
    private static final float[] DANCE_OFFSET = {.35f, .05f, .70f, .45f, .90f, .25f};
    // The sheen is a few nested wedges, so its edges stay soft without a sweep gradient.
    private static final float[] SHEEN_SPANS = {34f, 26f, 18f, 11f, 5f};
    private static final float[] SHEEN_OPACITY = {.26f, .26f, .26f, .26f, .2f};
    /** Room around each raster for antialiased edges, in mark units. */
    private static final float LAYER_MARGIN = .5f;
    /** Rasters are drawn a little finer than the mark, which grows by up to 7% on the beat and the exit. */
    private static final float LAYER_DETAIL = 1.08f;

    private static boolean decided;

    private final float durationScale;
    private final float heldMillis;
    private final float density;
    private final String title;
    private final String caption;

    private final int accent;
    private final int shade;
    private final int body;
    private final int light;
    private final int glowCore;
    private final int glowEdge;
    private final int vinylGroove;
    private final int glyph;
    private final ColorFilter charged;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint plain = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint captionPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Matrix matrix = new Matrix();
    private final Path note = new Path();
    private final Shader barShader;
    private final Shader vinylShader;
    private final Shader rimShader;
    private final Shader sheenShader;
    private final Shader labelShader;
    private final Shader glintShader;

    private Bitmap discLayer;
    private Bitmap rimLayer;
    private Bitmap labelLayer;
    private final Bitmap[] barLayers = new Bitmap[BAR_HEIGHTS.length];
    private Shader coolLight;
    private Shader warmShade;
    private Shader ambient;
    private Shader chargeGlow;
    private Shader shadow;
    private Shader rule;
    private Shader thumbGlow;

    private float markHeight;
    private float markScale;
    private float centerX;
    private float top;
    private float titleTop;
    private float ruleY;
    private float captionTop;
    private float ruleWidth;
    private float pivotY;
    private float discY;
    private float glowRadius;
    private float titleAscent;
    private float captionAscent;
    private String shownTitle = "";
    private String shownCaption = "";
    private float titleWidth;
    private float captionWidth;

    private final Choreographer.FrameCallback tick = this::onFrame;
    private IntroPacing.Settle settle;
    private IntroPacing.Clock clock;
    private long frameNanos = 16_666_667L;
    private long firstFrame = -1L;
    private long dismissedAt = -1L;
    private boolean dismissRequested;
    private boolean scheduled;
    private boolean finished;
    private boolean shown;
    private float introMillis;
    private float dismissFade = 1f;

    /**
     * Covers the window with the intro when this is the first launch of the player from the home screen in this
     * process, the intro is enabled and system animations are on.
     */
    static void showOnLaunch(Activity activity, Bundle saved, boolean enabled) {
        float held = heldMillis(activity);
        if (held < 0f) {
            if (decided) return;
            decided = true;
            Intent intent = activity.getIntent();
            if (saved != null || !enabled || intent == null || !Intent.ACTION_MAIN.equals(intent.getAction())) return;
        }
        float scale = Settings.Global.getFloat(activity.getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        if (held < 0f && (!ValueAnimator.areAnimatorsEnabled() || scale <= 0f)) return;
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        decor.addView(new MusicIntro(activity, Math.max(scale, .01f), held),
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private static float heldMillis(Activity activity) {
        boolean debuggable = (activity.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        Intent intent = activity.getIntent();
        return debuggable && intent != null ? intent.getFloatExtra(DEBUG_PHASE, -1f) : -1f;
    }

    private MusicIntro(Activity activity, float durationScale, float heldMillis) {
        super(activity);
        this.durationScale = durationScale;
        this.heldMillis = heldMillis;
        density = getResources().getDisplayMetrics().density;
        title = activity.getString(R.string.player_name);
        caption = activity.getString(R.string.player_intro_caption);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        // Above the bar backgrounds that the window adds later, without a shadow.
        setOutlineProvider(null);
        setTranslationZ(1_000f);

        UiKit ui = new UiKit(activity);
        accent = ui.accent;
        int steel = mix(UiKit.MUTED, ui.edge, .30f);
        shade = mix(mix(steel, UiKit.BACKGROUND, .78f), accent, .12f);
        body = mix(steel, UiKit.BACKGROUND, .32f);
        light = mix(steel, Color.WHITE, .86f);
        glowCore = mix(UiKit.BACKGROUND, accent, .30f);
        glowEdge = alpha(mix(UiKit.BACKGROUND, accent, .13f), .64f);
        vinylGroove = mix(light, accent, .15f);
        glyph = mix(UiKit.BACKGROUND, shade, .45f);
        // Multiplying by the accent keeps the bevels and shadows of the steel while it takes the accent color.
        charged = new LightingColorFilter(accent & 0xFFFFFF, 0);

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        titlePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titlePaint.setTextSize(sp(24));
        titlePaint.setLetterSpacing(.067f);
        captionPaint.setTextSize(sp(14));
        captionPaint.setLetterSpacing(.043f);

        buildNote();
        // Bars are drawn from one gradient between -1 and 1, stretched to each bar's height.
        barShader = new LinearGradient(0f, -1f, 0f, 1f,
                new int[] {tone(.95f, .80f), tone(.68f, .42f), tone(.40f, .28f), tone(.68f, .42f), tone(.92f, .74f)},
                new float[] {0f, .28f, .50f, .72f, 1f}, Shader.TileMode.CLAMP);
        // Vinyl stays nearly black so the steel rim and the label carry the light.
        int deep = mix(UiKit.BACKGROUND, Color.BLACK, .35f);
        vinylShader = new RadialGradient(CX, CY, DISC,
                new int[] {mix(UiKit.BACKGROUND, body, .22f), mix(UiKit.BACKGROUND, body, .10f), deep,
                        mix(UiKit.BACKGROUND, body, .28f)}, new float[] {0f, .40f, .88f, 1f},
                Shader.TileMode.CLAMP);
        rimShader = new LinearGradient(CX - DISC, CY - DISC, CX + DISC, CY + DISC,
                new int[] {tone(1f), tone(.78f), tone(.30f), tone(.62f)}, new float[] {0f, .32f, .70f, 1f},
                Shader.TileMode.CLAMP);
        int clear = alpha(light, 0f);
        sheenShader = new RadialGradient(CX, CY, DISC,
                new int[] {clear, clear, alpha(light, .20f), alpha(light, .11f), clear},
                new float[] {0f, .40f, .62f, .85f, 1f}, Shader.TileMode.CLAMP);
        labelShader = new RadialGradient(CX - 3f, CY - 3.5f, LABEL * 1.35f,
                new int[] {tone(1f), tone(.70f), tone(.34f)}, new float[] {0f, .55f, 1f}, Shader.TileMode.CLAMP);
        glintShader = new LinearGradient(0f, 0f, 30f, -17f,
                new int[] {Color.TRANSPARENT, mix(Color.WHITE, accent, .10f), Color.TRANSPARENT}, null,
                Shader.TileMode.CLAMP);
    }

    /** The beamed notes of the Voyah Music icon, centered on the record's label. */
    private void buildNote() {
        note.moveTo(45f, 39f);
        note.lineTo(77f, 31f);
        note.lineTo(77f, 66f);
        note.cubicTo(77f, 74f, 70f, 80f, 62f, 80f);
        note.cubicTo(55f, 80f, 51f, 76f, 51f, 71f);
        note.cubicTo(51f, 64f, 58f, 59f, 66f, 59f);
        note.cubicTo(68f, 59f, 69f, 59f, 71f, 60f);
        note.lineTo(71f, 44f);
        note.lineTo(51f, 49f);
        note.lineTo(51f, 73f);
        note.cubicTo(51f, 80f, 44f, 86f, 36f, 86f);
        note.cubicTo(29f, 86f, 25f, 82f, 25f, 77f);
        note.cubicTo(25f, 70f, 32f, 65f, 40f, 65f);
        note.cubicTo(42f, 65f, 43f, 65f, 45f, 66f);
        note.close();
        Matrix place = new Matrix();
        place.setTranslate(-51f, -58.5f);
        place.postScale(.30f, .30f);
        place.postTranslate(CX, CY);
        note.transform(place);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Display display = getDisplay();
        float refresh = display == null ? 60f : display.getRefreshRate();
        frameNanos = (long) (1_000_000_000L / (refresh >= 20f ? refresh : 60f));
        settle = new IntroPacing.Settle(frameNanos, STEADY_FRAMES);
        if (heldMillis >= 0f) introMillis = heldMillis;
        else schedule();
    }

    @Override protected void onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(tick);
        scheduled = false;
        super.onDetachedFromWindow();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        // Leaving the player while the intro plays ends it; the player is waiting underneath on return.
        if (visibility == VISIBLE) shown = true;
        else if (shown && heldMillis < 0f) post(this::finish);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) performClick();
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        if (heldMillis >= 0f) {
            post(this::finish);
        } else {
            dismissRequested = true;
            schedule();
        }
        return true;
    }

    private void schedule() {
        if (scheduled || finished) return;
        scheduled = true;
        Choreographer.getInstance().postFrameCallback(tick);
    }

    private void onFrame(long now) {
        scheduled = false;
        if (finished) return;
        if (clock == null && !dismissRequested) {
            if (firstFrame < 0) firstFrame = now;
            if (settle.onFrame(now) || now - firstFrame >= SETTLE_LIMIT_NANOS) {
                clock = new IntroPacing.Clock(frameNanos, (long) (Timeline.END * durationScale * 1_000_000L));
            }
        }
        if (clock != null) introMillis = clock.onFrame(now) / 1_000_000f / durationScale;
        float dismissed = 0f;
        if (dismissRequested) {
            if (dismissedAt < 0) dismissedAt = now;
            dismissed = (now - dismissedAt) / (Timeline.DISMISS * durationScale * 1_000_000f);
            dismissFade = 1f - Easing.STANDARD.at(clamp(dismissed));
        }
        invalidate();
        if (introMillis >= Timeline.END || dismissed >= 1f) finish();
        else schedule();
    }

    private void finish() {
        if (finished) return;
        finished = true;
        Choreographer.getInstance().removeFrameCallback(tick);
        ViewParent parent = getParent();
        if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(this);
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        float maxText = Math.max(0f, width - dp(32));
        shownTitle = TextUtils.ellipsize(title, titlePaint, maxText, TextUtils.TruncateAt.END).toString();
        shownCaption = TextUtils.ellipsize(caption, captionPaint, maxText, TextUtils.TruncateAt.END).toString();
        titleWidth = titlePaint.measureText(shownTitle);
        captionWidth = captionPaint.measureText(shownCaption);
        Paint.FontMetrics titleMetrics = titlePaint.getFontMetrics();
        Paint.FontMetrics captionMetrics = captionPaint.getFontMetrics();
        titleAscent = titleMetrics.ascent;
        captionAscent = captionMetrics.ascent;
        float titleHeight = titleMetrics.descent - titleMetrics.ascent;
        float captionHeight = captionMetrics.descent - captionMetrics.ascent;

        markHeight = Math.min(dp(150), Math.min(height * .40f, width * .30f));
        markScale = markHeight / HEIGHT;
        if (markScale > 0f) buildLayers(Math.min(markScale * LAYER_DETAIL, 6f));
        centerX = width / 2f;
        float block = markHeight + dp(18) + titleHeight + dp(14) + dp(2) + dp(12) + captionHeight;
        top = Math.max(0f, (height - block) / 2f);
        titleTop = top + markHeight + dp(18);
        ruleY = titleTop + titleHeight + dp(14);
        captionTop = ruleY + dp(2) + dp(12);
        pivotY = top + block / 2f;
        ruleWidth = Math.min(Math.max(Math.max(titleWidth, captionWidth), dp(180)), maxText);
        discY = top + CY * markScale;
        glowRadius = Math.max(1f, markHeight * 1.25f);

        int backdrop = UiKit.BACKGROUND;
        float shortEdge = Math.max(1f, Math.min(width, height));
        coolLight = new RadialGradient(width * .94f, height * .06f, shortEdge * 1.1f,
                new int[] {alpha(accent, .09f), alpha(accent, .025f), alpha(accent, 0f)}, null, Shader.TileMode.CLAMP);
        warmShade = new RadialGradient(width * .03f, height * .98f, shortEdge * .85f,
                new int[] {alpha(WARM_LIGHT, .035f), alpha(WARM_LIGHT, 0f)}, null, Shader.TileMode.CLAMP);
        ambient = new RadialGradient(centerX, discY, glowRadius,
                new int[] {glowCore, glowEdge, Color.TRANSPARENT}, null, Shader.TileMode.CLAMP);
        chargeGlow = new RadialGradient(centerX, discY, glowRadius * .9f,
                new int[] {alpha(accent, .42f), alpha(accent, 0f)}, null, Shader.TileMode.CLAMP);
        shadow = new RadialGradient(0f, 0f, 1f, new int[] {alpha(Color.BLACK, .65f), Color.TRANSPARENT}, null,
                Shader.TileMode.CLAMP);
        rule = new LinearGradient(centerX - ruleWidth / 2f, 0f, centerX + ruleWidth / 2f, 0f,
                new int[] {alpha(accent, 0f), accent, alpha(accent, 0f)}, null, Shader.TileMode.CLAMP);
        thumbGlow = new RadialGradient(0f, 0f, dp(15),
                new int[] {alpha(accent, .45f), alpha(accent, 0f)}, null, Shader.TileMode.CLAMP);
        plain.setColor(backdrop);
    }

    @Override protected void onDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        if (clock == null && heldMillis < 0f && !dismissRequested) {
            // Drawn once per frame under the opaque backdrop while frames settle, so that the first visible
            // frames do not pay for shaders, paths and text.
            drawIntro(canvas, Timeline.WARM, 1f);
            plain.setColor(UiKit.BACKGROUND);
            canvas.drawRect(0, 0, width, height, plain);
            return;
        }
        float t = introMillis;
        float cover = (1f - ease(t, Timeline.BACKDROP_START, Timeline.END)) * dismissFade;
        if (cover > 0f) {
            plain.setColor(alpha(UiKit.BACKGROUND, cover));
            canvas.drawRect(0, 0, width, height, plain);
        }
        drawIntro(canvas, t, dismissFade);
    }

    private void drawIntro(Canvas canvas, float t, float fade) {
        if (t <= Timeline.BODY_START || markScale <= 0f) return;
        float exit = ease(t, Timeline.EXIT_START, Timeline.EXIT_END);
        float out = (1f - exit) * fade;
        if (out <= 0f) return;
        float reveal = ease(t, Timeline.BODY_START, Timeline.BODY_VISIBLE);
        float charge = charge(t);
        float titleAlpha = ease(t, Timeline.TITLE_START, Timeline.TITLE_VISIBLE);
        float captionAlpha = ease(t, Timeline.CAPTION_START, Timeline.CAPTION_VISIBLE);
        float thump = .028f * bump(span(t, Timeline.BEAT, Timeline.BEAT + Timeline.THUMP))
                + .016f * bump(span(t, Timeline.BEAT_SECOND, Timeline.BEAT_SECOND + Timeline.THUMP));

        fill.setColorFilter(null);
        fill.setShader(coolLight);
        fill.setAlpha(alpha255(reveal * out));
        canvas.drawRect(0, 0, getWidth(), getHeight(), fill);
        fill.setShader(warmShade);
        canvas.drawRect(0, 0, getWidth(), getHeight(), fill);

        canvas.save();
        // The mark leaves by easing slightly toward the viewer while it fades.
        float exitScale = 1f + .045f * exit;
        canvas.scale(exitScale, exitScale, centerX, pivotY);

        fill.setShader(ambient);
        fill.setAlpha(alpha255(reveal * out));
        canvas.drawCircle(centerX, discY, glowRadius, fill);
        if (charge > 0f) {
            fill.setShader(chargeGlow);
            fill.setAlpha(alpha255(charge * out));
            canvas.drawCircle(centerX, discY, glowRadius * .9f, fill);
        }
        drawPulse(canvas, span(t, Timeline.BEAT, Timeline.BEAT + Timeline.PULSE_LENGTH), out);
        drawPulse(canvas, span(t, Timeline.BEAT_SECOND, Timeline.BEAT_SECOND + Timeline.PULSE_LENGTH), out * .7f);

        canvas.save();
        canvas.translate(centerX, top + (CY + DISC + 3f) * markScale);
        canvas.scale(markHeight * .30f, markHeight * .035f);
        fill.setShader(shadow);
        fill.setAlpha(alpha255(reveal * out));
        canvas.drawCircle(0f, 0f, 1f, fill);
        canvas.restore();

        canvas.save();
        canvas.translate(centerX - WIDTH * markScale / 2f, top);
        canvas.scale(markScale, markScale);
        canvas.scale(1f + thump, 1f + thump, CX, CY);
        float glint = t >= Timeline.GLINT_START && t <= Timeline.GLINT_END
                ? -60f + 280f * span(t, Timeline.GLINT_START, Timeline.GLINT_END) : Float.NaN;
        drawBars(canvas, t, out, thump, glint);
        if (reveal > 0f) drawRecord(canvas, t, reveal, out, charge, glint);
        canvas.restore();

        if (titleAlpha > 0f) {
            titlePaint.setColor(mix(UiKit.TEXT, accent, charge * .85f));
            titlePaint.setAlpha(alpha255(titleAlpha * out));
            canvas.drawText(shownTitle, centerX - titleWidth / 2f,
                    titleTop + dp(6) * (1f - titleAlpha) - titleAscent, titlePaint);
        }
        drawProgress(canvas, t, charge, out);
        if (captionAlpha > 0f) {
            captionPaint.setColor(UiKit.MUTED);
            captionPaint.setAlpha(alpha255(captionAlpha * out));
            canvas.drawText(shownCaption, centerX - captionWidth / 2f,
                    captionTop + dp(6) * (1f - captionAlpha) - captionAscent, captionPaint);
        }
        canvas.restore();
    }

    private void drawPulse(Canvas canvas, float pulse, float out) {
        if (pulse <= 0f || pulse >= 1f) return;
        float fade = (1f - pulse) * (1f - pulse);
        stroke.setShader(null);
        stroke.setColorFilter(null);
        stroke.setStrokeWidth(dp(1.5f));
        stroke.setColor(alpha(accent, .5f * fade * out));
        canvas.drawCircle(centerX, discY, markHeight * (.40f + .85f * Easing.STANDARD.at(pulse)), stroke);
    }

    /** The equalizer wings: bars rise outward from the record, dance, settle into an arch and kick on the beat. */
    private void drawBars(Canvas canvas, float t, float out, float thump, float glint) {
        float spread = Easing.SPRING.at(span(t, Timeline.BARS_START, Timeline.SPREAD_END));
        float dance = ease(t, Timeline.BARS_START + 200f, Timeline.DANCE_START)
                * (1f - ease(t, Timeline.DANCE_START + 120f, Timeline.BEAT)) * .42f;
        float seconds = t / 1000f;
        for (int i = 0; i < BAR_HEIGHTS.length; i++) {
            float start = Timeline.BARS_START + i * Timeline.BAR_STAGGER;
            float opacity = ease(t, start, start + Timeline.BAR_VISIBLE) * out * BAR_OPACITY[i];
            if (opacity <= 0f || barLayers[i] == null) continue;
            float rise = Easing.BACK.at(span(t, start, start + Timeline.BAR_RISE));
            double wave = .6 * Math.sin(2 * Math.PI * (DANCE_SLOW[i] * seconds + DANCE_PHASE[i]))
                    + .4 * Math.sin(2 * Math.PI * (DANCE_FAST[i] * seconds + DANCE_OFFSET[i]));
            float half = BAR_HEIGHTS[i] / 2f * rise * (1f + dance * (float) wave + thump * 4f);
            half = Math.max(half, BAR_WIDTH / 2f * Math.min(1f, rise * 4f));
            // Each bar is drawn from its own raster and stretched a little while it moves.
            float stretch = half / (BAR_HEIGHTS[i] / 2f);
            float offset = BAR_FIRST + i * BAR_STEP * (.45f + .55f * spread);
            float barCharge = charge(t - i * Timeline.CHARGE_STAGGER);
            for (int side = -1; side <= 1; side += 2) {
                float x = CX + side * offset;
                rect.set(x - BAR_WIDTH / 2f - LAYER_MARGIN, CY - (BAR_HEIGHTS[i] / 2f + LAYER_MARGIN) * stretch,
                        x + BAR_WIDTH / 2f + LAYER_MARGIN, CY + (BAR_HEIGHTS[i] / 2f + LAYER_MARGIN) * stretch);
                paintLayer(canvas, barLayers[i], rect, opacity, null);
                if (barCharge > 0f) paintLayer(canvas, barLayers[i], rect, opacity * barCharge * CHARGE_DEPTH, charged);
                if (!Float.isNaN(glint)) {
                    rect.set(x - BAR_WIDTH / 2f, CY - half, x + BAR_WIDTH / 2f, CY + half);
                    fill.setColorFilter(null);
                    fill.setShader(glint(glint));
                    fill.setAlpha(alpha255((.66f - .30f * barCharge) * opacity));
                    canvas.drawRoundRect(rect, BAR_WIDTH / 2f, BAR_WIDTH / 2f, fill);
                    fill.setShader(null);
                }
            }
        }
    }

    /** The record: vinyl with grooves and a moving sheen, a steel rim and a label that turns with the spin. */
    private void drawRecord(Canvas canvas, float t, float reveal, float out, float charge, float glint) {
        if (discLayer == null) return;
        float opacity = reveal * out;
        float spin = -360f * Timeline.SPIN_TURNS
                * (1f - Easing.DECELERATE.at(span(t, Timeline.BODY_START, Timeline.SPIN_END)));
        float grow = .86f + .14f * reveal;
        canvas.save();
        canvas.translate(0f, 6f * (1f - reveal));
        canvas.scale(grow, grow, CX, CY);

        rect.set(CX - DISC - LAYER_MARGIN, CY - DISC - LAYER_MARGIN, CX + DISC + LAYER_MARGIN, CY + DISC + LAYER_MARGIN);
        paintLayer(canvas, discLayer, rect, opacity, null);

        // The light stays where it is while the record turns under it, drifting a little with the spin.
        float sheen = -57f + .12f * spin;
        rect.set(CX - DISC + 1.2f, CY - DISC + 1.2f, CX + DISC - 1.2f, CY + DISC - 1.2f);
        fill.setColorFilter(null);
        fill.setShader(sheenShader);
        for (int k = 0; k < SHEEN_SPANS.length; k++) {
            fill.setAlpha(alpha255(opacity * SHEEN_OPACITY[k]));
            canvas.drawArc(rect, sheen - SHEEN_SPANS[k] / 2f, SHEEN_SPANS[k], true, fill);
            canvas.drawArc(rect, sheen + 180f - SHEEN_SPANS[k] / 2f, SHEEN_SPANS[k], true, fill);
        }
        fill.setShader(null);

        rect.set(CX - DISC - LAYER_MARGIN, CY - DISC - LAYER_MARGIN, CX + DISC + LAYER_MARGIN, CY + DISC + LAYER_MARGIN);
        paintLayer(canvas, rimLayer, rect, opacity, null);
        // Stop short of a full tint so the bevels of the steel stay readable under the accent.
        if (charge > 0f) paintLayer(canvas, rimLayer, rect, opacity * charge * CHARGE_DEPTH, charged);

        canvas.save();
        canvas.rotate(spin, CX, CY);
        rect.set(CX - LABEL - LAYER_MARGIN, CY - LABEL - LAYER_MARGIN, CX + LABEL + LAYER_MARGIN,
                CY + LABEL + LAYER_MARGIN);
        paintLayer(canvas, labelLayer, rect, opacity, null);
        if (charge > 0f) paintLayer(canvas, labelLayer, rect, opacity * charge * CHARGE_DEPTH, charged);
        canvas.restore();

        if (!Float.isNaN(glint)) {
            fill.setShader(glint(glint));
            fill.setAlpha(alpha255((.5f - .25f * charge) * opacity));
            canvas.drawCircle(CX, CY, DISC, fill);
            fill.setShader(null);
        }
        canvas.restore();
    }

    private void paintLayer(Canvas canvas, Bitmap layer, RectF bounds, float opacity, ColorFilter filter) {
        image.setColorFilter(filter);
        image.setAlpha(alpha255(opacity));
        canvas.drawBitmap(layer, null, bounds, image);
    }

    /**
     * Rasterizes the steel and the vinyl once at the rendering scale, as the Open Voyah Controls emblem does, so a
     * frame only moves, turns and stretches finished images instead of drawing every groove and the note again.
     */
    private void buildLayers(float scale) {
        discLayer = layer(DISC, DISC, scale, canvas -> {
            fill.setColorFilter(null);
            fill.setShader(vinylShader);
            fill.setAlpha(255);
            canvas.drawCircle(CX, CY, DISC, fill);
            fill.setShader(null);
            stroke.setShader(null);
            stroke.setColorFilter(null);
            stroke.setStrokeWidth(.32f);
            int groove = 0;
            for (float radius = LABEL + 2.2f; radius < DISC - 1.6f; radius += 1.6f, groove++) {
                stroke.setColor(alpha(vinylGroove, groove % 2 == 0 ? .09f : .045f));
                canvas.drawCircle(CX, CY, radius, stroke);
            }
            // The gaps between songs catch more light than the grooves.
            stroke.setStrokeWidth(.55f);
            stroke.setColor(alpha(vinylGroove, .14f));
            canvas.drawCircle(CX, CY, 23.6f, stroke);
            canvas.drawCircle(CX, CY, 30.4f, stroke);
        });
        rimLayer = layer(DISC, DISC, scale, canvas -> {
            stroke.setColorFilter(null);
            stroke.setShader(rimShader);
            stroke.setStrokeWidth(1.6f);
            stroke.setAlpha(255);
            canvas.drawCircle(CX, CY, DISC - .8f, stroke);
            stroke.setShader(null);
        });
        labelLayer = layer(LABEL, LABEL, scale, canvas -> {
            fill.setColorFilter(null);
            fill.setShader(labelShader);
            fill.setAlpha(255);
            canvas.drawCircle(CX, CY, LABEL, fill);
            fill.setShader(null);
            stroke.setShader(null);
            stroke.setStrokeWidth(.5f);
            stroke.setColor(tone(.30f, .8f));
            canvas.drawCircle(CX, CY, LABEL, stroke);
            // The note is pressed into the label: a light lower edge under a dark face.
            canvas.save();
            canvas.translate(.3f, .4f);
            fill.setColor(tone(1f, .55f));
            canvas.drawPath(note, fill);
            canvas.restore();
            fill.setColor(glyph);
            canvas.drawPath(note, fill);
        });
        for (int i = 0; i < BAR_HEIGHTS.length; i++) {
            float half = BAR_HEIGHTS[i] / 2f;
            barLayers[i] = layer(BAR_WIDTH / 2f, half, scale, canvas -> {
                rect.set(CX - BAR_WIDTH / 2f, CY - half, CX + BAR_WIDTH / 2f, CY + half);
                matrix.setScale(1f, half);
                matrix.postTranslate(0f, CY);
                barShader.setLocalMatrix(matrix);
                fill.setColorFilter(null);
                fill.setShader(barShader);
                fill.setAlpha(255);
                canvas.drawRoundRect(rect, BAR_WIDTH / 2f, BAR_WIDTH / 2f, fill);
                fill.setShader(null);
                stroke.setShader(null);
                stroke.setColorFilter(null);
                stroke.setStrokeWidth(.4f);
                stroke.setColor(tone(.96f, .55f));
                canvas.drawRoundRect(rect, BAR_WIDTH / 2f, BAR_WIDTH / 2f, stroke);
            });
        }
    }

    /** A raster of the part drawn by {@code paint} around the mark's center, with a margin for antialiased edges. */
    private static Bitmap layer(float halfWidth, float halfHeight, float scale, Consumer<Canvas> paint) {
        float width = 2f * (halfWidth + LAYER_MARGIN);
        float height = 2f * (halfHeight + LAYER_MARGIN);
        Bitmap bitmap = Bitmap.createBitmap(Math.max(1, (int) Math.ceil(width * scale)),
                Math.max(1, (int) Math.ceil(height * scale)), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.scale(bitmap.getWidth() / width, bitmap.getHeight() / height);
        canvas.translate(-(CX - halfWidth - LAYER_MARGIN), -(CY - halfHeight - LAYER_MARGIN));
        paint.accept(canvas);
        return bitmap;
    }

    /** The playback bar under the name: it fills from the left behind a thumb that leaves once it is full. */
    private void drawProgress(Canvas canvas, float t, float charge, float out) {
        float track = ease(t, Timeline.RULE_START, Timeline.RULE_START + 220f);
        if (track <= 0f) return;
        float left = centerX - ruleWidth / 2f;
        fill.setColorFilter(null);
        fill.setShader(null);
        fill.setColor(alpha(accent, .14f * track * out));
        canvas.drawRect(left, ruleY, left + ruleWidth, ruleY + dp(2), fill);

        float filled = Easing.STANDARD.at(span(t, Timeline.RULE_START, Timeline.RULE_DRAWN));
        float head = left + ruleWidth * filled;
        fill.setShader(rule);
        fill.setAlpha(alpha255((.55f + .45f * charge) * out));
        canvas.drawRect(left, ruleY, head, ruleY + dp(2), fill);
        fill.setShader(null);

        float thumb = track * (1f - ease(t, Timeline.RULE_DRAWN, Timeline.THUMB_GONE)) * out;
        if (thumb <= 0f) return;
        float size = .4f + .6f * (1f - ease(t, Timeline.RULE_DRAWN, Timeline.THUMB_GONE));
        canvas.save();
        canvas.translate(head, ruleY + dp(1));
        fill.setShader(thumbGlow);
        fill.setAlpha(alpha255(thumb));
        canvas.drawCircle(0f, 0f, dp(15), fill);
        fill.setShader(null);
        fill.setColor(alpha(mix(light, accent, .6f + .4f * charge), thumb));
        canvas.drawCircle(0f, 0f, dp(4.5f) * size, fill);
        canvas.restore();
    }

    private Shader glint(float x) {
        matrix.setTranslate(x, 0f);
        glintShader.setLocalMatrix(matrix);
        return glintShader;
    }

    private static float charge(float t) {
        return ease(t, Timeline.CHARGE_START, Timeline.CHARGE_FULL)
                * (1f - ease(t, Timeline.CHARGE_FADE, Timeline.CHARGE_END));
    }

    /** A quick rise and a slower fall over 0..1, for a beat. */
    static float bump(float x) {
        if (x <= 0f || x >= 1f) return 0f;
        if (x < .3f) return Easing.STANDARD.at(x / .3f);
        float fall = (1f - x) / .7f;
        return fall * fall;
    }

    /** A three-point steel ramp: level 0 is the deepest shadow and 1 the brightest edge. */
    private int tone(float level) {
        return level < .5f ? mix(shade, body, level * 2f) : mix(body, light, (level - .5f) * 2f);
    }

    private int tone(float level, float opacity) {
        return alpha(tone(level), opacity);
    }

    private float dp(float value) {
        return value * density;
    }

    private float sp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, getResources().getDisplayMetrics());
    }

    private static int alpha255(float opacity) {
        return Math.round(clamp(opacity) * 255f);
    }

    private static int alpha(int color, float opacity) {
        return (color & 0xFFFFFF) | (Math.round(clamp(opacity) * Color.alpha(color)) << 24);
    }

    static int mix(int from, int to, float fraction) {
        float f = clamp(fraction);
        return Color.argb(
                Math.round(Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * f),
                Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * f),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * f),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f));
    }
}
