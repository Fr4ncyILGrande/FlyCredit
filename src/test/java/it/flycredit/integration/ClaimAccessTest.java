package it.flycredit.integration;

import com.griefdefender.api.claim.Claim;
import com.griefdefender.api.claim.TrustType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClaimAccessTest {
    private final UUID player = UUID.randomUUID();
    private TrustType builder;
    private TrustType manager;
    private ClaimAccess access;
    private Claim claim;

    @BeforeEach void setup() {
        // GD populates its catalog constants at server boot. Inject distinct types for unit tests.
        builder = mock(TrustType.class);
        manager = mock(TrustType.class);
        access = new ClaimAccess(builder, manager);
        claim = mock(Claim.class);
        when(claim.getOwnerUniqueId()).thenReturn(UUID.randomUUID());
    }

    @Test void ownerIsAllowedWithoutTrust() {
        when(claim.getOwnerUniqueId()).thenReturn(player);
        assertTrue(access.allowed(player, claim));
        verify(claim, never()).isUserTrusted(any(), any());
    }
    @Test void builderTrustAllowsNonOwner() {
        when(claim.getUserTrusts(builder)).thenReturn(Set.of(player));
        assertTrue(access.allowed(player, claim));
    }
    @Test void managerTrustAloneAllowsNonOwner() {
        when(claim.getUserTrusts(manager)).thenReturn(Set.of(player));
        assertTrue(access.allowed(player, claim));
    }
    @Test void bothTrustsAllowNonOwner() {
        when(claim.getUserTrusts(builder)).thenReturn(Set.of(player));
        when(claim.getUserTrusts(manager)).thenReturn(Set.of(player));
        assertTrue(access.allowed(player, claim));
    }
    @Test void noBuilderOrManagerTrustIsDenied() {
        // Includes users with only accessor/container/resident trust: GD reports neither requested type.
        assertFalse(access.allowed(player, claim));
        verify(claim).getUserTrusts(builder);
        verify(claim).getUserTrusts(manager);
    }
    @Test void revokingTrustDeniesNextCheckWithoutCache() {
        when(claim.getUserTrusts(builder)).thenReturn(Set.of(player), Set.of());
        assertTrue(access.allowed(player, claim));
        assertFalse(access.allowed(player, claim));
    }
    @Test void trustDoesNotCarryOverToAnotherClaim() {
        when(claim.getUserTrusts(builder)).thenReturn(Set.of(player));
        Claim destination = mock(Claim.class);
        assertTrue(access.allowed(player, claim));
        assertFalse(access.allowed(player, destination));
        when(destination.getUserTrusts(manager)).thenReturn(Set.of(player));
        assertTrue(access.allowed(player, destination));
    }
    @Test void missingClaimWildernessAndAdminRemainDenied() {
        when(claim.getOwnerUniqueId()).thenReturn(player);
        when(claim.getUserTrusts(builder)).thenReturn(Set.of(player));
        when(claim.getUserTrusts(manager)).thenReturn(Set.of(player));
        assertFalse(access.allowed(player, null));
        when(claim.isWilderness()).thenReturn(true);
        assertFalse(access.allowed(player, claim));
        when(claim.isWilderness()).thenReturn(false);
        when(claim.isAdminClaim()).thenReturn(true);
        assertFalse(access.allowed(player, claim));
    }
    @Test void ignoreClaimsBypassDoesNotGrantPersonalTrust() {
        when(claim.isUserTrusted(player, builder)).thenReturn(true);
        when(claim.isUserTrusted(player, manager)).thenReturn(true);
        assertFalse(access.allowed(player, claim));
    }
    @Test void parentTrustRequiresInheritanceAndRevocationIsImmediate() {
        Claim parent = mock(Claim.class);
        var data = mock(com.griefdefender.api.data.ClaimData.class);
        when(claim.getData()).thenReturn(data);
        when(claim.getParent()).thenReturn(parent);
        when(parent.getUserTrusts(builder)).thenReturn(Set.of(player));
        assertFalse(access.allowed(player, claim));
        when(data.doesInheritParent()).thenReturn(true);
        assertTrue(access.allowed(player, claim));
        when(parent.getUserTrusts(builder)).thenReturn(Set.of());
        assertFalse(access.allowed(player, claim));
    }
    @Test void corruptParentCycleTerminatesWithoutGrantingFlight() {
        var data = mock(com.griefdefender.api.data.ClaimData.class);
        when(data.doesInheritParent()).thenReturn(true);
        when(claim.getData()).thenReturn(data);
        when(claim.getParent()).thenReturn(claim);
        assertFalse(access.allowed(player, claim));
    }
}
