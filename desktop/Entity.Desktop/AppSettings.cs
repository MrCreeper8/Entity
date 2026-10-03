using System.Text.Json;
using System.Text.RegularExpressions;

namespace Entity.Desktop;

public sealed record AppSettings
{
    public string BotName { get; init; } = "Entity";
    public string OwnerName { get; init; } = "";
    public string ServerHost { get; init; } = "127.0.0.1";
    public int ServerPort { get; init; } = 25565;
    public int BridgePort { get; init; } = 8765;
    public string WindowMode { get; init; } = "Background";
    public string AiMode { get; init; } = "Off";
    public string AiBaseUrl { get; init; } = "http://127.0.0.1:11434/v1";
    public string AiModel { get; init; } = "";
    public bool CloudConsent { get; init; }
    public bool EulaAccepted { get; init; }
    public bool ManageServer { get; init; } = true;
    public string ServerDirectory { get; init; } = "";
    public string JavaPath { get; init; } = "";
    public int ClientMemoryMb { get; init; } = 4096;
    public int ServerMemoryMb { get; init; } = 2048;

    public void Validate()
    {
        if (BotName != "Entity")
            throw new InvalidOperationException("This release uses the bot name Entity; other names are not supported yet.");
        if (OwnerName.Length != 0 && !Regex.IsMatch(OwnerName, "^[A-Za-z0-9_]{3,16}$"))
            throw new InvalidOperationException("Enter your Minecraft player name, not your email address.");
        if (ServerPort is < 1 or > 65535 || BridgePort is < 1 or > 65535 || ServerPort == BridgePort)
            throw new InvalidOperationException("Minecraft and control bridge need different valid ports.");
        if (!new[] { "Visible", "Background" }.Contains(WindowMode) ||
            !new[] { "Off", "Managed", "External" }.Contains(AiMode))
            throw new InvalidOperationException("Unsupported launch setting.");
        if (ClientMemoryMb is < 1024 or > 32768 || ServerMemoryMb is < 1024 or > 32768)
            throw new InvalidOperationException("Memory must be between 1024 and 32768 MB.");
        if (ServerHost is not ("127.0.0.1" or "localhost"))
            throw new InvalidOperationException("This release pairs with a local Paper server (127.0.0.1 or localhost).");
    }
}

public sealed class AppPaths
{
    public string Root { get; }
    public string Game => Path.Combine(Root, "minecraft");
    public string Logs => Path.Combine(Root, "logs");
    public string Runtime => Path.Combine(Game, "config", "entity2");
    public string DefaultServer => Path.Combine(Root, "server");
    public string SettingsFile => Path.Combine(Root, "settings.json");
    public AppPaths(string? root = null)
    {
        Root = Path.GetFullPath(root ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Entity", "data"));
        Directory.CreateDirectory(Root);
        Directory.CreateDirectory(Logs);
    }
    public static readonly JsonSerializerOptions Json = new() { WriteIndented = true, PropertyNameCaseInsensitive = true };
    public AppSettings Load() => File.Exists(SettingsFile)
        ? JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(SettingsFile), Json) ?? new() : new();
    public void Save(AppSettings settings)
    {
        settings.Validate();
        AtomicWrite(SettingsFile, JsonSerializer.Serialize(settings, Json));
    }
    public static void AtomicWrite(string file, string content)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(file)!);
        var temporary = file + ".tmp";
        File.WriteAllText(temporary, content, new System.Text.UTF8Encoding(false));
        File.Move(temporary, file, true);
    }
}
