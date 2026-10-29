using System.Collections.Concurrent;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Nodes;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;
using Pim.Infrastructure.Data;
using Pim.Infrastructure.Data.Entities;
using Pim.Infrastructure.Notes;
using Pim.UnitTests.Api;
using Xunit;

namespace Pim.UnitTests.Notes;

// Android device-QA source only. Copy into the sibling API test project for an
// isolated run; never compile it into Android or deploy it.
public sealed class TemporaryAndroidNoteAsrHostTests
{
    private const string Alice = "notes-qa-alice";
    private const string Bob = "notes-qa-bob";

    [Fact]
    public async Task ServeActualTestApiAndSyntheticAsrOnLoopbackForAndroid()
    {
        var root = Environment.GetEnvironmentVariable("PIM_ANDROID_NOTE_ASR_QA_ROOT")
            ?? throw new InvalidOperationException("Missing isolated note ASR QA root");
        Directory.CreateDirectory(root);

        var asr = new SyntheticAsrState();
        var fakeBuilder = WebApplication.CreateBuilder();
        fakeBuilder.Logging.ClearProviders();
        fakeBuilder.WebHost.UseUrls("http://127.0.0.1:0");
        await using var fake = fakeBuilder.Build();
        fake.MapPost("/v1/chat/completions", async context =>
        {
            JsonDocument document;
            try
            {
                document = await JsonDocument.ParseAsync(context.Request.Body, cancellationToken: context.RequestAborted);
            }
            catch (JsonException)
            {
                context.Response.StatusCode = StatusCodes.Status400BadRequest;
                await context.Response.WriteAsJsonAsync(new { error = "invalid synthetic ASR request" });
                return;
            }

            using (document)
            {
                try
                {
                    var audio = document.RootElement.GetProperty("messages")[0].GetProperty("content")[0]
                        .GetProperty("input_audio").GetProperty("data").GetString() ?? "";
                    var payload = audio.StartsWith("data:", StringComparison.Ordinal)
                        ? audio[(audio.IndexOf(',') + 1)..]
                        : audio;
                    var bytes = Convert.FromBase64String(payload);
                    var sha = Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
                    var sequence = asr.Record(sha, bytes.Length);
                    if (sequence == asr.FailOnCall)
                    {
                        asr.Mark(sequence, "failed");
                        context.Response.StatusCode = StatusCodes.Status503ServiceUnavailable;
                        await context.Response.WriteAsJsonAsync(new { error = "synthetic upstream failure" });
                        return;
                    }

                    if (asr.DelayMilliseconds > 0)
                        await Task.Delay(asr.DelayMilliseconds, context.RequestAborted);

                    var transcript = $"合成 ASR 第 {sequence} 次发送，音频 {sha[..12]}";
                    asr.Mark(sequence, "succeeded", transcript);
                    await context.Response.WriteAsJsonAsync(new
                    {
                        id = $"synthetic-note-asr-{sequence}",
                        @object = "chat.completion",
                        created = 1780000000,
                        model = "mimo-v2.5-asr",
                        choices = new[]
                        {
                            new
                            {
                                index = 0,
                                finish_reason = "stop",
                                message = new { role = "assistant", content = transcript }
                            }
                        },
                        usage = new { prompt_tokens = 10, completion_tokens = 5, total_tokens = 15 }
                    });
                }
                catch (Exception error) when (error is InvalidOperationException or FormatException or KeyNotFoundException)
                {
                    context.Response.StatusCode = StatusCodes.Status400BadRequest;
                    await context.Response.WriteAsJsonAsync(new { error = "invalid synthetic audio message" });
                }
            }
        });
        await fake.StartAsync();
        var asrBase = fake.Urls.Single() + "/v1/";
        var asrEndpoint = asrBase + "chat/completions";

        await using var factory = AuthRegistrationTestHost.Create(
                AuthRegistrationTestHost.NewDbName("android-note-asr"))
            .WithWebHostBuilder(builder => builder
                .UseEnvironment("Test")
                .UseSetting("DataProtection:KeysPath", Path.Combine(root, "keys"))
                .UseSetting("Jwt:PrivateKeyPath", Path.Combine(root, "missing-isolated-jwt.pem"))
                .UseSetting("NativeNotes:StorageRoot", Path.Combine(root, "materials"))
                .UseSetting("ManagedStorage:Root", Path.Combine(root, "binary")));

        await AuthRegistrationTestHost.SeedUserAsync(factory, "notes-qa-admin", "admin");
        await AuthRegistrationTestHost.SeedUserAsync(factory, Alice, "user");
        await AuthRegistrationTestHost.SeedUserAsync(factory, Bob, "user");
        await AuthRegistrationTestHost.SeedUserAsync(factory, "notes-qa-inactive", "user", isActive: false);

        var adminSession = await AuthRegistrationTestHost.LoginOkAsync(factory, "notes-qa-admin");
        using var admin = AuthRegistrationTestHost.Authed(factory, adminSession.AccessToken);
        var model = await CreateTranscriptionModelAsync(admin, asrBase);
        await CreateDisabledTranscriptionModelAsync(admin, asrBase);
        var runtime = await EnvelopeAsync(await admin.GetAsync("/api/v1/ai/model-settings"));
        var runtimeRevision = Guid.Parse(runtime["data"]!["revision"]!.GetValue<string>());
        var settingsResponse = await admin.PutAsJsonAsync("/api/v1/ai/model-settings", new
        {
            expectedRevision = runtimeRevision,
            enabled = true,
            textModelId = (Guid?)null,
            visionModelId = (Guid?)null,
            transcriptionModelId = model.Id
        });
        _ = await EnvelopeAsync(settingsResponse);

        var builder = WebApplication.CreateBuilder();
        builder.Logging.ClearProviders();
        builder.WebHost.UseUrls("http://127.0.0.1:0");
        await using var app = builder.Build();
        using var upstream = factory.CreateClient(new WebApplicationFactoryClientOptions
        {
            AllowAutoRedirect = false
        });
        var runs = new ConcurrentDictionary<Guid, string>();
        var dropAsrCreateResponse = 0;

        app.Run(async context =>
        {
            var path = context.Request.Path.Value ?? "";
            if (path == "/__qa/asr/summary")
            {
                await context.Response.WriteAsJsonAsync(asr.Summary(runs.Keys.ToArray()));
                return;
            }

            if (path == "/__qa/asr/target")
            {
                await context.Response.WriteAsJsonAsync(new
                {
                    model = new
                    {
                        model.Id,
                        ModelRevision = model.Revision,
                        DisplayName = "ASR QA 合成目标 v1",
                        Model = "mimo-v2.5-asr",
                        Endpoint = asrEndpoint,
                        PresetId = "mimo-asr",
                        PresetVersion = "1"
                    },
                    fixture = new
                    {
                        sampleRate = 16_000,
                        durationSamples = 16_000 * 121,
                        expectedSegments = 3,
                        expectedSamplesPerSegment = new[] { 960_000L, 960_000L, 16_000L }
                    }
                });
                return;
            }

            if (path == "/__qa/asr/control")
            {
                asr.Configure(context.Request);
                await context.Response.WriteAsJsonAsync(asr.Summary(runs.Keys.ToArray()));
                return;
            }

            if (path == "/__qa/asr/fault")
            {
                var kind = context.Request.Query["kind"].ToString();
                if (kind == "create-response") Interlocked.Exchange(ref dropAsrCreateResponse, 1);
                await context.Response.WriteAsync(kind == "create-response" ? "armed" : "ignored");
                return;
            }

            if (path == "/__qa/asr/run")
            {
                if (!Guid.TryParse(context.Request.Query["taskId"], out var requestedTask))
                {
                    await Results.BadRequest(new { error = "taskId is required" })
                        .ExecuteAsync(context);
                    return;
                }

                (Guid Owner, Guid Id) task;
                var scopes = factory.Services.GetRequiredService<IServiceScopeFactory>();
                using (var discovery = scopes.CreateScope())
                {
                    var store = discovery.ServiceProvider.GetRequiredService<NoteAsrTaskStore>();
                    task = store.Enumerate().SingleOrDefault(x => x.Id == requestedTask);
                }
                if (task.Owner == Guid.Empty || task.Id == Guid.Empty)
                {
                    await Results.NotFound(new { error = "task not found in isolated QA store" })
                        .ExecuteAsync(context);
                    return;
                }
                if (!runs.TryAdd(task.Id, "running"))
                {
                    await Results.Conflict(new { error = "task run already active" })
                        .ExecuteAsync(context);
                    return;
                }

                _ = Task.Run(async () =>
                {
                    using var scope = scopes.CreateScope();
                    try
                    {
                        var job = scope.ServiceProvider.GetRequiredService<NoteAsrJob>();
                        await job.RunAsync(task.Owner, task.Id, CancellationToken.None);
                    }
                    finally
                    {
                        runs.TryRemove(task.Id, out _);
                    }
                });
                await context.Response.WriteAsJsonAsync(new { accepted = true, taskId = task.Id });
                return;
            }

            if (path == "/__qa/asr/unknown")
            {
                if (!Guid.TryParse(context.Request.Query["taskId"], out var requestedTask) ||
                    !int.TryParse(context.Request.Query["segment"], out var segment))
                {
                    await Results.BadRequest(new { error = "taskId and segment are required" })
                        .ExecuteAsync(context);
                    return;
                }
                using var scope = scopesFor(factory).CreateScope();
                var store = scope.ServiceProvider.GetRequiredService<NoteAsrTaskStore>();
                var db = scope.ServiceProvider.GetRequiredService<PimDbContext>();
                var task = store.Enumerate().SingleOrDefault(x => x.Id == requestedTask);
                if (task.Owner == Guid.Empty)
                {
                    await Results.NotFound(new { error = "task not found" }).ExecuteAsync(context);
                    return;
                }
                var requestId = Guid.NewGuid();
                var logId = Guid.NewGuid();
                db.AiRequestLogs.Add(new AiRequestLogEntity
                {
                    Id = logId,
                    UserId = task.Owner,
                    ClientRequestId = requestId,
                    Module = "notes",
                    Purpose = "ai.asr-call",
                    SourceObjectType = "native-note-asr-task",
                    SourceObjectId = task.Id.ToString(),
                    Provider = "mimo-asr",
                    Model = "mimo-v2.5-asr",
                    Status = "running",
                    AttemptNumber = 1,
                    MaxAttempts = 1,
                    StartedAt = DateTimeOffset.UtcNow,
                    ExecutionExpiresAt = DateTimeOffset.UtcNow.AddMinutes(30),
                    InputHash = new string('0', 64),
                    CorrelationId = Guid.NewGuid().ToString("N")
                });
                await db.SaveChangesAsync(context.RequestAborted);
                await store.UpdateAsync(task.Owner, task.Id, state =>
                {
                    if (segment < 0 || segment >= state.Steps.Count)
                        throw new ArgumentOutOfRangeException(nameof(segment));
                    state.StopRequested = false;
                    for (var index = 0; index < state.Steps.Count; index++)
                    {
                        if (index == segment)
                        {
                            state.Steps[index].Attempts = [new NoteAsrAttempt
                            {
                                RequestId = requestId,
                                LogId = logId,
                                Status = "Unknown",
                                ErrorMessage = "QA synthetic unknown upstream receipt; explicit recovery required"
                            }];
                        }
                        else if (state.Steps[index].Current.Status != "Succeeded")
                        {
                            state.Steps[index].Attempts = [new NoteAsrAttempt
                            {
                                Status = "Blocked",
                                ErrorMessage = "QA synthetic unknown outcome stopped remaining slices"
                            }];
                        }
                    }
                }, context.RequestAborted);
                await context.Response.WriteAsJsonAsync(new { updated = true, taskId = task.Id, segment });
                return;
            }

            using var request = new HttpRequestMessage(
                new HttpMethod(context.Request.Method),
                "http://localhost" + path + context.Request.QueryString);
            if (context.Request.ContentLength is > 0 ||
                context.Request.Headers.ContainsKey("Transfer-Encoding"))
                request.Content = new StreamContent(context.Request.Body);
            foreach (var header in context.Request.Headers)
            {
                if (header.Key is "Host" or "Transfer-Encoding" or "Content-Length") continue;
                if (!request.Headers.TryAddWithoutValidation(header.Key, header.Value.ToArray()))
                    request.Content?.Headers.TryAddWithoutValidation(header.Key, header.Value.ToArray());
            }

            using var response = await upstream.SendAsync(
                request,
                HttpCompletionOption.ResponseHeadersRead,
                context.RequestAborted);
            var isAsrCreate = HttpMethods.IsPost(context.Request.Method) &&
                path.TrimEnd('/') == "/api/v1/native-notes/asr-tasks";
            if (isAsrCreate && response.IsSuccessStatusCode &&
                Interlocked.Exchange(ref dropAsrCreateResponse, 0) == 1)
            {
                context.Response.StatusCode = StatusCodes.Status503ServiceUnavailable;
                await context.Response.WriteAsJsonAsync(new
                {
                    code = 50302,
                    message = "Synthetic lost ASR create acknowledgement after actual task persistence"
                });
                return;
            }

            context.Response.StatusCode = (int)response.StatusCode;
            foreach (var header in response.Headers.Concat(response.Content.Headers))
            {
                if (header.Key is "Transfer-Encoding" or "Content-Length") continue;
                context.Response.Headers[header.Key] = header.Value.ToArray();
            }
            await response.Content.CopyToAsync(context.Response.Body, context.RequestAborted);
        });

        await app.StartAsync();
        await File.WriteAllTextAsync(Path.Combine(root, "ready.json"), JsonSerializer.Serialize(new
        {
            url = app.Urls.Single(),
            apiBase = app.Urls.Single() + "/api/v1/",
            accounts = new[] { Alice, Bob },
            password = AuthRegistrationTestHost.DefaultPassword,
            model = new
            {
                model.Id,
                ModelRevision = model.Revision,
                DisplayName = "ASR QA 合成目标 v1",
                Model = "mimo-v2.5-asr",
                Endpoint = asrEndpoint,
                PresetId = "mimo-asr",
                PresetVersion = "1"
            },
            fixture = new
            {
                sampleRate = 16_000,
                durationSamples = 16_000 * 121,
                expectedSegments = 3,
                expectedSamplesPerSegment = new[] { 960_000L, 960_000L, 16_000L }
            },
            boundary = "Actual Test API plus loopback synthetic ASR; isolated InMemory DB; no production, real credentials, classroom audio, or paid provider calls"
        }, new JsonSerializerOptions(JsonSerializerDefaults.Web)));

        var stop = Path.Combine(root, "stop");
        var deadline = DateTimeOffset.UtcNow.AddHours(2);
        while (!File.Exists(stop) && DateTimeOffset.UtcNow < deadline)
            await Task.Delay(500);
        await File.WriteAllTextAsync(Path.Combine(root, "summary.json"), JsonSerializer.Serialize(new
        {
            asr = asr.Summary(runs.Keys.ToArray()),
            stoppedAt = DateTimeOffset.UtcNow
        }, new JsonSerializerOptions(JsonSerializerDefaults.Web)));
        await app.StopAsync();
        await fake.StopAsync();

        static IServiceScopeFactory scopesFor(WebApplicationFactory<Program> factory) =>
            factory.Services.GetRequiredService<IServiceScopeFactory>();
    }

    private static async Task<(Guid Id, Guid Revision)> CreateTranscriptionModelAsync(
        HttpClient admin,
        string asrBase)
    {
        var response = await admin.PostAsJsonAsync("/api/v1/ai/models", new
        {
            name = "ASR QA 合成目标 v1",
            baseUrl = asrBase,
            model = "mimo-v2.5-asr",
            enabled = true,
            capabilities = new[] { "Transcription" },
            timeoutSeconds = 20,
            maxOutputTokens = 256,
            apiKey = "synthetic-note-asr-key-not-secret",
            asrPresetId = "mimo-asr"
        });
        var data = await EnvelopeAsync(response);
        return (
            Guid.Parse(data["data"]!["id"]!.GetValue<string>()),
            Guid.Parse(data["data"]!["revision"]!.GetValue<string>()));
    }

    private static async Task CreateDisabledTranscriptionModelAsync(HttpClient admin, string asrBase)
    {
        var response = await admin.PostAsJsonAsync("/api/v1/ai/models", new
        {
            name = "ASR QA 已停用目标",
            baseUrl = asrBase,
            model = "mimo-v2.5-asr",
            enabled = false,
            capabilities = new[] { "Transcription" },
            timeoutSeconds = 20,
            maxOutputTokens = 256,
            apiKey = "synthetic-disabled-key-not-secret",
            asrPresetId = "mimo-asr"
        });
        _ = await EnvelopeAsync(response);
    }

    private static async Task<JsonNode> EnvelopeAsync(HttpResponseMessage response)
    {
        var body = await response.Content.ReadAsStringAsync();
        Assert.True(response.IsSuccessStatusCode, $"{(int)response.StatusCode}: {body}");
        return JsonNode.Parse(body) ?? throw new InvalidOperationException("Empty API response");
    }

    private sealed class SyntheticAsrState
    {
        private readonly object _gate = new();
        private readonly List<Call> _calls = [];
        private int _failOnCall;
        private int _delayMilliseconds;

        public int FailOnCall { get { lock (_gate) return _failOnCall; } }
        public int DelayMilliseconds { get { lock (_gate) return _delayMilliseconds; } }

        public int Record(string sha256, int bytes)
        {
            lock (_gate)
            {
                var sequence = _calls.Count + 1;
                _calls.Add(new Call(sequence, sha256, bytes, "received", null));
                return sequence;
            }
        }

        public void Mark(int sequence, string status, string? transcript = null)
        {
            lock (_gate)
            {
                var index = _calls.FindIndex(x => x.Sequence == sequence);
                _calls[index] = _calls[index] with { Status = status, Transcript = transcript };
            }
        }

        public void Configure(HttpRequest request)
        {
            lock (_gate)
            {
                if (request.Query.TryGetValue("reset", out var reset) && reset == "1")
                {
                    _calls.Clear();
                    _failOnCall = 0;
                    _delayMilliseconds = 0;
                }
                if (int.TryParse(request.Query["delayMs"], out var delay))
                    _delayMilliseconds = Math.Clamp(delay, 0, 30_000);
                if (int.TryParse(request.Query["failAfter"], out var after))
                    _failOnCall = after > 0 ? _calls.Count + after : 0;
                if (request.Query.ContainsKey("clearFailure"))
                    _failOnCall = 0;
            }
        }

        public object Summary(IReadOnlyCollection<Guid> running)
        {
            lock (_gate)
            {
                return new
                {
                    sentCalls = _calls.Count,
                    successfulCalls = _calls.Count(x => x.Status == "succeeded"),
                    failedCalls = _calls.Count(x => x.Status == "failed"),
                    pendingCalls = _calls.Count(x => x.Status == "received"),
                    delayMilliseconds = _delayMilliseconds,
                    calls = _calls.Select(x => new
                    {
                        x.Sequence,
                        x.Sha256,
                        x.Bytes,
                        x.Status,
                        x.Transcript
                    }).ToArray(),
                    runningTaskIds = running.ToArray()
                };
            }
        }

        private sealed record Call(
            int Sequence,
            string Sha256,
            int Bytes,
            string Status,
            string? Transcript);
    }
}
