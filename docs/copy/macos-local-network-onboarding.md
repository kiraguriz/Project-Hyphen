# Copy: macOS Local Network onboarding

- **Tracker**: HYP-M1-012 · **Plan**: §8.3 Local Network Privacy
- **Canonical for**: `LocalNetworkCopy` in `apps/macos/Sources/HyphenDiscovery/LocalNetworkOnboarding.swift` (a unit test guards the key promises; update both together)
- 中文版本随 M5 双语任务 (HYP-M5-008) 落地。

## Design rules (enforced in code)

1. **Never trigger the OS prompt on launch.** All local-network-touching actions (Bonjour advertise, browse) run behind `LocalNetworkOnboardingGate`: the user clicks an action → Hyphen explains → only "Continue" reaches the network API and thus the macOS Local Network prompt.
2. **Declining is fully supported.** "Not now" persists nothing; the explanation reappears next time. QR/manual pairing never requires the permission.
3. **The copy may not overpromise.** It states only what the code does: paired devices only, direct connections, no internet scanning, no upload of network information (frozen by ADR-0001).

## Explanation dialog

> **Allow Hyphen to find devices on your network?**
>
> Hyphen looks for your paired phone on the Wi‑Fi network you're on. macOS will ask for the "Local Network" permission next.
>
> Hyphen only looks for your own paired devices and connects to them directly — nothing leaves your network. Hyphen never scans the internet and never uploads anything about your network.
>
> You can also pair without this permission: scanning a QR code or typing your phone's address always works.
>
> Changed your mind later? System Settings → Privacy & Security → Local Network → enable Hyphen.
>
> [Continue] [Not now]

## Denial / repair states

| Situation | Surface | Copy |
|---|---|---|
| Permission denied, user clicks a discovery action | menu state line | "Local network access is off — pair with a QR code instead, or enable Hyphen in System Settings → Privacy & Security → Local Network." |
| Permission revoked while advertising | menu state line | "Stopped: local network access was turned off. QR/manual pairing still works." |
| macOS 15.x prompt never appeared (known platform quirk) | troubleshooting doc (HYP-M5-009) | toggle the permission off/on in System Settings; if the entry is missing, relaunch Hyphen and retry the action |

## On-device verification (manual test for this row)

1. Fresh state: `defaults delete dev.hyphen.HyphenApp` (or first run) → launch app → **no** LNP prompt at launch.
2. Click "Start advertising" → explanation dialog appears **before** any OS prompt.
3. "Not now" → nothing happens, no OS prompt; click again → explanation reappears.
4. "Continue" → advertising starts; the OS Local Network prompt (when macOS decides to show it — browse triggers it deterministically, advertise may not) appears only now.
5. Record results in the test log below.

```text
Date:
macOS version:
Steps 1–4 result:
Prompt appeared at step:
Issues:
```
