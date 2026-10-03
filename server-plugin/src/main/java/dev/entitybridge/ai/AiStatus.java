package dev.entitybridge.ai;

import java.net.http.HttpTimeoutException;
import java.util.Locale;

/** Read-only observations. Details are an allowlist, never runtime output or exception text. */
public final class AiStatus {
    private AiStatus() { }
    public enum State { UNCONFIGURED, CONFIGURED, LOADING, READY, FAILED, STOPPED }
    public enum Provider { NONE, MANAGED, EXTERNAL }
    public enum Detail {
        OFF("AI is off; ordinary commands remain available."),
        UNCONFIGURED("Managed AI is not configured; choose AI setup in the app."),
        CONFIGURED_MANAGED("Managed AI is configured but has not loaded; it starts on an actual AI request."),
        CONFIGURED_EXTERNAL("External AI is configured but has not answered a request; the endpoint has not been probed."),
        VERIFYING("Verifying the configured managed AI files for an actual request."),
        STARTING("The managed runtime is loading its model for an actual request."),
        REQUESTING("An actual external AI request is in progress."),
        READY_MANAGED("The owned managed runtime reported its model ready."),
        READY_EXTERNAL("The external endpoint last returned a successful structured response; this is not a live probe."),
        CONFIGURATION_FAILED("AI configuration could not be loaded; review AI setup. Ordinary commands still work."),
        FILES_FAILED("Managed AI file verification failed; check the configured pack and its checksums."),
        STARTUP_FAILED("The managed model could not start; check the runtime, GPU compatibility and available memory."),
        RUNTIME_EXITED("The owned managed runtime exited; it is not ready."),
        TIMEOUT("The last AI request exceeded its deadline; check provider availability."),
        AUTHENTICATION_FAILED("The provider rejected authentication; check the API key in the server environment."),
        HTTP_FAILED("The provider rejected the last request; check endpoint, model and structured-response support."),
        RESPONSE_FAILED("The provider returned an incomplete or invalid structured response."),
        TRANSPORT_FAILED("The last AI request could not reach or receive a response from its provider."),
        REQUEST_FAILED("The last AI inference request failed; check the configured runtime or endpoint."),
        CANCELLED("The last AI request was cancelled; readiness has not been rechecked."),
        STOPPED("AI stopped; ordinary commands remain available."),
        PLUGIN_STOPPED("The server plugin stopped; this receipt is no longer a live heartbeat.");
        private final String text;
        Detail(String text) { this.text = text; }
        public String text() { return text; }
    }
    public record Snapshot(Provider provider, State state, Detail detail) {
        public Snapshot {
            java.util.Objects.requireNonNull(provider);
            java.util.Objects.requireNonNull(state);
            java.util.Objects.requireNonNull(detail);
        }
        public String providerName() { return provider.name().toLowerCase(Locale.ROOT); }
        public String stateName() { return state.name().toLowerCase(Locale.ROOT); }
    }
    public record Observation(boolean enabled, Snapshot backend) { }
    public static Snapshot off() { return new Snapshot(Provider.NONE, State.STOPPED, Detail.OFF); }

    /** Inspect only known types/prefixes to classify; never copy any part of a message. */
    static Detail requestFailure(Exception failure) {
        if (failure instanceof InterruptedException) return Detail.CANCELLED;
        if (failure instanceof HttpTimeoutException || failure instanceof java.util.concurrent.TimeoutException)
            return Detail.TIMEOUT;
        String message = java.util.Objects.toString(failure.getMessage(), "");
        if (message.equals("External AI exceeded its request deadline.")) return Detail.TIMEOUT;
        if (message.matches("(?:External|Local) AI returned HTTP (?:401|403)\\.")) return Detail.AUTHENTICATION_FAILED;
        if (message.matches("(?:External|Local) AI returned HTTP [0-9]{3}\\.")) return Detail.HTTP_FAILED;
        if (message.equals("External AI returned an incomplete or invalid structured response.")
                || message.equals("Local AI answer was incomplete; no action was accepted.")
                || message.equals("Local AI answer exceeded its output limit.")
                || message.equals("Local AI answer was not an object.")
                || message.equals("Local AI returned an invalid structured answer."))
            return Detail.RESPONSE_FAILED;
        if (message.equals("External AI transport failed; nothing was accepted.")) return Detail.TRANSPORT_FAILED;
        return Detail.REQUEST_FAILED;
    }
}
