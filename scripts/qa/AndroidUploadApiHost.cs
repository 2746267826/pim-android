using System.Net;
using System.Text.Json;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.Logging;
using Pim.UnitTests.Api;
using Xunit;

namespace Pim.UnitTests.Notes;

// Local device-QA source only. Copy into the sibling API test project temporarily; never compile into the Android product.
public sealed class TemporaryAndroidUploadHostTests
{
    [Fact]
    public async Task ServeActualTestApiOnLoopbackForAndroid()
    {
        var root = Environment.GetEnvironmentVariable("PIM_ANDROID_UPLOAD_QA_ROOT") ?? throw new InvalidOperationException("Missing isolated QA root");
        Directory.CreateDirectory(root);
        await using var factory = AuthRegistrationTestHost.Create(AuthRegistrationTestHost.NewDbName("android-note-upload"))
            .WithWebHostBuilder(b => b.UseEnvironment("Test").UseSetting("DataProtection:KeysPath", Path.Combine(root, "keys"))
                .UseSetting("Jwt:PrivateKeyPath", Path.Combine(root, "missing-isolated-jwt.pem"))
                .UseSetting("NativeNotes:StorageRoot", Path.Combine(root, "materials")).UseSetting("ManagedStorage:Root", Path.Combine(root, "binary")));
        await AuthRegistrationTestHost.SeedUserAsync(factory, "notes-qa-root", "admin");
        await AuthRegistrationTestHost.SeedUserAsync(factory, "notes-qa-alice", "user");
        await AuthRegistrationTestHost.SeedUserAsync(factory, "notes-qa-bob", "user");
        using var upstream = factory.CreateClient(new WebApplicationFactoryClientOptions { AllowAutoRedirect = false });
        var builder = WebApplication.CreateBuilder(); builder.Logging.ClearProviders(); builder.WebHost.UseUrls("http://127.0.0.1:0");
        await using var app = builder.Build();
        string? nextFault = null; var receivedParts = 0; var completedCalls = 0;
        app.Run(async context =>
        {
            if (context.Request.Path == "/__qa/fault")
            { nextFault = context.Request.Query["kind"]; await context.Response.WriteAsync("armed"); return; }
            if (context.Request.Path == "/__qa/summary")
            { await context.Response.WriteAsJsonAsync(new { receivedParts, completedCalls }); return; }
            using var request = new HttpRequestMessage(new HttpMethod(context.Request.Method), "http://localhost" + context.Request.Path + context.Request.QueryString);
            if (context.Request.ContentLength is > 0 || context.Request.Headers.ContainsKey("Transfer-Encoding")) request.Content = new StreamContent(context.Request.Body);
            foreach (var header in context.Request.Headers)
                if (header.Key is not ("Host" or "Transfer-Encoding"))
                    if (!request.Headers.TryAddWithoutValidation(header.Key, header.Value.ToArray())) request.Content?.Headers.TryAddWithoutValidation(header.Key, header.Value.ToArray());
            using var response = await upstream.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, context.RequestAborted);
            var part = context.Request.Path.Value!.Contains("/parts/", StringComparison.Ordinal) && context.Request.Method == "PUT";
            var complete = context.Request.Path.Value!.EndsWith("/complete", StringComparison.Ordinal);
            if (response.IsSuccessStatusCode)
            {
                if (part) Interlocked.Increment(ref receivedParts);
                if (complete) Interlocked.Increment(ref completedCalls);
                if (part && nextFault == "part" || complete && nextFault == "complete")
                {
                    nextFault = null; context.Response.StatusCode = 503;
                    await context.Response.WriteAsJsonAsync(new { code = 50301, message = "Synthetic lost acknowledgement after actual storage" }); return;
                }
            }
            context.Response.StatusCode = (int)response.StatusCode;
            foreach (var header in response.Headers.Concat(response.Content.Headers))
                if (header.Key is not ("Transfer-Encoding" or "Content-Length")) context.Response.Headers[header.Key] = header.Value.ToArray();
            await response.Content.CopyToAsync(context.Response.Body, context.RequestAborted);
        });
        await app.StartAsync();
        await File.WriteAllTextAsync(Path.Combine(root, "ready.json"), JsonSerializer.Serialize(new { url = app.Urls.Single(), boundary = "Actual Test API, isolated synthetic accounts/materials, loopback only" }));
        var stop = Path.Combine(root, "stop"); var deadline = DateTimeOffset.UtcNow.AddHours(2);
        while (!File.Exists(stop) && DateTimeOffset.UtcNow < deadline) await Task.Delay(500);
        await File.WriteAllTextAsync(Path.Combine(root, "summary.json"), JsonSerializer.Serialize(new { receivedParts, completedCalls }));
        await app.StopAsync();
    }
}
