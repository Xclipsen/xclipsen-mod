# AGENTS.md Compliance Plan

This file is the active implementation plan for applying the repository rules in `AGENTS.md`.
Keep the status markers and validation notes current while work is in progress.

Status markers: `[ ]` pending, `[-]` in progress, `[x]` completed, `[!]` blocked.

## Decisions

- Apply contract changes across this mod, `/home/la/workspace/xclipsen-mod-backend`, and `/home/la/workspace/Xclipsen Bot`.
- Keep the existing `/xclipsen`, `/irc`, and `/i` command roots; move other local command roots under `/xclipsen`.
- Convert existing runtime HUD elements to plain text.
- Remove dead Experimentation configuration fields instead of implementing them.
- Use code possession, after Discord/Hypixel verification, to issue mod credentials; do not use Mojang session proof.
- Enforce the authenticated backend contract immediately without legacy unauthenticated routes.
- Store gameplay configuration per Minecraft profile UUID in one file per UUID.
- Migrate the legacy flat gameplay configuration to the first active profile; later profiles start with defaults.
- Keep only installation infrastructure settings shared.

## Phase 1: Player-Scoped Configuration

- [x] Split persistence between `config/Xclipsen/config.json` and `config/Xclipsen/players/<uuid>.json` while retaining one combined runtime config.
- [x] Keep only backend/dev URLs, dev mode, IRC URL/token, IRC polling/formatting, and updater settings in shared config.
- [x] Store all module, feature, tracker, account-derived, IRC enable/relay, sound, color, Silent Disconnect, and HUD state in the player config.
- [x] Back up and atomically migrate the existing flat config to the first active UUID.
- [x] Initialize later profiles with defaults and never fall back to username-scoped or shared gameplay state.
- [x] Reload and reset applicable runtime services if the active profile changes.
- [x] Scope future mod-feature credentials by backend origin and profile UUID in a separate credential store.
- [x] Validate with `./gradlew jar` and record manual profile-switch testing as outstanding where it cannot be automated.

## Phase 2: Project And Build Compliance

- [x] Centralize plugin, loader, and dependency versions in `gradle.properties` and resource expansion.
- [x] Change normal README validation to `./gradlew jar` and clearly warn about destructive deployment tasks.
- [x] Make CI select the exact release JAR deterministically.
- [x] Correct noisy production logging levels and remove obsolete diagnostics.

## Phase 3: Backend Authentication And Contracts

- [x] Add bot-issued, hashed, expiring, single-use mod link codes bound to a Hypixel-verified Minecraft profile.
- [x] Add hashed, revocable, profile-bound mod credentials without reusing the IRC bearer token.
- [x] Store mod credentials separately on the client by normalized backend origin and profile UUID.
- [x] Authenticate Hideonleaf, mob-model, and minigame registration APIs and derive identity from the credential.
- [x] Move minigame session credentials out of query strings.
- [x] Replace client-clock conflict ordering with server-issued revisions and idempotent Hideonleaf mutations.
- [x] Persist and return all mob-model fields, including armor and held-item visibility.
- [x] Add a validated, bounded, cached Auction House pricing endpoint and consume it from the mod.
- [x] Harden backend input limits, control-character removal, rate limits, atomic persistence, and startup token checks.
- [x] Add backend and bot contract tests.

## Phase 4: Threading, Lifecycle, And Network Safety

- [x] Move all blocking link/status/connection-test HTTP work off the Minecraft client thread.
- [x] Add connection/world generation invalidation for asynchronous callbacks.
- [x] Reset applicable feature state on disconnect and world replacement without broadening current island gates.
- [x] Terminate all executors, schedulers, sessions, and pending work on client stop.
- [x] Dispatch Party Finder packet state to the Minecraft client thread.
- [x] Make minigame polling and shutdown resilient to malformed events and pending disconnects.
- [x] Harden image previews against SSRF, unsafe redirects, oversized bodies/images, and texture leaks.
- [x] Restrict remote IRC bearer-token traffic to HTTPS while allowing local development HTTP.

## Phase 5: Commands And Diagnostics

- [x] Keep `/irc` and `/i` as existing Brigadier roots and remove their manual fallback dispatch.
- [x] Move game, linking, Cata, Shulker Glow, and Shard Tracker commands under `/xclipsen`.
- [x] Add semantic and length validation for usernames, codes, IDs, and messages.
- [x] Require explicit confirmation for destructive tracker resets.
- [x] Gate all dev actions with `devModeEnabled`.
- [x] Add `/xclipsen dev <feature> status` diagnostics for every status/detection feature.

## Phase 6: Shared UI, Colors, Sounds, And ClickGUI

- [x] Add shared semantic UI tokens and migrate duplicated neutral, text, spacing, and size values.
- [x] Add one shared client color API and remove local parsers and duplicate color conversion logic.
- [x] Centralize module descriptors and settings availability.
- [x] Add keyboard focus plus normal, hover, active, selected, and disabled control states.
- [x] Clamp and scroll settings panels and nested popups on small screens.
- [x] Remove status/scope/type/description cards from right-click settings.
- [x] Remove right-click actions from modules without configurable options.
- [x] Add missing Wormhole sound controls and the Shard Tracker toggle.
- [x] Remove dead Experimentation fields while continuing to tolerate them in legacy JSON.

## Phase 7: HUD And Local Screens

- [x] Convert all runtime movable HUD rendering to plain text while retaining editor-only selection indicators.
- [x] Clamp persisted HUD positions after window or GUI-scale changes.
- [x] Give all minigame sub-screens explicit parents and consistent Back, Close, and Escape behavior.
- [x] Confirm local chest/sign screens do not send server container actions.

## Phase 8: Updater And Release Safety

- [x] Require canonical artifact names, safe paths, successful responses, size bounds, valid JAR structure, and checksums before installation.
- [x] Preserve the active JAR until a replacement has been fully validated.
- [ ] Manually test updater rejection and installation paths before release.

## Final Validation

- [x] Run mod validation with `./gradlew jar`; do not claim this is an in-game test.
- [x] Run backend syntax checks and automated tests.
- [x] Run bot syntax checks and targeted contract tests.
- [ ] Manually test two Minecraft profiles in one launcher instance, command migration, linking, lifecycle transitions, small-window ClickGUI behavior, keyboard navigation, plain-text HUDs, minigames, and updater behavior.
- [ ] Update README and release files together only when an actual release is requested.

## Item Update Fix Integration

- [x] Adapt Hot Shirtless Men's bow/drill continuity behavior for Minecraft 26.1.2 without copying its brittle component-string comparison.
- [x] Gate the feature to all Hypixel SkyBlock islands, require a stable Hypixel item UUID, and send no additional packets.
- [x] Integrate the player-scoped module toggle, ClickGUI descriptor, mixins, and developer status command.
- [x] Validate with `./gradlew jar`; bow use, drill mining, island gating, and inventory updates remain manual in-game checks.

## Pickobulus Helper Integration

- [x] Adapt Skyblocker's client-side Pickobulus block prediction to the Xclipsen render and lifecycle architecture.
- [x] Restrict prediction to Gold Mine, Deep Caverns, Dwarven Mines, Crystal Hollows, and Glacite Mineshafts.
- [x] Integrate the player-scoped MISC toggle, searchable ClickGUI descriptor, reset lifecycle, and developer status command.
- [x] Validate with `./gradlew jar`; target prediction, cooldown gating, block classification, and rendering remain manual in-game checks.

## Validation Log

- 2026-08-01: `./gradlew jar` passed with JDK 25 after implementing shared/player config persistence, flat-config backup migration, profile-change reload/reset handling, and UUID-scoped Hideonleaf tracker files.
- 2026-08-01: `./gradlew jar` passed after centralizing Gradle plugin/loader versions, updating safe build documentation, making CI artifact selection deterministic, and reducing verbose production logging.
- 2026-08-01: Authentication cutover validation passed: backend `npm run check` and 28 tests, bot `npm run check` and 4 tests, and mod `./gradlew jar`. The mod now stores origin/UUID-scoped credentials separately and uses bearer authentication for identity APIs and minigame sessions.
- 2026-08-01: `./gradlew jar` passed after Phase 4 threading, lifecycle, packet dispatch, minigame resilience, image-preview network/resource hardening, and IRC HTTPS enforcement. In-game disconnect, world-replacement, linking, minigame shutdown, and image-preview checks remain manual.
- 2026-08-01: `./gradlew jar` passed after adding the extensible `IslandType` mode mapping and official Hypixel Mod API location subscription. Live island transitions and guest-island classification remain manual checks.
- 2026-08-01: `./gradlew jar` passed after consolidating commands under `/xclipsen`, retaining only `/xclipsen`, `/irc`, and `/i` roots, removing IRC fallback dispatch, validating command and minigame contract inputs, confirming tracker resets, gating developer actions, and adding feature diagnostics. In-game command suggestions, invite click actions, reset warnings, and diagnostic output remain manual checks.
- 2026-08-01: `./gradlew jar` passed with JDK 25 after adding shared UI/color APIs, centralized ClickGUI module descriptors, keyboard and disabled control states, responsive scrolled settings, shared color/sound pickers, Wormhole sound and Shard Tracker controls, and legacy-tolerant Experimentation cleanup. Small-window layout, keyboard navigation, picker previews, and persistence remain manual in-game checks.
- 2026-08-01: Initial in-game startup exposed recursive IRC URL normalization caused by a private helper shadowing the shared validator. The helper was renamed, `./gradlew jar` passed with JDK 25, and the corrected JAR was copied to the test instance; startup confirmation remains outstanding.
- 2026-08-01: Alpha-network testing exposed unsolicited minigame registration without a profile credential while production still serves the obsolete registration contract. The client now skips registration until the active profile has a mod credential and reports the link requirement only when multiplayer is requested; no unauthenticated legacy fallback was added.
- 2026-08-01: `./gradlew jar` passed with JDK 25 after converting movable HUD elements to plain text, clamping placements to current GUI bounds, making minigame parent navigation explicit, guarding local chest/sign screens from Minecraft container actions, and hardening updater downloads with canonical names, response/size/path checks, GitHub SHA-256 verification, full JAR metadata validation, cancellation checks, and install-before-cleanup replacement. HUD, minigame, resize/GUI-scale, updater rejection, updater installation, and Windows replacement behavior remain manual in-game checks.
- 2026-08-01: `./gradlew jar` passed with Temurin JDK 25 after implementing the mod-side revisioned/idempotent Hideonleaf contract with UUID-scoped atomic canonical/queue persistence, stable request IDs, one-at-a-time mutation replay and conflict rebasing, client-thread generation/profile guards, projected local totals, and conservative legacy aggregate migration. Lost-response replay, network restart recovery, 409 rebasing, reset ordering, live duration/price tracking, and two-profile behavior remain manual end-to-end checks.
- 2026-08-01: Final automated contract validation passed: mod `./gradlew jar` with Temurin JDK 25, backend `npm run check` plus 46 tests, bot `npm run check` plus 4 tests, and a live bounded pricing-service fetch for `ASPECT_OF_THE_END` and `OCELOT;4+100`. Hideonleaf now uses server revisions, durable bounded replay protection, canonical item limits, UUID-scoped pending mutations, AFK-safe duration checkpoints, conservative legacy migration, and startup refusal rather than overwriting malformed backend state. Auction pricing now uses the mod backend instead of direct client third-party calls, with bounded bodies, strict source/client validation, independent caches/backoff, one-hour stale limits, backend-origin isolation, safe arithmetic, and level-aware pet keys. Live conflict/replay/profile behavior, in-game underbid behavior, and updater installation/rejection paths remain manual checks.
- Manual two-profile launcher testing remains outstanding; a successful JAR build is not an in-game test.
- 2026-08-02: `./gradlew jar` passed with Temurin JDK 25 after adding the player-scoped Item Update Fix for all Hypixel SkyBlock islands. The two client-only mixins preserve bow rendering and drill breaking only for the same UUID-bound item while ignoring damage, lore, and custom-data updates; they add no packet hooks or sends. Bow use, drill mining, inventory updates, and island gating remain manual in-game checks.
- 2026-08-02: Restored Inventory Preview's visual slot grid, hotbar, item decorations, and optional armor column after an accidental plain-text conversion. `./gradlew jar` passed with Temurin JDK 25; scaled HUD placement and in-game item rendering remain manual checks.
- 2026-08-02: `/xclipsen hud` exposed a deployment race rather than a HUD implementation failure: overwriting the active mod JAR in place caused Fabric's lazy class loading to hit `ZipFile invalid LOC header`. Prism deployment now stages and validates a complete Fabric JAR before atomically replacing the destination, and the HUD opener reports future linkage failures instead of terminating the render thread. `./gradlew jar` passed, and the atomically installed Clippy Normal JAR passed ZIP and SHA-256 verification; opening the HUD after a full client restart remains a manual check.
- 2026-08-02: Updated every registered plain-text HUD element to use Minecraft-style text shadows, including colored tracker segments and editor sample text. `./gradlew jar` passed, and the Clippy Normal JAR passed ZIP and SHA-256 verification after atomic installation; in-game visual checks remain pending.
- 2026-08-02: `./gradlew jar` passed with Temurin JDK 25 after adapting Skyblocker's Pickobulus block prediction into a player-scoped Xclipsen MISC module. The helper is client-only, sends no packets, gates to the five selected Mining Islands, checks held-item lore and tab-list cooldown state, mirrors island-specific exposed-block simulation, resets on world changes, and exposes `/xclipsen dev pickobulus status`. Live target alignment, Glacite sub-area classification, cooldown transitions, and outline rendering remain manual checks.
- 2026-08-02: Live Dwarven Mines testing showed `currentIsland=NONE` after the location event was reset during a world transition while the tab-list area remained available. Pickobulus now resolves Mining Islands from Mod API island, mode, then sanitized area text. `./gradlew jar` passed; live fallback resolution and prediction rendering remain manual checks.
- 2026-08-02: Pickobulus was moved from a separate MISC module row into the Pickaxe Cooldown settings and now requires the parent module. It consumes `PickaxeAbilityCooldownFeature.currentStatus()` as its single ready-state source and only marks blocks currently adjacent to air; simulated later-exposed interior blocks were removed. `./gradlew jar` passed; live ready-state transitions, settings interaction, and surface-only rendering remain manual checks.
- 2026-08-02: Pickobulus outlines now use Minecraft's depth-tested world line layer instead of the X-ray line layer so occluded back edges and lines behind blocks are hidden. `./gradlew jar` passed; visual depth behavior remains a manual in-game check.
- 2026-08-02: The existing Pickaxe Cooldown HUD now shows a shadowed `Predicted: <count>` line for Pickobulus when its helper option is enabled, using the same surface-block prediction set. `./gradlew jar` passed; live count updates and HUD sizing remain manual checks.
- 2026-08-02: Configurable alert sounds now use an unattenuated relative `MASTER` sound instead of a linearly attenuated sound positioned at world origin. `./gradlew jar` passed with Temurin JDK 25, and the JAR was atomically installed and checksum-verified in both Clippy instances; Pickaxe Ready playback and shared sound previews remain manual in-game checks.
- 2026-08-02: Release `v1.0.0` metadata was synchronized across Gradle, README, and changelog. `./gradlew jar` passed with Temurin JDK 25; `xclipsen-mod-1.0.0.jar` passed ZIP validation and embeds mod version `1.0.0` for Minecraft `26.1.2`. Manual in-game checks listed above remain outstanding.
