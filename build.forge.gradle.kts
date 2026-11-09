plugins {
    // Legacy Forge (1.20.1) support from the same ModDevGradle release as the NeoForge build
    id("net.neoforged.moddev.legacyforge") version "2.0.148"
    id("neoforge-mutex")
    id("com.gradleup.shadow") version "9.3.1"
}

version = "${property("mod.version")}-${sc.current.version}"
// Jar file name: ExcelonaStick-<loader>-<mod version>-<Minecraft version>.jar.
// Only the file name; the loaders identify the mod by mod.id inside the jar.
base.archivesName = "ExcelonaStick-forge"

val requiredJava = when {
    sc.current.parsed >= "26.1" -> JavaVersion.VERSION_25
    sc.current.parsed >= "1.20.5" -> JavaVersion.VERSION_21
    else -> JavaVersion.VERSION_17
}

val shadowBundle by configurations.creating

// Same libraries as the Fabric 1.20.1 build: POI 5.2.3 with xmlbeans 5.1.1.
// Libraries Minecraft/Forge already provide (log4j, commons-io, commons-codec) are left out.
val bundledLibraries = listOf(
    "org.apache.poi:poi:5.2.3",
    "org.apache.poi:poi-ooxml:5.2.3",
    "org.apache.poi:poi-ooxml-full:5.2.3",
    "org.apache.xmlbeans:xmlbeans:5.1.1",
    "org.apache.commons:commons-collections4:4.4",
    "org.apache.commons:commons-math3:3.6.1",
    "org.apache.commons:commons-compress:1.21",
    "com.zaxxer:SparseBitSet:1.2",
    "com.github.virtuald:curvesapi:1.07"
).map { coordinates ->
    (dependencies.create(coordinates) as ExternalModuleDependency).apply {
        isTransitive = false
    }
}

legacyForge {
    version = property("deps.forge_loader") as String

    mods {
        register(property("mod.id") as String) {
            sourceSet(sourceSets.main.get())
        }
    }

    runs {
        register("client") {
            gameDirectory = rootProject.file("run/${project.name}")
            client()
        }

        register("server") {
            gameDirectory = rootProject.file("run/${project.name}-server")
            server()
        }
    }
}

dependencies {
    for (library in bundledLibraries) {
        implementation(library)
        add(shadowBundle.name, library)
        add("additionalRuntimeClasspath", library)
    }

    // POI's module descriptor requires commons-codec. The client gets it from
    // Minecraft, the dedicated server does not, so the dev server gets it here.
    // (Only for runs: the mod jar shades POI, so it has no module descriptor.)
    add("additionalRuntimeClasspath", "commons-codec:commons-codec:1.15")
}

java {
    withSourcesJar()
    targetCompatibility = requiredJava
    sourceCompatibility = requiredJava

    toolchain {
        vendor = JvmVendorSpec.ADOPTIUM
        languageVersion = JavaLanguageVersion.of(requiredJava.majorVersion)
    }
}

tasks {
    processResources {
        fun MutableMap<String, String>.register(key: String, value: String) {
            inputs.property(key, value)
            set(key, value)
        }

        val props = buildMap {
            register("id", sc.properties["mod.id"])
            register("name", sc.properties["mod.name"])
            register("version", sc.properties["mod.version"])
            register("minecraft", sc.properties["mod.mc_compat"])
        }

        filesMatching("META-INF/mods.toml") { expand(props) }

        // Recipes: ingredients must be objects, and a result names its "item" (1.20.1 format).
        filesMatching("data/*/recipe/*.json") {
            filter { line ->
                line
                    .replace(Regex("\"([A-Za-z])\"\\s*:\\s*\"#([^\"]+)\""), "\"\$1\": {\"tag\": \"\$2\"}")
                    .replace(Regex("\"([A-Za-z])\"\\s*:\\s*\"([^\"#]+)\""), "\"\$1\": {\"item\": \"\$2\"}")
                    .replace(Regex("\"id\"(\\s*:\\s*)\"([^\"]+)\""), "\"item\"\$1\"\$2\"")
            }
            path = path.replace("/recipe/", "/recipes/")
        }
        filesMatching("data/*/loot_table/**/*.json") {
            path = path.replace("/loot_table/", "/loot_tables/")
        }

        exclude("fabric.mod.json", "META-INF/neoforge.mods.toml", "*.ct", "*.classtweaker")

        // The Redstone Connector is hidden until it is finished (see REDSTONE_CONNECTOR in
        // ExcelComputer.java): its recipe, loot table, model and texture stay out too.
        val redstoneConnector = rootProject.file("src/main/java/io/github/reverendbrown/excelcomputer/ExcelComputer.java")
            .readText().contains("REDSTONE_CONNECTOR = true")
        inputs.property("redstoneConnector", redstoneConnector)
        if (!redstoneConnector) {
            exclude("**/redstone_connector.json", "**/redstone_connector.png")
        }
    }

    named("createMinecraftArtifacts") {
        dependsOn("stonecutterGenerate")
    }

    jar {
        archiveClassifier = "slim"
    }

    shadowJar {
        archiveClassifier = ""
        configurations = listOf(shadowBundle)
        dependencies {
            for (library in bundledLibraries) {
                include(dependency("${library.group}:${library.name}"))
            }
        }

        relocate("org.apache.commons.collections4", "io.github.reverendbrown.excelcomputer.libs.collections4")
        relocate("org.apache.commons.math3", "io.github.reverendbrown.excelcomputer.libs.math3")
        relocate("org.apache.commons.compress", "io.github.reverendbrown.excelcomputer.libs.compress")
        // Small helper libraries POI uses (bit sets, chart curves); nothing looks
        // them up by name, so they can be renamed safely too. POI itself and
        // xmlbeans cannot: xmlbeans finds the Excel schema classes by names
        // stored inside its binary .xsb files.
        relocate("com.zaxxer.sparsebits", "io.github.reverendbrown.excelcomputer.libs.sparsebits")
        relocate("com.graphbuilder", "io.github.reverendbrown.excelcomputer.libs.graphbuilder")

        // The parts of POI the mod never uses (Word, PowerPoint, Visio...), see gradle/poi-trim.txt.
        val poiTrim = rootProject.file("gradle/poi-trim.txt")
        inputs.file(poiTrim)
        exclude(PoiTrim.patterns(poiTrim))

        mergeServiceFiles()

        append("META-INF/NOTICE")
        append("META-INF/NOTICE.txt")
        append("META-INF/LICENSE")
        append("META-INF/LICENSE.txt")

        exclude("module-info.class", "META-INF/versions/*/module-info.class")
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }

    withType<Jar> {
        val name = project.property("mod.id")
        inputs.property("mod_id", name)
        from("../../LICENSE") { rename { "$it-$name" } }
    }

    register<Copy>("buildAndCollect") {
        group = "build"
        description = "Builds mod jars and copies results to `build/libs/{mod version}/`"

        inputs.property("version", project.property("mod.version"))
        // Collect the remapped (SRG) jar from reobfShadowJar. shadowJar's own output
        // still has Mojang names and crashes outside dev with NoSuchFieldError.
        from(named("reobfShadowJar"), named<Jar>("sourcesJar").flatMap { it.archiveFile })
        into(rootProject.layout.buildDirectory.file("libs/${project.property("mod.version")}"))
    }
}

// Forge 1.20.1 runs with SRG names, so the shaded jar must be remapped from the
// development (Mojang) names, just like the plain jar is.
obfuscation {
    reobfuscate(tasks.shadowJar, sourceSets.main.get())
}
