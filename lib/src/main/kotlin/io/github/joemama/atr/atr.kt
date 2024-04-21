package io.github.joemama.atr

import org.objectweb.asm.*
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.MethodRemapper
import org.objectweb.asm.commons.Remapper
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.*

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

data class Class(val name: String, val oldName: String, var superClass: String?, val interfaces: MutableList<String>) {
    val parents: List<String> by lazy {
        val res = mutableListOf(superClass)
        res.addAll(interfaces)
        res.filterNotNull()
    }

    companion object {
        fun fromString(s: String): Class {
            val (name, oldName) = s.split("->").map { it.trim() }
            return Class(
                name = name.replace(".", "/"),
                oldName = oldName.split(":").first().replace(".", "/"),
                // initialized by a class reader later
                superClass = null,
                interfaces = mutableListOf()
            )
        }
    }
}

data class Field(val name: String, val type: String, val oldName: String) {
    companion object {
        fun fromString(s: String): Field {
            val (new, old) = s.split("->").map { it.trim() }
            val (type, name) = new.split(" ")
            return Field(
                name = name,
                type = type,
                oldName = old
            )
        }
    }
}

data class Method(
    val parentClass: String,
    val name: String,
    val parameters: List<String>,
    val returnType: String,
    val oldName: String
) {
    companion object {
        fun fromString(classParent: String, method: String): Method {
            val importantPart = method.split(":").last()
            val (new, old) = importantPart.split("->").map(String::trim)
            val (returnType, etc) = new.split(" ")
            val (name, argTypes) = etc.split("(")
            val properArgTypes = argTypes.substring(0, argTypes.lastIndex)
            val parameters = properArgTypes.split(",").filter { it.isNotEmpty() }

            return Method(
                parentClass = classParent,
                parameters = parameters,
                returnType = returnType,
                oldName = old,
                name = name
            )
        }
    }
}

class ProguardMappings(mappings: File) {
    // unironically scary how this can fit in a one liner
    private val classes: Map<String, ClassMappings> =
        mappings.readLines().filter { !it.startsWith("#") }.chunkBy { !it.startsWith(" ") }.map { clazz ->
            // we get a class mapping first
            val parsedClass = Class.fromString(clazz[0])
            // then field
            val fieldz = clazz.subList(1, clazz.size)
                .filter { !it.contains("(") }
                .map(Field.Companion::fromString)
            // then methods
            val methodz = clazz.subList(1 + fieldz.size, clazz.size)
                .filter { it.contains("(") }
                .map { Method.fromString(parsedClass.name, it) }

            ClassMappings(parsedClass, fieldz, methodz)
        }.associateByTo(mutableMapOf()) { it.clazz.oldName }

    operator fun get(oldName: String): ClassMappings? = this.classes[oldName]
}

class ProguardRemapper(mappings: File) : Remapper() {
    val proguardMappings = ProguardMappings(mappings)

    override fun map(internalName: String): String = this.proguardMappings[internalName]?.clazz?.name ?: internalName
    override fun mapMethodName(owner: String, name: String, descriptor: String): String {
        if (name == "<init>" || name == "<clinit>") return name
        // we are not in our mappings if null
        val ownerClass = this.proguardMappings[owner] ?: return name
        // try to find in *this* class
        val newName = ownerClass.methods.find { this.doesMethodMatch(it, name, descriptor) }?.name
        // if not null we found it here
        if (newName != null) return newName

        // otherwise we look at the parents
        for (p in ownerClass.clazz.parents) {
            val curr = this.mapMethodName(p, name, descriptor)
            // if we match then we can exit
            if (curr != name) {
                return curr
            }
        }

        // if we don't we have actually failed
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
        val ownerClass = this.proguardMappings[owner] ?: return name
        val newName = ownerClass.fields.find { this.doesFieldMatch(it, name, descriptor) }?.name
        if (newName != null) return newName

        for (p in ownerClass.clazz.parents) {
            val res = this.mapFieldName(p, name, descriptor)
            if (res != name) {
                return res
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

class VariableRenamingMethodVisitor(visitor: MethodVisitor, remapper: Remapper) : MethodRemapper(visitor, remapper) {
    private var internalParameterCount = 0

    // private var internalVariableCount = 0
    override fun visitParameter(name: String, access: Int) =
        super.visitParameter("p${this.internalParameterCount++}", access)
}

class LoaderMakeClassRemapper(visitor: ClassVisitor, remapper: Remapper) : ClassRemapper(visitor, remapper) {
    override fun createMethodRemapper(methodVisitor: MethodVisitor): MethodVisitor {
        return VariableRenamingMethodVisitor(methodVisitor, this.remapper)
    }

    override fun visitSource(source: String?, debug: String?) {
        super.visitSource(this.remapper.map(this.className).replace("/", ".") + ".java", debug)
    }
}

class JarRemapper(private val jarFile: Path) {
    fun remap(mappings: File): Path {
        val jarPath = this.jarFile.resolveSibling(this.jarFile.nameWithoutExtension + "-remapped.jar")
        if (jarPath.exists()) return jarPath

        println("Remapping $jarFile using ${mappings.path}")
        val mapper = ProguardRemapper(mappings)

        FileSystems.newFileSystem(this.jarFile).use { originJar ->
            // first pass, parse hierarchy info
            // only classes may have hierarchy info so filter on that
            for (clazz in Files.walk(originJar.getPath("/")).filter { it.extension == "class" }) {
                Files.newInputStream(clazz).use {
                    val reader = ClassReader(it)
                    mapper.proguardMappings[reader.className]?.let { classMappings ->
                        classMappings.clazz.superClass = reader.superName
                        classMappings.clazz.interfaces.addAll(reader.interfaces)
                    }
                }
            }

            // second pass, actually map
            FileSystems.newFileSystem(jarPath, mapOf("create" to "true")).use { outputJar ->
                for (file in Files.walk(originJar.rootDirectories.first()).filter { !it.isDirectory() }) {
                    // WARNING: Smoll(TM) possibly illegal hack to fix jar signing issues
                    if (file.extension == "SF" || file.extension == "RSA") continue
                    // if it's a class remap it
                    if (file.extension == "class") {
                        val reader = Files.newInputStream(file).use { ClassReader(it) }
                        val writer = ClassWriter(0)
                        val remapper = LoaderMakeClassRemapper(writer, mapper)
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
