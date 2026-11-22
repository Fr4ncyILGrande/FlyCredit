package it.flycredit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static it.flycredit.ChatOutput.*;
import it.flycredit.integration.PlayTimeHook;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommandPresentationTest {
    final FlyCreditPlugin plugin = mock(FlyCreditPlugin.class, withSettings().mockMaker(MockMakers.INLINE));
    final FlyCreditCommands commands = new FlyCreditCommands(plugin);
    final Command command = mock(Command.class);
    final Player player = mock(Player.class);
    final Server server = mock(Server.class);
    final PlayTimeHook ranks = mock(PlayTimeHook.class, withSettings().mockMaker(MockMakers.INLINE));

    @BeforeEach void setup() {
        when(plugin.getServer()).thenReturn(server);
        when(plugin.playtime()).thenReturn(ranks);
        when(ranks.recipientGroups()).thenReturn(Set.of("member"));
        doCallRealMethod().when(plugin).message(any(), anyString(), any(Object[].class));
        when(command.getName()).thenReturn("flycredit");
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.hasPermission("flycredit.use")).thenReturn(true);
        when(player.hasPermission("flycredit.pay")).thenReturn(true);
    }

    @Test void rootSuggestionsHideTechnicalNamesAndPreserveOtherPlugins() {
        var entries = new ArrayList<>(List.of("flycredit", "fc", "flycreditadd", "flycreditinfo",
                "flycredit:flycredit", "flycredit:flycreditadd", "flycredit:flycreditinfo", "flycredit:fc",
                "worldedit:wand", "help"));
        commands.commandList(new PlayerCommandSendEvent(player, entries));
        assertEquals(List.of("flycredit", "fc", "worldedit:wand", "help"), entries);
    }

    @Test void completionsReflectAvailableCommandsWithoutAutomaticDurationExamples() {
        var normal = commands.onTabComplete(player, command, "flycredit", new String[]{""});
        assertEquals(List.of("give", "help", "info", "off"), normal);
        assertFalse(normal.contains("add"));
        assertFalse(normal.contains("diagnostics"));

        when(player.hasPermission("flycredit.admin")).thenReturn(true);
        var staff = commands.onTabComplete(player, command, "flycredit", new String[]{""});
        assertTrue(staff.containsAll(List.of("add", "diagnostics", "info")));
        assertEquals(1, staff.stream().filter("info"::equals).count());
        assertTrue(commands.onTabComplete(player, command, "flycredit", new String[]{"add", "Alex", "1"}).isEmpty());
        assertFalse(commands.onTabComplete(player, command, "fc", new String[]{""}).contains("1"));

        var console = mock(ConsoleCommandSender.class);
        when(console.hasPermission("flycredit.admin")).thenReturn(true);
        assertEquals(List.of("add", "diagnostics", "help", "info"),
                commands.onTabComplete(console, command, "flycredit", new String[]{""}));
    }

    @Test void giftSuggestionsRespectVisibilityEligibilityAndSenderPermission() {
        Player eligible = mock(Player.class), hidden = mock(Player.class), blocked = mock(Player.class);
        when(eligible.getName()).thenReturn("Alex");
        when(player.canSee(eligible)).thenReturn(true);
        when(player.canSee(blocked)).thenReturn(true);
        when(ranks.canReceive(eligible)).thenReturn(true);
        doReturn(List.of(player, eligible, hidden, blocked)).when(server).getOnlinePlayers();
        assertEquals(List.of("Alex"), commands.onTabComplete(player, command, "flycredit", new String[]{"give", ""}));
        verify(ranks, never()).canReceive(hidden);
        when(player.hasPermission("flycredit.pay")).thenReturn(false);
        assertTrue(commands.onTabComplete(player, command, "flycredit", new String[]{"give", ""}).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"it-IT", "en-GB"})
    void helpIsOnePanelWithAColoredClickableRowForEachAvailableCommand(String locale) {
        boolean italian = locale.startsWith("it");
        when(player.locale()).thenReturn(Locale.forLanguageTag(locale));
        commands.onCommand(player, command, "flycredit", new String[]{});
        Component panel = capture(player);
        String display = plain(panel);
        assertEquals(italian ? "/flycredit » Comandi" : "/flycredit » Commands", display.lines().findFirst().orElseThrow());
        assertEquals(6, display.lines().count());
        assertEquals(display.indexOf("/flycredit"), display.lastIndexOf("/flycredit"));
        assertFalse(display.contains("Fr4ncyILGrande"));
        assertFalse(display.contains("1.30"));
        assertFalse(display.contains("diagnostics"));
        String label = italian ? "give <giocatore> <minuti>" : "give <player> <minutes>";
        Component give = part(panel, label);
        assertEquals(ClickEvent.suggestCommand("/flycredit give "), give.clickEvent());
        assertEquals(NamedTextColor.AQUA, give.color());
        assertEquals(NamedTextColor.GRAY, part(panel, italian ? "Regala tempo di volo" : "Give flight time").color());
        verify(plugin, never()).getPluginMeta();
        verify(plugin, never()).database();
    }

    @Test void limitedHelpAndCompletionMatchWhenGiftsAreDisabled() {
        var flight = mock(FlightService.class, withSettings().mockMaker(MockMakers.INLINE));
        when(plugin.flight()).thenReturn(flight);
        when(ranks.recipientGroups()).thenReturn(Set.of());
        when(player.hasPermission("flycredit.use")).thenReturn(false);
        commands.onCommand(player, command, "fc", new String[]{"help"});
        assertEquals(List.of("/flycredit » Commands", "info  ·  View your credit and flight time",
                "help  ·  Show this list"), plain(capture(player)).lines().toList());
        assertEquals(List.of("help", "info"), commands.onTabComplete(player, command, "fc", new String[]{""}));
        when(flight.busy(player.getUniqueId())).thenReturn(true);
        assertEquals(List.of("help", "info", "off"), commands.onTabComplete(player, command, "fc", new String[]{""}));
    }

    @Test void adminHelpShowsOneInfoRowWithAnOptionalTarget() {
        when(player.hasPermission("flycredit.admin")).thenReturn(true);
        commands.onCommand(player, command, "flycredit", new String[]{"help"});
        List<String> lines = plain(capture(player)).lines().toList();
        assertEquals(1, lines.stream().filter(line -> line.startsWith("info")).count());
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("info [player]")));
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("add <player> <minutes>")));
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("diagnostics")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"it-IT", "en-US"})
    void protectedCommandsAndAliasesDenyBeforeCheckingUsageOrAmount(String locale) {
        when(player.locale()).thenReturn(Locale.forLanguageTag(locale));
        when(player.hasPermission(anyString())).thenReturn(false);
        var invocations = List.of(new String[]{"flycredit", "add"}, new String[]{"flycreditadd", "Alex", "bad"},
                new String[]{"flycredit", "info", "Alex", "extra"}, new String[]{"flycreditinfo", "Alex"},
                new String[]{"flycredit", "diagnostics", "extra"}, new String[]{"flycredit", "give"},
                new String[]{"flycredit", "send", "Alex", "bad"}, new String[]{"flycredit", "1.60"});
        for (String[] invocation : invocations) {
            when(command.getName()).thenReturn(invocation[0]);
            commands.onCommand(player, command, invocation[0], java.util.Arrays.copyOfRange(invocation, 1, invocation.length));
        }
        String denial = locale.startsWith("it") ? "Non hai accesso a questo comando." : "You don’t have access to this command.";
        verify(player, times(invocations.size())).sendMessage(notice(denial));
        verify(plugin, never()).database();
    }

    @ParameterizedTest
    @CsvSource({"it-IT,1.60,Durata non valida. Controlla il valore e riprova.",
            "en-US,-2,Invalid duration. Check the value and try again.",
            "it-IT,0,La durata deve essere maggiore di zero.",
            "en-US,999999999999999999999999,That duration is too long. Choose a shorter one.",
            "it-IT,abc,Comando o durata non validi. Usa /flycredit help."})
    void invalidInputExplainsTheProblemWithoutExamples(String locale, String input, String expected) {
        when(player.locale()).thenReturn(Locale.forLanguageTag(locale));
        commands.onCommand(player, command, "flycredit", new String[]{input});
        verify(player).sendMessage(notice(expected));
        verify(plugin, never()).flight();
        verify(plugin, never()).database();
    }

    @ParameterizedTest
    @ValueSource(strings = {"it-IT", "en-US"})
    void diagnosticsUsesOneHeadingAndLocalizedMembershipText(String locale) {
        boolean italian = locale.startsWith("it");
        when(player.locale()).thenReturn(Locale.forLanguageTag(locale));
        when(player.hasPermission("flycredit.admin")).thenReturn(true);
        var manager = mock(PluginManager.class);
        var config = new YamlConfiguration();
        config.set("transfer.membership", "INHERITED");
        when(plugin.getConfig()).thenReturn(config);
        when(server.getPluginManager()).thenReturn(manager);
        when(server.getVersion()).thenReturn("Paper 26.3");
        var claims = mock(it.flycredit.integration.ClaimAccess.class, withSettings().mockMaker(MockMakers.INLINE));
        when(plugin.claimAccess()).thenReturn(claims);
        when(claims.guestTypeIds()).thenReturn(List.of("container"));
        commands.onCommand(player, command, "flycredit", new String[]{"diagnostics"});
        String message = plain(capture(player));
        assertEquals(message.indexOf("FlyCredit"), message.lastIndexOf("FlyCredit"));
        assertTrue(message.contains(italian ? "anche gruppi ereditati" : "includes inherited groups"));
        assertTrue(message.contains(italian ? "Diagnostica" : "Diagnostics"));
        assertFalse(message.contains("INHERITED"));
    }
}
