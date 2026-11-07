package top.kmar.worldrelay.snapshot;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** 两端使用同一有界格式。清单包含空目录、完整文件集合和每个文件的 SHA-256。 */
public record SnapshotManifest(List<Entry> entries) {

    public static final String FILE_NAME = "manifest.bin";
    public static final int MAX_ENTRIES = 200_000;
    public static final long MAX_BYTES = 100L * 1024 * 1024 * 1024;
    public static final int MAX_MANIFEST_BYTES = 64 * 1024 * 1024;
    private static final int MAGIC = 0x57525332;
    private static final int LEGACY_MAGIC = 0x57525331;

    public record Entry(String path, boolean directory, long size, String sha256) {

        public Entry {
            validatePath(path);
            if (size < 0 || size > MAX_BYTES || (directory ? size != 0 || !sha256.isEmpty() : !sha256.matches("[0-9a-f]{64}"))) {
                throw new IllegalArgumentException("Invalid snapshot entry");
            }
        }

    }

    public SnapshotManifest {
        entries = List.copyOf(entries);
        if (entries.size() > MAX_ENTRIES) throw new IllegalArgumentException("Too many snapshot entries");
        Map<String, Entry> names = new HashMap<>();
        Set<String> folded = new HashSet<>();
        long total = 0;
        String previous = "";
        for (Entry entry : entries) {
            if (entry.path.compareTo(previous) <= 0 || !folded.add(entry.path.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Duplicate or unordered snapshot path");
            }
            int slash = entry.path.lastIndexOf('/');
            if (slash >= 0) {
                Entry parent = names.get(entry.path.substring(0, slash));
                if (parent == null || !parent.directory) throw new IllegalArgumentException("Missing snapshot directory");
            }
            names.put(entry.path, entry);
            previous = entry.path;
            total = Math.addExact(total, entry.size);
            if (total > MAX_BYTES) throw new IllegalArgumentException("Snapshot exceeds quota");
        }
        Entry level = names.get("level.dat");
        if (level == null || level.directory || level.size == 0) throw new IllegalArgumentException("Snapshot has no level.dat");
        if (names.containsKey("session.lock")) throw new IllegalArgumentException("Session locks are not portable");
    }

    public byte[] encode() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(entries.size());
            for (Entry entry : entries) {
                output.writeUTF(entry.path);
                output.writeBoolean(entry.directory);
                output.writeLong(entry.size);
                output.writeUTF(entry.sha256);
                if (bytes.size() > MAX_MANIFEST_BYTES) throw new IOException("Manifest exceeds quota");
            }
        }
        return bytes.toByteArray();
    }

    public static SnapshotManifest read(Path file, String expectedHash) throws IOException {
        byte[] bytes;
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MAX_MANIFEST_BYTES + 1);
        }
        if (bytes.length > MAX_MANIFEST_BYTES || !hash(bytes).equals(expectedHash)) throw new IOException("Manifest checksum mismatch");
        return decode(bytes);
    }

    public static SnapshotManifest decode(byte[] bytes) throws IOException {
        if (bytes.length > MAX_MANIFEST_BYTES) throw new IOException("Manifest exceeds quota");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int format = input.readInt();
            if (format == LEGACY_MAGIC) {
                // 已发布快照仍按原始字节校验摘要；旧清单的环境指纹不再参与存档加载。
                input.skipNBytes(input.readUnsignedShort());
            } else if (format != MAGIC) throw new IOException("Unsupported snapshot format");
            int count = input.readInt();
            if (count < 0 || count > MAX_ENTRIES) throw new IOException("Invalid manifest length");
            List<Entry> entries = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                entries.add(new Entry(input.readUTF(), input.readBoolean(), input.readLong(), input.readUTF()));
            }
            if (input.read() != -1) throw new IOException("Trailing manifest bytes");
            return new SnapshotManifest(entries);
        } catch (IllegalArgumentException | ArithmeticException failure) {
            throw new IOException("Invalid snapshot manifest");
        }
    }

    public static void validatePath(String path) {
        if (path == null || path.length() > 4096 || path.isEmpty() || path.codePoints().anyMatch(Character::isISOControl)
            || path.indexOf('\\') >= 0 || path.matches(".*[:*?\"<>|].*")) throw new IllegalArgumentException("Unsafe snapshot path");
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || part.endsWith(".") || part.endsWith(" ")
                || part.getBytes(StandardCharsets.UTF_8).length > 255
                || part.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?")) {
                throw new IllegalArgumentException("Nonportable snapshot path");
            }
        }
    }

    public static String hash(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

}
