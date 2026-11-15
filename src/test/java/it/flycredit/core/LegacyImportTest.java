package it.flycredit.core;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class LegacyImportTest {
    private final String id = "00000000-0000-0000-0009-000000000001";
    private LegacyImport read(String text) throws IOException { return LegacyImport.read(text.getBytes(StandardCharsets.UTF_8)); }
    @Test void bigEndianSkriptLongIsMinutesNotSeconds() throws Exception {
        var data = read("# Skript\nflycredit::" + id + ", long, 0000000000000104\ntags::1, string, 8000\n");
        assertEquals(260 * 60_000L, data.balances().get(UUID.fromString(id)));
        assertEquals(1, data.ignoredRows());
        assertEquals(64, data.sha256().length());
    }
    @Test void zeroBalancesAreRetained() throws Exception {
        assertEquals(0, read("flycredit::" + id + ", long, 0000000000000000").totalMillis());
    }
    @Test void bomDoesNotSilentlyDropFirstPlayer() throws Exception {
        assertEquals(60_000, read("\uFEFFflycredit::" + id + ", long, 0000000000000001").totalMillis());
    }
    @Test void malformedUtf8AndOversizedFilesAreRejected() {
        assertThrows(IOException.class, () -> LegacyImport.read(new byte[]{(byte) 0xC3, 0x28}));
        assertThrows(IOException.class, () -> LegacyImport.read(new byte[LegacyImport.MAX_BYTES + 1]));
    }
    @Test void rejectsDuplicateUuidEntireFile() {
        String row = "flycredit::" + id + ", long, 0000000000000001\n";
        assertThrows(IOException.class, () -> read(row + row));
    }
    @Test void rejectsNegativeOverflowMalformedAndWrongTypes() {
        for (String v : new String[]{"FFFFFFFFFFFFFFFF", "7FFFFFFFFFFFFFFF", "00", "00000000000000XZ"})
            assertThrows(IOException.class, () -> read("flycredit::" + id + ", long, " + v));
        assertThrows(IOException.class, () -> read("flycredit::" + id + ", double, 0000000000000001"));
        assertThrows(IOException.class, () -> read("flycredit::1-1-1-1-1, long, 0000000000000001"));
        assertThrows(IOException.class, () -> read("tag::1, long, 0000000000000001"));
    }
    @Test void amountsRejectDecimalNegativeOverflowAndZeroActivation() {
        for (String s : new String[]{"-1", "1.5", "1e9", "999999999999999999999999", "0"})
            assertThrows(CreditException.class, () -> Amounts.minutes(s, false));
        assertEquals(60_000, Amounts.minutes("1m", false));
        assertEquals(0, Amounts.minutes("0", true));
    }
}
