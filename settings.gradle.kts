pluginManagement {
	plugins {
		id("net.fabricmc.fabric-loom") version providers.gradleProperty("loom_version").get()
		id("org.jetbrains.kotlin.jvm") version providers.gradleProperty("kotlin_version").get()
		id("org.gradle.toolchains.foojay-resolver-convention") version providers.gradleProperty("foojay_resolver_version").get()
	}

	repositories {
		gradlePluginPortal()
		maven("https://maven.fabricmc.net/") {
			name = "Fabric"
		}
	}
}

plugins {
	id("org.gradle.toolchains.foojay-resolver-convention")
}

rootProject.name = "Xclipsen Mod"
