package it.flycredit;

import java.nio.file.Path;
import java.util.UUID;
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
        verify(plugin, timeout(3000)).message(console, "account.balanceChanged", "Alex", "0h 04m 54s");
        verify(plugin, timeout(3000)).message(player, "account.yourBalance", "0h 07m 54s");
    }

    @Test void commandsCannotReplaceOrDeductExistingCredit() throws Exception {
        commands.onCommand(console, root, "flycredit", new String[]{"set", "Alex", "1"});
        commands.onCommand(console, root, "flycredit", new String[]{"add", "Alex", "-1"});
        commands.onCommand(console, award, "flycreditadd", new String[]{"Alex", "0"});

        verify(plugin).message(console, "command.unknown");
        assertTrue(commands.onTabComplete(console, root, "flycredit", new String[]{"s"}).isEmpty());
        assertEquals(300_000L, database.submit(s -> s.account(id).available()).get(3, TimeUnit.SECONDS));
    }

    @Test void grantingCreditStillRequiresTheAdminPermission() throws Exception {
        when(console.hasPermission("flycredit.admin")).thenReturn(false);
        commands.onCommand(console, root, "flycredit", new String[]{"add", "Alex", "2"});
        verify(plugin).message(console, "command.permission", "flycredit.admin");
        assertEquals(300_000L, database.submit(s -> s.account(id).available()).get(3, TimeUnit.SECONDS));
    }
}
