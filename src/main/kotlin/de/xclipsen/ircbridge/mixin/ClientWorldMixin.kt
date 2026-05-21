package de.xclipsen.ircbridge.mixin

import de.xclipsen.ircbridge.ClientTimeChanger
import de.xclipsen.ircbridge.FireFreezeFeature
import net.minecraft.client.world.ClientWorld
import net.minecraft.entity.Entity
import net.minecraft.particle.ParticleEffect
import net.minecraft.registry.entry.RegistryEntry
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvent
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.ModifyVariable
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(ClientWorld::class)
abstract class ClientWorldMixin {
	@ModifyVariable(method = ["setTime(JJZ)V"], at = At("HEAD"), argsOnly = true, ordinal = 1)
	private fun overrideTimeOfDay(timeOfDay: Long): Long {
		return ClientTimeChanger.overrideTimeOfDay(timeOfDay)
	}

	@ModifyVariable(method = ["setTime(JJZ)V"], at = At("HEAD"), argsOnly = true, ordinal = 0)
	private fun overrideShouldTickTimeOfDay(shouldTickTimeOfDay: Boolean): Boolean {
		return ClientTimeChanger.shouldTickTimeOfDay(shouldTickTimeOfDay)
	}

	@Inject(
		method = ["playSound(Lnet/minecraft/entity/Entity;DDDLnet/minecraft/registry/entry/RegistryEntry;Lnet/minecraft/sound/SoundCategory;FFJ)V"],
		at = [At("HEAD")],
	)
	private fun onPlaySound(
		except: Entity?,
		x: Double,
		y: Double,
		z: Double,
		sound: RegistryEntry<SoundEvent>,
		category: SoundCategory,
		volume: Float,
		pitch: Float,
		seed: Long,
		ci: CallbackInfo,
	) {
		FireFreezeFeature.onSound(x, y, z, sound, category, volume, pitch)
	}

	@Inject(
		method = ["addParticleClient(Lnet/minecraft/particle/ParticleEffect;DDDDDD)V"],
		at = [At("HEAD")],
		cancellable = true,
	)
	private fun onAddParticleClient(
		parameters: ParticleEffect,
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
		method = ["addParticleClient(Lnet/minecraft/particle/ParticleEffect;ZZDDDDDD)V"],
		at = [At("HEAD")],
		cancellable = true,
	)
	private fun onAddParticleClientWithFlags(
		parameters: ParticleEffect,
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
