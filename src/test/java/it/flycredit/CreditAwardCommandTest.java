package it.flycredit;

import java.nio.file.Path;
import java.util.UUID;
import java.util.Locale;
import java.util.List;
import it.flycredit.core.CreditStore;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static it.flycredit.ChatOutput.*;
import java.util.concurrent.TimeUnit;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockMakers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CreditAwardCommandTest {
    @TempDir Path directory;
    final DatabaseWorker database = new DatabaseWorker();
    final FlyCreditPlugin plugin = mock(FlyCreditPlugin.class, withSettings().mockMaker(MockMakers.INLINE));
    final FlyCreditCommands commands = new FlyCreditCommands(plugin);
    final ConsoleCommandSender console = mock(ConsoleCommandSender.class);
    final Command root = mock(Command.class), award = mock(Command.class);
    final Player player = mock(Player.class);
    final UUID id = UUID.randomUUID();

    @BeforeEach void setup() throws Exception {
        database.open(directory.resolve("awards.db"));
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPlayerExact("Alex")).thenReturn(player);
        when(server.getPlayer(id)).thenReturn(player);
        when(player.getUniqueId()).thenReturn(id);
        when(plugin.database()).thenReturn(database);
        doAnswer(call -> { call.getArgument(0, Runnable.class).run(); return null; }).when(plugin).onMain(any());
        when(console.hasPermission("flycredit.admin")).thenReturn(true);
        when(root.getName()).thenReturn("flycredit");
        when(award.getName()).thenReturn("flycreditadd");
        database.submit(s -> s.addCredit(id, 300_000, "test")).get(3, TimeUnit.SECONDS);
    }

    @AfterEach void close() throws Exception { database.close(); }

    @Test void bothCommandsAddToTheAvailableBalanceDuringAFlight() throws Exception {
        database.submit(s -> s.reserve(id, UUID.randomUUID(), 120_000)).get(3, TimeUnit.SECONDS);
        commands.onCommand(console, root, "flycredit", new String[]{"add", "Alex", "1,54"});
        commands.onCommand(console, award, "flycreditadd", new String[]{id.toString(), "3"});

        var account = database.submit(s -> s.account(id)).get(3, TimeUnit.SECONDS);
        assertEquals(474_000, account.available());
        assertEquals(120_000, account.reserved());
        verify(plugin, timeout(3000)).message(console, "account.balanceChanged", "Alex", "1m 54s", "4m 54s");
        verify(plugin, timeout(3000)).message(player, "account.yourBalance", "3m 00s", "7m 54s");
    }

    @Test void commandsCannotReplaceOrDeductExistingCredit() throws Exception {
        commands.onCommand(console, root, "flycredit", new String[]{"set", "Alex", "1"});
        commands.onCommand(console, root, "flycredit", new String[]{"add", "Alex", "-1"});
        commands.onCommand(console, award, "flycreditadd", new String[]{"Alex", "0"});

        verify(plugin).message(console, "command.unknown", "/flycredit help");
        assertTrue(commands.onTabComplete(console, root, "flycredit", new String[]{"s"}).isEmpty());
        assertEquals(300_000L, database.submit(s -> s.account(id).available()).get(3, TimeUnit.SECONDS));
    }

    @Test void grantingCreditStillRequiresTheAdminPermission() throws Exception {
        when(console.hasPermission("flycredit.admin")).thenReturn(false);
        commands.onCommand(console, root, "flycredit", new String[]{"add", "Alex", "2"});
        verify(plugin).message(console, "command.permission");
        assertEquals(300_000L, database.submit(s -> s.account(id).available()).get(3, TimeUnit.SECONDS));
    }
    @ParameterizedTest
    @ValueSource(strings = {"it-IT", "en-GB"})
    void infoShowsExactlyAvailableCreditAndLifetimeUsageDuringFlight(String locale) throws Exception {
        var flights = mock(FlightService.class, withSettings().mockMaker(MockMakers.INLINE));
        when(plugin.flight()).thenReturn(flights);
        when(player.locale()).thenReturn(Locale.forLanguageTag(locale));
        UUID previous = UUID.randomUUID(), current = UUID.randomUUID();
        database.submit(s -> {
            s.observe(id, "Alex", 72_000L);
            s.reserve(id, previous, 60_000);
            s.checkpoint(id, previous, 20_000, true);
            s.reserve(id, current, 90_000);
            s.checkpoint(id, current, 80_000, false);
            return null;
        }).get(3, TimeUnit.SECONDS);
        when(flights.usageSnapshot(id)).thenReturn(new CreditStore.Checkpoint(id, current, 75_000, false));
        commands.onCommand(player, root, "flycredit", new String[]{"info"});
        var panel = capture(player);
        var lines = plain(panel).lines().toList();
        assertEquals(locale.startsWith("it")
                ? List.of("FlyCredit » Il tuo credito di volo", "Credito disponibile  ·  2m 50s",
                        "Tempo totale di volo utilizzato  ·  55s")
                : List.of("FlyCredit » Your flight credit", "Available credit  ·  2m 50s",
                        "Total flight time used  ·  55s"), lines);
        assertNotEquals(part(panel, "2m 50s").color(), part(panel,
                locale.startsWith("it") ? "Credito disponibile" : "Available credit").color());
        assertEquals(50_000L, database.submit(s -> s.account(id).used()).get(3, TimeUnit.SECONDS));
        verify(plugin, never()).playtime();
        verify(plugin, never()).report(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"flycredit", "flycreditinfo"})
    void inspectingAnAccountUsesTheSameTwoRowsThroughBothCommandNames(String name) {
        var flights = mock(FlightService.class, withSettings().mockMaker(MockMakers.INLINE));
        when(plugin.flight()).thenReturn(flights);
        when(player.getName()).thenReturn("Alex");
        when(root.getName()).thenReturn(name);
        String[] args = name.equals("flycredit") ? new String[]{"info", "Alex"} : new String[]{"Alex"};
        commands.onCommand(console, root, name, args);
        assertEquals(List.of("FlyCredit » Flight credit for Alex", "Available credit  ·  5m 00s",
                "Total flight time used  ·  0s"), plain(capture(console)).lines().toList());
    }
}
