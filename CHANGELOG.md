# Changelog

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
