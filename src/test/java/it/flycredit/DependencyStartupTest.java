package it.flycredit;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.Server;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import com.griefdefender.api.GriefDefender;
import com.griefdefender.api.Registry;
import com.griefdefender.api.claim.TrustType;
import it.flycredit.integration.ClaimAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockMakers;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

class DependencyStartupTest {
    private PluginManager enabledDependencies() {
        PluginManager manager = mock(PluginManager.class);
        for (String name : new String[]{"GriefDefender", "LuckPerms", "PlayTimeManager"}) {
            Plugin dependency = mock(Plugin.class);
            when(dependency.isEnabled()).thenReturn(true);
            when(manager.getPlugin(name)).thenReturn(dependency);
        }
        return manager;
    }

    @Test void allowsStartupWhenAllDependenciesAreEnabled() {
        assertDoesNotThrow(() -> FlyCreditPlugin.requireDependencies(enabledDependencies()));
    }

    @ParameterizedTest
    @CsvSource({"false, false", "true, false", "false, true", "true, true"})
    void guestFlightAndGiftRecipientsAreIndependentAtStartup(boolean customRanks, boolean allowGuests,
                                                               @TempDir Path directory) throws Exception {
        YamlConfiguration config;
        try (var input = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/config.yml")), StandardCharsets.UTF_8)) {
            config = YamlConfiguration.loadConfiguration(input);
        }
        Set<String> expected = Set.copyOf(config.getStringList("transfer.allowed-recipient-groups"));
        assertTrue(expected.isEmpty());
        assertTrue(config.isList(ClaimAccess.TRUST_SETTING));
        assertTrue(config.getList(ClaimAccess.TRUST_SETTING).isEmpty());
        if (allowGuests) config.set(ClaimAccess.TRUST_SETTING, List.of("container"));
        if (customRanks) {
            config.set("transfer.allowed-recipient-groups", List.of(" Explorer ", " ", "explorer"));
            expected = Set.of("explorer");
        }
        var plugin = mock(FlyCreditPlugin.class, withSettings().mockMaker(MockMakers.INLINE));
        var server = mock(Server.class);
        var manager = enabledDependencies();
        var command = mock(PluginCommand.class, withSettings().mockMaker(MockMakers.INLINE));
        when(server.getPluginManager()).thenReturn(manager);
        when(server.getScheduler()).thenReturn(mock(BukkitScheduler.class));
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getCommand(anyString())).thenReturn(command);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.playtime()).thenCallRealMethod();
        when(plugin.flight()).thenCallRealMethod();
        when(plugin.claimAccess()).thenCallRealMethod();
        doCallRealMethod().when(plugin).onEnable();
        doCallRealMethod().when(plugin).onDisable();
        var luckPerms = mock(LuckPerms.class);
        var registry = mock(Registry.class);
        var container = mock(TrustType.class);
        when(container.getId()).thenReturn("griefdefender:container");
        when(registry.getAllOf(TrustType.class)).thenReturn(List.of(container));
        try (var provider = mockStatic(LuckPermsProvider.class, withSettings().mockMaker(MockMakers.INLINE));
             var gd = mockStatic(GriefDefender.class, withSettings().mockMaker(MockMakers.INLINE))) {
            gd.when(GriefDefender::getRegistry).thenReturn(registry);
            provider.when(LuckPermsProvider::get).thenReturn(luckPerms);
            plugin.onEnable();
            verify(manager, never()).disablePlugin(plugin);
            assertNotNull(plugin.flight());
            verify(manager).registerEvents(plugin.flight(), plugin);
            assertEquals(expected, plugin.playtime().recipientGroups());
            assertEquals(allowGuests ? List.of("container") : List.of(), plugin.claimAccess().guestTypeIds());
            if (plugin.playtime().recipientGroups().isEmpty()) {
                assertFalse(plugin.playtime().canReceive(mock(Player.class)));
                verifyNoInteractions(luckPerms);
            }
        } finally {
            plugin.onDisable();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"GriefDefender", "LuckPerms", "PlayTimeManager"})
    void rejectsAPluginThatWasFoundButFailedToEnable(String name) {
        PluginManager manager = enabledDependencies();
        when(manager.getPlugin(name).isEnabled()).thenReturn(false);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> FlyCreditPlugin.requireDependencies(manager));
        assertTrue(error.getMessage().contains(name + " is disabled"));
        assertTrue(error.getMessage().contains("cannot start the database or enable flight"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GriefDefender", "LuckPerms", "PlayTimeManager"})
    void rejectsAMissingDependencyWithItsName(String name) {
        PluginManager manager = enabledDependencies();
        when(manager.getPlugin(name)).thenReturn(null);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> FlyCreditPlugin.requireDependencies(manager));
        assertTrue(error.getMessage().contains(name + " is missing"));
    }
}
