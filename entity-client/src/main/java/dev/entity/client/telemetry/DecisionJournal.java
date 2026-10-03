package dev.entity.client.telemetry;

import com.google.gson.Gson;
import dev.entity.core.trace.DecisionTrace;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Append-only diagnostic evidence backing /e why and command-line debugging. */
public final class DecisionJournal {
    private static final Gson GSON = new Gson();

    private final Path path;
    private final Logger logger;
    private long lastSequence;

    public DecisionJournal(Path path, Logger logger) {
        this.path = path.toAbsolutePath().normalize();
        this.logger = logger;
    }

    public synchronized void capture(DecisionTrace trace) {
        for (DecisionTrace.Entry entry : trace.recent(256)) {
            if (entry.sequence() <= lastSequence) continue;
            try {
                Files.createDirectories(path.getParent());
                Files.writeString(
                        path,
                        GSON.toJson(entry) + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND,
                        StandardOpenOption.WRITE);
                logger.info("Entity decision: {} because {}", entry.decision(), entry.reason());
                lastSequence = entry.sequence();
            } catch (IOException exception) {
                logger.warn("Could not append Entity decision journal: {}", exception.getMessage());
                return;
            }
        }
    }
}
