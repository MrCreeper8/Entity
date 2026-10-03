package dev.entitybridge.ai;

import com.google.gson.*;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;

/** OpenAI-compatible structured chat. No subprocesses, retries, provider-specific fields or fallback. */
public final class ExternalAi implements AiBackendFactory.Provider {
    private static final int MAX_RESPONSE = 65_536;
    public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(45);
    private final AiBackendFactory.ExternalConfig config;
    private final String apiKey;
    private final Duration timeout;
    private final HttpClient http;
    private volatile boolean closed;
    private volatile String observation = "endpoint not tested";
    private volatile AiStatus.Snapshot status = new AiStatus.Snapshot(AiStatus.Provider.EXTERNAL,
            AiStatus.State.CONFIGURED, AiStatus.Detail.CONFIGURED_EXTERNAL);
    private CompletableFuture<HttpResponse<byte[]>> pending;

    ExternalAi(AiBackendFactory.ExternalConfig config, String apiKey) { this(config, apiKey, REQUEST_TIMEOUT); }
    ExternalAi(AiBackendFactory.ExternalConfig config, String apiKey, Duration timeout) {
        this.config = config; this.apiKey = apiKey; this.timeout = timeout;
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1)
                .proxy(new ProxySelector() {
                    public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                    public void connectFailed(URI uri, SocketAddress address, IOException failure) { }
                }).build();
    }

    @Override public boolean configured() { return !closed; }
    @Override public String label() { return "AI"; }
    @Override public AiStatus.Snapshot status() { return status; }
    private synchronized void observed(AiStatus.State state, AiStatus.Detail detail) {
        if (!closed) status = new AiStatus.Snapshot(AiStatus.Provider.EXTERNAL, state, detail);
    }
    @Override public String description() {
        return closed ? "External AI stopped." : (config.loopback() ? "External-local " : "External cloud (consented) ")
                + config.model() + "; " + observation + "; no automatic fallback";
    }

    @Override public String complete(String system, String request, JsonObject schema) throws IOException, InterruptedException {
        JsonArray messages = new JsonArray(); JsonObject user = new JsonObject();
        user.addProperty("role", "user"); user.addProperty("content", request); messages.add(user);
        return completeConversation(system, messages, schema, 768);
    }

    @Override public String completeConversation(String system, JsonArray messages, JsonObject schema, int maxTokens)
            throws IOException, InterruptedException {
        JsonObject body = request(config.model(), system, messages, schema, maxTokens);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(config.baseUrl() + "/chat/completions"))
                .header("Content-Type", "application/json").timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        if (!apiKey.isEmpty()) builder.header("Authorization", "Bearer " + apiKey);
        CompletableFuture<HttpResponse<byte[]>> call;
        synchronized (this) {
            if (closed) throw new IOException("External AI is off.");
            if (pending != null) throw new IOException("External AI is already answering a request.");
            call = http.sendAsync(builder.build(), limitedBody()); pending = call;
            observed(AiStatus.State.LOADING, AiStatus.Detail.REQUESTING);
        }
        try {
            // Covers the entire body, including a peer that sends headers then stalls indefinitely.
            HttpResponse<byte[]> response = call.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (closed) throw new IOException("External AI stopped before replying.");
            if (response.statusCode() != 200) throw new IOException("External AI returned HTTP " + response.statusCode() + ".");
            String content = content(response.body());
            observation = "last structured response succeeded";
            observed(AiStatus.State.READY, AiStatus.Detail.READY_EXTERNAL);
            return content;
        } catch (TimeoutException expired) {
            call.cancel(true); observation = "last request timed out";
            observed(AiStatus.State.FAILED, AiStatus.Detail.TIMEOUT);
            throw new IOException("External AI exceeded its request deadline.");
        } catch (ExecutionException | CancellationException transport) {
            observation = "last request failed";
            observed(AiStatus.State.FAILED, transport.getCause() instanceof HttpTimeoutException
                    ? AiStatus.Detail.TIMEOUT : AiStatus.Detail.TRANSPORT_FAILED);
            // Never propagate a URL, body, Authorization header or provider diagnostic.
            throw new IOException("External AI transport failed; nothing was accepted.");
        } catch (InterruptedException interrupted) {
            call.cancel(true); observation = "last request cancelled";
            observed(AiStatus.State.CONFIGURED, AiStatus.Detail.CANCELLED); throw interrupted;
        } catch (IOException rejected) {
            observation = "last request failed";
            observed(AiStatus.State.FAILED, AiStatus.requestFailure(rejected)); throw rejected;
        } finally {
            synchronized (this) { if (pending == call) pending = null; }
        }
    }

    static JsonObject request(String model, String system, JsonArray messages, JsonObject schema, int maxTokens) throws IOException {
        // Reuse established context validation/snapshotting, but emit an explicit standard-field allowlist.
        JsonObject validated = ManagedLocalAi.conversationRequest(system, messages, schema, maxTokens);
        JsonObject request = new JsonObject();
        request.addProperty("model", model); request.addProperty("stream", false);
        request.addProperty("max_tokens", maxTokens);
        request.add("messages", validated.get("messages"));
        request.add("response_format", validated.get("response_format"));
        return request;
    }

    static String content(byte[] bytes) throws IOException {
        try {
            JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices.size() != 1) throw new IllegalArgumentException();
            JsonObject choice = choices.get(0).getAsJsonObject(), message = choice.getAsJsonObject("message");
            if (!"stop".equals(choice.get("finish_reason").getAsString())
                    || message.has("refusal") && !message.get("refusal").isJsonNull()
                    || message.has("tool_calls") && !message.get("tool_calls").isJsonNull()
                    || !message.get("content").isJsonPrimitive() || !message.getAsJsonPrimitive("content").isString())
                throw new IllegalArgumentException();
            String content = message.get("content").getAsString();
            if (content.isBlank() || content.length() > 16_384 || !JsonParser.parseString(content).isJsonObject())
                throw new IllegalArgumentException();
            // EntityAi parses the typed proposal and checks current authority on the Paper main thread.
            return content;
        } catch (RuntimeException malformed) {
            throw new IOException("External AI returned an incomplete or invalid structured response.");
        }
    }

    private static HttpResponse.BodyHandler<byte[]> limitedBody() {
        return ignored -> new HttpResponse.BodySubscriber<>() {
            private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
            private Flow.Subscription subscription;
            private long bytes;
            public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
            public void onSubscribe(Flow.Subscription value) { subscription = value; delegate.onSubscribe(value); }
            public void onNext(List<ByteBuffer> buffers) {
                for (ByteBuffer buffer : buffers) bytes += buffer.remaining();
                if (bytes > MAX_RESPONSE) {
                    subscription.cancel(); delegate.onError(new IOException("External AI response exceeded its byte limit."));
                } else delegate.onNext(buffers);
            }
            public void onError(Throwable failure) { delegate.onError(failure); }
            public void onComplete() { delegate.onComplete(); }
        };
    }

    @Override public synchronized void close() {
        closed = true;
        status = new AiStatus.Snapshot(AiStatus.Provider.EXTERNAL, AiStatus.State.STOPPED, AiStatus.Detail.STOPPED);
        if (pending != null) pending.cancel(true);
        http.shutdownNow();
    }
}
