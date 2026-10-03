using System.Net.Sockets;
using System.Text;

namespace Entity.Desktop;

/** Authenticated loopback-only Minecraft RCON for normal save/stop after app recovery. */
public static class LocalServerConsole
{
    public static async Task<string> SendAsync(int port, string password, string command)
    {
        if (port is < 1 or > 65535 || password.Length < 32 || command.Length > 1024 || command.Any(x => x < 32))
            throw new InvalidOperationException("Invalid local server console request.");
        using var deadline = new CancellationTokenSource(TimeSpan.FromSeconds(8));
        using var client = new TcpClient(); await client.ConnectAsync("127.0.0.1", port, deadline.Token);
        using var network = client.GetStream();
        async Task Write(int id, int type, string body)
        {
            var data = Encoding.UTF8.GetBytes(body); var packet = new byte[14 + data.Length];
            BitConverter.GetBytes(10 + data.Length).CopyTo(packet, 0); BitConverter.GetBytes(id).CopyTo(packet, 4); BitConverter.GetBytes(type).CopyTo(packet, 8);
            data.CopyTo(packet, 12); await network.WriteAsync(packet, deadline.Token);
        }
        async Task<(int Id, int Type, string Body)> Read()
        {
            var header = new byte[4]; await network.ReadExactlyAsync(header, deadline.Token);
            var length = BitConverter.ToInt32(header); if (length is < 10 or > 1048576) throw new InvalidOperationException("Invalid local server console reply.");
            var data = new byte[length]; await network.ReadExactlyAsync(data, deadline.Token);
            if (data[^1] != 0 || data[^2] != 0) throw new InvalidOperationException("Invalid local server console terminator.");
            return (BitConverter.ToInt32(data, 0), BitConverter.ToInt32(data, 4), Encoding.UTF8.GetString(data, 8, length - 10));
        }
        await Write(41, 3, password);
        bool authenticated = false;
        for (var attempt = 0; attempt < 3; attempt++)
        {
            var response = await Read();
            if (response.Id == -1) throw new InvalidOperationException("Local server console authentication failed.");
            if (response.Id == 41 && response.Type == 2) { authenticated = true; break; }
        }
        if (!authenticated) throw new InvalidOperationException("Local server did not acknowledge authentication.");
        await Write(42, 2, command);
        try { var result = await Read(); if (result.Id != 42) throw new InvalidOperationException("Local server command receipt mismatched."); return result.Body; }
        catch (EndOfStreamException) when (command == "stop") { return "Server is stopping."; }
    }
}
