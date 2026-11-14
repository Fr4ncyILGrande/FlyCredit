package it.flycredit;

import it.flycredit.integration.PlayTimeHook;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockMakers;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GiveCommandTest {
    @TempDir Path directory;
    final DatabaseWorker database = new DatabaseWorker();
    final FlyCreditPlugin plugin = mock(FlyCreditPlugin.class, withSettings().mockMaker(MockMakers.INLINE));
    final PlayTimeHook ranks = mock(PlayTimeHook.class, withSettings().mockMaker(MockMakers.INLINE));
    final Command command = mock(Command.class);
    final Player sender = mock(Player.class), recipient = mock(Player.class);
    final UUID from = UUID.randomUUID(), to = UUID.randomUUID();
    final FlyCreditCommands commands = new FlyCreditCommands(plugin);

    @BeforeEach void setup() throws Exception {
        var server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPlayerExact("Marco")).thenReturn(recipient);
        when(plugin.playtime()).thenReturn(ranks);
        when(plugin.database()).thenReturn(database);
        when(command.getName()).thenReturn("flycredit");
        when(sender.getUniqueId()).thenReturn(from);
        when(sender.getName()).thenReturn("Alex");
        when(recipient.getUniqueId()).thenReturn(to);
        when(recipient.getName()).thenReturn("Marco");
        when(sender.isOnline()).thenReturn(true);
        when(recipient.isOnline()).thenReturn(true);
        when(sender.canSee(recipient)).thenReturn(true);
        when(sender.hasPermission("flycredit.pay")).thenReturn(true);
        when(ranks.canReceive(recipient)).thenReturn(true);
        doAnswer(i -> { i.getArgument(0, Runnable.class).run(); return null; }).when(plugin).onMain(any());
        database.open(directory.resolve("gifts.db"));
        database.submit(s -> s.change(from, 300_000, false, "test")).get(3, TimeUnit.SECONDS);
    }
    @AfterEach void close() throws Exception { database.close(); }

    @Test void giveTransfersCreditAndNotifiesBothPlayers() throws Exception {
        commands.onCommand(sender, command, "flycredit", new String[]{"give", "Marco", "2"});
        database.critical(s -> null).get(3, TimeUnit.SECONDS);
        assertEquals(180_000L, database.submit(s -> s.account(from).available()).get(3, TimeUnit.SECONDS));
        assertEquals(120_000L, database.submit(s -> s.account(to).available()).get(3, TimeUnit.SECONDS));
        verify(plugin, timeout(3000)).message(sender, "Ceduti 0h 02m 00s a Marco.");
        verify(plugin, timeout(3000)).message(recipient, "Hai ricevuto 0h 02m 00s da Alex.");
        verify(ranks).canReceive(recipient);
    }
    @Test void previousSenderPermissionStillControlsGive() throws Exception {
        when(sender.hasPermission("flycredit.pay")).thenReturn(false);
        commands.onCommand(sender, command, "flycredit", new String[]{"give", "Marco", "2"});
        verify(plugin).message(sender, "Non hai il permesso flycredit.pay.");
        assertEquals(300_000L, database.submit(s -> s.account(from).available()).get(3, TimeUnit.SECONDS));
    }
    @Test void completionSuggestsGiveAsTheTransferCommand() {
        assertEquals(List.of("give"), commands.onTabComplete(sender, command, "flycredit", new String[]{"g"}));
        assertFalse(commands.onTabComplete(sender, command, "flycredit", new String[]{""}).contains("pay"));
    }
    @Test void ineligibleRecipientDoesNotDebitTheSender() throws Exception {
        when(ranks.canReceive(recipient)).thenReturn(false);
        var config = new org.bukkit.configuration.file.YamlConfiguration();
        config.set("transfer.allowed-recipient-groups", List.of("cercatore", "elite"));
        when(plugin.getConfig()).thenReturn(config);
        commands.onCommand(sender, command, "flycredit", new String[]{"give", "Marco", "2"});
        verify(plugin).message(eq(sender), contains("gruppi abilitati"));
        assertEquals(300_000L, database.submit(s -> s.account(from).available()).get(3, TimeUnit.SECONDS));
    }
}
