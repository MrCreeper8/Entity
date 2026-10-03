package dev.entitybridge.ai;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/** Only captures text on the chat thread; all player/game authority stays on tick. */
public final class AiChatListener implements Listener {
    public interface Port {
        long revision();
        void mainThread(Runnable callback);
        void accept(Player player, String request, long revision);
    }
    private final Port port;
    public AiChatListener(Port port) { this.port = port; }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (event.isCancelled()) return;
        var request = AiChatAddress.request(PlainTextComponentSerializer.plainText().serialize(event.message()));
        if (request.isEmpty()) return;
        Player player = event.getPlayer();
        long revision = port.revision();
        port.mainThread(() -> port.accept(player, request.get(), revision));
        // Keep normal chat untouched; existing replies are private to the requester.
    }
}
