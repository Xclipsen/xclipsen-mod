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

publishing {
	publications {
		create<MavenPublication>("mavenJava") {
			artifactId = property("archives_base_name").toString()
			from(components["java"])
		}
	}
}
