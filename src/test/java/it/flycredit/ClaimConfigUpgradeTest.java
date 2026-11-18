package it.flycredit;

import it.flycredit.integration.ClaimAccess;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ClaimConfigUpgradeTest {
    @TempDir Path directory;

    @Test void updatingAnExistingServerAddsAnEmptyListAndKeepsItsSettings() throws Exception {
        Path file = directory.resolve("config.yml");
        Files.writeString(file, """
                # Our server configuration
                transfer:
                  # Keep our recipient groups
                  allowed-recipient-groups: [staff, veteran]
                  membership: INHERITED
                flight:
                  bossbar: false
                  fall-protection-seconds: 8
                storage: {checkpoint-ticks: 40}
                custom: 'Keep # this value'
                # Players can fly in claims they own or where they have personal BUILDER or MANAGER trust.
                """);
        ConfigComments.update(file);
        String updated = Files.readString(file);
        var config = new YamlConfiguration();
        config.loadFromString(updated);
        assertEquals(List.of(), config.getList(ClaimAccess.TRUST_SETTING));
        assertEquals(List.of("staff", "veteran"), config.getStringList("transfer.allowed-recipient-groups"));
        assertEquals("INHERITED", config.getString("transfer.membership"));
        assertFalse(config.getBoolean("flight.bossbar", true));
        assertEquals(8, config.getInt("flight.fall-protection-seconds"));
        assertEquals(40, config.getInt("storage.checkpoint-ticks"));
        assertEquals("Keep # this value", config.getString("custom"));
        assertTrue(updated.contains("# Keep our recipient groups"));
        assertFalse(updated.contains("personal BUILDER or MANAGER trust"));
        ConfigComments.update(file);
        assertEquals(updated, Files.readString(file));
    }

    @Test void restartingPreservesTheAdministratorsChosenListAndComments() throws Exception {
        Path file = directory.resolve("config.yml");
        String source = "flight:\n  allowed-trust-types: [container] # chosen for this server\n";
        Files.writeString(file, source);
        ConfigComments.update(file);
        assertEquals(source, Files.readString(file));
    }
}
