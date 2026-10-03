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
    private readonly CheckBox cloud = new() { Name = "CloudConsent", AutoSize = true, MaximumSize = new Size(700, 0), Text = "Allow sending bot chat and game context to my selected HTTPS cloud provider" };
    private readonly TextBox key = new() { Name = "AiKey", UseSystemPasswordChar = true, PlaceholderText = "Optional for local; required for cloud" };
    private readonly Button removeKey = new() { Name = "RemoveAiKey", Text = "Remove saved key", AutoSize = true };
    private readonly Label serverHint = Hint("");
    private readonly Label aiHint = Hint("");
    private readonly Label headline = new() { Name = "RuntimeState", AutoSize = true, MaximumSize = new Size(880, 0), Text = "Checking local process state…", Font = new Font("Segoe UI", 11, FontStyle.Bold) };
    private readonly TextBox activity = new() { Name = "ActivityLog", Multiline = true, ReadOnly = true, ScrollBars = ScrollBars.Vertical, Dock = DockStyle.Fill, BackColor = SystemColors.Window };
    private readonly Button start = new() { Name = "StartEntity", Text = "Start Entity", AutoSize = true, Enabled = false };
    private readonly Button stop = new() { Name = "StopEntity", Text = "Stop", AutoSize = true, Enabled = false };
    private readonly Button save = new() { Name = "SaveSettings", Text = "Save and open dashboard", AutoSize = true };
    private readonly Button import = new() { Name = "ImportSchematic", Text = "Import schematic…", AutoSize = true };
    private readonly ProgressBar progress = new() { Dock = DockStyle.Fill, Style = ProgressBarStyle.Marquee, MarqueeAnimationSpeed = 0, Visible = false, Height = 8 };
    private readonly Panel settingsPanel = new() { Dock = DockStyle.Fill, AutoScroll = true };
    private readonly Panel dashboard = new() { Name = "Dashboard", Dock = DockStyle.Fill, AutoScroll = true };
    private readonly Panel wizard = new() { Name = "SetupWizard", Dock = DockStyle.Fill };
    private readonly Panel pageHost = new() { Dock = DockStyle.Fill, AutoScroll = true };
    private readonly TableLayoutPanel[] pages = new TableLayoutPanel[3];
    private readonly Label wizardTitle = new() { AutoSize = true, Font = new Font("Segoe UI", 22, FontStyle.Bold) };
    private readonly Label wizardSubtitle = Hint("");
    private readonly Label steps = Hint("");
    private readonly Button next = new() { Name = "SetupNext", Text = "Continue", AutoSize = true };
    private readonly Button back = new() { Name = "SetupBack", Text = "Back", AutoSize = true };
    private readonly Button settingsButton = new() { Name = "OpenSettings", Text = "Settings", AutoSize = true };
    private readonly Button returnToDashboard = new() { Name = "ReturnToDashboard", Text = "Back to dashboard", AutoSize = true };
    private readonly Button cancelSetup = new() { Name = "CancelSetup", Text = "Cancel setup", AutoSize = true, Visible = false };
    private readonly Label progressText = Hint("");
    private readonly Label welcome = new() { Name = "Welcome", AutoSize = true, Font = new Font("Segoe UI", 22, FontStyle.Bold) };
    private readonly Label address = new() { Name = "JoinAddress", AutoSize = true, Font = new Font("Consolas", 20, FontStyle.Bold), ForeColor = Color.FromArgb(79, 57, 177) };
    private readonly Label serverState = StateLabel("ServerState");
    private readonly Label botState = StateLabel("BotState");
    private readonly Label aiState = StateLabel("AiState");
    private readonly Label serverDetail = Hint("");
    private readonly Label botDetail = Hint("");
    private readonly Label aiDetail = Hint("");
    private TableLayoutPanel serverAdvanced = null!, externalFields = null!, folderFields = null!;
    private FlowLayoutPanel eulaRow = null!;
    private readonly LinkLabel advancedToggle = new() { Name = "AdvancedSettings", AutoSize = true, Text = "Advanced server settings" };
    private int setupStep;
    private bool advancedOpen, setupCompleted, loadFailed;
    private CancellationTokenSource? setupCancellation;
    private TaskCompletionSource? operationFinished;
    private readonly System.Windows.Forms.Timer poll = new() { Interval = 2000 };
    private bool busy, polling, initialCheck, clientAlive, ownedServerAlive, keyChanged, loading, allowClose, closing;
    private string? credentialError;
    private string? lastError;
    private string redactionKey = "";

    public MainForm(AppPaths paths, string payloadDirectory)
    {
        this.paths = paths; secrets = new SecretStore(paths);
        Text = "Entity"; Name = "EntityMainForm";
        StartPosition = FormStartPosition.CenterScreen;
        Size = new Size(1020, 820); MinimumSize = new Size(840, 700);
        Font = new Font("Segoe UI", 10); AutoScaleMode = AutoScaleMode.Dpi;
        BackColor = Color.FromArgb(245, 246, 250); ForeColor = Color.FromArgb(33, 38, 55);
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
        save.Click += (_, _) => CompleteSetup();
        next.Click += (_, _) => { try { ValidateStep(setupStep); ShowStep(setupStep + 1); } catch (Exception error) { ShowError(error); } };
        back.Click += (_, _) => ShowStep(setupStep - 1);
        settingsButton.Click += (_, _) => ShowSetup();
        returnToDashboard.Click += (_, _) => { LoadChoices(); UpdateChoices(); ShowDashboard(); };
        cancelSetup.Click += (_, _) => CancelStartup();
        advancedToggle.LinkClicked += (_, _) => { advancedOpen = !advancedOpen; UpdateChoices(); };
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
        if (setupCompleted) ShowDashboard(); else ShowSetup();
    }

    private static ComboBox Choice(string name, params string[] choices)
    {
        var control = new ComboBox { Name = name, DropDownStyle = ComboBoxStyle.DropDownList, Dock = DockStyle.Fill };
        control.Items.AddRange(choices); control.SelectedIndex = 0; return control;
    }
    private static NumericUpDown Port(string name, int value) => new() { Name = name, Minimum = 1, Maximum = 65535, Value = value, Width = 100 };
    private static Label Hint(string text) => new() { AutoSize = true, Text = text, ForeColor = Color.FromArgb(98, 105, 124), MaximumSize = new Size(760, 0), Margin = new Padding(0, 5, 0, 12) };
    private static Label StateLabel(string name) => new() { Name = name, Text = "Checking", AutoSize = true, Font = new Font("Segoe UI", 16, FontStyle.Bold), Margin = new Padding(0, 8, 0, 2) };
    private static FlowLayoutPanel Flow(params Control[] controls)
    {
        var flow = new FlowLayoutPanel { AutoSize = true, Dock = DockStyle.Fill, WrapContents = true, Margin = new Padding(0) };
        flow.Controls.AddRange(controls); return flow;
    }
    private static TableLayoutPanel Fields(string title)
    {
        var table = new TableLayoutPanel { AutoSize = true, Dock = DockStyle.Top, ColumnCount = 2, RowCount = 1, Padding = new Padding(20), BackColor = Color.White, Margin = new Padding(0, 0, 0, 16) };
        table.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 145)); table.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        if (title.Length == 0) { table.RowCount = 0; return table; }
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
        var root = new TableLayoutPanel { Name = "EntityRoot", Dock = DockStyle.Fill, ColumnCount = 1, RowCount = 3, Padding = new Padding(28, 20, 28, 16) };
        root.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize)); root.RowStyles.Add(new RowStyle(SizeType.Percent, 100)); root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        var brand = Flow(new Label { Text = "Entity", AutoSize = true, Font = new Font("Segoe UI", 19, FontStyle.Bold), ForeColor = Color.FromArgb(79, 57, 177), Margin = new Padding(0, 0, 14, 0) },
            new Label { Text = "MINECRAFT JAVA 1.21.8", AutoSize = true, Font = new Font("Segoe UI", 9, FontStyle.Bold), ForeColor = Color.FromArgb(98, 105, 124), Margin = new Padding(0, 13, 0, 18) });
        var header = new TableLayoutPanel { AutoSize = true, Dock = DockStyle.Fill, ColumnCount = 2 };
        header.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100)); header.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        header.Controls.Add(brand, 0, 0); header.Controls.Add(settingsButton, 1, 0);
        root.Controls.Add(header, 0, 0);
        var surface = new Panel { Dock = DockStyle.Fill };
        surface.Controls.Add(dashboard); surface.Controls.Add(wizard); root.Controls.Add(surface, 0, 1);
        root.Controls.Add(Hint("Minecraft Java 1.21.8  ·  Independent project, not an official Minecraft product  ·  Your own Minecraft installation stays separate"), 0, 2);
        BuildWizard(); BuildDashboard(); Controls.Add(root);
        foreach (var button in new[] { start, stop, save, next, back, settingsButton, returnToDashboard, cancelSetup, import }) StyleButton(button, button == start || button == next || button == save);
    }

    private static void StyleButton(Button button, bool primary = false)
    {
        button.FlatStyle = FlatStyle.Flat; button.FlatAppearance.BorderSize = primary ? 0 : 1;
        button.FlatAppearance.BorderColor = Color.FromArgb(217, 220, 230);
        button.BackColor = primary ? Color.FromArgb(92, 65, 194) : Color.White;
        button.ForeColor = primary ? Color.White : Color.FromArgb(43, 48, 65);
        button.Padding = new Padding(12, 7, 12, 7); button.Margin = new Padding(0, 4, 10, 4);
        button.Cursor = Cursors.Hand;
    }

    private void BuildWizard()
    {
        var layout = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 1, RowCount = 5 };
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        for (var i = 0; i < 5; i++) layout.RowStyles.Add(new RowStyle(i == 3 ? SizeType.Percent : SizeType.AutoSize, i == 3 ? 100 : 0));
        layout.Controls.Add(steps, 0, 0); layout.Controls.Add(wizardTitle, 0, 1); layout.Controls.Add(wizardSubtitle, 0, 2);
        layout.Controls.Add(settingsPanel, 0, 3); settingsPanel.Controls.Add(pageHost);
        layout.Controls.Add(Flow(back, next, save, returnToDashboard), 0, 4); wizard.Controls.Add(layout);
        var player = pages[0] = Fields("Player identity"); player.Name = "PlayerSetup";
        AddWide(player, Hint("Use the exact player name shown in Minecraft Java. Entity uses it to recognize its owner. No account password or email is needed."));
        AddField(player, "Your player name", owner);
        AddWide(player, Hint("The bot's name is Entity. Join the local server from your own Minecraft client."));
        var local = pages[1] = Fields("Choose your local world server"); local.Name = "ServerSetup";
        AddField(local, "Server", serverMode); AddWide(local, serverHint);
        var eulaLink = new LinkLabel { Text = "Read Minecraft EULA", AutoSize = true, Margin = new Padding(0, 8, 0, 8) };
        eulaLink.LinkClicked += (_, _) => OpenLocation("https://www.minecraft.net/en-us/eula");
        eulaRow = Flow(eula, eulaLink); AddWide(local, eulaRow);
        AddWide(local, advancedToggle);
        folderFields = Fields(""); folderFields.Padding = Padding.Empty;
        var browse = new Button { Name = "BrowseServer", Text = "Browse…", AutoSize = true }; StyleButton(browse); browse.Click += (_, _) => BrowseServer();
        var folderRow = new TableLayoutPanel { AutoSize = true, Dock = DockStyle.Fill, ColumnCount = 2 };
        folderRow.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100)); folderRow.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        folderRow.Controls.Add(serverFolder, 0, 0); serverFolder.Dock = DockStyle.Fill; folderRow.Controls.Add(browse, 1, 0);
        AddField(folderFields, "Server folder", folderRow); AddWide(local, folderFields);
        serverAdvanced = Fields("Advanced server settings"); serverAdvanced.Name = "ServerAdvanced"; serverAdvanced.Padding = Padding.Empty;
        AddField(serverAdvanced, "Minecraft port", gamePort); AddField(serverAdvanced, "Control bridge port", bridgePort);
        AddWide(serverAdvanced, Hint("Keep the defaults unless another local server uses them. Existing servers must already match these ports and your owner name; Entity does not migrate them."));
        AddWide(local, serverAdvanced);
        var ai = pages[2] = Fields("Window and optional AI"); ai.Name = "OptionalSetup";
        AddField(ai, "Bot game window", mode);
        AddWide(ai, Hint("Background keeps the bot's window hidden and silent. Visible shows the bot's game window. Neither mode takes over your mouse or keyboard."));
        AddField(ai, "Optional AI", aiMode); AddWide(ai, aiHint);
        externalFields = Fields("Your AI provider"); externalFields.Name = "ExternalAiFields"; externalFields.Padding = Padding.Empty;
        AddField(externalFields, "API base URL", endpoint); AddField(externalFields, "Model ID", model);
        var keyRow = new TableLayoutPanel { AutoSize = true, Dock = DockStyle.Fill, ColumnCount = 2 };
        keyRow.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100)); keyRow.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        keyRow.Controls.Add(key, 0, 0); key.Dock = DockStyle.Fill; keyRow.Controls.Add(removeKey, 1, 0);
        AddField(externalFields, "API key", keyRow); AddWide(externalFields, cloud);
        AddWide(externalFields, Hint("Keys are encrypted for this Windows user. Cloud needs explicit consent and HTTPS. An existing server must receive its key in its own environment."));
        AddWide(ai, externalFields);
        AddWide(ai, Hint("Saving these choices does not start Minecraft or download a model. You can change them later in Settings."));
        foreach (var page in pages) { page.Visible = false; pageHost.Controls.Add(page); }
    }

    private void BuildDashboard()
    {
        var content = new TableLayoutPanel { AutoSize = true, Dock = DockStyle.Top, ColumnCount = 1 };
        content.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        content.Controls.Add(welcome); content.Controls.Add(Hint("Start or stop the bot, check status, and join the local server."));
        var cards = new TableLayoutPanel { AutoSize = true, Dock = DockStyle.Top, ColumnCount = 3, Margin = new Padding(0, 4, 0, 16) };
        for (var i = 0; i < 3; i++) cards.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 33.333f));
        cards.Controls.Add(StatusCard("SERVER", serverState, serverDetail), 0, 0);
        cards.Controls.Add(StatusCard("BOT", botState, botDetail), 1, 0);
        cards.Controls.Add(StatusCard("AI", aiState, aiDetail), 2, 0); content.Controls.Add(cards);
        var actions = Fields(""); actions.Padding = new Padding(20, 12, 20, 14);
        AddWide(actions, headline); AddWide(actions, Flow(start, stop, cancelSetup)); AddWide(actions, progressText); AddWide(actions, progress); content.Controls.Add(actions);
        var join = Fields("Join the server"); join.Name = "JoinInstructions";
        var copy = new Button { Name = "CopyAddress", Text = "Copy address", AutoSize = true }; StyleButton(copy);
        copy.Click += (_, _) => { try { Clipboard.SetText(address.Text); copy.Text = "Copied"; } catch (Exception error) { ShowError(error); } };
        AddWide(join, Flow(address, copy));
        AddWide(join, new Label { AutoSize = true, MaximumSize = new Size(760, 0), Text = "1. Open your Minecraft Java 1.21.8 client.\n2. Choose Multiplayer > Direct Connection.\n3. Paste this address, then select Join Server.", Margin = new Padding(0, 12, 0, 8) });
        AddWide(join, Hint("Wait for Server: Ready. In the world, /e help shows available commands. AI is optional; its card reports readiness separately.")); content.Controls.Add(join);
        var logs = new Button { Text = "Logs", AutoSize = true }; logs.Click += (_, _) => OpenLocation(paths.Logs); StyleButton(logs);
        var help = new Button { Text = "Help", AutoSize = true }; help.Click += (_, _) => ShowHelp(); StyleButton(help);
        var data = new Button { Text = "Data folder", AutoSize = true }; data.Click += (_, _) => OpenLocation(paths.Root); StyleButton(data);
        var notices = new Button { Text = "Licenses", AutoSize = true }; notices.Click += (_, _) => OpenLocation(Path.Combine(AppContext.BaseDirectory, "BINARY-NOTICES.md")); StyleButton(notices);
        content.Controls.Add(Flow(import, logs, help, data, notices));
        var details = new LinkLabel { Text = "Show activity", AutoSize = true, Margin = new Padding(0, 12, 0, 8) };
        activity.Visible = false; activity.Height = 150; activity.Dock = DockStyle.Top; activity.Font = new Font("Consolas", 9);
        details.LinkClicked += (_, _) => { activity.Visible = !activity.Visible; details.Text = activity.Visible ? "Hide activity" : "Show activity"; };
        content.Controls.Add(details); content.Controls.Add(activity); dashboard.Controls.Add(content);
    }

    private static Control StatusCard(string title, Label state, Label detail)
    {
        var card = new TableLayoutPanel { AutoSize = true, Dock = DockStyle.Fill, ColumnCount = 1, BackColor = Color.White, Padding = new Padding(18, 14, 18, 14), Margin = new Padding(0, 0, 10, 0), MinimumSize = new Size(0, 134) };
        card.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        detail.MaximumSize = new Size(240, 0); state.MaximumSize = new Size(250, 0);
        card.Controls.Add(new Label { Text = title, AutoSize = true, Font = new Font("Segoe UI", 9, FontStyle.Bold), ForeColor = Color.FromArgb(112, 117, 136), Margin = Padding.Empty });
        card.Controls.Add(state); card.Controls.Add(detail); return card;
    }

    private void ShowStep(int step)
    {
        setupStep = Math.Clamp(step, 0, 2);
        for (var i = 0; i < pages.Length; i++) pages[i].Visible = i == setupStep;
        pages[setupStep].BringToFront(); pageHost.AutoScrollPosition = Point.Empty;
        steps.Text = $"STEP {setupStep + 1} OF 3     •     Player  /  World  /  Preferences";
        wizardTitle.Text = setupStep switch { 0 => "Set up Entity", 1 => "Server setup", _ => "Preferences" };
        wizardSubtitle.Text = setupStep switch { 0 => "Enter your player name. No account sign-in required.", 1 => "Create a private local server, or connect an existing one.", _ => "Choose the bot's window mode and optional AI provider." };
        back.Visible = setupStep > 0; next.Visible = setupStep < 2; save.Visible = setupStep == 2;
        returnToDashboard.Visible = setupCompleted; AcceptButton = setupStep == 2 ? save : next;
    }

    private void ShowSetup() { settingsButton.Visible = false; dashboard.Visible = false; wizard.Visible = true; wizard.BringToFront(); ShowStep(0); }
    private void ShowDashboard()
    {
        wizard.Visible = false; dashboard.Visible = true; dashboard.BringToFront(); AcceptButton = start;
        settingsButton.Visible = true;
        welcome.Text = "Entity dashboard";
        address.Text = saved.ServerHost + ":" + saved.ServerPort; UpdateButtons();
    }

    private void ValidateStep(int step)
    {
        if (!Regex.IsMatch(owner.Text.Trim(), "^[A-Za-z0-9_]{3,16}$")) throw new InvalidOperationException("Enter your Minecraft Java player name (3–16 letters, numbers or underscores), not an email address.");
        if (step < 1) return;
        if (serverMode.SelectedIndex == 0 && !eula.Checked) throw new InvalidOperationException("Read and accept the Minecraft EULA to create a local server.");
        if (serverMode.SelectedIndex == 1 && !Directory.Exists(serverFolder.Text.Trim())) throw new InvalidOperationException("Choose your existing local Paper server folder.");
        if (serverFolder.Text.Trim().Length != 0 && !Path.IsPathFullyQualified(serverFolder.Text.Trim())) throw new InvalidOperationException("Choose an absolute local server folder with Browse.");
        if (gamePort.Value == bridgePort.Value) throw new InvalidOperationException("Minecraft and the control bridge need different ports.");
    }

    private void CompleteSetup()
    {
        try
        {
            ValidateStep(2); SaveChoices(true); setupCompleted = true; lastError = null;
            Report("Settings saved. Start Entity when you're ready; no download or game was started by saving."); ShowDashboard();
        }
        catch (Exception error) { ShowError(error); }
    }

    private void LoadChoices()
    {
        loading = true;
        try { saved = paths.Load(); saved.Validate(); loadFailed = false; }
        catch (Exception error) { saved = new(); loadFailed = true; Report("Saved choices could not be loaded. The original file is preserved and will not be overwritten. " + SafeMessage(error)); }
        setupCompleted = !loadFailed && File.Exists(paths.SettingsFile) && (saved.FirstSetupCompleted || saved.OwnerName.Length >= 3);
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
    private AppSettings SaveChoices(bool completeSetup = false)
    {
        if (loadFailed) throw new InvalidOperationException("The saved settings file could not be read. It has not been reset or overwritten. Open the data folder to restore or correct settings.json before saving.");
        var settings = ReadChoices() with { FirstSetupCompleted = completeSetup || saved.FirstSetupCompleted }; paths.Save(settings);
        if (keyChanged) { secrets.Save(key.Text); keyChanged = false; credentialError = null; }
        saved = settings; return settings;
    }
    private void UpdateChoices()
    {
        var external = aiMode.SelectedIndex == 2;
        endpoint.Enabled = model.Enabled = cloud.Enabled = key.Enabled = removeKey.Enabled = external;
        externalFields.Visible = external;
        eula.Enabled = serverMode.SelectedIndex == 0;
        eulaRow.Visible = serverMode.SelectedIndex == 0;
        folderFields.Visible = advancedOpen || serverMode.SelectedIndex == 1;
        serverAdvanced.Visible = advancedOpen;
        advancedToggle.Text = advancedOpen ? "Hide advanced server settings" : "Advanced server settings";
        serverHint.Text = serverMode.SelectedIndex == 0 ? "Recommended for a new start. Entity creates a loopback-only survival server and manages Start / Stop. Your existing Minecraft worlds stay untouched."
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
        settingsPanel.Enabled = save.Enabled = next.Enabled = back.Enabled = settingsButton.Enabled = interactive && !running;
        start.Enabled = initialCheck && interactive && !running && owner.Text.Trim().Length >= 3;
        stop.Enabled = interactive && running; import.Enabled = interactive;
        cancelSetup.Visible = busy && setupCancellation != null;
        cancelSetup.Enabled = setupCancellation is { IsCancellationRequested: false } && !closing;
        progress.Visible = progressText.Visible = busy;
        if (!busy) progress.MarqueeAnimationSpeed = 0;
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
                var cancellation = setupCancellation!.Token;
                await client.PrepareAsync(settings, cancellation, setupProgress);
                await server.PrepareAsync(settings, client.ValidatePayload(), cancellation, setupProgress);
                await AiConfiguration.ConfigureAsync(settings, server.PluginData(settings), paths, Report, cancellation, setupProgress);
                var token = await server.StartAsync(settings, externalKey, cancellation);
                await client.StartAsync(settings, token, cancellationToken: cancellation);
                cancellation.ThrowIfCancellationRequested();
                Report("Entity was launched. Waiting for its actual game/bridge heartbeat; launch is not proof of AI readiness.");
            }
            catch
            {
                Exception? cleanupFailure = null;
                try { await client.StopAsync(); } catch (Exception cleanup) { cleanupFailure = cleanup; }
                try { await server.StopAsync(); } catch (Exception cleanup) { cleanupFailure ??= cleanup; }
                if (cleanupFailure != null) throw new InvalidOperationException("Startup cleanup needs attention: " + SafeMessage(cleanupFailure) + " Use Stop or inspect Logs; Paper has not been force-killed.");
                throw;
            }
        }), cancellable: true);
    }
    private async Task StopOwnedAsync()
    {
        await Task.Run(async () => { await client.StopAsync(); await server.StopAsync(); });
        await PollAsync();
        Report("Stop returned for Entity's owned processes. Existing servers remain under their owner's control.");
    }
    private IProgress<SetupProgress> setupProgress = null!;
    private async Task RunOperationAsync(string message, Func<Task> action, bool cancellable = false)
    {
        if (busy) return;
        busy = true; lastError = null; headline.Text = message; progressText.Text = message;
        progress.Style = ProgressBarStyle.Marquee; progress.MarqueeAnimationSpeed = 25;
        setupCancellation = cancellable ? new CancellationTokenSource() : null;
        operationFinished = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var finished = operationFinished;
        setupProgress = new Progress<SetupProgress>(value => { if (ReferenceEquals(operationFinished, finished)) UpdateProgress(value); });
        Report(message); UpdateButtons();
        try { await action(); }
        catch (OperationCanceledException) when (setupCancellation?.IsCancellationRequested == true)
        { Report("Setup cancelled. Verified downloads are kept for the next attempt; owned processes have been asked to stop safely."); }
        catch (Exception error) { ShowError(error); }
        finally
        {
            setupCancellation?.Dispose(); setupCancellation = null; busy = false;
            try { await PollAsync(); UpdateButtons(); } finally { finished.TrySetResult(); }
        }
    }

    private void CancelStartup()
    {
        if (setupCancellation is not { IsCancellationRequested: false }) return;
        setupCancellation.Cancel(); cancelSetup.Enabled = false;
        headline.Text = "Cancelling setup safely…"; progressText.Text = "Finishing the current cleanup. Paper is allowed to save; no force-kill.";
        Report("Cancellation requested. Waiting for transfers and owned-process cleanup.");
    }

    private void UpdateProgress(SetupProgress value)
    {
        if (!busy || IsDisposed || setupCancellation?.IsCancellationRequested == true) return;
        headline.Text = value.Stage;
        if (value.TotalBytes is > 0)
        {
            progress.Style = ProgressBarStyle.Continuous;
            progress.Value = (int)Math.Clamp(100d * value.BytesReceived / value.TotalBytes.Value, 0, 100);
            progressText.Text = $"{value.Stage}  ·  {value.BytesReceived / 1048576d:0.0} / {value.TotalBytes.Value / 1048576d:0.0} MB  ({progress.Value}%)";
        }
        else
        {
            progress.Style = ProgressBarStyle.Marquee; progress.MarqueeAnimationSpeed = 25;
            progressText.Text = value.BytesReceived > 0 ? $"{value.Stage}  ·  {value.BytesReceived / 1048576d:0.0} MB received" : value.Stage;
        }
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
                var ai = ReadAiObservation(saved, paper?.Id);
                var serverReady = ai is { State: not "Stopped" } && ServerRuntime.PortInUse(saved.ServerPort);
                return (game: game != null, server: paper != null, pid: game?.Id, status: client.Status(), ai, serverReady);
            });
            if (IsDisposed) return;
            clientAlive = state.game; ownedServerAlive = state.server; initialCheck = true;
            var bot = DashboardState.Bot(state.status, state.pid, DateTimeOffset.UtcNow);
            SetState(serverState, serverDetail,
                bot.Fresh && bot.Paired || state.serverReady ? "Ready" : ownedServerAlive ? "Starting" : saved.ManageServer ? "Stopped" : "Not connected",
                bot.Fresh && bot.Paired || state.serverReady ? "Local Paper server is responding." : ownedServerAlive ? "Waiting for a verified server heartbeat." : saved.ManageServer ? "Start Entity to open your local world." : "Start your existing Paper server yourself.");
            SetState(botState, botDetail, !clientAlive ? "Stopped" : !bot.Fresh ? "Starting" : bot.InGame && bot.Paired ? "In game" : bot.InGame ? "Pairing" : "Starting",
                !clientAlive ? "The bot is not running." : !bot.Fresh ? "Waiting for a fresh game heartbeat." : bot.InGame && bot.Paired ? "Entity is in the world and paired." : bot.InGame ? "Waiting for the control bridge." : "Minecraft is loading or at its menu.");
            if (state.ai is { } observedAi && bot.Fresh && bot.Paired)
                SetState(aiState, aiDetail, observedAi.State, observedAi.Detail);
            else SetState(aiState, aiDetail, saved.AiMode == "Off" ? "Off" : "Not verified",
                saved.AiMode == "Off" ? "AI is disabled in saved preferences." : "Waiting for a fresh paired-server AI report.");
            if (!busy)
            {
                headline.Text = lastError != null ? "Needs attention — " + lastError : bot.Fresh && bot.InGame && bot.Paired ? "Entity is in the world. You're ready to join."
                    : clientAlive ? "Entity is starting…" : ownedServerAlive ? "The local server is still running." : "Stopped. Select Start Entity to begin.";
            }
            UpdateButtons();
        }
        catch (Exception error)
        {
            initialCheck = false; headline.Text = "Local process identity could not be verified. No process was stopped.";
            SetState(serverState, serverDetail, "Not verified", "Open Logs to inspect local process state.");
            SetState(botState, botDetail, "Not verified", "No unrelated process was stopped.");
            SetState(aiState, aiDetail, "Not verified", "Runtime readiness could not be confirmed.");
            Report(SafeMessage(error)); UpdateButtons();
        }
        finally { polling = false; }
    }

    private static void SetState(Label state, Label detail, string title, string description)
    {
        state.Text = title; detail.Text = description;
        state.ForeColor = title is "Ready" or "In game" ? Color.FromArgb(20, 126, 91) : title is "Needs attention" or "Not verified" ? Color.FromArgb(151, 93, 20) : Color.FromArgb(43, 48, 65);
    }

    private (string State, string Detail)? ReadAiObservation(AppSettings settings, int? ownedServerPid)
    {
        try
        {
            if (settings.ManageServer && ownedServerPid == null) return null;
            var pluginData = server.PluginData(settings);
            var receipt = Path.Combine(pluginData, "ai-status.json");
            if (!File.Exists(receipt) || new FileInfo(receipt).Length > 16_384) return null;
            var config = File.ReadAllText(Path.Combine(pluginData, "config.yml"));
            var pairedOwner = Regex.Match(config, "(?m)^owner-name:\\s*\"?([^\"\\r\\n]+)\"?\\s*$").Groups[1].Value.Trim();
            var pairedPort = Regex.Match(config, "(?m)^  port:\\s*(\\d+)\\s*$").Groups[1].Value;
            if (!pairedOwner.Equals(settings.OwnerName, StringComparison.OrdinalIgnoreCase) || pairedPort != settings.BridgePort.ToString()) return null;
            var properties = File.ReadAllText(Path.Combine(server.DirectoryFor(settings), "server.properties"));
            if (!Regex.IsMatch(properties, "(?m)^server-port=" + settings.ServerPort + "\\s*$")) return null;
            var json = File.ReadAllText(receipt);
            var parsed = DashboardState.Ai(json, ownedServerPid, DateTimeOffset.UtcNow);
            if (parsed == null) return null;
            // Attached servers are never adopted or controlled. Check only that the receipt's process still exists and predates this observation.
            if (!settings.ManageServer)
            {
                using var document = JsonDocument.Parse(json);
                using var process = Process.GetProcessById(document.RootElement.GetProperty("processId").GetInt32());
                var observed = DateTimeOffset.Parse(document.RootElement.GetProperty("observedAtUtc").GetString()!);
                if (process.HasExited || process.StartTime.ToUniversalTime() > observed.UtcDateTime || !process.ProcessName.StartsWith("java", StringComparison.OrdinalIgnoreCase)) return null;
            }
            return parsed;
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or JsonException or InvalidOperationException or ArgumentException or System.ComponentModel.Win32Exception) { return null; }
    }

    private async void OnClosing(object? sender, FormClosingEventArgs e)
    {
        if (allowClose) return;
        e.Cancel = true;
        if (closing) return;
        closing = true; UpdateButtons();
        try
        {
            if (busy)
            {
                if (setupCancellation != null)
                {
                    if (MessageBox.Show(this, "Cancel setup and close after safe cleanup? Completed verified downloads will remain for next time. Paper will be allowed to save.",
                        "Cancel setup and close", MessageBoxButtons.OKCancel, MessageBoxIcon.Question) != DialogResult.OK) return;
                    CancelStartup();
                }
                headline.Text = "Waiting for safe cleanup before closing…";
                if (operationFinished != null) await operationFinished.Task;
            }
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
        "1. Enter your Minecraft Java player name. The bot's name is Entity.\n" +
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
