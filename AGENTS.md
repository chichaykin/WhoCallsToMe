# WhoCallToMe agent instructions

## Project scope

WhoCallToMe is a private Android caller ID and spam-screening app. The current
build is for personal testing on a Pixel device. Keep personal data, external
provider cache, overrides, and block rules on the device unless the user asks
for an export or server feature.

## Build and validation

- Use Kotlin, Jetpack Compose, Room, WorkManager, and `CallScreeningService`.
- `minSdk` is 30 and `targetSdk` is 37.
- Build the debug APK with `./gradlew assembleDebug`.
- Run `./gradlew testDebugUnitTest lintDebug` after code changes that affect
  application behavior.
- The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
- When the Pixel is connected, install with `adb install -r` so the Room
  database and Android Keystore data are preserved.
- The personal Pixel is not a test fixture. Run `testDebugUnitTest`, `lintDebug`,
  and `assembleDebug` locally; run `connectedDebugAndroidTest` only on a
  dedicated emulator or test device unless the user explicitly confirms using
  the personal Pixel immediately before that command.

## Local API secrets

- Never put API keys or bearer tokens in Kotlin source, README files, tests,
  exports, commits, or public APK distribution.
- `local.properties` is ignored by Git and is the only local build input for
  personal debug provider credentials:

  - `whocalltome.phoneblock.token`
  - `whocalltome.ipqs.key`

- Every build variant, including release, injects those values through
  `BuildConfig` and seeds them into `SecretStore` on first launch.
  `SecretStore` must encrypt credentials with Android Keystore before normal
  app use.
- Any APK built from this checkout is a personal build and may contain the
  local provider credentials. Never copy personal `local.properties` into
  another checkout or share an APK built from it publicly.
- tellows is optional and must remain unset unless the user supplies a valid
  private-use key.

## Provider behavior

- Personal overrides and contacts have priority over external results.
- In the personal debug build, IPQualityScore is the default lookup provider;
  PhoneBlock is also queried for spam reputation. A user can change the source
  in Settings.
- External spam scores warn the user. Only personal block rules automatically
  reject calls.
- Cache provider results according to the source terms and the existing local
  cache policy. Fail open when a network lookup fails so a call is not delayed.

## Phone and data behavior

- The system call log is imported only after `READ_CALL_LOG` permission and is
  deduplicated by the Android system call ID.
- Do not add `READ_CALL_LOG` or SMS permissions for unrelated features.
- WhatsApp uses the official `wa.me` intent and SMS uses `ACTION_SENDTO` with
  `smsto:`. Do not implement a custom SMS transport for this private version.
- Preserve Room migrations and user data across APK updates. Do not use a
  destructive database migration to solve a schema change.

## Changes and review

- Never uninstall, remove, or reset an app on a device without explicit user
  confirmation immediately before the destructive operation. Read-only ADB
  inspection and installing/updating with `adb install -r` remain allowed when
  they are within the user's request.
- Treat all of the following as destructive device operations that require the
  same immediate confirmation: `adb uninstall`, `adb shell pm uninstall`,
  `adb shell pm clear`, a plain `adb install` without `-r`, and
  `connectedDebugAndroidTest` against the personal Pixel. Do not infer consent
  from an earlier request to build, update, or install the app.
- Before installing on the personal Pixel, run the read-only check
  `adb shell pm path com.whocalltome.app`. If the package is absent, stop and
  ask for confirmation; never fall back from `adb install -r` to a fresh
  `adb install` automatically.
- Use only `adb install -r app/build/outputs/apk/debug/app-debug.apk` for a
  normal update. Keep the existing `applicationId` and signing key stable so
  an update does not require uninstalling the app.
- If a destructive operation is explicitly approved, offer or make a current
  export of portable personal data first. Do not export API keys, bearer
  tokens, or other secrets.
- Keep provider integrations behind `CallerIdentityRepository` and the
  existing provider interfaces so a future server can be added without
  rewriting the UI.
- Do not silently replace a contact name, allow rule, or block rule with an
  external result.
- Do not expose phone numbers, contact names, API keys, or tokens in logs or
  final responses. Report counts and statuses instead.
