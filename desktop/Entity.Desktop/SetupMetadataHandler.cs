namespace Entity.Desktop;

// CmlLib's metadata readers do not all forward cancellation to body reads.
// Buffer only metadata under a full-response deadline before returning it to CmlLib.
internal sealed class SetupMetadataHandler : DelegatingHandler
{
    private readonly CancellationToken operationCancellation;
    public SetupMetadataHandler(CancellationToken operationCancellation = default, HttpMessageHandler? innerHandler = null)
        : base(innerHandler ?? new HttpClientHandler()) { this.operationCancellation = operationCancellation; }

    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
    {
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, operationCancellation);
        deadline.CancelAfter(TimeSpan.FromMinutes(2));
        deadline.Token.ThrowIfCancellationRequested();
        var response = await base.SendAsync(request, deadline.Token);
        try
        {
            var original = response.Content;
            var bytes = await original.ReadAsByteArrayAsync(deadline.Token);
            var buffered = new ByteArrayContent(bytes);
            foreach (var header in original.Headers) buffered.Headers.TryAddWithoutValidation(header.Key, header.Value);
            response.Content = buffered;
            original.Dispose();
            return response;
        }
        catch { response.Dispose(); throw; }
    }
}
