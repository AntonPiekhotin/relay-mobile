# Setup and Build

Toolchain, scaffolding, and build configuration.

---

## 1. Prerequisites

| Tool | Version | Notes |
|---|---|---|
| macOS | — | **Required.** iOS cannot be built on Linux or Windows. |
| Xcode | Latest stable | Includes the iOS SDK and simulators |
| Android Studio | Latest stable | With the Kotlin Multiplatform plugin |
| JDK | 17+ | |
| Kotlin | 2.x | |
| Gradle | 8.x / 9.x | Wrapper committed to the repo |
| CocoaPods | Latest | Only if using `webrtc-kmp` (phase 6) |

Verify the environment with JetBrains' `kdoctor` before debugging build failures — it catches most misconfiguration.

---

## 2. Scaffolding

**Use the wizard at `kmp.jetbrains.com`** rather than configuring by hand. It generates working Gradle configuration for iOS targets, which is fiddly to get right manually.

Select: **Android + iOS**, with **shared UI** (Compose Multiplatform).

Resulting structure:

```
relay-mobile/
├── composeApp/
│   ├── src/
│   │   ├── commonMain/kotlin/dev/relay/
│   │   ├── commonMain/composeResources/     # images, strings, fonts
│   │   ├── commonTest/
│   │   ├── androidMain/
│   │   └── iosMain/
│   └── build.gradle.kts
├── iosApp/
│   ├── iosApp.xcodeproj
│   └── iosApp/
├── docs/
├── gradle/libs.versions.toml
└── settings.gradle.kts
```

**Start with one module.** Split only when build times or ownership make it necessary — and when you do, remember iOS supports only one framework per app, requiring an umbrella module that re-exports the rest.

---

## 3. Dependencies

Use a version catalogue. Check current versions when adding — the KMP ecosystem moves quickly and pinned versions in docs go stale.

```toml
# gradle/libs.versions.toml
[versions]
kotlin = "2.x"
ktor = "3.x"
sqldelight = "2.x"
koin = "4.x"
coroutines = "1.x"
serialization = "1.x"

[libraries]
ktor-core        = { module = "io.ktor:ktor-client-core",        version.ref = "ktor" }
ktor-websockets  = { module = "io.ktor:ktor-client-websockets",  version.ref = "ktor" }
ktor-contentneg  = { module = "io.ktor:ktor-client-content-negotiation", version.ref = "ktor" }
ktor-json        = { module = "io.ktor:ktor-serialization-kotlinx-json", version.ref = "ktor" }
ktor-okhttp      = { module = "io.ktor:ktor-client-okhttp",      version.ref = "ktor" }  # android
ktor-darwin      = { module = "io.ktor:ktor-client-darwin",      version.ref = "ktor" }  # ios
sqldelight-runtime = { module = "app.cash.sqldelight:runtime",   version.ref = "sqldelight" }
sqldelight-android = { module = "app.cash.sqldelight:android-driver", version.ref = "sqldelight" }
sqldelight-native  = { module = "app.cash.sqldelight:native-driver",  version.ref = "sqldelight" }
sqldelight-coroutines = { module = "app.cash.sqldelight:coroutines-extensions", version.ref = "sqldelight" }
koin-core        = { module = "io.insert-koin:koin-core",        version.ref = "koin" }
koin-compose     = { module = "io.insert-koin:koin-compose",     version.ref = "koin" }
coroutines-core  = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
turbine          = { module = "app.cash.turbine:turbine", version = "1.x" }
```

**Library choices and why:**

| Need | Choice | Why |
|---|---|---|
| HTTP + WebSocket | **Ktor client** | One library for both; native engines per platform |
| Serialization | **kotlinx.serialization** | Compile-time, no reflection (required on Native) |
| Database | **SQLDelight** | SQL-first, typed queries, Flow support. Room KMP is a viable alternative if the Android-style API is preferred |
| DI | **Koin** | Simple, KMP-native, no code generation |
| UUID | **kotlin.uuid** (stdlib) or **benasher44/uuid** | Needed for `clientMsgId` |
| Time | **kotlinx-datetime** | |
| Testing flows | **Turbine** | |
| Logging | **Napier** or **Kermit** | |

---

## 4. Source set wiring

```kotlin
kotlin {
    androidTarget()
    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.ktor.core)
            implementation(libs.ktor.websockets)
            implementation(libs.ktor.contentneg)
            implementation(libs.ktor.json)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
            implementation(libs.koin.core)
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.components.resources)
        }
        androidMain.dependencies {
            implementation(libs.ktor.okhttp)
            implementation(libs.sqldelight.android)
        }
        iosMain.dependencies {
            implementation(libs.ktor.darwin)
            implementation(libs.sqldelight.native)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.turbine)
        }
    }
}

sqldelight {
    databases {
        create("RelayDb") { packageName.set("dev.relay.db") }
    }
}
```

`isStatic = true` avoids dynamic framework signing complications on iOS.

---

## 5. Configuration

Backend URLs must not be hardcoded. Use a build-config approach with per-environment values:

```kotlin
// commonMain
interface AppConfig {
    val apiBaseUrl: String     // https://host/api
    val wsUrl: String          // wss://host/ws
    val keycloakUrl: String
}
```

Inject via Koin, with different instances for debug and release. Local development against a Compose backend typically means `10.0.2.2` on the Android emulator and `localhost` on the iOS simulator — these differ, so make it configurable rather than hardcoding either.

**Never commit secrets.** No Keycloak client secrets, no FCM service account keys in the repo.

---

## 6. Commands

```bash
./gradlew :composeApp:assembleDebug                  # Android APK
./gradlew :composeApp:installDebug                   # install to device
./gradlew allTests                                   # all tests
./gradlew :composeApp:iosSimulatorArm64Test          # iOS unit tests
./gradlew :composeApp:generateSqlDelightInterface    # regenerate DB code
./gradlew ktlintFormat detekt                        # lint
kdoctor                                              # diagnose environment
```

iOS app: open `iosApp/iosApp.xcodeproj` in Xcode and run. The shared framework builds automatically as a build phase — do not run `embedAndSignAppleFrameworkForXcode` manually.

**After editing any `.sq` file**, regenerate before the code will compile.

---

## 7. Build gotchas

| Symptom | Cause |
|---|---|
| iOS build fails after adding a dependency | Dependency lacks an iOS target — check Maven Central for `-iosarm64` artifacts |
| "Framework not found Shared" | Gradle build phase did not run; clean build folder in Xcode |
| Simulator works, device fails | Missing `iosArm64` target, or signing configuration |
| Slow incremental builds | Expected on Native; K2 improved this substantially but iOS builds remain slower than Android |
| CocoaPods errors | Run `pod install` in `iosApp/` after Gradle sync |

**Every dependency must support all targets.** A JVM-only library added to `commonMain` breaks the iOS build, often with an unhelpful error. Verify iOS artifacts exist before adding anything.

---

## 8. CI

- Android builds and `commonTest` run on Linux runners — cheap
- **iOS builds require macOS runners**, which cost significantly more
- Run `commonTest` on every push; run iOS builds on PRs to main and on release branches
- Cache the Gradle and Kotlin/Native dependency directories aggressively; cold Native builds are slow

A reasonable split: fast Linux job for lint + `commonTest` + Android assembly on every push, and a slower macOS job for the iOS build gated to PRs.
