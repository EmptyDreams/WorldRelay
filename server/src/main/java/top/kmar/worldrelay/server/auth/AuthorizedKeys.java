package top.kmar.worldrelay.server.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 仅根据完整行尾注释识别用户；只替换一个可确认的 rrsync 条目，保留其他行的原始文本和换行。 */
final class AuthorizedKeys {

    private static final Set<String> RRSYNC_FLAGS = Set.of("-ro", "-wo", "-munge", "-absolute", "-no-del", "-no-lock", "-no-overwrite");

    private AuthorizedKeys() {
    }

    static String update(String original, String username, String publicKey, String rrsyncCommand, Path directory, boolean munge)
        throws IOException {
        String command = shellQuote(rrsyncCommand) + (munge ? " -munge " : " ")
            + shellQuote(directory.toString().replace('\\', '/'));
        String replacement = "restrict,command=\"" + command.replace("\\", "\\\\").replace("\"", "\\\"") + "\" " + publicKey;
        if (replacement.getBytes(StandardCharsets.UTF_8).length > 8192) throw new IOException("SSH authorization line is too long");
        String[] lines = original.split("(?<=\n)", -1);
        int matched = -1;
        for (int index = 0; index < lines.length; index++) {
            Entry entry = parse(lines[index]);
            if (entry == null) {
                String line = lines[index].strip();
                if (!line.startsWith("#") && (line.endsWith(" " + username) || line.endsWith("\t" + username))) {
                    throw new UserStore.AuthorizationConflictException();
                }
                continue;
            }
            if (!entry.comment.equals(username)) continue;
            if (matched != -1 || !isRrsync(entry.options, rrsyncCommand)) {
                throw new UserStore.AuthorizationConflictException();
            }
            matched = index;
        }
        if (matched >= 0) {
            String ending = lines[matched].endsWith("\r\n") ? "\r\n" : lines[matched].endsWith("\n") ? "\n" : "";
            lines[matched] = replacement + ending;
            return String.join("", lines);
        }
        String ending = original.contains("\r\n") ? "\r\n" : "\n";
        return original + (original.isEmpty() || original.endsWith("\n") ? "" : ending) + replacement + ending;
    }

    private static Entry parse(String raw) {
        String line = raw.strip();
        if (line.isEmpty() || line.startsWith("#")) return null;
        try {
            int firstEnd = fieldEnd(line, 0);
            String first = line.substring(0, firstEnd);
            String options = "";
            int keyEnd = firstEnd;
            if (!keyType(first)) {
                options = first;
                int start = skipSpace(line, firstEnd);
                keyEnd = fieldEnd(line, start);
                if (!keyType(line.substring(start, keyEnd))) return null;
            }
            int keyStart = skipSpace(line, keyEnd);
            int blobEnd = fieldEnd(line, keyStart);
            if (keyStart == blobEnd) return null;
            return new Entry(options, line.substring(skipSpace(line, blobEnd)));
        } catch (IllegalArgumentException failure) {
            return null;
        }
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private static boolean keyType(String value) {
        return value.startsWith("ssh-") || value.startsWith("ecdsa-") || value.startsWith("sk-");
    }

    private static int fieldEnd(String value, int start) {
        boolean quoted = false;
        boolean escaped = false;
        for (int index = start; index < value.length(); index++) {
            char current = value.charAt(index);
            if (escaped) escaped = false;
            else if (current == '\\') escaped = true;
            else if (current == '"') quoted = !quoted;
            else if (Character.isWhitespace(current) && !quoted) return index;
        }
        if (quoted || escaped) throw new IllegalArgumentException("Invalid SSH options");
        return value.length();
    }

    private static int skipSpace(String value, int start) {
        while (start < value.length() && Character.isWhitespace(value.charAt(start))) start++;
        return start;
    }

    private static boolean isRrsync(String options, String configuredCommand) {
        String command = null;
        boolean quoted = false;
        boolean escaped = false;
        int start = 0;
        for (int index = 0; index <= options.length(); index++) {
            char current = index == options.length() ? ',' : options.charAt(index);
            if (!quoted && current == ',') {
                String option = options.substring(start, index);
                if (option.regionMatches(true, 0, "command=", 0, 8)) {
                    if (command != null) return false;
                    String value = option.substring(8);
                    if (value.length() < 2 || value.charAt(0) != '"' || value.charAt(value.length() - 1) != '"') return false;
                    StringBuilder decoded = new StringBuilder();
                    for (int part = 1; part < value.length() - 1; part++) {
                        if (value.charAt(part) == '\\') {
                            if (++part >= value.length() - 1) return false;
                        }
                        decoded.append(value.charAt(part));
                    }
                    command = decoded.toString();
                }
                start = index + 1;
            } else if (escaped) escaped = false;
            else if (current == '\\') escaped = true;
            else if (current == '"') quoted = !quoted;
        }
        if (quoted || escaped || command == null) return false;
        List<String> words = shellWords(command);
        if (words == null || words.size() < 2) return false;
        String executable = words.getFirst();
        if (executable.equals("exec")) {
            words = words.subList(1, words.size());
            if (words.size() < 2) return false;
            executable = words.getFirst();
        }
        if (!executable.equals(configuredCommand) && !executable.equals("rrsync") && !executable.endsWith("/rrsync")) return false;
        for (int index = 1; index < words.size() - 1; index++) {
            if (!RRSYNC_FLAGS.contains(words.get(index))) return false;
        }
        return !words.getLast().isEmpty() && !words.getLast().startsWith("-");
    }

    private static List<String> shellWords(String value) {
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        char quote = 0;
        boolean started = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (quote != '\'' && (current == '$' || current == '`' || ";|&<>\r\n".indexOf(current) >= 0)) return null;
            if (current == '\\' && quote != '\'') {
                if (++index == value.length()) return null;
                word.append(value.charAt(index));
                started = true;
            } else if (quote != 0) {
                if (current == quote) quote = 0;
                else word.append(current);
            } else if (current == '\'' || current == '"') {
                quote = current;
                started = true;
            } else if (Character.isWhitespace(current)) {
                if (started) words.add(word.toString());
                word.setLength(0);
                started = false;
            } else {
                word.append(current);
                started = true;
            }
        }
        if (quote != 0) return null;
        if (started) words.add(word.toString());
        return words;
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private record Entry(String options, String comment) {
    }

}