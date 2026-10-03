using CmlLib.Core;
using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Reflection;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace Entity.Desktop;

public sealed class ServerRuntime
{
    private const string PaperHash = "8de7c52c3b02403503d16fac58003f1efef7dd7a0256786843927fa92ee57f1e";
    private const string PaperUrl = "https://fill-data.papermc.io/v1/objects/" + PaperHash + "/paper-1.21.8-60.jar";
    private readonly AppPaths paths;
    private readonly string payloadDirectory;
    private readonly Action<string> report;
    private Process? process;
    private StreamWriter? writer;
    private TaskCompletionSource ready = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private string token = "";
    public ServerRuntime(AppPaths paths, string payloadDirectory, Action<string>? report = null)
    { this.paths = paths; this.payloadDirectory = payloadDirectory; this.report = report ?? (_ => { }); }
    public string DirectoryFor(AppSettings settings) => Path.GetFullPath(settings.ServerDirectory.Length == 0 ? paths.DefaultServer : settings.ServerDirectory);
    public string PluginData(AppSettings settings) => Path.Combine(DirectoryFor(settings), "plugins", "EntityBridge");
    public bool Running => process is { HasExited: false };
    public static bool PortInUse(int port) => System.Net.NetworkInformation.IPGlobalProperties.GetIPGlobalProperties().GetActiveTcpListeners().Any(x => x.Port == port);

    public async Task PrepareAsync(AppSettings settings, ProductPayload payload, CancellationToken cancellationToken = default, IProgress<SetupProgress>? progress = null)
    {
        cancellationToken.ThrowIfCancellationRequested();
        progress?.Report(new SetupProgress("Preparing local server"));
        settings.Validate();
        if (Running) throw new InvalidOperationException("Stop the managed server before changing its files or settings.");
        using var active = ProcessCustody.Open(Path.Combine(paths.Root, "server-process.json"));
        if (settings.ManageServer && active != null) throw new InvalidOperationException("The owned server is already running. Stop it before updating files.");
        if (settings.OwnerName.Length == 0) throw new InvalidOperationException("Enter your Minecraft name so only you can control Entity.");
        var root = DirectoryFor(settings);
        var properties = Path.Combine(root, "server.properties");
        if (!settings.ManageServer)
        {
            if (!File.Exists(properties)) throw new InvalidOperationException("Choose an existing Paper server folder.");
            var content = File.ReadAllText(properties);
            if (!Regex.IsMatch(content, "(?m)^online-mode=false\\s*$"))
                throw new InvalidOperationException("This release uses offline bot names. Your server requires account login; its settings were not changed.");
            var config = Path.Combine(PluginData(settings), "config.yml");
            if (!File.Exists(config)) throw new InvalidOperationException("Install the included EntityBridge plugin in this server, start it once, then select its folder.");
            token = ReadToken(config);
            ValidatePairing(settings, properties, config);
            report("Attached to the existing local server; authentication/world/configuration unchanged.");
            return;
        }
        if (!settings.EulaAccepted) throw new InvalidOperationException("Read and accept the Minecraft EULA before creating a server.");
        if (File.Exists(properties) && !File.Exists(Path.Combine(root, "entity-managed-server.json")))
            throw new InvalidOperationException("This folder already contains a server. Select Use existing server to preserve it.");
        Directory.CreateDirectory(root);
        var paper = Path.Combine(root, "paper-1.21.8-60.jar");
        report("Preparing verified Paper 1.21.8...");
        using var http = new HttpClient { Timeout = Timeout.InfiniteTimeSpan };
        http.DefaultRequestHeaders.UserAgent.ParseAdd("Entity/2.22.0 (https://github.com/MrCreeper8/Entity)");
        await SetupTransfer.DownloadAsync(http, PaperUrl, paper, "Paper 1.21.8", PaperHash, null, cancellationToken, progress);
        cancellationToken.ThrowIfCancellationRequested();
        progress?.Report(new SetupProgress("Installing verified server components"));
        Directory.CreateDirectory(Path.Combine(root, "plugins"));
        var file = payload.Files.Single(x => x.Role == "server-plugin");
        var destination = Path.Combine(root, "plugins", file.File);
        // Managed app-created server only. Keep old plugin versions outside Paper's load directory.
        foreach (var old in Directory.EnumerateFiles(Path.Combine(root, "plugins"), "EntityBridge-v2-*.jar"))
        {
            if (old == destination) continue;
            var backup = Path.Combine(paths.Root, "backups", "plugins", DateTime.UtcNow.ToString("yyyyMMdd-HHmmss"));
            Directory.CreateDirectory(backup);
            File.Copy(old, Path.Combine(backup, Path.GetFileName(old)), true);
            File.Delete(old);
        }
        File.Copy(Path.Combine(payloadDirectory, file.File), destination, true);
        Directory.CreateDirectory(PluginData(settings));
        var configPath = Path.Combine(PluginData(settings), "config.yml");
        if (!File.Exists(configPath))
        {
            using var input = Assembly.GetExecutingAssembly().GetManifestResourceStream("Entity.ServerDefaults")!;
            using var reader = new StreamReader(input);
            var defaults = await reader.ReadToEndAsync();
            token = Convert.ToHexString(RandomNumberGenerator.GetBytes(32)).ToLowerInvariant();
            defaults = defaults.Replace("owner-name: \"CHANGE_ME\"", "owner-name: \"" + settings.OwnerName + "\"")
                .Replace("auth-token: \"\"", "auth-token: \"" + token + "\"")
                .Replace("port: 8765", "port: " + settings.BridgePort);
            AppPaths.AtomicWrite(configPath, defaults);
        }
        else token = ReadToken(configPath);
        ValidatePairing(settings, properties, configPath);
        if (!File.Exists(properties))
            AppPaths.AtomicWrite(properties, $"server-ip=127.0.0.1\nserver-port={settings.ServerPort}\nonline-mode=false\nenforce-secure-profile=false\nspawn-protection=0\nview-distance=8\nsimulation-distance=6\nlevel-name=world\nmotd=Entity Survival\n");
        var propertyText = File.ReadAllText(properties);
        if (!Regex.IsMatch(propertyText, "(?m)^rcon.password=.{32,}\\r?$"))
        {
            // This is an app-created, explicitly offline-loopback server only.
            var listener = new TcpListener(IPAddress.Loopback, 0); listener.Start();
            var consolePort = ((IPEndPoint)listener.LocalEndpoint).Port; listener.Stop();
            foreach (var key in new[] { "enable-rcon", "rcon.port", "rcon.password", "broadcast-rcon-to-ops" })
                propertyText = Regex.Replace(propertyText, "(?m)^" + Regex.Escape(key) + "=.*(?:\\r?\\n|$)", "");
            propertyText += $"\nenable-rcon=true\nrcon.port={consolePort}\nrcon.password={Convert.ToHexString(RandomNumberGenerator.GetBytes(32)).ToLowerInvariant()}\nbroadcast-rcon-to-ops=false\n";
            AppPaths.AtomicWrite(properties, propertyText);
        }
        AppPaths.AtomicWrite(Path.Combine(root, "eula.txt"), "eula=true\n");
        AppPaths.AtomicWrite(Path.Combine(root, "entity-managed-server.json"), JsonSerializer.Serialize(new { version = payload.Version, createdBy = "Entity", authentication = "offline-loopback" }, AppPaths.Json));
        report("Local Paper server and Entity pairing are ready.");
    }
    private static void ValidatePairing(AppSettings settings, string properties, string config)
    {
        var yaml = File.ReadAllText(config);
        var owner = Regex.Match(yaml, "(?m)^owner-name:\\s*\"?([^\"\\r\\n]+)\"?\\s*$").Groups[1].Value.Trim();
        var port = Regex.Match(yaml, "(?m)^  port:\\s*(\\d+)\\s*$").Groups[1].Value;
        if (!owner.Equals(settings.OwnerName, StringComparison.OrdinalIgnoreCase) || port != settings.BridgePort.ToString())
            throw new InvalidOperationException("The selected server uses a different owner or bridge port. Match its settings; existing configuration was preserved.");
        if (File.Exists(properties) && !Regex.IsMatch(File.ReadAllText(properties), "(?m)^server-port=" + settings.ServerPort + "\\s*$"))
            throw new InvalidOperationException("The selected server uses a different Minecraft port. Match its settings; existing configuration was preserved.");
    }
    public static string ReadToken(string config)
    {
        var match = Regex.Match(File.ReadAllText(config), "(?m)^\\s*auth-token:\\s*[\"']?([^\"'\\s]+)[\"']?\\s*$");
        if (!match.Success || match.Groups[1].Value.Length < 16) throw new InvalidOperationException("The server's Entity pairing token is not configured.");
        return match.Groups[1].Value;
    }
    public async Task<string> StartAsync(AppSettings settings, string? apiKey, CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        if (!settings.ManageServer) return token;
        if (Running) return token;
        ready = new(TaskCreationOptions.RunContinuationsAsynchronously);
        if (PortInUse(settings.ServerPort) || PortInUse(settings.BridgePort))
            throw new InvalidOperationException("A server is already using the selected port. Use existing server or select unused ports.");
        using var metadataHttp = new HttpClient(new SetupMetadataHandler(cancellationToken));
        var launcher = new MinecraftLauncher(MinecraftLauncherParameters.CreateDefault(new MinecraftPath(paths.Game), metadataHttp));
        var java = settings.JavaPath.Length == 0 ? launcher.GetJavaPath(await launcher.GetVersionAsync(MinecraftRuntime.MinecraftVersion, cancellationToken)) : settings.JavaPath;
        if (java == null) throw new InvalidOperationException("Prepare the Minecraft runtime before starting Paper.");
        var info = new ProcessStartInfo(java) { WorkingDirectory = DirectoryFor(settings), CreateNoWindow = true, WindowStyle = ProcessWindowStyle.Hidden,
            UseShellExecute = false, RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true };
        foreach (var arg in new[] { "-Xms512M", "-Xmx" + settings.ServerMemoryMb + "M", "-jar", "paper-1.21.8-60.jar", "--nogui" }) info.ArgumentList.Add(arg);
        if (!string.IsNullOrWhiteSpace(apiKey)) info.Environment["ENTITY_AI_API_KEY"] = apiKey;
        var logFile = Path.Combine(paths.Logs, "paper-" + DateTime.UtcNow.ToString("yyyyMMdd-HHmmss") + ".log");
        cancellationToken.ThrowIfCancellationRequested();
        writer = new StreamWriter(logFile) { AutoFlush = true };
        process = new Process { StartInfo = info, EnableRaisingEvents = true };
        void Line(string? line)
        {
            if (line == null) return;
            if (token.Length != 0) line = line.Replace(token, "[pairing token redacted]");
            lock (this) writer?.WriteLine(line);
            if (line.Contains("Done (") && line.Contains("For help")) ready.TrySetResult();
        }
        process.OutputDataReceived += (_, e) => Line(e.Data); process.ErrorDataReceived += (_, e) => Line(e.Data);
        process.Exited += (_, _) => ready.TrySetException(new InvalidOperationException("Paper exited before becoming ready. Check its saved log."));
        if (!process.Start()) throw new InvalidOperationException("Paper could not start.");
        try
        {
            process.BeginOutputReadLine(); process.BeginErrorReadLine();
            ProcessCustody.Save(process, Path.Combine(paths.Root, "server-process.json"));
            report("Starting local survival server...");
            await ready.Task.WaitAsync(TimeSpan.FromMinutes(3), cancellationToken);
            await process.StandardInput.WriteLineAsync(("op " + settings.OwnerName).AsMemory(), cancellationToken);
            await process.StandardInput.FlushAsync(cancellationToken);
            cancellationToken.ThrowIfCancellationRequested();
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested) { await StopAsync(); throw; }
        catch (Exception error) { await StopAsync(); throw new InvalidOperationException("Paper did not become ready. Its saved log contains the reason.", error); }
        report("Server ready. Join " + settings.ServerHost + ":" + settings.ServerPort + " from Minecraft Java 1.21.8.");
        return token;
    }
    public async Task StopAsync()
    {
        // An in-memory child is already owned, even if its receipt could not be saved.
        // Recovered children still require the complete custody identity check.
        using var recovered = process is { HasExited: false } ? null : ProcessCustody.Open(Path.Combine(paths.Root, "server-process.json"));
        var verified = process is { HasExited: false } ? process : recovered;
        if (verified == null) { lock (this) { writer?.Dispose(); writer = null; } return; }
        if (process is { HasExited: false })
        { await process.StandardInput.WriteLineAsync("stop"); await process.StandardInput.FlushAsync(); }
        else await SendConsoleAsync("stop");
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(45));
        try { await verified.WaitForExitAsync(timeout.Token); }
        catch (OperationCanceledException) { throw new InvalidOperationException("Paper is still saving. It was not force-killed; check the saved server log."); }
        lock (this) { writer?.Dispose(); writer = null; }
        report("Server saved and stopped.");
    }
    public async Task<string> SendConsoleAsync(string command)
    {
        using var verified = ProcessCustody.Open(Path.Combine(paths.Root, "server-process.json"));
        if (verified == null) throw new InvalidOperationException("The owned server is not running.");
        var root = DirectoryFor(paths.Load());
        if (!File.Exists(Path.Combine(root, "entity-managed-server.json"))) throw new InvalidOperationException("No owned-server configuration was changed.");
        var properties = File.ReadAllText(Path.Combine(root, "server.properties"));
        var port = int.Parse(Regex.Match(properties, "(?m)^rcon.port=(\\d+)\\r?$").Groups[1].Value);
        var password = Regex.Match(properties, "(?m)^rcon.password=([^\\r\\n]+)\\r?$").Groups[1].Value;
        return await LocalServerConsole.SendAsync(port, password, command);
    }
}
