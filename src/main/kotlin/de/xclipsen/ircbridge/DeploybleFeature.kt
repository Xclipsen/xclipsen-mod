package de.xclipsen.ircbridge

import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.entity.Entity
import net.minecraft.entity.decoration.ArmorStandEntity
import net.minecraft.entity.projectile.FireworkRocketEntity
import net.minecraft.text.Text
import net.minecraft.util.ActionResult
import net.minecraft.util.Hand
import kotlin.math.max

object DeploybleFeature {
 const val ALERT_TEXT = "Deployable expires soon"

 private const val SECONDS_BEFORE_EXPIRATION = 10
 private const val CHECK_INTERVAL_TICKS = 20
 private const val INTERACTION_WINDOW_MS = 1000L
 private const val ALERT_DEDUPE_MS = 5000L
 private const val FLARE_CHECK_DELAY_MS = 500L
 private val FLARE_DISAPPEARED_PATTERN = Regex("^Your flare disappeared because you were too far away!$")
 private val PREVIOUS_DEPLOYABLE_REMOVED_PATTERN = Regex("^Your previous (.*) was removed!$")

	private open class DeployableData {
  var remainingTime: String = ""
  var lastAlertAt: Long = 0L
 }

 private class EntityDeployableData : DeployableData() {
  var id: Int? = null
  var itemDisplayName: String = ""
 }

 private class FlareData : DeployableData() {
  var remainingSeconds: Int? = null
  var lastPlacedAt: Long = 0L
  var itemDisplayName: String = "Flare"
 }

 private enum class DeployableType(val displayName: String) {
  TOTEM_OF_CORRUPTION("Totem of Corruption"),
  BLACK_HOLE("Black Hole"),
  UMBERELLA("Umberella"),
  FLARE("Flare"),
  DWARVEN_LANTERN("Dwarven Lantern"),
 }

 private val dwarvenLanternNamePrefixes = listOf(
  "Dwarven Lantern",
  "Mithril Lantern",
  "Titanium Lantern",
  "Glacite Lantern",
  "Will-o'-wisp",
 )

 private var totemData = DeployableData()
 private var blackHoleData = DeployableData()
 private var umberellaData = EntityDeployableData()
 private var flareData = FlareData()
 private var dwarvenLanternData = EntityDeployableData()
 private var tickCounter = 0
 private var lastDwarvenLanternInteractAt = 0L
 private var lastUmberellaInteractAt = 0L
 private var pendingFlareCheckAt = 0L
 private var pendingFlareDisplayName = ""
 private var currentAlertText = ""
 private var alertVisibleUntil = 0L

 fun init() {
  UseItemCallback.EVENT.register { player, world, hand ->
   if (world.isClient && hand == Hand.MAIN_HAND) {
    handleItemUse(player.mainHandStack.name.string)
   }
   ActionResult.PASS
  }

  UseBlockCallback.EVENT.register { player, world, hand, _ ->
   if (world.isClient && hand == Hand.MAIN_HAND) {
    handleItemUse(player.mainHandStack.name.string)
   }
   ActionResult.PASS
  }
 }

 fun onTick(client: MinecraftClient) {
  val config = XclipsenIrcBridgeClient.instance?.config()
  if (config?.deploybleModuleEnabled != true || !LocationTracker.isOnHypixelSkyBlock || client.world == null || client.player == null) {
   onWorldChange()
   return
  }

  handlePendingFlareCheck(client)

  if (++tickCounter < CHECK_INTERVAL_TICKS) {
   return
  }
  tickCounter = 0

  trackDeployablesStatus(client)
 }

 fun onIncomingMessage(message: Text?) {
  if (XclipsenIrcBridgeClient.instance?.config()?.deploybleModuleEnabled != true || !LocationTracker.isOnHypixelSkyBlock) {
   return
  }

  val normalized = normalize(message?.string ?: return)
  if (FLARE_DISAPPEARED_PATTERN.matches(normalized)) {
   resetFlare()
   return
  }

  val previous = PREVIOUS_DEPLOYABLE_REMOVED_PATTERN.find(normalized) ?: return
  if (previous.groupValues.getOrNull(1)?.contains("flare", ignoreCase = true) == true) {
   resetFlare()
  }
 }

 fun onWorldChange() {
  tickCounter = 0
  totemData = DeployableData()
  blackHoleData = DeployableData()
  umberellaData = EntityDeployableData()
  flareData = FlareData()
  dwarvenLanternData = EntityDeployableData()
  lastDwarvenLanternInteractAt = 0L
  lastUmberellaInteractAt = 0L
  pendingFlareCheckAt = 0L
  pendingFlareDisplayName = ""
  currentAlertText = ""
  alertVisibleUntil = 0L
 }

 fun shouldDrawAlert(config: BridgeConfig): Boolean {
  return config.deploybleModuleEnabled && currentAlertText.isNotBlank() && System.currentTimeMillis() <= alertVisibleUntil
 }

 fun currentAlertText(): String = currentAlertText

 fun statusLine(): String {
  val active = listOfNotNull(
   totemData.remainingTime.takeIf { it.isNotBlank() }?.let { "Totem $it" },
   blackHoleData.remainingTime.takeIf { it.isNotBlank() }?.let { "Black Hole $it" },
   umberellaData.remainingTime.takeIf { it.isNotBlank() }?.let { "Umberella $it" },
   flareData.remainingTime.takeIf { it.isNotBlank() }?.let { "Flare $it" },
   dwarvenLanternData.remainingTime.takeIf { it.isNotBlank() }?.let { "Lantern $it" },
  )
  return active.takeIf { it.isNotEmpty() }?.joinToString(" | ") ?: "Tracking deployable timers"
 }

 private fun handleItemUse(heldItemName: String) {
  val config = XclipsenIrcBridgeClient.instance?.config() ?: return
  if (!config.deploybleModuleEnabled || !LocationTracker.isOnHypixelSkyBlock) {
   return
  }

  val now = System.currentTimeMillis()
  if (heldItemName == DeployableType.UMBERELLA.displayName) {
   lastUmberellaInteractAt = now
  }

  if (heldItemName.endsWith("Flare")) {
   if (now - flareData.lastPlacedAt >= FLARE_CHECK_DELAY_MS) {
    pendingFlareCheckAt = now + FLARE_CHECK_DELAY_MS
    pendingFlareDisplayName = heldItemName
   }
  }

  if (isHeldItemDwarvenLantern(heldItemName)) {
   lastDwarvenLanternInteractAt = now
  }
 }

 private fun handlePendingFlareCheck(client: MinecraftClient) {
  val checkAt = pendingFlareCheckAt
  if (checkAt <= 0L || System.currentTimeMillis() < checkAt) {
   return
  }

  pendingFlareCheckAt = 0L
  val player = client.player ?: return
  val world = client.world ?: return
  val hasNearbyRocket = world.entities.asSequence()
   .filterIsInstance<FireworkRocketEntity>()
   .any { it.squaredDistanceTo(player) <= 100.0 }

  if (hasNearbyRocket) {
   flareData.remainingSeconds = 180
   flareData.remainingTime = secondsToTimeString(180)
   flareData.lastPlacedAt = System.currentTimeMillis()
   flareData.itemDisplayName = pendingFlareDisplayName.ifBlank { DeployableType.FLARE.displayName }
  }
 }

 private fun trackDeployablesStatus(client: MinecraftClient) {
  val world = client.world ?: return
  val player = client.player ?: return
  val armorStands = world.entities.asSequence()
   .filterIsInstance<ArmorStandEntity>()
   .filter { it.isAlive && !it.isRemoved }
   .toList()

  trackRecentlyPlacedEntityDeployables(armorStands, player)
  trackTotemStatus(armorStands, player)
  trackBlackHoleStatus(armorStands, player)
  trackUmberellaStatus(armorStands)
  trackDwarvenLanternStatus(armorStands)
  trackFlareStatus()
 }

 private fun trackRecentlyPlacedEntityDeployables(armorStands: List<ArmorStandEntity>, player: Entity) {
  val now = System.currentTimeMillis()
  val canTrackUmberella = now - lastUmberellaInteractAt <= INTERACTION_WINDOW_MS
  val canTrackLantern = now - lastDwarvenLanternInteractAt <= INTERACTION_WINDOW_MS
  if (!canTrackUmberella && !canTrackLantern) {
   return
  }

  armorStands.asSequence()
   .filter { it.squaredDistanceTo(player) <= 25.0 }
   .forEach { armorStand ->
    val name = armorStand.customName?.string?.trim().orEmpty()
    if (canTrackUmberella && (name == "Umberella 300s" || name == "Umberella 600s")) {
     umberellaData.id = armorStand.id
    }

    if (canTrackLantern && isDwarvenLanternArmorStandName(name) && (name.endsWith("300s") || name.endsWith("600s"))) {
     dwarvenLanternData.id = armorStand.id
     dwarvenLanternData.itemDisplayName = name.substringBeforeLast(' ').ifBlank { DeployableType.DWARVEN_LANTERN.displayName }
    }
   }
 }

 private fun trackTotemStatus(armorStands: List<ArmorStandEntity>, player: Entity) {
  val playerName = player.name.string.takeIf { it.isNotBlank() } ?: return
  val ownerArmorStand = armorStands.firstOrNull { armorStand ->
   val name = armorStand.customName?.string ?: return@firstOrNull false
   name.contains("Owner:") && name.contains(playerName)
  }

  if (ownerArmorStand == null) {
   totemData = DeployableData()
   return
  }

  val totemArmorStand = armorStands.firstOrNull { it.id == ownerArmorStand.id - 2 }
  if (totemArmorStand?.customName?.string != DeployableType.TOTEM_OF_CORRUPTION.displayName) {
   totemData = DeployableData()
   return
  }

  val remainingName = armorStands.firstOrNull { it.id == ownerArmorStand.id - 1 }?.customName?.string.orEmpty()
  if (!remainingName.contains("Remaining: ")) {
   totemData = DeployableData()
   return
  }

  totemData.remainingTime = remainingName.substringAfter("Remaining: ").trim()
  maybeAlert(DeployableType.TOTEM_OF_CORRUPTION.displayName, totemData)
 }

 private fun trackBlackHoleStatus(armorStands: List<ArmorStandEntity>, player: Entity) {
  val playerName = player.name.string.takeIf { it.isNotBlank() } ?: return
  val ownerArmorStand = armorStands.firstOrNull { armorStand ->
   val name = armorStand.customName?.string.orEmpty()
   name.contains("Spawned by:") && name.contains(playerName)
  }

  if (ownerArmorStand == null) {
   blackHoleData = DeployableData()
   return
  }

  val blackHoleName = armorStands.firstOrNull { it.id == ownerArmorStand.id + 1 }?.customName?.string.orEmpty()
  if (!blackHoleName.startsWith(DeployableType.BLACK_HOLE.displayName)) {
   blackHoleData = DeployableData()
   return
  }

  val seconds = blackHoleName.substringAfter(DeployableType.BLACK_HOLE.displayName).trim().removeSuffix("s").toIntOrNull() ?: 180
  blackHoleData.remainingTime = secondsToTimeString(seconds)
  maybeAlert(DeployableType.BLACK_HOLE.displayName, blackHoleData)
 }

 private fun trackUmberellaStatus(armorStands: List<ArmorStandEntity>) {
  val trackedId = umberellaData.id ?: return
  val name = armorStands.firstOrNull { it.id == trackedId && it.customName?.string?.startsWith("Umberella ") == true }
   ?.customName?.string

  if (name == null) {
   umberellaData = EntityDeployableData()
   return
  }

  val seconds = name.substringAfter("Umberella ").removeSuffix("s").toIntOrNull() ?: return
  umberellaData.remainingTime = secondsToTimeString(seconds)
  maybeAlert(DeployableType.UMBERELLA.displayName, umberellaData)
 }

 private fun trackDwarvenLanternStatus(armorStands: List<ArmorStandEntity>) {
  val trackedId = dwarvenLanternData.id ?: return
  val name = armorStands.firstOrNull { it.id == trackedId && isDwarvenLanternArmorStandName(it.customName?.string.orEmpty()) }
   ?.customName?.string

  if (name == null) {
   dwarvenLanternData = EntityDeployableData()
   return
  }

  val seconds = name.substringAfterLast(' ').removeSuffix("s").toIntOrNull() ?: return
  dwarvenLanternData.remainingTime = secondsToTimeString(seconds)
  maybeAlert(dwarvenLanternData.itemDisplayName.ifBlank { DeployableType.DWARVEN_LANTERN.displayName }, dwarvenLanternData)
 }

	private fun trackFlareStatus() {
		val remainingSeconds = flareData.remainingSeconds ?: return
		if (remainingSeconds <= 0) {
			resetFlare()
			return
		}

		if (remainingSeconds == SECONDS_BEFORE_EXPIRATION) {
			maybeAlert(flareData.itemDisplayName, flareData)
		}

		flareData.remainingSeconds = remainingSeconds - 1
		flareData.remainingTime = secondsToTimeString(flareData.remainingSeconds ?: 0)
	}

 private fun maybeAlert(itemDisplayName: String, data: DeployableData) {
  if (data.remainingTime != "${SECONDS_BEFORE_EXPIRATION}s") {
   return
  }

  val now = System.currentTimeMillis()
  if (now - data.lastAlertAt < ALERT_DEDUPE_MS) {
   return
  }
  data.lastAlertAt = now
  currentAlertText = "$itemDisplayName expires soon"
  alertVisibleUntil = now + 2800L

  val client = MinecraftClient.getInstance()
  client.player?.sendMessage(Text.literal("Your $itemDisplayName expires soon."), false)
  client.soundManager.play(SoundCatalog.masterSound(SoundCatalog.defaultSoundId, 1.0f, 1.0f))
 }

 private fun resetFlare() {
  flareData = FlareData()
 }

 private fun isHeldItemDwarvenLantern(heldItemName: String): Boolean {
  return dwarvenLanternNamePrefixes.any { prefix -> heldItemName == prefix }
 }

 private fun isDwarvenLanternArmorStandName(name: String): Boolean {
  return dwarvenLanternNamePrefixes.any { prefix -> name.startsWith(prefix) }
 }

 private fun secondsToTimeString(totalSeconds: Int): String {
  if (totalSeconds <= 0) {
   return ""
  }
  val minutes = (totalSeconds % 3600) / 60
  val seconds = totalSeconds % 60
  return if (minutes > 0) {
   "${minutes.toString().padStart(2, '0')}m ${seconds.toString().padStart(2, '0')}s"
  } else {
   "${seconds.toString().padStart(2, '0')}s"
  }
 }

 private fun normalize(raw: String): String {
  return stripFormatting(raw).replace('\r', ' ').replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
 }

 private fun stripFormatting(input: String): String {
  if (!input.contains('§')) {
   return input
  }

  val builder = StringBuilder(input.length)
  var skip = false
  for (character in input) {
   if (skip) {
    skip = false
    continue
   }
   if (character == '§') {
    skip = true
    continue
   }
   builder.append(character)
  }
  return builder.toString()
 }
}

object DeploybleAlertHudElement : XclipsenHudElement(
 id = "deployble_alert",
 displayName = "Deployble Alert",
) {
 override fun isEnabled(config: BridgeConfig): Boolean = config.deploybleModuleEnabled

 override fun shouldDraw(config: BridgeConfig): Boolean = DeploybleFeature.shouldDrawAlert(config)

 override fun defaultX(context: DrawContext): Float {
  return ((context.scaledWindowWidth - DEFAULT_WIDTH) / 2f).coerceAtLeast(4f)
 }

 override fun defaultY(context: DrawContext): Float {
  return (context.scaledWindowHeight * 0.24f).coerceAtLeast(24f)
 }

 override fun draw(context: DrawContext, example: Boolean): Pair<Float, Float> {
  val client = MinecraftClient.getInstance()
  val textRenderer = client.textRenderer
  val text = if (example) DeploybleFeature.ALERT_TEXT else DeploybleFeature.currentAlertText()
  val width = max(DEFAULT_WIDTH, textRenderer.getWidth(text) + PADDING_X * 2)
  val height = PADDING_Y * 2 + textRenderer.fontHeight

  context.fill(0, 0, width, height, BACKGROUND)
  context.fill(0, 0, width, 1, ACCENT)
  context.fill(0, height - 1, width, height, ACCENT)
  context.fill(0, 0, 1, height, ACCENT)
  context.fill(width - 1, 0, width, height, ACCENT)
  context.drawCenteredTextWithShadow(textRenderer, text, width / 2, PADDING_Y, TEXT_COLOR)
  return width.toFloat() to height.toFloat()
 }

 private const val DEFAULT_WIDTH = 180
 private const val PADDING_X = 8
 private const val PADDING_Y = 6
 private const val BACKGROUND = 0xC0181818.toInt()
 private const val ACCENT = 0xFFE85D75.toInt()
 private const val TEXT_COLOR = 0xFFFFFFFF.toInt()
}
