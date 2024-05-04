import io.github.joemama.atr.ProguardMappings
import io.github.joemama.atr.TinyV1Mappings
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.sign

class ParsingTests {
    @Test
    fun parseProguard() {
        assertEquals(8206, getProguardMappings().classes.size)
    }

    @Test
    fun parseTinyV1() {
        val file = String(this.javaClass.getResourceAsStream("intermediary.tiny")!!.readAllBytes())
        val tinyMappings = TinyV1Mappings(file, "intermediary")
        val proguardMappings = getProguardMappings()
        for (clazz in proguardMappings.classes.keys.filter { it.length <= 3 }) {
            assertNotNull(tinyMappings[clazz]) { "Could not find class $clazz in tiny mappings" }
        }
        assertEquals(8181, tinyMappings.classes.size)
    }

    private fun getProguardMappings(): ProguardMappings {
        val file = String(this.javaClass.getResourceAsStream("proguard.txt")!!.readAllBytes())
        return ProguardMappings(file)
    }

    @Test
    fun testTinyV1Namespaces() {
        val file = String(this.javaClass.getResourceAsStream("mappings.tiny")!!.readAllBytes())
        val intermediary = TinyV1Mappings(file, "intermediary")
        val named = TinyV1Mappings(file, "named")
        assertEquals(intermediary.classes.size, named.classes.size)
        var unmapped = 0
        for (clazz in intermediary.classes.keys) {
            assertNotNull(named[clazz])
            if (intermediary[clazz] == named[clazz]) {
                println("Class ${intermediary[clazz]} was unmapped")
                unmapped++
            }
        }
        println(unmapped)
    }
}