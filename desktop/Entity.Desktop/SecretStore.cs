using System.Security.Cryptography;
using System.Text;

namespace Entity.Desktop;

public sealed class SecretStore
{
    private readonly string file;
    public SecretStore(AppPaths paths) { file = Path.Combine(paths.Root, "ai-key.dpapi"); }
    public void Save(string key)
    {
        if (key.Length == 0) { if (File.Exists(file)) File.Delete(file); return; }
        File.WriteAllBytes(file, ProtectedData.Protect(Encoding.UTF8.GetBytes(key), null, DataProtectionScope.CurrentUser));
    }
    public string? Load() => !File.Exists(file) ? null : Encoding.UTF8.GetString(ProtectedData.Unprotect(File.ReadAllBytes(file), null, DataProtectionScope.CurrentUser));
}
