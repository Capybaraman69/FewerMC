package link.fewermc;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DurationParser {

    private static final Pattern PART_PATTERN = Pattern.compile("^(\\d+)([smhd])$", Pattern.CASE_INSENSITIVE);

    private DurationParser() {
    }

    public static Duration parseDuration(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }

        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        Matcher matcher = PART_PATTERN.matcher(normalized);

        if (!matcher.matches()) {
            return null;
        }

        long value = Long.parseLong(matcher.group(1));
        if (value <= 0) {
            return null;
        }

        return switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
            case "s" -> Duration.ofSeconds(value);
            case "m" -> Duration.ofMinutes(value);
            case "h" -> Duration.ofHours(value);
            case "d" -> Duration.ofDays(value);
            default -> null;
        };
    }

    public static String toCompact(Duration duration) {
        long seconds = duration.getSeconds();
        if (seconds % (24L * 60 * 60) == 0) {
            return (seconds / (24L * 60 * 60)) + "d";
        }
        if (seconds % (60L * 60) == 0) {
            return (seconds / (60L * 60)) + "h";
        }
        if (seconds % 60L == 0) {
            return (seconds / 60L) + "m";
        }
        return seconds + "s";
    }
}
