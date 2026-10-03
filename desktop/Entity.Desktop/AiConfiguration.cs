using System.IO.Compression;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace Entity.Desktop;

public static class AiConfiguration
{
    public static async Task ConfigureAsync(AppSettings settings, string pluginData, AppPaths paths, Action<string> report, CancellationToken cancellationToken = default, IProgress<SetupProgress>? progress = null)
    {
        cancellationToken.ThrowIfCancellationRequested();
        progress?.Report(new SetupProgress("Preparing AI preference"));
        settings.Validate();
        var configFile = Path.Combine(pluginData, "config.yml");
        var yaml = File.ReadAllText(configFile);
        var enabled = settings.AiMode != "Off";
        var pattern = new Regex("(?m)(^local-ai:\\s*\\r?\\n  enabled: )(?:true|false)(\\s*)$");
        if (pattern.Matches(yaml).Count != 1) throw new InvalidOperationException("Server AI preference could not be identified. Existing configuration was preserved.");
        var provider = settings.AiMode == "External" ? ExternalProvider(settings) : new { provider = "managed" } as object;
        var providerFile = Path.Combine(pluginData, "ai-provider.json");
        var providerJson = JsonSerializer.Serialize(provider, AppPaths.Json);
        var updated = pattern.Replace(yaml, m => m.Groups[1].Value + enabled.ToString().ToLowerInvariant() + m.Groups[2].Value);
        if (!settings.ManageServer && ServerRuntime.PortInUse(settings.ServerPort))
        {
            // Never imply that disk edits reconfigure a running plugin or its environment.
            var sameProvider = !File.Exists(providerFile) ? settings.AiMode is "Off" or "Managed" :
                System.Text.Json.Nodes.JsonNode.DeepEquals(System.Text.Json.Nodes.JsonNode.Parse(File.ReadAllText(providerFile)), System.Text.Json.Nodes.JsonNode.Parse(providerJson));
            if (updated != yaml || !sameProvider)
                throw new InvalidOperationException("Stop the existing server before applying different AI settings, then restart it. Its active settings were not changed.");
            return;
        }
        if (settings.AiMode == "Managed") await PrepareManagedAsync(pluginData, paths, report, cancellationToken, progress);
        cancellationToken.ThrowIfCancellationRequested();
        AppPaths.AtomicWrite(providerFile, providerJson);
        AppPaths.AtomicWrite(configFile, updated);
        report(settings.AiMode == "Off" ? "AI is off. All ordinary in-game commands remain available." : "AI preference saved; actual readiness is checked by the running server.");
    }

    private static object ExternalProvider(AppSettings settings)
    {
        if (!Uri.TryCreate(settings.AiBaseUrl.TrimEnd('/'), UriKind.Absolute, out var uri) || uri.Scheme is not ("http" or "https") ||
            uri.UserInfo.Length != 0 || uri.Query.Length != 0 || uri.Fragment.Length != 0)
            throw new InvalidOperationException("Enter an OpenAI-compatible base URL without a password or query, for example http://127.0.0.1:11434/v1.");
        var local = uri.IsLoopback;
        if (!local && (!settings.CloudConsent || uri.Scheme != "https"))
            throw new InvalidOperationException("Non-local AI requires HTTPS and explicit cloud consent. No data has been sent.");
        if (!Regex.IsMatch(settings.AiModel, "^[A-Za-z0-9._:/-]{1,160}$"))
            throw new InvalidOperationException("Enter the exact model ID from your AI provider.");
        return new { provider = "external", baseUrl = uri.AbsoluteUri.TrimEnd('/'), model = settings.AiModel, allowCloud = settings.CloudConsent, apiKeyEnv = "ENTITY_AI_API_KEY" };
    }

    public static async Task PrepareManagedAsync(string pluginData, AppPaths paths, Action<string> report, CancellationToken cancellationToken = default, IProgress<SetupProgress>? progress = null)
    {
        cancellationToken.ThrowIfCancellationRequested();
        var configuration = Path.Combine(pluginData, "local-ai.json");
        // Preserve an existing verified user-selected managed model; the backend checks its pins.
        if (File.Exists(configuration)) { report("Keeping the existing managed AI configuration; the server verifies its files before use."); return; }
        var manifestFile = Path.Combine(AppContext.BaseDirectory, "local-ai", "pinned-runtime.json");
        using var manifest = JsonDocument.Parse(File.ReadAllText(manifestFile));
        var cache = Path.Combine(paths.Root, "ai"); Directory.CreateDirectory(cache);
        async Task<string> Download(JsonElement asset, string name)
        {
            if (Path.GetFileName(name) != name) throw new InvalidOperationException("Invalid managed AI asset name.");
            var file = Path.Combine(cache, name);
            var expected = asset.GetProperty("sha256").GetString()!;
            var bytes = asset.GetProperty("bytes").GetInt64();
            using var http = new HttpClient { Timeout = Timeout.InfiniteTimeSpan };
            report("Preparing " + name + " (" + Math.Round(bytes / 1048576.0) + " MiB); AI off needs none of these files.");
            await SetupTransfer.DownloadAsync(http, asset.GetProperty("url").GetString()!, file, name,
                expected, bytes, cancellationToken, progress, TimeSpan.FromMinutes(40));
            return file;
        }
        var runtimeZip = await Download(manifest.RootElement.GetProperty("runtime"), "llama-b11146-win-cuda12.4.zip");
        var cudaZip = await Download(manifest.RootElement.GetProperty("cuda"), "cudart-b11146-win-cuda12.4.zip");
        var modelAsset = manifest.RootElement.GetProperty("model");
        var model = await Download(modelAsset, modelAsset.GetProperty("fileName").GetString()!);
        var native = Path.Combine(cache, "llama-b11146"); Directory.CreateDirectory(native);
        foreach (var archiveFile in new[] { runtimeZip, cudaZip })
        {
            using var archive = ZipFile.OpenRead(archiveFile);
            foreach (var entry in archive.Entries)
            {
                cancellationToken.ThrowIfCancellationRequested();
                if (!Regex.IsMatch(entry.Name, "^(llama-server\\.exe|.+\\.dll|LICENSE.*|COPYING.*)$", RegexOptions.IgnoreCase)) continue;
                progress?.Report(new SetupProgress("Extracting AI runtime: " + entry.Name));
                var destination = Path.Combine(native, entry.Name);
                var partial = destination + ".partial-" + Guid.NewGuid().ToString("N");
                try
                {
                    await using (var input = entry.Open())
                    await using (var output = new FileStream(partial, FileMode.CreateNew, FileAccess.Write, FileShare.None, 65536, true))
                        await input.CopyToAsync(output, cancellationToken);
                    cancellationToken.ThrowIfCancellationRequested();
                    File.Move(partial, destination, true);
                }
                finally { if (File.Exists(partial)) File.Delete(partial); }
            }
        }
        async Task<string> Hash(string file)
        {
            await using var stream = new FileStream(file, FileMode.Open, FileAccess.Read, FileShare.Read, 65536, true);
            return Convert.ToHexString(await SHA256.HashDataAsync(stream, cancellationToken)).ToLowerInvariant();
        }
        var exe = Path.Combine(native, "llama-server.exe");
        var runtimeFiles = new Dictionary<string, string>();
        progress?.Report(new SetupProgress("Verifying extracted AI runtime"));
        foreach (var file in Directory.GetFiles(native, "*.dll").OrderBy(x => x)) runtimeFiles[Path.GetFileName(file)] = await Hash(file);
        var config = new { enabled = true, executable = exe, executableSha256 = await Hash(exe), runtimeFiles, model, modelSha256 = modelAsset.GetProperty("sha256").GetString(),
            modelName = modelAsset.GetProperty("name").GetString(), gpuLayers = 99, contextSize = 4096, reasoningTokens = 0, threads = 4, timeoutSeconds = 45, startupSeconds = 90 };
        cancellationToken.ThrowIfCancellationRequested();
        AppPaths.AtomicWrite(configuration, JsonSerializer.Serialize(config, AppPaths.Json));
        report("Managed NVIDIA/CUDA AI pack verified. The server will start it and report readiness; no cloud fallback.");
    }
}
