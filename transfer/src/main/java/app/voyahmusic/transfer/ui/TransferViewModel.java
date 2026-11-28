package app.voyahmusic.transfer.ui;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import app.voyahmusic.shared.lan.MusicWire;
import app.voyahmusic.shared.lan.MusicWire.Candidate;
import app.voyahmusic.shared.lan.MusicWire.Folder;
import app.voyahmusic.shared.lan.MusicWire.Presence;
import app.voyahmusic.shared.lan.MusicWire.Status;
import app.voyahmusic.shared.lan.WifiLink;
import app.voyahmusic.transfer.data.CarConnection;
import app.voyahmusic.transfer.data.SelectedFile;
import app.voyahmusic.transfer.data.SendSession;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import javax.net.ssl.SSLHandshakeException;

public final class TransferViewModel {
    enum State {
        READY, READING, CONNECTING, CHECKING, CONFLICT, SENDING, DONE, CANCELLED, NO_WIFI, ADDRESS, CODE, CONNECTION,
        FAILED
    }

    /** What to do with files whose name other audio already has in the destination folder. */
    enum Clash { REPLACE, KEEP_BOTH, SKIP }
    enum Link { UNPAIRED, PAIRING, CHECKING, CONNECTED, OFFLINE, DISCONNECTED }

    record Connection(Link state, String address, String confirmation, List<Folder> folders, long destination) {}

    /**
     * The phone's screen. Of the {@code completed} files, {@code duplicates} were already in the car, {@code replaced}
     * took the place of a track with the same name and {@code skipped} were left out because of their name;
     * {@code clashes} lists those names while the transfer waits for a choice.
     */
    record Screen(List<SelectedFile> files, Connection connection, State state, int completed, int duplicates,
                  int replaced, int skipped, List<String> clashes, SelectedFile current, long bytes, Status error) {
        boolean busy() {
            return state == State.READING || state == State.CONNECTING || state == State.CHECKING
                    || state == State.CONFLICT || state == State.SENDING || connection.state() == Link.PAIRING;
        }
    }

    private record Pending(CarConnection car, long folder, List<SelectedFile> batch, List<Presence> presence) {}

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<Uri, SelectedFile> selected = new LinkedHashMap<>();
    private final Runnable monitor = this::monitor;
    private List<SelectedFile> files = Collections.emptyList();
    private List<Folder> folders = Collections.emptyList();
    private Consumer<Screen> listener;
    private Screen screen;
    private CarConnection car;
    private Link link;
    private long destination;
    private String confirmation = "";
    private boolean checking;
    private int replaced;
    private int skipped;
    private List<String> clashes = Collections.emptyList();
    private Pending pending;
    private volatile int generation;
    private volatile boolean closed;
    private volatile SendSession active;

    public TransferViewModel(Context context) {
        this.context = context.getApplicationContext();
        car = CarConnection.load(this.context);
        link = car == null ? Link.UNPAIRED : CarConnection.paused(this.context) ? Link.DISCONNECTED : Link.CHECKING;
        show(State.READY, 0, 0, null, 0, null);
    }

    void observe(Consumer<Screen> listener) {
        this.listener = listener;
        main.removeCallbacks(monitor);
        if (listener != null) {
            listener.accept(screen);
            monitor();
        }
    }

    private void monitor() {
        if (listener == null) return;
        probe();
        main.postDelayed(monitor, 7_000);
    }

    void choose(List<Uri> uris) {
        if (screen.busy()) return;
        int task = ++generation;
        show(State.READING, 0, 0, null, 0, null);
        worker.execute(() -> {
            List<SelectedFile> result = new ArrayList<>(uris.size());
            for (Uri uri : uris) result.add(SelectedFile.read(context.getContentResolver(), uri));
            main.post(() -> {
                if (!current(task)) return;
                for (SelectedFile file : result) selected.put(file.uri(), file);
                snapshot();
                show(State.READY, 0, 0, null, 0, null);
                probe();
            });
        });
    }

    void remove(SelectedFile file) {
        if (screen.busy()) return;
        selected.remove(file.uri());
        snapshot();
        show(State.READY, 0, 0, null, 0, null);
    }

    void pair(String host, String pin) {
        if (screen.busy() || car != null) return;
        InetAddress address = MusicWire.address(host);
        if (address == null) {
            show(State.ADDRESS, 0, 0, null, 0, null);
            return;
        }
        if (!pin.matches("[0-9]{8}") && !MusicWire.fingerprint(pin)) {
            show(State.CODE, 0, 0, null, 0, null);
            return;
        }
        int task = ++generation;
        String digits = String.format(Locale.ROOT, "%04d", new SecureRandom().nextInt(10_000));
        confirmation = digits;
        link = Link.PAIRING;
        show(State.READY, 0, 0, null, 0, null);
        worker.execute(() -> {
            if (!current(task)) return;
            WifiLink wifi = wifi(address);
            if (wifi == null) {
                pairingResult(task, null, State.NO_WIFI, null);
                return;
            }
            try {
                SendSession session = new SendSession(wifi, address, pin, "");
                active = session;
                if (!current(task)) return;
                String name = Build.MODEL.replaceAll("[\\p{Cntrl}\\p{Cf}]", " ").trim();
                if (name.isEmpty()) name = "Android";
                SendSession.Pair result = session.pair(name.substring(0, Math.min(60, name.length())), digits);
                if (result.status() != Status.OK) {
                    pairingResult(task, null, State.FAILED, result.status());
                    return;
                }
                pairingResult(task, new CarConnection(address.getHostAddress(), result.fingerprint(), result.token()),
                        State.READY, null);
            } catch (SSLHandshakeException e) {
                pairingResult(task, null, State.FAILED, Status.CODE);
            } catch (IOException e) {
                pairingResult(task, null, State.CONNECTION, null);
            } finally {
                active = null;
            }
        });
    }

    private void pairingResult(int task, CarConnection result, State outcome, Status error) {
        main.post(() -> {
            if (!current(task)) return;
            confirmation = "";
            if (result != null && result.save(context)) {
                car = result;
                link = Link.CHECKING;
                show(State.READY, 0, 0, null, 0, null);
                probe();
            } else {
                link = Link.UNPAIRED;
                show(result == null ? outcome : State.FAILED, 0, 0, null, 0, result == null ? error : Status.IO);
            }
        });
    }

    void disconnect() {
        if (car == null) {
            cancel();
            return;
        }
        invalidate();
        CarConnection.paused(context, true);
        link = Link.DISCONNECTED;
        show(State.READY, 0, 0, null, 0, null);
    }

    void reconnect() {
        if (car == null || screen.busy() || checking) return;
        CarConnection.paused(context, false);
        link = Link.CHECKING;
        emit();
        probe();
    }

    void forget() {
        invalidate();
        clearCar();
        show(State.READY, 0, 0, null, 0, null);
    }

    private void clearCar() {
        CarConnection.forget(context);
        car = null;
        link = Link.UNPAIRED;
        folders = Collections.emptyList();
        destination = 0;
    }

    void target(long folder) {
        if (screen.busy()) return;
        if (folder == 0 || folders.stream().anyMatch(entry -> entry.id() == folder)) {
            destination = folder;
            emit();
        }
    }

    private void probe() {
        if (car == null || link == Link.DISCONNECTED || checking || screen.busy()) return;
        checking = true;
        int task = generation;
        CarConnection saved = car;
        worker.execute(() -> {
            SendSession.Check response = null;
            try {
                if (current(task)) {
                    SendSession session = connect(saved);
                    active = session;
                    if (current(task) && session != null) response = session.check();
                }
            } catch (IOException ignored) {
            } finally {
                active = null;
            }
            SendSession.Check result = response;
            main.post(() -> {
                checking = false;
                if (!current(task)) {
                    if (listener != null) probe();
                    return;
                }
                Connection previous = screen.connection();
                if (result != null && result.status() == Status.OK) {
                    link = Link.CONNECTED;
                    folders = result.folders();
                    if (destination != 0 && folders.stream().noneMatch(folder -> folder.id() == destination)) {
                        destination = 0;
                    }
                    if (!previous.equals(connection())) emit();
                } else if (result != null && result.status() == Status.UNPAIRED) {
                    clearCar();
                    show(State.FAILED, 0, 0, null, 0, Status.UNPAIRED);
                } else if (link != Link.OFFLINE) {
                    link = Link.OFFLINE;
                    emit();
                }
            });
        });
    }

    void send() {
        if (files.isEmpty() || screen.busy() || link != Link.CONNECTED) return;
        int task = ++generation;
        List<SelectedFile> batch = files;
        CarConnection saved = car;
        long folder = destination;
        replaced = 0;
        skipped = 0;
        clashes = Collections.emptyList();
        show(State.CONNECTING, 0, 0, null, 0, null);
        worker.execute(() -> prepare(task, saved, folder, batch));
    }

    /** Asks the car which files it already has, then sends the rest or asks about names that are taken. */
    private void prepare(int task, CarConnection saved, long folder, List<SelectedFile> batch) {
        try {
            SendSession session = open(task, saved);
            if (session == null) return;
            List<Presence> presence = inspect(task, session, folder, batch);
            if (presence == null) return;
            List<String> taken = new ArrayList<>();
            for (int i = 0; i < batch.size(); i++) {
                if (presence.get(i) == Presence.NAME) taken.add(batch.get(i).name());
            }
            if (taken.isEmpty()) {
                upload(task, session, folder, batch, presence, Clash.KEEP_BOTH);
                return;
            }
            main.post(() -> {
                if (!current(task)) return;
                pending = new Pending(saved, folder, batch, presence);
                clashes = Collections.unmodifiableList(taken);
                show(State.CONFLICT, 0, 0, null, 0, null);
            });
        } catch (IOException e) {
            post(task, State.CONNECTION, 0, 0, null, 0, null);
        } finally {
            active = null;
        }
    }

    /** Continues a transfer that waited for a choice about taken names. */
    void resolve(Clash choice) {
        if (screen.state() != State.CONFLICT || pending == null) return;
        Pending job = pending;
        pending = null;
        int task = ++generation;
        show(State.CONNECTING, 0, 0, null, 0, null);
        worker.execute(() -> {
            try {
                SendSession session = open(task, job.car());
                if (session != null) upload(task, session, job.folder(), job.batch(), job.presence(), choice);
            } catch (IOException e) {
                post(task, State.CONNECTION, 0, 0, null, 0, null);
            } finally {
                active = null;
            }
        });
    }

    /** Drops a transfer that waited for a choice about taken names; nothing was sent. */
    void abandon() {
        if (screen.state() != State.CONFLICT) return;
        pending = null;
        clashes = Collections.emptyList();
        show(State.READY, 0, 0, null, 0, null);
    }

    /** Connects to the car and checks the pairing, or reports why it cannot and returns null. */
    private SendSession open(int task, CarConnection saved) throws IOException {
        if (!current(task)) return null;
        SendSession session = connect(saved);
        if (session == null) {
            post(task, State.NO_WIFI, 0, 0, null, 0, null);
            return null;
        }
        active = session;
        if (!current(task)) return null;
        SendSession.Check connected = session.check();
        if (connected.status() != Status.OK) {
            post(task, State.FAILED, 0, 0, null, 0, connected.status());
            return null;
        }
        return session;
    }

    /**
     * What the car holds for each file, in order. A file the car cannot tell about, or one that cannot be read here,
     * counts as new and goes through the upload, which reports its problem. Null when the transfer was cancelled.
     */
    private List<Presence> inspect(int task, SendSession session, long folder, List<SelectedFile> batch)
            throws IOException {
        List<Presence> presence = new ArrayList<>(Collections.nCopies(batch.size(), Presence.NEW));
        List<Candidate> candidates = new ArrayList<>();
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < batch.size() && candidates.size() < MusicWire.MAX_PROBE; i++) {
            if (!current(task)) return null;
            SelectedFile file = batch.get(i);
            if (!MusicWire.validName(file.name()) || file.size() == 0) continue;
            post(task, State.CHECKING, i, 0, file, 0, null);
            try {
                candidates.add(new Candidate(file.name(), session.sha256(context.getContentResolver(), file)));
                positions.add(i);
            } catch (InterruptedIOException e) {
                return null;
            } catch (IOException unreadable) {
                // The upload reports the file that cannot be read.
            }
        }
        if (candidates.isEmpty()) return presence;
        List<Presence> answers = session.probe(folder, candidates);
        if (!current(task)) return null;
        if (answers != null) {
            for (int k = 0; k < answers.size(); k++) presence.set(positions.get(k), answers.get(k));
        }
        return presence;
    }

    private void upload(int task, SendSession session, long folder, List<SelectedFile> batch, List<Presence> presence,
                        Clash choice) {
        int done = 0;
        int duplicates = 0;
        int replacements = 0;
        int skips = 0;
        SelectedFile current = null;
        try {
            for (int i = 0; i < batch.size(); i++) {
                if (!current(task)) return;
                SelectedFile file = batch.get(i);
                Presence found = presence.get(i);
                if (found == Presence.SAME || (found == Presence.NAME && choice == Clash.SKIP)) {
                    if (found == Presence.SAME) duplicates++;
                    else skips++;
                    done++;
                    finished(file);
                    continue;
                }
                boolean replace = found == Presence.NAME && choice == Clash.REPLACE;
                current = file;
                int completed = done;
                int already = duplicates;
                int swapped = replacements;
                int passed = skips;
                post(task, State.SENDING, completed, already, swapped, passed, file, 0, null);
                Status result = session.send(context.getContentResolver(), file, folder, replace,
                        bytes -> post(task, State.SENDING, completed, already, swapped, passed, file, bytes, null));
                if (result != Status.OK && result != Status.DUPLICATE) {
                    post(task, State.FAILED, done, duplicates, replacements, skips, file, 0, result);
                    return;
                }
                if (result == Status.DUPLICATE) duplicates++;
                else if (replace) replacements++;
                done++;
                finished(file);
            }
            post(task, State.DONE, done, duplicates, replacements, skips, null, 0, null);
        } catch (IOException e) {
            post(task, State.CONNECTION, done, duplicates, replacements, skips, current, 0, null);
        }
    }

    /** Takes a file that was sent, found on the car or skipped off the list. */
    private void finished(SelectedFile file) {
        main.post(() -> {
            if (!closed) {
                if (selected.get(file.uri()) == file) selected.remove(file.uri());
                snapshot();
                if (!screen.busy()) emit();
            }
        });
    }

    private SendSession connect(CarConnection saved) throws IOException {
        InetAddress address = MusicWire.address(saved.address());
        WifiLink wifi = wifi(address);
        return wifi == null ? null : new SendSession(wifi, address, saved.fingerprint(), saved.token());
    }

    private WifiLink wifi(InetAddress address) {
        for (WifiLink candidate : WifiLink.available(context, true)) {
            if (candidate.includes(address)) return candidate;
        }
        return null;
    }

    private void snapshot() {
        files = Collections.unmodifiableList(new ArrayList<>(selected.values()));
    }

    private boolean current(int task) {
        return !closed && generation == task;
    }

    private void post(int task, State state, int sent, int duplicates, SelectedFile file, long bytes, Status error) {
        post(task, state, sent, duplicates, -1, -1, file, bytes, error);
    }

    /** Shows a transfer's progress; a negative count keeps the one shown. */
    private void post(int task, State state, int sent, int duplicates, int replacements, int skips, SelectedFile file,
                      long bytes, Status error) {
        main.post(() -> {
            if (!current(task)) return;
            if (replacements >= 0) replaced = replacements;
            if (skips >= 0) skipped = skips;
            if (error == Status.UNPAIRED) clearCar();
            if (state == State.NO_WIFI || state == State.CONNECTION) link = Link.OFFLINE;
            show(state, sent, duplicates, file, bytes, error);
        });
    }

    private Connection connection() {
        return new Connection(link, car == null ? "" : car.address(), confirmation, folders, destination);
    }

    private void emit() {
        show(screen.state(), screen.completed(), screen.duplicates(), screen.current(), screen.bytes(), screen.error());
    }

    private void show(State state, int completed, int duplicates, SelectedFile file, long bytes, Status error) {
        screen = new Screen(files, connection(), state, completed, duplicates, replaced, skipped, clashes, file, bytes,
                error);
        if (listener != null) listener.accept(screen);
    }

    private void invalidate() {
        generation++;
        pending = null;
        SendSession session = active;
        if (session != null) session.cancel();
    }

    void cancel() {
        boolean pairing = link == Link.PAIRING;
        invalidate();
        if (pairing) {
            link = Link.UNPAIRED;
            confirmation = "";
        }
        show(pairing ? State.READY : State.CANCELLED, screen.completed(), screen.duplicates(), null, 0, null);
    }

    void close() {
        closed = true;
        observe(null);
        invalidate();
        worker.shutdownNow();
    }
}
