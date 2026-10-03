using System.Security.Cryptography;
using System.Text.Json;

namespace Entity.Desktop;

internal static class SetupTransfer
{
    private sealed record CacheReceipt(string Url, long Bytes, string Sha256);
    public static async Task<bool> VerifiedAsync(string path, string? hash, long? size, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        if (!File.Exists(path) || (size.HasValue && new FileInfo(path).Length != size.Value)) return false;
        // Without a publisher hash, an old/legacy file cannot be trusted as a verified cache.
        if (string.IsNullOrEmpty(hash)) return false;
        await using var input = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 65536, true);
        var actual = hash.Length switch
        {
            40 => await SHA1.HashDataAsync(input, cancellationToken),
            64 => await SHA256.HashDataAsync(input, cancellationToken),
            _ => throw new InvalidOperationException("Invalid download checksum.")
        };
        return Convert.ToHexString(actual).Equals(hash, StringComparison.OrdinalIgnoreCase);
    }

    public static async Task DownloadAsync(HttpClient http, string url, string destination, string stage,
        string? expectedHash, long? expectedSize, CancellationToken cancellationToken = default,
        IProgress<SetupProgress>? progress = null, TimeSpan? timeout = null, Func<string, Task>? validate = null)
    {
        cancellationToken.ThrowIfCancellationRequested();
        progress?.Report(new SetupProgress("Checking " + stage));
        if (await VerifiedAsync(destination, expectedHash, expectedSize, cancellationToken)) return;
        var receiptPath = destination + ".entity-integrity.json";
        if (string.IsNullOrEmpty(expectedHash) && File.Exists(receiptPath))
        {
            try
            {
                var receipt = JsonSerializer.Deserialize<CacheReceipt>(await File.ReadAllTextAsync(receiptPath, cancellationToken));
                if (receipt != null && receipt.Url == url && (!expectedSize.HasValue || receipt.Bytes == expectedSize) &&
                    await VerifiedAsync(destination, receipt.Sha256, receipt.Bytes, cancellationToken)) return;
            }
            catch (Exception error) when (error is IOException or JsonException or InvalidOperationException) { }
        }
        Directory.CreateDirectory(Path.GetDirectoryName(destination)!);
        var partial = destination + ".partial-" + Guid.NewGuid().ToString("N");
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        deadline.CancelAfter(timeout ?? TimeSpan.FromMinutes(5));
        var token = deadline.Token;
        try
        {
            using var response = await http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, token);
            response.EnsureSuccessStatusCode();
            var announced = response.Content.Headers.ContentLength;
            if (expectedSize.HasValue && announced.HasValue && expectedSize != announced)
                throw new InvalidDataException(stage + " has an unexpected download size.");
            var total = expectedSize ?? announced;
            long received = 0;
            progress?.Report(new SetupProgress(stage, 0, total));
            await using (var input = await response.Content.ReadAsStreamAsync(token))
            await using (var output = new FileStream(partial, FileMode.CreateNew, FileAccess.Write, FileShare.None, 65536, true))
            {
                var buffer = new byte[65536];
                var lastReport = Environment.TickCount64;
                while (true)
                {
                    var count = await input.ReadAsync(buffer, token);
                    if (count == 0) break;
                    received += count;
                    if (total.HasValue && received > total.Value)
                        throw new InvalidDataException(stage + " exceeded its expected download size.");
                    await output.WriteAsync(buffer.AsMemory(0, count), token);
                    if (Environment.TickCount64 - lastReport >= 100)
                    {
                        progress?.Report(new SetupProgress(stage, received, total));
                        lastReport = Environment.TickCount64;
                    }
                }
                await output.FlushAsync(token);
            }
            if ((total.HasValue && received != total.Value) || received == 0)
                throw new InvalidDataException(stage + " download was incomplete.");
            progress?.Report(new SetupProgress("Verifying " + stage, received, total));
            if (!string.IsNullOrEmpty(expectedHash) && !await VerifiedAsync(partial, expectedHash, expectedSize, token))
                throw new InvalidDataException(stage + " did not match its publisher checksum. Nothing was installed.");
            if (validate != null) await validate(partial);
            if (string.IsNullOrEmpty(expectedHash))
            {
                // Some Fabric Maven entries have no publisher checksum. Preserve a
                // digest of the complete HTTPS response, never an unverified legacy partial.
                await using var verified = new FileStream(partial, FileMode.Open, FileAccess.Read, FileShare.Read, 65536, true);
                var digest = Convert.ToHexString(await SHA256.HashDataAsync(verified, token));
                await File.WriteAllTextAsync(partial + ".receipt", JsonSerializer.Serialize(new CacheReceipt(url, received, digest)), token);
            }
            token.ThrowIfCancellationRequested();
            // Only a complete, validated file replaces an existing cache entry.
            File.Move(partial, destination, true);
            if (File.Exists(partial + ".receipt")) File.Move(partial + ".receipt", receiptPath, true);
            progress?.Report(new SetupProgress(stage, received, total));
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            throw new TimeoutException(stage + " download timed out. Retry to continue with verified cached files.");
        }
        finally
        {
            if (File.Exists(partial)) File.Delete(partial);
            if (File.Exists(partial + ".receipt")) File.Delete(partial + ".receipt");
        }
    }
}
