# Third-party and content notices

This repository contains or integrates third-party code, models, binaries, and manual-derived content. Before publishing or distributing the repository or APK, the repository owner must verify that every artifact may be redistributed and must add the required notices.

Known categories include:

- `vendor/llama.cpp` and its bundled dependencies; see the license files inside that directory.
- SQLite amalgamation sources in `vendor/sqlite`.
- sqlite-vec sources in `vendor/sqlite-vec`.
- ONNX Runtime, resolved through Gradle.
- sherpa-onnx, supplied as `app/libs/sherpa-onnx-1.13.2.aar`.
- MiniLM embedding model and vocabulary under `app/src/main/assets/models`.
- Qwen2.5-0.5B-Instruct GGUF Q4_0 under
  `app/src/main/assets/models`, published by Qwen under Apache-2.0.
  Bundled SHA-256:
  `7671c0c304e6ce5a7fc577bcb12aba01e2c155cc2efd29b2213c95b18edaf6ed`.
- Zipformer speech-recognition model under `app/src/main/assets/asr`.
- Microsoft MarkItDown, used as a build-time PDF-to-Markdown conversion tool.
- The manual database and extracted images under `app/src/main/assets/database` and `app/src/main/assets/manual_assets`.

No project-wide license has been selected in this snapshot. GitHub hosting does not itself grant permission to reuse the code or bundled content. Add a root `LICENSE` and complete model/content attribution only after confirming ownership and redistribution terms.
