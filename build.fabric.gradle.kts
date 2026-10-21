plugins {
    // This plugin applies the correct loom variant based on the Minecraft version
    id("dev.kikugie.loom-back-compat")
}

// DO NOT set group = ...!
version = "${property("mod.version")}+${sc.current.version}"
base.archivesName = "${property("mod.id") as String}-fabric"

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
}

// Libraries bundled inside the mod jar. Minecraft already ships log4j, commons-io
// and commons-codec, and the game uses ITS copies at runtime (a newer copy
// nested in a mod does not replace them), so they are left out here. POI 5.2.5
// is used (not 5.4.1) because it works with the older commons-io that
// Minecraft 1.21.1 ships; 5.4.1 needs a newer commons-io and crashes with
// NoSuchMethodError outside the development environment.
val bundledLibraries = listOf(
    "org.apache.poi:poi-ooxml:5.2.5",
    "org.apache.poi:poi:5.2.5",
    "org.apache.xmlbeans:xmlbeans:5.2.0",
    "org.apache.commons:commons-collections4:4.4",
    "org.apache.poi:poi-ooxml-lite:5.2.5",
    "org.apache.commons:commons-math3:3.6.1"
).map { notation ->
    (dependencies.create(notation) as ExternalModuleDependency).apply {
        exclude(group = "org.apache.logging.log4j")
        exclude(group = "commons-io")
        exclude(group = "commons-codec")
    }
}

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
        }

        filesMatching("fabric.mod.json") { expand(props) }

        val mixinJava = "JAVA_${requiredJava.majorVersion}"
        filesMatching("*.mixins.json") { expand("java" to mixinJava) }

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

        exclude("META-INF/neoforge.mods.toml")
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
