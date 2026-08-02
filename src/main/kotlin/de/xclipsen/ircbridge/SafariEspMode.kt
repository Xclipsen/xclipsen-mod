package de.xclipsen.ircbridge

object SafariEspMode {
	const val BOX_TRACER = 0
	const val GLOW = 1
	const val modeCount = 2

	fun normalize(mode: Int): Int = mode.coerceIn(BOX_TRACER, GLOW)

	fun displayName(mode: Int): String = when (normalize(mode)) {
		GLOW -> "Glow (X-Ray)"
		else -> "Box + Tracer"
	}
}
