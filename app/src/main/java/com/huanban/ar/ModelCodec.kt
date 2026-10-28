package com.huanban.ar

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.*
import org.json.JSONArray
import org.json.JSONObject

/** Bounded glTF 2 importer. All URIs resolved by caller, never by the renderer/network. */
object ModelCodec {
    const val MAX_BYTES = 64 * 1024 * 1024

    data class Result(
        val bytes: ByteArray,
        val animations: List<String>,
        val warning: String,
        val rigged: Boolean,
    )

    fun safePath(path: String): String {
        require(
            !path.contains('\\') &&
                !path.contains(':') &&
                !path.startsWith('/') &&
                !path.contains('?') &&
                !path.contains('#')
        ) {
            "关联资源路径不安全"
        }
        val decoded = java.net.URLDecoder.decode(path.replace("+", "%2B"), "UTF-8")
        require(
            decoded.split('/').none { it == ".." || it.isEmpty() } &&
                !decoded.contains(':') &&
                !decoded.contains('\\')
        ) {
            "关联资源路径不安全"
        }
        return decoded
    }

    fun unpack(bytes: ByteArray): Pair<JSONObject, ByteArray> {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "文件为空或超过 64 MB 限制" }
        if (
            bytes.size >= 12 &&
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int == 0x46546c67
        ) {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            b.int
            require(b.int == 2 && b.int == bytes.size) { "GLB 版本或长度损坏" }
            var json: JSONObject? = null
            var bin = byteArrayOf()
            while (b.remaining() >= 8) {
                val n = b.int
                val type = b.int
                require(n >= 0 && n % 4 == 0 && n <= b.remaining()) { "GLB 数据块损坏" }
                val chunk = ByteArray(n)
                b.get(chunk)
                when (type) {
                    0x4e4f534a -> {
                        require(json == null)
                        json = JSONObject(String(chunk, Charsets.UTF_8).trimEnd('\u0000', ' '))
                    }
                    0x004e4942 -> bin = chunk
                }
            }
            require(b.remaining() == 0) { "GLB 末尾数据损坏" }
            return (json ?: error("缺少模型描述")) to bin
        }
        require(bytes.size < 8 * 1024 * 1024) { "GLTF 描述过大" }
        return JSONObject(String(bytes, Charsets.UTF_8)) to byteArrayOf()
    }

    // Filament 1.52 selects image providers from raw JSON MIME tokens.
    // Android JSON escapes '/', so normalize actual slash escapes only.
    internal fun rendererJson(text: String): String {
        val slashEscape = 92.toChar().toString() + "/"
        if (!text.contains(slashEscape)) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (text[i] == 92.toChar() && i + 1 < text.length) {
                val next = text[i + 1]
                if (next == '/') out.append('/') else out.append(92.toChar()).append(next)
                i += 2
            } else out.append(text[i++])
        }
        return out.toString()
    }

    // Migrate the JSON of older persisted GLBs, retaining every binary byte.
    fun forRenderer(bytes: ByteArray): ByteArray {
        require(bytes.size >= 20)
        val h = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(h.int == 0x46546c67 && h.int == 2 && h.int == bytes.size)
        val length = h.int
        require(h.int == 0x4e4f534a && length >= 0 && length % 4 == 0 && 20L + length <= bytes.size)
        val original = String(bytes,20,length,Charsets.UTF_8)
        val normalized = rendererJson(original)
        if (original == normalized) return bytes
        val raw = normalized.toByteArray(Charsets.UTF_8)
        val padded = raw + ByteArray((4-raw.size%4)%4){32}
        val total = bytes.size-length+padded.size
        return ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x46546c67).putInt(2).putInt(total)
            .putInt(padded.size).putInt(0x4e4f534a).put(padded)
            .put(bytes,20+length,bytes.size-20-length).array()
    }

    fun pack(j: JSONObject, bin: ByteArray): ByteArray {
        val raw = rendererJson(j.toString()).toByteArray(Charsets.UTF_8)
        val js = raw + ByteArray((4 - raw.size % 4) % 4) { 32 }
        val bs = bin + ByteArray((4 - bin.size % 4) % 4)
        return ByteBuffer.allocate(28 + js.size + bs.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x46546c67)
            .putInt(2)
            .putInt(28 + js.size + bs.size)
            .putInt(js.size)
            .putInt(0x4e4f534a)
            .put(js)
            .putInt(bs.size)
            .putInt(0x004e4942)
            .put(bs)
            .array()
    }

    fun convert(
        input: ByteArray,
        resolve: (String) -> ByteArray = { error("缺少关联文件：$it；GLTF 请使用目录导入") },
    ): Result {
        val (j, original) = unpack(input)
        require(j.optJSONObject("asset")?.optString("version") == "2.0") { "只支持 glTF 2.0 / VRM" }
        val nodes = j.optJSONArray("nodes") ?: error("模型缺少节点")
        require(nodes.length() in 1..2048) { "模型节点数量超限" }
        require((j.optJSONArray("meshes")?.length() ?: 0) > 0) { "模型没有可显示网格" }
        val required = j.optJSONArray("extensionsRequired") ?: JSONArray()
        val allowed =
            setOf(
                "KHR_materials_unlit",
                "KHR_texture_transform",
                "KHR_materials_pbrSpecularGlossiness",
                "KHR_materials_clearcoat",
                "KHR_materials_transmission",
                "KHR_materials_ior",
                "KHR_materials_sheen",
                "KHR_materials_specular",
                "KHR_materials_emissive_strength",
                "VRM",
                "VRMC_vrm",
                "VRMC_materials_mtoon",
            )
        for (i in 0 until required.length()) require(required.getString(i) in allowed) {
            "暂不支持必需扩展 ${required.getString(i)}；请导出未压缩 GLB"
        }
        val out = ByteArrayOutputStream()
        fun append(b: ByteArray): Int {
            require(out.size().toLong() + b.size <= MAX_BYTES) { "关联资源合计超过 64 MB" }
            while (out.size() % 4 != 0) out.write(0)
            val p = out.size()
            out.write(b)
            return p
        }
        fun uri(s: String): ByteArray {
            if (s.startsWith("data:")) {
                require(s.substringBefore(',').endsWith(";base64"))
                require(s.length < MAX_BYTES * 4L / 3 + 256)
                return Base64.getDecoder().decode(s.substringAfter(','))
            }
            return resolve(safePath(s))
        }
        val buffers = j.optJSONArray("buffers") ?: error("缺少缓冲区")
        val offsets = IntArray(buffers.length())
        val lengths = IntArray(buffers.length())
        for (i in offsets.indices) {
            val b = buffers.getJSONObject(i)
            val v =
                if (b.has("uri")) uri(b.getString("uri"))
                else {
                    require(i == 0)
                    original
                }
            require(b.getInt("byteLength") in 0..v.size)
            lengths[i] = v.size
            offsets[i] = append(v)
        }
        val views = j.optJSONArray("bufferViews") ?: JSONArray().also { j.put("bufferViews", it) }
        require(views.length() <= 20000)
        for (i in 0 until views.length()) {
            val v = views.getJSONObject(i)
            val bi = v.getInt("buffer")
            require(bi in offsets.indices)
            val o = v.optInt("byteOffset", 0)
            val n = v.getInt("byteLength")
            require(o >= 0 && n >= 0 && o.toLong() + n <= lengths[bi]) { "模型缓冲区越界" }
            v.put("byteOffset", o + offsets[bi]).put("buffer", 0)
        }
        val images = j.optJSONArray("images") ?: JSONArray()
        require(images.length() <= 64) { "纹理数量超过 64 张" }
        var pixels = 0L
        for (i in 0 until images.length()) {
            val im = images.getJSONObject(i)
            if (im.has("uri")) {
                val path = im.getString("uri")
                val b = uri(path)
                val off = append(b)
                val mt =
                    if (path.startsWith("data:")) path.substringAfter("data:").substringBefore(';')
                    else if (path.endsWith(".png", true)) "image/png" else "image/jpeg"
                im.remove("uri")
                im.put("bufferView", views.length()).put("mimeType", mt)
                views.put(
                    JSONObject().put("buffer", 0).put("byteOffset", off).put("byteLength", b.size)
                )
            }
            val vi = im.optInt("bufferView", -1)
            require(vi in 0 until views.length()) { "纹理数据缺失" }
            val v = views.getJSONObject(vi)
            val all = out.toByteArray()
            val p = v.optInt("byteOffset")
            val n = v.getInt("byteLength")
            val dimension = imageDimensions(all, p, n)
            require(dimension.first in 1..4096 && dimension.second in 1..4096) {
                "纹理超过 4096 像素或格式不支持"
            }
            pixels += dimension.first.toLong() * dimension.second
        }
        require(pixels <= 32L * 1024 * 1024) { "纹理解码内存预算超限（合计 3200 万像素）" }
        val access = j.optJSONArray("accessors") ?: JSONArray().also { j.put("accessors", it) }
        require(access.length() <= 30000)
        var vertices = 0L
        for (i in 0 until access.length()) {
            val a = access.getJSONObject(i)
            val count = a.getLong("count")
            require(count in 0..2_000_000)
            if (a.has("bufferView")) {
                val vi = a.getInt("bufferView")
                require(vi in 0 until views.length())
                val components =
                    mapOf(
                        "SCALAR" to 1,
                        "VEC2" to 2,
                        "VEC3" to 3,
                        "VEC4" to 4,
                        "MAT2" to 4,
                        "MAT3" to 9,
                        "MAT4" to 16,
                    )[a.getString("type")] ?: error("非法访问器类型")
                val size =
                    mapOf(5120 to 1, 5121 to 1, 5122 to 2, 5123 to 2, 5125 to 4, 5126 to 4)[
                        a.getInt("componentType")] ?: error("非法分量类型")
                val v = views.getJSONObject(vi)
                val stride = v.optInt("byteStride", components * size)
                require(stride >= components * size)
                val needed =
                    a.optLong("byteOffset", 0) +
                        if (count == 0L) 0 else (count - 1) * stride + components * size
                require(needed <= v.getLong("byteLength")) { "访问器数据越界" }
            }
        }
        val meshes = j.getJSONArray("meshes")
        val countedPositions=mutableSetOf<Int>()
        for (i in 0 until meshes.length()) {
            val ps = meshes.getJSONObject(i).getJSONArray("primitives")
            for (k in 0 until ps.length()) {
                val p = ps.getJSONObject(k).getJSONObject("attributes").getInt("POSITION")
                require(p in 0 until access.length())
                if(countedPositions.add(p)) vertices += access.getJSONObject(p).getLong("count")
            }
        }
        require(vertices <= 750_000) { "顶点总数超过 75 万，无法安全加载" }
        validateGraph(nodes)
        validateReferences(j, out.toByteArray())
        // VRM-specific rendering is an explicit PBR fallback; preserve metadata for bone/expression
        // maps.
        j.put(
            "extensionsRequired",
            JSONArray(
                (0 until required.length())
                    .map { required.getString(it) }
                    .filter { it !in setOf("VRM", "VRMC_vrm", "VRMC_materials_mtoon") }
            ),
        )
        val rig = RigMotion.append(j, out)
        j.put("buffers", JSONArray().put(JSONObject().put("byteLength", out.size())))
        val vrm = j.optJSONObject("extensions")?.let { it.has("VRM") || it.has("VRMC_vrm") } == true
        val warning =
            listOfNotNull(
                    if (vrm) "VRM 使用 glTF 基础材质；MToon、弹簧骨骼与物理碰撞不支持" else null,
                    j.optJSONObject("extensions")?.optJSONObject("VRM")?.optJSONObject("meta")?.let { meta ->
                        "模型作者：${meta.optString("author","未标注")}；原授权：${meta.optString("licenseName","未标注")}；可用用户：${meta.optString("allowedUserName","未标注")}"
                    },
                    if (!rig) "未识别兼容人形骨骼；保留模型自带动画，不能套用内置动作" else null,
                )
                .joinToString("；")
        val an = j.optJSONArray("animations") ?: JSONArray()
        return Result(
            pack(j, out.toByteArray()),
            (0 until an.length()).map { an.getJSONObject(it).optString("name", "动作 ${it+1}") },
            warning,
            rig,
        )
    }

    fun validateGraph(nodes: JSONArray) {
        val parents = IntArray(nodes.length()) { -1 }
        val state = IntArray(nodes.length())
        for (i in 0 until nodes.length()) {
            val children = nodes.getJSONObject(i).optJSONArray("children") ?: continue
            for (k in 0 until children.length()) {
                val c = children.getInt(k)
                require(c in parents.indices && parents[c] == -1 && c != i) { "骨骼层次损坏" }
                parents[c] = i
            }
        }
        fun visit(i: Int, depth: Int) {
            require(depth < 128 && state[i] != 1) { "骨骼循环或深度超限" }
            if (state[i] == 2) return
            state[i] = 1
            val p = parents[i]
            if (p >= 0) visit(p, depth + 1)
            state[i] = 2
        }
        parents.indices.forEach { visit(it, 0) }
    }

    private fun validateReferences(j: JSONObject, bin: ByteArray) {
        val nodes = j.getJSONArray("nodes")
        val meshes = j.getJSONArray("meshes")
        val access = j.getJSONArray("accessors")
        val views = j.getJSONArray("bufferViews")
        val skins = j.optJSONArray("skins") ?: JSONArray()
        val materials = j.optJSONArray("materials") ?: JSONArray()
        fun ref(index: Int, size: Int, what: String) {
            require(index in 0 until size) { "$what 索引越界" }
        }
        fun accessor(index: Int): JSONObject {
            ref(index, access.length(), "访问器")
            return access.getJSONObject(index)
        }
        for (i in 0 until nodes.length()) {
            val n = nodes.getJSONObject(i)
            if (n.has("mesh")) ref(n.getInt("mesh"), meshes.length(), "网格")
            if (n.has("skin")) ref(n.getInt("skin"), skins.length(), "蒙皮")
            for (k in listOf("translation", "rotation", "scale", "matrix")) {
                val a = n.optJSONArray(k) ?: continue
                require(
                    a.length() ==
                        when (k) {
                            "rotation" -> 4
                            "matrix" -> 16
                            else -> 3
                        }
                )
                for (c in 0 until a.length()) require(
                    a.getDouble(c).isFinite() && abs(a.getDouble(c)) < 1e6
                ) {
                    "节点变换包含非法数值"
                }
            }
        }
        for (i in 0 until skins.length()) {
            val skin = skins.getJSONObject(i)
            val joints = skin.getJSONArray("joints")
            require(joints.length() in 1..256) { "单个蒙皮骨骼数超限" }
            for (k in 0 until joints.length()) ref(joints.getInt(k), nodes.length(), "骨骼")
            if (skin.has("inverseBindMatrices")) {
                val a = accessor(skin.getInt("inverseBindMatrices"))
                require(a.getString("type") == "MAT4" && a.getInt("count") == joints.length()) {
                    "逆绑定矩阵数量错误"
                }
            }
        }
        for (i in 0 until meshes.length()) {
            val ps = meshes.getJSONObject(i).getJSONArray("primitives")
            require(ps.length() in 1..256) { "网格图元数超限" }
            for (k in 0 until ps.length()) {
                val p = ps.getJSONObject(k)
                val at = p.getJSONObject("attributes")
                val position = accessor(at.getInt("POSITION"))
                val count = position.getInt("count")
                require(position.getString("type") == "VEC3" && count > 0)
                val keys = at.keys()
                while (keys.hasNext()) {
                    val a = accessor(at.getInt(keys.next()))
                    require(a.getInt("count") == count) { "顶点属性数量不一致" }
                }
                if (p.has("material")) ref(p.getInt("material"), materials.length(), "材质")
                require(p.optInt("mode", 4) in 0..6)
                if (p.has("indices")) {
                    val a = accessor(p.getInt("indices"))
                    require(a.getString("type") == "SCALAR")
                    val type = a.getInt("componentType")
                    require(type in listOf(5121, 5123, 5125))
                    if (a.has("bufferView") && !a.has("sparse")) {
                        val v = views.getJSONObject(a.getInt("bufferView"))
                        val offset = v.optInt("byteOffset") + a.optInt("byteOffset")
                        val bytes = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN)
                        bytes.position(offset)
                        repeat(a.getInt("count")) {
                            val index =
                                when (type) {
                                    5121 -> bytes.get().toLong() and 255
                                    5123 -> bytes.short.toLong() and 65535
                                    else -> bytes.int.toLong() and 0xffffffffL
                                }
                            require(index < count) { "三角形顶点索引越界" }
                        }
                    }
                }
            }
        }
        val scenes = j.optJSONArray("scenes") ?: error("模型缺少场景")
        require(scenes.length() > 0)
        ref(j.optInt("scene", 0), scenes.length(), "场景")
        for (i in 0 until scenes.length()) {
            val ns = scenes.getJSONObject(i).optJSONArray("nodes") ?: continue
            for (k in 0 until ns.length()) ref(ns.getInt(k), nodes.length(), "场景节点")
        }
        val animations = j.optJSONArray("animations") ?: JSONArray()
        require(animations.length() <= 128) { "动作数量超限" }
        for (i in 0 until animations.length()) {
            val a = animations.getJSONObject(i)
            val ss = a.getJSONArray("samplers")
            for (k in 0 until ss.length()) {
                val sampler = ss.getJSONObject(k)
                val input = accessor(sampler.getInt("input"))
                accessor(sampler.getInt("output"))
                require(
                    input.getString("type") == "SCALAR" && input.getInt("componentType") == 5126
                )
                require(
                    sampler.optString("interpolation", "LINEAR") in
                        listOf("LINEAR", "STEP", "CUBICSPLINE")
                )
            }
            val cs = a.getJSONArray("channels")
            for (k in 0 until cs.length()) {
                val c = cs.getJSONObject(k)
                ref(c.getInt("sampler"), ss.length(), "动画采样器")
                val t = c.getJSONObject("target")
                ref(t.getInt("node"), nodes.length(), "动画骨骼")
                require(
                    t.getString("path") in listOf("translation", "rotation", "scale", "weights")
                )
            }
        }
    }

    fun imageDimensions(b: ByteArray, p: Int, n: Int): Pair<Int, Int> {
        if (n >= 24 && b[p] == (-119).toByte() && b[p + 1] == 80.toByte()) {
            val v = ByteBuffer.wrap(b, p + 16, 8).order(ByteOrder.BIG_ENDIAN)
            return v.int to v.int
        }
        if (n > 4 && b[p] == (-1).toByte() && b[p + 1] == (-40).toByte()) {
            var o = p + 2
            while (o + 8 < p + n) {
                if (b[o] != (-1).toByte()) {
                    o++
                    continue
                }
                val marker = b[o + 1].toInt() and 255
                o += 2
                if (marker == 0xd9 || marker == 0xda) break
                val len = ((b[o].toInt() and 255) shl 8) + (b[o + 1].toInt() and 255)
                if (len < 2 || o + len > p + n) break
                if (marker in listOf(0xc0, 0xc1, 0xc2, 0xc3)) {
                    return (((b[o + 5].toInt() and 255) shl 8) + (b[o + 6].toInt() and 255)) to
                        (((b[o + 3].toInt() and 255) shl 8) + (b[o + 4].toInt() and 255))
                }
                o += len
            }
        }
        error("仅支持 PNG / JPEG 纹理；请转换纹理后导入")
    }
}
