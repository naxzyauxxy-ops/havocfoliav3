import java.util.*

pluginManagement {
    repositories {
        gradlePluginPortal()
        // Required — the weaver patcher plugin is not on the Gradle Plugin Portal.
        maven {
            name = "canvasmc"
            url = uri("https://maven.canvasmc.io/public")
        }
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "HavocFolia"

for (name in listOf("havocfolia-api", "havocfolia-server")) {
    val projName = name.lowercase(Locale.ENGLISH)
    include(projName)
    findProject(":$projName")!!.projectDir = file(name)
}

gradle.lifecycle.beforeProject {
    val mcVersion = providers.gradleProperty("mcVersion").get().trim()
    val buildNumber = providers.environmentVariable("BUILD_NUMBER").orNull?.trim()?.toInt()
    version = if (buildNumber == null) "$mcVersion.local-SNAPSHOT" else "$mcVersion.build.$buildNumber"
}
