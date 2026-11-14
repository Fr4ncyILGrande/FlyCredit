package it.flycredit;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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
    @ValueSource(strings = {"GriefDefender", "LuckPerms", "PlayTimeManager"})
    void rejectsAPluginThatWasFoundButFailedToEnable(String name) {
        PluginManager manager = enabledDependencies();
        when(manager.getPlugin(name).isEnabled()).thenReturn(false);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> FlyCreditPlugin.requireDependencies(manager));
        assertTrue(error.getMessage().contains(name + " disabilitata"));
        assertTrue(error.getMessage().contains("non avvia database o volo"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GriefDefender", "LuckPerms", "PlayTimeManager"})
    void rejectsAMissingDependencyWithItsName(String name) {
        PluginManager manager = enabledDependencies();
        when(manager.getPlugin(name)).thenReturn(null);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> FlyCreditPlugin.requireDependencies(manager));
        assertTrue(error.getMessage().contains(name + " mancante"));
    }
}
