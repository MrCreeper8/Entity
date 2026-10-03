using System.Diagnostics;
using System.Management;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace Entity.Desktop;

public sealed record ProcessReceipt(int ProcessId, long StartedTicks, string Image, string CommandHash);

public static class ProcessCustody
{
    private static string ReadCommandHash(int pid)
    {
        using var search = new ManagementObjectSearcher("SELECT CommandLine FROM Win32_Process WHERE ProcessId=" + pid);
        using var objects = search.Get();
        var command = objects.Cast<ManagementObject>().Select(x => x["CommandLine"] as string).SingleOrDefault();
        if (string.IsNullOrWhiteSpace(command)) throw new InvalidOperationException("Cannot verify the owned process command line.");
        return Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(command)));
    }
    public static void Save(Process process, string file)
    {
        string? image = null;
        // CreateProcess may return before the executable's module is available.
        // Bound only this startup race; an exited child is not a running receipt.
        for (var attempt = 0; attempt < 40 && image == null; attempt++)
        {
            process.Refresh();
            if (process.HasExited) throw new InvalidOperationException("The launched process exited before becoming ready. Check its saved log.");
            image = process.MainModule?.FileName;
            if (image == null) Thread.Sleep(50);
        }
        var receipt = new ProcessReceipt(process.Id, process.StartTime.ToUniversalTime().Ticks,
            image ?? throw new InvalidOperationException("Cannot verify process image."), ReadCommandHash(process.Id));
        AppPaths.AtomicWrite(file, JsonSerializer.Serialize(receipt, AppPaths.Json));
    }
    public static Process? Open(string file)
    {
        if (!File.Exists(file)) return null;
        var receipt = JsonSerializer.Deserialize<ProcessReceipt>(File.ReadAllText(file), AppPaths.Json)!;
        Process process;
        try { process = Process.GetProcessById(receipt.ProcessId); }
        catch (ArgumentException) { return null; }
        if (process.HasExited) return null;
        if (process.StartTime.ToUniversalTime().Ticks != receipt.StartedTicks ||
            !string.Equals(process.MainModule?.FileName, receipt.Image, StringComparison.OrdinalIgnoreCase) ||
            ReadCommandHash(process.Id) != receipt.CommandHash)
        { process.Dispose(); throw new InvalidOperationException("Process identity changed; no unrelated process was stopped."); }
        return process;
    }
}
