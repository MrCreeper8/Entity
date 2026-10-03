package dev.entity.client.bridge;

import com.google.gson.JsonObject;

/** The directly tested protocol fallback/readiness state machine used by {@link BridgeClient}. */
final class BridgeClientHandshakeState {
    enum FailureAction {
        RETRY_PROTOCOL_1_NOW,
        BACK_OFF
    }

    private final int preferredProtocol;
    private int attemptedProtocol;
    private boolean ready;

    BridgeClientHandshakeState(int preferredProtocol) {
        if (preferredProtocol < 1) throw new IllegalArgumentException("preferredProtocol must be positive");
        this.preferredProtocol = preferredProtocol;
        this.attemptedProtocol = preferredProtocol;
    }

    synchronized int attemptedProtocol() {
        return attemptedProtocol;
    }

    synchronized boolean ready() {
        return ready;
    }

    static boolean legacyFallbackAllowed(JsonObject handshakeResponse) {
        return !BridgeBuildIdentity.isMismatchError(handshakeResponse);
    }

    synchronized void authenticated() {
        ready = true;
        attemptedProtocol = preferredProtocol;
    }

    synchronized FailureAction handshakeFailed(boolean legacyFallbackAllowed) {
        ready = false;
        if (legacyFallbackAllowed && attemptedProtocol != 1) {
            attemptedProtocol = 1;
            return FailureAction.RETRY_PROTOCOL_1_NOW;
        }
        attemptedProtocol = preferredProtocol;
        return FailureAction.BACK_OFF;
    }

    synchronized void disconnected() {
        ready = false;
    }

    synchronized void resetPreferredProtocol() {
        attemptedProtocol = preferredProtocol;
    }
}
