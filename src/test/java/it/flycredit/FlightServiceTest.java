package it.flycredit;

import com.griefdefender.api.Core;
import com.griefdefender.api.GriefDefender;
import com.griefdefender.api.Registry;
import com.griefdefender.api.claim.Claim;
import com.griefdefender.api.claim.TrustType;
import it.flycredit.core.*;
import it.flycredit.integration.ClaimAccess;
import org.bukkit.*;
import org.bukkit.boss.BossBar;
import org.bukkit.boss.BarColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockMakers;
import org.mockito.ArgumentCaptor;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual session code and SQLite writer; Bukkit players and the GD boundary are controlled doubles. */
class FlightServiceTest {
    @TempDir Path temp;
    DatabaseWorker database;
    FlightService flight;
    FlightService.Host host;
    BossBar bar = mock(BossBar.class);
    Player player;
    World world;
    UUID id = UUID.randomUUID();
    AtomicLong time = new AtomicLong();
    Queue<Runnable> main = new ConcurrentLinkedQueue<>();
    List<Throwable> failures = new ArrayList<>();
    boolean allowed = true, grounded, allowFlight, flying, online = true;
    final Map<NamespacedKey, Object> playerData = new HashMap<>();
    int slowTicks;
    final FallProtection.SlowFall slowFall = new FallProtection.SlowFall() {
        public boolean apply(Player p, int ticks) {
            if (slowTicks != 0) return false;
            slowTicks = ticks;
            return true;
        }
        public void remove(Player p) { slowTicks = 0; }
    };
    @BeforeEach void setup() throws Exception {
        database = new DatabaseWorker(); database.open(temp.resolve("test.db"));
        database.submit(s -> s.addCredit(id, 60_000, "test")).get(3, TimeUnit.SECONDS);
        player = mock(Player.class); world = mock(World.class);
        var data = mock(PersistentDataContainer.class);
        when(player.getPersistentDataContainer()).thenReturn(data);
        when(data.has(any(NamespacedKey.class))).thenAnswer(i -> playerData.containsKey(i.getArgument(0)));
        when(data.get(any(NamespacedKey.class), eq(PersistentDataType.LONG)))
                .thenAnswer(i -> playerData.get(i.getArgument(0)));
        doAnswer(i -> { playerData.put(i.getArgument(0), i.getArgument(2)); return null; })
                .when(data).set(any(NamespacedKey.class), any(PersistentDataType.class), any());
        doAnswer(i -> { playerData.remove(i.getArgument(0)); return null; }).when(data).remove(any(NamespacedKey.class));
        when(player.getUniqueId()).thenReturn(id);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.getLocation()).thenReturn(new Location(world, 0, 70, 0));
        when(player.isOnline()).thenAnswer(i -> online);
        when(player.hasPermission("flycredit.use")).thenReturn(true);
        when(player.getAllowFlight()).thenAnswer(i -> allowFlight);
        when(player.isFlying()).thenAnswer(i -> flying);
        doAnswer(i -> { allowFlight = i.getArgument(0); return null; }).when(player).setAllowFlight(anyBoolean());
        doAnswer(i -> { flying = i.getArgument(0); return null; }).when(player).setFlying(anyBoolean());
        host = new FlightService.Host() {
            public DatabaseWorker database() { return database; }
            public void onMain(Runnable task) { main.add(task); }
            public <T> void persist(DatabaseWorker.Operation<T> op) {
                database.critical(op).whenComplete((r, e) -> { if (e != null) main.add(() -> failClosed(e)); });
            }
            public void message(CommandSender p, String key, Object... arguments) {}
            public void report(CommandSender p, Throwable e) {
                if (!(e instanceof CreditException)) failClosed(e);
            }
            public void failClosed(Throwable e) { failures.add(e); flight.stopAll(false); }
            public BossBar createFlightBar() { return bar; }
            public Player onlinePlayer(UUID uuid) { return online && uuid.equals(id) ? player : null; }
            public void dependencyLost() { flight.stopAll(false); }
        };
        flight = new FlightService(host, (uuid, location) -> allowed, time::get, p -> grounded, 20, 15, false, slowFall);
    }
    @AfterEach void close() throws Exception {
        flight.stopAll(false); flush(); database.close();
    }
    void flush() throws Exception {
        for (int i = 0; i < 3; i++) {
            database.critical(s -> null).get(3, TimeUnit.SECONDS);
            Runnable task; while ((task = main.poll()) != null) task.run();
        }
    }
    CreditStore.Account account() throws Exception { return database.critical(s -> s.account(id)).get(3, TimeUnit.SECONDS); }
    void advance(long millis) { time.addAndGet(millis * 1_000_000L); flight.tick(); }
    @Test void guestFlightRequiresOptInAndRevocationSettlesTheLiveSession() throws Exception {
        var config = new YamlConfiguration();
        config.set(ClaimAccess.TRUST_SETTING, List.of());
        var logger = mock(Logger.class);
        var plugin = mock(FlyCreditPlugin.class, withSettings().mockMaker(MockMakers.INLINE));
        when(plugin.flight()).thenAnswer(call -> flight);
        var command = mock(Command.class);
        when(command.getName()).thenReturn("flycredit");
        var claim = mock(Claim.class);
        when(claim.getOwnerUniqueId()).thenReturn(UUID.randomUUID());
        var container = mock(TrustType.class);
        when(container.getId()).thenReturn("griefdefender:container");
        when(claim.getUserTrusts(container)).thenReturn(Set.of(id));
        var core = mock(Core.class);
        UUID worldId = UUID.randomUUID();
        when(world.getUID()).thenReturn(worldId);
        when(core.isEnabled(worldId)).thenReturn(true);
        when(core.getClaimAt(player.getLocation())).thenReturn(claim);
        var registry = mock(Registry.class);
        when(registry.getAllOf(TrustType.class)).thenReturn(List.of(container));
        try (var gd = mockStatic(GriefDefender.class, withSettings().mockMaker(MockMakers.INLINE))) {
            gd.when(GriefDefender::getCore).thenReturn(core);
            gd.when(GriefDefender::getRegistry).thenReturn(registry);
            var ownersOnly = ClaimAccess.fromConfig(config, logger);
            flight = new FlightService(host, ownersOnly::canFly, time::get, p -> grounded, 20, 15, false, slowFall);
            new FlyCreditCommands(plugin).onCommand(player, command, "flycredit", new String[]{"1"});
            flush();
            assertFalse(allowFlight);
            assertEquals(60_000, account().available());

            config.set(ClaimAccess.TRUST_SETTING, List.of("container"));
            var withGuests = ClaimAccess.fromConfig(config, logger);
            flight = new FlightService(host, withGuests::canFly, time::get, p -> grounded, 20, 15, false, slowFall);
            new FlyCreditCommands(plugin).onCommand(player, command, "flycredit", new String[]{"1"});
            flush();
            assertTrue(allowFlight);
            assertEquals(60_000, account().reserved());
            when(claim.getUserTrusts(container)).thenReturn(Set.of());
            for (int i = 0; i < 5; i++) advance(50);
            flush();
            assertFalse(allowFlight);
            assertEquals(0, account().reserved());
            assertEquals(250, account().used());
            assertEquals(59_750, account().available());
        }
    }
    @ParameterizedTest
    @ValueSource(strings = {"1.54", "1,54"})
    void flightCommandReservesExactlyTheChosenTimeAndOffRefundsTheRemainder(String amount) throws Exception {
        database.submit(s -> s.addCredit(id, 120_000, "test")).get(3, TimeUnit.SECONDS);
        var plugin = mock(FlyCreditPlugin.class, withSettings().mockMaker(MockMakers.INLINE));
        when(plugin.flight()).thenReturn(flight);
        var command = mock(Command.class);
        when(command.getName()).thenReturn("flycredit");
        var commands = new FlyCreditCommands(plugin);

        commands.onCommand(player, command, "flycredit", new String[]{amount});
        flush();
        assertTrue(allowFlight);
        assertEquals(114_000, flight.remaining(id));
        assertEquals(114_000, account().reserved());
        assertEquals(66_000, account().available());

        advance(54_000);
        commands.onCommand(player, command, "flycredit", new String[]{"off"});
        flush();
        assertFalse(allowFlight);
        assertEquals(0, account().reserved());
        assertEquals(54_000, account().used());
        assertEquals(126_000, account().available());
    }

    @Test void startingRequiresTheFullCommandNameAndAnExplicitDuration() throws Exception {
        var plugin = mock(FlyCreditPlugin.class, withSettings().mockMaker(MockMakers.INLINE));
        when(plugin.flight()).thenReturn(flight);
        when(plugin.database()).thenReturn(database);
        var meta = mock(io.papermc.paper.plugin.configuration.PluginMeta.class);
        when(plugin.getPluginMeta()).thenReturn(meta);
        var command = mock(Command.class);
        when(command.getName()).thenReturn("flycredit");

        for (String alias : List.of("fc", "flycredit:fc")) {
            new FlyCreditCommands(plugin).onCommand(player, command, alias, new String[]{"0.30"});
        }
        for (String[] args : List.of(new String[]{}, new String[]{"on"}, new String[]{"on", "1"},
                new String[]{"0.00"}, new String[]{"1.60"}, new String[]{"1", "30"})) {
            new FlyCreditCommands(plugin).onCommand(player, command, "flycredit", args);
        }
        assertThrows(CreditException.class, () -> flight.start(player, 0));
        flush();
        assertFalse(flight.busy(id));
        assertFalse(allowFlight);
        assertEquals(60_000, account().available());
        verify(plugin, never()).report(any(), any());
    }
    @Test void pausedBarFollowsLanguageChangesWithoutSpendingCredit() throws Exception {
        flight = new FlightService(host, (uuid, location) -> allowed, time::get, p -> grounded, 20, 15, true, slowFall);
        grounded = true;
        when(player.locale()).thenReturn(Locale.ITALY);
        flight.start(player, 60_000);
        flush();
        verify(bar).setTitle(contains("In pausa"));

        when(player.locale()).thenReturn(Locale.FRENCH);
        for (int tick = 0; tick < 20; tick++) advance(50);
        verify(bar).setTitle(contains("Paused"));
        assertEquals(60_000L, flight.remaining(id));
    }

    @Test void onlyAirborneFlyingIntervalsAreChargedAndRemainderIsRefunded() throws Exception {
        grounded = true; flight.start(player, 60_000); flush();
        advance(1000); assertEquals(60_000, flight.remaining(id));
        grounded = false; flight.tick(); advance(5000);
        flying = false; flight.toggleFlight(new PlayerToggleFlightEvent(player, false));
        advance(5000); flight.stop(player, null); flush();
        assertEquals(55_000, account().available()); assertEquals(5000, account().used());
        assertFalse(allowFlight); assertTrue(failures.isEmpty());
    }
    @Test void cancelledActivationAndNewActivationRemainIndependent() throws Exception {
        flight.start(player, 60_000);
        flight.stop(player, null);
        flight.start(player, 30_000);
        flush();
        assertTrue(flight.active(id)); assertTrue(allowFlight);
        assertEquals(30_000, flight.remaining(id));
        assertEquals(30_000, account().reserved()); assertEquals(30_000, account().available());
        assertTrue(failures.isEmpty());
    }
    @Test void logoutBeforeActivationCallbackRefundsWithoutEnablingFlight() throws Exception {
        flight.start(player, 60_000); online = false; flight.stop(player, null); flush();
        assertFalse(allowFlight); assertFalse(flight.busy(id)); assertEquals(60_000, account().available());
    }
    @Test void destinationOutsideClaimEndsActiveAndPendingSessions() throws Exception {
        flight.start(player, 60_000); flush(); advance(1000);
        allowed = false;
        flight.move(new PlayerMoveEvent(player, new Location(world, 0, 70, 0), new Location(world, 1, 70, 0)));
        flush();
        assertFalse(allowFlight); assertEquals(59_000, account().available());
        allowed = true; flight.start(player, 59_000); allowed = false;
        flight.move(new PlayerMoveEvent(player, new Location(world, 0, 70, 0), new Location(world, 1, 70, 0)));
        flush(); assertFalse(flight.busy(id)); assertEquals(59_000, account().available());
    }
    @Test void trustRevocationIsDetectedWhileStationary() throws Exception {
        flight.start(player, 60_000); flush(); allowed = false;
        for (int i = 0; i < 5; i++) advance(50);
        flush(); assertFalse(allowFlight); assertFalse(flight.active(id));
        assertEquals(59_750, account().available());
    }
    @Test void exhaustingCreditCannotLeaveFlightEnabled() throws Exception {
        flight.start(player, 1000); flush(); advance(5000); flush();
        assertFalse(allowFlight); assertFalse(flight.active(id));
        assertEquals(59_000, account().available()); assertEquals(1000, account().used());
    }
    @Test void alreadyEnabledFlightFromAnotherPluginIsNotTakenOver() {
        allowFlight = true;
        assertThrows(CreditException.class, () -> flight.start(player, 60_000));
        assertTrue(allowFlight); assertFalse(flight.busy(id));
    }
    @Test void startErrorsIdentifyTheActualRestrictionWithoutReservingCredit() throws Exception {
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
        assertEquals("flight.gameModeRequired",
                assertThrows(CreditException.class, () -> flight.start(player, 30_000)).key());
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        allowed = false;
        assertEquals("flight.claimRequired",
                assertThrows(CreditException.class, () -> flight.start(player, 30_000)).key());
        when(player.hasPermission("flycredit.use")).thenReturn(false);
        assertEquals("command.permission",
                assertThrows(CreditException.class, () -> flight.start(player, 30_000)).key());
        assertEquals(60_000, account().available());
        assertFalse(flight.busy(id));
    }
    @Test void activationErrorAfterReservationIsRefundedByFailClosedShutdown() throws Exception {
        flight.start(player, 60_000);
        when(player.getLocation()).thenThrow(new IllegalStateException("claim plugin unavailable"));
        flush();
        assertFalse(allowFlight); assertEquals(60_000, account().available()); assertEquals(1, failures.size());
    }
    @Test void shutdownReleasesPendingReservationEvenIfMainCallbacksNeverRun() throws Exception {
        flight.start(player, 60_000);
        flight.stopAll(true);
        database.critical(s -> null).get(3, TimeUnit.SECONDS);
        assertEquals(60_000, account().available());
        assertFalse(allowFlight);
        flush(); assertEquals(60_000, account().available());
    }
    @Test void partialSessionLeavesOtherCreditTransferable() throws Exception {
        flight.start(player, 30_000); flush();
        UUID recipient = UUID.randomUUID();
        database.submit(s -> { s.transfer(id, recipient, 30_000); return null; }).get(3, TimeUnit.SECONDS);
        advance(1000); flight.stop(player, null); flush();
        assertEquals(29_000, account().available());
        assertEquals(30_000, database.submit(s -> s.account(recipient)).get(3, TimeUnit.SECONDS).available());
    }
    @Test void repeatedStartDoesNotToggleOrDoubleReserve() throws Exception {
        flight.start(player, 60_000);
        assertThrows(CreditException.class, () -> flight.start(player, 60_000));
        flush(); assertEquals(60_000, account().reserved());
        assertThrows(CreditException.class, () -> flight.start(player, 60_000));
        assertTrue(flight.active(id));
    }
    @Test void checkpointThenStopKeepsExactUsedTotal() throws Exception {
        flight.start(player, 60_000); flush();
        for (int i = 0; i < 20; i++) advance(50);
        flush(); assertEquals(1000, account().used());
        advance(250); flight.stop(player, null); flush();
        assertEquals(1250, account().used()); assertEquals(58_750, account().available());
    }
    @Test void flightFlagExceptionCannotSkipRefundOrLeaveAllowFlightEnabled() throws Exception {
        flight.start(player, 60_000); flush(); advance(1000);
        doThrow(new IllegalStateException("simulated plugin failure")).when(player).setFlying(false);
        assertThrows(IllegalStateException.class, () -> flight.stop(player, null));
        flush();
        assertFalse(allowFlight); assertEquals(59_000, account().available()); assertEquals(1000, account().used());
    }
    @Test void usePermissionRevocationStopsAndSettlesTheSession() throws Exception {
        flight.start(player, 60_000); flush();
        when(player.hasPermission("flycredit.use")).thenReturn(false);
        for (int i = 0; i < 5; i++) advance(50);
        flush(); assertFalse(allowFlight); assertEquals(59_750, account().available());
    }
    @Test void fallProtectionCancelsOnlyOneFall() throws Exception {
        flight.start(player, 60_000); flush(); flight.stop(player, null); flush();
        var first = mock(org.bukkit.event.entity.EntityDamageEvent.class);
        var second = mock(org.bukkit.event.entity.EntityDamageEvent.class);
        for (var event : List.of(first, second)) {
            when(event.getEntity()).thenReturn(player);
            when(event.getCause()).thenReturn(org.bukkit.event.entity.EntityDamageEvent.DamageCause.FALL);
            flight.landings().fall(event);
        }
        verify(first).setCancelled(true); verify(second, never()).setCancelled(true);
    }
    @Test void fallProtectionExpires() throws Exception {
        flight.start(player, 60_000); flush(); flight.stop(player, null); flush(); advance(16_000);
        var event = mock(org.bukkit.event.entity.EntityDamageEvent.class);
        when(event.getEntity()).thenReturn(player);
        when(event.getCause()).thenReturn(org.bukkit.event.entity.EntityDamageEvent.DamageCause.FALL);
        flight.landings().fall(event); verify(event, never()).setCancelled(true);
    }
    @ParameterizedTest
    @ValueSource(strings = {"off", "stop"})
    void manualStopRefundsCreditAndAllowsNormalFallDamage(String action) throws Exception {
        flight.start(player, 60_000);
        flush();
        advance(1000);
        var plugin = mock(FlyCreditPlugin.class, withSettings().mockMaker(MockMakers.INLINE));
        when(plugin.flight()).thenReturn(flight);
        var command = mock(Command.class);
        when(command.getName()).thenReturn("flycredit");
        new FlyCreditCommands(plugin).onCommand(player, command, "flycredit", new String[]{action});
        flush();

        assertFalse(allowFlight);
        assertFalse(flight.busy(id));
        assertEquals(59_000, account().available());
        assertEquals(1000, account().used());
        verify(player, never()).setFallDistance(0);
        verify(impact(flight.landings()), never()).setCancelled(true);
        flight.landings().suspend(player);
        assertEquals(0, slowTicks);
        assertTrue(playerData.isEmpty());
        verify(impact(afterRestart()), never()).setCancelled(true);
    }
    @Test void cancellingAPendingFlightCannotCreateLandingProtection() throws Exception {
        flight.start(player, 60_000);
        flight.stopManually(player);
        flush();
        assertFalse(allowFlight);
        assertEquals(60_000, account().available());
        verify(impact(flight.landings()), never()).setCancelled(true);
        assertTrue(playerData.isEmpty());
    }
    @Test void compactBarShowsAConsistentClockPauseAndRemainingFraction() throws Exception {
        flight = new FlightService(host, (uuid, location) -> allowed, time::get, p -> grounded, 20, 15, true, slowFall);
        when(player.locale()).thenReturn(Locale.ITALY);
        flight.start(player, 10_000);
        flush();
        var titles = ArgumentCaptor.forClass(String.class);
        verify(bar).setTitle(titles.capture());
        assertEquals("Volo  •  00:10", ChatColor.stripColor(titles.getValue()));
        verify(bar).setProgress(1.0);

        for (int i = 0; i < 5; i++) advance(100);
        verify(bar).setProgress(0.95);
        verify(bar, times(1)).setTitle(anyString());
        grounded = true;
        for (int i = 0; i < 5; i++) advance(100);
        verify(bar, atLeastOnce()).setTitle(titles.capture());
        assertEquals("In pausa  •  00:10", ChatColor.stripColor(titles.getValue()));
        verify(bar).setColor(BarColor.WHITE);
        long remaining = flight.remaining(id);
        for (int i = 0; i < 10; i++) advance(100);
        assertEquals(remaining, flight.remaining(id));

        grounded = false;
        for (int i = 0; i < 5; i++) advance(100);
        verify(bar, atLeastOnce()).setTitle(titles.capture());
        assertTrue(ChatColor.stripColor(titles.getValue()).startsWith("Volo"));
        flight.stopManually(player);
        flush();
        verify(bar).removeAll();
    }
    private EntityDamageEvent impact(FallProtection protection) {
        var event = mock(EntityDamageEvent.class);
        when(event.getEntity()).thenReturn(player);
        when(event.getCause()).thenReturn(EntityDamageEvent.DamageCause.FALL);
        protection.fall(event);
        return event;
    }
    private FallProtection afterRestart() {
        var restored = new FallProtection(time::get, p -> grounded, uuid -> player, 15, slowFall);
        restored.resume(player);
        return restored;
    }
    @Test void exhaustedCreditProtectsOnlyTheFirstImpact() throws Exception {
        flight.start(player, 1000); flush(); advance(1000); flush();
        assertFalse(allowFlight);
        verify(impact(flight.landings())).setCancelled(true);
        verify(impact(flight.landings()), never()).setCancelled(true);
    }
    @Test void normalShutdownAndRestartShareOneLandingWithSlowFalling() throws Exception {
        flight.start(player, 60_000); flush(); advance(1000);
        flight.stopAll(true); flush();
        assertEquals(300, slowTicks); assertEquals(59_000, account().available());
        var restored = afterRestart();
        verify(impact(restored)).setCancelled(true);
        assertEquals(0, slowTicks); assertTrue(playerData.isEmpty());
        verify(impact(restored), never()).setCancelled(true);
        verify(impact(afterRestart()), never()).setCancelled(true);
    }
    @Test void harmlessLandingAfterLogoutConsumesProtectionBeforeSecondDrop() throws Exception {
        flight.start(player, 60_000); flush();
        var quit = new PlayerQuitEvent(player, net.kyori.adventure.text.Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED);
        flight.quit(quit); flush(); assertEquals(300, slowTicks);
        var restored = afterRestart();
        grounded = true; restored.tick(time.get());
        grounded = false; assertEquals(0, slowTicks);
        verify(impact(restored), never()).setCancelled(true);
    }
    @Test void repeatedReconnectsKeepOnlyTheRemainingOnlineTime() throws Exception {
        flight.start(player, 60_000); flush(); flight.stop(player, null); flush();
        advance(9000); flight.landings().suspend(player);
        assertEquals(120, slowTicks);
        var next = afterRestart();
        time.addAndGet(5_000_000_000L); next.suspend(player); next.suspend(player);
        var last = afterRestart();
        time.addAndGet(1_000_000_000L); last.tick(time.get());
        assertEquals(0, slowTicks);
        verify(impact(last), never()).setCancelled(true);
    }
    @Test void unrelatedSlowFallingIsPreservedAfterLanding() throws Exception {
        slowTicks = 2400; // Existing effect from a potion or another plugin.
        flight.start(player, 60_000); flush(); flight.stopAll(true); flush();
        var restored = afterRestart();
        grounded = true; restored.tick(time.get());
        assertEquals(2400, slowTicks); assertTrue(playerData.isEmpty());
        verify(impact(restored), never()).setCancelled(true);
    }
    @Test void aJumpFollowingASafeLandingCannotCarryProtectionToTheNextDrop() throws Exception {
        flight.start(player, 60_000); flush(); flight.stopAll(true); flush();
        var restored = afterRestart();
        var jump = new com.destroystokyo.paper.event.player.PlayerJumpEvent(player, player.getLocation(), player.getLocation());
        restored.jump(jump);
        assertEquals(0, slowTicks); verify(impact(restored), never()).setCancelled(true);
    }
}
