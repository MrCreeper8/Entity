using CmlLib.Core;
using CmlLib.Core.Auth;
using CmlLib.Core.ProcessBuilder;
using CmlLib.Core.Version;
using System.Diagnostics;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Entity.Desktop;

public sealed record PayloadFile(string Role, string File, string Sha256, string? DownloadUrl = null);
public sealed record ProductPayload(string Version, string SourceCommit, PayloadFile[] Files);

public sealed class MinecraftRuntime
{
    public const string MinecraftVersion = "1.21.8";
    public const string FabricVersion = "0.16.14";
    public const string FabricProfile = "fabric-loader-0.16.14-1.21.8";
    private readonly AppPaths paths;
    private readonly string payloadDirectory;
    private readonly Action<string> report;
    private Process? owned;
    private StreamWriter? writer;
    public MinecraftRuntime(AppPaths paths, string payloadDirectory, Action<string>? report = null)
    {
        this.paths = paths;
        this.payloadDirectory = Path.GetFullPath(payloadDirectory);
        this.report = report ?? (_ => { });
    }
    public ProductPayload ValidatePayload()
    {
        var payload = JsonSerializer.Deserialize<ProductPayload>(File.ReadAllText(Path.Combine(payloadDirectory, "product.json")), AppPaths.Json)
            ?? throw new InvalidOperationException("The Entity product package is missing.");
        foreach (var file in payload.Files)
        {
            if (Path.GetFileName(file.File) != file.File || file.File.Length == 0 || !System.Text.RegularExpressions.Regex.IsMatch(file.Sha256, "^[A-Fa-f0-9]{64}$"))
                throw new InvalidOperationException("Invalid package file name.");
            if (file.DownloadUrl != null)
            {
                if (file.Role is not ("fabric-api" or "baritone") || !Uri.TryCreate(file.DownloadUrl, UriKind.Absolute, out var uri) ||
                    uri.Scheme != "https" || uri.UserInfo.Length != 0 || uri.Query.Length != 0 || uri.Fragment.Length != 0)
                    throw new InvalidOperationException("Invalid dependency download declaration.");
                continue;
            }
            var actual = Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(Path.Combine(payloadDirectory, file.File))));
            if (!actual.Equals(file.Sha256, StringComparison.OrdinalIgnoreCase))
                throw new InvalidOperationException($"Package verification failed: {file.File}. Reinstall Entity.");
        }
        foreach (var role in new[] { "client", "fabric-api", "baritone", "server-plugin" })
            if (payload.Files.Count(x => x.Role == role) != 1)
                throw new InvalidOperationException($"Package needs exactly one {role} component.");
        return payload;
    }
    public async Task PrepareAsync(AppSettings settings, CancellationToken cancellationToken = default, IProgress<SetupProgress>? progress = null)
    {
        cancellationToken.ThrowIfCancellationRequested();
        using var active = ProcessCustody.Open(Path.Combine(paths.Root, "client-process.json"));
        if (active != null) throw new InvalidOperationException("Stop Entity before preparing or upgrading its files.");
        settings.Validate();
        var payload = ValidatePayload();
        var minecraftPath = new MinecraftPath(paths.Game);
        using var metadataHttp = new HttpClient(new SetupMetadataHandler(cancellationToken));
        using var downloadHttp = new HttpClient { Timeout = Timeout.InfiniteTimeSpan };
        var parameters = MinecraftLauncherParameters.CreateDefault(minecraftPath, metadataHttp);
        parameters.GameInstaller = new VerifiedGameInstaller(downloadHttp, progress);
        var launcher = new MinecraftLauncher(parameters);
        var last = DateTime.MinValue;
        launcher.FileProgressChanged += (_, fileProgress) =>
        {
            if ((DateTime.UtcNow - last).TotalSeconds < 1) return;
            last = DateTime.UtcNow;
            report($"Installing Minecraft: {fileProgress.ProgressedTasks}/{fileProgress.TotalTasks} files");
        };
        report("Preparing Minecraft 1.21.8 and Java 21 (first launch downloads required files)...");
        progress?.Report(new SetupProgress("Preparing Minecraft 1.21.8 and Java 21"));
        await launcher.InstallAsync(MinecraftVersion, cancellationToken);
        // FabricInstaller in pinned CmlLib 4.0.6 has no cancellation API and writes
        // directly to its final JSON. Fetch the same pinned profile atomically.
        await SetupTransfer.DownloadAsync(downloadHttp,
            $"https://meta.fabricmc.net/v2/versions/loader/{MinecraftVersion}/{FabricVersion}/profile/json",
            minecraftPath.GetVersionJsonPath(FabricProfile), "Fabric profile", null, null, cancellationToken, progress,
            validate: async file =>
            {
                using var profile = JsonDocument.Parse(await File.ReadAllTextAsync(file, cancellationToken));
                if (profile.RootElement.GetProperty("id").GetString() != FabricProfile ||
                    profile.RootElement.GetProperty("inheritsFrom").GetString() != MinecraftVersion)
                    throw new InvalidDataException("The Fabric profile did not match the requested version.");
            });
        await launcher.InstallAsync(FabricProfile, cancellationToken);
        // Finish all network work before changing the installed managed-mod set.
        var sources = new Dictionary<string, string>();
        foreach (var file in payload.Files.Where(x => x.Role is "client" or "fabric-api" or "baritone"))
        {
            var source = Path.Combine(payloadDirectory, file.File);
            if (file.DownloadUrl != null)
            {
                source = Path.Combine(paths.Root, "downloads", file.File);
                report("Preparing verified " + file.Role + " from its publisher...");
                await SetupTransfer.DownloadAsync(downloadHttp, file.DownloadUrl, source, file.Role,
                    file.Sha256, null, cancellationToken, progress);
            }
            sources[file.File] = source;
        }
        cancellationToken.ThrowIfCancellationRequested();
        progress?.Report(new SetupProgress("Installing verified Entity components"));
        Directory.CreateDirectory(Path.Combine(paths.Game, "mods"));
        var installed = new List<PayloadFile>();
        var receiptFile = Path.Combine(paths.Root, "managed-mods.json");
        var previous = File.Exists(receiptFile) ? JsonSerializer.Deserialize<PayloadFile[]>(File.ReadAllText(receiptFile), AppPaths.Json)! : Array.Empty<PayloadFile>();
        foreach (var old in previous)
        {
            if (Path.GetFileName(old.File) != old.File) throw new InvalidOperationException("Invalid installed mod receipt.");
            if (payload.Files.Any(x => x.File == old.File)) continue;
            var oldFile = Path.Combine(paths.Game, "mods", old.File);
            if (!File.Exists(oldFile)) continue;
            using var input = File.OpenRead(oldFile);
            var hash = Convert.ToHexString(SHA256.HashData(input)); input.Close();
            if (!hash.Equals(old.Sha256, StringComparison.OrdinalIgnoreCase)) throw new InvalidOperationException("An old Entity-managed mod was modified; it was preserved. Move it out of the mods folder before upgrading.");
            var backup = Path.Combine(paths.Root, "backups", "mods", payload.Version); Directory.CreateDirectory(backup);
            File.Move(oldFile, Path.Combine(backup, old.File), true);
        }
        foreach (var file in payload.Files.Where(x => x.Role is "client" or "fabric-api" or "baritone"))
        {
            var source = sources[file.File];
            var overrideJar = Path.Combine(paths.Root, "baritone-override.jar");
            if (file.Role == "baritone" && File.Exists(overrideJar))
            {
                source = overrideJar;
                report("Using your explicitly supplied modified Baritone library. Interface compatibility is your responsibility.");
            }
            var destination = Path.Combine(paths.Game, "mods", file.File);
            File.Copy(source, destination, true);
            installed.Add(file with { Sha256 = Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(destination))).ToLowerInvariant() });
        }
        AppPaths.AtomicWrite(receiptFile, JsonSerializer.Serialize(installed, AppPaths.Json));
        Directory.CreateDirectory(paths.Runtime);
        report("Entity client files are ready.");
    }
    public async Task StartAsync(AppSettings settings, string? bridgeToken = null, bool connect = true, CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        using var existing = ProcessCustody.Open(Path.Combine(paths.Root, "client-process.json"));
        if (owned is { HasExited: false } || existing != null) throw new InvalidOperationException("Entity is already running.");
        settings.Validate();
        using var metadataHttp = new HttpClient(new SetupMetadataHandler(cancellationToken));
        var launcher = new MinecraftLauncher(MinecraftLauncherParameters.CreateDefault(new MinecraftPath(paths.Game), metadataHttp));
        var options = new MLaunchOption
        {
            Session = MSession.CreateOfflineSession(settings.BotName),
            MaximumRamMb = settings.ClientMemoryMb,
            ScreenWidth = 960, ScreenHeight = 540,
            GameLauncherName = "Entity", GameLauncherVersion = "2.22.2",
            ServerIp = connect ? settings.ServerHost : null,
            ServerPort = settings.ServerPort,
            ExtraJvmArguments = new MArgument[]
            {
                new("-Dentity2.runtimeMode=" + settings.WindowMode.ToLowerInvariant()),
                new("-Dentity2.backgroundInputIsolation=true")
            }
        };
        if (settings.JavaPath.Length != 0) options.JavaPath = settings.JavaPath;
        var process = await launcher.BuildProcessAsync(FabricProfile, options, cancellationToken);
        cancellationToken.ThrowIfCancellationRequested();
        // Defer launch-specific settings until cancellable metadata work completes.
        var stopFile = Path.Combine(paths.Runtime, "stop-request");
        if (File.Exists(stopFile)) File.Delete(stopFile);
        var statusFile = Path.Combine(paths.Runtime, "runtime-status.json");
        if (File.Exists(statusFile)) File.Delete(statusFile);
        AppPaths.AtomicWrite(Path.Combine(paths.Runtime, "runtime-mode"), settings.WindowMode.ToLowerInvariant());
        // Written before Fabric's static bootstrap; hidden operation is never just a Win32 hide.
        AppPaths.AtomicWrite(Path.Combine(paths.Runtime, "background-input-isolation"), "true");
        PrepareBotOptions(paths.Game);
        if (!string.IsNullOrWhiteSpace(bridgeToken))
        {
            var configFile = Path.Combine(paths.Runtime, "client.json");
            var config = File.Exists(configFile) ? JsonNode.Parse(File.ReadAllText(configFile))!.AsObject() : new JsonObject();
            var bridge = config["bridge"] as JsonObject ?? new JsonObject();
            bridge["host"] = "127.0.0.1"; bridge["port"] = settings.BridgePort; bridge["token"] = "";
            if (config["bridge"] == null) config["bridge"] = bridge;
            AppPaths.AtomicWrite(configFile, config.ToJsonString(AppPaths.Json));
        }
        process.StartInfo.CreateNoWindow = true;
        process.StartInfo.WindowStyle = ProcessWindowStyle.Hidden;
        process.StartInfo.UseShellExecute = false;
        process.StartInfo.RedirectStandardOutput = true;
        process.StartInfo.RedirectStandardError = true;
        if (!string.IsNullOrWhiteSpace(bridgeToken)) process.StartInfo.Environment["ENTITY_BRIDGE_TOKEN"] = bridgeToken;
        var logFile = Path.Combine(paths.Logs, "minecraft-" + DateTime.UtcNow.ToString("yyyyMMdd-HHmmss") + ".log");
        cancellationToken.ThrowIfCancellationRequested();
        writer = new StreamWriter(logFile) { AutoFlush = true };
        process.OutputDataReceived += (_, e) => WriteLog(e.Data);
        process.ErrorDataReceived += (_, e) => WriteLog(e.Data);
        if (!process.Start()) throw new InvalidOperationException("Minecraft did not start.");
        owned = process;
        try
        {
            process.BeginOutputReadLine(); process.BeginErrorReadLine();
            ProcessCustody.Save(process, Path.Combine(paths.Root, "client-process.json"));
            cancellationToken.ThrowIfCancellationRequested();
        }
        catch
        {
            // The Process object is the exact newly created child, even if saving
            // its custody receipt failed. Never leave that child orphaned.
            if (!process.HasExited) { process.Kill(false); await process.WaitForExitAsync(); }
            lock (this) { writer?.Dispose(); writer = null; }
            throw;
        }
        report("Entity is starting. " + logFile);
    }
    private void WriteLog(string? line)
    {
        if (line == null) return;
        // Do not retain launch arguments, environment variables or credentials.
        lock (this) { writer?.WriteLine(line); }
    }
    public static void PrepareBotOptions(string gameDirectory)
    {
        // This dedicated bot has no human to dismiss first-run onboarding. Minecraft
        // queues Quick Play behind that screen, including in hidden mode.
        var file = Path.Combine(gameDirectory, "options.txt");
        var lines = File.Exists(file) ? File.ReadAllLines(file).ToList() : new List<string>();
        lines.RemoveAll(line => line.StartsWith("onboardAccessibility:", StringComparison.Ordinal));
        lines.Add("onboardAccessibility:false");
        AppPaths.AtomicWrite(file, string.Join(Environment.NewLine, lines) + Environment.NewLine);
    }
    public JsonElement? Status()
    {
        var file = Path.Combine(paths.Runtime, "runtime-status.json");
        if (!File.Exists(file)) return null;
        try { using var document = JsonDocument.Parse(File.ReadAllText(file)); return document.RootElement.Clone(); }
        catch (Exception error) when (error is IOException or JsonException) { return null; }
    }
    public async Task StopAsync()
    {
        using var process = ProcessCustody.Open(Path.Combine(paths.Root, "client-process.json"));
        if (process == null) return;
        AppPaths.AtomicWrite(Path.Combine(paths.Runtime, "stop-request"), "stop");
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(20));
        try { await process.WaitForExitAsync(timeout.Token); }
        catch (OperationCanceledException)
        {
            using var verified = ProcessCustody.Open(Path.Combine(paths.Root, "client-process.json"));
            if (verified != null) verified.Kill(false);
            await process.WaitForExitAsync();
        }
        if (File.Exists(Path.Combine(paths.Runtime, "stop-request"))) File.Delete(Path.Combine(paths.Runtime, "stop-request"));
        lock (this) { writer?.Dispose(); writer = null; }
        report("Entity stopped.");
    }
}
