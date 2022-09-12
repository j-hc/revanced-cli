package app.revanced.cli.patcher

import app.revanced.cli.command.MainCommand.args
import app.revanced.cli.command.MainCommand.logger
import app.revanced.patcher.data.Data
import app.revanced.patcher.patch.Patch
import app.revanced.utils.patcher.addPatchesFiltered
import app.revanced.utils.patcher.applyPatchesVerbose
import app.revanced.utils.patcher.mergeFiles
import app.revanced.utils.zipUtil.ZipUtil
import java.io.File

internal object Patcher {
    internal fun start(patcher: app.revanced.patcher.Patcher, output: File, allPatches: List<Class<out Patch<Data>>>) {
        val inputFile = args.inputFile
        val args = args.patchArgs?.patchingArgs!!

        // merge files like necessary integrations
        patcher.mergeFiles()
        // add patches, but filter incompatible or excluded patches
        patcher.addPatchesFiltered(allPatches)
        // apply patches
        patcher.applyPatchesVerbose()

        val result = patcher.save()
        ZipUtil(output).use { outputFileSystem ->
            // replace all dex files
            result.dexFiles.forEach {
                logger.info("Writing dex file ${it.name}")
                outputFileSystem.write(it.name, it.stream.readAllBytes(), true)
            }
            val doNotCompress = result.doNotCompress.orEmpty()
            if (!args.disableResourcePatching) {
                logger.info("Writing resources...")
                result.resourceFile?.let {
                    outputFileSystem.copyFromZip(it, doNotCompress)
                }
            }
            outputFileSystem.copyFromZip(inputFile, doNotCompress)
        }
    }
}
