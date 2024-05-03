import io.github.joemama.atr.ProguardMappings
import io.github.joemama.atr.TinyV1Mappings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class ParsingTests {
    @Test
    fun parseProguard() {
        assertEquals(8206, getProguardMappings().classes.size)
    }

    @Test
    fun parseTinyV1() {
        val file = String(this.javaClass.getResourceAsStream("intermediary.tiny")!!.readAllBytes())
        val tinyMappings = TinyV1Mappings(file)
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
}