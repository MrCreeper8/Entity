using System.Reflection;
using System.Diagnostics;
namespace Entity.Setup;

internal static class Program
{
    [STAThread]
    private static void Main()
    {
        ApplicationConfiguration.Initialize();
        var form = new Form { Text = "Install Entity 2.22.1", Width = 560, Height = 300, StartPosition = FormStartPosition.CenterScreen, MaximizeBox = false };
        var info = new Label { Dock = DockStyle.Top, Height = 145, Padding = new Padding(20), Text = "Install Entity for this Windows user. No administrator access needed.\n\nMinecraft, Java and Paper are downloaded on first launch. AI is optional and downloaded only when selected. No Prism launcher is required.\n\nExisting worlds and settings stay untouched. Original Entity code is all rights reserved, with personal download/install/play permission. Third-party licenses remain unchanged." };
        var status = new Label { Dock = DockStyle.Bottom, Height = 55, Padding = new Padding(12) };
        var install = new Button { Text = "Install Entity", Width = 160, Height = 36, Left = 185, Top = 155 };
        install.Click += async (_, _) =>
        {
            install.Enabled = false;
            try
            {
                using var bundle = Assembly.GetExecutingAssembly().GetManifestResourceStream("Entity.Bundle") ?? throw new InvalidOperationException("This installer does not contain the verified application package.");
                var destination = await Installation.InstallAsync(bundle, Installation.DefaultRoot, text => status.Text = text);
                install.Enabled = true;
                // Close the completed installer instead of rerunning extraction.
                Process.Start(new ProcessStartInfo(Path.Combine(destination, "Entity.exe")) { UseShellExecute = true }); form.Close();
            }
            catch (Exception error) { status.Text = error.Message; install.Enabled = true; }
        };
        form.FormClosing += (_, e) => { if (!install.Enabled) e.Cancel = true; };
        form.Controls.Add(install); form.Controls.Add(info); form.Controls.Add(status); Application.Run(form);
    }
}
