package app.revanced.utils.zipUtil

import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream


internal class ZipUtil(
    outputFile: File,
) : Closeable {
    private var zipOutputStream = ZipOutputStream(FileOutputStream(outputFile))

    internal fun copyFromZip(path: File, doNotCompress: List<String>) {
        val zipInputStream = ZipInputStream(FileInputStream(path))

        var inputZe = zipInputStream.nextEntry
        while (inputZe != null) {
            val zeName = inputZe.name
            val ze = ZipEntry(zeName)
            if (doNotCompress.contains(zeName)) {
                ze.method = ZipEntry.STORED
                ze.size = inputZe.size
                ze.compressedSize = inputZe.compressedSize
                ze.crc = inputZe.crc
            } else {
                ze.method = ZipEntry.DEFLATED
            }
            val content = zipInputStream.readAllBytes()
            write(ze, content)
            inputZe = zipInputStream.nextEntry
        }
        zipInputStream.close()

    }

    private fun write(ze: ZipEntry, content: ByteArray) {
        try {
            zipOutputStream.putNextEntry(ze)
            zipOutputStream.write(content)
            zipOutputStream.closeEntry()
        } catch (e: Exception) {
            // ignore duplicate entry
        }
    }

    internal fun write(path: String, content: ByteArray, isCompressed: Boolean) {
        val ze = ZipEntry(path)
        ze.method = if (isCompressed) ZipEntry.DEFLATED else ZipEntry.STORED
        write(ze, content)
    }

    override fun close() = zipOutputStream.close()
}
