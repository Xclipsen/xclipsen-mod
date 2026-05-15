# Changelog

## v0.5.14

- Added Glacite mineshaft corpse ESP so discovered corpses can be highlighted directly in-world.
- Routed corpse ESP rendering through the shared entity glow path for more consistent highlighting behavior.

## v0.5.13

- Expanded `Pickaxe Cooldown` with configurable ready alerts, sound settings, and a separate movable alert HUD element.
- Added a new `Mineshaft AutoWarp` module that detects Glacite corpses directly from ArmorStand helmet IDs and can auto-request lead with `!ptme` before warping the party.
- Mineshaft AutoWarp rules now support OR clauses such as `lapis 2; vanguard 1` and use total shaft corpse counts instead of only remaining unlooted corpses.
- Prevented Mineshaft AutoWarp from re-warping parties into mineshafts that were entered from someone else's summon.

## v0.5.12

- Tightened `Pest ESP` matching so unrelated nearby mobs no longer inherit a pest nametag from loose ArmorStand matching.
- Removed the overly broad fallback that treated every Garden bat or silverfish as a confirmed pest before the proper nametag had synced.

## v0.5.11

- Added a new `Pickaxe Cooldown` misc module with a movable HUD widget for Hypixel mining ability cooldowns.
- Reads mining ability states such as `Pickobulus` directly from the SkyBlock tab list, following the same signal Skyblocker uses.
- Added a config toggle to keep the HUD visible even while the ability is ready.

## v0.5.10

- Added a new `Pest ESP` misc module for Garden pests with configurable box and tracer rendering.
- Switched Pest ESP away from glow outlines to world-space boxes and tracer lines.
- Simplified the Pest ESP config popup by removing the extra static info rows.

## v0.5.9

- Reworked the Experimentation module into a shizo-style auto-play flow for Chronomatron and Ultrasequencer.
- Added SkyHanni-style keep-items-visible support for Superpairs via slot update/render mixins.
- Simplified the Experimentation config section around the new Keep Items Visible option.
- Switched the 1.21.10 chat mixin invokers to named methods for the current mappings.

## v0.5.8

- Restricted the mod back to Minecraft `1.21.10` only to avoid client/render incompatibilities on `1.21.11`.
- Reworked the chat mixin setup for the `1.21.10` client path and removed the old multi-version split.
- Added Purple Terracotta highlighting and Auction House underbid auto-copy support.
- Hardened Lowest BIN refresh handling so temporary non-JSON/HTTP failures keep cached BIN data instead of hard-failing the refresh.

## v0.5.7

- Added Minecraft `1.21.11` compatibility.
- Updated the build, mappings, and Fabric API targets to `1.21.11`.
- Adjusted chat, camera, and render integration for the `1.21.11` client changes.

## v0.5.5

- Added an `Auto Experiments` module for Chronomatron, Ultrasequencer, and Superpairs.
- Added automatic stakes selection for the highest playable experiment tier.
- Added Superpairs memory/priority handling and safer waiting for board reset before continuing.
- Tuned experiment auto-close timing and Chronomatron/Ultrasequencer stop behavior.

## v0.5.3

- Added an automatic update checker that notifies in-game when a newer GitHub release is available.

## v0.5.2

- Updated the Door module with a selectable middle `3x3` barrier mode.
- IRC chat tab now also shows Discord-sourced bridge messages.
- Moved `Hideonleaf Helper` into the `Galatea` category and removed the old separate Galatea helper modules.
- Simplified the Dungeon module config popups and removed leftover unused config-screen code.

## v0.5.1

- Added a Devonian-style red vignette click-fix as a separate Dungeon module.
- Added the Mort Door Barrier dungeon module to locally block the disappearing Mort entrance door.
- Added Galatea Hideonleaf highlighting and Invisibug detection/highlighting with configurable colours.

## v0.5.0

- Added a dedicated IRC chat tab with a compact chat-only HUD toggle button.
- Moved backend/API setup into a shared global Setup section for all modules.
- Added Hideonleaf share-data sync, `/hideonleaf` bot leaderboard support, and instance-to-instance tracker sync.
- Improved Hideonleaf tracker migration, total-time handling, reset confirmation, and startup/backend sync behavior.

## v0.4.5

- Added a separate IRC chat tab that can be toggled without removing IRC lines from the main chat.
- Reworked the IRC switcher into a compact chat-only button and only show it while the chat screen is open.

## v0.4.4

- Added Co-op Relay toggle in IRC Bridge settings to disable forwarding co-op chat to IRC.

## v0.4.3

- No changelog entry.

## v0.4.2

- Added a ClickGUI-style settings screen and `/xclipsen` command alias.
- Added a Hideonleaf shard profit tracker with HUD display, session/total view, commands, and backend price refresh.
- Fixed startup crash caused by directly loading a helper class from the Mixin package.

## v0.4.1

- Added Hideonleaf helper rendering improvements and time changer support.

## v0.3.1

- Added a client-side glow outline for shulkers and shulker projectiles.
- Added `/shulkerglow` and `/irc shulkerglow` controls for toggling the glow setting.

## v0.3.0

- Added automatic Hypixel co-op chat relaying into IRC/Discord whenever a linked client is online.
- Added a configurable `[Co-op]` chat format so forwarded co-op lines can be themed to your liking.

## v0.2.1

- Added Discord image-link previews inside Minecraft chat hover.
- Added Shift hover mode for a large 1:1 image preview.
- Improved preview styling with a cleaner panel and image size footer.
- Paused chat movement while hovering image links so previews stay stable.
- Fixed preview handling for messages containing multiple image links.
- Kept `/i` as an alias for `/irc <message>` and preserved `/chat i` IRC chat mode behavior.

## v0.2.0

- Rewrote the mod in Kotlin and reduced it to IRC bridge functionality only.
- Added backend-only link flow and IRC bridge improvements.
