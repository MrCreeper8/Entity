package dev.entitybridge.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/** Explicit provider selection only. Loading never probes a network or launches inference. */
public final class AiBackendFactory {
    private AiBackendFactory() { }

    public interface Provider extends EntityAi.Backend {
        boolean configured();
        /** Configuration/last observed request status, never a claim that an endpoint is ready now. */
        String description();
    }

    /** These messages contain field names only and are safe for an owner-facing setup error. */
    public static final class ConfigurationException extends IOException {
        ConfigurationException(String message) { super(message); }
    }

    public static Provider load(Path dataDirectory) throws IOException {
        return load(dataDirectory, System::getenv);
    }

    static Provider load(Path dataDirectory, Function<String, String> environment) throws IOException {
        Path file = dataDirectory.resolve("ai-provider.json");
        if (!Files.exists(file)) return managed(dataDirectory);
        JsonObject json;
        try {
            if (Files.size(file) > 8192) throw new ConfigurationException("AI provider configuration exceeds 8192 bytes.");
            json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        } catch (ConfigurationException failure) { throw failure; }
        catch (RuntimeException malformed) { throw new ConfigurationException("AI provider configuration must be a JSON object."); }
        catch (IOException unreadable) { throw new ConfigurationException("AI provider configuration could not be read."); }
        Set<String> fields = Set.of("provider", "baseUrl", "model", "allowCloud", "apiKeyEnv");
        if (!fields.containsAll(json.keySet()))
            throw new ConfigurationException("Unknown AI provider field; credentials belong in process environment, not JSON.");
        String provider = string(json, "provider", null);
        if ("managed".equals(provider)) return managed(dataDirectory);
        if (!"external".equals(provider)) throw new ConfigurationException("AI provider must be managed or external.");
        ExternalConfig config = externalConfig(json);
        String key = environment.apply(config.apiKeyEnv());
        if (key == null || key.isBlank()) key = "";
        if (!key.isEmpty() && (!key.matches("[\\x21-\\x7E]{1,4096}")))
            throw new ConfigurationException("AI API key environment value is not a valid bearer credential.");
        if (!config.loopback() && key.isEmpty())
            throw new ConfigurationException("Cloud AI requires the API key environment variable to be set.");
        return new ExternalAi(config, key);
    }

    private static Provider managed(Path directory) throws IOException {
        ManagedLocalAi backend = ManagedLocalAi.load(directory.resolve("local-ai.json"));
        return new Provider() {
            public boolean configured() { return backend.configured(); }
            public String description() { return backend.description(); }
            public String complete(String system, String request, JsonObject schema) throws Exception {
                return backend.complete(system, request, schema);
            }
            public String completeConversation(String system, com.google.gson.JsonArray messages, JsonObject schema, int tokens) throws Exception {
                return backend.completeConversation(system, messages, schema, tokens);
            }
            public void close() { backend.close(); }
        };
    }

    record ExternalConfig(URI baseUrl, String model, boolean loopback, String apiKeyEnv) { }

    static ExternalConfig externalConfig(JsonObject json) throws ConfigurationException {
        String model = string(json, "model", null);
        if (model == null || !model.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}"))
            throw new ConfigurationException("External AI needs a valid model identifier (1–160 characters).");
        String name = string(json, "apiKeyEnv", "ENTITY_AI_API_KEY");
        if (!name.matches("[A-Za-z_][A-Za-z0-9_]{0,127}"))
            throw new ConfigurationException("apiKeyEnv must name a process environment variable, not contain a key.");
        boolean consent = false;
        if (json.has("allowCloud")) {
            if (!json.get("allowCloud").isJsonPrimitive() || !json.getAsJsonPrimitive("allowCloud").isBoolean())
                throw new ConfigurationException("allowCloud must be an explicit JSON boolean.");
            consent = json.get("allowCloud").getAsBoolean();
        }
        URI uri;
        try { uri = URI.create(string(json, "baseUrl", "")); }
        catch (RuntimeException malformed) { throw new ConfigurationException("baseUrl must be an absolute HTTP(S) API base URL."); }
        String host = uri.getHost(), scheme = uri.getScheme(), path = uri.getRawPath();
        if (host == null || scheme == null || uri.getUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || path == null || !path.matches("[A-Za-z0-9._~/-]*")
                || java.util.Arrays.stream(path.split("/", -1)).anyMatch(segment -> segment.equals("..") || segment.equals(".")))
            throw new ConfigurationException("baseUrl must be HTTP(S), with no credentials, query, fragment or traversal.");
        boolean local = loopback(host);
        if (!local && !scheme.equalsIgnoreCase("https"))
            throw new ConfigurationException("External non-loopback AI requires HTTPS; cleartext is loopback-only.");
        if (!local && !consent)
            throw new ConfigurationException("Cloud AI is blocked until allowCloud is explicitly true.");
        // Resolve localhost to a literal locally, never trust DNS to keep cleartext on loopback.
        if (host.equalsIgnoreCase("localhost")) host = "127.0.0.1";
        String normalizedPath = path.replaceFirst("/+$", "");
        try { uri = new URI(scheme.toLowerCase(Locale.ROOT), null, host, uri.getPort(), normalizedPath, null, null); }
        catch (java.net.URISyntaxException invalid) { throw new ConfigurationException("Invalid baseUrl."); }
        return new ExternalConfig(uri, model, local, name);
    }

    private static boolean loopback(String host) {
        if (host.equalsIgnoreCase("localhost") || host.equals("[::1]") || host.equals("::1")) return true;
        if (!host.matches("127(?:\\.[0-9]{1,3}){3}")) return false;
        for (String octet : host.split("\\.")) if (Integer.parseInt(octet) > 255) return false;
        return true;
    }

    private static String string(JsonObject json, String field, String fallback) throws ConfigurationException {
        if (!json.has(field)) return fallback;
        if (!json.get(field).isJsonPrimitive() || !json.getAsJsonPrimitive(field).isString())
            throw new ConfigurationException(field + " must be a JSON string.");
        return json.get(field).getAsString();
    }
}
