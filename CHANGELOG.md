# Changelog

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
