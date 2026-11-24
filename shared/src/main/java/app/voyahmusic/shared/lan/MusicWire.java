package app.voyahmusic.shared.lan;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class MusicWire {
    private static final int VERSION = 0x564D0002;
    public static final int PORT = 47477;
    public static final int CHUNK = 64 * 1024;
    public static final long MAX_BYTES = 2L * 1024 * 1024 * 1024;
    private static final List<String> EXTENSIONS = Arrays.asList(
            "mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "mka", "amr");

    public static final int CHECK = 1;
    public static final int UPLOAD = 2;
    public static final int PAIR = 3;
    /** Asks which of the files the phone is about to send the car already has. Older cars close the connection. */
    public static final int PROBE = 4;
    /** An upload that takes the place of the destination folder's other tracks with the same file name. */
    public static final int REPLACE = 5;
    public static final int MAX_FOLDERS = 256;
    /** The most files one probe asks about. */
    public static final int MAX_PROBE = 1000;

    public record Folder(long id, String name) {}

    /** A file the phone is about to send: its name and the SHA-256 of its content, which becomes the track's id. */
    public record Candidate(String name, String sha256) {}

    public record ProbeRequest(long folder, List<Candidate> files) {}

    /** What the car already holds for a candidate. */
    public enum Presence {
        /** Nothing: the file is new to the car. */
        NEW(0),
        /** The same audio is already in the library, in any folder, so sending it again adds nothing. */
        SAME(1),
        /** Other audio with the same file name is already in the destination folder. */
        NAME(2);

        private final int value;

        Presence(int value) {
            this.value = value;
        }

        static Presence of(int value) throws IOException {
            for (Presence presence : values()) {
                if (presence.value == value) return presence;
            }
            throw new IOException("Unknown presence");
        }
    }

    public enum Status {
        OK(1), DUPLICATE(2), CODE(3), FORMAT(4), TOO_LARGE(5), STORAGE(6), IO(7), EXPIRED(8),
        DECLINED(9), UNPAIRED(10), FOLDER(11), BUSY(12);

        private final int value;

        Status(int value) {
            this.value = value;
        }

        public void write(DataOutputStream out) throws IOException {
            out.writeByte(value);
            out.flush();
        }

        public static Status read(DataInputStream in) throws IOException {
            int value = in.readUnsignedByte();
            for (Status status : values()) {
                if (status.value == value) return status;
            }
            throw new IOException("Unknown transfer status");
        }
    }

    private MusicWire() {}

    public static void request(DataOutputStream out, int command, String code) throws IOException {
        out.writeInt(VERSION);
        out.writeByte(command);
        out.writeUTF(code);
    }

    public static int command(DataInputStream in) throws IOException {
        if (in.readInt() != VERSION) throw new IOException("Unsupported transfer protocol");
        int command = in.readUnsignedByte();
        if (command != CHECK && command != UPLOAD && command != PAIR && command != PROBE && command != REPLACE) {
            throw new IOException("Unknown transfer command");
        }
        return command;
    }

    public static void writeProbe(DataOutputStream out, long folder, List<Candidate> files) throws IOException {
        if (files.size() > MAX_PROBE) throw new IOException("Too many files to probe");
        out.writeLong(folder);
        out.writeInt(files.size());
        for (Candidate file : files) {
            out.writeUTF(file.name());
            out.writeUTF(file.sha256());
        }
        out.flush();
    }

    public static ProbeRequest readProbe(DataInputStream in) throws IOException {
        long folder = in.readLong();
        int count = in.readInt();
        if (count < 0 || count > MAX_PROBE) throw new IOException("Invalid probe size");
        List<Candidate> files = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String name = in.readUTF();
            String sha256 = in.readUTF();
            if (!validName(name) || !fingerprint(sha256)) throw new IOException("Invalid probe entry");
            files.add(new Candidate(name, sha256));
        }
        return new ProbeRequest(folder, Collections.unmodifiableList(files));
    }

    public static void writePresence(DataOutputStream out, List<Presence> answers) throws IOException {
        out.writeInt(answers.size());
        for (Presence answer : answers) out.writeByte(answer.value);
        out.flush();
    }

    /** Reads one answer for each of the {@code expected} candidates, in the order they were asked about. */
    public static List<Presence> readPresence(DataInputStream in, int expected) throws IOException {
        int count = in.readInt();
        if (count != expected) throw new IOException("The probe answer does not match the request");
        List<Presence> answers = new ArrayList<>(count);
        for (int i = 0; i < count; i++) answers.add(Presence.of(in.readUnsignedByte()));
        return Collections.unmodifiableList(answers);
    }

    public static boolean validFolder(String name) {
        return !name.trim().isEmpty() && name.length() <= 60 && !name.equals(".") && !name.equals("..")
                && name.codePoints().noneMatch(c -> c == '/' || c == '\\' || Character.isISOControl(c));
    }

    public static boolean fingerprint(String value) {
        return value.matches("[0-9a-f]{64}");
    }

    public static String pairingCode(String fingerprint) {
        long prefix = Long.parseLong(fingerprint.substring(0, 12), 16);
        return String.format(Locale.ROOT, "%08d", prefix % 100_000_000L);
    }

    public static String hex(byte[] bytes) {
        StringBuilder output = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            output.append(Character.forDigit((value & 255) >>> 4, 16));
            output.append(Character.forDigit(value & 15, 16));
        }
        return output.toString();
    }

    public static Inet4Address address(String value) {
        String[] parts = value.trim().split("\\.", -1);
        if (parts.length != 4) return null;
        byte[] bytes = new byte[4];
        for (int i = 0; i < bytes.length; i++) {
            if (!parts[i].matches("[0-9]{1,3}")) return null;
            int part = Integer.parseInt(parts[i]);
            if (part > 255) return null;
            bytes[i] = (byte) part;
        }
        try {
            Inet4Address address = (Inet4Address) InetAddress.getByAddress(bytes);
            return isLocal(address) ? address : null;
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public static boolean isLocal(InetAddress address) {
        return address instanceof Inet4Address && (address.isSiteLocalAddress() || address.isLinkLocalAddress());
    }

    public static boolean sameSubnet(InetAddress local, InetAddress peer, int prefix) {
        if (!isLocal(local) || !isLocal(peer) || prefix < 1 || prefix > 32) return false;
        byte[] a = local.getAddress();
        byte[] b = peer.getAddress();
        for (int i = 0; i < a.length && prefix > 0; i++, prefix -= 8) {
            int mask = 0xFF << Math.max(0, 8 - prefix);
            if (((a[i] ^ b[i]) & 0xFF & mask) != 0) return false;
        }
        return true;
    }

    public static boolean validName(String name) {
        if (name.trim().isEmpty() || name.length() > 180 || name.startsWith(".")) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\' || Character.isISOControl(c)) return false;
        }
        return EXTENSIONS.contains(extension(name))
                && name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 240;
    }

    public static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }
}
