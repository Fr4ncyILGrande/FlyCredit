package it.flycredit;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GroundProbeTest {
    Player player = mock(Player.class);
    World world = mock(World.class);
    WorldBorder border = mock(WorldBorder.class);
    GroundProbeTest() {
        when(player.getWorld()).thenReturn(world);
        when(player.getBoundingBox()).thenReturn(new BoundingBox(0.2, 64, 0.2, 0.8, 65.8, 0.8));
        when(world.getWorldBorder()).thenReturn(border);
        when(border.isInside(any())).thenReturn(true);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
    }
    @Test void spoofedClientGroundFlagDoesNotPauseAirborneFlight() {
        when(player.isOnGround()).thenReturn(true);
        when(world.hasCollisionsIn(any())).thenReturn(false);
        assertFalse(GroundProbe.isSupported(player));
        verify(player, never()).isOnGround();
    }
    @Test void supportIsProbedBelowFeetRatherThanAtHeadOrCentre() {
        when(world.hasCollisionsIn(any())).thenAnswer(i -> {
            BoundingBox feet = i.getArgument(0);
            assertEquals(64, feet.getMaxY()); assertEquals(63.98, feet.getMinY(), 0.00001);
            assertTrue(feet.getMinX() > 0.2); assertTrue(feet.getMaxX() < 0.8);
            return true;
        });
        assertTrue(GroundProbe.isSupported(player));
    }
    @Test void unloadedNegativeCoordinateChunkIsNeverQueriedForCollisions() {
        when(player.getBoundingBox()).thenReturn(new BoundingBox(-0.2, 64, -0.2, 0.4, 65.8, 0.4));
        when(world.isChunkLoaded(-1, -1)).thenReturn(false);
        assertFalse(GroundProbe.isSupported(player));
        verify(world, never()).hasCollisionsIn(any());
    }
    @Test void verticalWorldBorderIsNotTreatedAsAFloor() {
        when(border.isInside(any())).thenReturn(false);
        when(world.hasCollisionsIn(any())).thenReturn(true);
        assertFalse(GroundProbe.isSupported(player));
        verify(world, never()).hasCollisionsIn(any());
    }
}
