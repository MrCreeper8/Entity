using System.Text.Json;

namespace Entity.Desktop;

/// <summary>Read-only, conservative presentation of observed runtime state.</summary>
public static class DashboardState
{
    public static bool IsFresh(DateTimeOffset observed, DateTimeOffset now) =>
        now - observed >= TimeSpan.Zero && now - observed < TimeSpan.FromSeconds(10);

    public static (bool Fresh, bool InGame, bool Paired) Bot(JsonElement? heartbeat, int? processId, DateTimeOffset now)
    {
        if (heartbeat is not JsonElement value || processId == null) return default;
        try
        {
            if (value.GetProperty("processId").GetInt32() != processId ||
                !DateTimeOffset.TryParse(value.GetProperty("heartbeatAtUtc").GetString(), out var observed) ||
                !IsFresh(observed, now)) return default;
            return (true, value.GetProperty("gameReady").GetBoolean(), value.GetProperty("bridgeConnected").GetBoolean());
        }
        catch (Exception error) when (error is KeyNotFoundException or InvalidOperationException or FormatException) { return default; }
    }

    public static (string State, string Detail)? Ai(string json, int? expectedProcessId, DateTimeOffset now)
    {
        try
        {
            using var document = JsonDocument.Parse(json);
            var value = document.RootElement;
            var pid = value.GetProperty("processId").GetInt32();
            if (value.GetProperty("schemaVersion").GetInt32() != 1 || pid <= 0 ||
                expectedProcessId is int expected && pid != expected ||
                !DateTimeOffset.TryParse(value.GetProperty("observedAtUtc").GetString(), out var observed) || !IsFresh(observed, now)) return null;
            var state = value.GetProperty("state").GetString();
            var enabled = value.GetProperty("enabled").GetBoolean();
            var provider = value.GetProperty("provider").GetString();
            if (value.TryGetProperty("detail", out var detail) && detail.GetString() == "The server plugin stopped; this receipt is no longer a live heartbeat.") return null;
            // Present known state text, never raw provider output, URLs or credentials.
            return state switch
            {
                "unconfigured" => ("Not configured", "Choose optional AI in Settings."),
                "configured" => ("Configured", "Provider selected; readiness not yet confirmed."),
                "loading" when enabled => ("Loading", "The server is preparing the AI provider."),
                "ready" when enabled && provider == "external" => ("Ready", "The last actual AI request succeeded."),
                "ready" when enabled && provider == "managed" => ("Ready", "The paired server confirms its AI runtime is ready."),
                "failed" => ("Needs attention", "AI could not become ready. Open Logs or use /e ai status."),
                "stopped" when !enabled && provider == "none" => ("Off", "AI is off; ordinary commands remain available."),
                "stopped" => ("Stopped", "AI is stopped on the paired server."),
                _ => null
            };
        }
        catch (Exception error) when (error is JsonException or KeyNotFoundException or InvalidOperationException or FormatException or OverflowException) { return null; }
    }
}
