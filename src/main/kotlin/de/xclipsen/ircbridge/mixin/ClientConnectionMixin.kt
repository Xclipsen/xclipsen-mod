package de.xclipsen.ircbridge.mixin

import de.xclipsen.ircbridge.ServerTickTracker
import io.netty.channel.ChannelHandlerContext
import net.minecraft.network.ClientConnection
import net.minecraft.network.packet.Packet
import net.minecraft.network.packet.s2c.common.CommonPingS2CPacket
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(ClientConnection::class)
abstract class ClientConnectionMixin {
	@Inject(
		method = ["channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/packet/Packet;)V"],
		at = [At("HEAD")],
	)
	private fun onChannelRead(context: ChannelHandlerContext, packet: Packet<*>, ci: CallbackInfo) {
		if (packet is CommonPingS2CPacket) {
			ServerTickTracker.onServerTick()
		}
	}
}
