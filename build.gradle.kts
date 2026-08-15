plugins {
    java
    // NOT io.papermc.paperweight.patcher. Canvas is built with its own paperweight
    // fork ("weaver"), and only weaver defines the `upstreams.canvas { }` helper.
    id("io.canvasmc.weaver.patcher") version "2.4.5"
}

val paperMavenPublicUrl = "https://repo.papermc.io/repository/maven-public/"
val canvasMavenPublicUrl = "https://maven.canvasmc.io/public/"

paperweight {
    // Canvas sets gitFilePatches/filterPatches this way; match upstream unless you
    // have a reason not to, or `rebuildPatches` will churn every patch file.
    filterPatches = false
    gitFilePatches = false

    upstreams.canvas {
        // `repo` is set by convention to github.com/CraftCanvasMC/Canvas.
        // There is NO `branch` property — pin an exact commit via `ref`.
        ref = providers.gradleProperty("canvasRef")

        // Single-file patches: `path` (upstream) -> `outputFile` (generated locally),
        // with the diff stored in `patchFile`. There is NO `patchesDir` here.
        patchFile {
            path = "canvas-server/build.gradle.kts"
            outputFile = file("havocfolia-server/build.gradle.kts")
            patchFile = file("havocfolia-server/build.gradle.kts.patch")
        }
        patchFile {
            path = "canvas-api/build.gradle.kts"
            outputFile = file("havocfolia-api/build.gradle.kts")
            patchFile = file("havocfolia-api/build.gradle.kts.patch")
        }

        // Directory patch sets are where `patchesDir` actually lives.
        // Leave the patches dir empty and this is a pass-through.
        patchDir("paperApi") {
            upstreamPath = "paper-api"
            excludes = setOf("build.gradle.kts")
            patchesDir = file("havocfolia-api/paper-patches")
            outputDir = file("paper-api")
        }
    }
}

subprojects {
    apply(plugin = "java-library")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            // Canvas is on Java 25. Do not drop this to 21 — upstream source will not compile.
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    repositories {
        mavenCentral()
        maven(paperMavenPublicUrl)
        maven(canvasMavenPublicUrl)
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = Charsets.UTF_8.name()
        options.release = 25
        options.isFork = true
    }
}
