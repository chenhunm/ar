package com.huanban.ar

import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile

/** Bounded ZIP/7z extraction; no archive attributes or links are installed. */
object ModelArchive {
    private val seven = byteArrayOf(0x37,0x7a,0xbc.toByte(),0xaf.toByte(),0x27,0x1c)
    fun isArchive(bytes: ByteArray) = bytes.take(6).toByteArray().contentEquals(seven) ||
        (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte())

    fun models(input: File, destination: File): List<File> {
        destination.mkdirs()
        var total = 0L
        var entries = 0
        fun extract(archive: File, root: File, depth: Int) {
            require(depth <= 3) { "压缩包嵌套超过 3 层" }
            root.mkdirs()
            fun target(name: String, directory: Boolean): File {
                require(++entries <= 2048) { "压缩包文件数量超过 2048" }
                val path = name.replace('\\','/').trimEnd('/')
                require(path.length in 1..512 && !path.startsWith('/') && !path.contains(':') &&
                    path.split('/').none { it == ".." || it.isEmpty() } && path.split('/').size <= 32) { "压缩包包含不安全路径" }
                val result = File(root, path)
                require(result.canonicalPath.startsWith(root.canonicalPath + File.separator))
                require(!result.exists() || (directory && result.isDirectory)) { "压缩包包含重复路径：$path" }
                return result
            }
            fun copy(stream: InputStream, file: File) {
                file.parentFile!!.mkdirs()
                file.outputStream().use { out ->
                    val buffer = ByteArray(32768)
                    while (true) {
                        val n = stream.read(buffer)
                        if (n < 0) break
                        total += n
                        require(total <= ModelCodec.MAX_BYTES) { "压缩包展开后超过 64 MB" }
                        out.write(buffer,0,n)
                    }
                }
            }
            val header = ByteArray(6).also { bytes -> java.io.DataInputStream(archive.inputStream()).use { it.readFully(bytes) } }
            if (header.contentEquals(seven)) {
                SevenZFile.builder().setFile(archive).setMaxMemoryLimitKiB(98304).get().use { z ->
                    while (true) {
                        val entry = z.nextEntry ?: break
                        val file = target(entry.name ?: error("压缩包条目缺少名称"), entry.isDirectory)
                        if (entry.isDirectory) file.mkdirs() else {
                            require(entry.size in 0..ModelCodec.MAX_BYTES.toLong()) { "压缩包单个文件过大" }
                            copy(object : InputStream() {
                                override fun read() = z.read()
                                override fun read(b: ByteArray, off: Int, len: Int) = z.read(b,off,len)
                            },file)
                        }
                    }
                }
            } else {
                require(isArchive(header)) { "不是有效 ZIP / 7z 压缩包" }
                ZipInputStream(archive.inputStream()).use { z ->
                    while (true) {
                        val entry = z.nextEntry ?: break
                        val file = target(entry.name, entry.isDirectory)
                        if (entry.isDirectory) file.mkdirs() else copy(z,file)
                        z.closeEntry()
                    }
                }
            }
            root.walkTopDown().filter { it.isFile }.toList().forEach { file ->
                if (file.extension.lowercase() in setOf("zip","7z")) {
                    val nested = File(file.parentFile, file.name + ".unpacked")
                    require(!nested.exists()) { "压缩包目录冲突" }
                    extract(file,nested,depth+1)
                }
            }
        }
        extract(input,destination,1)
        return destination.walkTopDown().filter { it.isFile && it.extension.lowercase() in setOf("glb","gltf","vrm") }.toList()
            .also { require(it.isNotEmpty()) { "包内没有 GLB / GLTF / VRM 模型；其他格式尚需转换" } }
    }
}
