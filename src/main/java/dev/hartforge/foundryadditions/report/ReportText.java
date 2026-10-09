package dev.hartforge.foundryadditions.report;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ReportText {

    public static final int MAX_LEN = 300;

    private static final Pattern CMD = Pattern.compile("^!(log|idea)(?:\\s+(.*))?$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    public record Parsed(String kind, String body) {}

    private ReportText() {}

    public static Optional<Parsed> parse(String message) {
        if (message == null) return Optional.empty();
        Matcher m = CMD.matcher(message.strip());
        if (!m.matches()) return Optional.empty();
        String kind = m.group(1).equalsIgnoreCase("idea") ? "idea" : "bug";
        String body = m.group(2) == null ? "" : m.group(2).strip();
        if (body.length() > MAX_LEN) body = body.substring(0, MAX_LEN);
        return Optional.of(new Parsed(kind, body));
    }

    public static String usage(String kind) {
        return kind.equals("idea")
                ? "usage: !idea <your idea>, for example !idea a bigger backpack"
                : "usage: !log <what went wrong>, for example !log create press recipe is not working";
    }
}
