package dev.entitybridge.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/** Owns one private inference child. Never call complete on the Paper main thread. */
public final class ManagedLocalAi implements AutoCloseable {
    private static final int MAX_RESPONSE = 65_536;
    private static final Logger LOG = Logger.getLogger(ManagedLocalAi.class.getName());
    private final Config config;
    private final Path runtimeDirectory;
    private final Launcher launcher;
    private final HttpClient http;
    private final Semaphore inference = new Semaphore(1);
    private volatile boolean closed;
    private Process child;
    private URI endpoint;
    private String token;
    private Path tokenFile;
    private Thread shutdownHook;
    private final StringBuilder startupLog = new StringBuilder();
    private volatile boolean modelReady;
    private volatile Timing lastTiming;
    private volatile DeviceDiagnostics deviceDiagnostics = new DeviceDiagnostics(-1, -1, -1, -1);

    private ManagedLocalAi(Config config, Path runtimeDirectory, Launcher launcher) {
        this.config = config;
        this.runtimeDirectory = runtimeDirectory;
        this.launcher = launcher;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1)
                .proxy(new ProxySelector() {
                    @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                    @Override public void connectFailed(URI uri, SocketAddress address, IOException e) { }
                }).build();
    }

    public static ManagedLocalAi load(Path configFile) throws IOException {
        return load(configFile, builder -> builder.start());
    }

    static ManagedLocalAi load(Path configFile, Launcher launcher) throws IOException {
        Path absolute = configFile.toAbsolutePath().normalize();
        Config config = null;
        if (Files.exists(absolute)) {
            if (Files.size(absolute) > MAX_RESPONSE) throw new IOException("Local AI configuration is too large.");
            try {
                JsonObject json = JsonParser.parseString(Files.readString(absolute)).getAsJsonObject();
                if (json.has("enabled") && json.get("enabled").getAsBoolean()) config = Config.read(json);
            } catch (RuntimeException badConfig) {
                throw new IOException("Invalid local AI configuration: " + badConfig.getMessage(), badConfig);
            }
        }
        return new ManagedLocalAi(config, absolute.getParent().resolve("local-ai-runtime"), launcher);
    }

    public boolean configured() { return config != null && !closed; }

    /** Last finished admitted inference call, not command acceptance; null before the first call finishes. */
    public Timing lastTiming() { return lastTiming; }

    /** Runtime-observed startup facts, not inferred from configured gpuLayers or GPU utilisation. */
    public DeviceDiagnostics deviceDiagnostics() { return deviceDiagnostics; }

    public String description() {
        if (closed) return "Local AI stopped.";
        if (config == null) return "Local AI is not configured; ordinary Entity commands still work.";
        return "Local-only " + config.modelName + "; starts on demand; no cloud fallback.";
    }

    /** Schema JSON is supplied by trusted application code, never by the player's message. */
    public String complete(String systemPrompt, String userText, JsonObject schema)
            throws IOException, InterruptedException {
        JsonArray messages = new JsonArray();
        JsonObject message = new JsonObject();
        message.addProperty("role", "user"); message.addProperty("content", userText); messages.add(message);
        return completeConversation(systemPrompt, messages, schema, 768);
    }

    /** Bounded transient dialogue; caller owns player/world scoping and the post-inference authority fence. */
    public String completeConversation(String systemPrompt, JsonArray messages, JsonObject schema, int maxTokens)
            throws IOException, InterruptedException {
        if (!configured()) throw new IOException("Local AI is unavailable; use ordinary Entity commands.");
        // Snapshot/validate before startup: mutable caller arrays cannot change a request during cold loading.
        JsonObject request = conversationRequest(systemPrompt, messages, schema, maxTokens, config.reasoningTokens);
        if (!inference.tryAcquire()) throw new IOException("Local AI is already answering a request.");
        Measurement measurement = new Measurement();
        measurement.outputBudget = maxTokens;
        try {
            Session session = ensureStarted(measurement);
            HttpRequest httpRequest = HttpRequest.newBuilder(session.endpoint.resolve("/v1/chat/completions"))
                    .header("Authorization", "Bearer " + session.token)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(config.timeoutSeconds))
                    .POST(HttpRequest.BodyPublishers.ofString(request.toString())).build();
            long httpBegan = System.nanoTime();
            HttpResponse<byte[]> response;
            try { response = http.send(httpRequest, limitedBody(MAX_RESPONSE)); }
            finally { measurement.httpMillis = elapsedMillis(httpBegan); }
            if (closed || !session.process.isAlive()) throw new IOException("Local AI stopped before replying.");
            if (response.statusCode() != 200) throw new IOException("Local AI returned HTTP " + response.statusCode() + ".");
            String answer = content(response.body());
            JsonObject envelope = JsonParser.parseString(new String(response.body(), StandardCharsets.UTF_8)).getAsJsonObject();
            measurement.promptMillis = reportedMillis(envelope, "prompt_ms");
            measurement.generationMillis = reportedMillis(envelope, "predicted_ms");
            measurement.promptTokens = reportedCount(envelope, "prompt_n");
            measurement.generatedTokens = reportedCount(envelope, "predicted_n");
            measurement.success = true;
            return answer;
        } finally {
            Timing timing = measurement.finish();
            lastTiming = timing;
            inference.release();
            LOG.info(String.format(Locale.ROOT,
                    "Entity2 local AI timing cold=%s success=%s totalMs=%d hashMs=%d startupMs=%d httpMs=%d promptMs=%.3f generationMs=%.3f outputBudget=%d promptTokens=%d generatedTokens=%d cudaDevices=%d offloadedLayers=%d modelLayers=%d cuda0ModelMiB=%.3f",
                    timing.coldStart(), timing.success(), timing.totalMillis(), timing.hashMillis(),
                    timing.startupMillis(), timing.httpMillis(), timing.promptMillis(), timing.generationMillis(),
                    timing.outputBudget(), timing.promptTokens(), timing.generatedTokens(), deviceDiagnostics.cudaDevices(),
                    deviceDiagnostics.offloadedLayers(), deviceDiagnostics.modelLayers(), deviceDiagnostics.cuda0ModelMiB()));
        }
    }

    private Session ensureStarted(Measurement measurement) throws IOException, InterruptedException {
        synchronized (this) {
            if (closed) throw new IOException("Local AI was stopped.");
            if (child != null && child.isAlive()) return new Session(child, endpoint, token);
        }
        // Multi-GB hashing is deliberately on the inference worker, not plugin startup.
        measurement.coldStart = true;
        long hashBegan = System.nanoTime();
        try { config.verifyFiles(); }
        finally { measurement.hashMillis = elapsedMillis(hashBegan); }
        long startupBegan = System.nanoTime();
        try { return startVerified(); }
        finally { measurement.startupMillis = elapsedMillis(startupBegan); }
    }

    private Session startVerified() throws IOException, InterruptedException {
        Session session;
        synchronized (this) {
            if (closed) throw new IOException("Local AI was stopped.");
            cleanupToken();
            Files.createDirectories(runtimeDirectory);
            byte[] secret = new byte[32];
            new SecureRandom().nextBytes(secret);
            token = HexFormat.of().formatHex(secret);
            tokenFile = Files.createTempFile(runtimeDirectory, "session-", ".key");
            restrictTokenFile(tokenFile);
            Files.writeString(tokenFile, token + "\n", StandardCharsets.UTF_8);
            int port;
            try (ServerSocket reservation = new ServerSocket()) {
                reservation.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
                port = reservation.getLocalPort();
            }
            endpoint = URI.create("http://127.0.0.1:" + port);
            ProcessBuilder builder = new ProcessBuilder(command(config, port, tokenFile));
            builder.directory(config.executable.getParent().toFile());
            builder.redirectErrorStream(true);
            // Environment options must not reopen the network, enable a proxy, or override limits.
            builder.environment().keySet().removeIf(k -> k.startsWith("LLAMA_") || k.startsWith("GGML_"));
            try {
                child = launcher.start(builder);
            } catch (IOException failure) {
                cleanupToken();
                throw new IOException("Could not start the pinned local AI runtime.", failure);
            }
            Process owned = child;
            modelReady = false;
            deviceDiagnostics = new DeviceDiagnostics(-1, -1, -1, -1);
            synchronized (startupLog) { startupLog.setLength(0); }
            // Drain continuously in fixed chunks. Logs contain prompts/runtime details, so do not persist them.
            Thread.ofPlatform().daemon().name("Entity2-local-ai-output").start(() -> {
                try (InputStream stream = owned.getInputStream()) {
                    byte[] buffer = new byte[4096];
                    int count;
                    while ((count = stream.read(buffer)) != -1) {
                        if (!modelReady) synchronized (startupLog) {
                            startupLog.append(new String(buffer, 0, count, StandardCharsets.UTF_8));
                            deviceDiagnostics = parseDeviceDiagnostics(startupLog.toString(), deviceDiagnostics);
                            if (startupLog.length() > 4096) startupLog.delete(0, startupLog.length() - 4096);
                        }
                    }
                } catch (IOException ignored) { }
            });
            if (shutdownHook == null) {
                shutdownHook = new Thread(this::close, "Entity2-local-ai-shutdown");
                Runtime.getRuntime().addShutdownHook(shutdownHook);
            }
            session = new Session(child, endpoint, token);
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.startupSeconds);
        try {
            while (System.nanoTime() < deadline) {
                if (closed || !session.process.isAlive()) {
                    String detail;
                    synchronized (startupLog) { detail = startupLog.toString().replace(session.token, "[redacted]"); }
                    throw new IOException("Local AI runtime exited before becoming ready: " + detail);
                }
                try {
                    HttpResponse<byte[]> ready = http.send(HttpRequest.newBuilder(session.endpoint.resolve("/v1/models"))
                                    .header("Authorization", "Bearer " + session.token).timeout(Duration.ofSeconds(2)).GET().build(),
                            limitedBody(8192));
                    if (ready.statusCode() == 200) {
                        modelReady = true;
                        synchronized (startupLog) { startupLog.setLength(0); }
                        return session;
                    }
                } catch (IOException notReady) { /* Startup is bounded by deadline. */ }
                Thread.sleep(100);
            }
            throw new IOException("Local AI model did not load before its startup deadline.");
        } catch (IOException | InterruptedException failure) {
            stopOwned(session.process);
            synchronized (this) { cleanupToken(); }
            throw failure;
        }
    }

    /** Numeric-only stage evidence; prompt/generation are runtime-reported subsets of HTTP, -1 if unknown. */
    public record Timing(boolean coldStart, boolean success, long totalMillis, long hashMillis,
                         long startupMillis, long httpMillis, double promptMillis, double generationMillis,
                         int outputBudget, int promptTokens, int generatedTokens) { }

    /** Numeric-only startup evidence; -1 means no supported runtime evidence was observed. */
    public record DeviceDiagnostics(int cudaDevices, int offloadedLayers, int modelLayers, double cuda0ModelMiB) {
        public boolean fullCudaOffload() {
            return cudaDevices > 0 && cuda0ModelMiB > 0 && modelLayers > 0 && offloadedLayers == modelLayers;
        }
    }

    private static final Pattern CUDA_DEVICES = Pattern.compile("ggml_cuda_init: found ([0-9]{1,2}) CUDA devices");
    private static final Pattern OFFLOAD = Pattern.compile("offloaded ([0-9]{1,3})/([0-9]{1,3}) layers to GPU");
    private static final Pattern CUDA_MODEL = Pattern.compile("CUDA0\\s+model buffer size =\\s*([0-9]{1,6}(?:\\.[0-9]{1,3})?) MiB");

    static DeviceDiagnostics parseDeviceDiagnostics(String text, DeviceDiagnostics previous) {
        int devices = previous.cudaDevices(), offloaded = previous.offloadedLayers(), layers = previous.modelLayers();
        double mib = previous.cuda0ModelMiB();
        var cuda = CUDA_DEVICES.matcher(text);
        if (cuda.find()) devices = Integer.parseInt(cuda.group(1));
        var offload = OFFLOAD.matcher(text);
        if (offload.find()) {
            int actual = Integer.parseInt(offload.group(1)), total = Integer.parseInt(offload.group(2));
            if (actual <= total && total > 0) { offloaded = actual; layers = total; }
        }
        var memory = CUDA_MODEL.matcher(text);
        if (memory.find()) {
            mib = Double.parseDouble(memory.group(1));
            // Some builds enumerate CUDA before logging is enabled; CUDA0 allocation still proves one observed device.
            devices = Math.max(devices, 1);
        }
        return new DeviceDiagnostics(devices, offloaded, layers, mib);
    }

    private static final class Measurement {
        final long began = System.nanoTime();
        boolean coldStart, success;
        long hashMillis, startupMillis, httpMillis;
        double promptMillis = -1, generationMillis = -1;
        int outputBudget, promptTokens = -1, generatedTokens = -1;
        Timing finish() {
            return new Timing(coldStart, success, elapsedMillis(began), hashMillis, startupMillis,
                    httpMillis, promptMillis, generationMillis, outputBudget, promptTokens, generatedTokens);
        }
    }

    private static long elapsedMillis(long began) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began); }

    // llama.cpp b11146's chat response includes timings without enabling verbose prompt logging.
    // Missing/invalid optional telemetry stays unknown (-1); it must not change admission semantics.
    static double reportedMillis(JsonObject envelope, String name) {
        try {
            var value = envelope.getAsJsonObject("timings").get(name);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return -1;
            double millis = value.getAsDouble();
            return Double.isFinite(millis) && millis >= 0 && millis <= 3_600_000 ? millis : -1;
        } catch (RuntimeException invalid) { return -1; }
    }

    static int reportedCount(JsonObject envelope, String name) {
        double value = reportedMillis(envelope, name);
        return value >= 0 && value <= 1_000_000 && value == Math.rint(value) ? (int) value : -1;
    }

    static List<String> command(Config config, int port, Path tokenFile) {
        List<String> command = new ArrayList<>(List.of(config.executable.toString(), "--model", config.model.toString(),
                "--alias", "entity2-local", "--host", "127.0.0.1", "--port", Integer.toString(port),
                "--api-key-file", tokenFile.toString(), "--ctx-size", Integer.toString(config.contextSize),
                "--gpu-layers", Integer.toString(config.gpuLayers), "--threads", Integer.toString(config.threads),
                "--threads-batch", Integer.toString(config.threads), "--parallel", "1", "--batch-size", "256",
                "--ubatch-size", "128", "--jinja", "--reasoning", config.reasoningTokens > 0 ? "on" : "off",
                "--reasoning-budget", Integer.toString(config.reasoningTokens), "--no-webui", "--no-slots", "--log-verbosity", "4", "--log-colors", "off"));
        // Keep private reasoning outside message.content and do not carry it into later turns.
        // b11146 can infer thinking support from history-only </think> handling in a
        // nonthinking template. NONE removes that optional prelude from the JSON
        // grammar; --reasoning off alone only changes template rendering.
        if (config.reasoningTokens == 0) command.addAll(List.of("--reasoning-format", "none"));
        if (config.reasoningTokens > 0) command.addAll(List.of("--reasoning-format", "deepseek", "--no-reasoning-preserve"));
        return List.copyOf(command);
    }

    static JsonObject request(String system, String user, JsonObject schema) {
        JsonArray messages = new JsonArray();
        JsonObject message = new JsonObject();
        message.addProperty("role", "user"); message.addProperty("content", user); messages.add(message);
        return requestBody(system, messages, schema, 768, 0);
    }

    static JsonObject conversationRequest(String system, JsonArray messages, JsonObject schema, int maxTokens)
            throws IOException {
        return conversationRequest(system, messages, schema, maxTokens, 0);
    }

    static JsonObject conversationRequest(String system, JsonArray messages, JsonObject schema, int maxTokens, int reasoningTokens)
            throws IOException {
        if (system == null || system.length() > 24_000 || schema == null || schema.toString().length() > 24_000
                || messages == null || messages.isEmpty() || messages.size() > 24 || maxTokens < 32 || maxTokens > 768
                || reasoningTokens < 0 || reasoningTokens > 256) {
            throw new IOException("Local AI request exceeds its input limit.");
        }
        JsonArray copy = new JsonArray();
        int characters = 0;
        for (var element : messages) {
            if (!element.isJsonObject()) throw new IOException("Local AI dialogue contains an invalid message.");
            JsonObject message = element.getAsJsonObject();
            if (message.size() != 2 || !stringField(message, "role") || !stringField(message, "content")) {
                throw new IOException("Local AI dialogue contains an invalid message.");
            }
            String role = message.get("role").getAsString(), content = message.get("content").getAsString();
            if (!(role.equals("user") || role.equals("assistant")) || content.isBlank() || content.length() > 4096
                    || (characters += content.length()) > 16_000) {
                throw new IOException("Local AI dialogue exceeds its role or input limit.");
            }
            copy.add(message.deepCopy());
        }
        return requestBody(system, copy, schema, maxTokens, reasoningTokens);
    }

    private static boolean stringField(JsonObject object, String name) {
        return object.has(name) && object.get(name).isJsonPrimitive() && object.getAsJsonPrimitive(name).isString();
    }

    private static JsonObject requestBody(String system, JsonArray dialogue, JsonObject schema, int maxTokens, int reasoningTokens) {
        JsonObject root = new JsonObject();
        root.addProperty("model", "entity2-local");
        root.addProperty("stream", false);
        root.addProperty("temperature", 0.0);
        // llama.cpp's output ceiling includes reasoning. Reserve a separate bounded allowance for it.
        root.addProperty("max_tokens", maxTokens + reasoningTokens);
        root.addProperty("reasoning_budget_tokens", reasoningTokens);
        root.addProperty("reasoning_format", reasoningTokens == 0 ? "none" : "deepseek");
        JsonArray messages = new JsonArray();
        JsonObject trusted = new JsonObject();
        trusted.addProperty("role", "system"); trusted.addProperty("content", system); messages.add(trusted);
        dialogue.forEach(message -> messages.add(message.deepCopy()));
        root.add("messages", messages);
        JsonObject format = new JsonObject();
        format.addProperty("type", "json_schema");
        JsonObject body = new JsonObject();
        body.addProperty("name", "entity_action"); body.addProperty("strict", true);
        body.add("schema", schema.deepCopy()); format.add("json_schema", body);
        root.add("response_format", format);
        JsonObject template = new JsonObject(); template.addProperty("enable_thinking", reasoningTokens > 0);
        template.addProperty("preserve_reasoning", false);
        root.add("chat_template_kwargs", template);
        return root;
    }

    static String content(byte[] bytes) throws IOException {
        try {
            JsonObject response = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject choice = response.getAsJsonArray("choices").get(0).getAsJsonObject();
            if (!"stop".equals(choice.get("finish_reason").getAsString())) {
                throw new IOException("Local AI answer was incomplete; no action was accepted.");
            }
            String value = choice.getAsJsonObject("message").get("content").getAsString();
            if (value.isBlank() || value.length() > 16_384) throw new IOException("Local AI answer exceeded its output limit.");
            // Grammar constrains syntax. The caller still validates meaning and current authority.
            if (!JsonParser.parseString(value).isJsonObject()) throw new IOException("Local AI answer was not an object.");
            return value;
        } catch (RuntimeException malformed) {
            throw new IOException("Local AI returned an invalid structured answer.", malformed);
        }
    }

    private static HttpResponse.BodyHandler<byte[]> limitedBody(int limit) {
        return ignored -> new HttpResponse.BodySubscriber<>() {
            private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
            private Flow.Subscription subscription;
            private long total;
            @Override public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
            @Override public void onSubscribe(Flow.Subscription value) { subscription = value; delegate.onSubscribe(value); }
            @Override public void onNext(List<ByteBuffer> buffers) {
                for (ByteBuffer buffer : buffers) total += buffer.remaining();
                if (total > limit) {
                    subscription.cancel(); delegate.onError(new IOException("Local AI response exceeded its byte limit."));
                } else delegate.onNext(buffers);
            }
            @Override public void onError(Throwable failure) { delegate.onError(failure); }
            @Override public void onComplete() { delegate.onComplete(); }
        };
    }

    @Override public void close() {
        Process owned;
        synchronized (this) { closed = true; owned = child; }
        stopOwned(owned);
        synchronized (this) {
            cleanupToken();
            if (shutdownHook != null && Thread.currentThread() != shutdownHook) {
                try { Runtime.getRuntime().removeShutdownHook(shutdownHook); } catch (IllegalStateException ignored) { }
            }
        }
        http.shutdownNow();
    }

    private static void stopOwned(Process process) {
        if (process == null || !process.isAlive()) return;
        // Process objects retain exact OS custody. Never search/kill by executable name or stale PID.
        process.destroy();
        if (process.isAlive()) process.destroyForcibly();
    }

    private void cleanupToken() {
        if (tokenFile != null) {
            try { Files.deleteIfExists(tokenFile); } catch (IOException ignored) { tokenFile.toFile().deleteOnExit(); }
            tokenFile = null;
        }
        token = null;
    }

    private static void restrictTokenFile(Path file) throws IOException {
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } else if (Files.getFileStore(file).supportsFileAttributeView("acl")) {
            var view = Files.getFileAttributeView(file, java.nio.file.attribute.AclFileAttributeView.class);
            var owner = file.getFileSystem().getUserPrincipalLookupService()
                    .lookupPrincipalByName(System.getProperty("user.name"));
            view.setAcl(List.of(java.nio.file.attribute.AclEntry.newBuilder()
                    .setType(java.nio.file.attribute.AclEntryType.ALLOW).setPrincipal(owner)
                    .setPermissions(java.nio.file.attribute.AclEntryPermission.values()).build()));
        }
    }

    @FunctionalInterface interface Launcher { Process start(ProcessBuilder builder) throws IOException; }
    private record Session(Process process, URI endpoint, String token) { }

    record Config(Path executable, String executableSha256, Path model, String modelSha256,
                  String modelName, Map<String, String> runtimeFiles, int gpuLayers, int contextSize,
                  int threads, int timeoutSeconds, int startupSeconds, int reasoningTokens) {
        static Config read(JsonObject json) {
            Path executable = absolute(json, "executable"); Path model = absolute(json, "model");
            Map<String, String> files = new LinkedHashMap<>();
            if (json.has("runtimeFiles")) {
                json.getAsJsonObject("runtimeFiles").entrySet().forEach(entry -> {
                    if (!entry.getKey().matches("[A-Za-z0-9_.-]+\\.dll")) throw new IllegalArgumentException("Invalid runtime filename");
                    files.put(entry.getKey(), hash(entry.getValue().getAsString()));
                });
            }
            String name = json.has("modelName") ? json.get("modelName").getAsString() : "local model";
            if (!name.matches("[A-Za-z0-9_. -]{1,80}")) throw new IllegalArgumentException("Invalid model name");
            return new Config(executable, hash(json.get("executableSha256").getAsString()), model,
                    hash(json.get("modelSha256").getAsString()), name, Map.copyOf(files),
                    number(json, "gpuLayers", 99, 0, 200), number(json, "contextSize", 4096, 2048, 8192),
                    number(json, "threads", 4, 1, 16), number(json, "timeoutSeconds", 45, 5, 120),
                    number(json, "startupSeconds", 90, 5, 180), number(json, "reasoningTokens", 0, 0, 256));
        }
        private static int number(JsonObject json, String key, int fallback, int min, int max) {
            if (!json.has(key)) return fallback;
            String text = json.get(key).getAsString();
            if (!text.matches("[0-9]+")) throw new IllegalArgumentException("Invalid " + key);
            int value = Integer.parseInt(text);
            if (value < min || value > max) throw new IllegalArgumentException("Out-of-range " + key);
            return value;
        }
        private static Path absolute(JsonObject json, String key) {
            Path path = Path.of(json.get(key).getAsString());
            if (!path.isAbsolute() || !Files.isRegularFile(path)) throw new IllegalArgumentException("Missing absolute " + key + " file");
            return path.normalize();
        }
        private static String hash(String value) {
            if (!value.matches("(?i)[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid SHA-256 pin");
            return value.toLowerCase(java.util.Locale.ROOT);
        }
        void verifyFiles() throws IOException {
            verify(executable, executableSha256); verify(model, modelSha256);
            for (var entry : runtimeFiles.entrySet()) verify(executable.getParent().resolve(entry.getKey()), entry.getValue());
        }
        private static void verify(Path file, String expected) throws IOException {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream input = Files.newInputStream(file)) {
                    byte[] buffer = new byte[1 << 20]; int count;
                    while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
                }
                if (!MessageDigest.isEqual(HexFormat.of().parseHex(expected), digest.digest())) {
                    throw new IOException("Local AI file failed SHA-256 verification: " + file.getFileName());
                }
            } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        }
    }
}
