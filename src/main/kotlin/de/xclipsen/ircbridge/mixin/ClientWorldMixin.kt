package de.xclipsen.ircbridge.mixin

import de.xclipsen.ircbridge.FireFreezeFeature
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.entity.Entity
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.Holder
import net.minecraft.sounds.SoundSource
import net.minecraft.sounds.SoundEvent
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(ClientLevel::class)
abstract class ClientWorldMixin {
	@Inject(
		method = ["playSeededSound(Lnet/minecraft/world/entity/Entity;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V"],
		at = [At("HEAD")],
	)
	private fun onPlaySound(
		except: Entity?,
		x: Double,
		y: Double,
		z: Double,
		sound: Holder<SoundEvent>,
		category: SoundSource,
		volume: Float,
		pitch: Float,
		seed: Long,
		ci: CallbackInfo,
	) {
		FireFreezeFeature.onSound(x, y, z, sound, category, volume, pitch)
	}

	@Inject(
		method = ["addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"],
		at = [At("HEAD")],
		cancellable = true,
	)
	private fun onAddParticleClient(
		parameters: ParticleOptions,
		x: Double,
		y: Double,
		z: Double,
		velocityX: Double,
		velocityY: Double,
		velocityZ: Double,
		ci: CallbackInfo,
	) {
		if (FireFreezeFeature.shouldSuppressParticle(parameters, x, y, z, velocityX, velocityY, velocityZ)) {
			ci.cancel()
		}
	}

	@Inject(
		method = ["addParticle(Lnet/minecraft/core/particles/ParticleOptions;ZZDDDDDD)V"],
		at = [At("HEAD")],
		cancellable = true,
	)
	private fun onAddParticleClientWithFlags(
		parameters: ParticleOptions,
		alwaysSpawn: Boolean,
		canSpawnOnMinimal: Boolean,
		x: Double,
		y: Double,
		z: Double,
		velocityX: Double,
		velocityY: Double,
		velocityZ: Double,
		ci: CallbackInfo,
	) {
		if (FireFreezeFeature.shouldSuppressParticle(parameters, x, y, z, velocityX, velocityY, velocityZ)) {
			ci.cancel()
		}
	}
}
