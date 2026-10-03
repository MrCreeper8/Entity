using System.IO.Compression;
using System.Security.Cryptography;
using System.Text.Json;
using System.Diagnostics;

namespace Entity.Setup;

public static class Installation
{
    public const string Version = "2.22.0";
    public static string DefaultRoot => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Entity", "app");
    private sealed record FilePin(string Path, string Sha256);
    private sealed record PackageManifest(string Version, string SourceCommit, FilePin[] Files);
    private static readonly JsonSerializerOptions Json = new() { PropertyNameCaseInsensitive = true, WriteIndented = true };
    public static async Task<string> InstallAsync(Stream archiveInput, string appRoot, Action<string> report, bool shortcuts = true)
    {
        var root = Path.GetFullPath(appRoot);
        for (var ancestor = new DirectoryInfo(root); ancestor != null; ancestor = ancestor.Parent)
            if (ancestor.Exists && ancestor.Attributes.HasFlag(FileAttributes.ReparsePoint)) throw new InvalidOperationException("Installation path cannot pass through a junction or symbolic link.");
        if (root == Path.GetPathRoot(root)) throw new InvalidOperationException("Cannot install at a drive root.");
        Directory.CreateDirectory(root);
        var stage = Path.Combine(root, ".install-" + Guid.NewGuid().ToString("N")); Directory.CreateDirectory(stage);
        var destination = Path.Combine(root, Version);
        if (Directory.Exists(destination)) throw new InvalidOperationException("This version is already installed. Its files were preserved; launch it or choose a different installation directory.");
        using var zip = new ZipArchive(archiveInput, ZipArchiveMode.Read, leaveOpen: true);
        foreach (var entry in zip.Entries)
        {
            var relative = entry.FullName;
            if (relative.Length == 0 || relative.Contains('\\') || relative.Contains(':') || relative.Split('/').Any(x => x is "." or "..")) throw new InvalidOperationException("Invalid installer archive path.");
            var target = Path.GetFullPath(Path.Combine(stage, relative.Replace('/', Path.DirectorySeparatorChar)));
            if (!target.StartsWith(stage + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)) throw new InvalidOperationException("Installer entry escaped the application directory.");
            if (relative.EndsWith('/')) { Directory.CreateDirectory(target); continue; }
            Directory.CreateDirectory(Path.GetDirectoryName(target)!);
            await using var output = File.Create(target); using var input = entry.Open(); await input.CopyToAsync(output);
        }
        var manifestFile = Path.Combine(stage, "PACKAGE.json");
        var manifest = JsonSerializer.Deserialize<PackageManifest>(File.ReadAllText(manifestFile), Json) ?? throw new InvalidOperationException("Package manifest missing.");
        if (manifest.Version != Version || manifest.SourceCommit.Length != 40) throw new InvalidOperationException("Package identity does not match the installer.");
        var pins = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var pin in manifest.Files)
        {
            var path = Path.GetFullPath(Path.Combine(stage, pin.Path));
            if (!path.StartsWith(stage + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase) || !pins.Add(pin.Path)) throw new InvalidOperationException("Invalid package manifest path.");
            using var stream = File.OpenRead(path);
            if (!Convert.ToHexString(SHA256.HashData(stream)).Equals(pin.Sha256, StringComparison.OrdinalIgnoreCase)) throw new InvalidOperationException("Package checksum failed: " + pin.Path);
        }
        var actual = Directory.GetFiles(stage, "*", SearchOption.AllDirectories).Select(x => Path.GetRelativePath(stage, x).Replace('\\', '/')).Where(x => x != "PACKAGE.json").ToHashSet(StringComparer.OrdinalIgnoreCase);
        if (!actual.SetEquals(pins)) throw new InvalidOperationException("Installer contains files not declared in its manifest.");
        if (!File.Exists(Path.Combine(stage, "Entity.exe"))) throw new InvalidOperationException("Entity application missing.");
        File.WriteAllText(Path.Combine(stage, "entity-install.json"), JsonSerializer.Serialize(new { version = Version, sourceCommit = manifest.SourceCommit, installedAtUtc = DateTime.UtcNow }, Json));
        Directory.Move(stage, destination);
        // Immutable version directories preserve rollback; user data is a separate sibling.
        if (shortcuts)
        {
            var shell = Type.GetTypeFromProgID("WScript.Shell") ?? throw new InvalidOperationException("Windows shortcut service unavailable.");
            dynamic automation = Activator.CreateInstance(shell)!;
            void Shortcut(string file)
            {
                dynamic link = automation.CreateShortcut(file); link.TargetPath = Path.Combine(destination, "Entity.exe");
                link.WorkingDirectory = destination; link.Description = "Entity Minecraft companion"; link.Save();
                System.Runtime.InteropServices.Marshal.FinalReleaseComObject(link);
            }
            var menu = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), "Entity"); Directory.CreateDirectory(menu);
            Shortcut(Path.Combine(menu, "Entity.lnk")); Shortcut(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), "Entity.lnk"));
            System.Runtime.InteropServices.Marshal.FinalReleaseComObject(automation);
        }
        report("Installed Entity " + Version + ". Your worlds, settings and AI files were not changed.");
        return destination;
    }
}
