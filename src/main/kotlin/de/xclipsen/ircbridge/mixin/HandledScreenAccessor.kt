package de.xclipsen.ircbridge.mixin

import net.minecraft.client.gui.screen.ingame.HandledScreen
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor

@Mixin(HandledScreen::class)
interface HandledScreenAccessor {
	@Accessor("x")
	fun `xclipsen$getX`(): Int

	@Accessor("y")
	fun `xclipsen$getY`(): Int
}
