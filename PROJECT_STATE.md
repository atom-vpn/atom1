# Atom VPN — Project State

> Living handoff document. Update this file whenever repository work changes the project state, decisions, validation status, or next action.
> Last reviewed: 2026-11-22
> Repository: https://github.com/atom-vpn/atom1

## 1. Product goal

Build Atom VPN as a standalone Android VPN application with Atom identity.

Non-negotiable requirements:
- Preserve the existing connection engine and its capabilities.
- Preserve routing, per-app routing, exit selection, connection lifecycle, reconnect behavior, and native transport behavior.
- Remove legacy product naming from the user-facing application and product documentation.
- Do not remove compatibility identifiers required to migrate existing installations unless a safe replacement migration has been implemented and tested.

## 2. Active development baseline

- Active development branch: `pow-standalone`
- Last verified branch HEAD (2026-11-21): `5b78e6eb7318d3f507408d959da9db60ecb2cf4a`
- Default repository branch: `main` (not the current development baseline).
- Do not assume `main` contains the latest standalone-engine work.
- Do not rename or delete `pow-standalone` while open PRs target it. Plan branch/workflow migration only after dependent PRs are closed and the new base is verified.

## 3. Pull request ledger

Repository PR list: https://github.com/atom-vpn/atom1/pulls

| PR | Change | State at last review | Notes |
|---|---|---|---|
| [#1](https://github.com/atom-vpn/atom1/pull/1) | Atom engine extraction and internal rename | Merged | Included refreshed Atom UI and engine/service/package/tooling rename. PR description reports device smoke test: install, connect, traffic routing, exit-country/IP change. |
| [#2](https://github.com/atom-vpn/atom1/pull/2) | Final Atom naming cleanup | Merged | Removed obsolete visible/log naming and old automatic-region alias. |
| [#3](https://github.com/atom-vpn/atom1/pull/3) | Legacy network preference migration | Merged | Type-preserving migration to Atom preference keys; legacy source keys retained for compatibility. |
| [#4](https://github.com/atom-vpn/atom1/pull/4) | Atom-owned HEV JNI binding | Merged | Rebuilt HEV native library for Android ABIs and removed obsolete Java forwarding bridge. Device-level verification was requested in PR validation notes. |
| [#5](https://github.com/atom-vpn/atom1/pull/5) | Legacy peer-cache migration correction | Merged | Prevents stale peer hints from being restored after reset; preserves other migration data. |
| [#6](https://github.com/atom-vpn/atom1/pull/6) | VPN service destruction cleanup | Merged | Adds bounded emergency resource-release fallback when Android destroys/revokes the service or normal stop fails. |
| [#7](https://github.com/atom-vpn/atom1/pull/7) | Aether reference release on emergency teardown | Open | Base: `pow-standalone`; head: `fix-emergency-aether-detach`; head SHA: `4664958c02fcc3b48534e4bd1a8c1808f32f1d7b`. |

### PR #7 validation snapshot

- GitHub Actions run: [#44940039875](https://github.com/atom-vpn/atom1/actions/runs/44940039875)
- Result at last review: successful.
- Build/validation success does **not** prove runtime emergency teardown correctness on a physical device.
- Required before treating the change as fully validated: device testing of emergency release, reconnect, and normal stop/start behavior.
- Do not mark PR #7 merged unless GitHub confirms it is merged.

## 4. Confirmed architecture and identity

Previously audited on `pow-standalone`:
- Android application ID: `com.atomvpn.mobile`
- Android namespace: `com.atomvpn.mobile`
- Engine namespace: `com.atomvpn.mobile.engine`
- Engine implementation package: `com.atomvpn.mobile.engine.atom`
- App label: `Atom VPN`
- Main VPN service: `AtomVpnService`

These values are a snapshot, not a substitute for re-checking the current tree before future refactors.

## 5. Known compatibility constraints

Some legacy strings are data-migration identifiers, not product branding. Examples found in the prior source audit include:
- SharedPreferences migration keys such as `uac_pow_engine_v1` and `uac_pow_dns_warm`.
- Legacy engine configuration/cache filenames such as `uac-pow-*.toml`, `uac-pow-*.json`, and the `uac-pow` directory.
- Legacy HEV JNI symbols may require a compatibility bridge depending on the exact native library build.

Do not delete or rename these blindly. First trace every reader/writer and migration direction, then implement and test a safe migration if the old identifiers must disappear from active source.

The product requirement is that legacy names must not appear in the user-facing app, UI, notifications, or product-facing documentation. Internal compatibility code may retain old identifiers when required to preserve existing user data.

## 6. Known cleanup items from the previous source audit

The following were reported as remaining references on the audited `pow-standalone` tree. Re-check current files before editing because PRs may have changed them:
- `README.md`: historical/technical references and branch name.
- `docs/EXTRACTION_STATUS.md`: historical references and branch name.
- `tools/import_engine_artifacts.ps1`: branch references.
- `.github/workflows/atom-engine-build.yml`: branch trigger/reference.
- Engine migration comments and legacy preference/file identifiers in `AtomEngineStore.kt`, `AtomCoreConfig.kt`, and `AtomDnsWarm.kt`.

Branch naming and workflow updates must be coordinated with PR #7 and any other open PRs. Do not break CI triggers during a branch rename.

## 7. Next actions — recommended order

1. Refresh GitHub state: active branch HEAD, all open/closed PRs, checks, workflow runs, and relevant branch relationships. This file can become stale.
2. Test the current install on a physical Android device before merging PR #7:
   - Normal connect and disconnect.
   - Repeated connect/disconnect cycles.
   - Android service destruction/revocation while connected.
   - Emergency teardown followed by a fresh connection.
   - Confirm no stale VPN state, stuck service, leaked session, or blocked reconnect.
   - Verify traffic routing and selected exit/IP behavior after reconnect.
3. If device tests pass and the diff/checks remain valid, merge PR #7 into `pow-standalone`. If tests fail, fix the issue in a focused PR and retest.
4. After PR #7 is resolved, establish a stable Atom-named development branch and update CI/scripts/docs consistently. Preserve the current baseline until the new branch is verified.
5. Re-audit the full repository for legacy branding, migration compatibility, UI consistency, build configuration, and generated artifacts.
6. Run clean Universal and ARM64 builds, install the resulting APKs, and complete regression tests for connection, routing, exit selection, reconnect, and service teardown.
7. Update this document with exact commit SHAs, PR links, test device/Android version, test results, and the next concrete task.

## 8. Working rules for future sessions

- Always inspect the live GitHub state before making code or merge decisions. Never assume this document is current.
- Use `pow-standalone` as the development baseline until an explicit, verified branch transition is recorded here.
- Make focused changes; avoid combining unrelated refactors.
- Before edits, inspect the relevant source and callers. After edits, run the narrowest relevant tests plus the Android build workflow.
- Never claim device validation based only on CI.
- Never merge, delete, rename, or rewrite branch history without explicit user approval.
- After every meaningful repository change, update this file in the same branch with the resulting commit/PR/test state.
- Record unresolved questions and blockers explicitly rather than guessing.

## 9. New-chat handoff prompt

Paste this into a new conversation:

> Continue the Atom VPN project in repository https://github.com/atom-vpn/atom1. First read `PROJECT_STATE.md`, then independently refresh the live GitHub state (branches, recent commits, all PRs, checks, and workflow runs). Treat GitHub as the source of truth if this file is stale. Continue from the recorded next action, preserve existing engine behavior and migration compatibility, and do not merge/delete/rename branches or make destructive changes without asking me. Update `PROJECT_STATE.md` after every meaningful repository change.


## 17. Legacy identifier isolation (2026-11-22)

- Created branch `atom-access-clean-identifiers` from the verified `pow-standalone` baseline at `1772d6246020aca84188d724420d949d600d136e`. This work is intentionally separate from the open UI integration PR #10.
- Moved historical preference names and file/directory names into one compatibility boundary: `engine/src/main/kotlin/com/atomvpn/mobile/engine/atom/LegacyInstallMigration.kt`.
- `AtomCoreConfig`, `AtomEngineStore`, and `AtomDnsWarm` now call the compatibility boundary and contain no legacy product identifiers.
- The migration remains one-way and type-preserving. Existing settings, identity material, DNS warm-up data, endpoint caches, and learned path files continue to be copied into Atom-owned storage locations. The old source values are retained; no destructive migration or deletion was introduced.
- Updated technical documentation wording to describe the previous bridge generically.
- No transport, routing, JNI, native binary, connection coordinator, or VPN lifecycle behavior was intentionally changed.
- Validation status: source refactor committed; PR #11 is ready for review to trigger CI; Android build and device regression tests are pending. Do not claim runtime validation until CI and device testing complete.
- PR #7 remains open against `pow-standalone`; PR #10 remains open and separate. Neither PR was merged or modified by this work.

## 18. Atom Access combined integration (2026-11-22)

- Created test integration branch `atom-access-integrated` from `atom-access-clean-identifiers` (PR #11 head), preserving the legacy install migration and its Kotlin compile fix.
- Applied the UI/settings source files from `ui-integration` (PR #10): `MainActivity.kt`, `AtomVpnService.kt`, `AndroidManifest.xml`, `AtomAppearanceStore.kt`, `AtomLanguageStore.kt`, and `AtomRemoteConfig.kt`.
- This branch is the combined candidate for CI validation; it does not merge either source PR into `pow-standalone`.
- PR #10 and PR #11 remain open and unmerged. No public release is intended.
- Combined test PR: [#14](https://github.com/atom-vpn/atom1/pull/14), targeting `pow-standalone` for CI only; PR #10 and #11 remain open/unmerged.
- GitHub Actions run [#45043481083](https://github.com/atom-vpn/atom1/actions/runs/45043481083) was queued for the combined Universal/ARM64 build at 2026-11-22 16:22 UTC. Await its result and inspect APK artifacts, then perform device regression tests. No device validation is claimed.
- Engine migration compatibility remains from PR #11. UI settings/theme/language/logs/About/notification changes are from PR #10. No intentional changes to transport, routing, JNI/native binaries, coordinator, or VPN lifecycle.


## 19. Atom Access combined candidate — initial device smoke test (2026-11-22)

- Development/reference branch for the combined UI + migration candidate: `atom-access-integrated`.
- Tested commit: `c60b27dc925bf75b851a206ce616f5b8eb8c8950`.
- Combined GitHub Actions build: [run #45043506104](https://github.com/atom-vpn/atom1/actions/runs/45043506104) — completed successfully; Universal and ARM64 debug APK artifacts were produced and validated by the workflow.
- User-confirmed physical-device smoke test on the combined ARM64 APK:
  - APK installation: successful.
  - New Atom Access settings UI: displayed correctly.
  - VPN connection: successful; user reported the connection test was OK on 2026-11-22.
- Validation scope is limited to installation, UI display, and a successful connection. Repeated connect/disconnect, traffic-routing/exit behavior, reconnect, service destruction/revocation, and emergency teardown have not been confirmed on this combined build.
- This is an internal/test candidate, not a public release. PR #14 remains open and unmerged; PR #10 and #11 remain open and unmerged. No PR was merged and no release was published.
- Next step: retain this commit as the initial device-smoke-tested baseline; before further changes, refresh the live branch/PR state and continue from `atom-access-integrated`. For any new build, record its exact branch, SHA, architecture, CI run, and device-test scope.

## 20. Branch and PR separation policy (live inventory reviewed 2026-11-22)

### Tested combined baseline
- Use `atom-access-integrated` as the sole starting point for new Atom Access product work.
- Current branch HEAD after recording the device smoke test: `6dcc2c0aae782216110b4f221ea5602645f4d78f`.
- Device-confirmed scope: ARM64 install, new settings UI display, and successful VPN connection. Do not infer broader regression coverage.

### Open source/change PRs — keep isolated until individually evaluated
- PR #7 / branch `fix-emergency-aether-detach`: Aether emergency teardown reference cleanup. Separate engine change; requires focused device validation before integration.
- PR #8 / branch `fix-auto-unknown-masque-h2`: unknown-carrier MASQUE H2 fallback. Separate transport-selection change; requires focused network/device validation before integration.
- PR #9 / branch `fix-exit-ip-startup-retry`: exit-IP lookup retry/backoff. Separate startup/retry change; requires focused device validation before integration.
- PR #10 / branch `ui-integration`: source UI/settings implementation. Its changes are already represented in the tested combined branch; retain PR as source history/review only. Do not merge or separately apply it to the combined branch.
- PR #11 / branch `atom-access-clean-identifiers`: source migration/identifier-isolation implementation. Its changes are already represented in the tested combined branch; retain PR as source history/review only. Do not merge or separately apply it to the combined branch.
- PR #14 / branch `atom-access-integrated`: integration/build-validation PR. It is the PR associated with the combined candidate; keep open/unmerged unless the user explicitly authorizes a merge decision.

### Closed experimental PRs
- PR #12 and PR #13 are closed, unmerged integration experiments. Do not use their heads as build sources and do not reopen unless specifically needed.

### Branches not to use as the default build source
- `pow-standalone`: underlying engine baseline/PR target, not the current tested Atom Access product branch.
- `main`: repository default branch; not the tested combined product baseline.
- `ui-integration` and `atom-access-clean-identifiers`: source branches already represented in `atom-access-integrated`; do not build product APKs from them.
- Other historical/fix branches remain isolated; do not delete them or merge them automatically.

### Anti-mix rules
1. All new product work starts from `atom-access-integrated` only.
2. Never combine multiple open engine PRs in one step. Review and integrate at most one focused change at a time, then build and device-test before proceeding.
3. Before integrating any PR, compare its base/head against the current integrated HEAD and inspect the exact diff for overlap/conflicts.
4. Never cherry-pick, merge, close, delete, or rewrite any branch/PR without explicit user approval.
5. Every APK handoff must state branch, full commit SHA, CI run, architecture, and whether physical-device testing has passed.


## 21. PR #8 review and isolated Atom Access port (2026-11-22)

- User approved technical review/preparation of PR #8 and requested that all work be recorded for future chat continuity.
- Source PR: [#8](https://github.com/atom-vpn/atom1/pull/8), branch `fix-auto-unknown-masque-h2`, head `6ba02cb5b0a55a5af330e6d38ca0fbc9215aa601`, base `pow-standalone` at `1772d6246020aca84188d724420d949d600d136e`. It remains open and unmerged; it was not modified.
- Live comparison of PR #8 against `atom-access-integrated` showed diverged history (PR branch 7 commits ahead / 19 behind). Do not merge or cherry-pick the whole PR branch.
- Important compatibility finding: the PR #8 version of `AtomCoreConfig.kt` contains inline legacy file-migration implementation, while the integrated product branch delegates these paths to `LegacyInstallMigration`. Taking the old file wholesale would undo part of PR #11's migration isolation. The change must be ported narrowly.
- PR #8's intended behavior is limited to automatic carrier mode with an unknown carrier class (including Wi-Fi): retain the existing automatic protocol ladder, but use dual-stack (`both`) MASQUE HTTP/2 (`h2`). Known Irancell remains IPv4/H3; known MCI remains dual-stack/H2 with its existing fixed MASQUE policy.
- CI state on source PR #8 head at review time: associated workflow run [#45002356987](https://github.com/atom-vpn/atom1/actions/runs/45002356987) was `completed / cancelled`; this is not a passing validation. Physical-device verification on the target Wi-Fi is still required.
- Created isolated branch `atom-access-pr8-masque-h2` directly from the current integrated HEAD `b890020da7307ef2ae001c4b65137687e5678ea8`. This branch is for the narrow Atom Access port and validation only; `atom-access-integrated` was not changed.
- Ported only the unknown-carrier plan change into the integrated version of `AtomCoreConfig.kt`, preserving its `LegacyInstallMigration` delegation.
- Added focused engine unit tests for unknown, Irancell, and MCI automatic carrier behavior in `engine/src/test/kotlin/com/atomvpn/mobile/engine/atom/AtomCoreConfigTest.kt`; added JUnit 4.13.2 as the engine test dependency.
- Updated `.github/workflows/atom-engine-build.yml` on the isolated branch so pushes to this validation branch run CI, PRs targeting `atom-access-integrated` can run CI, and `:engine:testDebugUnitTest` runs before the Universal/ARM64 APK builds.
- Current isolated branch HEAD before this state-file update: `0cc86adaa10fea263406e86e0b4e71f54e2c3abf`. Refresh the branch ref after this commit to obtain the new HEAD.
- Validation status: source/test/workflow changes are committed to the isolated branch; CI has not yet run on this branch. No APK is claimed as built, and no device test has been performed.
- No merge, cherry-pick, release, or change to the tested integrated branch was performed.
- Next action: verify this branch's resulting diff against `atom-access-integrated`, ensure CI triggers and unit tests pass, inspect APK artifacts, then provide an ARM64 test build for the user's Wi-Fi device verification. Only after that review device results and ask before any integration/merge.


## 22. PR #8 isolated port — CI and ARM64 artifact ready (2026-11-23)

- Isolated branch: `atom-access-pr8-masque-h2`.
- CI workflow run [#45064726842](https://github.com/atom-vpn/atom1/actions/runs/45064726842) completed successfully for code/workflow commit `0cc86adaa10fea263406e86e0b4e71f54e2c3abf`.
- Successful CI steps include rebuilding HEV JNI for all four ABIs, running `:engine:testDebugUnitTest`, building Universal and ARM64 debug APKs, validating APK structure/manifest/alignment/signature, and uploading both artifacts.
- ARM64 artifact ID: `14771006713`; downloaded and extracted locally as `/mnt/data/atom-access-pr8-arm64/app-arm64-debug.apk`.
- ARM64 APK size: 58,568,833 bytes. SHA-256: `c294fe783322b3611833400332443ccf2c538fe8cb2b91dd8bced1dad681bc68`.
- Universal artifact ID: `14771505232`; retained on GitHub Actions.
- CI success is not physical-device validation. ARM64 APK is ready for user testing, especially automatic carrier mode on Wi-Fi/unknown carrier. Verify install, connect, traffic, disconnect, and reconnect; report any connection/log errors.
- The CI run was triggered at commit `0cc86adaa10fea263406e86e0b4e71f54e2c3abf`. A later documentation-only update may advance branch HEAD; refresh live HEAD before further code changes.
- No merge/cherry-pick/release was performed. `atom-access-integrated` remains unchanged; PR #8 source branch remains untouched.


## 23. User device test — PR #8 ARM64 build (2026-11-23)

- User installed the ARM64 APK from isolated branch `atom-access-pr8-masque-h2`; installation succeeded and the VPN tunnel connected.
- User then observed that after reconnect, the UI showed both upload and download speeds as zero while internet traffic continued to work.
- Treat this as a traffic-statistics/UI telemetry regression candidate, separate from tunnel establishment and data-plane connectivity. Do not alter MASQUE/transport policy to address it without evidence.
- Initial source trace:
  - `AtomVpnService.startTrafficSpeedMonitor()` samples `coordinator.tunStats()` once per second and publishes byte-rate deltas to `TrafficSpeedRepository`.
  - `AtomConnectionCoordinator.tunStats()` reads HEV/TUN native counters when `AtomTun2Socks` is running, otherwise it falls back to coordinator callback counters.
  - Negative counter deltas are clamped to zero in the service monitor, so a native counter reset/rebase or stale stats source around reconnect is one possible cause, not yet proven.
  - `connect()` and `disconnect()` both clear the displayed speed state; that alone explains a temporary zero during transition, but not a persistent zero while traffic continues.
- Physical test confirms install + tunnel + working internet after reconnect; it does not confirm the accuracy of speed telemetry, nor does it yet identify whether reconnect was manual disconnect/connect or an automatic/network-triggered reconnect.
- Next: clarify reconnect trigger; inspect native HEV stats semantics and monitor lifecycle, then make a focused fix on this isolated branch with tests and CI. Keep `atom-access-integrated` unchanged until the fix is validated and user authorizes integration.


## 24. Automatic reconnect traffic monitor finding (2026-11-23)

- User clarified the device behavior: when the app reconnects automatically, upload/download speeds remain unreported (zero); after the user manually disconnects and reconnects, speed reporting works.
- This narrows the issue to the automatic reconnect lifecycle rather than a general inability to read traffic counters.
- Source trace confirms:
  - `AtomConnectionCoordinator.beginNetworkRestore()` calls `publishReconnecting()`, which changes `ConnectionStateStore` from CONNECTED to CONNECTING.
  - `finishNetworkRestore()` later calls `publishConnected()`, which returns the state to CONNECTED without re-entering `AtomVpnService.connect()`.
  - `AtomVpnService.startTrafficSpeedMonitor()` currently samples only while the state remains CONNECTED. When automatic recovery changes the state to CONNECTING, that monitor exits and clears the displayed rates.
  - The automatic restore path does not call `startTrafficSpeedMonitor()` again when it publishes CONNECTED. Manual connect does call it after the coordinator connect completes.
- This is a strong, directly code-supported explanation for the user's exact manual-vs-automatic reconnect observation. Native counter reset/rebase is no longer the leading hypothesis, though it may still need consideration if telemetry remains incorrect after the lifecycle fix.
- Proposed focused fix: make the service's traffic monitor lifecycle observe connection-state transitions and restart sampling whenever the state returns to CONNECTED, including automatic network restore. Keep the transport policy, MASQUE, native libraries, routing, and coordinator reconnect behavior unchanged.
- No code fix has been applied yet. The finding and next action are recorded; implement the focused service-side fix only after user approval, then run CI and issue a new ARM64 test APK. Keep `atom-access-integrated` unchanged.
