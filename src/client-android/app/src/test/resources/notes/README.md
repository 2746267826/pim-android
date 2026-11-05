# Cross-client material golden

`web-continuation-v1` contains a synthetic note produced by the API/Web repository's production `buildMaterialBundle` from the Android production material-store fixture. It changes the title and one Markdown block, while preserving photo, ink, recording-manifest, and WAV bytes.

The source is API PR #561, candidate `903595b160d015a11ecbfa1f187f76c0b1e69bcb`, under `tests/Pim.UnitTests/Fixtures/notes/web-continuation-v1`.

- `input.json` SHA-256: `19916b65f47d4c9dea18a989e0a55fc5de28f926584e261a37a60796b47377c3`
- `manifest.json` SHA-256: `5cbf921733156fe44daba52ce291f09fcc85671e64ce6a032765810f51cf492a`

Keep these bytes unchanged. The directory's `* -text` attribute prevents line-ending conversion from invalidating the manifest digests. Regenerate through the production-builder test and review all material hashes together when updating it.

`CloudNoteWebContinuationGoldenTest` consumes the fixture through the production cloud discovery, download, validation, and installation path. Missing fixture resources fail the test. The test uses a local synthetic HTTP server and does not validate a physical device, paid provider, or production deployment.
