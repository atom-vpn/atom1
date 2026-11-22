# Atom Access — Project State

> Living handoff document. Update this file whenever repository work changes the project state, decisions, validation status, or next action.
> Last reviewed: 2026-11-21
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


## 10. Cloud UI integration review (2026-11-22)

- Review source branch: `cloud-ui-review` (uploaded Cloud delivery; preserved unchanged).
- Integration branch: `ui-integration` (created from `cloud-ui-review`; no merge to `pow-standalone` or `main`).
- Integrated Cloud UI source into the actual Android source tree:
  - `app/src/main/java/com/atomvpn/mobile/MainActivity.kt`
  - `app/src/main/java/com/atomvpn/mobile/AtomAppearanceStore.kt`
  - `app/src/main/java/com/atomvpn/mobile/AtomRemoteConfig.kt`
- Corrected remote config bootstrap URL to `https://raw.githubusercontent.com/atom-vpn/atom-vpn-config/main/remote-config.json`.
- The misplaced root-level upload copies were removed from `ui-integration`; the original review branch remains intact.
- Initial source review confirms the Logs screen uses the existing `AppLogRepository` API (entries, snapshot, clear, count, estimated size); no parallel logger was introduced.
- Theme persistence uses a separate SharedPreferences store, not `AtomEngineStore`.
- Remote config refresh starts asynchronously at app launch and is also checked when About is opened; it does not block the VPN connection path, and cached/default content is retained on fetch failure.
- Validation pending: run GitHub Actions Universal and ARM64 builds and inspect compile/test results. No device validation claimed. No merge performed.

## 11. Log filter cleanup (2026-11-22)

- On `ui-integration`, removed `TOR` and `XRAY` from the Logs screen's visible source-filter chips.
- This is a UI-only filter change. The underlying logging enum, log collection, and VPN engine behavior were left unchanged.
- Commit: `8f43e2ad08dc0c879161429faa955315541da5a3`.
- Device/build validation for this specific change: pending.

## 12. Notification and language updates (2026-11-22)

- On `ui-integration`, added an in-app language preference persisted in `AtomLanguageStore`; Persian remains the default and English can be selected in Settings.
- Added English translations for the app's primary screens, settings, About, logs, and country names; switched layout direction to LTR for English and RTL for Persian.
- Renamed the Settings entry `درباره‌ی سازنده` to `About us` in English and the Persian Settings entry now displays `درباره ما`.
- Added the Android 13+ runtime notification permission request before starting VPN consent; rejecting notification permission does not block VPN connection.
- Updated the foreground VPN notification to track connection state and engine phases, with Persian/English status text and non-repeating alert behavior.
- Files changed: `MainActivity.kt`, `AtomVpnService.kt`, new `AtomLanguageStore.kt`.
- Build and device validation: pending. No claim of successful compilation or runtime notification display until CI/device checks complete.
- Engine transport, routing, native components, and coordinator logic were not changed.

- Latest implementation commit: `be1cf50124dcbec8e9065279dfaaf27bbb6f2523` (MainActivity translations/root labels); latest PR head at this checkpoint: `be1cf50124dcbec8e9065279dfaaf27bbb6f2523`.
- Latest CI run: [#45022621840](https://github.com/atom-vpn/atom1/actions/runs/45022621840), pending at last check; rebuild result and APK artifact links are not yet available.

- Follow-up service commit: `f5504b686aaa480af57e21e3b25555aa12514075`; the active notification now also refreshes immediately when the user changes the app language while VPN remains connected.

## 13. Compose build failure and correction (2026-11-22)

- CI run [#45022809892](https://github.com/atom-vpn/atom1/actions/runs/45022809892) failed while compiling both Universal and ARM64 debug variants.
- Root cause: composable translation calls in `AtomLogsPage` were placed inside a non-composable `remember` calculation and inside the `runCatching` click callback.
- Corrected `MainActivity.kt` on `ui-integration`:
  - Build the translated log-level filter list directly in the composable scope.
  - Resolve the translated share-sheet title in composable scope and capture the string in the click callback.
- Fix commit: `63cc22c18949759221c1d8f8969132074ed88e0c`.
- Follow-up CI validation is pending; no APK or successful build is claimed yet.
- VPN engine, native transport, routing, and coordinator code were not changed.


## 14. Atom Access product naming (2026-11-22)

- Approved user-facing product name: **Atom Access**.
- On `ui-integration`, changed the Android launcher label in `app/src/main/AndroidManifest.xml` to `Atom Access`; updated the Android special-use VPN service description to use the new brand.
- Updated offline/default About metadata in `AtomRemoteConfig.kt` to use `Atom Access`.
- The live remote configuration is served by the separate `atom-vpn/atom-vpn-config` repository and currently still contains the old `app_name`, `about`, and `developer_role` values. It must be updated separately so online refresh does not restore the old About title.
- No application ID, package/namespace, VPN engine, transport, routing, JNI, or connection lifecycle changes.
- CI/build validation for this naming change: pending. No device validation claimed. PR #10 remains open and unmerged.


## 15. About page title wording
- Updated the Persian About page heading to `درباره‌ Atom Access` (dynamic app name retained).
- Updated the English translation matcher for the revised Persian prefix.
- No VPN engine, transport, routing, JNI, package ID, or lifecycle behavior changed.


## 15. About page title wording (2026-11-22)

- Applied the user's preferred Persian About page title format: `درباره‌ Atom Access` (ZWNJ after «درباره» followed by a space).
- Updated the English translation prefix matcher to recognize the same Persian title format and render it as `About Atom Access`.
- Source change: `app/src/main/java/com/atomvpn/mobile/MainActivity.kt`.
- Commit: `5747cf36b6eee3580bee279498e5187838f3b004`.
- No engine, transport, routing, native, package, or connection lifecycle changes. CI/build validation remains pending.
