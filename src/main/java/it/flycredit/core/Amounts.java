package it.flycredit.core;

import java.util.Locale;

public final class Amounts {
    private Amounts() {}
    // Each account can hold up to ten years of flight time, including reservations.
    public static final long MAX_MILLIS = 315_360_000_000L;
    public static long minutes(String text, boolean allowZero) {
        if (!text.matches("[0-9]+[mM]?")) throw new CreditException("amount.wholeMinutes");
        try {
            long n = Long.parseLong(text.replaceAll("[mM]$", ""));
            long ms = Math.multiplyExact(n, 60_000L);
            if (ms > MAX_MILLIS || (!allowZero && ms == 0)) throw new ArithmeticException();
            return ms;
        } catch (ArithmeticException | NumberFormatException ex) {
            throw new CreditException("amount.range");
        }
    }
    public static String format(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        return String.format(Locale.ROOT, "%dh %02dm %02ds", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }
}
