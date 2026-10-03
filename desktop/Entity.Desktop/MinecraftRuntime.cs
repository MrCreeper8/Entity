using CmlLib.Core;
using CmlLib.Core.Auth;
using CmlLib.Core.ModLoaders.FabricMC;
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
    public async Task PrepareAsync(AppSettings settings)
    {
        using var active = ProcessCustody.Open(Path.Combine(paths.Root, "client-process.json"));
        if (active != null) throw new InvalidOperationException("Stop Entity before preparing or upgrading its files.");
        settings.Validate();
        var payload = ValidatePayload();
        var minecraftPath = new MinecraftPath(paths.Game);
        var launcher = new MinecraftLauncher(minecraftPath);
        var last = DateTime.MinValue;
        launcher.FileProgressChanged += (_, progress) =>
        {
            if ((DateTime.UtcNow - last).TotalSeconds < 1) return;
            last = DateTime.UtcNow;
            report($"Installing Minecraft: {progress.ProgressedTasks}/{progress.TotalTasks} files");
        };
        report("Preparing Minecraft 1.21.8 and Java 21 (first launch downloads required files)...");
        await launcher.InstallAsync(MinecraftVersion);
        var installer = new FabricInstaller(new HttpClient());
        await installer.Install(MinecraftVersion, FabricVersion, minecraftPath);
        await launcher.InstallAsync(FabricProfile);
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
            var source = Path.Combine(payloadDirectory, file.File);
            if (file.DownloadUrl != null)
            {
                var cache = Path.Combine(paths.Root, "downloads"); Directory.CreateDirectory(cache); source = Path.Combine(cache, file.File);
                bool Verified() => File.Exists(source) && Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(source))).Equals(file.Sha256, StringComparison.OrdinalIgnoreCase);
                if (!Verified())
                {
                    report("Downloading verified " + file.Role + " from its publisher...");
                    using var http = new HttpClient { Timeout = TimeSpan.FromMinutes(5) };
                    await using (var output = File.Create(source + ".partial"))
                        await (await http.GetAsync(file.DownloadUrl)).Content.CopyToAsync(output);
                    File.Move(source + ".partial", source, true);
                    if (!Verified()) throw new InvalidOperationException("Downloaded dependency failed its publisher checksum: " + file.Role);
                }
            }
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
    public async Task StartAsync(AppSettings settings, string? bridgeToken = null, bool connect = true)
    {
        using var existing = ProcessCustody.Open(Path.Combine(paths.Root, "client-process.json"));
        if (owned is { HasExited: false } || existing != null) throw new InvalidOperationException("Entity is already running.");
        settings.Validate();
        var stopFile = Path.Combine(paths.Runtime, "stop-request");
        if (File.Exists(stopFile)) File.Delete(stopFile);
        var statusFile = Path.Combine(paths.Runtime, "runtime-status.json");
        if (File.Exists(statusFile)) File.Delete(statusFile);
        AppPaths.AtomicWrite(Path.Combine(paths.Runtime, "runtime-mode"), settings.WindowMode.ToLowerInvariant());
        // Written before Fabric's static bootstrap; hidden operation is never just a Win32 hide.
        AppPaths.AtomicWrite(Path.Combine(paths.Runtime, "background-input-isolation"), "true");
        if (!string.IsNullOrWhiteSpace(bridgeToken))
        {
            var configFile = Path.Combine(paths.Runtime, "client.json");
            var config = File.Exists(configFile) ? JsonNode.Parse(File.ReadAllText(configFile))!.AsObject() : new JsonObject();
            var bridge = config["bridge"] as JsonObject ?? new JsonObject();
            bridge["host"] = "127.0.0.1"; bridge["port"] = settings.BridgePort; bridge["token"] = "";
            if (config["bridge"] == null) config["bridge"] = bridge;
            AppPaths.AtomicWrite(configFile, config.ToJsonString(AppPaths.Json));
        }
        var launcher = new MinecraftLauncher(new MinecraftPath(paths.Game));
        var options = new MLaunchOption
        {
            Session = MSession.CreateOfflineSession(settings.BotName),
            MaximumRamMb = settings.ClientMemoryMb,
            ScreenWidth = 960, ScreenHeight = 540,
            GameLauncherName = "Entity", GameLauncherVersion = "2.22.0",
            ServerIp = connect ? settings.ServerHost : null,
            ServerPort = settings.ServerPort,
            ExtraJvmArguments = new MArgument[]
            {
                new("-Dentity2.runtimeMode=" + settings.WindowMode.ToLowerInvariant()),
                new("-Dentity2.backgroundInputIsolation=true")
            }
        };
        if (settings.JavaPath.Length != 0) options.JavaPath = settings.JavaPath;
        var process = await launcher.BuildProcessAsync(FabricProfile, options);
        process.StartInfo.CreateNoWindow = true;
        process.StartInfo.WindowStyle = ProcessWindowStyle.Hidden;
        process.StartInfo.UseShellExecute = false;
        process.StartInfo.RedirectStandardOutput = true;
        process.StartInfo.RedirectStandardError = true;
        if (!string.IsNullOrWhiteSpace(bridgeToken)) process.StartInfo.Environment["ENTITY_BRIDGE_TOKEN"] = bridgeToken;
        var logFile = Path.Combine(paths.Logs, "minecraft-" + DateTime.UtcNow.ToString("yyyyMMdd-HHmmss") + ".log");
        writer = new StreamWriter(logFile) { AutoFlush = true };
        process.OutputDataReceived += (_, e) => WriteLog(e.Data);
        process.ErrorDataReceived += (_, e) => WriteLog(e.Data);
        if (!process.Start()) throw new InvalidOperationException("Minecraft did not start.");
        owned = process;
        process.BeginOutputReadLine(); process.BeginErrorReadLine();
        ProcessCustody.Save(process, Path.Combine(paths.Root, "client-process.json"));
        report("Entity is starting. " + logFile);
    }
    private void WriteLog(string? line)
    {
        if (line == null) return;
        // Do not retain launch arguments, environment variables or credentials.
        lock (this) { writer?.WriteLine(line); }
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
