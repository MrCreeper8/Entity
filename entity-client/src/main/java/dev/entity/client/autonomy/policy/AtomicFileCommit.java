package dev.entity.client.autonomy.policy;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Windows-safe bounded commit for the Home stores' same-directory temporary files. */
public final class AtomicFileCommit {
    private static final int MAXIMUM_ATTEMPTS = 8;

    private AtomicFileCommit() {}

    public static void replace(Path temporaryPath, Path path) throws IOException {
        IOException lastFailure = null;
        for (int attempt = 0; attempt < MAXIMUM_ATTEMPTS; attempt++) {
            try {
                try {
                    Files.move(temporaryPath, path,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporaryPath, path, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (FileSystemException transientSharingFailure) {
                lastFailure = transientSharingFailure;
                if (attempt + 1 >= MAXIMUM_ATTEMPTS) break;
                try {
                    Thread.sleep(Math.min(80L, 5L << attempt));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while committing " + path, interrupted);
                }
            }
        }
        throw lastFailure == null ? new IOException("could not commit " + path) : lastFailure;
    }
}
