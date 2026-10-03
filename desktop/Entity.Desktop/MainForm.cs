using System.Diagnostics;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace Entity.Desktop;

/// <summary>Consumer setup UI; runtime objects retain exclusive process custody.</summary>
public sealed class MainForm : Form
{
    private readonly AppPaths paths;
    private readonly SecretStore secrets;
    private readonly MinecraftRuntime client;
    private readonly ServerRuntime server;
    private AppSettings saved = new();
    private readonly TextBox owner = new() { Name = "OwnerName", MaxLength = 16, PlaceholderText = "Your Minecraft name, not your email" };
    private readonly ComboBox mode = Choice("WindowMode", "Background", "Visible");
    private readonly ComboBox serverMode = Choice("ServerMode", "Create / manage local Paper", "Use existing local Paper server");
    private readonly TextBox serverFolder = new() { Name = "ServerDirectory", PlaceholderText = "Leave blank for Entity's own local server" };
    private readonly NumericUpDown gamePort = Port("ServerPort", 25565);
    private readonly NumericUpDown bridgePort = Port("BridgePort", 8765);
    private readonly CheckBox eula = new() { Name = "EulaAccepted", AutoSize = true, Text = "I have read and accept the Minecraft EULA for this server" };
    private readonly ComboBox aiMode = Choice("AiMode", "Off — no model download", "Managed local — NVIDIA / CUDA, optional ~3 GB download", "External — endpoint and model below");
    private readonly TextBox endpoint = new() { Name = "AiBaseUrl", PlaceholderText = "http://127.0.0.1:11434/v1" };
    private readonly TextBox model = new() { Name = "AiModel", MaxLength = 160, PlaceholderText = "Exact model ID from your provider" };
    private readonly CheckBox cloud = new() { Name = "CloudConsent", AutoSize = true, MaximumSize = new Size(700, 0), Text = "Allow sending companion chat and game context to my selected HTTPS cloud provider" };
    private readonly TextBox key = new() { Name = "AiKey", UseSystemPasswordChar = true, PlaceholderText = "Optional for local; required for cloud" };
    private readonly Button removeKey = new() { Name = "RemoveAiKey", Text = "Remove saved key", AutoSize = true };
    private readonly Label serverHint = Hint("");
    private readonly Label aiHint = Hint("");
    private readonly Label headline = new() { Name = "RuntimeState", AutoSize = true, MaximumSize = new Size(720, 0), Text = "Checking local process state…", Font = new Font("Segoe UI", 11, FontStyle.Bold) };
    private readonly TextBox activity = new() { Name = "ActivityLog", Multiline = true, ReadOnly = true, ScrollBars = ScrollBars.Vertical, Dock = DockStyle.Fill, BackColor = SystemColors.Window };
    private readonly Button start = new() { Name = "StartEntity", Text = "Start Entity", AutoSize = true, Enabled = false };
    private readonly Button stop = new() { Name = "StopEntity", Text = "Stop", AutoSize = true, Enabled = false };
    private readonly Button save = new() { Name = "SaveSettings", Text = "Save settings", AutoSize = true };
    private readonly Button import = new() { Name = "ImportSchematic", Text = "Import schematic…", AutoSize = true };
    private readonly ProgressBar progress = new() { Dock = DockStyle.Fill, Style = ProgressBarStyle.Marquee, MarqueeAnimationSpeed = 0, Visible = false, Height = 8 };
    private readonly Panel settingsPanel = new() { Dock = DockStyle.Fill, AutoScroll = true };
    private readonly System.Windows.Forms.Timer poll = new() { Interval = 2000 };
    private bool busy, polling, initialCheck, clientAlive, ownedServerAlive, keyChanged, loading, allowClose, closing;
    private string? credentialError;
    private string? lastError;
    private string redactionKey = "";

    public MainForm(AppPaths paths, string payloadDirectory)
    {
        this.paths = paths; secrets = new SecretStore(paths);
        Text = "Entity — Minecraft companion"; Name = "EntityMainForm";
        StartPosition = FormStartPosition.CenterScreen;
        Size = new Size(940, 820); MinimumSize = new Size(780, 660);
        Font = new Font("Segoe UI", 10); AutoScaleMode = AutoScaleMode.Dpi;
        BuildLayout();
        client = new MinecraftRuntime(paths, payloadDirectory, Report);
        server = new ServerRuntime(paths, payloadDirectory, Report);
        LoadChoices();
        owner.TextChanged += (_, _) => UpdateButtons();
        serverMode.SelectedIndexChanged += (_, _) => UpdateChoices();
        aiMode.SelectedIndexChanged += (_, _) => UpdateChoices();
        key.TextChanged += (_, _) => { redactionKey = key.Text; if (!loading) keyChanged = true; };
        start.Click += async (_, _) => await StartEntityAsync();
        stop.Click += async (_, _) => await RunOperationAsync("Stopping Entity and saving its local server…", StopOwnedAsync);
        save.Click += (_, _) => { try { SaveChoices(); lastError = null; Report("Settings saved. Changes apply on the next start."); } catch (Exception error) { ShowError(error); } };
        import.Click += (_, _) => ImportSchematic();
        removeKey.Click += (_, _) =>
        {
            try { secrets.Save(""); key.Text = ""; keyChanged = false; credentialError = null; Report("Saved AI key removed from this Windows user account."); }
            catch (Exception error) { ShowError(error); }
        };
        poll.Tick += async (_, _) => await PollAsync();
        Shown += async (_, _) => { await PollAsync(); poll.Start(); };
        FormClosing += OnClosing; FormClosed += (_, _) => poll.Dispose();
        UpdateChoices();
    }

    private static ComboBox Choice(string name, params string[] choices)
    {
        var control = new ComboBox { Name = name, DropDownStyle = ComboBoxStyle.DropDownList, Dock = DockStyle.Fill };
        control.Items.AddRange(choices); control.SelectedIndex = 0; return control;
    }
    private static NumericUpDown Port(string name, int value) => new() { Name = name, Minimum = 1, Maximum = 65535, Value = value, Width = 100 };
    private static Label Hint(string text) => new() { AutoSize = true, Text = text, ForeColor = SystemColors.GrayText, MaximumSize = new Size(720, 0), Margin = new Padding(3, 4, 3, 8) };
    private static FlowLayoutPanel Flow(params Control[] controls)
    {
        var flow = new FlowLayoutPanel { AutoSize = true, Dock = DockStyle.Fill, WrapContents = true, Margin = new Padding(0) };
        flow.Controls.AddRange(controls); return flow;
    }
    private static TableLayoutPanel Fields(string title)
    {
        var table = new TableLayoutPanel { AutoSize = true, Dock = DockStyle.Top, ColumnCount = 2, RowCount = 1, Padding = new Padding(12) };
        table.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 145)); table.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        table.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        var heading = new Label { Text = title, AutoSize = true, Font = new Font("Segoe UI", 11, FontStyle.Bold), Margin = new Padding(0, 3, 0, 12) };
        table.Controls.Add(heading, 0, 0); table.SetColumnSpan(heading, 2); return table;
    }
    private static void AddField(TableLayoutPanel table, string label, Control control)
    {
        var row = table.RowCount++; table.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        table.Controls.Add(new Label { Text = label, AutoSize = true, Anchor = AnchorStyles.Left, Margin = new Padding(0, 7, 5, 7) }, 0, row);
        control.Dock = DockStyle.Fill; control.Margin = new Padding(3, 4, 3, 4); table.Controls.Add(control, 1, row);
    }
    private static void AddWide(TableLayoutPanel table, Control control)
    {
        var row = table.RowCount++; table.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        table.Controls.Add(control, 0, row); table.SetColumnSpan(control, 2);
    }
    private void BuildLayout()
    {
        var root = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 1, RowCount = 5, Padding = new Padding(18) };
        root.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize)); root.RowStyles.Add(new RowStyle(SizeType.Percent, 73));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize)); root.RowStyles.Add(new RowStyle(SizeType.AutoSize)); root.RowStyles.Add(new RowStyle(SizeType.Percent, 27));
        var header = new FlowLayoutPanel { AutoSize = true, Dock = DockStyle.Fill, FlowDirection = FlowDirection.TopDown, WrapContents = false };
        header.Controls.Add(new Label { Text = "Entity", AutoSize = true, Font = new Font("Segoe UI", 24, FontStyle.Bold), Margin = new Padding(0) });
        header.Controls.Add(Hint("Your Minecraft Java 1.21.8 companion • local data stays separate from your own Minecraft installation"));
        root.Controls.Add(header, 0, 0); root.Controls.Add(settingsPanel, 0, 1);
        var choices = new TableLayoutPanel { AutoSize = true, Dock = DockStyle.Top, ColumnCount = 1 };
        choices.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        var player = Fields("1  Player and presentation");
        AddField(player, "Your player name", owner);
        AddField(player, "Companion name", new Label { Text = "Entity  (fixed in this release)", AutoSize = true });
        AddField(player, "Game window", mode);
        AddWide(player, Hint("Background hides and silences the bot game window. Both modes isolate the bot's mouse and keyboard; AI is a separate choice."));
        var local = Fields("2  Local Paper server"); AddField(local, "Server choice", serverMode);
        var browse = new Button { Text = "Browse…", AutoSize = true }; browse.Click += (_, _) => BrowseServer();
        var folderRow = new TableLayoutPanel { AutoSize = true, Dock = DockStyle.Fill, ColumnCount = 2 };
        folderRow.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100)); folderRow.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        folderRow.Controls.Add(serverFolder, 0, 0); serverFolder.Dock = DockStyle.Fill; folderRow.Controls.Add(browse, 1, 0);
        AddField(local, "Server folder", folderRow);
        AddField(local, "Local ports", Flow(new Label { Text = "Minecraft", AutoSize = true }, gamePort, new Label { Text = "Control bridge", AutoSize = true }, bridgePort));
        var eulaLink = new LinkLabel { Text = "Read Minecraft EULA", AutoSize = true };
        eulaLink.LinkClicked += (_, _) => OpenLocation("https://www.minecraft.net/en-us/eula");
        AddWide(local, Flow(eula, eulaLink)); AddWide(local, serverHint);
        var ai = Fields("3  AI — optional"); AddField(ai, "AI choice", aiMode); AddWide(ai, aiHint);
        AddField(ai, "External API URL", endpoint); AddField(ai, "External model", model); AddWide(ai, cloud);
        var keyRow = new TableLayoutPanel { AutoSize = true, Dock = DockStyle.Fill, ColumnCount = 2 };
        keyRow.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100)); keyRow.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        keyRow.Controls.Add(key, 0, 0); key.Dock = DockStyle.Fill; keyRow.Controls.Add(removeKey, 1, 0);
        AddField(ai, "API key", keyRow);
        AddWide(ai, Hint("Keys are encrypted for this Windows user and passed only to the owned Paper process. No cloud fallback. Existing-server AI changes need a restart or /e ai on|off."));
        choices.Controls.Add(player, 0, 0); choices.Controls.Add(local, 0, 1); choices.Controls.Add(ai, 0, 2); settingsPanel.Controls.Add(choices);
        var data = new Button { Text = "Data folder", AutoSize = true }; data.Click += (_, _) => OpenLocation(paths.Root);
        var logs = new Button { Text = "Logs", AutoSize = true }; logs.Click += (_, _) => OpenLocation(paths.Logs);
        var help = new Button { Text = "Help", AutoSize = true }; help.Click += (_, _) => ShowHelp();
        var notices = new Button { Text = "Licenses", AutoSize = true }; notices.Click += (_, _) => OpenLocation(Path.Combine(AppContext.BaseDirectory, "BINARY-NOTICES.md"));
        root.Controls.Add(Flow(start, stop, save, import, data, logs, help, notices), 0, 2); root.Controls.Add(progress, 0, 3);
        var status = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 1, RowCount = 2, Padding = new Padding(0, 10, 0, 0) };
        status.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        status.RowStyles.Add(new RowStyle(SizeType.AutoSize)); status.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        status.Controls.Add(headline, 0, 0); status.Controls.Add(activity, 0, 1); root.Controls.Add(status, 0, 4); Controls.Add(root);
        AcceptButton = start;
    }

    private void LoadChoices()
    {
        loading = true;
        try { saved = paths.Load(); saved.Validate(); }
        catch (Exception error) { saved = new(); Report("Saved choices could not be loaded; the original file is preserved until you choose Save or Start. " + SafeMessage(error)); }
        owner.Text = saved.OwnerName; mode.SelectedIndex = saved.WindowMode == "Visible" ? 1 : 0;
        serverMode.SelectedIndex = saved.ManageServer ? 0 : 1; serverFolder.Text = saved.ServerDirectory;
        gamePort.Value = saved.ServerPort; bridgePort.Value = saved.BridgePort; eula.Checked = saved.EulaAccepted;
        aiMode.SelectedIndex = saved.AiMode switch { "Managed" => 1, "External" => 2, _ => 0 };
        endpoint.Text = saved.AiBaseUrl; model.Text = saved.AiModel; cloud.Checked = saved.CloudConsent;
        try { key.Text = secrets.Load() ?? ""; }
        catch (Exception) { credentialError = "The saved AI key could not be decrypted. Re-enter it or use Remove saved key; it has not been overwritten."; Report(credentialError); }
        loading = false; keyChanged = false; redactionKey = key.Text;
        Report("Save settings or Start stores your choices. Existing Minecraft worlds, graphics settings and account files are not migrated or deleted.");
    }
    private AppSettings ReadChoices()
    {
        var settings = saved with
        {
            OwnerName = owner.Text.Trim(), BotName = "Entity", WindowMode = mode.SelectedIndex == 1 ? "Visible" : "Background",
            ManageServer = serverMode.SelectedIndex == 0, ServerDirectory = serverFolder.Text.Trim(),
            ServerPort = (int)gamePort.Value, BridgePort = (int)bridgePort.Value, EulaAccepted = eula.Checked,
            AiMode = aiMode.SelectedIndex switch { 1 => "Managed", 2 => "External", _ => "Off" },
            AiBaseUrl = endpoint.Text.Trim(), AiModel = model.Text.Trim(), CloudConsent = cloud.Checked
        };
        settings.Validate();
        if (settings.ServerDirectory.Length != 0 && !Path.IsPathFullyQualified(settings.ServerDirectory))
            throw new InvalidOperationException("Choose an absolute local server folder with Browse.");
        if (settings.AiMode == "External")
        {
            if (!Uri.TryCreate(settings.AiBaseUrl, UriKind.Absolute, out var uri) || uri.Scheme is not ("http" or "https")
                    || uri.UserInfo.Length != 0 || uri.Query.Length != 0 || uri.Fragment.Length != 0)
                throw new InvalidOperationException("Enter an HTTP(S) API base URL with no password, query or fragment.");
            if (!uri.IsLoopback && (uri.Scheme != "https" || !settings.CloudConsent))
                throw new InvalidOperationException("Cloud AI needs HTTPS and your explicit cloud consent. No request has been sent.");
            if (!Regex.IsMatch(settings.AiModel, "^[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}$"))
                throw new InvalidOperationException("Enter the exact external model ID (1–160 characters).");
        }
        return settings;
    }
    private AppSettings SaveChoices()
    {
        var settings = ReadChoices(); paths.Save(settings);
        if (keyChanged) { secrets.Save(key.Text); keyChanged = false; credentialError = null; }
        saved = settings; return settings;
    }
    private void UpdateChoices()
    {
        var external = aiMode.SelectedIndex == 2;
        endpoint.Enabled = model.Enabled = cloud.Enabled = key.Enabled = removeKey.Enabled = external;
        eula.Enabled = serverMode.SelectedIndex == 0;
        serverHint.Text = serverMode.SelectedIndex == 0 ? "Entity controls only its own server. Default folder: " + paths.DefaultServer
            : "Select an existing local offline-name Paper server with EntityBridge already paired to you. You start/stop that server; its world and authentication are preserved.";
        aiHint.Text = aiMode.SelectedIndex switch
        {
            1 => "Optional managed inference uses NVIDIA/CUDA and downloads approximately 3 GB on first setup. Model readiness is checked by the server, not assumed by this window.",
            2 => "Use a compatible structured chat-completions endpoint. HTTP is loopback-only; cloud requires HTTPS, consent and a key. No model download by Entity.",
            _ => "No model/runtime download. Ordinary manual gameplay commands remain available; addressed AI conversation is disabled."
        };
        UpdateButtons();
    }
    private void UpdateButtons()
    {
        if (IsDisposed) return;
        var running = clientAlive || ownedServerAlive || server.Running;
        var interactive = !busy && !closing;
        settingsPanel.Enabled = save.Enabled = interactive && !running;
        start.Enabled = initialCheck && interactive && !running && owner.Text.Trim().Length >= 3;
        stop.Enabled = interactive && running; import.Enabled = interactive;
        progress.Visible = busy; progress.MarqueeAnimationSpeed = busy ? 25 : 0;
    }

    private async Task StartEntityAsync()
    {
        AppSettings settings;
        try
        {
            settings = ReadChoices();
            if (settings.OwnerName.Length == 0) throw new InvalidOperationException("Enter your Minecraft name so only you can control Entity.");
            if (settings.ManageServer && !settings.EulaAccepted) throw new InvalidOperationException("Read and accept the Minecraft EULA before creating a local server.");
            if (!settings.ManageServer && settings.ServerDirectory.Length == 0) throw new InvalidOperationException("Choose your existing Paper server folder.");
            if (settings.AiMode == "External" && !new Uri(settings.AiBaseUrl).IsLoopback && string.IsNullOrWhiteSpace(key.Text))
                throw new InvalidOperationException(credentialError ?? "Enter your provider's API key for cloud AI. It will not be stored in settings JSON.");
            if (settings.AiMode == "Managed" && !File.Exists(Path.Combine(server.PluginData(settings), "local-ai.json"))
                    && MessageBox.Show(this, "Managed local AI needs a compatible NVIDIA/CUDA GPU and an optional download of approximately 3 GB. Continue? Choose AI Off to use manual gameplay without this download.",
                        "Optional managed AI download", MessageBoxButtons.OKCancel, MessageBoxIcon.Information) != DialogResult.OK) return;
            settings = SaveChoices();
        }
        catch (Exception error) { ShowError(error); return; }
        var externalKey = settings.AiMode == "External" ? key.Text : null;
        await RunOperationAsync("Preparing Entity… first setup may download Minecraft, Java and Paper.", () => Task.Run(async () =>
        {
            using (var existing = ProcessCustody.Open(Path.Combine(paths.Root, "client-process.json")))
                if (existing != null) throw new InvalidOperationException("Entity is already running. Stop it before changing setup.");
            try
            {
                await client.PrepareAsync(settings);
                await server.PrepareAsync(settings, client.ValidatePayload());
                await AiConfiguration.ConfigureAsync(settings, server.PluginData(settings), paths, Report);
                var token = await server.StartAsync(settings, externalKey);
                await client.StartAsync(settings, token);
                Report("Entity was launched. Waiting for its actual game/bridge heartbeat; launch is not proof of AI readiness.");
            }
            catch
            {
                try { await client.StopAsync(); await server.StopAsync(); }
                catch (Exception cleanup) { Report("Startup cleanup needs attention: " + SafeMessage(cleanup)); }
                throw;
            }
        }));
    }
    private async Task StopOwnedAsync()
    {
        await Task.Run(async () => { await client.StopAsync(); await server.StopAsync(); });
        await PollAsync();
        Report("Stop returned for Entity's owned processes. Existing servers remain under their owner's control.");
    }
    private async Task RunOperationAsync(string message, Func<Task> action)
    {
        if (busy) return;
        busy = true; lastError = null; headline.Text = message; Report(message); UpdateButtons();
        try { await action(); } catch (Exception error) { ShowError(error); }
        finally { busy = false; await PollAsync(); UpdateButtons(); }
    }
    private async Task PollAsync()
    {
        if (polling || IsDisposed) return;
        polling = true;
        try
        {
            var state = await Task.Run(() =>
            {
                using var game = ProcessCustody.Open(Path.Combine(paths.Root, "client-process.json"));
                using var paper = ProcessCustody.Open(Path.Combine(paths.Root, "server-process.json"));
                return (game: game != null, server: paper != null, pid: game?.Id, status: client.Status());
            });
            if (IsDisposed) return;
            clientAlive = state.game; ownedServerAlive = state.server; initialCheck = true;
            if (!busy)
            {
                headline.Text = lastError != null ? "Needs attention — " + lastError : clientAlive ? "Entity process is running — waiting for a fresh heartbeat" : ownedServerAlive ? "Owned local server is still running" : "Stopped — ready to start";
                if (lastError == null && clientAlive && state.status is JsonElement heartbeat
                        && heartbeat.TryGetProperty("processId", out var pid) && pid.GetInt32() == state.pid
                        && heartbeat.TryGetProperty("heartbeatAtUtc", out var at) && DateTimeOffset.TryParse(at.GetString(), out var observed)
                        && DateTimeOffset.UtcNow - observed >= TimeSpan.Zero && DateTimeOffset.UtcNow - observed < TimeSpan.FromSeconds(10))
                {
                    var inGame = heartbeat.TryGetProperty("gameReady", out var game) && game.GetBoolean();
                    var connected = heartbeat.TryGetProperty("bridgeConnected", out var bridge) && bridge.GetBoolean();
                    headline.Text = inGame && connected ? "Entity is in game and paired — AI readiness: /e ai status"
                        : inGame ? "Entity is in game — waiting for the control bridge" : "Entity is starting / at the game menu";
                }
            }
            UpdateButtons();
        }
        catch (Exception error)
        {
            initialCheck = false; headline.Text = "Local process identity could not be verified. No process was stopped.";
            Report(SafeMessage(error)); UpdateButtons();
        }
        finally { polling = false; }
    }

    private async void OnClosing(object? sender, FormClosingEventArgs e)
    {
        if (allowClose) return;
        e.Cancel = true;
        if (closing) return;
        if (busy) { Report("Setup or shutdown is still in progress. Wait for it to finish before closing Entity."); return; }
        closing = true; UpdateButtons();
        try
        {
            await PollAsync();
            if (!initialCheck)
            {
                if (MessageBox.Show(this, "Process identity could not be verified. Close without stopping any process? Cancel keeps this window open to inspect Logs.",
                    "Entity", MessageBoxButtons.OKCancel, MessageBoxIcon.Warning) != DialogResult.OK) return;
            }
            else if (clientAlive || ownedServerAlive || server.Running)
            {
                if (MessageBox.Show(this, "Stop Entity and save its owned local server before closing? Cancel keeps this window and its processes open.",
                        "Stop and close Entity", MessageBoxButtons.OKCancel, MessageBoxIcon.Question) != DialogResult.OK) return;
                await RunOperationAsync("Stopping before closing…", StopOwnedAsync);
                if (clientAlive || ownedServerAlive || server.Running) return;
            }
            allowClose = true; poll.Stop(); Close();
        }
        catch (Exception error) { ShowError(error); } finally { closing = false; UpdateButtons(); }
    }
    private void BrowseServer()
    {
        using var picker = new FolderBrowserDialog { Description = "Choose the local Paper server folder (not your Minecraft client folder)", UseDescriptionForTitle = true };
        if (Directory.Exists(serverFolder.Text)) picker.SelectedPath = serverFolder.Text;
        if (picker.ShowDialog(this) == DialogResult.OK) serverFolder.Text = picker.SelectedPath;
    }
    private void ImportSchematic()
    {
        using var picker = new OpenFileDialog { Title = "Queue a schematic for Entity validation", Filter = "Supported schematics (*.schem;*.litematic)|*.schem;*.litematic", CheckFileExists = true, Multiselect = false };
        if (picker.ShowDialog(this) != DialogResult.OK) return;
        try
        {
            var request = SchematicInbox.Queue(paths, picker.FileName);
            Report("Schematic request " + request + " queued for validation by Entity. Run /e build list after the bot starts; import does not authorize building.");
        }
        catch (Exception error) { ShowError(error); }
    }
    private void OpenLocation(string location)
    {
        try { Process.Start(new ProcessStartInfo(location) { UseShellExecute = true }); } catch (Exception error) { ShowError(error); }
    }
    private void ShowHelp() => MessageBox.Show(this,
        "1. Enter your Minecraft Java player name. The companion name is Entity.\n" +
        "2. Create a local Paper server and explicitly accept the EULA, or attach a local offline-name server with EntityBridge already paired. You start/stop existing servers. Account sign-in is not available here; existing authentication is never weakened.\n" +
        "3. Choose Visible or silent Background independently of AI. Off downloads no model; Managed requires NVIDIA/CUDA; External needs your endpoint/model and explicit HTTPS cloud consent.\n" +
        "4. Start, then join 127.0.0.1:" + gamePort.Value + " in your Minecraft Java 1.21.8 client. Use /e help and /e ai status for actual AI state.\n\n" +
        "Existing-server AI preferences must match while it is running. To change them, stop that server first, apply settings here, then restart it or use /e ai on|off. A cloud key must exist in that server's process environment; this app cannot inject it into an already-running server.\n\n" +
        "Import schematic queues .schem/.litematic files for bot validation, never construction. Use Stop before closing. Save settings or Start stores choices; API keys are protected for this Windows user. Data/logs are separate from your own Minecraft installation. This is an independent project, not an official Minecraft product.",
        "Entity help", MessageBoxButtons.OK, MessageBoxIcon.Information);
    private string SafeMessage(Exception error)
    {
        var message = error.Message;
        if (redactionKey.Length != 0) message = message.Replace(redactionKey, "[key redacted]", StringComparison.Ordinal);
        return message;
    }
    private void ShowError(Exception error)
    {
        var message = SafeMessage(error); lastError = message; headline.Text = "Needs attention — " + message; Report("Needs attention: " + message);
        MessageBox.Show(this, message + "\n\nNo unrelated process was stopped. Saved runtime logs are available with Logs.", "Entity needs attention", MessageBoxButtons.OK, MessageBoxIcon.Warning);
    }
    private void Report(string message)
    {
        if (IsDisposed) return;
        if (InvokeRequired) { try { BeginInvoke(() => Report(message)); } catch (InvalidOperationException) { } return; }
        if (key.Text.Length != 0) message = message.Replace(key.Text, "[key redacted]", StringComparison.Ordinal);
        if (activity.TextLength > 50_000) activity.Text = activity.Text[^40_000..];
        activity.AppendText(DateTime.Now.ToString("HH:mm:ss") + "  " + message + Environment.NewLine);
    }
}
