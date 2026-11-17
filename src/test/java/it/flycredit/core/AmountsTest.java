package it.flycredit.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class AmountsTest {
    @ParameterizedTest
    @ValueSource(strings = {"1.54", "1,54"})
    void readsSecondsAfterEitherSeparator(String input) {
        assertEquals(114_000, Amounts.minutes(input));
    }

    @Test void readsWholeMinutesAndDurationsShorterThanTwoMinutes() {
        assertEquals(60_000, Amounts.minutes("1"));
        assertEquals(60_000, Amounts.minutes("1m"));
        assertEquals(60_000, Amounts.minutes("1M"));
        assertEquals(65_000, Amounts.minutes("1.05"));
        assertEquals(90_000, Amounts.minutes("1,30"));
        assertEquals(1000, Amounts.minutes("0.01"));
        assertEquals(59_000, Amounts.minutes("0,59"));
    }

    @Test void requiresPositiveTimeWithinTheAccountLimit() {
        assertThrows(CreditException.class, () -> Amounts.minutes("0,00"));
        assertEquals(Amounts.MAX_MILLIS, Amounts.minutes("5256000.00"));
        assertEquals(Amounts.MAX_MILLIS - 1000, Amounts.minutes("5255999.59"));
        assertThrows(CreditException.class, () -> Amounts.minutes("5256000.01"));
        assertThrows(CreditException.class, () -> Amounts.minutes("99999999999999999999999"));
        assertThrows(CreditException.class, () -> Amounts.minutes("0"));
        assertThrows(CreditException.class, () -> Amounts.minutes("0.00"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.5", "1,5", "1.60", "0,99", "1.", ".30", "1.234", "1,2.30", "-1", "1e9"})
    void rejectsAmbiguousOrInvalidAmounts(String input) {
        CreditException error = assertThrows(CreditException.class, () -> Amounts.minutes(input));
        assertEquals("amount.format", error.key());
    }
}
