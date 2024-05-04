package io.github.joemama.atr

import org.objectweb.asm.Type

interface Mappings {
    operator fun get(oldName: String): ClassMappings?
    fun createRemapper(wrapperPool: ClassWrapperPool): AtrRemapper = AtrRemapper(this, wrapperPool)
}

class ProguardMappings(mappings: String) : Mappings {
    // unironically scary how this can fit in a one liner
    val classes: Map<String, ClassMappings> =
        mappings.lines()
            .filter { !it.startsWith("#") }
            .filter { it.isNotEmpty() }
            .chunkBy { !it.startsWith(" ") }.map { clazz ->
                // we get a class mapping first
                val parsedClass = parseClassInfo(clazz[0])
                // then field
                val fieldz = clazz.subList(1, clazz.size)
                    .filter { !it.contains("(") }
                    .map { parseFieldInfo(it) }
                // then methods
                val methodz = clazz.subList(1 + fieldz.size, clazz.size)
                    .filter { it.contains("(") }
                    .map { parseMethodInfo(it) }

                ClassMappings(parsedClass, fieldz, methodz)
            }
            .associateByTo(mutableMapOf()) { it.clazz.oldName }

    override operator fun get(oldName: String): ClassMappings? = this.classes[oldName]

    override fun toString(): String {
        return this.classes.toString()
    }

    private companion object {
        fun parseClassInfo(s: String): Class {
            val (name, oldName) = s.split("->").map { it.trim() }
            return Class(
                name = name.replace(".", "/"),
                oldName = oldName.split(":").first().replace(".", "/"),
            )
        }

        fun parseFieldInfo(s: String): Field {
            val (new, old) = s.split("->").map { it.trim() }
            val (type, name) = new.split(" ")
            return Field(
                name = name,
                type = type,
                oldName = old
            )
        }

        fun parseMethodInfo(method: String): Method {
            val importantPart = method.split(":").last()
            val (new, old) = importantPart.split("->").map(String::trim)
            val (returnType, etc) = new.split(" ")
            val (name, argTypes) = etc.split("(")
            val properArgTypes = argTypes.substring(0, argTypes.lastIndex)
            val parameters = properArgTypes.split(",").filter { it.isNotEmpty() }

            return Method(
                parameters = parameters,
                returnType = returnType,
                oldName = old,
                name = name
            )
        }
    }
}

class TinyV1Mappings(mappings: String, namespace: String) : Mappings {
    var classes: Map<String, ClassMappings>
        private set

    init {
        val lines = mappings.lineSequence().iterator()
        val header = lines.next().split('\t').iterator()
        check(header.next() == "v1") { "v1 header was not detected, aborting" }
        header.next() // 'from' namespace
        val toNss = header.asSequence().toList()
        val targetNsIndex = toNss.indexOf(namespace)
        if (targetNsIndex == -1) throw IllegalArgumentException("Namespace $namespace is not specified in mappings. Available targets are $toNss")
        val classes = hashMapOf<String, String>()
        val fields = hashMapOf<String, MutableList<Field>>()
        val methods = hashMapOf<String, MutableList<Method>>()

        while (lines.hasNext()) {
            val curr = lines.next()
            if (curr.startsWith('#') || curr.isEmpty()) continue

            val parts = curr.split('\t').listIterator()
            val type = parts.next()
            when (type) {
                "CLASS" -> {
                    val oldName = parts.next()
                    repeat(targetNsIndex) { parts.next() } // remove all other namespaces
                    val newName = Type.getObjectType(parts.next()).className
                    classes[oldName] = newName
                }

                "FIELD" -> {
                    val owner = parts.next() // owner class in old 'from' namespace
                    val desc = parts.next() // desc in old 'from' namespace
                    val old = parts.next()
                    repeat(targetNsIndex) { parts.next() }
                    val new = parts.next()
                    fields.getOrPut(owner, ::mutableListOf).add(Field(new, Type.getType(desc).className, old))
                }

                "METHOD" -> {
                    val owner = parts.next()
                    val desc = parts.next()
                    val old = parts.next()
                    repeat(targetNsIndex) { parts.next() }
                    val new = parts.next()
                    methods.getOrPut(owner, ::mutableListOf).add(Method(new, desc, old))
                }

                else -> throw IllegalStateException("How did we get here?")
            }
        }

        // also fix mapping types and descriptors
        this.classes = classes.entries.map { (old, new) ->
            val clazz = Class(new, old)

            val mappedFields = fields[old]?.map { (name, oldType, oldName) ->
                Field(name, classes[oldType] ?: oldType, oldName)
            } ?: emptyList()
            val mappedMethods = methods[old]?.map { (name, oldParams, oldReturnType, oldName) ->
                Method(
                    name,
                    oldParams.map { classes[it] ?: it },
                    classes[oldReturnType] ?: oldReturnType,
                    oldName
                )
            } ?: emptyList()

            ClassMappings(clazz, mappedFields, mappedMethods)
        }.associateBy { it.clazz.oldName }.toMap()
    }

    override fun get(oldName: String): ClassMappings? = this.classes[oldName]
}