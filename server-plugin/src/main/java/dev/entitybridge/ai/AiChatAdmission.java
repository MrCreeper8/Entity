package dev.entitybridge.ai;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Fences the async-chat-to-main-thread hop; the existing AI fence owns inference. */
public final class AiChatAdmission {
    private final AtomicLong revision = new AtomicLong();
    public long revision() { return revision.get(); }
    public void invalidate() { revision.incrementAndGet(); }
    public boolean accepts(String request, long captured) {
        // Stop never resurrects work and must not be dropped when an older
        // model answer/direct command changes the revision before this tick.
        return captured == revision.get() || isStop(request);
    }
    public static boolean isStop(String request) {
        return Set.of("stop", "stop now", "stop please", "please stop", "cancel everything")
                .contains(request.strip().toLowerCase(Locale.ROOT).replaceAll("[.!]+$", ""));
    }
}
