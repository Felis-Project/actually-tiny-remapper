package io.github.joemama.atr

import org.objectweb.asm.*
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.*
import kotlin.streams.asSequence

fun <T> List<T>.chunkBy(pred: (T) -> Boolean): List<List<T>> {
    val res = mutableListOf<List<T>>()

    var curr = mutableListOf<T>()

    for (i in this) {
        if (pred(i)) {
            if (curr.isNotEmpty()) res.add(curr)
            curr = mutableListOf()
        }

        curr.add(i)
    }

    if (curr.isNotEmpty()) res.add(curr)

    return res
}

data class ClassWrapper(val name: String, val superClass: String?, val interfaces: List<String>) {
    val parents: List<String> by lazy {
        buildList {
            addAll(interfaces)
            superClass?.let { add(it) }
        }
    }
}

typealias ClassWrapperPool = Map<String, ClassWrapper>

class AtrRemapper(
    private val mappings: AtrMappings,
    private val wrapperPool: ClassWrapperPool,
) : Remapper() {
    override fun map(internalName: String): String =
        this.mappings.classes[ClassInfo(internalName)]?.name ?: internalName

    override fun mapMethodName(owner: String, name: String, descriptor: Descriptor): String {
        if (owner.startsWith("java/")) return name
        if (name == "<init>" || name == "<clinit>") return name

        val methodInfo = MethodInfo(InternalName(owner), name, descriptor)
        val mappedMethod = this.mappings.methods[methodInfo]

        if (mappedMethod != null) return mappedMethod

        // we look at the parents(if they exist)
        this.wrapperPool[owner]?.let { wrapper ->
            for (p in wrapper.parents) {
                val curr = this.mapMethodName(p, name, descriptor)
                // if we match then we can exit
                if (curr != name) {
                    return curr
                }
            }
        }

        // if we don't find it we have actually failed
        return name
    }

    // same login as #mapMethodName
    override fun mapFieldName(owner: String, name: String, descriptor: String): String {
        if (owner.startsWith("java/")) return name
        val fieldInfo = FieldInfo(InternalName(owner), name, descriptor)
        val mappedField = this.mappings.fields[fieldInfo]
        if (mappedField != null) return mappedField

        this.wrapperPool[owner]?.let { wrapper ->
            for (p in wrapper.parents) {
                val curr = this.mapFieldName(p, name, descriptor)
                // if we match then we can exit
                if (curr != name) {
                    return curr
                }
            }
        }

        return name
    }

    override fun mapRecordComponentName(owner: String, name: String, descriptor: String): String =
        this.mapFieldName(owner, name, descriptor)
}

class AtrClassRemapper(del: ClassVisitor, remapper: AtrRemapper) : ClassRemapper(del, remapper) {
    private lateinit var name: String
    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?
    ) {
        this.name = name
        super.visit(version, access, name, signature, superName, interfaces)
    }

    override fun visitSource(source: String?, debug: String?) {
        val mappedInternalName = this.remapper.map(name)
        val className = mappedInternalName.substringBefore("$").substringAfterLast("/") + ".java"
        super.visitSource(className, debug)
    }
}

class JarRemapper(private val jarFile: Path) {
    fun remap(mappings: AtrMappings, outputJar: Path? = null): Path {
        val jarPath = outputJar ?: this.jarFile.resolveSibling(this.jarFile.nameWithoutExtension + "-remapped.jar")
        if (jarPath.exists()) Files.delete(jarPath)

        FileSystems.newFileSystem(this.jarFile).use { originJar ->
            // first pass, parse hierarchy info
            // only classes may have hierarchy info so filter on that
            val wrapperPool = Files.walk(originJar.getPath("/")).asSequence()
                .filter { it.extension == "class" }
                .map { clazz ->
                    Files.newInputStream(clazz).use {
                        val reader = ClassReader(it)
                        ClassWrapper(reader.className, reader.superName, reader.interfaces.toList())
                    }
                }
                .associateBy { it.name }
                .toMap()

            val mapper = AtrRemapper(mappings, wrapperPool)
            // second pass, actually map
            FileSystems.newFileSystem(jarPath, mapOf("create" to "true")).use { outputJar ->
                for (file in Files.walk(originJar.rootDirectories.first()).filter { !it.isDirectory() }) {
                    // WARNING: Smoll(TM) possibly illegal hack to fix jar signing issues
                    if (file.extension == "SF" || file.extension == "RSA") continue
                    // if it's a class remap it
                    if (file.extension == "class") {
                        val reader = Files.newInputStream(file).use { ClassReader(it) }
                        val writer = ClassWriter(0)
                        val remapper = AtrClassRemapper(writer, mapper)
                        reader.accept(remapper, 0)

                        val path = outputJar.getPath(mapper.map(reader.className).replace(".", "/") + ".class")
                        path.createParentDirectories()
                        Files.newOutputStream(
                            path,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE
                        ).use { it.write(writer.toByteArray()) }
                    } else {
                        // else copy everything
                        val outputPath = outputJar.getPath(file.pathString)
                        outputPath.createParentDirectories()
                        Files.copy(file, outputPath, StandardCopyOption.COPY_ATTRIBUTES)
                    }
                }
            }
        }

        return jarPath
    }
}
