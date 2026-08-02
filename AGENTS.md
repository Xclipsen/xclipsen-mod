# Working Instructions

These rules apply to the entire repository.

## Active Compliance Plan

- The active repository compliance plan is `plan.md`.
- Keep `plan.md` status markers and validation notes current while implementing that plan.

## AGENTS.md Approval

- This file may only be created, extended, or changed in content with the user's prior explicit consent.
- Before any intended change, specifically ask which rules should be added, changed, or removed.

## Project and Validation

- Never add or send dangerous packets that could result in anyone being banned from Minecraft servers.
- The project is a client-only Fabric mod. Do not add server entrypoints or server-side requirements unless explicitly requested.
- Use only the Gradle Wrapper (`./gradlew`). The project requires JDK 25.
- By default, validate changes with `./gradlew jar`. This command corresponds to CI.
- Run `./gradlew build` only with explicit consent because its finalizer can delete and replace PrismLauncher mod JARs outside the repository.
- After every successful `./gradlew jar`, automatically run `./gradlew copyPrismMods` to deploy the validated JAR to the `26.1.2 Normal für clippy` PrismLauncher instance. The user has granted standing consent for this specific deployment target.
- Maintain target and dependency versions centrally in `gradle.properties` and do not duplicate them unnecessarily in the source code.
- There is currently no automated test structure. Do not present a successful JAR build as an in-game test; mixins, rendering, screens, and runtime behavior must be checked manually as needed.

## Reference Mods

- Local source checkouts of SkyHanni, NoammAddons, and Skyblocker are available under `/home/la/workspace/mods` for implementation reference.
- Treat these repositories as read-only unless the user explicitly requests changes there. Adapt relevant concepts to this project's architecture and compliance rules instead of copying implementations blindly.

## Backend

- The local mod backend is located at `/home/la/workspace/xclipsen-mod-backend` and is the authoritative local source for backend work.
- Production backend access and the safe Docker Compose update procedure are documented in `/home/la/workspace/xclipsen-mod-backend/AGENTS.md`; follow those rules before any production inspection or deployment.
- Make changes to the backend, backend endpoints, or its server-side behavior exclusively in `/home/la/workspace/xclipsen-mod-backend`; do not recreate them in this mod repository.
- Always check changes to API paths, JSON fields, minigame events, sessions, or revision semantics on both sides of the contract and implement required backend adjustments in the backend repository.
- Keep the mod feature backend and IRC/bot backend strictly separate. `ircServerBaseUrl` and `backendAuthToken` apply only to the IRC/bot bridge; never send the bearer token to the mod feature backend.
- Do not commit or log tokens or other secrets, or include them in chat and error messages.
- Validate and limit network inputs, remove control characters, URL-encode query parameters, and handle HTTP/JSON errors defensively.
- Never perform blocking network calls on the Minecraft client thread. Use existing bounded executors and return UI, chat, and Minecraft access to the client thread via `Minecraft.getInstance().execute { ... }`.
- Appropriately terminate or reset executors, schedulers, and network sessions on client stop, disconnect, and world change.

## Code Style

- Prefer implementing new features in Kotlin under `de.xclipsen.ircbridge`. Use Java only for existing Java areas or technically necessary Minecraft package helpers.
- Preserve the local style of the edited file and do not perform mass formatting.
- Keep changes small and limited to the task. Introduce reusable abstractions only where multiple concrete callers exist or where a shared UI API is required below.
- Use SLF4J with appropriate log levels for diagnostics; do not introduce new `println` or `System.out` calls.

## Features and Configuration

- Before implementing any new feature, explicitly ask the user and establish on which islands the feature should work.
- Before creating a new module, check whether the feature can be integrated as an option of an existing related module. Reuse shared detection, trackers, and runtime state; do not create parallel modules with overlapping responsibilities. Create a separate module only when its purpose is clearly distinct.
- Fully integrate new features into the existing lifecycle: account for initialization, tick/render callbacks, and reset on disconnect or world change.
- Manage persistent feature states through `BridgeConfig`. For new fields, update the default value, `BridgeConfig.copy()`, normalization, working-copy transfer, ClickGUI integration, and persistence together.
- Protect existing configuration paths and migrations. The main configuration remains at `config/Xclipsen/config.json` unless an explicit migration is requested.

## Player-Scoped Configuration

- Persist all gameplay, account-specific, feature, tracker, and HUD configuration per Minecraft profile UUID under `config/Xclipsen/players/<uuid>.json`. Never key player configuration by username or allow it to leak between profiles.
- Keep only installation-level infrastructure settings shared in `config/Xclipsen/config.json`: backend and development URLs, development mode, IRC server URL and bearer token, IRC polling and formatting settings, and updater settings.
- Treat new persisted fields as player-specific by default. A field may be shared only when it is explicitly installation-level and independent of the active Minecraft account.
- Store mod-feature backend credentials separately from general configuration and scope them by normalized backend origin and Minecraft profile UUID.
- Load the active profile before initializing dependent features. When the active profile changes, save and reset the old profile's runtime state before loading and activating the new profile.
- Do not fall back to usernames or shared gameplay state when a profile UUID is unavailable.
- Migrate legacy flat configuration atomically, preserve a backup, copy gameplay settings only to the first active profile, and initialize later profiles with defaults.

## ClickGUI and UI

- New ClickGUI, screen, and HUD interfaces must use shared semantic UI tokens for the primary accent, neutral colors, text colors, spacing, and standard sizes. Do not duplicate existing neutral values as raw ARGB literals.
- Feature-specific accent colors may remain local but must be named and justified by their purpose.
- Implement recurring UI components such as panels, alerts, toggles, sliders, dropdowns, search fields, and text-field borders through shared renderers or widgets. Do not create a copy of an existing renderer; parameterize or extend the shared component.
- Interactive controls must have consistent normal, hover, active/selected, disabled, and keyboard-focus states. Disabled controls must not accept input and must be visibly dimmed.
- Prefer native Minecraft widgets or shared focusable widget abstractions. Do not represent focus exclusively through hover.
- A new configurable module is considered integrated only when all applicable locations have been maintained together: config default, `copy()`, normalization, working copy, `ConfigSection`, category, toggle/enabled mapping, settings renderer, click handling, bounds, searchable description, runtime lifecycle, and HUD registration where applicable.
- Assign new modules to exactly one existing category: `MODULES`, `MISC`, `DUNGEON`, `GALATEA`, `SAFARI`, or `SYSTEM`. A new category requires a deliberate UI decision beforehand.
- Every module requires a unique label and a searchable description. Interactions such as toggling and opening settings must remain visually understandable.
- Right-click settings may contain only genuinely changeable options such as toggles, sliders, selection fields, colors, or sounds. Do not display status-only, scope, type, or description cards in settings panels.
- If a module has no configurable options, it must not have a right-click action or an empty or purely informational settings panel.
- Constrain settings panels, popups, and overlays to the current screen size, keep them within the window, and make larger content scissored and scrollable. All controls must remain accessible even in small windows.
- Sub-screens receive their parent explicitly and return consistently on Back, Close, or Escape. Purely local screens must not trigger unintended server packets or server-side container actions.
- Do not improvise new animations and timed UI states locally within a feature using custom time- or tick-based interpolation. Either preserve the existing immediate state change or use shared duration, delta, and easing helpers.

## Colors and Sounds

- Store persisted custom colors canonically as uppercase `#RRGGBB`.
- Implement parsing, normalization, RGB/ARGB conversion, alpha application, HSB conversion, and color mixing through a shared client-side color API. Do not introduce new local copies of `HEX_COLOR_PATTERN` or `parseColor`.
- Edit every color configurable in the ClickGUI exclusively through the shared global color-picker renderer. Do not implement feature-local color selections or different pickers.
- Model configurable UI sounds as related values for sound ID, volume, and pitch.
- Normalize and play sound IDs through `SoundCatalog`. Use the same searchable selection with preview in the ClickGUI for all configurable sounds.
- Edit every sound option configurable in the ClickGUI exclusively through the shared global sound-picker renderer. Do not implement feature-local sound lists or different selection interfaces.
- Preserve the existing limits for sound options: volume `0.0..2.0`, pitch `0.1..2.0`. Use hard-coded sounds only for effects that are intentionally not configurable.

## HUD

- Implement new movable HUD content through `XclipsenHudElement` and register it in `XclipsenHudManager.elements`.
- Every HUD element requires a permanently stable, unique ID, separate `isEnabled`/`shouldDraw` logic, and meaningful sample data for the HUD editor.
- Keep positions within screen bounds and preserve the existing scaling range of `0.5..4.0`. Do not introduce custom placement or hitbox infrastructure when the shared HUD abstraction is sufficient.
- By default, render new HUD elements exclusively as plain text. Do not add backgrounds, panels, borders, outlines, shadows, or other decoration unless explicitly requested for the specific element.
- Render all plain-text HUD elements with a Minecraft-style text shadow, equivalent to `drawTextWithShadow`. Intentionally shadowless text outside HUD rendering, such as ClickGUI and other screen text, is exempt.

## Commands

- Implement all command handling for new features exclusively as a subcommand of `/xclipsen`, for example `/xclipsen <feature> ...`. Do not register new top-level commands or top-level aliases.
- For every feature with status or detection logic, provide a suitable diagnostic command under `/xclipsen dev <feature> status`, for example `/xclipsen dev pickaxecd status`.
- Register new subcommands with the Fabric Client Command API and Brigadier on the existing `xclipsen` command tree. Do not manually parse new command namespaces through `ClientSendMessageEvents.ALLOW_COMMAND`.
- Use appropriate Brigadier argument types with explicit bounds. Offer limited values as literals or with suggestions, and additionally validate player, ID, and code arguments semantically.
- Do not perform synchronous HTTP calls, long file access, sleeps, or extensive processing on the client thread in command handlers. Perform background work through bounded executors and return Minecraft, UI, and chat access to the client thread.
- Validate and length-limit command inputs before file, network, or server use, and encode them when used in URLs. Do not output secrets, sensitive response bodies, or stack traces in chat.
- Check feature, connection, and dev gates before backend calls, configuration changes, or other side effects. A `dev` namespace alone does not constitute authorization.
- Clearly name destructive or irreversible subcommands and warn beforehand. Require explicit confirmation or a `confirm` subcommand when substantial data loss is possible.

## Releases

- For a release, update at least the mod version in `gradle.properties`, the version listed in `README.md`, and the top entry in `CHANGELOG.md` in sync. `fabric.mod.json` obtains the version through the Gradle resource process.
- Preserve the artifact naming scheme `xclipsen-mod-X.Y.Z.jar` and keep the normal release JAR clearly distinguishable from `-sources.jar` and `-dev.jar`.
- Keep changes to the auto-updater, download logic, file names, deletion patterns, or Windows installation scripts small and explicitly test them manually.
