package it.flycredit.integration;

import com.griefdefender.api.GriefDefender;
import com.griefdefender.api.claim.Claim;
import com.griefdefender.api.claim.TrustType;
import com.griefdefender.api.claim.TrustTypes;
import org.bukkit.Location;
import java.util.Objects;
import java.util.UUID;

public final class ClaimAccess {
    private final TrustType builder;
    private final TrustType manager;

    public ClaimAccess() {
        this(TrustTypes.BUILDER, TrustTypes.MANAGER);
    }

    ClaimAccess(TrustType builder, TrustType manager) {
        this.builder = Objects.requireNonNull(builder, "GriefDefender BUILDER trust not initialized");
        this.manager = Objects.requireNonNull(manager, "GriefDefender MANAGER trust not initialized");
    }

    public boolean canFly(UUID player, Location location) {
        if (location == null || location.getWorld() == null || !GriefDefender.getCore().isEnabled(location.getWorld().getUID())) return false;
        Claim claim = GriefDefender.getCore().getClaimAt(location);
        return allowed(player, claim);
    }

    boolean allowed(UUID player, Claim claim) {
        // Read explicit personal trust, not isUserTrusted(), which may also grant GD ignore-claims bypass.
        if (claim == null || claim.isWilderness() || claim.isAdminClaim()) return false;
        for (int depth = 0; claim != null && depth < 64; depth++) {
            if (claim.isWilderness() || claim.isAdminClaim()) return false;
            if (player.equals(claim.getOwnerUniqueId()) || claim.getUserTrusts(builder).contains(player)
                    || claim.getUserTrusts(manager).contains(player)) return true;
            if (claim.getData() == null || !claim.getData().doesInheritParent()) return false;
            claim = claim.getParent();
        }
        return false;
    }
}
