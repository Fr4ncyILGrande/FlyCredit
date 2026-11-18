package it.flycredit.integration;

import com.griefdefender.api.GriefDefender;
import com.griefdefender.api.claim.Claim;
import com.griefdefender.api.claim.TrustType;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;

public final class ClaimAccess {
    public static final String TRUST_SETTING = "flight.allowed-trust-types";
    private static final String GD_NAMESPACE = "griefdefender:";
    private final List<TrustType> guestTypes;

    ClaimAccess(Collection<TrustType> guestTypes) {
        this.guestTypes = List.copyOf(guestTypes);
    }

    public static ClaimAccess fromConfig(ConfigurationSection config, Logger logger) {
        Object setting = config.get(TRUST_SETTING);
        if (setting == null) return new ClaimAccess(List.of());
        if (!(setting instanceof List<?> entries)) {
            logger.warning(TRUST_SETTING + " must be a YAML list. Only claim owners can fly.");
            return new ClaimAccess(List.of());
        }
        if (entries.isEmpty()) return new ClaimAccess(List.of());

        Map<String, TrustType> catalog = new HashMap<>();
        for (TrustType type : GriefDefender.getRegistry().getAllOf(TrustType.class)) {
            String id = normalize(type.getId());
            // NONE represents revoked trust, never a grant to a guest.
            if (!id.equals("none")) catalog.put(id, type);
        }

        Set<TrustType> selected = new LinkedHashSet<>();
        for (Object entry : entries) {
            if (entry instanceof String name) {
                if (name.isBlank()) continue;
                TrustType type = catalog.get(normalize(name));
                if (type != null) {
                    selected.add(type);
                    continue;
                }
            }
            logger.warning("Ignoring invalid GriefDefender trust type '" + entry + "' in " + TRUST_SETTING + ".");
        }
        return new ClaimAccess(selected);
    }

    private static String normalize(String id) {
        String value = id.strip().toLowerCase(Locale.ROOT);
        return value.startsWith(GD_NAMESPACE) ? value.substring(GD_NAMESPACE.length()) : value;
    }

    public List<String> guestTypeIds() {
        return guestTypes.stream().map(type -> normalize(type.getId())).sorted().toList();
    }

    public boolean canFly(UUID player, Location location) {
        if (location == null || location.getWorld() == null) return false;
        var core = GriefDefender.getCore();
        if (!core.isEnabled(location.getWorld().getUID())) return false;
        return allowed(player, core.getClaimAt(location));
    }

    private static boolean playerClaim(Claim claim) {
        return claim != null && !claim.isWilderness() && !claim.isAdminClaim();
    }

    boolean allowed(UUID player, Claim claim) {
        if (!playerClaim(claim)) return false;
        // GD supplies the effective owner of the claim at this location, including subclaims.
        if (player.equals(claim.getOwnerUniqueId())) return true;
        if (guestTypes.isEmpty()) return false;

        for (int depth = 0; depth < 64; depth++) {
            // An explicit UUID grant is scoped to this claim. Global permission bypasses are irrelevant.
            for (TrustType type : guestTypes) {
                if (claim.getUserTrusts(type).contains(player)) return true;
            }
            var data = claim.getData();
            if (data == null || !data.doesInheritParent()) return false;
            claim = claim.getParent();
            if (!playerClaim(claim)) return false;
        }
        return false;
    }
}
