# Bugfixes Kotlin SDK

Sends an Android app's crashes, ANRs, errors and logs to [Bugfixes](https://bugfix.es), which groups them into reports and raises tickets (GitHub, Jira, TrackLin.es, …).

- **Crashes:** uncaught JVM/Kotlin exceptions, written to disk as the app dies and sent on the next launch.
- **ANRs and native crashes:** from the system's exit records (`ApplicationExitInfo`, Android 11 / API 30+), sent on the next launch, each once.
- **Errors and logs** you report yourself.
- **Offline-safe:** reports wait in a queue (at most 50, oldest dropped first) until they're sent.
- **No dependencies** beyond the Kotlin standard library; it never throws into your app.

## Installation

```kotlin
dependencies {
    implementation("es.bugfix:bugfixes-kotlin:0.1.0")
}
```

minSdk 24. The library declares the `INTERNET` permission.

## Setup

Create a **mobile key** for your project's agent in the Bugfixes dashboard, with your app's package name as an allowed app ID. A mobile key (`bfm_…`) is public, so it's safe to ship in the app. **Never put an agent secret in an app**; the SDK refuses to use one.

Pass the key in through `BuildConfig`, the same way as flags.gg's IDs:

```kotlin
// app/build.gradle.kts
android {
    defaultConfig {
        // unset: reporting stays off
        buildConfigField("String", "BUGFIXES_KEY", "\"${providers.gradleProperty("bugfixesKey").getOrElse("")}\"")
    }
}
```

Build the client once, in `Application.onCreate`:

```kotlin
class App : Application() {
    lateinit var bugfixes: BugfixesClient

    override fun onCreate() {
        super.onCreate()
        bugfixes = BugfixesClient.builder()
            .key(BuildConfig.BUGFIXES_KEY)
            .environment(BuildConfig.BUILD_TYPE)
            .build(this) // installs the crash handler and sends anything left from last time
    }
}
```

With no key, `build` returns a client that does nothing, so debug builds without a key just work.

## Reporting

```kotlin
try {
    checkout()
} catch (e: IOException) {
    bugfixes.report(e) // an error report, with its stack trace
}

bugfixes.log(Level.ERROR, "payment declined", e) // sent: error is the default level
bugfixes.log(Level.INFO, "screen opened")          // not sent unless .level(Level.INFO)
```

## Options

| Builder | Default | |
|---|---|---|
| `key` | none | The `bfm_…` mobile key. Empty: off. |
| `environment` | `""` | e.g. `debug` or `release`. |
| `release` | `versionName (versionCode)` | Groups reports by app version. |
| `level` | `ERROR` | The lowest `log` level sent. Crashes and `report` are always sent. |
| `server` | `https://api.bugfix.es/v1` | Must be https; plain http only to `localhost` or `10.0.2.2` (the emulator's host) for local development. |

Every report carries the device context: Android version and API level, manufacturer and model, app version and build.

## Release builds

R8 renames your classes and methods, so upload each build's mapping file from CI, or crashes from that build stay obfuscated and their reports say `unsymbolicated`. Upload it before the build reaches users: reports that arrive first keep their obfuscated stack.

```sh
curl --fail --data-binary @app/build/outputs/mapping/release/mapping.txt \
  -H "X-API-KEY: $BUGFIXES_AGENT_KEY" -H "X-API-SECRET: $BUGFIXES_AGENT_SECRET" \
  --url-query platform=android --url-query "release=$VERSION_NAME ($VERSION_CODE)" \
  https://api.bugfix.es/v1/symbols
```

- Use the agent's key and secret, the same agent as the mobile key. These are CI secrets: **never put them in the app**.
- `release` must match what the SDK sends exactly, which by default is `versionName (versionCode)`.
- The file can be up to 256 MB. The newest 20 releases are kept per agent.

## Limits

- **Android 10 and lower:** only JVM crashes are captured; ANRs and native crashes need `ApplicationExitInfo` (API 30+).
- **Native crashes:** sent with the system's description only. Their tombstone is a binary protobuf, which isn't parsed.
- **First launch:** ANRs and native crashes from before the SDK was added aren't sent.
- **Retries:** reports that couldn't be sent are retried with the next report or on the next launch, not when the network returns.

## Development

```sh
./gradlew build   # compile, lint, and unit + Robolectric tests
./gradlew publishToMavenLocal
```

Releases publish to Maven Central from a `v*` tag (`.github/workflows/publish.yml`).
