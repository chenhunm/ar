package com.huanban.ar

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Conservative rest-relative joint animation. Never edits vertex positions or inverse bind poses.
 */
object RigMotion {
    private val standard =
        mapOf(
            "hips" to "hips",
            "spine" to "spine",
            "chest" to "chest",
            "head" to "head",
            "leftUpperArm" to "upperArmLeft",
            "leftLowerArm" to "lowerArmLeft",
            "rightUpperArm" to "upperArmRight",
            "rightLowerArm" to "lowerArmRight",
            "leftUpperLeg" to "upperLegLeft",
            "leftLowerLeg" to "lowerLegLeft",
            "rightUpperLeg" to "upperLegRight",
            "rightLowerLeg" to "lowerLegRight",
        )

    fun map(j: JSONObject): Map<String, Int> {
        val nodes = j.getJSONArray("nodes")
        val result = mutableMapOf<String, Int>()
        val ex = j.optJSONObject("extensions")
        val vrm0 = ex?.optJSONObject("VRM")?.optJSONObject("humanoid")?.optJSONArray("humanBones")
        if (vrm0 != null)
            for (i in 0 until vrm0.length()) {
                val b = vrm0.getJSONObject(i)
                standard[b.optString("bone")]?.let { result[it] = b.getInt("node") }
            }
        val vrm1 =
            ex?.optJSONObject("VRMC_vrm")?.optJSONObject("humanoid")?.optJSONObject("humanBones")
        if (vrm1 != null)
            for ((bone, name) in standard) vrm1.optJSONObject(bone)?.let {
                result[name] = it.getInt("node")
            }
        for (i in 0 until nodes.length()) {
            val raw = nodes.getJSONObject(i).optString("name")
            val n = raw.lowercase().replace(Regex("[^a-z0-9]"), "").removePrefix("mixamorig")
            val japanese = mapOf("下半身" to "hips", "上半身" to "spine", "上半身2" to "chest", "頭" to "head",
                "左腕" to "upperArmLeft", "右腕" to "upperArmRight", "左ひじ" to "lowerArmLeft", "右ひじ" to "lowerArmRight",
                "左足" to "upperLegLeft", "右足" to "upperLegRight", "左ひざ" to "lowerLegLeft", "右ひざ" to "lowerLegRight")
            val key = japanese[raw] ?: when (n) {
                    "hips",
                    "pelvis" -> "hips"
                    "spine" -> "spine"
                    "chest",
                    "spine1",
                    "spine2",
                    "spineone",
                    "spinetwo" -> "chest"
                    "head" -> "head"
                    "leftarm",
                    "leftupperarm",
                    "upperarmleft" -> "upperArmLeft"
                    "rightarm",
                    "rightupperarm",
                    "upperarmright" -> "upperArmRight"
                    "leftforearm",
                    "leftlowerarm",
                    "lowerarmleft" -> "lowerArmLeft"
                    "rightforearm",
                    "rightlowerarm",
                    "lowerarmright" -> "lowerArmRight"
                    "leftupleg",
                    "leftupperleg",
                    "upperlegleft" -> "upperLegLeft"
                    "rightupleg",
                    "rightupperleg",
                    "upperlegright" -> "upperLegRight"
                    "leftleg",
                    "leftlowerleg",
                    "lowerlegleft" -> "lowerLegLeft"
                    "rightleg",
                    "rightlowerleg",
                    "lowerlegright" -> "lowerLegRight"
                    "mouth" -> "mouth"
                    "eyeleft" -> "eyeLeft"
                    "eyeright" -> "eyeRight"
                    else -> null
                }
            if (key != null && !result.containsKey(key)) result[key] = i
        }
        return result.filterValues { it in 0 until nodes.length() }
    }

    fun multiply(a: FloatArray, b: FloatArray): FloatArray =
        floatArrayOf(
                a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
                a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
                a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
                a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2],
            )
            .let { q ->
                val l = sqrt(q.sumOf { (it * it).toDouble() }).toFloat()
                require(l > .001)
                q.map { it / l }.toFloatArray()
            }

    fun append(j: JSONObject, out: ByteArrayOutputStream): Boolean {
        val ns = j.getJSONArray("nodes")
        val rig = map(j)
        val skin = j.optJSONArray("skins")
        val joints = mutableSetOf<Int>()
        if (skin != null)
            for (i in 0 until skin.length()) {
                val a = skin.getJSONObject(i).getJSONArray("joints")
                for (k in 0 until a.length()) joints += a.getInt(k)
            }
        val core =
            listOf(
                "hips",
                "spine",
                "head",
                "upperArmLeft",
                "lowerArmLeft",
                "upperArmRight",
                "lowerArmRight",
                "upperLegLeft",
                "lowerLegLeft",
                "upperLegRight",
                "lowerLegRight",
            )
        if (
            core.any { rig[it] !in joints } ||
                core.mapNotNull { rig[it] }.any { ns.getJSONObject(it).has("matrix") }
        )
            return false
        for (index in rig.values) {
            val node = ns.getJSONObject(index)
            val scale = node.optJSONArray("scale")
            if (scale != null && (0..2).any { abs(scale.getDouble(it) - 1) > 0.01 }) return false
        }
        // Work in rest-pose world axes, then transform each motion back into that joint's local
        // basis.
        // This handles common VRM T poses and our A pose without assuming identical bone rotations.
        if ((0 until ns.length()).any { ns.getJSONObject(it).has("matrix") }) return false
        val parent = IntArray(ns.length()) { -1 }
        for (i in parent.indices) {
            val cs = ns.getJSONObject(i).optJSONArray("children") ?: continue
            for (k in 0 until cs.length()) parent[cs.getInt(k)] = i
        }
        fun ancestor(child: Int, expected: Int): Boolean {
            var p = parent[child]
            while (p >= 0) {
                if (p == expected) return true
                p = parent[p]
            }
            return false
        }
        for (side in listOf("Left", "Right")) {
            if (
                !ancestor(rig.getValue("lowerArm$side"), rig.getValue("upperArm$side")) ||
                    !ancestor(rig.getValue("lowerLeg$side"), rig.getValue("upperLeg$side"))
            )
                return false
        }
        val rest =
            Array(ns.length()) { i ->
                val a = ns.getJSONObject(i).optJSONArray("rotation")
                FloatArray(4) {
                    a?.optDouble(it, if (it == 3) 1.0 else 0.0)?.toFloat()
                        ?: if (it == 3) 1f else 0f
                }
            }
        val world = arrayOfNulls<FloatArray>(ns.length())
        val positions = arrayOfNulls<FloatArray>(ns.length())
        fun rotation(i: Int): FloatArray {
            world[i]?.let {
                return it
            }
            return (if (parent[i] < 0) rest[i] else multiply(rotation(parent[i]), rest[i])).also {
                world[i] = it
            }
        }
        fun position(i: Int): FloatArray {
            positions[i]?.let {
                return it
            }
            val a = ns.getJSONObject(i).optJSONArray("translation")
            val local = FloatArray(3) { a?.optDouble(it)?.toFloat() ?: 0f }
            val p = parent[i]
            val result =
                if (p < 0) local
                else {
                    val r = rotate(rotation(p), local)
                    val pp = position(p)
                    FloatArray(3) { r[it] + pp[it] }
                }
            positions[i] = result
            return result
        }
        val base = Array(ns.length()) { floatArrayOf(0f, 0f, 0f, 1f) }
        for (side in listOf("Left", "Right")) {
            val arm = rig.getValue("upperArm$side")
            val elbow = rig.getValue("lowerArm$side")
            val a = position(arm)
            val b = position(elbow)
            val direction = FloatArray(3) { b[it] - a[it] }
            if (direction.sumOf { (it * it).toDouble() } < 1e-8) return false
            val delta = fromTo(direction, floatArrayOf(0f, -1f, 0f))
            val w = rotation(arm)
            base[arm] = multiply(multiply(inverse(w), delta), w)
        }
        val baseline = arrayOfNulls<FloatArray>(ns.length())
        fun baselineRotation(i: Int): FloatArray {
            baseline[i]?.let {
                return it
            }
            return multiply(
                    if (parent[i] < 0) rest[i] else multiply(baselineRotation(parent[i]), rest[i]),
                    base[i],
                )
                .also { baseline[i] = it }
        }
        val access = j.getJSONArray("accessors")
        val views = j.getJSONArray("bufferViews")
        val animations =
            j.optJSONArray("animations") ?: JSONArray().also { j.put("animations", it) }
        fun floats(values: FloatArray, components: Int, type: String): Int {
            while (out.size() % 4 != 0) out.write(0)
            val off = out.size()
            val b = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            values.forEach { b.putFloat(it) }
            out.write(b.array())
            val vi = views.length()
            views.put(
                JSONObject().put("buffer", 0).put("byteOffset", off).put("byteLength", b.capacity())
            )
            val a =
                JSONObject()
                    .put("bufferView", vi)
                    .put("componentType", 5126)
                    .put("count", values.size / components)
                    .put("type", type)
            if (type == "SCALAR")
                a.put("min", JSONArray().put(values.minOrNull()))
                    .put("max", JSONArray().put(values.maxOrNull()))
            access.put(a)
            return access.length() - 1
        }
        val duration = 4f
        val samples = 65
        val times = FloatArray(samples) { duration * it / (samples - 1) }
        val time = floats(times, 1, "SCALAR")
        for ((mode, title) in
            listOf(
                "idle" to "幻伴·呼吸待机",
                "look" to "幻伴·环顾",
                "wave" to "幻伴·挥手",
                "dance" to "幻伴·轻舞",
                "step" to "幻伴·踏步",
                "happy" to "幻伴·开心",
                "blink" to "幻伴·眨眼",
            )) {
            val samplers = JSONArray()
            val channels = JSONArray()
            fun channel(node: Int, path: String, values: FloatArray, c: Int, type: String) {
                val o = floats(values, c, type)
                val s = samplers.length()
                samplers.put(
                    JSONObject().put("input", time).put("output", o).put("interpolation", "LINEAR")
                )
                channels.put(
                    JSONObject()
                        .put("sampler", s)
                        .put("target", JSONObject().put("node", node).put("path", path))
                )
            }
            for ((name, index) in rig) {
                val node = ns.getJSONObject(index)
                if (name.startsWith("eye") || name == "mouth") {
                    val original = node.optJSONArray("scale")
                    val rest = FloatArray(3) { original?.optDouble(it, 1.0)?.toFloat() ?: 1f }
                    val v = FloatArray(samples * 3)
                    for (k in 0 until samples) {
                        val t = times[k]
                        val blink = max(0f, 1f - abs(t - 2.5f) / .1f)
                        for (c in 0..2) v[k * 3 + c] =
                            rest[c] *
                                (if (name == "mouth") {
                                    if (mode == "happy")
                                        if (c == 1) 3f else if (c == 0) 1.25f else 1f
                                    else 1f
                                } else if (c == 1)
                                    (1f - .93f * blink) * (if (mode == "happy") .45f else 1f)
                                else 1f)
                    }
                    channel(index, "scale", v, 3, "VEC3")
                    continue
                }
                val localRest = rest[index]
                val basis = baselineRotation(index)
                val vals = FloatArray(samples * 4)
                for (k in 0 until samples) {
                    val phase = times[k] * 2 * PI.toFloat() / duration
                    val s = sin(phase)
                    val side = if (name.endsWith("Left")) -1f else 1f
                    var angle =
                        when (name) {
                            "head" -> .035f * s
                            "spine",
                            "chest" -> .018f * s
                            else -> 0f
                        }
                    var axis = 0
                    when (mode) {
                        "look" ->
                            if (name == "head") {
                                angle = .28f * s
                                axis = 1
                            }
                        "wave" ->
                            when (name) {
                                "upperArmRight" -> {
                                    angle = 1.9f
                                    axis = 2
                                }
                                "lowerArmRight" -> {
                                    angle = .65f + .35f * sin(phase * 3)
                                    axis = 2
                                }
                            }
                        "dance",
                        "happy" ->
                            when {
                                name.startsWith("upperArm") -> {
                                    angle = side * (.4f + .35f * s)
                                    axis = 2
                                }
                                name.startsWith("lowerArm") -> {
                                    angle = -.45f - .25f * sin(phase + side)
                                    axis = 0
                                }
                                name.startsWith("upperLeg") -> {
                                    angle = .2f * sin(phase + side * PI.toFloat() / 2)
                                    axis = 0
                                }
                                name.startsWith("lowerLeg") -> {
                                    angle = .18f + .15f * sin(phase + side * PI.toFloat() / 2)
                                    axis = 0
                                }
                                name == "hips" -> {
                                    angle = .11f * s
                                    axis = 1
                                }
                                name == "head" -> {
                                    angle = .09f * s
                                    axis = 2
                                }
                            }
                        "step" ->
                            when {
                                name.startsWith("upperLeg") -> {
                                    angle = side * .34f * s
                                }
                                name.startsWith("lowerLeg") -> {
                                    angle = .3f + side * .25f * s
                                }
                                name.startsWith("upperArm") -> {
                                    angle = -side * .23f * s
                                }
                            }
                    }
                    val worldAxis = FloatArray(3)
                    worldAxis[axis] = 1f
                    val localAxis = rotate(inverse(basis), worldAxis)
                    val q =
                        floatArrayOf(
                            localAxis[0] * sin(angle / 2),
                            localAxis[1] * sin(angle / 2),
                            localAxis[2] * sin(angle / 2),
                            cos(angle / 2),
                        )
                    multiply(multiply(localRest, base[index]), q).copyInto(vals, k * 4)
                }
                channel(index, "rotation", vals, 4, "VEC4")
            }
            appendExpression(j, mode, samples, ::channel)
            animations.put(
                JSONObject().put("name", title).put("samplers", samplers).put("channels", channels)
            )
        }
        return true
    }

    private fun inverse(q: FloatArray) = floatArrayOf(-q[0], -q[1], -q[2], q[3])

    private fun rotate(q: FloatArray, v: FloatArray): FloatArray {
        val tx = 2f * (q[1] * v[2] - q[2] * v[1])
        val ty = 2f * (q[2] * v[0] - q[0] * v[2])
        val tz = 2f * (q[0] * v[1] - q[1] * v[0])
        return floatArrayOf(
            v[0] + q[3] * tx + q[1] * tz - q[2] * ty,
            v[1] + q[3] * ty + q[2] * tx - q[0] * tz,
            v[2] + q[3] * tz + q[0] * ty - q[1] * tx,
        )
    }

    private fun fromTo(a: FloatArray, b: FloatArray): FloatArray {
        val len = sqrt(a.sumOf { (it * it).toDouble() }).toFloat()
        require(len > .0001f) { "骨骼关节长度为零，无法重定向" }
        val x = FloatArray(3) { a[it] / len }
        val dot = (x[0] * b[0] + x[1] * b[1] + x[2] * b[2]).coerceIn(-1f, 1f)
        if (dot > 0.9999f) return floatArrayOf(0f, 0f, 0f, 1f)
        if (dot < -.9999f) return floatArrayOf(1f, 0f, 0f, 0f)
        return multiply(
            floatArrayOf(0f, 0f, 0f, 1f),
            floatArrayOf(
                x[1] * b[2] - x[2] * b[1],
                x[2] * b[0] - x[0] * b[2],
                x[0] * b[1] - x[1] * b[0],
                1f + dot,
            ),
        )
    }

    private fun appendExpression(
        j: JSONObject,
        mode: String,
        samples: Int,
        channel: (Int, String, FloatArray, Int, String) -> Unit,
    ) {
        if (mode !in listOf("happy", "blink")) return
        val nodes = j.getJSONArray("nodes")
        val meshes = j.getJSONArray("meshes")
        val ex = j.optJSONObject("extensions") ?: return
        val name = if (mode == "happy") "happy" else "blink"
        val binds =
            ex.optJSONObject("VRMC_vrm")
                ?.optJSONObject("expressions")
                ?.optJSONObject("preset")
                ?.optJSONObject(name)
                ?.optJSONArray("morphTargetBinds")
        val targets = mutableMapOf<Int, MutableMap<Int, Float>>()
        if (binds != null)
            for (i in 0 until binds.length()) {
                val b = binds.getJSONObject(i)
                targets.getOrPut(b.getInt("node")) { mutableMapOf() }[b.getInt("index")] =
                    b.optDouble("weight", 1.0).toFloat()
            }
        val groups =
            ex.optJSONObject("VRM")
                ?.optJSONObject("blendShapeMaster")
                ?.optJSONArray("blendShapeGroups")
        if (groups != null)
            for (i in 0 until groups.length()) {
                val g = groups.getJSONObject(i)
                if (
                    g.optString("presetName") !in listOf(name, if (name == "happy") "joy" else name)
                )
                    continue
                val bs = g.optJSONArray("binds") ?: continue
                for (k in 0 until bs.length()) {
                    val b = bs.getJSONObject(k)
                    for (n in 0 until nodes.length()) if (
                        nodes.getJSONObject(n).optInt("mesh", -1) == b.getInt("mesh")
                    )
                        targets.getOrPut(n) { mutableMapOf() }[b.getInt("index")] =
                            b.optDouble("weight", 100.0).toFloat() / 100f
                }
            }
        for ((node, weights) in targets) {
            if (node !in 0 until nodes.length()) continue
            val mi = nodes.getJSONObject(node).optInt("mesh", -1)
            if (mi !in 0 until meshes.length()) continue
            val ps = meshes.getJSONObject(mi).getJSONArray("primitives")
            val count = ps.getJSONObject(0).optJSONArray("targets")?.length() ?: 0
            if (count !in 1..64) continue
            val values = FloatArray(samples * count)
            for (k in 0 until samples) for ((index, w) in weights) if (index in 0 until count)
                values[k * count + index] =
                    w.coerceIn(0f, 1f) *
                        (if (mode == "blink") max(0f, 1f - abs(k / (samples - 1f) - .65f) / .06f)
                        else 1f)
            channel(node, "weights", values, 1, "SCALAR")
        }
    }
}

