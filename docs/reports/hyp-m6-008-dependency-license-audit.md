# HYP-M6-008 Dependency And License Audit

- **Date**: 2026-06-12
- **Status**: audit complete; public-release license gate remains blocked until
  formal root license files and a license map are added.
- **Tracker**: HYP-M6-008

## Verdict

No GPL, AGPL, proprietary, or unknown-license runtime dependency was found in
the current Android release runtime, macOS SwiftPM package, protocol tooling, or
repo-vendored binary scan.

This is not a public-release pass yet. ADR-0005 intentionally records the
license decision but does not add formal root license files. Before a public
beta/release, the project still needs canonical license texts, a top-level
license map, any required NOTICE material, and SPDX/header hygiene.

## Evidence Commands

```bash
rg --files | rg '(^|/)(Package\.resolved|build\.gradle\.kts|settings\.gradle\.kts|libs\.versions\.toml|gradle-wrapper\.properties|LICENSE|NOTICE|COPYING|THIRD_PARTY|pom\.xml|package\.json|requirements\.txt|pyproject\.toml|Gemfile|Cargo\.toml|go\.mod)$'
find . -maxdepth 6 -type f \( -name '*.jar' -o -name '*.aar' -o -name '*.so' -o -name '*.dylib' -o -name '*.framework' -o -name '*.xcframework' \)
shasum -a 256 apps/android/gradle/wrapper/gradle-wrapper.jar
cd apps/android && ./gradlew :app:dependencies --configuration releaseRuntimeClasspath
cd apps/android && ./gradlew :app:dependencies --configuration releaseUnitTestRuntimeClasspath
cd apps/android && ./gradlew :app:dependencies
```

The initially guessed configuration
`testDebugUnitTestRuntimeClasspath` does not exist in this Android Gradle
project; Gradle reported: `configuration 'testDebugUnitTestRuntimeClasspath'
not found in configuration container for project ':app'`. The audit then used
the actual `releaseUnitTestRuntimeClasspath` configuration.

## Dependency Surface

### Android Release Runtime

| Dependency | Version | Scope | License evidence | Result |
|---|---:|---|---|---|
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.3.21 | release runtime | Local POM declares `Apache-2.0` | Compatible |
| `org.jetbrains:annotations` | 13.0 | transitive release runtime | Local POM declares Apache Software License 2.0 | Compatible |

Release runtime dependency report:

```text
releaseRuntimeClasspath
\--- org.jetbrains.kotlin:kotlin-stdlib:2.3.21
     \--- org.jetbrains:annotations:13.0
```

### Android Unit Test Runtime

| Dependency | Version | Scope | License evidence | Result |
|---|---:|---|---|---|
| `junit:junit` | 4.13.2 | unit test only | Local POM declares Eclipse Public License 1.0 | Compatible for test-only use |
| `org.hamcrest:hamcrest-core` | 1.3 | transitive unit test only | Parent POM declares New BSD License | Compatible for test-only use |
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.3.21 | unit test runtime | Apache-2.0 | Compatible |
| `org.jetbrains:annotations` | 13.0 | transitive unit test runtime | Apache-2.0 | Compatible |

Unit-test dependencies do not ship in the Android release artifact.

### Android Build-Time Tooling

| Tooling | Version | Scope | License evidence | Result |
|---|---:|---|---|---|
| Android Gradle Plugin `com.android.tools.build:gradle` | 8.13.0 | build-time | Local POM declares Apache Software License 2.0 | Compatible |
| Kotlin Android Gradle plugin | 2.3.21 | build-time | Local POM declares Apache-2.0 | Compatible |
| Kotlin Gradle plugin | 2.3.21 | build-time | Local POM declares Apache-2.0 | Compatible |
| Gradle wrapper jar | 8.14.3 distribution path | repo-vendored wrapper binary | `gradle-wrapper.jar` SHA-256 `7d3a4ac4de1c32b59bc6a4eb8ecb8e612ccd0cf1ae1e99f66902da64df296172`; upstream Gradle wrapper notices must be included in release license map | Needs formal notice mapping |

### macOS SwiftPM

`apps/macos/Package.swift` declares only local targets. There is no
`Package.resolved` and no external SwiftPM package dependency.

### Scripts And Protocol Tooling

The current scripts use shell, Ruby standard-library YAML, and Python standard
library code. No `requirements.txt`, `pyproject.toml`, `Gemfile`,
`package.json`, `Cargo.toml`, or `go.mod` exists.

### Vendored Binary Scan

The only repo-vendored binary found by the scan is:

```text
./apps/android/gradle/wrapper/gradle-wrapper.jar
```

No vendored `.aar`, `.so`, `.dylib`, `.framework`, or `.xcframework` files were
found.

## Release Blockers

These are not third-party incompatibilities, but they block claiming release
license readiness:

1. Add canonical root license files for MPL-2.0, Apache-2.0, and CC-BY-4.0.
2. Add a top-level license map explaining which tree/license applies:
   app source, protocol specs/schemas/vectors, docs, third-party files, and
   generated artifacts.
3. Add or explicitly waive NOTICE material after checking the Gradle wrapper and
   Apache-2.0 tooling notices.
4. Add SPDX identifiers or another documented header policy for new source/docs.
5. Decide DCO vs CLA before accepting external contributions.
6. Re-run this audit after any scanner, QR, crash-reporting, analytics,
   packaging, or updater dependency is added.

## Result For Tracker

HYP-M6-008 should remain blocked until the release blockers above are resolved.
The current dependency set itself does not introduce a GPL/AGPL/proprietary
blocker.
