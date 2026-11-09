import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Leaves the parts of Apache POI the mod never uses (Word, PowerPoint, Visio,
 * signatures) out of the mod jar. The folders are listed in gradle/poi-trim.txt;
 * each goes together with xmlbeans' name index for it.
 *
 * NeoForge and Forge shade POI, so they pass [patterns] to shadowJar's exclude.
 * Fabric nests whole library jars (Loom only nests Maven artifacts, not files
 * made by the build), so [trimNestedJars] rewrites the nested copies inside the
 * finished mod jar instead.
 */
object PoiTrim {
    private const val NAME_INDEX = "org/apache/poi/schemas/ooxml/javaname/"

    // Services that name the removed PowerPoint classes.
    private val services = listOf(
        "META-INF/services/org.apache.poi.sl.usermodel.SlideShowProvider",
        "META-INF/services/org.apache.poi.sl.usermodel.MetroShapeProvider"
    )

    fun folders(list: File): List<String> = list.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }

    /** Ant-style patterns for a jar task's exclude(). */
    fun patterns(list: File): List<String> =
        folders(list).flatMap { listOf("$it/**", "$NAME_INDEX$it/**") } + services

    /**
     * Rewrites the jars nested in [modJar] (META-INF/jars/<name>-<version>.jar)
     * whose name is in [libraries], without the listed parts.
     */
    fun trimNestedJars(modJar: File, list: File, libraries: Set<String>) {
        val prefixes = folders(list).flatMap { listOf("$it/", "$NAME_INDEX$it/") }
        val nested = Regex("META-INF/jars/(.+)-\\d[^/]*\\.jar")
        val temp = File(modJar.path + ".trimming")

        ZipFile(modJar).use { zip ->
            ZipOutputStream(temp.outputStream().buffered()).use { out ->
                for (entry in zip.entries()) {
                    var bytes = zip.getInputStream(entry).readBytes()
                    val library = nested.matchEntire(entry.name)?.groupValues?.get(1)

                    if (library != null && library in libraries) {
                        bytes = trimJar(bytes, prefixes)
                    }

                    out.putNextEntry(ZipEntry(entry.name).apply { time = entry.time })
                    out.write(bytes)
                    out.closeEntry()
                }
            }
        }

        Files.move(temp.toPath(), modJar.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun trimJar(jar: ByteArray, prefixes: List<String>): ByteArray {
        val result = ByteArrayOutputStream(jar.size)

        ZipInputStream(jar.inputStream()).use { input ->
            ZipOutputStream(result).use { out ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val name = entry.name

                    if (name in services || prefixes.any { name.startsWith(it) }) {
                        continue
                    }

                    out.putNextEntry(ZipEntry(name).apply { time = entry.time })
                    input.copyTo(out)
                    out.closeEntry()
                }
            }
        }

        return result.toByteArray()
    }
}
