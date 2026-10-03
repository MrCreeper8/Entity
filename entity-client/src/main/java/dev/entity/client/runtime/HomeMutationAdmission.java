package dev.entity.client.runtime;

import java.io.IOException;

/** Configuration rejection must precede changes to mission, Idle and body ownership. */
public final class HomeMutationAdmission {
    @FunctionalInterface
    public interface Step { void run() throws IOException; }

    @FunctionalInterface
    public interface Mutation { String run() throws IOException; }

    public static String execute(Step preflight, Step stopWork, Mutation mutation) throws IOException {
        preflight.run();
        stopWork.run();
        return mutation.run();
    }

    private HomeMutationAdmission() {}
}
