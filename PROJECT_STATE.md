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


## 25. Automatic reconnect traffic monitor fix (2026-11-23)

- User explicitly approved a focused fix on `atom-access-pr8-masque-h2`.
- Updated `app/src/main/java/com/atomvpn/mobile/AtomVpnService.kt`:
  - The service now observes `ConnectionStateStore.state` for traffic-monitor lifecycle as well as notification updates.
  - On every transition to CONNECTED, it starts a fresh traffic sampler if one is not already active. This includes coordinator-managed automatic network restoration, which does not call the service's manual `connect()` method.
  - On every non-CONNECTED state, it cancels and joins the previous sampler before clearing displayed rates, preventing the old sampler's cleanup from racing with a newly started sampler.
  - Sampling checks connection state again after each one-second delay before publishing a rate.
  - Removed the one-off monitor start from the manual connect completion path; monitor lifecycle is now driven by the shared connection-state flow for both manual and automatic connections.
- Intended scope is app-side telemetry lifecycle only. No changes to MASQUE, transport selection, native libraries, routing, TUN setup, or coordinator reconnect policy.
- Source commits:
  - `6c3f758b5cd453e55bb0bf29743903c1e73bb78f` — state-driven monitor lifecycle fix.
  - `db41972a67560a8d468d44f05a3ff148d38fb2f3` — remove unused import.
- Validation: source committed; CI/build and APK artifact validation pending. Physical-device test after automatic reconnect is pending.
- The tested product branch `atom-access-integrated` remains unchanged. No merge, cherry-pick, PR merge, or public release was performed.
- Next: inspect the workflow triggered by the branch push, verify unit tests and Universal/ARM64 builds, then provide the new ARM64 APK with exact SHA and test scope.


## 26. Automatic reconnect traffic speed — physical-device validation passed (2026-11-23)

- User installed the ARM64 debug APK built by CI run [#45131105891](https://github.com/atom-vpn/atom1/actions/runs/45131105891); installation succeeded.
- User performed the requested network interruption/automatic reconnect test and explicitly confirmed that upload/download speed reporting resumed correctly after automatic reconnect.
- This validates the specific regression addressed in section 25 on the user's physical device: traffic speed telemetry now recovers after automatic network restoration.
- CI run #45131105891 completed successfully. The workflow rebuilt HEV JNI for all four ABIs, ran engine unit tests, built Universal and ARM64 debug APKs, and passed APK structure/manifest/alignment/signature validation.
- ARM64 artifact ID: `14798401074`. CI head/code commit: `db41972a67560a8d468d44f05a3ff148d38fb2f3`.
- Validation scope is specifically APK installation and recovery of upload/download speed telemetry after an automatic reconnect. This does not imply full regression coverage for repeated connect/disconnect, exit-IP/routing, service destruction, emergency teardown, or all carrier/network combinations.
- `atom-access-integrated` remains unchanged. No merge, cherry-pick, PR merge, or public release was performed.
- Next decision: keep the validated fix isolated until the user explicitly authorizes a review/integration step. Before integration, refresh branch/PR state and inspect the exact diff against the current `atom-access-integrated` HEAD.


## 27. Integration review against atom-access-integrated (2026-11-23)

- Refreshed live refs before review:
  - `atom-access-integrated` HEAD: `b890020da7307ef2ae001c4b65137687e5678ea8`.
  - `atom-access-pr8-masque-h2` HEAD at review start: `a937a941c53bc21712bf9aca1f4e579d64228697`.
- GitHub compare result: experimental branch is 13 commits ahead and 0 behind the integrated branch; merge base is exactly `b890020da7307ef2ae001c4b65137687e5678ea8`. No integrated-branch commits need to be pulled into the experimental branch.
- Compare reports six changed paths:
  - `.github/workflows/atom-engine-build.yml`
  - `PROJECT_STATE.md`
  - `app/src/main/java/com/atomvpn/mobile/AtomVpnService.kt`
  - `engine/build.gradle.kts`
  - `engine/src/main/kotlin/com/atomvpn/mobile/engine/atom/AtomCoreConfig.kt`
  - `engine/src/test/kotlin/com/atomvpn/mobile/engine/atom/AtomCoreConfigTest.kt`
- Review findings:
  - AtomCoreConfig change is narrowly limited to the automatic unknown-carrier plan: preserve `OUTER_LADDER`, switch the generic MASQUE parameters from IPv4/H3 to dual-stack/H2. Existing Irancell and MCI policies remain unchanged in the diff.
  - Three unit tests cover unknown, Irancell, and MCI automatic carrier policy. The CI workflow runs these tests before building APKs.
  - AtomVpnService change makes traffic sampling follow CONNECTED state transitions and joins/cancels the previous sampler on non-CONNECTED states. User's physical-device automatic-reconnect test confirms speed telemetry resumes.
  - Workflow changes add the isolated branch push trigger, allow PR CI targeting `atom-access-integrated`, and run engine unit tests. No native/transport implementation files are changed.
  - The only additional dependency is JUnit 4.13.2 for engine unit tests.
- Validation: CI run [#45131105891](https://github.com/atom-vpn/atom1/actions/runs/45131105891) passed; user confirmed install and automatic-reconnect speed telemetry on device.
- Integration assessment: branch ancestry is clean and the code diff is focused, with no overlapping edits required on the integrated branch. Before merging, open a PR targeting `atom-access-integrated` and let GitHub calculate mergeability/checks against the live target. No PR was created in this review.
- Existing PR #14 remains an unrelated build-validation PR from `atom-access-integrated` to `pow-standalone`; it is open/unmerged. No PR was merged, no branch was rewritten, and `atom-access-integrated` remains unchanged.
- Next action: await explicit user approval before creating a draft/review PR or applying this change to `atom-access-integrated`. Merging remains a separate explicit approval.

## 28. Draft integration PR created (2026-11-23)

- User approved creating a draft PR for review; this approval does not authorize merging.
- Created draft PR [#15](https://github.com/atom-vpn/atom1/pull/15): `atom-access-pr8-masque-h2` → `atom-access-integrated`.
- PR title: `[Draft] Restore traffic speed monitoring after automatic reconnect`.
- PR creation snapshot:
  - Head SHA: `4b82eb56acd0da9d989f936abff179e3cca0804b`.
  - Base SHA: `b890020da7307ef2ae001c4b65137687e5678ea8`.
  - 14 commits, 6 changed files.
  - GitHub initially returned `mergeable: false`; treat mergeability as not yet established and inspect the PR's live mergeability/check status before any merge decision.
- PR #15 is open and draft, not merged. No merge was attempted. The product branch `atom-access-integrated` was not modified by PR creation.
- Existing PRs and source branches were not merged, closed, deleted, or rewritten.
- Next: inspect PR #15's checks and diff, resolve any conflicts or failed checks in the isolated branch only, and request separate explicit approval before marking ready or merging.


## 29. Repository branch/PR audit and PR #15 diff review (2026-11-23)

- User approved continuing the read-only audit and explicitly requested that every meaningful action/result be recorded so work can resume safely if the chat reaches its limit.
- Audit method: refreshed GitHub branch refs, all PR states, compare results against both `atom-access-integrated` and `pow-standalone`, PR #15 changed-file patches/commit list, and latest Actions run. No branch/PR mutation was performed during the audit.
- Current product reference at audit time: `atom-access-integrated` = `b890020da7307ef2ae001c4b65137687e5678ea8`. Experimental PR branch: `atom-access-pr8-masque-h2` = `be72a39125fc26c5ba902ebd0616f58644d11107`.
- Current PR #15 snapshot: open, draft, not merged; source `atom-access-pr8-masque-h2`, target `atom-access-integrated`; 15 commits, 6 changed files, +206/-12. Latest GitHub REST snapshot reports `mergeable=true` and `mergeable_state=unstable`; do not interpret this as approval to merge. Earlier PR creation snapshot had `mergeable=false`, so the value has since been recalculated.
- PR #15 changed-file review:
  - `app/src/main/java/com/atomvpn/mobile/AtomVpnService.kt`: state-driven traffic sampler start/stop; this is the user-device-confirmed automatic reconnect speed display fix.
  - `engine/src/main/kotlin/com/atomvpn/mobile/engine/atom/AtomCoreConfig.kt`: generic/unknown automatic carrier uses existing outer protocol ladder with dual-stack MASQUE H2; known Irancell and MCI branches remain unchanged in this patch.
  - `engine/src/test/kotlin/com/atomvpn/mobile/engine/atom/AtomCoreConfigTest.kt`: three tests for unknown, Irancell, and MCI policies.
  - `engine/build.gradle.kts`: JUnit 4.13.2 test dependency.
  - `.github/workflows/atom-engine-build.yml`: adds target/source branch triggers and runs engine unit tests before APK builds.
  - `PROJECT_STATE.md`: history/validation notes only.
- Latest CI for exact PR head `be72a39125fc26c5ba902ebd0616f58644d11107`: workflow run [#45134236877](https://github.com/atom-vpn/atom1/actions/runs/45134236877), completed successfully. Prior run #168 also succeeded for earlier code commit `db41972a67560a8d468d44f05a3ff148d38fb2f3`. Device validation remains the user's explicit pass for automatic-reconnect speed reporting on the earlier ARM64 artifact/code build; this does not extend device coverage to all PR #15 changes/carrier policies.
- PR #15 overlap assessment against the open focused PRs:
  - PR #8 (`fix-auto-unknown-masque-h2`) overlaps the `AtomCoreConfig.kt` behavior and unit tests. PR #15 already contains a narrow port on top of the current integrated code; do not merge PR #8 wholesale or apply its old branch on top.
  - PR #9 (`fix-exit-ip-startup-retry`) overlaps `AtomVpnService.kt`, and additionally changes `ExitIpInfoRepository.kt`. Its service diff must be reviewed against PR #15 before any selective port; do not merge its entire old branch.
  - PR #7 (`fix-emergency-aether-detach`) changes `AetherNative.kt` and `AtomConnectionCoordinator.kt`, which are not among PR #15's changed files. It is functionally separate by file scope, but still requires source review, lifecycle analysis, CI, and device validation before considering integration.
- Branch inventory findings (comparison to `atom-access-integrated`):
  - `atom-access-pr8-masque-h2`: 15 ahead / 0 behind; current PR #15 review branch.
  - `fix-auto-unknown-masque-h2`: diverged, 7 ahead / 19 behind; old PR #8 source, do not merge wholesale.
  - `fix-emergency-aether-detach`: diverged, 2 ahead / 20 behind; old PR #7 source, isolated engine lifecycle change.
  - `fix-exit-ip-startup-retry`: diverged, 4 ahead / 19 behind; old PR #9 source, overlaps service file.
  - `fix-vpn-service-destroy-cleanup`: diverged, 1 ahead / 21 behind; PR #6 was merged into `pow-standalone` (2026-11-21), not proof by itself that its intended behavior is fully represented/tested in the current product branch. Audit the actual code before deciding; do not reapply the branch.
  - `atom-access-clean-identifiers`: 0 ahead / 10 behind relative to integrated; no unique commits to bring forward.
  - `ui-integration` and `atom-access-independent-naming`: same head `bee3249ed16e864f7b1321b9a2b68115b9181fd6`, 36 ahead / 19 behind; large UI/source history, not a safe direct merge candidate.
  - `cloud-ui-review`: diverged, 1 ahead / 19 behind; includes separate UI files and a differently named `MainActivity (1).kt`; requires manual source/content review, not direct merge.
  - `atom-legacy-audit`: diverged, 4 ahead / 22 behind; overlaps legacy engine/config/docs; historical PR #5 was merged into `pow-standalone`, not to be reapplied blindly.
  - `atom-engine-rename`, `atom-final-cleanup`, `atom-hev-jni-standalone`, `atom-legacy-identifier-migration`, and `pow-standalone` are behind integrated with no unique commits in the comparison to integrated.
  - `main` is 319 commits behind integrated.
- PR state inventory refreshed: #15 open draft; #14, #11, #10, #9, #8, #7 open/unmerged; #12 and #13 closed/unmerged; #1 through #5 closed/merged into `pow-standalone` as reported by GitHub; #6 closed/merged into `pow-standalone`. PR #14 is from `atom-access-integrated` to `pow-standalone`, not an integration PR into the product branch.
- Safety decision: do not merge all branches or PRs. Keep `atom-access-integrated` as the sole product baseline. PR #15 remains draft pending separate review/approval; no merge, cherry-pick, close, deletion, force-push, or release was performed.
- Next actions, one at a time:
  1. Inspect PR #9's exact patch and compare its service changes with PR #15; determine whether its `ExitIpInfoRepository.kt` retry improvement is independently useful and safe to port.
  2. Separately inspect PR #7's exact Aether/coordinator lifecycle patch.
  3. Check PR #6's intended cleanup behavior in the current integrated source to avoid duplicate application.
  4. After all findings are recorded, present a proposed ordered integration plan. Ask for explicit approval before any code port/branch mutation; merging remains a separate approval.
- Continuity rule: after every meaningful source review, code change, CI run, artifact handoff, device test, or GitHub mutation, append a dated entry to this file on the active experimental branch and report the resulting commit SHA. Never rely on chat history alone for project state.


## 30. PR #9 exit-IP startup retry review (2026-11-23)

- User approved continuing the audit. This step was read-only source review followed by this documentation entry; no application code, PR metadata, or product branch was changed.
- Reviewed all three PR #9 changed paths using GitHub's changed-file list and exact file patches:
  - `app/src/main/java/com/atomvpn/mobile/AtomVpnService.kt`
  - `app/src/main/java/com/atomvpn/mobile/ExitIpInfoRepository.kt`
  - `PROJECT_STATE.md`
- PR #9 source branch: `fix-exit-ip-startup-retry`; PR target: `pow-standalone`; recorded PR head SHA: `570e22aaaf84c4e09756e0b9774a3d880cfc6f17`. PR remains open/unmerged per the latest recorded PR inventory.
- Exact behavior added by PR #9:
  - Replaces eight identical 2-second retries with bounded backoff delays of 2, 3, 5, 8, 10, 10, 10, and 10 seconds (58 seconds total waiting time, plus each lookup's own network timeouts).
  - Calls `ExitIpInfoRepository.refresh(socksPort, keepLoadingOnFailure = !isFinalAttempt)`; a successful lookup ends the retry loop, transient failures retain the loading UI state, and the last failed attempt exposes an error.
  - Changes `refresh` to return Boolean success and preserve cancellation by rethrowing `CancellationException`.
  - The lookup continues to use the local engine SOCKS proxy only. No direct phone-network fallback was introduced.
- Compared the service hunks against both `atom-access-integrated` and the PR #15 experimental branch:
  - PR #15's service change is the state-driven traffic-speed monitor lifecycle (the already device-verified automatic-reconnect telemetry fix).
  - PR #9's service hunk changes only the exit-IP retry loop and its retry-delay constants. It does not modify the traffic monitor implementation.
  - The two behavioral changes are separate by code region and appear selectively composable; do not merge PR #9's old branch wholesale because its base is `pow-standalone` and it is divergent from the product branch.
- Current `atom-access-integrated` `ExitIpInfoRepository.kt` does not contain the retry-aware Boolean-returning API; it still exposes `suspend fun refresh(socksPort: Int)` and publishes every failure immediately. The PR #9 repository implementation is therefore not already present in the product baseline.
- Review assessment: the bounded retry and transient-error suppression are a plausible isolated improvement for startup races where VPN CONNECTED precedes SOCKS readiness. The 58-second backoff is finite but can take longer in wall-clock time because each attempt may contact both lookup providers with their own timeouts. No direct-network leak was found in the reviewed diff.
- Validation evidence recorded for PR #9: Actions run [#45005071780](https://github.com/atom-vpn/atom1/actions/runs/45005071780) succeeded, including Universal/ARM64 APK builds, four-ABI HEV JNI rebuild, APK structure/manifest/alignment/signature checks, and artifact upload. Physical-device verification remains pending; CI success is not device validation.
- Recommendation: retain PR #9 as a candidate for a narrowly scoped selective port of the repository API plus the retry loop/constants onto a fresh branch based on current `atom-access-integrated`. Before porting, add focused tests for retry-state transitions/cancellation or otherwise document why the repository behavior is sufficiently covered; then run CI and request a device test specifically for exit-IP recovery after connect/startup. Do not change the existing PR #15 sampler behavior.
- No code was ported, no PR was merged/closed/retargeted, no branch was rewritten/deleted, and no public APK/release was made. `atom-access-integrated` remains unchanged.
- Next audit item: inspect PR #7's exact Aether reference-release/emergency-teardown patch and compare it with the current integrated coordinator/native lifecycle.


## 31. PR #7 emergency Aether detach review (2026-11-23)

- User approved continuing the sequential read-only audit. Reviewed the exact PR #7 patches and current product-baseline implementations; this step made no application-code changes.
- PR: [#7](https://github.com/atom-vpn/atom1/pull/7), source branch `fix-emergency-aether-detach`, target `pow-standalone`; recorded source head `4664958c02fcc3b48534e4bd1a8c1808f32f1d7b`. PR remains open/unmerged per the repository audit.
- Changed paths:
  - `engine/src/main/kotlin/com/atomvpn/mobile/engine/atom/AetherNative.kt`
  - `engine/src/main/kotlin/com/atomvpn/mobile/engine/atom/AtomConnectionCoordinator.kt`
- Exact patch behavior:
  - `AetherNative.detach()` now clears `OobKeyFetch.bindVpn(null)` before checking native-library availability and calling `nativeDetach()`. This releases the Java-side VpnService reference in addition to the native JNI global reference.
  - `forceRelease()` calls Aether stop, checks the captured session generation, calls Aether detach, then (if a Psiphon client was captured and the generation still matches) attempts bounded client stop and calls `client.close()`.
- Current product baseline findings:
  - `AetherNative.attach(service)` already binds the service into `OobKeyFetch` and calls native attach.
  - Current `AetherNative.detach()` only calls native detach; it does not clear the OOB Java-side service reference.
  - Current normal `stopLocked()` already stops the Psiphon client, closes it, stops the outer leg, and calls Aether detach. PR #7 specifically augments the emergency timeout/reaper path; it is not a replacement for ordinary stop.
  - Current `forceRelease()` does not call Aether detach and does not close the captured Psiphon client. Thus the PR addresses a real teardown-path gap not already covered by the baseline normal-stop path.
  - `OobKeyFetch.bindVpn` is a volatile reference assignment. `AtomPsiphonClient.close()` calls `incomingThread.quitSafely()`; its suspend `stop()` separately sends stop, waits up to its timeout, and unbinds.
- Lifecycle concern requiring correction or explicit acceptance before port:
  - The generation checks are check-then-act, not atomic with a new session's generation increment/attach. A new connect can theoretically begin between the final `forcedSession == sessionGeneration.get()` check and `AetherNative.detach()`; the reaper could then detach the newer session's Aether/OOB binding. The comment says it will never detach a newer session, but the plain integer comparison does not guarantee that under concurrency.
  - The reaper also calls `client.close()` after the bounded `client.stop()` attempt even if that stop timed out. This may be intentional emergency cleanup, but the callback-thread shutdown and any late service callbacks should be covered by a targeted race/lifecycle test.
  - Calling `AetherNative.stop()` before detach is ordered correctly at the call level, but the patch does not explicitly wait for a native-exit acknowledgement before detaching. Confirm the native stop contract before claiming that the native core has fully exited.
- Review assessment: PR #7 contains a meaningful missing cleanup in the emergency path and a valid Java OOB reference-release improvement. However, do not port it verbatim yet: first make the session ownership/serialization guarantee real (for example, coordinate reaper teardown with session start/attach under a shared lifecycle gate, without blocking the emergency path indefinitely), then add targeted tests for stale reaper versus new session, detach idempotence, and Psiphon client closure. Keep normal `stopLocked()` behavior unchanged.
- No CI/device validation for PR #7 was established in this review. Do not describe the change as tested. CI and physical-device emergency teardown validation are still required after any selective port.
- No code was ported, no PR was merged/closed/retargeted, no branch was rewritten/deleted, and no public APK/release was made. `atom-access-integrated` remains unchanged.
- Next audit item: inspect PR #6's intended VPN service-destruction cleanup against the actual current integrated service/coordinator implementation; determine whether the behavior is already present or needs a separate narrow proposal.
