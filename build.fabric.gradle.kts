plugins {
    // This plugin applies the correct loom variant based on the Minecraft version
    id("dev.kikugie.loom-back-compat")
}

// DO NOT set group = ...!
version = "${property("mod.version")}-${sc.current.version}"
// Jar file name: ExcelonaStick-<loader>-<mod version>-<Minecraft version>.jar.
// Only the file name; the loaders identify the mod by mod.id inside the jar.
base.archivesName = "ExcelonaStick-fabric"

val requiredJava: JavaVersion = when {
    sc.current.parsed >= "26.1" -> JavaVersion.VERSION_25
    sc.current.parsed >= "1.20.5" -> JavaVersion.VERSION_21
    sc.current.parsed >= "1.18" -> JavaVersion.VERSION_17
    sc.current.parsed >= "1.17" -> JavaVersion.VERSION_16
    else -> JavaVersion.VERSION_1_8
}

// This can be used for publishing on Modrinth and Curseforge
val compatibleVersions: List<String> = sc.properties.rawOrNull("mod", "mc_releases")
    ?.asList().orEmpty().map { it.toString() }

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
    strictMaven("https://maven.creeperhost.net", "Refined Mods", "com.refinedmods.refinedstorage")
    strictMaven("https://modmaven.dev", "ModMaven", "appeng")
}

// Minecraft 1.20.1 ships older libraries (for example commons-io 2.11), so it needs the
// matching older POI. Newer versions use POI 5.2.5.
val poiVersion = if (sc.current.parsed < "1.21") "5.2.3" else "5.2.5"
val xmlBeansVersion = if (sc.current.parsed < "1.21") "5.1.1" else "5.2.0"

// Libraries bundled inside the mod jar. Minecraft already ships log4j, commons-io
// and commons-codec, and the game uses ITS copies at runtime (a newer copy
// nested in a mod does not replace them), so they are left out here. POI 5.2.5
// is used (not 5.4.1) because it works with the older commons-io that
// Minecraft 1.21.1 ships; 5.4.1 needs a newer commons-io and crashes with
// NoSuchMethodError outside the development environment.
val bundledLibraries = listOf(
    "org.apache.poi:poi-ooxml:$poiVersion",
    "org.apache.poi:poi:$poiVersion",
    "org.apache.xmlbeans:xmlbeans:$xmlBeansVersion",
    "org.apache.commons:commons-collections4:4.4",
    "org.apache.poi:poi-ooxml-full:$poiVersion", // all the Excel XML classes (the slim "lite" one has no filter classes)
    "org.apache.commons:commons-math3:3.6.1"
).map { notation ->
    (dependencies.create(notation) as ExternalModuleDependency).apply {
        exclude(group = "org.apache.logging.log4j")
        exclude(group = "commons-io")
        exclude(group = "commons-codec")
        // poi-ooxml-full replaces the slim poi-ooxml-lite that POI would otherwise bring along.
        exclude(group = "org.apache.poi", module = "poi-ooxml-lite")
    }
}

// The parts of POI the mod never uses (Word, PowerPoint, Visio...) are cut out of
// these nested jars once the mod jar is built (see gradle/poi-trim.txt, PoiTrim).
val poiTrimFile = rootProject.file("gradle/poi-trim.txt")
val trimmedLibraries = setOf("poi-ooxml", "poi-ooxml-full")

dependencies {
    minecraft("com.mojang:minecraft:${sc.current.version}")
    // Applies Mojang Mappings on obfuscated versions
    loomx.applyMojangMappings()

    // Use `mod{dependency type}` even on 26.1+ - loom-back-compat converts them
    modImplementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")

    // The full Fabric API. fabric.mod.json declares a dependency on "fabric-api",
    // which only the full package provides (individual modules do not).
    modImplementation("net.fabricmc.fabric-api:fabric-api:${sc.properties.get<String>("deps.fabric_api")}")

    // Apache POI reads and writes .xlsx files. Bundled inside the mod jar (jar-in-jar).
    for (library in bundledLibraries) {
        implementation(library)
        add("include", library)
    }

    // Refined Storage, where it exists for this version (deps.refinedstorage in
    // stonecutter.properties.toml). Compile-only: the hubs use it when the player
    // has it installed (see HubNetworks).
    findProperty("deps.ae2")?.let {
        modCompileOnly("appeng:appliedenergistics2-fabric:$it") { isTransitive = false }
    }
    findProperty("deps.refinedstorage")?.let {
        modCompileOnly("com.refinedmods.refinedstorage:refinedstorage-fabric:$it") { isTransitive = false }
        compileOnly("org.apiguardian:apiguardian-api:1.1.2") // annotations RS uses (silences warnings)
    }
}

loomx.modJar.configure {
    val modJar = archiveFile
    val list = poiTrimFile
    val libraries = trimmedLibraries
    inputs.file(list)
    doLast { PoiTrim.trimNestedJars(modJar.get().asFile, list, libraries) }
}

loom {
    fabricModJsonPath = rootProject.file("src/main/resources/fabric.mod.json") // Useful for interface injection

    decompilerOptions.named("vineflower") {
        options.put("mark-corresponding-synthetics", "1") // Adds names to lambdas - useful for mixins
    }

    runConfigs.all {
        preferGradleTask = true
        generateRunConfig = true
        runDirectory = rootProject.file("run/${project.name}") // One run directory per version, so Gradle can tell the versions apart
        jvmArguments.add("-Dmixin.debug.export=true") // Exports transformed classes for debugging
    }
    // The dedicated server gets its own folder, so it can run next to the client
    // without sharing log files and settings.
    runConfigs.named("server") {
        runDirectory = rootProject.file("run/${project.name}-server")
    }
    // The same player every launch ("Dev", like the NeoForge and Forge dev clients).
    // Otherwise Fabric picks a random PlayerNNN each time, so anything the mod keeps
    // per player (a player's Logistics Hubs, which hubs they own) seems to vanish.
    runConfigs.named("client") {
        programArgs("--username", "Dev")
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
            register("loader", Regex("\\d+\\.\\d+").find(sc.properties.get<String>("deps.fabric_loader"))!!.value)
            register("java", requiredJava.majorVersion)
        }

        filesMatching("fabric.mod.json") { expand(props) }

        val mixinJava = "JAVA_${requiredJava.majorVersion}"
        filesMatching("*.mixins.json") { expand("java" to mixinJava) }

        // Loom remaps mixin targets in the classes themselves ("static" remapping), and
        // 26.1+ isn't obfuscated, so Fabric needs no refmap (only the Forge build has one).
        filesMatching("excelcomputer.mixins.json") {
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

        // 1.20.1 and older: the data folders are plural and a recipe result names its "item".
        if (sc.current.parsed < "1.21") {
            filesMatching("data/*/recipe/*.json") {
                filter { line ->
                    line.replace(Regex("\"id\"(\\s*:\\s*)\"([^\"]+)\""), "\"item\"\$1\"\$2\"")
                }
                path = path.replace("/recipe/", "/recipes/")
            }
            filesMatching("data/*/loot_table/**/*.json") {
                path = path.replace("/loot_table/", "/loot_tables/")
            }
        }

        exclude("META-INF/neoforge.mods.toml", "META-INF/mods.toml", "pack.mcmeta")

        // The Redstone Connector is hidden until it is finished (see REDSTONE_CONNECTOR in
        // ExcelComputer.java): its recipe, loot table, model and texture stay out too.
        val redstoneConnector = rootProject.file("src/main/java/io/github/reverendbrown/excelcomputer/ExcelComputer.java")
            .readText().contains("REDSTONE_CONNECTOR = true")
        inputs.property("redstoneConnector", redstoneConnector)
        if (!redstoneConnector) {
            exclude("**/redstone_connector.json", "**/redstone_connector.png")
        }
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
        // loomx.mod(Sources)Jar returns the jar task for the applied loom variant
        from(loomx.modJar.flatMap { it.archiveFile }, loomx.modSourcesJar.flatMap { it.archiveFile })
        into(rootProject.layout.buildDirectory.file("libs/${project.property("mod.version")}"))
    }
}
