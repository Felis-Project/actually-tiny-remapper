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

data class ClassMappings(val clazz: Class, val fields: List<Field>, val methods: List<Method>)

data class ClassWrapper(val name: String, val superClass: String?, val interfaces: List<String>) {
    val parents: List<String> by lazy {
        buildList {
            addAll(interfaces)
            superClass?.let { add(it) }
        }
    }
}

typealias ClassWrapperPool = Map<String, ClassWrapper>

data class Class(val name: String, val oldName: String) {
    val internalName by lazy { name.replace(".", "/") }
}

data class Field(val name: String, val type: String, val oldName: String)

data class Method(
    val name: String,
    val parameters: List<String>,
    val returnType: String,
    val oldName: String
) {
    constructor(name: String, desc: String, oldName: String) : this(
        name,
        Type.getMethodType(desc).argumentTypes.map { it.className }.toList(),
        Type.getMethodType(desc).returnType.className,
        oldName
    )
}

class AtrRemapper(
    private val mappings: Mappings,
    private val wrapperPool: Map<String, ClassWrapper>
) : Remapper() {
    override fun map(internalName: String): String = this.mappings[internalName]?.clazz?.internalName ?: internalName
    override fun mapMethodName(owner: String, name: String, descriptor: String): String {
        if (name == "<init>" || name == "<clinit>") return name
        // we are not in our mappings if null
        val ownerClass = this.mappings[owner] ?: return name
        // try to find in *this* class
        val newName = ownerClass.methods.find { this.doesMethodMatch(it, name, descriptor) }?.name
        // if not null we found it here
        if (newName != null) return newName

        // otherwise we look at the parents(if they exist)
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

    private fun doesMethodMatch(method: Method, name: String, descriptor: String): Boolean =
        Type.getMethodType(this.mapDesc(descriptor)).let { type ->
            method.oldName == name && // match our names
                    method.returnType == type.returnType.className && // match our return types
                    method.parameters.size == type.argumentTypes.size && // match our parameter count
                    method.parameters.indices.all { method.parameters[it] == type.argumentTypes[it].className } // match our parameter types
        }

    // same login as #mapMethodName
    override fun mapFieldName(owner: String, name: String, descriptor: String): String {
        val ownerClass = this.mappings[owner] ?: return name
        val newName = ownerClass.fields.find { this.doesFieldMatch(it, name, descriptor) }?.name
        if (newName != null) return newName

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

    private fun doesFieldMatch(field: Field, name: String, descriptor: String): Boolean =
        Type.getType(this.mapDesc(descriptor)).let { type ->
            field.oldName == name && field.type == type.className
        }

    override fun mapRecordComponentName(owner: String, name: String, descriptor: String): String {
        return this.mapFieldName(owner, name, descriptor)
    }
}

//class VariableRenamingMethodVisitor(visitor: MethodVisitor, remapper: Remapper) : MethodRemapper(visitor, remapper) {
//    private var internalParameterCount = 0
//
//    // private var internalVariableCount = 0
//    override fun visitParameter(name: String?, access: Int) =
//        super.visitParameter("p${this.internalParameterCount++}", access)
//
//    override fun visitLocalVariable(
//        name: String,
//        descriptor: String,
//        signature: String?,
//        start: Label,
//        end: Label,
//        index: Int
//    ) {
//        super.visitLocalVariable(name, descriptor, signature, start, end, index)
//    }
//}

class JarRemapper(private val jarFile: Path) {
    fun remap(mappings: Mappings): Path {
        val jarPath = this.jarFile.resolveSibling(this.jarFile.nameWithoutExtension + "-remapped.jar")
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

            val mapper = mappings.createRemapper(wrapperPool)
            // second pass, actually map
            FileSystems.newFileSystem(jarPath, mapOf("create" to "true")).use { outputJar ->
                for (file in Files.walk(originJar.rootDirectories.first()).filter { !it.isDirectory() }) {
                    // WARNING: Smoll(TM) possibly illegal hack to fix jar signing issues
                    if (file.extension == "SF" || file.extension == "RSA") continue
                    // if it's a class remap it
                    if (file.extension == "class") {
                        val reader = Files.newInputStream(file).use { ClassReader(it) }
                        val writer = ClassWriter(0)
                        val remapper = ClassRemapper(writer, mapper)
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
