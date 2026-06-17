# ADR-0007: Android Jetpack Compose + Material 3 UI dependency

- **Status**: Accepted (maintainer go-ahead given 2026-06-17)
- **Date**: 2026-06-17
- **Source**: `docs/reports/frontend-ux-improvement-plan-handoff-2026-06-17.md`
  (§4 Track B, "Dependency gate"); ADR-0005 §3 (new-dependency review);
  HYP-M6-008 dependency/license audit boundary.
- **Supplements**: ADR-0005 (does not supersede it).

## Context

The Android companion currently hand-builds its entire UI from classic Android
Views in a single 1533-line `MainActivity.kt` (LinearLayout / TextView / Button
+ `GradientDrawable`). There are **no runtime dependencies** today: the release
runtime is `kotlin-stdlib` (+ JetBrains annotations) only; JUnit is test-only
(confirmed by HYP-M6-008). Color tokens are companion constants, the timeline
and connection summary are hardcoded demo data, and real output is routed to a
monospace debug `TextView`.

The frontend UX plan (Track B) calls for rebuilding the Android UI with
**Jetpack Compose + Material 3** — a locked decision in the handoff (§0). This
is the new rendering host; the visual language is unchanged (the existing dark
tokens are ported to a Compose `ColorScheme` + `Typography`).

ADR-0005 §3 requires that new runtime dependencies be reviewed before adoption,
and `CLAUDE.md` requires an ADR + explicit confirmation before adding any
dependency. Compose is a large new dependency surface, so it gets its own ADR.

## Decision

### 1. Adopt Jetpack Compose + Material 3 for the Android UI

Compose replaces the classic-View UI as the rendering host. The visual design
(dark tokens, typography, mono-for-auditable-detail) is reused, not redesigned.

### 2. Dependency footprint

All additions are AndroidX / Jetbrains artifacts under **Apache-2.0** (the same
family already accepted for the Gradle wrapper and protocol artifacts). Versions
are pinned via the Compose BOM so the surface is a single managed cluster.

| Artifact | Purpose | License |
|---|---|---|
| `org.jetbrains.kotlin.plugin.compose` (Gradle plugin, ships with Kotlin 2.x) | Compose compiler | Apache-2.0 |
| `androidx.compose:compose-bom` | Version alignment (BOM, no code) | Apache-2.0 |
| `androidx.compose.ui:ui`, `:ui-graphics` | Core runtime + graphics | Apache-2.0 |
| `androidx.compose.ui:ui-tooling-preview` | `@Preview` support | Apache-2.0 |
| `androidx.compose.material3:material3` | Material 3 components | Apache-2.0 |
| `androidx.activity:activity-compose` | `setContent` host | Apache-2.0 |
| `androidx.lifecycle:lifecycle-runtime-compose`, `:lifecycle-viewmodel-compose` | `collectAsStateWithLifecycle`, VM-scoped state holder | Apache-2.0 |
| `androidx.compose.ui:ui-tooling` (**debug-only**) | Preview tooling | Apache-2.0 |

No GPL/AGPL, proprietary SDK, or ambiguous-license artifact is introduced. No
new network, analytics, crash-reporting, or cloud dependency is added — the
v1 safety boundaries (no telemetry, no cloud relay, LAN-only) are unaffected.

### 3. Boundaries preserved

- No new product features; this is UI realization within ADR-0001 v1 scope.
- No telemetry/analytics transitive dependency is enabled.
- The version catalog (`gradle/libs.versions.toml`) remains the single source
  of dependency versions; the Compose BOM pins the cluster.

### 4. Formalization follow-ups

- Update HYP-M6-008's dependency/license audit to list the Compose cluster
  (Apache-2.0) once the deps land.
- `NOTICE` material for the Apache-2.0 cluster is part of the existing ADR-0005
  release-readiness step, not a blocker for development builds.

### 5. Gate: explicit maintainer confirmation required

Per ADR-0005 §3 and `CLAUDE.md`, the dependencies in §2 are **not added** until
the maintainer gives explicit go-ahead. The maintainer approved on 2026-06-17;
this ADR is now Accepted, and the `build.gradle.kts` + `libs.versions.toml`
edits and the Track B work (B0→B5) proceed.

## Consequences

- Positive: live-state UI, real empty/loading/error states, declarative screens,
  `@Preview`-driven iteration, parity with the macOS realization.
- Cost: larger APK and build graph; Compose compiler tied to the Kotlin version
  (already 2.3.21, compatible).
- Reversibility: low once screens are ported, but the protocol/transport/trust
  layers are UI-agnostic and unaffected, so the blast radius stays in the UI tree.
