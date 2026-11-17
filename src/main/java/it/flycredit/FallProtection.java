package it.flycredit;

import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** One landing per flight. The remaining online time is saved with the player on logout. */
final class FallProtection implements Listener {
    interface SlowFall {
        boolean apply(Player player, int ticks);
        void remove(Player player);
    }

    static final SlowFall BUKKIT_EFFECT = new SlowFall() {
        public boolean apply(Player player, int ticks) {
            if (player.hasPotionEffect(PotionEffectType.SLOW_FALLING)) return false;
            return player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, ticks, 0, false, false, false));
        }
        public void remove(Player player) {
            player.removePotionEffect(PotionEffectType.SLOW_FALLING);
        }
    };

    private static final NamespacedKey REMAINING = NamespacedKey.fromString("flycredit:fall_remaining_ns");
    private static final NamespacedKey OWN_EFFECT = NamespacedKey.fromString("flycredit:fall_owns_effect");
    private static final long TICK_NANOS = 50_000_000L;
    private final Map<UUID, Long> deadlines = new HashMap<>();
    private final LongSupplier clock;
    private final Predicate<Player> grounded;
    private final Function<UUID, Player> online;
    private final SlowFall effect;
    private final long duration;

    FallProtection(LongSupplier clock, Predicate<Player> grounded, Function<UUID, Player> online,
                   int seconds, SlowFall effect) {
        this.clock = clock;
        this.grounded = grounded;
        this.online = online;
        this.effect = effect;
        duration = Math.clamp(seconds, 1, 60) * 1_000_000_000L;
    }

    void grant(Player player) {
        if (!player.isDead() && !grounded.test(player)) {
            deadlines.put(player.getUniqueId(), clock.getAsLong() + duration);
        }
    }

    private boolean landed(Player p) {
        return p.isDead() || p.isInsideVehicle() || p.isInWater() || p.isInLava() || p.isClimbing()
                || p.isGliding() || p.isFlying() || p.getGameMode() == GameMode.CREATIVE
                || p.getGameMode() == GameMode.SPECTATOR || grounded.test(p);
    }

    void tick(long now) {
        var entries = deadlines.entrySet().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            Player player = online.apply(entry.getKey());
            if (player == null || entry.getValue() - now <= 0 || landed(player)) {
                entries.remove();
                if (player != null) forget(player);
            }
        }
    }

    void suspend(Player player) {
        Long deadline = deadlines.remove(player.getUniqueId());
        if (deadline == null) return; // A second quit/disable callback must not renew a saved landing.
        long left = deadline - clock.getAsLong();
        if (left <= 0 || landed(player)) {
            forget(player);
            return;
        }
        var data = player.getPersistentDataContainer();
        data.set(REMAINING, PersistentDataType.LONG, left);
        if (!data.has(OWN_EFFECT) && effect.apply(player, (int) Math.ceilDiv(left, TICK_NANOS))) {
            data.set(OWN_EFFECT, PersistentDataType.BYTE, (byte) 1);
        }
    }

    void suspendAll() {
        for (UUID id : java.util.List.copyOf(deadlines.keySet())) {
            Player player = online.apply(id);
            if (player != null) suspend(player);
            else deadlines.remove(id);
        }
    }

    void resume(Player player) {
        if (deadlines.containsKey(player.getUniqueId())) return;
        var data = player.getPersistentDataContainer();
        Long saved = data.get(REMAINING, PersistentDataType.LONG);
        if (saved == null) return;
        long left = Math.min(saved, duration);
        if (left <= 0 || landed(player)) {
            clear(player);
            return;
        }
        // Mark the entire allowance as spent on disk until an orderly logout saves the true remainder.
        // A process crash then cannot replay the same landing or restart the countdown.
        data.set(REMAINING, PersistentDataType.LONG, 0L);
        deadlines.put(player.getUniqueId(), clock.getAsLong() + left);
    }

    void clear(Player player) {
        deadlines.remove(player.getUniqueId());
        forget(player);
    }

    private void forget(Player player) {
        var data = player.getPersistentDataContainer();
        data.remove(REMAINING);
        if (data.has(OWN_EFFECT)) {
            data.remove(OWN_EFFECT);
            effect.remove(player);
        }
    }

    @EventHandler public void join(PlayerJoinEvent event) { resume(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void jump(PlayerJumpEvent event) {
        Player player = event.getPlayer();
        if (deadlines.containsKey(player.getUniqueId())) clear(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void fall(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL || !(event.getEntity() instanceof Player player)) return;
        Long until = deadlines.remove(player.getUniqueId());
        if (until == null) return;
        forget(player);
        if (until - clock.getAsLong() > 0) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void effectChanged(EntityPotionEffectEvent event) {
        if (!(event.getEntity() instanceof Player player) || event.getModifiedType() != PotionEffectType.SLOW_FALLING) return;
        if (event.getAction() == EntityPotionEffectEvent.Action.CHANGED && !event.isOverride()) return;
        // An independent potion, command or plugin can replace our effect. Leave that replacement alone.
        player.getPersistentDataContainer().remove(OWN_EFFECT);
    }
}
