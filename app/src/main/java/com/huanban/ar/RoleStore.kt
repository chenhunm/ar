package com.huanban.ar

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class Role(
    val id: String,
    val name: String,
    val file: String,
    val scale: Float = 1f,
    val action: Int = 0,
    val warning: String = "",
    val animations: List<String> = emptyList(),
    val builtin: Boolean = false,
) {
    fun json() =
        JSONObject()
            .put("id", id)
            .put("name", name)
            .put("file", file)
            .put("scale", scale)
            .put("action", action)
            .put("warning", warning)
            .put("animations", JSONArray(animations))
            .put("builtin", builtin)

    companion object {
        fun from(j: JSONObject) =
            Role(
                j.getString("id"),
                j.getString("name"),
                j.getString("file"),
                j.optDouble("scale", 1.0).toFloat().coerceIn(.1f, 3f),
                j.optInt("action"),
                j.optString("warning"),
                (j.optJSONArray("animations") ?: JSONArray()).let { a ->
                    (0 until a.length()).map { a.getString(it) }
                },
                j.optBoolean("builtin"),
            )
    }
}

class RoleStore(val context: Context) {
    companion object {
        private val globalLock = Any()
    }

    private val dir = File(context.filesDir, "roles").apply { mkdirs() }
    private val db = File(dir, "roles.json")
    private val prefs = context.getSharedPreferences("settings", 0)

    fun roles(): List<Role> {
        if (!db.exists()) return emptyList()
        return try {
            val a = JSONArray(db.readText())
            (0 until a.length())
                .map { Role.from(a.getJSONObject(it)) }
                .filter { File(dir, it.file).isFile }
        } catch (e: Exception) {
            android.util.Log.e("RoleStore", "角色索引损坏", e)
            emptyList()
        }
    }

    private fun write(list: List<Role>) =
        synchronized(globalLock) {
            val tmp = File(dir, "roles.json.tmp")
            tmp.writeText(JSONArray(list.map { it.json() }).toString())
            check(tmp.renameTo(db)) { "角色索引保存失败" }
        }

    fun ensureBuiltins() = synchronized(globalLock) {
        val list=roles().toMutableList()
        val revisions=mutableMapOf<String,String>()
        for((id,name) in listOf("qing" to "青禾 · 人形","momo" to "团子 · Q版")) {
            val bytes=context.assets.open("models/$id.glb").use{it.readBytes()}
            val revision=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
            val key="builtinRevision.$id"
            val index=list.indexOfFirst{it.id==id}
            if(index<0 || prefs.getString(key,null)!=revision) {
                val result=ModelCodec.convert(bytes)
                val filename="$id.glb"
                val temporary=File(dir,"$filename.tmp")
                temporary.writeBytes(result.bytes)
                check(temporary.renameTo(File(dir,filename))){"内置角色更新失败"}
                val old=list.getOrNull(index)
                val updated=old?.copy(file=filename,animations=result.animations,warning=result.warning,builtin=true)
                    ?: Role(id,name,filename,animations=result.animations,builtin=true)
                if(index<0)list+=updated else list[index]=updated
            }
            revisions[key]=revision
        }
        write(list)
        val edit=prefs.edit();for((key,value)in revisions)edit.putString(key,value);edit.apply()
    }

    fun selected(): Role? {
        val list = roles()
        return list.find { it.id == prefs.getString("role", "qing") } ?: list.firstOrNull()
    }

    fun select(role: Role) {
        prefs.edit().putString("role", role.id).apply()
    }

    fun file(role: Role) = File(dir, role.file)

    fun update(role: Role) =
        synchronized(globalLock) {
            require(role.name.isNotBlank() && role.name.length <= 40)
            write(roles().map { if (it.id == role.id) role else it })
        }

    fun delete(role: Role): Unit =
        synchronized(globalLock) {
            require(!role.builtin) { "内置角色不能删除" }
            write(roles().filter { it.id != role.id })
            file(role).delete()
            Unit
        }

    fun readLimited(stream: InputStream): ByteArray {
        stream.use { s ->
            val o = java.io.ByteArrayOutputStream()
            val b = ByteArray(32768)
            while (true) {
                val n = s.read(b)
                if (n < 0) break
                require(o.size().toLong() + n <= ModelCodec.MAX_BYTES) { "文件超过 64 MB" }
                o.write(b, 0, n)
            }
            return o.toByteArray()
        }
    }

    fun importFile(uri: Uri): Role {
        val name =
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use {
                    if (it.moveToFirst())
                        it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                    else "导入角色"
                } ?: "导入角色"
        require(name.substringAfterLast('.').lowercase() in listOf("glb", "vrm", "gltf")) {
            "请选择 GLB / VRM；GLTF 请使用目录导入"
        }
        val b = readLimited(context.contentResolver.openInputStream(uri) ?: error("无法读取文件"))
        return importBytes(name, b)
    }

    fun importBytes(
        name: String,
        bytes: ByteArray,
        resolver: (String) -> ByteArray = { error("缺少关联资源 $it，请使用 GLTF 目录导入") },
    ): Role {
        val r = ModelCodec.convert(bytes, resolver)
        val id = UUID.randomUUID().toString()
        val filename = "$id.glb"
        val f = File(dir, filename)
        try {
            f.writeBytes(r.bytes)
            val role =
                Role(
                    id,
                    name.substringBeforeLast('.').take(40).ifBlank { "导入角色" },
                    filename,
                    animations = r.animations,
                    warning = r.warning,
                )
            synchronized(globalLock) { write(roles() + role) }
            return role
        } catch (t: Throwable) {
            f.delete()
            throw t
        }
    }

    fun importTree(tree: Uri): Role {
        val base = DocumentsContract.getTreeDocumentId(tree)
        fun children(id: String): List<Pair<String, String>> {
            val u = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
            return context.contentResolver
                .query(
                    u,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    ),
                    null,
                    null,
                    null,
                )
                ?.use { c ->
                    buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
                } ?: emptyList()
        }
        val roots = children(base).filter { it.second.endsWith(".gltf", true) }
        require(roots.size == 1) { "请选择含且仅含一个 .gltf 的目录，并保留 bin / textures 子目录" }
        val root = roots.single()
        fun read(id: String) =
            readLimited(
                context.contentResolver.openInputStream(
                    DocumentsContract.buildDocumentUriUsingTree(tree, id)
                ) ?: error("无法读取关联文件")
            )
        return importBytes(root.second, read(root.first)) { path ->
            var id = base
            for (part in path.split('/')) id =
                children(id).firstOrNull { it.second == part }?.first ?: error("缺少关联文件 $path")
            read(id)
        }
    }
}
