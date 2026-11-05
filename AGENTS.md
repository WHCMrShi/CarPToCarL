# Development workflow

- Base development on `codex/audi-q4-etron` in `WHCMrShi/CarPToCarL`.
- Commit and push completed changes to `origin/codex/audi-q4-etron`, as requested by the repository owner. Do not push to `main` or force-push.
- Windows toolchain: JDK 17, Gradle 8.10.2, Android SDK 34 and Build Tools 34.0.0.
- See `docs/local-build-windows.md`. Install local tools with `scripts/setup-local.ps1`; validate with `scripts/build-local.ps1`.
- Keep tool binaries, caches, local mirror/proxy configuration, signing material and APK outputs out of Git.
- Report actual validation results; distinguish local checks from Q4 vehicle testing.
