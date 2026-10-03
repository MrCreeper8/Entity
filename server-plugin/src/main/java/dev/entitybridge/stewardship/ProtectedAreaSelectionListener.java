package dev.entitybridge.stewardship;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Objects;

/** Cancels non-durable previews when their selecting owner leaves the context. */
public final class ProtectedAreaSelectionListener implements Listener {
    private final ProtectedAreaSelection selection;

    public ProtectedAreaSelectionListener(ProtectedAreaSelection selection) {
        this.selection = Objects.requireNonNull(selection, "selection");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        selection.ownerUnavailable(event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDimensionChange(PlayerChangedWorldEvent event) {
        selection.ownerChangedDimension(
                event.getPlayer().getName(),
                event.getPlayer().getWorld().getKey().toString());
    }
}
