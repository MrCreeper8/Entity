using System.Security.Cryptography;
namespace Entity.Desktop;

public static class SchematicInbox
{
    public static string Queue(AppPaths paths, string selectedFile)
    {
        var extension = Path.GetExtension(selectedFile).ToLowerInvariant();
        if (extension is not (".schem" or ".litematic")) throw new InvalidOperationException("Use a Sponge .schem v1/v2 or Litematica .litematic v7 file. Legacy .schematic and ZIP files need conversion first.");
        var info = new FileInfo(selectedFile);
        if (!info.Exists || info.Length is < 2 or > 8388608) throw new InvalidOperationException("A schematic file must be between 2 bytes and 8 MiB.");
        using (var input = info.OpenRead()) if (input.ReadByte() != 0x1f || input.ReadByte() != 0x8b) throw new InvalidOperationException("This is not a compressed schematic file.");
        var name = Path.GetFileNameWithoutExtension(selectedFile);
        name = new string(name.Where(x => char.IsLetterOrDigit(x) || x is '-' or '_' or ' ').Take(60).ToArray()).Trim();
        if (name.Length == 0) name = "Imported design";
        var request = Convert.ToHexString(RandomNumberGenerator.GetBytes(8)).ToLowerInvariant();
        var folder = Path.Combine(paths.Runtime, "blueprint-file-inbox"); Directory.CreateDirectory(folder);
        var destination = Path.Combine(folder, request + "--" + name + extension);
        File.Copy(selectedFile, destination + ".partial"); File.Move(destination + ".partial", destination);
        return request;
    }
}
