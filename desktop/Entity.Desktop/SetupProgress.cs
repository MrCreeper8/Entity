namespace Entity.Desktop;

public sealed record SetupProgress(string Stage, long BytesReceived = 0, long? TotalBytes = null)
{
    public int? Percent => TotalBytes is > 0
        ? (int)Math.Clamp(BytesReceived * 100.0 / TotalBytes.Value, 0, 100)
        : null;
}

// Unlike Progress<T>, this adapter does not enqueue stale callbacks after a stage ends.
internal sealed class SetupCallback<T>(Action<T> callback) : IProgress<T>
{
    public void Report(T value) => callback(value);
}
