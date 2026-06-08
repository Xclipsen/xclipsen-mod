# Xclipsen Mod

Fabric-Clientmod fuer Minecraft `26.1.2`.

Aktuelle Version: `0.5.21`

## Kurzuebersicht

- `Settings GUI`: ClickGUI-style Konfigurationsscreen unter `/xclipsen`, `/xclipsen config` oder `/irc config`.
- `IRC Bridge`: Discord-/IRC-Nachrichten im Minecraft-Chat, `/irc <nachricht>`, `/i <nachricht>` und temporaerer IRC-Chatmodus.
- `Account Link`: Minecraft-Account per `/link CODE` mit dem Discord-/Bot-Backend verbinden.
- `Hypixel Co-op Relay`: Hypixel-Co-op-Chat automatisch ins Backend weiterleiten, wenn die Bridge aktiv und der Account gelinkt ist.
- `Image Preview`: Discord-/Chat-Bildlinks als Hover-Preview im Chat anzeigen, inklusive Shift-Grossansicht.
- `Hideonleaf Helper`: Shulker Glow, Projektil-Glow, Tracer-Linie, Lost-Fight-Alert und konfigurierbarer Alert-Sound.
- `Shard Tracker`: Hideonleaf-Shards und Drops tracken, Session/Total-HUD anzeigen, Profit pro Stunde berechnen und Bazaar-Preise vom Backend aktualisieren.
- `HUD Editor`: HUD-Elemente verschieben, skalieren und zuruecksetzen ueber `/xclipsen hud` oder `/irc hud`.
- `Time Changer`: clientseitige Zeit-Presets wie Day, Noon, Sunset, Night, Midnight, Sunrise und Real Time.
- `Minigames`: echte lokal erzeugte Vanilla-Chest- und Sign-Screens mit Tic-Tac-Toe gegen KI und autoritativem Backend-Multiplayer, ohne Inventar- oder Sign-Pakete an den Minecraft-Server.

## Commands

- `/xclipsen` - Settings oeffnen.
- `/xclipsen hud` - HUD-Editor oeffnen.
- `/xclipsen dev [on|off|status]` - Lokales Mod-Backend umschalten oder Status anzeigen.
- `/irc <nachricht>` oder `/i <nachricht>` - Nachricht ans Backend senden.
- `/irc on|off|status|reload` - Bridge lokal steuern und Status anzeigen.
- `/link CODE` - Minecraft-Account mit dem Backend-Linkcode verbinden.
- `/shulkerglow on|off|toggle` - Shulker Glow schnell umschalten.
- `/shardtracker` oder `/st` - Shard-Tracker-Status anzeigen.
- `/shardtracker reset|resetall|toggle|on|off` - Shard-Tracker steuern.
- `/game` - Minigame-Menue oder laufendes Match oeffnen.
- `/game leave` - Laufendes Match bewusst verlassen und fuer beide Spieler abbrechen.
- `/game accept <spieler>` und `/game deny <spieler>` - Spieleinladungen annehmen oder ablehnen.

## Einrichtung

1. `./gradlew build`
2. Die erzeugte Jar aus `build/libs/` in den `mods/`-Ordner des Clients legen.
3. Minecraft mit Fabric starten.
4. `/xclipsen` oeffnen und die gewuenschten Module aktivieren.
5. Optional `config/Xclipsen/config.json` bearbeiten oder `/irc reload` zum Neuladen ausfuehren.

## Backend- und IRC-Modus

Der Fabric-Clientmod nutzt zwei getrennte Server:

- Mod-Feature-Backend: standardmaessig `https://api.xclipsen.de`, per Dev-Modus lokal umschaltbar
- IRC-Server: im IRC-Bridge-Modul konfigurierbar, z. B. dein Xclipsen-Bot-Bridge-Server

1. Mod-Config in `config/Xclipsen/config.json`:
   - `ircBridgeEnabled = true`
   - `ircServerBaseUrl = "http://DEIN-BOT-SERVER:8765"`
   - `backendAuthToken = "dein-irc-shared-secret"` (wird ausschließlich für den IRC-Server verwendet)
2. Im Bot-Projekt `.env` setzen:
   - `IRC_BRIDGE_ENABLED=true`
   - `IRC_BRIDGE_HOST=0.0.0.0`
   - `IRC_BRIDGE_PORT=8765`
   - `IRC_BRIDGE_AUTH_TOKEN=dein-shared-secret`
   - `IRC_BRIDGE_CHANNEL_ID=dein-discord-kanal`
3. Den bestehenden Bot starten. Der Bot stellt nur den IRC-Server bereit.
4. Das standalone Backend aus `xclipsen-mod-backend` separat starten und `api.xclipsen.de` darauf zeigen lassen.

Mit `/xclipsen dev` kann zwischen dem Produktionsbackend und dem lokalen Mod-Backend unter
`http://127.0.0.1:8765` umgeschaltet werden. Der Zustand wird in `config/Xclipsen/config.json`
gespeichert und gilt fuer alle Mod-Features inklusive Minigames, nicht aber fuer IRC oder Update-Checks.

## Discord-Bot

Der Bot braucht mindestens:

- `View Channels`
- `Send Messages`
- `Read Message History`

Und in den Bot-Einstellungen muss `MESSAGE CONTENT INTENT` aktiviert sein, damit Discord-Nachrichten zurueck nach Minecraft gespiegelt werden.
