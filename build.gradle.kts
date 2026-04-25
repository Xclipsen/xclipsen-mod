import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
	id("fabric-loom") version "1.14-SNAPSHOT"
	id("org.jetbrains.kotlin.jvm") version "2.1.20"
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
}

dependencies {
	minecraft("com.mojang:minecraft:${property("minecraft_version")}")
	mappings("net.fabricmc:yarn:${property("yarn_mappings_version")}:v2")
	modImplementation("net.fabricmc:fabric-loader:${property("loader_version")}")
	modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_version")}")
	modImplementation("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")
}

tasks.processResources {
	inputs.property("version", project.version)
	inputs.property("minecraft_version", project.property("minecraft_version"))

	filesMatching("fabric.mod.json") {
		expand(
			mapOf(
				"version" to project.version,
				"minecraft_version" to project.property("minecraft_version"),
			),
		)
	}
}

tasks.withType<JavaCompile>().configureEach {
	options.release.set(21)
}

tasks.withType<KotlinJvmCompile>().configureEach {
	compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}

kotlin {
	jvmToolchain(21)
}

java {
	withSourcesJar()
	sourceCompatibility = JavaVersion.VERSION_21
	targetCompatibility = JavaVersion.VERSION_21
}

tasks.jar {
	archiveClassifier.set("dev")
}

val prismInstancesDir = "${System.getProperty("user.home")}/instances"
val prismTargetDirs = listOf(
	// "$prismInstancesDir/1.21.10 Dungeons für clippy/minecraft/mods",
	// "$prismInstancesDir/1.21.10 skyhanni + sbo/minecraft/mods",
	"$prismInstancesDir/1.21.10 test/minecraft/mods",
)

fun Project.findRemappedModJar(): File {
	val jarName = "${base.archivesName.get()}-${project.version}.jar"
	return layout.buildDirectory.dir("libs").get().file(jarName).asFile
		.also { if (!it.exists()) throw GradleException("Remapped mod jar not found: ${it.path}") }
}

fun Project.copyRemappedModToPrismTargets() {
	val modJar = findRemappedModJar()

	prismTargetDirs.forEach { targetDir ->
		val target = file(targetDir)
		if (!target.isDirectory) {
			throw GradleException("Target directory does not exist: $targetDir")
		}

		fileTree(target) {
			include("xclipsen-irc-bridge-*.jar", "xclipsen-mod-*.jar")
		}.files.forEach(File::delete)

		copy {
			from(modJar)
			into(target)
		}

		println("Deployed ${modJar.name} to $targetDir")
	}
}

tasks.register("copyPrismMods") {
	group = "distribution"
	description = "Copies the remapped mod jar to the configured PrismLauncher test instances."
	dependsOn("remapJar")

	doLast {
		project.copyRemappedModToPrismTargets()
	}
}

tasks.named("build") {
	finalizedBy("copyPrismMods")
}

tasks.register("deployPrismMods") {
	group = "distribution"
	description = "Builds the mod and copies the remapped jar to the configured PrismLauncher test instances."
	dependsOn("build")
}

tasks.register("xmodTest") {
	group = "distribution"
	description = "Alias for deployPrismMods."
	dependsOn("deployPrismMods")
}

publishing {
	publications {
		create<MavenPublication>("mavenJava") {
			artifactId = property("archives_base_name").toString()
			from(components["java"])
		}
	}
}
