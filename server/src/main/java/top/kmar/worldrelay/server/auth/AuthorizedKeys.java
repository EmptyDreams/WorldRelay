package top.kmar.worldrelay.server.auth;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 根据完整行尾注释识别受管 rrsync 授权，加固命令并保留原有公钥与限制。 */
final class AuthorizedKeys {

    private static final Set<String> RRSYNC_FLAGS = Set.of("-ro", "-wo", "-munge", "-absolute", "-no-del", "-no-lock", "-no-overwrite");
    private static final Pattern COMMAND_OPTION = Pattern.compile("(?i)(^|,)command=\"(?:[^\"\\\\]|\\\\.)*\"");

    private AuthorizedKeys() {
    }

    /** 更新已登记用户的受管 rrsync 命令，保留原公钥和管理员附加的 from 等限制。 */
    static String harden(String original, Set<String> usernames, String executable) {
        String[] lines = original.split("(?<=\n)", -1);
        for (int index = 0; index < lines.length; index++) {
            Entry entry = parse(lines[index]);
            if (entry == null || !usernames.contains(entry.comment) || isNotRrsync(entry.options, executable)) continue;
            Matcher command = COMMAND_OPTION.matcher(entry.options);
            if (!command.find()) continue;
            String option = command.group();
            String decoded = option.substring(option.indexOf('"') + 1, option.length() - 1).replace("\\\"", "\"");
            List<String> words = shellWords(decoded);
            if (words == null) continue;
            if (words.getFirst().equals("exec")) words = words.subList(1, words.size());
            List<String> flags = new ArrayList<>(words.subList(1, words.size() - 1));
            if (!flags.contains("-munge")) flags.add("-munge");
            // 保留原目录及 -ro/-wo/-no-del 等限制，不能因加固而扩大已有公钥权限。
            String forced = shellQuote(executable) + " " + String.join(" ", flags) + " " + shellQuote(words.getLast());
            String updated = command.replaceFirst(Matcher.quoteReplacement(command.group(1) + "command=\"" + forced.replace("\"", "\\\"") + "\""));
            String tail = lines[index].strip().substring(entry.options.length()).stripLeading();
            String ending = lines[index].endsWith("\r\n") ? "\r\n" : lines[index].endsWith("\n") ? "\n" : "";
            lines[index] = updated + " " + tail + ending;
        }
        return String.join("", lines);
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
        while (start < value.length() && Character.isWhitespace(value.charAt(start))) {
            start++;
        }
        return start;
    }

    private static boolean isNotRrsync(String options, String configuredCommand) {
        String command = null;
        boolean quoted = false;
        boolean escaped = false;
        int start = 0;
        for (int index = 0; index <= options.length(); index++) {
            char current = index == options.length() ? ',' : options.charAt(index);
            if (!quoted && current == ',') {
                String option = options.substring(start, index);
                if (option.regionMatches(true, 0, "command=", 0, 8)) {
                    if (command != null) return true;
                    String value = option.substring(8);
                    if (value.length() < 2 || value.charAt(0) != '"' || value.charAt(value.length() - 1) != '"') return true;
                    StringBuilder decoded = new StringBuilder();
                    for (int part = 1; part < value.length() - 1; part++) {
                        if (value.charAt(part) == '\\' && part + 1 < value.length() - 1 && value.charAt(part + 1) == '"') part++;
                        decoded.append(value.charAt(part));
                    }
                    command = decoded.toString();
                }
                start = index + 1;
            } else if (escaped) escaped = false;
            else if (current == '\\') escaped = true;
            else if (current == '"') quoted = !quoted;
        }
        if (quoted || escaped || command == null) return true;
        List<String> words = shellWords(command);
        if (words == null || words.size() < 2) return true;
        String executable = words.getFirst();
        if (executable.equals("exec")) {
            words = words.subList(1, words.size());
            if (words.size() < 2) return true;
            executable = words.getFirst();
        }
        if (!executable.equals(configuredCommand) && !executable.equals("rrsync") && !executable.endsWith("/rrsync")) return true;
        for (int index = 1; index < words.size() - 1; index++) {
            if (!RRSYNC_FLAGS.contains(words.get(index))) return true;
        }
        return words.getLast().isEmpty() || words.getLast().startsWith("-");
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
