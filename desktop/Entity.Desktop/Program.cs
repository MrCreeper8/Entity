using System.Security.Cryptography;
using System.Text;

namespace Entity.Desktop;

internal static class Program
{
    [STAThread]
    private static void Main()
    {
        ApplicationConfiguration.Initialize();
        try
        {
            var paths = new AppPaths();
            var identity = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(paths.Root.ToUpperInvariant())));
            using var instance = new Mutex(true, "Local\\Entity.Desktop." + identity, out var ownsInstance);
            if (!ownsInstance)
            {
                MessageBox.Show("Entity is already open for this local data folder. Use that window to start or stop your companion.",
                    "Entity", MessageBoxButtons.OK, MessageBoxIcon.Information);
                return;
            }
            try { Application.Run(new MainForm(paths, Path.Combine(AppContext.BaseDirectory, "payload"))); }
            finally { instance.ReleaseMutex(); }
        }
        catch (Exception)
        {
            MessageBox.Show("Entity could not open its local data folder or settings. Check folder permissions and reinstall if needed. Your Minecraft worlds were not removed.",
                "Entity could not open", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }
}
