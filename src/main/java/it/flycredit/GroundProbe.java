package it.flycredit;

import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

/** Checks server collision shapes, never the spoofable Player.isOnGround packet flag. */
final class GroundProbe {
    private GroundProbe() {}
    static boolean isSupported(Player player) {
        BoundingBox body = player.getBoundingBox();
        double inset = Math.min(0.001, Math.min(body.getWidthX(), body.getWidthZ()) / 4);
        BoundingBox feet = new BoundingBox(body.getMinX() + inset, body.getMinY() - 0.02, body.getMinZ() + inset,
                body.getMaxX() - inset, body.getMinY(), body.getMaxZ() - inset);
        World world = player.getWorld();
        // A vertical world border is not a floor. hasCollisionsIn also reports world-border collisions.
        WorldBorder border = world.getWorldBorder();
        if (!border.isInside(new Location(world, feet.getMinX(), feet.getMinY(), feet.getMinZ()))
                || !border.isInside(new Location(world, feet.getMaxX(), feet.getMinY(), feet.getMaxZ()))) return false;
        // Collision probes must never cause a synchronous chunk load at a boundary.
        for (int x = ((int) Math.floor(feet.getMinX())) >> 4; x <= ((int) Math.floor(feet.getMaxX())) >> 4; x++)
            for (int z = ((int) Math.floor(feet.getMinZ())) >> 4; z <= ((int) Math.floor(feet.getMaxZ())) >> 4; z++)
                if (!world.isChunkLoaded(x, z)) return false;
        return world.hasCollisionsIn(feet);
    }
}
