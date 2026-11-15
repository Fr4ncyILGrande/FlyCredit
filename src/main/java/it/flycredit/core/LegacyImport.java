package it.flycredit.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.ByteBuffer;
import java.security.*;
import java.util.*;

public record LegacyImport(String sha256, Map<UUID, Long> balances, int ignoredRows) {
    public static final int MAX_BYTES = 10_000_000;
    public LegacyImport {
        balances = Map.copyOf(balances);
        for (long balance : balances.values())
            if (balance < 0 || balance > Amounts.MAX_MILLIS) throw new IllegalArgumentException("Saldo importato fuori limite");
    }
    public static LegacyImport read(byte[] bytes) throws IOException {
        if (bytes.length > MAX_BYTES) throw new IOException("Il CSV supera 10 MB.");
        String source = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        if (source.startsWith("\uFEFF")) source = source.substring(1);
        var balances = new LinkedHashMap<UUID, Long>();
        int ignored = 0;
        int line = 0;
        for (String raw : source.split("\\R")) {
            line++;
            String s = raw.strip();
            if (s.isEmpty() || s.startsWith("#")) continue;
            if (!s.startsWith("flycredit::")) { ignored++; continue; }
            String[] fields = s.split(",", -1);
            try {
                if (fields.length != 3 || !fields[1].strip().equals("long")) throw new IllegalArgumentException();
                String id = fields[0].substring("flycredit::".length()).strip();
                UUID uuid = UUID.fromString(id);
                if (!uuid.toString().equalsIgnoreCase(id)) throw new IllegalArgumentException();
                String hex = fields[2].strip();
                if (!hex.matches("[0-9a-fA-F]{16}")) throw new IllegalArgumentException();
                long minutes = Long.parseUnsignedLong(hex, 16);
                long millis = Math.multiplyExact(minutes, 60_000);
                if (minutes < 0 || millis > Amounts.MAX_MILLIS || balances.putIfAbsent(uuid, millis) != null)
                    throw new IllegalArgumentException();
            } catch (IllegalArgumentException | ArithmeticException ex) {
                throw new IOException("Riga flycredit non valida o UUID duplicato alla riga " + line, ex);
            }
        }
        if (balances.isEmpty()) throw new IOException("Nessun saldo flycredit trovato.");
        try {
            return new LegacyImport(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                    Collections.unmodifiableMap(balances), ignored);
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    public long totalMillis() { return balances.values().stream().reduce(0L, Math::addExact); }
}
