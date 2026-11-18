package it.flycredit.integration;

import com.griefdefender.api.GriefDefender;
import com.griefdefender.api.Registry;
import com.griefdefender.api.claim.Claim;
import com.griefdefender.api.claim.TrustType;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClaimAccessTest {
    private final UUID player = UUID.randomUUID();
    private TrustType builder;
    private TrustType manager;
    private ClaimAccess access;
    private Claim claim;

    @BeforeEach void setup() {
        builder = mock(TrustType.class);
        manager = mock(TrustType.class);
        access = new ClaimAccess(List.of(builder, manager));
        claim = mock(Claim.class);
        when(claim.getOwnerUniqueId()).thenReturn(UUID.randomUUID());
    }

    @Test void ownerIsAllowedWithoutTrust() {
        when(claim.getOwnerUniqueId()).thenReturn(player);
        assertTrue(access.allowed(player, claim));
        verify(claim, never()).isUserTrusted(any(), any());
    }
    @Test void configuredBuilderTrustAllowsNonOwner() {
        when(claim.getUserTrusts(builder)).thenReturn(Set.of(player));
        assertTrue(access.allowed(player, claim));
    }
    @Test void configuredManagerTrustAllowsNonOwner() {
        when(claim.getUserTrusts(manager)).thenReturn(Set.of(player));
        assertTrue(access.allowed(player, claim));
    }
    @Test void bothTrustsAllowNonOwner() {
        when(claim.getUserTrusts(builder)).thenReturn(Set.of(player));
        when(claim.getUserTrusts(manager)).thenReturn(Set.of(player));
        assertTrue(access.allowed(player, claim));
    }
    @Test void absenceOfAllConfiguredPersonalGrantsIsDenied() {
        assertFalse(access.allowed(player, claim));
        verify(claim).getUserTrusts(builder);
        verify(claim).getUserTrusts(manager);
    }
    @Test void emptyMissingAndMalformedSettingsAllowOnlyOwners() {
        var config = new YamlConfiguration();
        var logger = mock(Logger.class);
        when(claim.getUserTrusts(builder)).thenReturn(Set.of(player));
        when(claim.getUserTrusts(manager)).thenReturn(Set.of(player));
        try (var gd = mockStatic(GriefDefender.class, withSettings().mockMaker(MockMakers.INLINE))) {
            assertFalse(ClaimAccess.fromConfig(config, logger).allowed(player, claim));
            config.set(ClaimAccess.TRUST_SETTING, List.of());
            ClaimAccess empty = ClaimAccess.fromConfig(config, logger);
            assertFalse(empty.allowed(player, claim));
            config.set(ClaimAccess.TRUST_SETTING, "manager");
            assertFalse(ClaimAccess.fromConfig(config, logger).allowed(player, claim));
            verify(logger).warning(contains("must be a YAML list"));
            when(claim.getOwnerUniqueId()).thenReturn(player);
            assertTrue(empty.allowed(player, claim));
            verify(claim, never()).getUserTrusts(any(TrustType.class));
            gd.verifyNoInteractions();
        }
    }
    @Test void namedTypesAreResolvedOnceAndOnlyAnExactPersonalGrantQualifies() {
        var registry = mock(Registry.class);
        var resident = mock(TrustType.class);
        var none = mock(TrustType.class);
        when(resident.getId()).thenReturn("griefdefender:resident");
        when(none.getId()).thenReturn("griefdefender:none");
        when(builder.getId()).thenReturn("builder");
        when(manager.getId()).thenReturn("manager");
        when(registry.getAllOf(TrustType.class)).thenReturn(List.of(none, resident, builder, manager));
        var config = new YamlConfiguration();
        var logger = mock(Logger.class);
        config.set(ClaimAccess.TRUST_SETTING, List.of(" ReSiDeNt ", "griefdefender:RESIDENT", " ", "typo", "NONE", "/trust"));
        try (var gd = mockStatic(GriefDefender.class, withSettings().mockMaker(MockMakers.INLINE))) {
            gd.when(GriefDefender::getRegistry).thenReturn(registry);
            ClaimAccess configured = ClaimAccess.fromConfig(config, logger);
            assertEquals(List.of("resident"), configured.guestTypeIds());
            verify(logger).warning(contains("'typo'"));
            verify(logger).warning(contains("'NONE'"));
            verify(logger).warning(contains("'/trust'"));
            when(claim.getUserTrusts(builder)).thenReturn(Set.of(player));
            when(claim.getUserTrusts(manager)).thenReturn(Set.of(player));
            when(claim.getUserTrusts(none)).thenReturn(Set.of(player));
            assertFalse(configured.allowed(player, claim));
            when(claim.getUserTrusts(resident)).thenReturn(Set.of(player));
            assertTrue(configured.allowed(player, claim));
            verify(claim, times(2)).getUserTrusts(resident);
            verify(claim, never()).getUserTrusts(builder);
            verify(claim, never()).getUserTrusts(manager);
            verify(claim, never()).getUserTrusts(none);
            verify(registry).getAllOf(TrustType.class);

            config.set(ClaimAccess.TRUST_SETTING, List.of("none", "typo"));
            assertFalse(ClaimAccess.fromConfig(config, logger).allowed(player, claim));
        }
    }
    @Test void anotherOwnersSubclaimRequiresAnAllowedPersonalGrant() {
        var parent = mock(Claim.class);
        var data = mock(com.griefdefender.api.data.ClaimData.class);
        when(claim.getData()).thenReturn(data);
        when(data.doesInheritParent()).thenReturn(true);
        when(claim.getParent()).thenReturn(parent);
        when(parent.getOwnerUniqueId()).thenReturn(player);
        assertFalse(new ClaimAccess(List.of()).allowed(player, claim));
        assertFalse(access.allowed(player, claim));
        when(parent.getUserTrusts(manager)).thenReturn(Set.of(player));
        assertTrue(access.allowed(player, claim));
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
