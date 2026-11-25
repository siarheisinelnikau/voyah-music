package app.voyahmusic.player.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.RippleDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;

import app.voyahmusic.player.PlayerApp;
import app.voyahmusic.player.data.MusicLibrary;
import app.voyahmusic.player.data.TrustedPhones;
import app.voyahmusic.player.domain.Track;
import app.voyahmusic.player.domain.TrackOrder;
import app.voyahmusic.player.playback.PlaybackService;
import app.voyahmusic.shared.R;
import app.voyahmusic.shared.lan.MusicWire;
import app.voyahmusic.shared.lan.MusicWire.Folder;
import app.voyahmusic.shared.lan.PairQr;
import app.voyahmusic.shared.lan.WifiLink;
import app.voyahmusic.shared.ui.MusicActivity;
import app.voyahmusic.shared.ui.UiKit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static app.voyahmusic.shared.ui.UiKit.MATCH;
import static app.voyahmusic.shared.ui.UiKit.WRAP;

@UnstableApi
public final class PlayerActivity extends MusicActivity {
    /** How long the header's search button takes to open into the field or close back; Voyah Radio uses the same. */
    private static final long SEARCH_MILLIS = 250;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable playbackChanged = this::renderPlayback;
    private final Runnable tick = this::tick;
    private final ConnectivityManager.NetworkCallback wifi = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) { if (visible) model.wifiChanged(); }
        @Override public void onLost(Network network) { if (visible) model.wifiChanged(); }
    };
    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            playback = ((PlaybackService.LocalBinder) binder).service();
            playback.observe(playbackChanged);
            if (tracks != null) playback.setTracks(tracks);
            renderPlayback();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            playback = null;
            renderPlayback();
        }
    };
    private PlayerViewModel model;
    private PlaybackService playback;
    private List<Track> tracks;
    private MusicLibrary.Snapshot librarySnapshot;
    private long folderId;
    private boolean bound, visible, receiving, dragging;
    private LinearLayout receiveBody;
    private TextView title, artist, elapsed, total, problem, emptyTitle, emptyHint, libraryTitle;
    private ImageButton previous, play, next, shuffle, repeat;
    private Button receiveButton;
    private SeekBar seek;
    private ImageButton sortButton;
    private MusicVisualizer visualizer;
    private FrameLayout stage;
    private View emblem;
    private boolean compactStage;
    private TrackAdapter adapter;
    private ListView list;
    private LinearLayout mainRoot;
    private FrameLayout searchBox;
    private EditText searchField;
    private ImageButton searchClose;
    private ValueAnimator searchMotion;
    private boolean searchOpen;
    private boolean followOnOpen, listTouched, listScrolling, ownScroll;
    private String query = "", highlighted = "", followedQuery = "";
    private PairQr lastQr;
    private Bitmap qrBitmap;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        model = new PlayerViewModel(this, ((PlayerApp) getApplication()).library());
        if (saved != null) {
            query = saved.getString("query", "");
            receiving = saved.getBoolean("receiving");
            folderId = saved.getLong("folder");
        }
        renderRoute();
    }

    @Override
    protected boolean hasStartupAnimation() {
        return true;
    }

    @Override
    protected boolean hasVisualization() {
        return true;
    }

    @Override
    protected void renderRoute() {
        if (receiving) showReceiver();
        else super.renderRoute();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("receiving", receiving);
        state.putString("query", query);
        state.putLong("folder", folderId);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onStart() {
        super.onStart();
        MusicIntro.onActivityStart(this, prefs.startupAnimation());
        visible = true;
        followOnOpen = true;
        model.observe(this::tracks, this::renderReceiver);
        model.refresh();
        model.setPairingVisible(receiving);
        model.startReceiving(null);
        getSystemService(ConnectivityManager.class).registerNetworkCallback(
                new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifi, main);
        bound = bindService(new Intent(this, PlaybackService.class), connection, BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        visible = false;
        if (visualizer != null) visualizer.update(null, "off", false, false);
        getSystemService(ConnectivityManager.class).unregisterNetworkCallback(wifi);
        main.removeCallbacks(tick);
        model.observe(null, null);
        model.stopReceiving();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (playback != null) playback.removeObserver(playbackChanged);
        if (bound) unbindService(connection);
        bound = false;
        playback = null;
        MusicIntro.onActivityStop(this);
        super.onStop();
    }

    @Override
    public void startActivityForResult(Intent intent, int requestCode, Bundle options) {
        // The installer and other screens opened from here belong to this visit, so coming back skips the intro.
        MusicIntro.onOwnScreenOpened();
        super.startActivityForResult(intent, requestCode, options);
    }

    @Override
    protected void onDestroy() {
        model.close();
        super.onDestroy();
    }

    @Override
    protected void openSettings() {
        main.removeCallbacks(tick);
        hideKeyboard();
        super.openSettings();
    }

    @Override
    public void onBackPressed() {
        if (!settings && !receiving && searchOpen) {
            closeSearch(true);
        } else if (receiving) {
            receiving = false;
            model.setPairingVisible(false);
            showMain();
        } else if (!settings && folderId != 0) {
            openFolder(0);
        } else {
            super.onBackPressed();
        }
    }

    private void openReceiver() {
        main.removeCallbacks(tick);
        hideKeyboard();
        receiving = true;
        showReceiver();
        model.setPairingVisible(true);
    }

    @Override
    protected void showMain() {
        hideKeyboard();
        LinearLayout root = page();
        mainRoot = root;
        int width = getResources().getConfiguration().screenWidthDp;
        int height = getResources().getConfiguration().screenHeightDp;
        boolean wide = width >= 720;
        boolean compact = !wide || height < 470;
        int panelWidth = Math.min(445, Math.max(320, (int) (width * 0.36f)));
        LinearLayout header;
        if (wide) {
            // As in Voyah Radio, the library's own row moves up beside Settings, above the library, so the track list
            // starts level with the player card and shows more tracks.
            header = ui.row();
            TextView name = ui.text(getString(R.string.player_name), 25, UiKit.TEXT, true);
            ui.truncate(name, 2);
            name.setMinHeight(ui.dp(52));
            name.setGravity(Gravity.CENTER_VERTICAL);
            header.addView(name, ui.dp(panelWidth), WRAP);
            ui.gap(header, 18);
            libraryHeader(header, true);
            ui.gap(header, 8);
            settingsIcon(header);
        } else {
            header = heading(getString(R.string.player_name), false);
            settingsButton(header);
        }
        ui.add(root, header, MATCH, WRAP, 0);
        LinearLayout content = wide ? ui.row() : ui.column();
        content.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams fill = new LinearLayout.LayoutParams(MATCH, 0, 1f);
        fill.topMargin = ui.dp(12);
        root.addView(content, fill);
        LinearLayout player = playerCard(compact, width < 375);
        LinearLayout library = libraryCard(wide);
        if (wide) {
            content.addView(player, ui.dp(panelWidth), MATCH);
            LinearLayout.LayoutParams remainder = new LinearLayout.LayoutParams(0, MATCH, 1f);
            remainder.leftMargin = ui.dp(18);
            content.addView(library, remainder);
        } else {
            content.addView(player, MATCH, WRAP);
            LinearLayout.LayoutParams remainder = new LinearLayout.LayoutParams(MATCH, 0, 1f);
            remainder.topMargin = ui.dp(12);
            content.addView(library, remainder);
        }
        setContentView(root);
        root.requestFocus();
        followOnOpen = true;
        followedQuery = query;
        filter();
        renderPlayback();
        // A search kept from before, such as one left open for Settings, comes back open without the keyboard.
        if (!query.isEmpty()) searchBox.post(() -> openSearch(false, false));
    }

    private LinearLayout playerCard(boolean compact, boolean narrow) {
        LinearLayout card = ui.card(compact ? 12 : 22);
        card.setBackground(ui.box(UiKit.PANEL, ui.selectedEdge, 22));
        TextView label = ui.text(getText(R.string.now_playing), 15, ui.accent, true);
        ui.center(card, label, WRAP, WRAP, 2);
        compactStage = compact;
        stage = new FrameLayout(this);
        if (!compact) {
            card.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));
            int art = getResources().getConfiguration().screenHeightDp >= 590 ? 126 : 82;
            // While music plays, its sound takes the emblem's place, as in Voyah Radio.
            emblem = ui.emblem(art);
            stage.addView(emblem, new FrameLayout.LayoutParams(ui.dp(art), ui.dp(art), Gravity.CENTER));
            ui.add(card, stage, MATCH, ui.dp(art), 8);
        }
        visualizer = new MusicVisualizer(this, ui.accent);
        FrameLayout.LayoutParams visualParams = new FrameLayout.LayoutParams(MATCH, MATCH);
        visualParams.setMargins(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4));
        stage.addView(visualizer, visualParams);
        title = ui.text("", compact ? 21 : 29, UiKit.TEXT, true);
        title.setGravity(Gravity.CENTER);
        ui.truncate(title, compact ? 1 : 2);
        // The title keeps the height of its longest form, so a track with a shorter title does not move the card;
        // a one-line title sits on the artist line, leaving the spare line above it.
        title.setMinLines(compact ? 1 : 2);
        if (!compact) title.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        ui.add(card, title, MATCH, WRAP, compact ? 10 : 20);
        artist = ui.text("", 15, UiKit.MUTED, false);
        artist.setGravity(Gravity.CENTER);
        ui.truncate(artist, 1);
        ui.add(card, artist, MATCH, WRAP, 6);
        if (!compact) card.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));
        else ui.add(card, stage, MATCH, ui.dp(52), 6);

        seek = new SeekBar(this);
        seek.setMax(1000);
        seek.setContentDescription(getText(R.string.playback_position));
        seek.setProgressTintList(ColorStateList.valueOf(ui.accent));
        seek.setThumbTintList(ColorStateList.valueOf(ui.accent));
        seek.setProgressBackgroundTintList(ColorStateList.valueOf(ui.edge));
        seek.setPadding(ui.dp(7), 0, ui.dp(7), 0);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                seekTo(bar.getProgress());
                dragging = false;
                updatePosition();
            }
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && playback != null) {
                    elapsed.setText(time(playback.duration() * progress / 1000));
                    if (!dragging) seekTo(progress);
                }
            }
        });
        ui.add(card, seek, MATCH, ui.dp(38), compact ? 4 : 10);
        LinearLayout timestamps = ui.row();
        timestamps.setPadding(ui.dp(5), 0, ui.dp(5), 0);
        elapsed = ui.text("0:00", 14, UiKit.MUTED, false);
        total = ui.text("0:00", 14, UiKit.MUTED, false);
        timestamps.addView(elapsed, ui.weighted());
        timestamps.addView(total, WRAP, WRAP);
        ui.add(card, timestamps, MATCH, WRAP, 0);

        LinearLayout controls = ui.row();
        controls.setGravity(Gravity.CENTER);
        shuffle = ui.iconButton(UiKit.Icon.SHUFFLE, getText(R.string.shuffle), false,
                () -> { if (playback != null) playback.shuffle(); });
        previous = ui.iconButton(UiKit.Icon.PREVIOUS, getText(R.string.previous), false,
                () -> { if (playback != null) playback.previous(); });
        play = ui.iconButton(UiKit.Icon.PLAY, getText(R.string.play), true,
                () -> { if (playback != null) playback.toggle(); });
        next = ui.iconButton(UiKit.Icon.NEXT, getText(R.string.next), false,
                () -> { if (playback != null) playback.next(); });
        repeat = ui.iconButton(UiKit.Icon.REPEAT, getText(R.string.repeat_off), false,
                () -> { if (playback != null) playback.repeat(); });
        int touch = narrow ? 42 : 48;
        controls.addView(shuffle, ui.dp(touch), ui.dp(48));
        ui.gap(controls, narrow ? 4 : 6);
        controls.addView(previous, ui.dp(touch), ui.dp(48));
        ui.gap(controls, narrow ? 4 : 6);
        controls.addView(play, ui.dp(narrow ? 56 : 70), ui.dp(compact ? 51 : 58));
        ui.gap(controls, narrow ? 4 : 6);
        controls.addView(next, ui.dp(touch), ui.dp(48));
        ui.gap(controls, narrow ? 4 : 6);
        controls.addView(repeat, ui.dp(touch), ui.dp(48));
        ui.add(card, controls, MATCH, WRAP, compact ? 10 : 16);
        problem = ui.text(getText(R.string.playback_error), 14, UiKit.ERROR, false);
        problem.setVisibility(View.GONE);
        ui.add(card, problem, MATCH, WRAP, 9);
        return card;
    }

    private LinearLayout libraryCard(boolean wide) {
        LinearLayout library = ui.card(wide ? 16 : 12);
        if (!wide) {
            LinearLayout header = ui.row();
            libraryHeader(header, false);
            ui.add(library, header, MATCH, WRAP, 0);
        }
        FrameLayout body = new FrameLayout(this);
        LinearLayout.LayoutParams listSpace = new LinearLayout.LayoutParams(MATCH, 0, 1f);
        if (!wide) listSpace.topMargin = ui.dp(10);
        library.addView(body, listSpace);
        return libraryBody(library, body);
    }

    /**
     * The library's title and buttons: in the page header on wide screens, at the top of the library card otherwise.
     *
     * <p>The buttons keep one order and fixed places at the right: search, sort, new folder, Connect phone and, on wide
     * screens, Settings after them, all 8 dp apart. New folder stays in its place, dimmed, inside a folder; an
     * offered update appears left of the search button, the search field opens leftward over the title, and a long
     * title ends in an ellipsis, so none of them moves a button.
     */
    private void libraryHeader(LinearLayout header, boolean wide) {
        if (folderId != 0) {
            header.addView(ui.iconButton(UiKit.Icon.BACK, getText(R.string.library), false, () -> openFolder(0)),
                    ui.dp(48), ui.dp(48));
            ui.gap(header, 8);
        }
        libraryTitle = ui.text(getText(R.string.library), wide ? 24 : 20, UiKit.TEXT, true);
        ui.truncate(libraryTitle, 1);
        header.addView(libraryTitle, ui.weighted());
        ui.gap(header, 8);
        if (wide) updateButton(header);
        header.addView(searchBox(), new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        ui.gap(header, 8);
        sortButton = ui.iconButton(UiKit.Icon.SORT, getText(R.string.sort_tracks), false, this::chooseOrder);
        header.addView(sortButton, ui.dp(48), ui.dp(48));
        ui.gap(header, 8);
        View newFolder = ui.iconButton(UiKit.Icon.FOLDER_ADD, getText(R.string.new_folder), false, this::createFolder);
        // Folders hold no folders, so inside one the button stays in its place dimmed, as unavailable buttons are,
        // instead of leaving and moving the others.
        ui.enabled(newFolder, folderId == 0);
        header.addView(newFolder, ui.dp(48), ui.dp(48));
        ui.gap(header, 8);
        receiveButton = ui.button(getText(R.string.receive), false, this::openReceiver);
        header.addView(receiveButton, WRAP, WRAP);
    }

    private LinearLayout libraryBody(LinearLayout library, FrameLayout body) {
        list = new ListView(this) {
            @Override
            public boolean dispatchTouchEvent(MotionEvent event) {
                touched(event);
                return super.dispatchTouchEvent(event);
            }
        };
        listTouched = listScrolling = ownScroll = false;
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int state) {
                listScrolling = state != SCROLL_STATE_IDLE;
                if (!listScrolling) ownScroll = false;
            }

            @Override
            public void onScroll(AbsListView view, int first, int visibleCount, int totalCount) {}
        });
        list.setDivider(null);
        list.setClipToPadding(false);
        adapter = new TrackAdapter();
        list.setAdapter(adapter);
        body.addView(list, MATCH, MATCH);
        LinearLayout empty = ui.column();
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(ui.dp(8), ui.dp(5), ui.dp(8), ui.dp(5));
        emptyTitle = ui.text("", 19, UiKit.TEXT, true);
        emptyTitle.setGravity(Gravity.CENTER);
        emptyHint = ui.text("", 15, UiKit.MUTED, false);
        emptyHint.setGravity(Gravity.CENTER);
        ui.add(empty, emptyTitle, MATCH, WRAP, 0);
        ui.add(empty, emptyHint, MATCH, WRAP, 7);
        body.addView(empty, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER));
        list.setEmptyView(empty);
        return library;
    }

    /**
     * The search button of the library header. A tap opens it into the search field, which grows leftward from the
     * button over the title while the hint and the close button fade in, and closes it back the same way.
     */
    private FrameLayout searchBox() {
        searchOpen = false;
        ValueAnimator previous = searchMotion;
        searchMotion = null;
        if (previous != null) previous.cancel();
        searchBox = new FrameLayout(this);
        searchBox.setBackground(ui.actionBackground(UiKit.SURFACE, ui.edge, 14));
        searchBox.setContentDescription(getText(R.string.search_open));
        searchBox.setTooltipText(getText(R.string.search_open));
        searchBox.setOnClickListener(view -> openSearch(true, true));
        ImageView mark = new ImageView(this);
        mark.setImageDrawable(ui.glyph(UiKit.Icon.SEARCH, UiKit.TEXT, 22));
        mark.setScaleType(ImageView.ScaleType.CENTER);
        searchBox.addView(mark, new FrameLayout.LayoutParams(ui.dp(48), ui.dp(48), Gravity.START | Gravity.CENTER_VERTICAL));
        searchField = new EditText(this);
        searchField.setTextColor(UiKit.TEXT);
        searchField.setTextSize(16);
        searchField.setHintTextColor(UiKit.MUTED);
        searchField.setHint(R.string.search_library);
        searchField.setSingleLine();
        searchField.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        searchField.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        searchField.setPadding(0, ui.dp(8), 0, ui.dp(8));
        searchField.setText(query);
        searchField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                query = s.toString();
                filter();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        searchField.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            leaveSearch();
            return true;
        });
        searchField.setOnFocusChangeListener((view, focused) -> {
            if (!focused && query.trim().isEmpty()) closeSearch(false);
        });
        // The field keeps the open width while the box grows over it, because Android takes the focus away from a
        // field that is laid out with no width.
        FrameLayout.LayoutParams field = new FrameLayout.LayoutParams(ui.dp(48), MATCH, Gravity.START | Gravity.CENTER_VERTICAL);
        field.leftMargin = ui.dp(44);
        searchBox.addView(searchField, field);
        searchClose = new ImageButton(this);
        searchClose.setImageDrawable(ui.glyph(UiKit.Icon.CLOSE, UiKit.TEXT, 20));
        searchClose.setScaleType(ImageView.ScaleType.CENTER);
        searchClose.setBackground(new RippleDrawable(ColorStateList.valueOf(0x30ffffff), null, ui.box(0xffffffff, 0, 12)));
        searchClose.setContentDescription(getText(R.string.search_close));
        searchClose.setTooltipText(getText(R.string.search_close));
        searchClose.setOnClickListener(view -> closeSearch(true));
        FrameLayout.LayoutParams close = new FrameLayout.LayoutParams(ui.dp(40), ui.dp(40), Gravity.END | Gravity.CENTER_VERTICAL);
        close.rightMargin = ui.dp(4);
        searchBox.addView(searchClose, close);
        setSearchParts(0f);
        searchField.setVisibility(View.GONE);
        searchClose.setVisibility(View.GONE);
        return searchBox;
    }

    private void openSearch(boolean animate, boolean focus) {
        if (searchOpen || searchBox == null || !searchBox.isAttachedToWindow()) return;
        searchOpen = true;
        searchBox.setClickable(false);
        searchBox.setContentDescription(null);
        searchField.setVisibility(View.VISIBLE);
        searchClose.setVisibility(View.VISIBLE);
        int text = (int) Math.ceil(libraryTitle.getPaint().measureText(libraryTitle.getText().toString()));
        int width = SearchWidth.open(ui.dp(48), libraryTitle.getWidth(), text, ui.dp(12), ui.dp(220), ui.dp(360));
        ViewGroup.LayoutParams fieldSize = searchField.getLayoutParams();
        fieldSize.width = Math.max(1, width - ui.dp(44) - ui.dp(48));
        searchField.setLayoutParams(fieldSize);
        resizeSearch(width, animate, true);
        if (!focus) return;
        searchField.requestFocus();
        searchField.setSelection(searchField.length());
        EditText field = searchField;
        field.post(() -> getSystemService(InputMethodManager.class).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT));
    }

    /** Closes the field back into the button; {@code clear} also drops the query, so the whole list returns. */
    private void closeSearch(boolean clear) {
        if (!searchOpen) return;
        searchOpen = false;
        hideKeyboard();
        if (searchField.hasFocus() && mainRoot != null) mainRoot.requestFocus();
        if (clear && searchField.length() > 0) searchField.setText("");
        searchBox.setClickable(true);
        searchBox.setContentDescription(getText(R.string.search_open));
        resizeSearch(ui.dp(48), true, false);
    }

    /** Moves the focus off the search field, which closes it when no query is typed. */
    private void leaveSearch() {
        if (searchField == null || !searchField.hasFocus()) return;
        hideKeyboard();
        if (mainRoot != null) mainRoot.requestFocus();
    }

    private void hideKeyboard() {
        if (searchField != null) {
            getSystemService(InputMethodManager.class).hideSoftInputFromWindow(searchField.getWindowToken(), 0);
        }
    }

    /**
     * Opening decelerates and closing accelerates, so closing retraces opening exactly; the animator follows the system
     * animation scale and jumps to the end when animations are off.
     */
    private void resizeSearch(int target, boolean animate, boolean opening) {
        ValueAnimator previous = searchMotion;
        searchMotion = null;
        if (previous != null) previous.cancel();
        ViewGroup.LayoutParams params = searchBox.getLayoutParams();
        int from = searchBox.getWidth() > 0 ? searchBox.getWidth() : params.width;
        float partsFrom = searchField.getAlpha();
        float partsTo = opening ? 1f : 0f;
        if (!animate) {
            setSearchSize(target, partsTo);
            if (!opening) hideSearchParts();
            return;
        }
        ValueAnimator motion = ValueAnimator.ofFloat(0f, 1f);
        motion.setDuration(SEARCH_MILLIS);
        motion.setInterpolator(opening ? new DecelerateInterpolator() : new AccelerateInterpolator());
        motion.addUpdateListener(update -> {
            float fraction = (float) update.getAnimatedValue();
            setSearchSize(Math.round(from + (target - from) * fraction), partsFrom + (partsTo - partsFrom) * fraction);
        });
        motion.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (searchMotion != motion) return;
                searchMotion = null;
                if (!searchOpen) hideSearchParts();
            }
        });
        searchMotion = motion;
        motion.start();
    }

    private void setSearchSize(int width, float parts) {
        ViewGroup.LayoutParams params = searchBox.getLayoutParams();
        if (params.width != width) {
            params.width = width;
            searchBox.setLayoutParams(params);
        }
        setSearchParts(parts);
    }

    private void setSearchParts(float alpha) {
        searchField.setAlpha(alpha);
        searchClose.setAlpha(alpha);
    }

    private void hideSearchParts() {
        searchField.setVisibility(View.GONE);
        searchClose.setVisibility(View.GONE);
    }

    private void tracks(MusicLibrary.Snapshot value) {
        librarySnapshot = value;
        tracks = TrackOrder.parse(prefs.trackOrder()).sort(value.tracks());
        if (playback != null) playback.setTracks(tracks);
        if (!settings && !receiving) filter();
        renderPlayback();
    }

    private void filter() {
        if (adapter == null) return;
        List<Object> result = new ArrayList<>();
        String term = query.trim().toLowerCase(Locale.ROOT);
        if (librarySnapshot != null) {
            for (Folder folder : librarySnapshot.folders()) {
                if (folderId == folder.id()) libraryTitle.setText(folder.name());
                if (folderId == 0 && folder.name().toLowerCase(Locale.ROOT).contains(term)) result.add(folder);
            }
            for (Track track : tracks) {
                boolean inFolder = librarySnapshot.folderOf(track) == folderId || (folderId == 0 && !term.isEmpty());
                if (inFolder && (term.isEmpty() || track.matches(term))) result.add(track);
            }
        }
        emptyTitle.setText(tracks == null ? R.string.loading_library
                : term.isEmpty() ? (folderId == 0 ? R.string.empty_library : R.string.empty_folder) : R.string.no_matches);
        emptyHint.setVisibility(tracks != null && term.isEmpty() && folderId == 0 ? View.VISIBLE : View.GONE);
        emptyHint.setText(R.string.empty_library_hint);
        boolean emphasizeReceive = tracks != null && tracks.isEmpty();
        if (receiveButton.isSelected() != emphasizeReceive) {
            receiveButton.setSelected(emphasizeReceive);
            ui.styleButton(receiveButton, emphasizeReceive);
        }
        adapter.rows = result;
        adapter.notifyDataSetChanged();
        follow(false);
    }

    private void touched(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            listTouched = true;
            ownScroll = false;
            leaveSearch();
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            listTouched = false;
        }
    }

    /** Keeps the playing track in view: see {@link TrackFollow} for when the list moves. */
    private void follow(boolean newTrack) {
        if (list == null || adapter == null || settings || receiving) return;
        boolean newQuery = !query.equals(followedQuery);
        followedQuery = query;
        boolean opened = followOnOpen && tracks != null && playback != null;
        if (opened) followOnOpen = false;
        switch (TrackFollow.move(opened, newQuery, newTrack, playingRow())) {
            case TOP -> list.setSelection(0);
            case SHOW -> reveal(false);
            case FOLLOW -> reveal(true);
            case STAY -> { }
        }
    }

    private int playingRow() {
        return TrackFollow.rowOf(adapter.rows, highlighted, entry -> entry instanceof Track track ? track.id() : null);
    }

    /** Brings the playing track into view after the next layout, unless it is fully visible or the user holds the list. */
    private void reveal(boolean smooth) {
        ListView target = list;
        target.post(() -> {
            int row = playingRow();
            if (target != list || row < 0 || !TrackFollow.mayMove(listTouched, listScrolling, ownScroll)) return;
            int first = target.getFirstVisiblePosition();
            View child = row >= first ? target.getChildAt(row - first) : null;
            // Each row ends with its gap to the next one, which does not need to be on screen.
            if (child != null && TrackFollow.fullyVisible(child.getTop(),
                    child.getBottom() - child.getPaddingBottom(), target.getHeight())) return;
            if (smooth) {
                ownScroll = true;
                target.smoothScrollToPositionFromTop(TrackFollow.firstRow(row), 0);
            } else {
                target.setSelectionFromTop(TrackFollow.firstRow(row), 0);
            }
        });
    }

    /** Lets the driver order the library; the queue follows, so next always plays the row below. */
    private void chooseOrder() {
        TrackOrder current = TrackOrder.parse(prefs.trackOrder());
        TrackOrder[] orders = TrackOrder.values();
        int[] names = {R.string.sort_title, R.string.sort_artist, R.string.sort_album, R.string.sort_added};
        PopupMenu popup = new PopupMenu(this, sortButton);
        for (int index = 0; index < orders.length; index++) {
            popup.getMenu().add(1, index, index, names[index]).setCheckable(true).setChecked(orders[index] == current);
        }
        popup.getMenu().setGroupCheckable(1, true, true);
        popup.setOnMenuItemClickListener(item -> {
            TrackOrder chosen = orders[item.getItemId()];
            if (chosen != current) {
                prefs.trackOrder(chosen.key);
                if (librarySnapshot != null) tracks(librarySnapshot);
            }
            return true;
        });
        popup.show();
    }

    private void openFolder(long id) {
        folderId = id;
        query = "";
        searchOpen = false;
        showMain();
    }

    private void createFolder() {
        EditText name = new EditText(this);
        name.setSingleLine();
        name.setHint(R.string.folder_name);
        name.setFilters(new InputFilter[] {new InputFilter.LengthFilter(60)});
        LinearLayout input = ui.column();
        input.setPadding(ui.dp(22), ui.dp(8), ui.dp(22), 0);
        input.addView(name, MATCH, WRAP);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.new_folder).setView(input)
                .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.create_folder, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            String value = name.getText().toString().trim();
            if (!MusicWire.validFolder(value)) {
                name.setError(getString(R.string.invalid_folder));
                return;
            }
            model.createFolder(value, () -> Toast.makeText(this, R.string.folder_failed, Toast.LENGTH_LONG).show());
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void folderOptions(Folder folder, View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, R.string.delete_folder, 0, R.string.delete_folder);
        popup.setOnMenuItemClickListener(option -> {
            deleteFolder(folder);
            return true;
        });
        popup.show();
    }

    /** Asks before a folder goes with its tracks; playback skips to the next track left, as when one track is deleted. */
    private void deleteFolder(Folder folder) {
        if (librarySnapshot == null) return;
        int count = librarySnapshot.tracksIn(folder.id()).size();
        String message = count == 0 ? getString(R.string.delete_folder_empty, folder.name())
                : getResources().getQuantityString(R.plurals.delete_folder_tracks, count, folder.name(), count);
        new AlertDialog.Builder(this).setTitle(R.string.delete_folder).setMessage(message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete_folder, (dialog, item) -> model.deleteFolder(folder.id(),
                        () -> Toast.makeText(this, R.string.delete_folder_failed, Toast.LENGTH_LONG).show())).show();
    }

    private void move(Track track) {
        if (librarySnapshot == null) return;
        List<Folder> choices = new ArrayList<>();
        choices.add(new Folder(0, getString(R.string.library_root)));
        choices.addAll(librarySnapshot.folders());
        CharSequence[] labels = choices.stream().map(Folder::name).toArray(CharSequence[]::new);
        new AlertDialog.Builder(this).setTitle(R.string.move_track).setItems(labels, (dialog, index) ->
                model.moveTrack(track, choices.get(index).id(),
                        () -> Toast.makeText(this, R.string.move_failed, Toast.LENGTH_LONG).show()))
                .setNegativeButton(R.string.cancel, null).show();
    }

    private void renderPlayback() {
        if (!visible || settings || receiving || title == null) return;
        PlaybackService.State state = playback == null ? null : playback.state();
        String active = state == null ? "" : state.id();
        Track track = currentTrack(active);
        boolean newTrack = !highlighted.equals(active);
        if (newTrack) {
            highlighted = active;
            adapter.notifyDataSetChanged();
        }
        follow(newTrack);
        boolean hasTrack = track != null;
        title.setText(hasTrack ? track.title() : getText(R.string.player_empty));
        String details = hasTrack ? details(track) : getString(R.string.player_empty_hint);
        artist.setText(details);
        // An untagged track leaves the line empty instead of closing it, so switching tracks does not move the card.
        artist.setVisibility(details.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        problem.setVisibility(state != null && state.failed() ? View.VISIBLE : View.GONE);
        boolean playing = state != null && state.wantsPlay();
        renderStage(state);
        play.setImageDrawable(ui.glyph(playing ? UiKit.Icon.PAUSE : UiKit.Icon.PLAY, ui.onAccent, 26));
        play.setContentDescription(getText(playing ? R.string.pause : R.string.play));
        play.setTooltipText(play.getContentDescription());
        ui.enabled(play, hasTrack);
        ui.enabled(previous, hasTrack);
        ui.enabled(next, hasTrack && playback.hasNext());
        ui.enabled(shuffle, hasTrack);
        ui.enabled(repeat, hasTrack);
        option(shuffle, state != null && state.shuffle(), UiKit.Icon.SHUFFLE, R.string.shuffle);
        int loop = state == null ? Player.REPEAT_MODE_OFF : state.repeat();
        option(repeat, loop != Player.REPEAT_MODE_OFF,
                loop == Player.REPEAT_MODE_ONE ? UiKit.Icon.REPEAT_ONE : UiKit.Icon.REPEAT,
                loop == Player.REPEAT_MODE_ONE ? R.string.repeat_one
                        : loop == Player.REPEAT_MODE_ALL ? R.string.repeat_all : R.string.repeat_off);
        updatePosition();
        main.removeCallbacks(tick);
        if (state != null && state.playing()) main.postDelayed(tick, 500);
    }

    /** Shows the sound in place of the emblem while music plays; the compact layout shows it only then. */
    private void renderStage(PlaybackService.State state) {
        String mode = prefs.visualization();
        boolean drawing = PlayerStage.drawing(playback != null, state != null && state.wantsPlay(), mode);
        if (compactStage) stage.setVisibility(drawing ? View.VISIBLE : View.GONE);
        else if (emblem != null) emblem.setVisibility(drawing ? View.INVISIBLE : View.VISIBLE);
        visualizer.setVisibility(drawing ? View.VISIBLE : View.GONE);
        visualizer.update(playback == null ? null : playback.meter(), mode, drawing, prefs.smoothVisualization());
    }

    private Track currentTrack(String id) {
        if (tracks == null || id.isEmpty()) return null;
        for (Track track : tracks) {
            if (track.id().equals(id)) return track;
        }
        return null;
    }

    private void option(ImageButton button, boolean active, UiKit.Icon icon, int description) {
        button.setSelected(active);
        button.setImageDrawable(ui.glyph(icon, active ? ui.accent : UiKit.TEXT, 21));
        button.setBackground(ui.actionBackground(active ? ui.selected : UiKit.SURFACE, active ? ui.selectedEdge : ui.edge, 14));
        button.setContentDescription(getText(description));
        button.setTooltipText(getText(description));
    }

    private void tick() {
        if (!visible || settings || receiving || playback == null || !playback.state().playing()) return;
        updatePosition();
        main.postDelayed(tick, 500);
    }

    private void updatePosition() {
        if (dragging) return;
        long duration = playback == null ? 0 : playback.duration();
        long position = playback == null ? 0 : playback.position();
        seek.setEnabled(duration > 0);
        int progress = duration == 0 ? 0 : (int) Math.min(1000, position * 1000 / duration);
        if (seek.getProgress() != progress) seek.setProgress(progress);
        setTime(elapsed, position);
        setTime(total, duration);
    }

    private void seekTo(int progress) {
        if (playback != null) playback.seek(playback.duration() * progress / 1000);
    }

    private void setTime(TextView view, long value) {
        String text = time(value);
        if (!text.contentEquals(view.getText())) view.setText(text);
    }

    private static String time(long millis) {
        long seconds = millis / 1000;
        return seconds >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private static String details(Track track) {
        if (track.artist().isEmpty()) return track.album();
        if (track.album().isEmpty()) return track.artist();
        return track.artist() + " · " + track.album();
    }

    private void showReceiver() {
        LinearLayout root = page();
        ui.add(root, heading(getString(R.string.pair_phone), true), MATCH, WRAP, 0);
        ScrollView scroll = new ScrollView(this);
        ui.add(root, scroll, MATCH, MATCH, 12);
        FrameLayout frame = new FrameLayout(this);
        scroll.addView(frame, MATCH, WRAP);
        receiveBody = ui.card(22);
        int width = getResources().getConfiguration().screenWidthDp;
        frame.addView(receiveBody, new FrameLayout.LayoutParams(width > 780 ? ui.dp(720) : MATCH,
                WRAP, Gravity.CENTER_HORIZONTAL));
        setContentView(root);
        renderReceiver(model.receiver());
    }

    private void renderReceiver(PlayerViewModel.Receiver state) {
        boolean waiting = receiving && state.state() == PlayerViewModel.Receiving.WAITING;
        if (waiting) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (!receiving || receiveBody == null) return;
        receiveBody.removeAllViews();
        TextView instructions = ui.text(getText(R.string.receive_instructions), 17, UiKit.MUTED, false);
        instructions.setLineSpacing(ui.dp(4), 1f);
        ui.add(receiveBody, instructions, MATCH, WRAP, 0);
        if (waiting) {
            if (state.request() != null) approval(state);
            qr(state);
        }
        int notice = switch (state.state()) {
            case INACTIVE, STOPPED -> R.string.receive_expired;
            case OPENING -> R.string.opening_receiver;
            case WAITING -> R.string.receive_active;
            case NO_NETWORK -> R.string.receive_no_wifi;
            case FAILED -> R.string.receive_failed;
        };
        ui.add(receiveBody, ui.text(getText(notice), 16, waiting ? ui.accent : UiKit.TEXT, true), MATCH, WRAP, 18);
        if (!state.latest().isEmpty()) {
            TextView done = ui.text(getString(R.string.received_track, state.latest()), 15, UiKit.MUTED, false);
            done.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            ui.add(receiveBody, done, MATCH, WRAP, 10);
        }
        if (!waiting && state.state() != PlayerViewModel.Receiving.OPENING) {
            ui.add(receiveBody, ui.button(getText(R.string.start_receiving), true,
                    () -> model.startReceiving(state.link())), WRAP, WRAP, 16);
        }
        if (!state.phones().isEmpty()) {
            ui.add(receiveBody, ui.text(getText(R.string.trusted_phones), 17, UiKit.TEXT, true), MATCH, WRAP, 18);
            for (TrustedPhones.Phone phone : state.phones()) {
                LinearLayout row = ui.row();
                row.addView(ui.text(phone.name(), 15, UiKit.MUTED, true), ui.weighted());
                ui.gap(row, 8);
                row.addView(ui.button(getText(R.string.forget_phone), false, () -> forget(phone)), WRAP, WRAP);
                ui.add(receiveBody, row, MATCH, WRAP, 6);
            }
        }
    }

    private void approval(PlayerViewModel.Receiver state) {
        LinearLayout card = ui.card(14);
        card.setBackground(ui.box(ui.selected, ui.selectedEdge, 14));
        ui.add(card, ui.text(getString(R.string.approve_phone, state.request().name(), state.request().digits()),
                18, UiKit.TEXT, true), MATCH, WRAP, 0);
        LinearLayout actions = ui.row();
        actions.addView(ui.button(getText(R.string.deny), false, () -> model.decide(state.request(), false)), ui.weighted());
        ui.gap(actions, 8);
        actions.addView(ui.button(getText(R.string.allow), true, () -> model.decide(state.request(), true)), ui.weighted());
        ui.add(card, actions, MATCH, WRAP, 12);
        ui.add(receiveBody, card, MATCH, WRAP, 12);
    }

    private void qr(PlayerViewModel.Receiver state) {
        String host = state.link().address().getHostAddress();
        PairQr current = new PairQr(host, state.fingerprint());
        if (!current.equals(lastQr)) {
            lastQr = current;
            qrBitmap = current.bitmap(ui.dp(202));
        }
        ImageView qr = new ImageView(this);
        qr.setImageBitmap(qrBitmap);
        qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qr.setContentDescription(getText(R.string.pair_qr));
        LinearLayout details = ui.column();
        ui.add(details, receiveValue(R.string.car_address, host), MATCH, WRAP, 0);
        ui.add(details, receiveValue(R.string.pairing_code, MusicWire.pairingCode(state.fingerprint())), MATCH, WRAP, 8);
        if (state.networks().size() > 1) {
            ui.add(details, ui.button(getText(R.string.car_address), false, () -> chooseLink(state)), WRAP, WRAP, 8);
        }
        if (getResources().getConfiguration().screenWidthDp >= 650) {
            LinearLayout row = ui.row();
            row.addView(qr, ui.dp(202), ui.dp(202));
            ui.gap(row, 18);
            row.addView(details, ui.weighted());
            ui.add(receiveBody, row, MATCH, WRAP, 16);
        } else {
            ui.center(receiveBody, qr, ui.dp(202), ui.dp(202), 16);
            ui.add(receiveBody, details, MATCH, WRAP, 12);
        }
    }

    private void forget(TrustedPhones.Phone phone) {
        new AlertDialog.Builder(this).setMessage(getString(R.string.forget_phone_prompt, phone.name()))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.forget_phone, (dialog, which) -> model.forgetPhone(phone,
                        () -> Toast.makeText(this, R.string.forget_failed, Toast.LENGTH_LONG).show())).show();
    }

    private void chooseLink(PlayerViewModel.Receiver state) {
        List<WifiLink> networks = state.networks();
        CharSequence[] addresses = new CharSequence[networks.size()];
        for (int i = 0; i < addresses.length; i++) addresses[i] = networks.get(i).address().getHostAddress();
        new AlertDialog.Builder(this).setTitle(R.string.car_address)
                .setSingleChoiceItems(addresses, networks.indexOf(state.link()), (chooser, index) -> {
                    chooser.dismiss();
                    if (!state.link().equals(networks.get(index))) model.startReceiving(networks.get(index));
                }).setNegativeButton(R.string.cancel, null).show();
    }

    private LinearLayout receiveValue(int label, String value) {
        LinearLayout box = ui.card(16);
        box.setBackground(ui.box(UiKit.SURFACE, ui.edge, 14));
        ui.add(box, ui.text(getText(label), 14, UiKit.MUTED, true), MATCH, WRAP, 0);
        TextView number = ui.text(value, 28, UiKit.TEXT, true);
        number.setTypeface(Typeface.MONOSPACE);
        number.setTextIsSelectable(true);
        ui.add(box, number, MATCH, WRAP, 8);
        return box;
    }

    private void delete(Track track) {
        new AlertDialog.Builder(this).setTitle(R.string.delete_track).setMessage(getString(R.string.delete_hint, track.title()))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete_track, (dialog, item) -> model.delete(track,
                        () -> Toast.makeText(this, R.string.delete_failed, Toast.LENGTH_LONG).show())).show();
    }

    private final class TrackAdapter extends BaseAdapter {
        private List<Object> rows = Collections.emptyList();

        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int position) { return getItem(position) instanceof Folder ? 1 : 0; }

        @Override
        public View getView(int position, View reusable, ViewGroup parent) {
            Object entry = getItem(position);
            if (entry instanceof Folder folder) {
                Button button = ui.button(folder.name(), false, () -> openFolder(folder.id()));
                button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                button.setCompoundDrawablesRelativeWithIntrinsicBounds(ui.glyph(UiKit.Icon.FOLDER, ui.accent, 22), null, null, null);
                button.setCompoundDrawablePadding(ui.dp(14));
                // Leave room for the options button, which lines up with the options of the track rows.
                button.setPaddingRelative(ui.dp(13), ui.dp(8), ui.dp(13 + 48), ui.dp(8));
                FrameLayout holder = new FrameLayout(PlayerActivity.this);
                holder.setPadding(0, 0, 0, ui.dp(7));
                holder.addView(button, MATCH, ui.dp(55));
                ImageButton[] options = new ImageButton[1];
                options[0] = ui.iconButton(UiKit.Icon.MORE, getText(R.string.folder_options), false,
                        () -> folderOptions(folder, options[0]));
                FrameLayout.LayoutParams place = new FrameLayout.LayoutParams(ui.dp(48), ui.dp(48),
                        Gravity.END | Gravity.CENTER_VERTICAL);
                place.setMarginEnd(ui.dp(5));
                holder.addView(options[0], place);
                return holder;
            }
            TrackRow item = reusable instanceof TrackRow row ? row : new TrackRow();
            item.bind((Track) entry);
            return item;
        }
    }

    private final class TrackRow extends LinearLayout {
        private final LinearLayout row;
        private final TextView name, metadata, duration;
        private final ImageButton options;
        private final android.widget.ImageView mark;
        private Track track;

        TrackRow() {
            super(PlayerActivity.this);
            setOrientation(VERTICAL);
            setPadding(0, 0, 0, ui.dp(7));
            row = ui.row();
            row.setMinimumHeight(ui.dp(62));
            row.setPadding(ui.dp(11), ui.dp(5), ui.dp(5), ui.dp(5));
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(view -> { if (playback != null) playback.select(track.id()); });
            mark = ui.mark(UiKit.Icon.NOTE, 22);
            row.addView(mark, ui.dp(30), ui.dp(36));
            ui.gap(row, 8);
            LinearLayout description = ui.column();
            name = ui.text("", 17, UiKit.TEXT, true);
            metadata = ui.text("", 14, UiKit.MUTED, false);
            ui.truncate(name, 1);
            ui.truncate(metadata, 1);
            ui.add(description, name, MATCH, WRAP, 0);
            ui.add(description, metadata, MATCH, WRAP, 4);
            row.addView(description, ui.weighted());
            duration = ui.text("", 14, UiKit.MUTED, false);
            ui.gap(row, 6);
            row.addView(duration, WRAP, WRAP);
            ui.gap(row, 8);
            options = ui.iconButton(UiKit.Icon.MORE, getText(R.string.track_options), false, this::openOptions);
            row.addView(options, ui.dp(48), ui.dp(48));
            addView(row, MATCH, WRAP);
        }

        private void openOptions() {
            PopupMenu popup = new PopupMenu(PlayerActivity.this, options);
            popup.getMenu().add(0, R.string.move_track, 0, R.string.move_track);
            popup.getMenu().add(0, R.string.delete_track, 1, R.string.delete_track);
            Track target = track;
            popup.setOnMenuItemClickListener(option -> {
                if (option.getItemId() == R.string.move_track) move(target);
                else delete(target);
                return true;
            });
            popup.show();
        }

        void bind(Track track) {
            this.track = track;
            boolean active = highlighted.equals(track.id());
            row.setBackground(ui.actionBackground(active ? ui.selected : UiKit.SURFACE,
                    active ? ui.selectedEdge : ui.edge, 13));
            row.setSelected(active);
            mark.setImageDrawable(ui.glyph(active ? UiKit.Icon.PLAY : UiKit.Icon.NOTE, active ? ui.accent : UiKit.MUTED, 20));
            name.setText(track.title());
            name.setTextColor(active ? ui.accent : UiKit.TEXT);
            String detail = details(track);
            metadata.setText(detail);
            metadata.setVisibility(detail.isEmpty() ? View.GONE : View.VISIBLE);
            duration.setText(track.duration() > 0 ? time(track.duration()) : "");
        }
    }
}
