# Contributing

Thank you for improving Atlas Manual Assistant.

## Development workflow

1. Install the prerequisites listed in `README.md`, including Git LFS.
2. Create a focused branch from `main`.
3. Keep the app fully offline and preserve evidence citations and abstention behavior.
4. Add or update tests for behavior that can run on the JVM.
5. Run `./gradlew testDebugUnitTest lintDebug assembleDebug` before opening a pull request.
6. Describe user-visible behavior, performance impact, model or database changes, and verification in the pull request.

## Code conventions

- Use Java 17 and C++17 features supported by the configured Android toolchain.
- Keep Android lifecycle work on the UI thread and inference or I/O work on the existing background executors.
- Close native, SQLite, ONNX Runtime, audio, and TTS resources deterministically.
- Keep retrieval constants named and documented. Threshold changes require representative evaluation data.
- Do not introduce network access, telemetry, or a cloud fallback without an explicit architectural decision and user-facing disclosure.
- Never log manual text, user questions, transcripts, or generated answers in release builds.

## Large assets

The repository tracks `.aar`, `.db`, `.gguf`, and `.onnx` files through Git LFS. Run `git lfs install` before adding or replacing them. Model or database updates must include provenance, license, expected byte size, and compatibility notes.

## Pull-request checklist

- [ ] Functionality is unchanged, or the intended change is clearly described.
- [ ] Unit tests pass.
- [ ] A debug APK assembles, including native code.
- [ ] Microphone, text-to-speech, retrieval, citations, image display, and cleanup were checked when affected.
- [ ] Documentation reflects architecture or dependency changes.
- [ ] New third-party material is redistributable and documented.
