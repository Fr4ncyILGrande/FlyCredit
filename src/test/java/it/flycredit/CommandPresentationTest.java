package it.flycredit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import it.flycredit.core.Language;
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

    @BeforeEach void setup() {
        when(plugin.getServer()).thenReturn(server);
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

    @Test void completionsFollowPermissionsAndOfferSubcommandsAndValidDurations() {
        var normal = commands.onTabComplete(player, command, "flycredit", new String[]{""});
        assertTrue(normal.containsAll(List.of("give", "off", "info", "help", "1", "1.30")));
        assertFalse(normal.contains("add"));
        assertFalse(normal.contains("diagnostics"));

        when(player.hasPermission("flycredit.admin")).thenReturn(true);
        var staff = commands.onTabComplete(player, command, "flycredit", new String[]{""});
        assertTrue(staff.containsAll(List.of("add", "diagnostics", "info")));
        assertEquals(1, staff.stream().filter("info"::equals).count());
        assertEquals(List.of("1", "1.30", "10"), commands.onTabComplete(player, command, "flycredit",
                new String[]{"add", "Alex", "1"}));
        assertFalse(commands.onTabComplete(player, command, "fc", new String[]{""}).contains("1"));

        var console = mock(ConsoleCommandSender.class);
        when(console.hasPermission("flycredit.admin")).thenReturn(true);
        assertEquals(List.of("add", "diagnostics", "help", "info"),
                commands.onTabComplete(console, command, "flycredit", new String[]{""}));
    }

    @Test void giftSuggestionsRespectVisibilityEligibilityAndSenderPermission() {
        var ranks = mock(PlayTimeHook.class, withSettings().mockMaker(MockMakers.INLINE));
        when(plugin.playtime()).thenReturn(ranks);
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

    @Test void bareCommandShowsHelpWithoutLoadingTheAccount() {
        when(player.locale()).thenReturn(Locale.ITALY);
        var metadata = mock(io.papermc.paper.plugin.configuration.PluginMeta.class);
        when(metadata.getAuthors()).thenReturn(List.of("Fr4ncyILGrande"));
        when(plugin.getPluginMeta()).thenReturn(metadata);
        doCallRealMethod().when(plugin).message(any(), anyString(), any(Object[].class));
        commands.onCommand(player, command, "flycredit", new String[]{});
        verify(player).sendMessage(endsWith(Language.ITALIAN.text("help.flight")));
        verify(player).sendMessage(endsWith(Language.ITALIAN.text("help.give")));
        verify(plugin, never()).database();
        verify(plugin, never()).report(any(), any());
    }
}
