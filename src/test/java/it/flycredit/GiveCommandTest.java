package it.flycredit;

import it.flycredit.integration.PlayTimeHook;
import it.flycredit.core.Language;
import it.flycredit.core.Amounts;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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
        when(ranks.recipientGroups()).thenReturn(Set.of("member"));
        when(ranks.canReceive(recipient)).thenReturn(true);
        doAnswer(i -> { i.getArgument(0, Runnable.class).run(); return null; }).when(plugin).onMain(any());
        doCallRealMethod().when(plugin).message(any(), anyString(), any(Object[].class));
        doCallRealMethod().when(plugin).report(any(), any());
        database.open(directory.resolve("gifts.db"));
        database.submit(s -> s.addCredit(from, 300_000, "test")).get(3, TimeUnit.SECONDS);
    }
    @AfterEach void close() throws Exception { database.close(); }

    @ParameterizedTest
    @CsvSource({"en-US,it-IT,ENGLISH,ITALIAN", "it-CH,fr-FR,ITALIAN,ENGLISH"})
    void giveTransfersCreditAndNotifiesEachPlayerInTheirClientLanguage(
            String senderLocale, String recipientLocale, Language senderLanguage, Language recipientLanguage) throws Exception {
        when(sender.locale()).thenReturn(Locale.forLanguageTag(senderLocale));
        when(recipient.locale()).thenReturn(Locale.forLanguageTag(recipientLocale));
        commands.onCommand(sender, command, "flycredit", new String[]{"give", "Marco", "2"});
        database.critical(s -> null).get(3, TimeUnit.SECONDS);
        assertEquals(180_000L, database.submit(s -> s.account(from).available()).get(3, TimeUnit.SECONDS));
        assertEquals(120_000L, database.submit(s -> s.account(to).available()).get(3, TimeUnit.SECONDS));
        verify(sender, timeout(3000)).sendMessage(endsWith(senderLanguage.text("transfer.sent", "Marco", Amounts.format(120_000))));
        verify(recipient, timeout(3000)).sendMessage(endsWith(recipientLanguage.text("transfer.received", "Alex", Amounts.format(120_000))));
        verify(ranks).canReceive(recipient);
    }
    @Test void previousSenderPermissionStillControlsGive() throws Exception {
        when(sender.hasPermission("flycredit.pay")).thenReturn(false);
        commands.onCommand(sender, command, "flycredit", new String[]{"give", "Marco", "2"});
        verify(sender).sendMessage(endsWith("Missing permission: flycredit.pay."));
        assertEquals(300_000L, database.submit(s -> s.account(from).available()).get(3, TimeUnit.SECONDS));
    }
    @Test void completionSuggestsGiveAsTheTransferCommand() {
        assertEquals(List.of("give"), commands.onTabComplete(sender, command, "flycredit", new String[]{"g"}));
        assertFalse(commands.onTabComplete(sender, command, "flycredit", new String[]{""}).contains("pay"));
    }
    @Test void ineligibleRecipientDoesNotDebitTheSender() throws Exception {
        when(ranks.canReceive(recipient)).thenReturn(false);
        commands.onCommand(sender, command, "flycredit", new String[]{"give", "Marco", "2"});
        verify(sender).sendMessage(endsWith(Language.ENGLISH.text("transfer.group")));
        assertEquals(300_000L, database.submit(s -> s.account(from).available()).get(3, TimeUnit.SECONDS));
    }
    @Test void emptyAllowlistDisablesGiftsWithoutDebitingOrNotifyingTheRecipient() throws Exception {
        when(ranks.recipientGroups()).thenReturn(Set.of());
        commands.onCommand(sender, command, "flycredit", new String[]{"give", "Marco", "2"});
        verify(sender).sendMessage(endsWith("Gifts are disabled on this server."));
        verify(ranks, never()).canReceive(any());
        verify(recipient, never()).sendMessage(anyString());
        assertEquals(300_000L, database.submit(s -> s.account(from).available()).get(3, TimeUnit.SECONDS));
        assertEquals(0L, database.submit(s -> s.account(to).available()).get(3, TimeUnit.SECONDS));
    }

    @Test void aOneMinuteGiftHasAnExactCompactDuration() {
        when(sender.locale()).thenReturn(Locale.ITALY);
        commands.onCommand(sender, command, "flycredit", new String[]{"give", "Marco", "1"});
        verify(sender, timeout(3000)).sendMessage(endsWith(Language.ITALIAN.text("transfer.sent", "Marco",
                Amounts.format(60_000))));
        verify(recipient, timeout(3000)).sendMessage(endsWith("Received 1m 00s from Alex."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.54", "1,54"})
    void giveMovesExactlyOneMinuteAndFiftyFourSeconds(String amount) throws Exception {
        when(sender.locale()).thenReturn(Locale.ITALY);
        when(recipient.locale()).thenReturn(Locale.FRENCH);
        commands.onCommand(sender, command, "flycredit", new String[]{"give", "Marco", amount});

        verify(sender, timeout(3000)).sendMessage(endsWith(Language.ITALIAN.text("transfer.sent", "Marco",
                Amounts.format(114_000))));
        verify(recipient, timeout(3000)).sendMessage(endsWith("Received 1m 54s from Alex."));
        var debit = database.submit(s -> s.account(from)).get(3, TimeUnit.SECONDS);
        var credit = database.submit(s -> s.account(to)).get(3, TimeUnit.SECONDS);
        assertEquals(186_000, debit.available());
        assertEquals(114_000, debit.sent());
        assertEquals(114_000, credit.available());
        assertEquals(114_000, credit.received());
    }

    @Test void invalidSecondsLeaveBothBalancesAlone() throws Exception {
        commands.onCommand(sender, command, "flycredit", new String[]{"give", "Marco", "1,60"});
        verify(sender).sendMessage(endsWith(Language.ENGLISH.text("amount.format")));
        verify(recipient, never()).sendMessage(anyString());
        assertEquals(300_000, database.submit(s -> s.account(from).available()).get(3, TimeUnit.SECONDS));
        assertEquals(0, database.submit(s -> s.account(to).available()).get(3, TimeUnit.SECONDS));
    }

    @Test void anAsyncBalanceErrorUsesTheSenderLanguage() throws Exception {
        when(sender.locale()).thenReturn(Locale.ITALY);
        commands.onCommand(sender, command, "flycredit", new String[]{"give", "Marco", "6"});
        verify(sender, timeout(3000)).sendMessage(endsWith(Language.ITALIAN.text("transfer.insufficient", "5m 00s")));
        verify(recipient, never()).sendMessage(anyString());
        assertEquals(300_000L, database.submit(s -> s.account(from).available()).get(3, TimeUnit.SECONDS));
    }

    @Test void helpKeepsTheEnglishCommandSyntaxForItalianPlayers() {
        when(sender.locale()).thenReturn(Locale.ITALY);
        var meta = mock(io.papermc.paper.plugin.configuration.PluginMeta.class);
        when(meta.getAuthors()).thenReturn(List.of("Fr4ncyILGrande"));
        when(plugin.getPluginMeta()).thenReturn(meta);
        commands.onCommand(sender, command, "flycredit", new String[]{"help"});
        verify(sender).sendMessage(endsWith(Language.ITALIAN.text("help.owner", "Fr4ncyILGrande")));
        verify(sender).sendMessage(contains("/flycredit give <player> <minutes>"));
        assertEquals(List.of("give"), commands.onTabComplete(sender, command, "flycredit", new String[]{"g"}));
    }
}
