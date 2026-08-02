package de.xclipsen.ircbridge.mixin

import de.xclipsen.ircbridge.PartyFinderFeature
import de.xclipsen.ircbridge.ClientSessionLifecycle
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
import net.minecraft.client.Minecraft
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
			is ClientboundOpenScreenPacket -> dispatchPartyFinderPacket {
				PartyFinderFeature.onServerContainerOpen(packet.containerId, packet.title)
			}
			is ClientboundContainerClosePacket -> dispatchPartyFinderPacket {
				PartyFinderFeature.onServerContainerClose(packet.containerId)
			}
			is ClientboundContainerSetContentPacket -> {
				val items = packet.items().map { it.copy() }
				dispatchPartyFinderPacket {
					PartyFinderFeature.onServerContainerContent(packet.containerId(), packet.stateId(), items)
				}
			}
			is ClientboundContainerSetSlotPacket -> {
				val item = packet.item.copy()
				dispatchPartyFinderPacket {
					PartyFinderFeature.onServerContainerSlot(packet.containerId, packet.stateId, packet.slot, item)
				}
			}
		}
	}

	private fun dispatchPartyFinderPacket(action: () -> Unit) {
		val generation = ClientSessionLifecycle.snapshot()
		Minecraft.getInstance().execute {
			if (ClientSessionLifecycle.isCurrent(generation)) action()
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
