package de.xclipsen.ircbridge

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents

/**
 * Hook into screen mouse clicks via Fabric's screen event API.
 */
object ScreenMouseClickHandler {
	fun register() {
		ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
			ScreenMouseEvents.allowMouseClick(screen).register { _, click ->
				val button = click.button()
				if (button < 0) {
					return@register true
				}
				button != 0 || !XclipsenHudManager.handleScreenClick(click.x().toInt(), click.y().toInt(), button)
			}
		}
	}
}
