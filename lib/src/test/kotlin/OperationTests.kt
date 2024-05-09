import io.github.joemama.atr.ClassInfo
import org.junit.jupiter.api.Test
import kotlin.time.measureTimedValue

class OperationTests {
    @Test
    fun inverseMappings() {
        val (proguard, _) = ParsingTests.getProguardMappings()
        val (inverted, time) = measureTimedValue { !proguard }
        println(time)

        for ((info, oldName) in inverted.classes) {
            assert(proguard.classes[ClassInfo(oldName)] == info.internalName)
        }

        for ((info, oldName) in inverted.methods) {
            assert(proguard.methods.entries.any { it.key.name == oldName && it.value == info.name })
        }

        for ((info, oldName) in inverted.fields) {
            assert(proguard.fields.entries.any { it.key.name == oldName && it.value == info.name })
        }
    }

    @Test
    fun composeMappings() {
        val (proguard, _) = ParsingTests.getProguardMappings()
        val (intermediary, _) = ParsingTests.getIntermediaryMappings()
        // to create intermediary -> proguard we need to invert intermediary and then compose with proguard
        val adapterMappings = proguard * !intermediary
        println(adapterMappings.classes.size)
        println(adapterMappings.fields.size)
        println(adapterMappings.methods.size)
        val (yarn, _) = ParsingTests.getYarnMappings()
        val adapter2 = proguard * !yarn
        println(adapter2.classes.size)
        println(adapter2.fields.size)
        println(adapter2.methods.size)
    }
}