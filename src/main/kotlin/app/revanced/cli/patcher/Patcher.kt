package app.revanced.cli.patcher

import app.revanced.cli.command.MainCommand.args
import app.revanced.cli.command.MainCommand.logger
import app.revanced.patcher.PatcherResult
import app.revanced.utils.filesystem.ZipFileSystemUtils
import app.revanced.utils.patcher.addPatchesFiltered
import app.revanced.utils.patcher.applyPatchesVerbose
import app.revanced.utils.patcher.mergeFiles
import java.io.File

internal object Patcher {
    internal fun start(patcher: app.revanced.patcher.Patcher, outputPath: File): List<File> {
        val patchingArgs = args.patchArgs?.patchingArgs!!

        // merge files like necessary integrations
        patcher.mergeFiles()
        // add patches, but filter incompatible or excluded patches
        patcher.addPatchesFiltered()
        // apply patches
        patcher.applyPatchesVerbose()

        val outputs = mutableListOf<Pair<File, PatcherResult>>()
        for (result in patcher.save()) {
            val f = outputPath.resolve("${result.file.nameWithoutExtension}_raw.apk")
            result.file.copyTo(f)
            outputs.add(f to result)
        }

        for (resultPair in outputs) {
            val output = resultPair.first
            val result = resultPair.second

            ZipFileSystemUtils(output).use { outputFileSystem ->
                // replace all dex files
                result.dexFiles.forEach {
                    logger.info("Writing dex file ${it.name}")
                    outputFileSystem.write(it.name, it.stream.readAllBytes())
                }
                patchingArgs.ripLibs.forEach {
                    try {
                        outputFileSystem.deleteRecursively("lib/$it")
                        logger.info("Ripped $it libs")
                    } catch (_: Exception) {
                    }
                }

                if (!patchingArgs.disableResourcePatching) {
                    logger.info("Writing resources...")

                    ZipFileSystemUtils(result.resourceFile!!).use { resourceFileSystem ->
                        val resourceFiles = resourceFileSystem.getFile(File.separator)
                        outputFileSystem.writePathRecursively(resourceFiles)
                    }
                }

                result.doNotCompress?.let { outputFileSystem.uncompress(*it.toTypedArray()) }
            }
        }
        return outputs.map { it.first }
    }
}
