package io.github.joemama.atr

import org.objectweb.asm.Type

interface AtrParser {
    fun parse(): AtrMappings
}

interface AtrMappings {
    val fields: Map<FieldInfo, String>
    val methods: Map<MethodInfo, String>
    val classes: Map<ClassInfo, InternalName>

    fun mapClassInfo(classInfo: ClassInfo): ClassInfo = this.classes[classInfo]?.let { ClassInfo(it) } ?: classInfo

    fun mapFieldInfo(fieldInfo: FieldInfo): FieldInfo = this.fields[fieldInfo]?.let { newName ->
        FieldInfo(
            owner = this.classes[ClassInfo(fieldInfo.owner)] ?: fieldInfo.owner,
            name = newName,
            desc = this.classes[ClassInfo(Type.getType(fieldInfo.desc).internalName)]
                ?.let { Type.getObjectType(it.name) }
                ?.descriptor
                ?: fieldInfo.desc
        )
    } ?: fieldInfo

    fun mapMethodInfo(methodInfo: MethodInfo): MethodInfo = this.methods[methodInfo]?.let { newName ->
        MethodInfo(
            owner = this.classes[ClassInfo(methodInfo.owner)] ?: methodInfo.owner,
            name = newName,
            desc = Type.getMethodType(methodInfo.desc).let { type ->
                val returnType: String =
                    this.classes[ClassInfo(type.returnType.internalName)]?.name ?: type.returnType.internalName
                val argumentTypes: Array<Type> = type.argumentTypes.map {
                    this.classes[ClassInfo(it.internalName)]?.name ?: it.internalName
                }.map { Type.getObjectType(it) }.toTypedArray()

                Type.getMethodType(Type.getObjectType(returnType), *argumentTypes)
            }.descriptor
        )
    } ?: methodInfo

    // a * b a.times(b) a(b()) named * intermediary named(intermediary(obf))
    // to create a mapping from proguard to intermediary u would need:  proguard * !intermediary
    // if mappings don't define a value, the inner value is used instead
    // TODO: Perhaps makes this lazy
    operator fun times(others: AtrMappings): AtrMappings = MapBackedMappings(
        classes = others.classes.map { (classInfo, newName) ->
            Pair(classInfo, this.classes[others.mapClassInfo(classInfo)] ?: newName)
        }.toMap(),
        fields = others.fields.map { (fieldInfo, newName) ->
            Pair(fieldInfo, this.fields[others.mapFieldInfo(fieldInfo)] ?: newName)
        }.toMap(),
        methods = others.methods.map { (methodInfo, newName) ->
            Pair(methodInfo, this.methods[others.mapMethodInfo(methodInfo)] ?: newName)
        }.toMap()
    )

    operator fun not(): AtrMappings = MapBackedMappings(
        classes = this.classes.keys.associate { classInfo ->
            Pair(this.mapClassInfo(classInfo), classInfo.internalName)
        },
        fields = this.fields.keys.associate { fieldInfo ->
            Pair(this.mapFieldInfo(fieldInfo), fieldInfo.name)
        },
        methods = this.methods.keys.associate { methodInfo -> Pair(this.mapMethodInfo(methodInfo), methodInfo.name) }
    )
}

data class MapBackedMappings(
    override val classes: Map<ClassInfo, InternalName>,
    override val fields: Map<FieldInfo, String>,
    override val methods: Map<MethodInfo, String>,
) : AtrMappings

data class InternalName(val name: String) {
    init {
        if ('.' in name) throw IllegalArgumentException("Cannot have '.' in InternalNames")
    }
}

typealias Descriptor = String

data class FieldInfo(val owner: InternalName, val name: String, val desc: Descriptor)
data class MethodInfo(val owner: InternalName, val name: String, val desc: Descriptor)
data class ClassInfo(val internalName: InternalName) {
    constructor(internalName: String) : this(InternalName(internalName))
}

class ProguardParser(private val mappings: String) : AtrParser {
    private val classnameCache: MutableMap<String, Type> = hashMapOf()
    private fun getTypeFromClassName(name: String): Type = classnameCache.getOrPut(name) {
        when (name) {
            Type.INT_TYPE.className -> Type.INT_TYPE
            Type.BOOLEAN_TYPE.className -> Type.BOOLEAN_TYPE
            Type.FLOAT_TYPE.className -> Type.FLOAT_TYPE
            Type.VOID_TYPE.className -> Type.VOID_TYPE
            Type.CHAR_TYPE.className -> Type.CHAR_TYPE
            Type.BYTE_TYPE.className -> Type.BYTE_TYPE
            Type.SHORT_TYPE.className -> Type.SHORT_TYPE
            Type.LONG_TYPE.className -> Type.LONG_TYPE
            Type.DOUBLE_TYPE.className -> Type.DOUBLE_TYPE
            else -> {
                if (name.endsWith("[]")) {
                    // array
                    val typename = name.substringBeforeLast("[]")
                    val type = getTypeFromClassName(typename)
                    Type.getType("[${type.descriptor}")
                } else {
                    // object
                    Type.getObjectType(name.replace(".", "/"))
                }
            }
        }
    }

    private fun getReversedType(reverseClasses: Map<InternalName, InternalName>, type: Type): Type = when (type.sort) {
        Type.OBJECT -> {
            Type.getObjectType(reverseClasses[InternalName(type.internalName)]?.name ?: type.internalName)
        }

        Type.ARRAY -> {
            val element = this.getReversedType(reverseClasses, type.elementType)
            Type.getType("[${element.descriptor}")
        }

        else -> type
    }

    override fun parse(): AtrMappings {
        val classes: MutableMap<ClassInfo, InternalName> = hashMapOf()
        val reverseClasses: MutableMap<InternalName, InternalName> = hashMapOf()
        val intermediateFields: MutableMap<FieldInfo, String> = hashMapOf()
        val intermediateMethods: MutableMap<MethodInfo, String> = hashMapOf()

        for (clazz in mappings.lines().filter { !it.startsWith("#") }.filter { it.isNotEmpty() }
            .chunkBy { !it.startsWith(" ") }) {
            val lines = clazz.listIterator()
            val classDecl = lines.next()
            val (name, oldName) = classDecl.split("->").map { it.trim().replace(".", "/") }
            val oldClassName = InternalName(oldName.substringBefore(":"))
            val newClassName = InternalName(name)

            classes[ClassInfo(oldClassName)] = newClassName
            reverseClasses[newClassName] = oldClassName

            for (memberLine in lines) {
                if ('(' in memberLine) {
                    // method
                    // 304:312:java.lang.Object parseArgument(joptsimple.OptionSet,joptsimple.OptionSpec) -> a
                    val importantPart = memberLine.substringAfterLast(":")
                    val (new, oldMethodName) = importantPart.split("->").map(String::trim)
                    val (returnType, etc) = new.split(" ")
                    val (newMethodName, argTypes) = etc.split("(")
                    val properArgTypes = argTypes.substring(0, argTypes.lastIndex)
                    val parameters = properArgTypes.split(",").filter { it.isNotEmpty() }
                    val methodInfo = MethodInfo(
                        owner = oldClassName,
                        name = oldMethodName,
                        desc = Type.getMethodDescriptor(
                            this.getTypeFromClassName(returnType),
                            *parameters.map { this.getTypeFromClassName(it) }.toTypedArray()
                        )
                    )
                    intermediateMethods[methodInfo] = newMethodName
                } else {
                    //field
                    // com.mojang.authlib.properties.PropertyMap userProperties -> b
                    // split at -> then the left is split at space. First element is type second is name and third olf name
                    val (new, oldFieldName) = memberLine.split("->").map { it.trim() }
                    val (type, fieldName) = new.split(" ")
                    val fieldInfo = FieldInfo(oldClassName, oldFieldName, this.getTypeFromClassName(type).descriptor)
                    intermediateFields[fieldInfo] = fieldName
                }
            }
        }

        val fields = intermediateFields.mapKeys { (fieldInfo, _) ->
            val type = Type.getType(fieldInfo.desc)
            val mappedType = this.getReversedType(reverseClasses, type)
            FieldInfo(
                fieldInfo.owner,
                fieldInfo.name,
                mappedType.descriptor
            )
        }

        val methods = intermediateMethods.mapKeys { (methodInfo, _) ->
            val methodType = Type.getMethodType(methodInfo.desc)
            val returnType: Type = this.getReversedType(reverseClasses, methodType.returnType)
            val parameters: Array<Type> =
                methodType.argumentTypes.map { this.getReversedType(reverseClasses, it) }.toTypedArray()
            MethodInfo(
                methodInfo.owner,
                methodInfo.name,
                Type.getMethodDescriptor(
                    returnType,
                    *parameters
                )
            )
        }
        return MapBackedMappings(classes, fields, methods)
    }
}

class TinyV1MappingParser(private val mappings: String, private val namespace: String) : AtrParser {
    override fun parse(): AtrMappings {
        val lines = mappings.lineSequence().iterator()
        val header = lines.next().split('\t').iterator()
        check(header.next() == "v1") { "v1 header was not detected, aborting" }
        header.next() // 'from' namespace
        val toNss = header.asSequence().toList()
        val targetNsIndex = toNss.indexOf(namespace)
        if (targetNsIndex == -1) throw IllegalArgumentException("Namespace $namespace is not specified in mappings. Available targets are $toNss")

        val classes = hashMapOf<ClassInfo, InternalName>()
        val fields = hashMapOf<FieldInfo, String>()
        val methods = hashMapOf<MethodInfo, String>()

        while (lines.hasNext()) {
            val curr = lines.next()
            if (curr.startsWith('#') || curr.isEmpty()) continue

            val parts = curr.split('\t').listIterator()
            val type = parts.next()
            when (type) {
                "CLASS" -> {
                    val oldName = parts.next()
                    repeat(targetNsIndex) { parts.next() } // remove all other namespaces
                    val newName = parts.next()
                    classes[ClassInfo(oldName)] = InternalName(newName)
                }

                "FIELD" -> {
                    val owner = parts.next() // owner class in old 'from' namespace
                    val desc = parts.next() // desc in old 'from' namespace
                    val old = parts.next()
                    repeat(targetNsIndex) { parts.next() }
                    val new = parts.next()
                    val fieldInfo = FieldInfo(InternalName(owner), old, desc)
                    fields[fieldInfo] = new
                }

                "METHOD" -> {
                    val owner = parts.next()
                    val desc = parts.next()
                    val old = parts.next()
                    repeat(targetNsIndex) { parts.next() }
                    val new = parts.next()
                    val methodInfo = MethodInfo(InternalName(owner), old, desc)
                    methods[methodInfo] = new
                }

                else -> throw IllegalStateException("How did we get here?")
            }
        }

        return MapBackedMappings(classes, fields, methods)
    }
}
