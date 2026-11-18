package it.flycredit;

import it.flycredit.core.CreditException;
import it.flycredit.core.Language;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockMakers;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LanguageTest {
    @ParameterizedTest
    @CsvSource({"it,ITALIAN", "it-IT,ITALIAN", "it-CH,ITALIAN", "en-US,ENGLISH",
                "en-GB,ENGLISH", "fr-IT,ENGLISH", "de-DE,ENGLISH", "es-MX,ENGLISH"})
    void selectsTheLanguageIndependentlyOfTheClientRegion(String tag, Language expected) {
        assertEquals(expected, Language.forLocale(Locale.forLanguageTag(tag)));
    }

    @Test void missingLocaleUsesEnglish() {
        assertEquals(Language.ENGLISH, Language.forLocale(null));
        assertEquals(Language.ENGLISH, Language.forLocale(Locale.ROOT));
    }

    @Test void onlyPlayerMessagesHaveItalianTranslations() throws Exception {
        Properties english = resources("/lang/flycredit.properties");
        Properties italian = resources("/lang/flycredit_it.properties");
        Set<String> playerKeys = new HashSet<>(english.stringPropertyNames());
        playerKeys.removeIf(key -> key.startsWith("console."));
        assertEquals(playerKeys, italian.stringPropertyNames());
        Pattern argument = Pattern.compile("%[1-9][0-9]*\\$s");
        for (String key : playerKeys) {
            assertFalse(italian.getProperty(key).isBlank(), key);
            assertEquals(argument.matcher(english.getProperty(key)).results().map(m -> m.group()).sorted().toList(),
                    argument.matcher(italian.getProperty(key)).results().map(m -> m.group()).sorted().toList(), key);
            if (key.startsWith("usage.")) {
                String en = english.getProperty(key), it = italian.getProperty(key);
                assertEquals(en.substring(en.indexOf('/')), it.substring(it.indexOf('/')), key);
            }
        }
    }

    @Test void consoleStaysInEnglishAndPlayerChatFollowsTheCurrentLocale() {
        var plugin = mock(FlyCreditPlugin.class, withSettings().mockMaker(MockMakers.INLINE));
        doCallRealMethod().when(plugin).message(any(), anyString(), any(Object[].class));
        var console = mock(ConsoleCommandSender.class);
        var player = mock(Player.class);
        when(player.locale()).thenReturn(Locale.ITALY, Locale.JAPANESE);
        plugin.message(player, "help.owner", "Fr4ncyILGrande");
        plugin.message(player, "help.owner", "Fr4ncyILGrande");
        plugin.message(console, "help.owner", "Fr4ncyILGrande");
        verify(player).sendMessage(endsWith(Language.ITALIAN.text("help.owner", "Fr4ncyILGrande")));
        verify(player).sendMessage(endsWith("Plugin by Fr4ncyILGrande"));
        verify(console).sendMessage(endsWith("Plugin by Fr4ncyILGrande"));
        assertEquals("Missing permission: flycredit.use.",
                new CreditException("command.permission", "flycredit.use").getMessage());
    }

    private Properties resources(String path) throws Exception {
        var result = new Properties();
        try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream(path)),
                StandardCharsets.UTF_8)) {
            result.load(reader);
        }
        return result;
    }
}
