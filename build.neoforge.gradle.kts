plugins {
    id("net.neoforged.moddev") version "2.0.147"
    id("neoforge-mutex")
}

version = "${property("mod.version")}+${sc.current.version}"
base.archivesName = "${property("mod.id") as String}-neoforge"

val requiredJava = when {
    sc.current.parsed >= "26.1" -> JavaVersion.VERSION_25
    sc.current.parsed >= "1.20.5" -> JavaVersion.VERSION_21
    sc.current.parsed >= "1.18" -> JavaVersion.VERSION_17
    sc.current.parsed >= "1.17" -> JavaVersion.VERSION_16
    else -> JavaVersion.VERSION_1_8
}

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

// Apache POI (reads and writes .xlsx files), bundled inside the mod jar with
// jar-in-jar. Each library is listed on its own, with the version range the
// mod accepts. Libraries Minecraft already provides (log4j, commons-io,
// commons-codec) are left out on purpose, because two copies of the same
// package make NeoForge refuse to start. POI 5.2.5 is used here (not 5.4.1)
// because it works with the older commons-io that Minecraft 1.21.1 ships;
// 5.4.1 needs a newer commons-io and crashes with NoSuchMethodError.
val bundledLibraries = listOf(
    Triple("org.apache.poi:poi", "5.2.5", "[5.2.5,6)"),
    Triple("org.apache.poi:poi-ooxml", "5.2.5", "[5.2.5,6)"),
    Triple("org.apache.poi:poi-ooxml-lite", "5.2.5", "[5.2.5,6)"),
    Triple("org.apache.xmlbeans:xmlbeans", "5.2.0", "[5.2.0,6)"),
    Triple("org.apache.commons:commons-collections4", "4.4", "[4.4,5)"),
    Triple("org.apache.commons:commons-math3", "3.6.1", "[3.6.1,4)"),
    Triple("org.apache.commons:commons-compress", "1.24.0", "[1.24.0,2)"),
    Triple("com.zaxxer:SparseBitSet", "1.3", "[1.3,2)"),
    Triple("com.github.virtuald:curvesapi", "1.08", "[1.08,2)")
).map { (coordinates, preferred, range) ->
    (dependencies.create(coordinates) as ExternalModuleDependency).apply {
        isTransitive = false
        version {
            strictly(range)
            prefer(preferred)
        }
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
            gameDirectory = rootProject.file("run/${project.name}")
            server()
        }
    }
}

// This block comes after neoForge { } on purpose: the NeoForge plugin only
// creates the "additionalRuntimeClasspath" configuration once its version is set.
dependencies {
    for (library in bundledLibraries) {
        implementation(library)
        jarJar(library)

        // Up to 1.21.8, NeoForge only loads extra libraries in the dev game
        // if they are on this list as well.
        if (sc.current.parsed < "1.21.9") add("additionalRuntimeClasspath", library)
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

        exclude("fabric.mod.json", "*.ct", "*.classtweaker")
    }

    named("createMinecraftArtifacts") {
        dependsOn("stonecutterGenerate")
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
        from(jar.flatMap { it.archiveFile }, named<Jar>("sourcesJar").flatMap { it.archiveFile })
        into(rootProject.layout.buildDirectory.file("libs/${project.property("mod.version")}"))
    }
}
