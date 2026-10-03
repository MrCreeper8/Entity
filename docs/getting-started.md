# Install Entity and join your world

## 1. Download and install

**[Download the Windows installer](https://github.com/MrCreeper8/Entity/releases/download/v2.22.2/Entity-Setup-2.22.2-win-x64.exe).**
Open it and click **Install Entity**. No administrator access, Prism launcher,
separate Java or separate .NET installation is needed. The app opens afterwards;
next time, open **Entity** from Start.

The filename ends in **.exe**. Other GitHub Release files are optional manual or
developer downloads. The installer is unsigned; only use the official download
and do not disable antivirus or Windows security protections.

## 2. Follow the setup

![Entity setup](images/setup.png)

- **Your player name:** your exact Minecraft Java name, not an email. This
  identifies who controls Entity. The bot itself is named Entity.
- **Server:** choose a new local server for the simplest setup. Read and accept
  the Minecraft EULA before creation. An existing server must be compatible local
  Paper 1.21.8 with EntityBridge already configured. Its world and authentication
  are not silently replaced.
- **AI:** Off starts without a model download. Managed Local uses the optional
  NVIDIA/CUDA pack; External uses your chosen compatible provider. Cloud use needs
  explicit consent and may have provider charges.
- **Window:** Background keeps the bot silent and out of the way. Visible lets
  you watch it. This choice is independent of AI.

Existing settings are kept. Use Settings on the dashboard to review them.
Advanced ports and folders are not required for a new server.

## 3. Start Entity, then join from Minecraft

![Entity dashboard](images/dashboard.png)

Start Entity from the dashboard. First setup downloads required files; follow
the displayed progress. You can cancel preparation. Verified downloads can be
reused, and cancellation does not delete your world or settings.

Server, Bot and AI states are separate. Configured does not mean a model is
already loaded. Do not wait for AI Ready if you selected Off.

Open your own **Minecraft Java Edition 1.21.8** and choose:

**Multiplayer → Direct Connection → paste the server address → Join Server**

Use **Copy address** in Entity. The default is `127.0.0.1:25565`, but the
dashboard reflects your actual configured port. No bot mods are needed in your
human client. Keep the Entity app open while the bot is running.

After joining, try `/e come` and `/e stop`. `/e help` lists existing commands.
AI is optional, not required for normal commands.

## If something needs attention

- **Not in the world yet:** read Server and Bot state separately. A running
  process is not necessarily an in-world bot.
- **Port already used:** another server may already be running. Attach it if
  compatible, or stop your own conflicting server first.
- **AI configured/loading/failed:** follow its reported state. Normal commands
  remain available. Put API keys only in the app's protected key field.
- **Existing server mismatch:** match its current owner/port settings. Never
  weaken authentication or replace its world as a shortcut.
- **Import schematic:** this queues library validation, not construction.

Logs are available in the app. Do not publish raw logs, chats, worlds, passwords,
pairing tokens or API keys when asking for help.
