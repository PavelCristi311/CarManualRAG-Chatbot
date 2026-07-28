# Security policy

## Reporting

Do not open a public issue for a vulnerability. Report it privately through the repository owner's preferred GitHub security contact or GitHub private vulnerability reporting once enabled.

Include the affected version, reproduction steps, impact, and any proposed mitigation. Avoid attaching proprietary manual content or large model files.

## Supported version

Until a formal release policy exists, only the latest commit on `main` is supported.

## Security boundaries

The app is designed to run without network access. Its main security-sensitive inputs are microphone audio, typed questions, bundled database content, model files, and JNI boundaries. Changes should preserve prompt-injection screening, answer evidence validation, read-only database access, Android permission minimization, and deterministic native-resource cleanup.
