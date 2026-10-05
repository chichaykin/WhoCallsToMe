# WhoCallToMe

Private Android caller ID and spam-screening app. The first version keeps all
personal data on-device and uses Android's `CallScreeningService` while leaving
Google Phone as the default dialer.

## Build

```bash
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## First run

1. Grant Contacts and Notifications when prompted.
2. Open **Settings** inside the app and choose **Enable caller ID**.
3. Select WhoCallToMe as the caller-ID and spam app.
4. Add a personal blocked number to validate local call rejection.

API keys and the PhoneBlock bearer token are encrypted with Android Keystore
and are never included in exports. Every source with a saved key is queried in
parallel; the result of each source is cached locally until its own expiry.
PhoneBlock is free but requires a free account token; tellows and IPQS are
optional keyed sources.

Current source constraints and the 50-number evaluation template are described
in `docs/data-sources.md`.

Incoming-call notifications show an animated indicator while lookup is running,
the number of completed sources, and a final result even when caller data has
not changed. Expand the notification to see each source's status. Cached answers
are labeled separately; missing data, errors, and unavailable sources never imply
that a caller is safe. After 10 seconds, an unfinished lookup is displayed as
incomplete and the indicator stops. Notification permission and the caller-ID
notification channel must be enabled for these updates to be visible.

## Future provider plan

tellows can be enabled as a third source in the personal build when a private
key is saved. Its score, common caller name, and category are compared with the
other available sources and cached through the existing provider layer. A
public or paid release requires a commercial tellows license; the current
private key is for personal testing only.
