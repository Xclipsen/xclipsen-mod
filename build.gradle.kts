import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.jar.JarFile

plugins {
	id("net.fabricmc.fabric-loom")
	id("org.jetbrains.kotlin.jvm")
	`maven-publish`
}

version = property("mod_version").toString()
group = property("maven_group").toString()

base {
	archivesName.set(property("archives_base_name").toString())
}

repositories {
	mavenCentral()
	maven("https://maven.fabricmc.net/")
	maven("https://api.modrinth.com/maven")
	maven("https://repo.hypixel.net/repository/Hypixel/")
}

dependencies {
	minecraft("com.mojang:minecraft:${property("minecraft_version")}")
	implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
	implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_version")}")
	implementation("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")
	val hypixelModApi = "maven.modrinth:hypixel-mod-api:${property("hypixel_mod_api_version")}"
	implementation(hypixelModApi)
	compileOnly("net.hypixel:mod-api:${property("hypixel_mod_api_core_version")}")
	include(hypixelModApi)
}

tasks.processResources {
	inputs.property("version", project.version)
	inputs.property("minecraft_version", project.property("minecraft_version"))
	inputs.property("loader_version", project.property("loader_version"))

	filesMatching("fabric.mod.json") {
		expand(
			mapOf(
				"version" to project.version,
				"minecraft_version" to project.property("minecraft_version"),
				"loader_version" to project.property("loader_version"),
			),
		)
	}
}

tasks.withType<JavaCompile>().configureEach {
	options.release.set(25)
}

tasks.withType<KotlinJvmCompile>().configureEach {
	compilerOptions.jvmTarget.set(JvmTarget.JVM_25)
}

kotlin {
	jvmToolchain(25)
}

java {
	withSourcesJar()
	sourceCompatibility = JavaVersion.VERSION_25
	targetCompatibility = JavaVersion.VERSION_25
}

val prismTargetDirs: List<String> = listOf(
	// """C:\Users\leon.arning\AppData\Roaming\PrismLauncher\instances\26.1.2""",
	"/home/la/.local/share/PrismLauncher/instances/26.1.2 Normal für clippy/minecraft/mods",
)

fun Project.findRemappedModJar(): File {
	val jarName = "${base.archivesName.get()}-${project.version}.jar"
	return layout.buildDirectory.dir("libs").get().file(jarName).asFile
		.also { if (!it.exists()) throw GradleException("Remapped mod jar not found: ${it.path}") }
}

fun Project.resolvePrismModsDir(targetDir: String): File? {
	val root = file(targetDir)
	val candidates = listOf(
		root.resolve("minecraft/mods"),
		root.resolve(".minecraft/mods"),
		root,
	)

	return candidates.firstOrNull(File::isDirectory)
}

fun Project.copyRemappedModToPrismTargets() {
	val modJar = findRemappedModJar()
	var copiedAny = false

	prismTargetDirs.forEach { targetDir ->
		val target = resolvePrismModsDir(targetDir)
		if (target == null) {
			println("Skipping Prism deploy; no mods directory found for: $targetDir")
			return@forEach
		}

		val destination = target.resolve(modJar.name)
		val temporary = target.resolve(".${modJar.name}.tmp")
		try {
			Files.copy(modJar.toPath(), temporary.toPath(), StandardCopyOption.REPLACE_EXISTING)
			JarFile(temporary).use { jar ->
				require(jar.getJarEntry("fabric.mod.json") != null) { "Staged Prism mod is not a valid Fabric JAR" }
			}
			try {
				Files.move(
					temporary.toPath(),
					destination.toPath(),
					StandardCopyOption.ATOMIC_MOVE,
					StandardCopyOption.REPLACE_EXISTING,
				)
			} catch (_: AtomicMoveNotSupportedException) {
				Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
			}
		} finally {
			Files.deleteIfExists(temporary.toPath())
		}

		val root = file(targetDir)
		val cleanupRoots = if (target != root && root.isDirectory) listOf(root, target) else listOf(target)
		cleanupRoots.flatMap { cleanupRoot ->
			fileTree(cleanupRoot) {
				include("xclipsen-irc-bridge-*.jar", "xclipsen-mod-*.jar")
			}.files
		}.filter { it != destination }.forEach(File::delete)

		copiedAny = true
		println("Deployed ${modJar.name} to ${target.path}")
	}

	if (!copiedAny) {
		println("Skipped Prism deploy; no configured target directories were available.")
	}
}

tasks.register("copyPrismMods") {
	group = "distribution"
	description = "Copies the mod jar to the configured PrismLauncher test instances."
	dependsOn("jar")

	doLast {
		copyRemappedModToPrismTargets()
	}
}

tasks.named("build") {
	finalizedBy("copyPrismMods")
}

publishing {
	publications {
		create<MavenPublication>("mavenJava") {
			artifactId = property("archives_base_name").toString()
			from(components["java"])
		}
	}
}
