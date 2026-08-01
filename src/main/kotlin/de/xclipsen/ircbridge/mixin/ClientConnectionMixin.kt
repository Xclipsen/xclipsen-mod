package de.xclipsen.ircbridge.mixin

import de.xclipsen.ircbridge.PartyFinderFeature
import de.xclipsen.ircbridge.SilentDisconnectFeature
import de.xclipsen.ircbridge.ServerTickTracker
import io.netty.channel.ChannelHandlerContext
import net.minecraft.network.Connection
import net.minecraft.network.DisconnectionDetails
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.common.ClientboundPingPacket
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(Connection::class)
abstract class ClientConnectionMixin {
	@Inject(
		method = ["channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V"],
		at = [At("HEAD")],
	)
	private fun onChannelRead(context: ChannelHandlerContext, packet: Packet<*>, ci: CallbackInfo) {
		if (packet is ClientboundPingPacket) {
			ServerTickTracker.onServerTick()
		}
		when (packet) {
			is ClientboundOpenScreenPacket -> PartyFinderFeature.onServerContainerOpen(packet.containerId, packet.title)
			is ClientboundContainerClosePacket -> PartyFinderFeature.onServerContainerClose(packet.containerId)
			is ClientboundContainerSetContentPacket -> PartyFinderFeature.onServerContainerContent(packet.containerId(), packet.stateId(), packet.items())
			is ClientboundContainerSetSlotPacket -> PartyFinderFeature.onServerContainerSlot(packet.containerId, packet.stateId, packet.slot, packet.item)
		}
	}

	@Inject(
		method = ["disconnect(Lnet/minecraft/network/DisconnectionDetails;)V"],
		at = [At("HEAD")],
	)
	private fun onDisconnect(info: DisconnectionDetails, ci: CallbackInfo) {
		SilentDisconnectFeature.onDisconnectStarting()
	}
}
