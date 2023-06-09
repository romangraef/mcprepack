package mcprepack

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.architectury.pack200.java.Pack200
import lzma.sdk.lzma.Decoder
import lzma.sdk.lzma.Encoder
import lzma.streams.LzmaInputStream
import lzma.streams.LzmaOutputStream
import net.fabricmc.stitch.commands.tinyv2.*
import net.minecraftforge.mergetool.Merger
import net.minecraftforge.srgutils.IMappingFile
import net.minecraftforge.srgutils.INamedMappingFile
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Opcodes
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarOutputStream
import kotlin.io.path.*

val gson = GsonBuilder()
    .setPrettyPrinting()
    .create()

enum class Versions(val pubVersion: String, val accessVersion: String, val mcpVersion: String) {
    _10809("1.8.9", "1.8.9", "22-1.8.9"),
    _11200("1.12", "1.12", "39-1.12"),
    ;
}

@OptIn(ExperimentalPathApi::class)
fun main(): Unit = lifecycle("Repacking") {
    val mavenModulePub = "test"
    val whichOne = Versions._10809
    val pubVersion = whichOne.pubVersion
    val accessVersion = whichOne.accessVersion
    val mcpVersion = whichOne.mcpVersion

    WorkContext.setupWorkSpace()
    val downloadDebugFiles = true
    if (downloadDebugFiles) {
        WorkContext.getArtifact("net.minecraftforge", "forge", "1.19-41.1.0", "universal")
        WorkContext.getArtifact("net.minecraftforge", "forge", "1.19-41.1.0", "installer")
        WorkContext.getArtifact("de.oceanlabs.mcp", "mcp_config", "1.19-20220607.102129", extension = "zip")
        val userdev119 = WorkContext.getArtifact("net.minecraftforge", "forge", "1.19-41.1.0", "userdev")
        val patches119 = WorkContext.file("patches1.19", "jar").outputStream()
        LzmaInputStream(
            FileSystems.newFileSystem(userdev119).getPath("joined.lzma")
                .inputStream(), Decoder()
        ).copyTo(patches119)
        patches119.close()
    }

    val legacyForgeInstallerJar by lifecycleNonNull("Downloading Installer") {
        WorkContext.getArtifact("net.minecraftforge", "forge", "1.8.9-11.15.1.2318-1.8.9", "installer")
    }

    val legacyUserDev by lifecycleNonNull("Downloading userdev") {
        WorkContext.getArtifact("net.minecraftforge", "forge", "1.8.9-11.15.1.2318-1.8.9", "userdev")
            ?.let(FileSystems::newFileSystem)
    }
    val legacyForgeUniversal by lifecycleNonNull("Downloading universal") {
        WorkContext.getArtifact("net.minecraftforge", "forge", "1.8.9-11.15.1.2318-1.8.9", "universal")
            ?.let(FileSystems::newFileSystem)
    }
    val mcpStableFs by lifecycleNonNull("Downloading mcp stable") {
        WorkContext.getArtifact("de.oceanlabs.mcp", "mcp_stable", mcpVersion, extension = "zip")
            ?.let(FileSystems::newFileSystem)
    }
    val mcpSrgFs by lifecycleNonNull("Downloading mcp srg") {
        WorkContext.getArtifact("de.oceanlabs.mcp", "mcp", accessVersion, "srg", extension = "zip")
            ?.let(FileSystems::newFileSystem)
    }


    val variousTinies by lifecycle("Generate Tiny classes") {
        val classes = INamedMappingFile.load(Files.newInputStream(mcpSrgFs.getPath("joined.srg")))
        val tinyTemp = WorkContext.file("tiny-joined", "tiny")
        val tsrgTemp = WorkContext.file("tsrg-joined", "tsrg")
        classes.write(tinyTemp, IMappingFile.Format.TINY)
        classes.write(tsrgTemp, IMappingFile.Format.TSRG) // Write tsrg not tsrg2 so mojang mappings arent involved
        tinyTemp to tsrgTemp
    }
    val classesTiny by lazy { variousTinies.first }
    val classesTsrg by lazy { variousTinies.second }

    val minecraftManifest by lifecycle("Resolve minecraft manifest") {
        val f = WorkContext.file("version-manifest", "json")
        require(WorkContext.httpGet("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json", f))
        val vm = gson.fromJson(f.readText(), JsonObject::class.java)
        val version = vm["versions"].asJsonArray.map { it.asJsonObject }
            .find { it["id"].asString == accessVersion } ?: error("Could not find minecraft version $accessVersion")
        val cf = WorkContext.file("version", "json")
        require(WorkContext.httpGet(version["url"].asString, cf))
        gson.fromJson(cf.readText(), JsonObject::class.java)
    }

    val clientJar by lifecycle("Download client jar") {
        val f = WorkContext.file("minecraft-client", "jar")
        WorkContext.httpGet(minecraftManifest["downloads"].asJsonObject["client"].asJsonObject["url"].asString, f)
        f
    }

    val serverJar by lifecycle("Download server jar") {
        val f = WorkContext.file("minecraft-server", "jar")
        WorkContext.httpGet(minecraftManifest["downloads"].asJsonObject["server"].asJsonObject["url"].asString, f)
        f
    }

    val minecraftjar by lifecycle("Merge Minecraft Jar") {
        val f = WorkContext.file("minecraft-merged", "jar")
        Merger(clientJar.toFile(), serverJar.toFile(), f.toFile()).process()
        FileSystems.newFileSystem(f)
    }

    val classCache = mutableMapOf<String, Map<String, String>>()

    fun findFieldDescriptorInMergedJar(className: String, fieldName: String): String? {
        if (className in classCache) return classCache[className]!![fieldName]
        val fieldDescriptors = mutableMapOf<String, String>()
        val cr = ClassReader(Files.readAllBytes(minecraftjar.getPath("/$className.class")))
        cr.accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitField(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                value: Any?
            ): FieldVisitor? {
                fieldDescriptors[name] = descriptor
                return super.visitField(access, name, descriptor, signature, value)
            }
        }, 0)
        classCache[className] = fieldDescriptors
        return fieldDescriptors[fieldName]
    }


    val tinyV2Enhanced by lifecycle("Enhance tiny classes with methods and fields") {
        val params = readCSV(mcpStableFs.getPath("/params.csv"))
        val fields = readCSV(mcpStableFs.getPath("/fields.csv"))
        val methods = readCSV(mcpStableFs.getPath("/methods.csv"))
        val tinyFile = TinyV2Reader.read(classesTiny)
        fun commentOrEmptyList(comment: String?) =
            if (comment.isNullOrBlank()) listOf()
            else listOf(comment)

        val newTiny =
            TinyFile(TinyHeader(listOf("official", "intermediary", "named"), 2, 0, mapOf()), tinyFile.classEntries.map {
                TinyClass(it.classNames + listOf(it.classNames[1]), it.methods.map { method ->
                    val mcpMethod = methods.indexedBySearge[method.methodNames[1]]
                    TinyMethod(
                        method.methodDescriptorInFirstNamespace,
                        method.methodNames + listOf(mcpMethod?.get("name") ?: method.methodNames[1]),
                        method.parameters,
                        method.localVariables,
                        method.comments + commentOrEmptyList(mcpMethod?.get("desc"))
                    )
                    // TODO parameter names
                }, it.fields.map { field ->
                    val mcpField = fields.indexedBySearge[field.fieldNames[1]]
                    TinyField(
                        findFieldDescriptorInMergedJar(it.classNames[0], field.fieldNames[0]),
                        field.fieldNames + listOf(mcpField?.get("name") ?: field.fieldNames[1]),
                        field.comments + commentOrEmptyList(mcpField?.get("desc"))
                    )
                }, it.comments.map { it })
            })
        val newTinyFile = WorkContext.file("tiny-joined-enhanced", "tiny")
        TinyV2Writer.write(newTiny, newTinyFile)
        newTinyFile
    }

    val yarnCompatibleJar by lifecycle("Create v2 compatible \"yarn\" zip") {
        val x = WorkContext.file("yarn", "zip")
        Files.delete(x)
        val fs = FileSystems.newFileSystem(x, mapOf("create" to true))
        val mappingsPath = fs.getPath("/mappings/mappings.tiny")
        Files.createDirectories(mappingsPath.parent)
        Files.copy(tinyV2Enhanced, mappingsPath)
        fs.close()
        x
    }


    val binpatchesLegacy by lifecycle("Unpack binpatches") {
        val inputStream =
            LzmaInputStream(legacyForgeUniversal.getPath("/binpatches.pack.lzma").inputStream(), Decoder())
        val patchJar = WorkContext.file("binpatches", "jar")
        val patchOutput = JarOutputStream(patchJar.outputStream())
        Pack200.newUnpacker().unpack(inputStream, patchOutput)
        patchOutput.close()
        FileSystems.newFileSystem(patchJar)
    }

    // TODO merge binpatches somehow? not sure how that would work, maybe look at essential loom

    fun createBinPatchSubJar(dist: String): Path {
        val basePath = binpatchesLegacy.getPath("binpatch/$dist")
        val modernPatchJar = WorkContext.file("binpatches-modern", "jar")
        modernPatchJar.deleteExisting()
        val patchJarFs = FileSystems.newFileSystem(modernPatchJar, mapOf("create" to true))
        basePath.listDirectoryEntries()
            .filter { Files.isRegularFile(it) }
            .forEach {
                val to = patchJarFs.getPath("/${it.name}")
                it.copyTo(to)
            }
        patchJarFs.close()
        return modernPatchJar

    }

    val legacyDevJson by lazy {
        gson.fromJson(legacyUserDev.getPath("/dev.json").readText(), JsonObject::class.java) }

    val binpatchesModernClient by lifecycle("Modernize client binpatches") {
        createBinPatchSubJar("client")
    }

    val binpatchesModernServer by lifecycle("Modernize server binpatches") {
        createBinPatchSubJar("server")
    }

    val proguardLog by lifecycle("Create Proguard Obfuscation Log") {
        val x = WorkContext.file("proguard", "txt")
        x.bufferedWriter().use {
            val pro = ProguardWriter(it)
            val tinyFile = TinyV2Reader.read(tinyV2Enhanced)
            tinyFile.classEntries.forEach { tinyClass ->
                pro.visitClass(tinyClass.classNames[2], tinyClass.classNames[0])
                tinyClass.fields.forEach { field ->
                    val fd = FieldDescriptor.parseFieldDescriptor(field.fieldDescriptorInFirstNamespace)
                    pro.visitField(
                        field.fieldNames[2],
                        field.fieldNames[0],
                        fd.type.mapClassName(tinyFile).toProguardString()
                    )
                }
                tinyClass.methods.forEach { method ->
                    val md = MethodDescriptor.parseMethodDescriptor(method.methodDescriptorInFirstNamespace)
                    pro.visitMethod(
                        method.methodNames[2], method.methodNames[0],
                        md.arguments.map { it.mapClassName(tinyFile) }
                            .map { it.toProguardString() },
                        md.returnType.mapClassName(tinyFile).toProguardString()
                    )
                }
            }
        }
        x
    }

    val modernForgeInstaller by lifecycle("Create Modern Forge Installer") {
        val x = WorkContext.file("modern-forge-installer", "jar")
        x.deleteExisting()
        legacyForgeInstallerJar.copyTo(x)
        val fs = FileSystems.newFileSystem(x)
        legacyForgeUniversal.getPath("version.json").copyTo(fs.getPath("version.json"))

        fs.getPath("/data").createDirectories()
        fs.getPath("/data/client.lzma").outputStream().use {
            binpatchesModernClient.inputStream()
                .copyTo(LzmaOutputStream(it, Encoder()))
        }
        fs.getPath("/data/server.lzma").outputStream().use {
            binpatchesModernServer.inputStream()
                .copyTo(LzmaOutputStream(it, Encoder()))
        }
        fs.close()
        // TODO args and run scripts also potentially specify mappings in install_profile.json
        x
    }

    val modernForgeUserdev by lifecycle("Create Modern Forge Userdev") {
        val x = WorkContext.file("forge-modern-userdev", "jar")
        x.deleteExisting()
        val userdevModern = FileSystems.newFileSystem(x, mapOf("create" to true))
        val config = userdevModern.getPath("config.json")
        config.writeText(gson.toJson(JsonObject().apply {
            addProperty("mcp", "$mavenModulePub:mcp_config:$pubVersion")
            addProperty("binpatches", "joined.lzma")
            add("binpatcher", JsonObject().apply {
                addProperty("version", "net.minecraftforge:binarypatcher:1.1.1:fatjar")
                add("args", JsonArray().apply {
                    add("--clean")
                    add("{clean}")
                    add("--output")
                    add("{output}")
                    add("--apply")
                    add("{patch}")
                })
            })
            // TODO generic forge dependencies
            addProperty("universal", "net.minecraftforge:forge:1.8.9-11.15.1.2318-1.8.9:universal@jar")
            addProperty("sources", "net.minecraftforge:forge:1.8.9-11.15.1.2318-1.8.9:universal@jar")
            addProperty("spec", 2)//Hahaha, yes, I follow the spec, so true
            add("libraries", JsonArray().apply {
                legacyDevJson["libraries"].asJsonArray.forEach {
                    add(it.asJsonObject["name"])
                }
            })
            add("runs", JsonObject().apply {
                add("client", JsonObject().apply {
                    addProperty("name", "client")
                    addProperty("main", "net.minecraft.launchwrapper.Launch")
                    add("parents", JsonArray())
                    add("args", JsonArray().apply {
                        add("--accessToken")
                        add("undefined")
                        add("--assetsIndex")
                        add("{assets_index}")
                        add("--assetsDir")
                        add("{assets_root}")
                        add("--tweakClass")
                        add("net.minecraftforge.fml.common.launcher.FMLTweaker")
                    })
                    add("jvmArgs", JsonArray())
                    addProperty("client", true)
                    addProperty("forceExit", true)
                    add("env", JsonObject())
                    add("props", JsonObject())
                })
            })
            addProperty("spec", 2)
        }))
        userdevModern.getPath("/joined.lzma").outputStream().use {
            binpatchesModernClient.inputStream().copyTo(LzmaOutputStream(it, Encoder()))
        }
        userdevModern.getPath("/META-INF").createDirectories()
        legacyForgeUniversal.getPath("forge_at.cfg").copyTo(userdevModern.getPath("/META-INF/accesstransformer.cfg"))
        userdevModern.close()
        x
    }

    val mcpConfig by lifecycle("Create modern mcp_config") {
        val x = WorkContext.file("mcp_config-1.9.9", "jar")
        x.deleteExisting()
        val mcpConfigModern = FileSystems.newFileSystem(x, mapOf("create" to true))
        mcpConfigModern.getPath("config.json").writeText(gson.toJson(JsonObject().apply {
            addProperty("spec", 3)
            addProperty("version", "1.8.9")
            addProperty("official", true)
            addProperty("encoding", "UTF-8")
            add("data", JsonObject().apply {
                addProperty("mappings", "config/joined.tsrg")
                // TODO Inject and Patches
            })
            add("steps", JsonObject().apply {
                add("joined", JsonArray().apply {
                    add(JsonObject().apply { addProperty("type", "downloadClient") })
                    add(JsonObject().apply { addProperty("type", "downloadServer") })
                    add(JsonObject().apply { addProperty("type", "listLibraries") })
                    add(JsonObject().apply {
                        addProperty("type", "merge")
                        addProperty("client", "{downloadClientOutput}")
                        addProperty("server", "{downloadServerOutput}")
                        addProperty("version", "1.8.9")
                        addProperty("name", "rename")
                    })
                })
                for (dist in listOf("client", "server")) {
                    add(dist, JsonArray().apply {
                        add(JsonObject().apply { addProperty("type", "listLibraries") })
                        add(JsonObject().apply {
                            addProperty(
                                "type",
                                "download" + dist.replaceFirstChar { it.uppercase() })
                        })
                    })
                    // TODO rename in specific distro
                }
            })
            add("functions", JsonObject().apply {
                add("merge", JsonObject().apply {
                    addProperty("version", "net.minecraftforge:mergetool:1.1.5:fatjar")
                    add("jvmargs", JsonArray())
                    addProperty("repo", "https://maven.minecraftforge.net/")
                    add("args", JsonArray().apply {
                        listOf(
                            "--client",
                            "{client}",
                            "--server",
                            "{server}",
                            "--ann",
                            "{version}",
                            "--output",
                            "{output}",
                            "--inject",
                            "false"
                        ).forEach { add(it) }
                    })
                })
                add("rename", JsonObject().apply {
                    addProperty("version", "net.minecraftforge:ForgeAutoRenamingTool:0.1.22:all")
                    add("jvmargs", JsonArray())
                    addProperty("repo", "https://maven.minecraftforge.net/")
                    add("args", JsonArray().apply {
                        listOf(
                            "--input",
                            "{input}",
                            "--output",
                            "{output}",
                            "--map",
                            "{mappings}",
                            "--cfg",
                            "{libraries}",
                            "--ann-fix",
                            "--ids-fix",
                            "--src-fix",
                            "--record-fix"
                        ).forEach { add(it) }
                    })
                })
            })
        }))
        mcpConfigModern.getPath("/config").createDirectories()
        classesTsrg.copyTo(mcpConfigModern.getPath("/config/joined.tsrg"))
        mcpConfigModern.close()
        x
    }

    val mavenBasePath = Path.of(System.getProperty("user.home"), ".m2/repository")
    lifecycle("Publish to $mavenBasePath") {
        val modBasePath = mavenBasePath.resolve(mavenModulePub.replace(".", "/"))
        modBasePath.deleteRecursively()
        fun mavenFile(artifact: String, ext: String, classifier: String? = null) =
            modBasePath.resolve("$artifact/$pubVersion/$artifact-$pubVersion${if (classifier != null) "-$classifier" else ""}.$ext")
                .also { it.parent.createDirectories() }

        fun mkPom(artifact: String) =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd" xmlns="http://maven.apache.org/POM/4.0.0"
                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
              <modelVersion>4.0.0</modelVersion>
              <groupId>$mavenModulePub</groupId>
              <artifactId>$artifact</artifactId>
              <version>$pubVersion</version>
            </project>
        """.trimIndent()

        mavenFile("yarn", "pom").writeText(mkPom("yarn"))
        yarnCompatibleJar.copyTo(mavenFile("yarn", "jar"))
        yarnCompatibleJar.copyTo(mavenFile("yarn", "jar", "v2"))

        mavenFile("mcp_config", "pom").writeText(mkPom("mcp_config"))
        mcpConfig.copyTo(mavenFile("mcp_config", "jar"))

        mavenFile("forge", "pom").writeText(mkPom("forge"))
        modernForgeUserdev.copyTo(mavenFile("forge", "jar", "userdev"))
        modernForgeUserdev.copyTo(mavenFile("forge", "jar"))
        modernForgeInstaller.copyTo(mavenFile("forge", "jar", "installer"))

        proguardLog.copyTo(mavenFile("official", "txt"))
    }

    lifecycle("Generate gradle publication buildscript") {
        WorkContext.workDir.resolve("settings.gradle.kts").createFile()
        WorkContext.workDir.resolve("build.gradle.kts").writer().use {
            it.append(
                """
                plugins { `maven-publish` }
                
                val allGroupId = "moe.nea.mcp"
                
                publishing {
            """
            )
            data class Publication(val classifier: String?, val path: Path, val extension: String = "jar")

            fun pub(artifactId: String, version: String, artifacts: List<Publication>) {
                it.append(
                    """
                    publications.create<MavenPublication>("artifactId") {
                        ${
                        artifacts.map { (classifier, path, extension) ->
                            "this.artifact(file(${gson.toJson(path.toAbsolutePath().toString())})) {" +
                                    (if (classifier == null) ""
                                    else "this.classifier = ${gson.toJson(classifier)};") + "this.extension = ${
                                gson.toJson(
                                    extension
                                )
                            }}"
                        }.joinToString(separator = "\n")
                    }
                        this.groupId = allGroupId
                        this.artifactId = ${gson.toJson(artifactId)}
                        this.version = ${gson.toJson(version)}
                    }
                """
                )
            }

            pub(
                "mcp-yarn",
                pubVersion,
                listOf(
                    Publication(null, yarnCompatibleJar),
                    Publication("v2", yarnCompatibleJar),
                    Publication("official", proguardLog, "txt")
                )
            )
            it.append(
                """
                publications.filterIsInstance<MavenPublication>().forEach {
                    it.pom {
                        url.set("https://git.nea.moe/nea/mcprepack")
                    }
                }
                }"""
            )
        }
    }.executeNow()

}.executeNow()

fun readCSV(path: Path): CSVFile {
    val lines = Files.readAllLines(path)
    val headers = lines.first().split(",")
    val entries = lines.drop(1).map {
        it.split(",", limit = headers.size).map {
            if (it.firstOrNull() == '"') it.drop(1).dropLast(1).replace("\"\"", "\"") else it
        }
    }
    return CSVFile(headers, entries)
}
