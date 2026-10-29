package com.huanban.ar

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun zip(vararg entries: Pair<String, ByteArray>): File {
        val file = temporary.newFile()
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (name, bytes) ->
                out.putNextEntry(ZipEntry(name)); out.write(bytes); out.closeEntry()
            }
        }
        return file
    }
    @Test fun unicodeResourcesRetainRelativePaths() {
        val root = temporary.newFolder()
        val files = ModelArchive.models(zip("角色/人物.gltf" to "{}".toByteArray(), "角色/贴图/a.png" to byteArrayOf(1,2,3)), root)
        assertEquals("人物.gltf", files.single().name)
        assertArrayEquals(byteArrayOf(1,2,3), File(files.single().parentFile,"贴图/a.png").readBytes())
    }
    @Test fun traversalCannotWriteOutsideExtractionRoot() {
        val root = temporary.newFolder()
        assertThrows(IllegalArgumentException::class.java) { ModelArchive.models(zip("../escape.vrm" to byteArrayOf(1)), root) }
        assertFalse(File(root.parentFile,"escape.vrm").exists())
    }
    @Test fun duplicateFilesAreRejected() {
        val file = zip("a.vrm" to byteArrayOf(1), "./a.vrm" to byteArrayOf(2))
        assertThrows(IllegalArgumentException::class.java) { ModelArchive.models(file, temporary.newFolder()) }
    }
    @Test fun unknownPackageIsNotReportedAsSuccessfulImport() {
        assertThrows(IllegalArgumentException::class.java) { ModelArchive.models(zip("notes.txt" to byteArrayOf(1)), temporary.newFolder()) }
    }
}
