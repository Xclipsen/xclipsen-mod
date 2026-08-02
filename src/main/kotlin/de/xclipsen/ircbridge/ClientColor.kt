package de.xclipsen.ircbridge

import java.awt.Color
import java.util.Locale

object ClientColor {
	fun normalize(value: String?, fallback: String): String {
		return parseRgb(value)?.let(::formatRgb)
			?: parseRgb(fallback)?.let(::formatRgb)
			?: "#000000"
	}

	fun parseRgb(value: String?): Int? {
		val candidate = value?.trim()?.removePrefix("#") ?: return null
		return if (HEX_COLOR_PATTERN.matches(candidate)) candidate.toInt(16) else null
	}

	fun formatRgb(rgb: Int): String = String.format(Locale.ROOT, "#%06X", rgb and RGB_MASK)

	fun rgb(red: Int, green: Int, blue: Int): Int {
		return (red.coerceIn(0, 255) shl 16) or
			(green.coerceIn(0, 255) shl 8) or
			blue.coerceIn(0, 255)
	}

	fun rgbChannels(rgb: Int): RgbChannels {
		return RgbChannels(
			red = rgb ushr 16 and 0xFF,
			green = rgb ushr 8 and 0xFF,
			blue = rgb and 0xFF,
		)
	}

	fun rgbFloatChannels(rgb: Int): RgbFloatChannels {
		val channels = rgbChannels(rgb)
		return RgbFloatChannels(
			red = channels.red / 255.0f,
			green = channels.green / 255.0f,
			blue = channels.blue / 255.0f,
		)
	}

	fun argb(rgb: Int, alpha: Int): Int = (alpha.coerceIn(0, 255) shl 24) or (rgb and RGB_MASK)

	fun argb(rgb: Int, alpha: Float): Int = argb(rgb, (alpha.coerceIn(0.0f, 1.0f) * 255.0f).toInt())

	fun rgbToHsb(rgb: Int): HsbColor {
		val channels = rgbChannels(rgb)
		val hsb = Color.RGBtoHSB(channels.red, channels.green, channels.blue, null)
		return HsbColor(hsb[0], hsb[1], hsb[2])
	}

	fun hsbToRgb(hue: Float, saturation: Float, brightness: Float): Int {
		return Color.HSBtoRGB(hue, saturation, brightness) and RGB_MASK
	}

	fun hsbToRgb(color: HsbColor): Int = hsbToRgb(color.hue, color.saturation, color.brightness)

	fun mix(from: Int, to: Int, amount: Double): Int {
		val fraction = amount.coerceIn(0.0, 1.0)
		val inverse = 1.0 - fraction
		val fromChannels = rgbChannels(from)
		val toChannels = rgbChannels(to)
		return rgb(
			((fromChannels.red * inverse) + (toChannels.red * fraction)).toInt(),
			((fromChannels.green * inverse) + (toChannels.green * fraction)).toInt(),
			((fromChannels.blue * inverse) + (toChannels.blue * fraction)).toInt(),
		)
	}

	data class RgbChannels(val red: Int, val green: Int, val blue: Int)

	data class RgbFloatChannels(val red: Float, val green: Float, val blue: Float)

	data class HsbColor(val hue: Float, val saturation: Float, val brightness: Float)

	private const val RGB_MASK = 0xFFFFFF
	private val HEX_COLOR_PATTERN = Regex("[0-9a-fA-F]{6}")
}
