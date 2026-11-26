# Building & Deploying PromptCut

## Prerequisites
- **JDK**: Java 17
- **Android SDK**: `compileSdk = 36`, `targetSdk = 36`, `minSdk = 29` (Android 10+)
- **Gradle**: `./gradlew` (Gradle 8.14.3 wrapper included) or system `gradle`. Do **not** hardcode API keys or credentials in Gradle or source files.

## Common Commands

```bash
# Make wrapper executable if needed (or: git update-index --chmod=+x gradlew)
chmod +x gradlew

# Run all unit tests and Robolectric tests (Room, KeyVault, ProviderRouter, Agent, Timeline, FilmRules, QA)
./gradlew :app:testDebugUnitTest

# Build debug APK (app/build/outputs/apk/debug/app-debug.apk)
./gradlew assembleDebug

# Run Android Lint
./gradlew :app:lintDebug

# Build shrunk & R8-optimized release APK (app/build/outputs/apk/release/app-release.apk)
./gradlew assembleRelease

# Build Play Store App Bundle (AAB)
./gradlew bundleRelease
```

*(In environments where the Gradle wrapper is disabled, substitute `gradle` for `./gradlew`.)*

## Local Release Signing
Create a git-ignored `keystore.properties` file in the repository root to sign release builds with your upload key:

```properties
storeFile=/absolute/path/to/upload.jks
storePassword=your_store_password
keyAlias=your_key_alias
keyPassword=your_key_password
```

Alternatively, `app/build.gradle.kts` reads `RELEASE_KEYSTORE_PATH`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD` from environment variables. Release packaging (`assembleRelease` / `bundleRelease`) intentionally fails if complete release signing credentials are missing—it never falls back to a debug key. Never commit `keystore.properties`, `.env`, or production `.jks`/`.keystore` files.

## GitHub Deployment (CI, Releases & Pages)

### 1. Required GitHub Repository Secrets (for Signed Releases)
In **Settings → Secrets and variables → Actions**, add:
- `RELEASE_KEYSTORE_BASE64`: Base64-encoded `.jks`/`.keystore` file (`base64 -w0 upload-release.jks` on Linux, `base64 -i upload-release.jks` on macOS)
- `RELEASE_STORE_PASSWORD`: Keystore password
- `RELEASE_KEY_ALIAS`: Signing key alias
- `RELEASE_KEY_PASSWORD`: Signing key password

### 2. Automated Workflows
- **CI & Artifacts (`.github/workflows/android-build.yml`)**: Runs on pushes/PRs to `main` and manual dispatch. Runs unit/Robolectric tests, builds `PromptCut-debug.apk`, and (when release secrets are set) builds and `apksigner`-verifies `PromptCut-release.apk` and `PromptCut-release.aab` with `SHA256SUMS.txt`.
- **GitHub Releases (`.github/workflows/release.yml`)**: Triggered by pushing a version tag matching `versionName` in `app/build.gradle.kts` (e.g., `git tag v1.0.0 && git push origin v1.0.0`) or via `workflow_dispatch`. Requires release signing secrets, verifies the APK is not debug-signed, and publishes `PromptCut-<tag>-release.apk`, `PromptCut-<tag>-release.aab`, and `SHA256SUMS.txt` to GitHub Releases.
- **GitHub Pages (`.github/workflows/pages.yml`)**: Publishes `docs/` (`index.html` and `privacy-policy.html`). Enable **Settings → Pages → Build and deployment → Source: GitHub Actions** once in GitHub repository settings.

## API Keys (Runtime Only)
PromptCut ships with **no embedded API keys**. Users paste their own keys inside the app's **Settings** screen, where `KeyVault` encrypts them on-device using Android Keystore AES-256-GCM:
- Google Gemini (Free tier): https://aistudio.google.com/apikey
- Groq (Free tier + Whisper STT): https://console.groq.com/keys
- Anthropic Claude (Paid API, explicit opt-in consent required): https://console.anthropic.com/settings/keys
