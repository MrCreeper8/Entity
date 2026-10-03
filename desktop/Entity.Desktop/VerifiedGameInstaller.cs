using CmlLib.Core;
using CmlLib.Core.Files;
using CmlLib.Core.Installers;

namespace Entity.Desktop;

// CmlLib 4.0.6 exposes IGameInstaller but its built-in body reads are not cancellable.
// Keep its file extraction/update tasks, using atomic, cancellable transfers for the files.
internal sealed class VerifiedGameInstaller(HttpClient http, IProgress<SetupProgress>? progress) : IGameInstaller
{
    public async ValueTask Install(IEnumerable<GameFile> gameFiles,
        IProgress<InstallerProgressChangedEventArgs>? fileProgress,
        IProgress<ByteProgress>? byteProgress, CancellationToken cancellationToken)
    {
        var files = gameFiles.Where(f => !string.IsNullOrEmpty(f.Path) && !string.IsNullOrEmpty(f.Url))
            .DistinctBy(f => f.Path, StringComparer.OrdinalIgnoreCase).ToArray();
        var completed = 0;
        // Parallel.ForEachAsync awaits all workers, including their cancellation cleanup.
        await Parallel.ForEachAsync(files, new ParallelOptions { MaxDegreeOfParallelism = 6, CancellationToken = cancellationToken },
            async (file, token) =>
            {
                token.ThrowIfCancellationRequested();
                var stage = "Minecraft: " + file.Name;
                await SetupTransfer.DownloadAsync(http, file.Url!, file.Path!, stage, file.Hash,
                    file.Size > 0 ? file.Size : null, token, progress);
                token.ThrowIfCancellationRequested();
                await file.ExecuteUpdateTask(token);
                fileProgress?.Report(new InstallerProgressChangedEventArgs(files.Length,
                    Interlocked.Increment(ref completed), file.Name, InstallerEventType.Done));
            });
    }
}
