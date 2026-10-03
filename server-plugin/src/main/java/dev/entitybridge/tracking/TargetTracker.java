package dev.entitybridge.tracking;

import com.google.gson.JsonObject;
import dev.entitybridge.bridge.BridgeCommand;
import dev.entitybridge.bridge.BridgeServer;
import dev.entitybridge.mission.Mission;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class TargetTracker {
    private final JavaPlugin plugin;
    private final BridgeServer bridge;
    private volatile TrackedTarget target;
    private BukkitTask task;

    public TargetTracker(JavaPlugin plugin, BridgeServer bridge) {
        this.plugin = plugin;
        this.bridge = bridge;
    }

    public void start(long periodTicks) {
        if (task != null) {
            task.cancel();
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::publish, 1L, periodTicks);
    }

    public void track(BridgeCommand command, String playerName) {
        setTarget(new TrackedTarget(
                command.id(), command.action(), command.missionId(), command.sequence(), playerName
        ));
    }

    /** Restores delivery waypoint streaming from Paper's durable mission journal. */
    public boolean trackRestoredDelivery(Mission mission) {
        TrackedTarget restored = TrackedTarget.fromDeliveryMission(mission);
        if (restored == null) {
            return false;
        }
        setTarget(restored);
        return true;
    }

    public void clearMission(String missionId) {
        TrackedTarget current = target;
        if (current != null && current.missionId() != null
                && current.missionId().equals(missionId)) {
            target = null;
        }
    }

    public void clear() {
        target = null;
    }

    public void stop() {
        clear();
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void publish() {
        TrackedTarget current = target;
        if (current == null || !bridge.isConnected()) {
            return;
        }
        Player player = Bukkit.getPlayerExact(current.playerName());
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "target_update");
        frame.addProperty("commandId", current.commandId());
        frame.addProperty("action", current.action());
        if (current.missionId() != null) {
            frame.addProperty("missionId", current.missionId());
            frame.addProperty("sequence", current.sequence());
        }
        frame.addProperty("player", current.playerName());
        frame.addProperty("online", player != null && player.isOnline());
        if (player != null && player.isOnline()) {
            Location location = player.getLocation();
            JsonObject position = new JsonObject();
            position.addProperty("x", location.getX());
            position.addProperty("y", location.getY());
            position.addProperty("z", location.getZ());
            position.addProperty("yaw", location.getYaw());
            position.addProperty("pitch", location.getPitch());
            position.addProperty("dimension", player.getWorld().getKey().toString());
            frame.add("position", position);
        }
        frame.addProperty("timestamp", System.currentTimeMillis());
        bridge.sendTargetUpdate(frame);
    }

    private void setTarget(TrackedTarget next) {
        if (next.equals(target)) {
            return;
        }
        target = next;
        publish();
    }

    record TrackedTarget(
            String commandId,
            String action,
            String missionId,
            Long sequence,
            String playerName
    ) {
        static TrackedTarget fromDeliveryMission(Mission mission) {
            if (mission == null || mission.terminal()
                    || !(mission.action().equalsIgnoreCase("give")
                    || mission.action().equalsIgnoreCase("bring"))) {
                return null;
            }
            if (!mission.args().has("player")
                    || !mission.args().get("player").isJsonPrimitive()) {
                return null;
            }
            String player = mission.args().get("player").getAsString().trim();
            if (player.isBlank()) {
                return null;
            }
            return new TrackedTarget(
                    mission.commandId(), mission.action(), mission.id(), mission.sequence(), player
            );
        }
    }
}
