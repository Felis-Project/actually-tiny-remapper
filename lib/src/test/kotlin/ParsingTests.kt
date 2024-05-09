import io.github.joemama.atr.AtrMappings
import io.github.joemama.atr.ProguardParser
import io.github.joemama.atr.TinyV1MappingParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.objectweb.asm.Type
import kotlin.time.TimedValue
import kotlin.time.measureTimedValue

class ParsingTests {
    @Test
    fun parseProguard() {
        val (proguard, time) = getProguardMappings()
        println(time)
        assertEquals(8206, proguard.classes.size)
        assertDoesNotThrow {
            for (methodInfo in proguard.methods.keys) {
                Type.getType(methodInfo.desc).returnType
                Type.getType(methodInfo.desc).argumentTypes
            }
        }
        assertDoesNotThrow {
            for (fieldInfo in proguard.fields.keys) {
                Type.getType(fieldInfo.desc).internalName
            }
        }
    }

    @Test
    fun parseTinyV1() {
        val (tinyMappings, time) = getIntermediaryMappings()
        println(time)
        val (proguardMappings, _) = getProguardMappings()
        for (clazz in proguardMappings.classes.keys.filter { it.internalName.name.length <= 3 }) {
            assertNotNull(tinyMappings.classes[clazz]) { "Could not find class $clazz in tiny mappings" }
        }
        assertEquals(8181, tinyMappings.classes.size)
    }

    companion object {
        fun getProguardMappings(): TimedValue<AtrMappings> {
            val file = String(ParsingTests::class.java.getResourceAsStream("proguard.txt")!!.readAllBytes())
            return measureTimedValue { ProguardParser(file).parse() }
        }

        fun getIntermediaryMappings(): TimedValue<AtrMappings> {
            val file = String(ParsingTests::class.java.getResourceAsStream("intermediary.tiny")!!.readAllBytes())
            return measureTimedValue { TinyV1MappingParser(file, "intermediary").parse() }
        }

        fun getYarnMappings(): TimedValue<AtrMappings> {
            val file = String(ParsingTests::class.java.getResourceAsStream("mappings.tiny")!!.readAllBytes())
            return measureTimedValue { TinyV1MappingParser(file, "named").parse() }
        }
    }

    @Test
    fun testTinyV1Namespaces() {
        val file = String(this.javaClass.getResourceAsStream("mappings.tiny")!!.readAllBytes())
        val intermediary = TinyV1MappingParser(file, "intermediary").parse()
        val named = TinyV1MappingParser(file, "named").parse()
        assertEquals(intermediary.classes.size, named.classes.size)
        var unmapped = 0
        for (clazz in intermediary.classes.keys) {
            assertNotNull(named.classes[clazz])
            if (intermediary.classes[clazz] == named.classes[clazz]) {
                println("Class ${intermediary.classes[clazz]} was unmapped")
                unmapped++
            }
        }
        println(unmapped)
    }
}