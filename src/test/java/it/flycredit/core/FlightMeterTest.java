package it.flycredit.core;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class FlightMeterTest {
    @Test void groundAndPausedIntervalsDoNotConsume() {
        var meter = new FlightMeter(60_000, 0);
        meter.update(10_000_000_000L, true);
        assertEquals(60_000, meter.remainingMillis());
        meter.update(15_000_000_000L, false);
        assertEquals(55_000, meter.remainingMillis());
        meter.update(80_000_000_000L, false);
        assertEquals(55_000, meter.remainingMillis());
    }
    @Test void rapidlyTogglingDoesNotRoundEachChargeAway() {
        var meter = new FlightMeter(10, 0);
        for (int i = 0; i < 20; i++) {
            meter.update(i * 500_000L, true);
            meter.update(i * 500_000L + 500_000L, false);
        }
        assertTrue(meter.exhausted());
    }
    @Test void lagUsesRealElapsedTimeAndNeverNegative() {
        var meter = new FlightMeter(1000, 0);
        meter.update(0, true);
        meter.update(5_000_000_000L, true);
        assertTrue(meter.exhausted());
        assertEquals(0, meter.remainingMillis());
    }
}
