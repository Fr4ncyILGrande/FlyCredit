package it.flycredit.core;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Amounts {
    private Amounts() {}
    // Each account can hold up to ten years of flight time, including reservations.
    public static final long MAX_MILLIS = 315_360_000_000L;
    private static final Pattern DURATION = Pattern.compile("([0-9]+)(?:[mM]|[.,]([0-5][0-9]))?");

    public static long minutes(String text, boolean allowZero) {
        Matcher duration = DURATION.matcher(text);
        if (!duration.matches()) throw new CreditException("amount.format");
        try {
            long minutes = Long.parseLong(duration.group(1));
            long seconds = duration.group(2) == null ? 0 : Integer.parseInt(duration.group(2));
            long millis = Math.addExact(Math.multiplyExact(minutes, 60_000L), seconds * 1000L);
            if (millis > MAX_MILLIS || (!allowZero && millis == 0)) throw new CreditException("amount.range");
            return millis;
        } catch (ArithmeticException | NumberFormatException ex) {
            throw new CreditException("amount.range");
        }
    }
    public static String format(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        return String.format(Locale.ROOT, "%dh %02dm %02ds", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }
}
