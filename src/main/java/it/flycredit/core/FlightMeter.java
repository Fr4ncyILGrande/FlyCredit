package it.flycredit.core;

/** Main-thread clock. Fractional milliseconds are retained, including across rapid toggles. */
public final class FlightMeter {
    private long remainingNanos;
    private long lastNanos;
    private boolean consuming;
    public FlightMeter(long millis, long now) {
        remainingNanos = Math.multiplyExact(millis, 1_000_000L);
        lastNanos = now;
    }
    public void update(long now, boolean nextConsuming) {
        if (consuming) remainingNanos = Math.max(0, remainingNanos - Math.max(0, now - lastNanos));
        lastNanos = now;
        consuming = nextConsuming;
    }
    public long remainingMillis() { return remainingNanos / 1_000_000L; }
    public boolean consuming() { return consuming; }
    public boolean exhausted() { return remainingNanos <= 0; }
}
