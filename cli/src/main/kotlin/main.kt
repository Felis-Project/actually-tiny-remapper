package io.github.joemama.atr

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.help
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.path
import java.nio.file.Files
import java.nio.file.Path

enum class MappingFormat {
    TinyV1,
    Proguard
}

internal class AtrCommand : CliktCommand() {
    private val inputJar: Path by argument().path(mustExist = true, canBeDir = false, mustBeReadable = true)
        .help("The unmapped jar")
    private val mappingFormat: MappingFormat by argument().enum<MappingFormat> { it.name }
        .help("Any of: ${enumValues<MappingFormat>().contentToString()}")
    private val mappingFile: Path by argument().path(mustBeReadable = true, canBeDir = false, mustExist = true)
        .help("The file containing the mappings")
    private val outputJar: Path? by argument().path(canBeDir = false, mustBeWritable = true)
        .optional()
        .help("Where to output the remapped jar")
    private val outNs: String? by option("--outNs").help("The target namespace, currently only used by tiny mappings")

    override fun run() {
        val mappings = when (this.mappingFormat) {
            MappingFormat.TinyV1 -> TinyV1Mappings(
                Files.readString(this.mappingFile),
                outNs ?: throw IllegalArgumentException("You must specify an out namespace when remapping using TinyV1")
            )

            MappingFormat.Proguard -> ProguardMappings(Files.readString(this.mappingFile))
        }
        JarRemapper(inputJar).remap(mappings, this.outputJar)
    }
}

fun main(args: Array<String>) {
    AtrCommand().main(args)
}
