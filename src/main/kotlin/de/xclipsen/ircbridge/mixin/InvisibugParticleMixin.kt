package de.xclipsen.ircbridge.mixin

import de.xclipsen.ircbridge.InvisibugHighlighter
import net.minecraft.client.network.ClientPlayNetworkHandler
import net.minecraft.network.packet.s2c.play.ParticleS2CPacket
import net.minecraft.particle.ParticleTypes
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(ClientPlayNetworkHandler::class)
abstract class InvisibugParticleMixin {

    @Inject(method = ["onParticle"], at = [At("HEAD")])
    private fun handleParticleForInvisibug(packet: ParticleS2CPacket, ci: CallbackInfo) {
        if (packet.parameters !== ParticleTypes.CRIT) return
        InvisibugHighlighter.onCritParticle(packet.x, packet.y, packet.z)
    }
}
