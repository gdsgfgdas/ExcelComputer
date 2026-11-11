plugins {
    id("net.neoforged.moddev") version "2.0.148"
    id("neoforge-mutex")
    // Bundles the libraries into the mod jar and renames some of their packages
    id("com.gradleup.shadow") version "9.3.1"
}

version = "${property("mod.version")}-${sc.current.version}"
// Jar file name: ExcelonaStick-<loader>-<mod version>-<Minecraft version>.jar.
// Only the file name; the loaders identify the mod by mod.id inside the jar.
base.archivesName = "ExcelonaStick-neoforge"

val requiredJava = when {
    sc.current.parsed >= "26.1" -> JavaVersion.VERSION_25
    sc.current.parsed >= "1.20.5" -> JavaVersion.VERSION_21
    sc.current.parsed >= "1.18" -> JavaVersion.VERSION_17
    sc.current.parsed >= "1.17" -> JavaVersion.VERSION_16
    else -> JavaVersion.VERSION_1_8
}

val shadowBundle by configurations.creating

repositories {
    /**
     * Restricts dependency search of the given [groups] to the [maven URL][url],
     * improving the setup speed.
     */
    fun strictMaven(url: String, alias: String, vararg groups: String) = exclusiveContent {
        forRepository { maven(url) { name = alias } }
        filter { groups.forEach(::includeGroup) }
    }
    strictMaven("https://www.cursemaven.com", "CurseForge", "curse.maven")
    strictMaven("https://api.modrinth.com/maven", "Modrinth", "maven.modrinth")
    // Storage mods the Logistics Hubs can join (compile-only, see dependencies)
    strictMaven("https://maven.neoforged.net/releases", "AE2", "org.appliedenergistics")
    strictMaven("https://maven.creeperhost.net", "Refined Mods", "com.refinedmods.refinedstorage")
}

// Apache POI (reads and writes .xlsx files) is shaded into the mod jar, and the
// commons libraries it needs are moved to private package names ("relocated").
// Other mods (for example Iris) bundle their own copies of some of these under
// the original names, and NeoForge refuses to start when two mods contain the
// same package. Libraries Minecraft already provides (log4j, commons-io,
// commons-codec) are left out on purpose. POI 5.2.5 is used (not 5.4.1) because
// it works with the older commons-io that Minecraft 1.21.1 ships. Each library
// version below is the one POI 5.2.5 itself declares (for example commons-compress
// 1.25.0; 1.24.0 makes saving crash with NoSuchMethodError: putArchiveEntry).
val bundledLibraries = listOf(
    "org.apache.poi:poi:5.2.5",
    "org.apache.poi:poi-ooxml:5.2.5",
    "org.apache.poi:poi-ooxml-full:5.2.5", // all the Excel XML classes (the slim "lite" one has no filter classes)
    "org.apache.xmlbeans:xmlbeans:5.2.0",
    "org.apache.commons:commons-collections4:4.4",
    "org.apache.commons:commons-math3:3.6.1",
    "org.apache.commons:commons-compress:1.25.0",
    "com.zaxxer:SparseBitSet:1.3",
    "com.github.virtuald:curvesapi:1.08"
).map { coordinates ->
    (dependencies.create(coordinates) as ExternalModuleDependency).apply {
        isTransitive = false
    }
}

neoForge {
    version = property("deps.neo_loader") as String

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

// This block comes after neoForge { } on purpose: the NeoForge plugin only
// creates the "additionalRuntimeClasspath" configuration once its version is set.
dependencies {
    for (library in bundledLibraries) {
        implementation(library)
        add(shadowBundle.name, library)

        if (sc.current.parsed < "1.21.9") {
            add("additionalRuntimeClasspath", library)
        }
    }

    // POI's module descriptor requires commons-codec. The client gets it from
    // Minecraft, the dedicated server does not, and the server runs POI for the
    // Logistics Hubs, so the dev server gets it here. (Only for runs: the mod
    // jar shades POI, so it has no module descriptor.)
    if (sc.current.parsed < "1.21.9") {
        add("additionalRuntimeClasspath", "commons-codec:commons-codec:1.16.0")
    }

    // Applied Energistics 2 and Refined Storage, where they exist for this version
    // (deps.ae2 / deps.refinedstorage in stonecutter.properties.toml). Compile-only:
    // the hubs use them when the player has them installed (see HubNetworks).
    findProperty("deps.ae2")?.let {
        compileOnly("org.appliedenergistics:appliedenergistics2:$it") { isTransitive = false }
    }
    findProperty("deps.refinedstorage")?.let {
        compileOnly("com.refinedmods.refinedstorage:refinedstorage-neoforge:$it") { isTransitive = false }
        compileOnly("org.apiguardian:apiguardian-api:1.1.2") // annotations RS uses (silences warnings)
    }
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

        filesMatching("META-INF/neoforge.mods.toml") { expand(props) }

        val mixinJava = "JAVA_${requiredJava.majorVersion}"
        // NeoForge runs with Mojang's names, so the mixins need no refmap (only Forge 1.20.1 has one).
        filesMatching("*.mixins.json") {
            expand("java" to mixinJava)
            filter { line -> if (line.contains("\"refmap\"")) "" else line }
        }

        // Recipes are written in the 1.21.2+ format ("P": "#minecraft:planks").
        // On 1.21.1 ingredients must be objects, so convert them while copying:
        // "#tag" becomes {"tag": "tag"} and "item" becomes {"item": "item"}.
        if (sc.current.parsed < "1.21.2") {
            filesMatching("data/*/recipe/*.json") {
                filter { line ->
                    line
                        .replace(Regex("\"([A-Za-z])\"\\s*:\\s*\"#([^\"]+)\""), "\"\$1\": {\"tag\": \"\$2\"}")
                        .replace(Regex("\"([A-Za-z])\"\\s*:\\s*\"([^\"#]+)\""), "\"\$1\": {\"item\": \"\$2\"}")
                }
            }
        }

        exclude("fabric.mod.json", "META-INF/mods.toml", "pack.mcmeta", "*.ct", "*.classtweaker")

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

    // The shaded jar (below) is the real mod jar; the plain jar is only an intermediate step.
    jar {
        archiveClassifier = "slim"
    }

    shadowJar {
        archiveClassifier = ""
        configurations = listOf(shadowBundle)
        // Only bundle the libraries listed at the top of this file (not Minecraft,
        // NeoForge or anything else on the classpath).
        dependencies {
            for (library in bundledLibraries) {
                include(dependency("${library.group}:${library.name}"))
            }
        }

        // Give our private copies of these libraries their own package names so
        // they cannot clash with the same libraries bundled by other mods.
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

        // Keep the license and notice text of the bundled libraries (Apache License 2.0)
        append("META-INF/NOTICE")
        append("META-INF/NOTICE.txt")
        append("META-INF/LICENSE")
        append("META-INF/LICENSE.txt")

        exclude("net/neoforged/neoforge/**")
        exclude("module-info.class", "META-INF/versions/*/module-info.class")
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }

    // Includes the license file in the built mod
    withType<Jar> {
        val name = project.property("mod.id")
        inputs.property("mod_id", name)
        from("../../LICENSE") { rename { "$it-$name" } }
    }

    register<Copy>("buildAndCollect") {
        group = "build"
        description = "Builds mod jars and copies results to `build/libs/{mod version}/`"

        inputs.property("version", project.property("mod.version"))
        from(shadowJar.flatMap { it.archiveFile }, named<Jar>("sourcesJar").flatMap { it.archiveFile })
        into(rootProject.layout.buildDirectory.file("libs/${project.property("mod.version")}"))
    }
}
