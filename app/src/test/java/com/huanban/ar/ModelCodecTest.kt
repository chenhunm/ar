package com.huanban.ar

import java.nio.ByteBuffer
import java.nio.ByteOrder

import java.io.File
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelCodecTest {
    private fun fixture() = File("src/main/assets/models/qing.glb").readBytes()

    private fun fixture2() = File("src/main/assets/models/momo.glb").readBytes()

    private fun rejects(task: () -> Unit) {
        try {
            task()
            fail("应拒绝损坏输入")
        } catch (e: IllegalArgumentException) {} catch (e: IllegalStateException) {} catch (
            e: org.json.JSONException) {}
    }

    @Test
    fun originalRolesHaveSkinsAndGeneratedJointAnimations() {
        for (input in listOf(fixture(), fixture2())) {
            val r = ModelCodec.convert(input)
            assertTrue(r.rigged)
            assertEquals(7, r.animations.size)
            val j = ModelCodec.unpack(r.bytes).first
            val nodes = j.getJSONArray("nodes")
            val animations = j.getJSONArray("animations")
            assertTrue(
                j.getJSONArray("skins").getJSONObject(0).getJSONArray("joints").length() >= 15
            )
            for (i in 0 until animations.length()) {
                val a = animations.getJSONObject(i)
                val channels = a.getJSONArray("channels")
                assertTrue(channels.length() >= 11)
                for (k in 0 until channels.length()) {
                    val t = channels.getJSONObject(k).getJSONObject("target")
                    assertTrue(t.getInt("node") < nodes.length())
                }
            }
        }
    }

    @Test
    fun emptyRejected() {
        rejects { ModelCodec.convert(byteArrayOf()) }
    }

    @Test
    fun corruptHeaderRejected() {
        val b = fixture()
        b[8] = 0
        rejects { ModelCodec.convert(b) }
    }

    @Test
    fun truncatedChunkRejected() {
        rejects { ModelCodec.convert(fixture().copyOf(100)) }
    }

    @Test
    fun unsupportedVersionRejected() {
        val b = fixture()
        b[4] = 1
        rejects { ModelCodec.convert(b) }
    }

    @Test
    fun wrongJsonVersionRejected() {
        rejects { ModelCodec.convert("{\"asset\":{\"version\":\"1.0\"}}".toByteArray()) }
    }

    @Test
    fun unsafePathsRejected() {
        for (s in
            listOf(
                "../a.bin",
                "/a.bin",
                "https://host/x",
                "a/../../x",
                "%2e%2e/a.bin",
                "C:/x",
                "a\\b",
                "a/%2f../x",
                "a?x",
                "a#x",
            )) rejects { ModelCodec.safePath(s) }
    }

    @Test
    fun escapedResourcePathsSupported() {
        assertEquals("textures/人物.png", ModelCodec.safePath("textures/%E4%BA%BA%E7%89%A9.png"))
        assertEquals("a+b.bin", ModelCodec.safePath("a+b.bin"))
    }

    @Test
    fun externalGltfResourcesBundled() {
        val (j, bin) = ModelCodec.unpack(fixture())
        j.getJSONArray("buffers").getJSONObject(0).put("uri", "mesh.bin")
        val r =
            ModelCodec.convert(j.toString().toByteArray()) { p ->
                assertEquals("mesh.bin", p)
                bin
            }
        assertTrue(r.rigged)
        assertFalse(
            ModelCodec.unpack(r.bytes).first.getJSONArray("buffers").getJSONObject(0).has("uri")
        )
    }

    @Test
    fun missingGltfResourceRejected() {
        val (j, _) = ModelCodec.unpack(fixture())
        j.getJSONArray("buffers").getJSONObject(0).put("uri", "missing.bin")
        rejects { ModelCodec.convert(j.toString().toByteArray()) }
    }

    @Test
    fun embeddedBufferSupported() {
        val (j, bin) = ModelCodec.unpack(fixture())
        j.getJSONArray("buffers")
            .getJSONObject(0)
            .put(
                "uri",
                "data:application/octet-stream;base64," +
                    java.util.Base64.getEncoder().encodeToString(bin),
            )
        assertTrue(ModelCodec.convert(j.toString().toByteArray()).rigged)
    }

    @Test
    fun vrm0MappingSupported() {
        val (j, b) = ModelCodec.unpack(fixture())
        val bones = JSONArray()
        for ((a, c) in
            mapOf(
                "hips" to "hips",
                "spine" to "spine",
                "head" to "head",
                "leftUpperArm" to "upperArmLeft",
                "leftLowerArm" to "lowerArmLeft",
                "rightUpperArm" to "upperArmRight",
                "rightLowerArm" to "lowerArmRight",
                "leftUpperLeg" to "upperLegLeft",
                "leftLowerLeg" to "lowerLegLeft",
                "rightUpperLeg" to "upperLegRight",
                "rightLowerLeg" to "lowerLegRight",
            )) {
            val ns = j.getJSONArray("nodes")
            val i = (0 until ns.length()).first { ns.getJSONObject(it).getString("name") == c }
            ns.getJSONObject(i).put("name", "bone$i")
            bones.put(JSONObject().put("bone", a).put("node", i))
        }
        j.put(
            "extensions",
            JSONObject()
                .put("VRM", JSONObject().put("humanoid", JSONObject().put("humanBones", bones))),
        )
        val r = ModelCodec.convert(ModelCodec.pack(j, b))
        assertTrue(r.rigged)
        assertTrue(r.warning.contains("MToon"))
    }

    @Test
    fun noSkinStillDisplaysWithoutGeneratedMotion() {
        val (j, b) = ModelCodec.unpack(fixture())
        j.remove("skins")
        val ns = j.getJSONArray("nodes")
        for (i in 0 until ns.length()) ns.getJSONObject(i).remove("skin")
        val r = ModelCodec.convert(ModelCodec.pack(j, b))
        assertFalse(r.rigged)
        assertEquals(0, r.animations.size)
        assertTrue(r.warning.isNotEmpty())
    }

    @Test
    fun oversizedFileRejected() {
        rejects { ModelCodec.convert(ByteArray(ModelCodec.MAX_BYTES + 1)) }
    }

    @Test
    fun bufferOverrunRejected() {
        val (j, b) = ModelCodec.unpack(fixture())
        j.getJSONArray("bufferViews").getJSONObject(0).put("byteLength", Int.MAX_VALUE)
        rejects { ModelCodec.convert(ModelCodec.pack(j, b)) }
    }

    @Test
    fun accessorOverrunRejected() {
        val (j, b) = ModelCodec.unpack(fixture())
        j.getJSONArray("accessors").getJSONObject(0).put("count", 1_000_000)
        rejects { ModelCodec.convert(ModelCodec.pack(j, b)) }
    }

    @Test
    fun cyclicRigRejected() {
        val (j, b) = ModelCodec.unpack(fixture())
        j.getJSONArray("nodes").getJSONObject(0).put("children", JSONArray().put(0))
        rejects { ModelCodec.convert(ModelCodec.pack(j, b)) }
    }

    @Test
    fun invalidChildRejected() {
        val (j, b) = ModelCodec.unpack(fixture())
        j.getJSONArray("nodes").getJSONObject(0).put("children", JSONArray().put(99999))
        rejects { ModelCodec.convert(ModelCodec.pack(j, b)) }
    }

    @Test
    fun requiredCompressionRejectedClearly() {
        val (j, b) = ModelCodec.unpack(fixture())
        j.put("extensionsRequired", JSONArray().put("KHR_draco_mesh_compression"))
        rejects { ModelCodec.convert(ModelCodec.pack(j, b)) }
    }

    @Test
    fun matrixRigDoesNotReceiveUnsafeGeneratedClips() {
        val (j, b) = ModelCodec.unpack(fixture())
        j.getJSONArray("nodes")
            .getJSONObject(0)
            .put("matrix", JSONArray(listOf(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1)))
        assertFalse(ModelCodec.convert(ModelCodec.pack(j, b)).rigged)
    }

    @Test
    fun quaternionCompositionStaysNormalized() {
        repeat(100) {
            val q =
                RigMotion.multiply(
                    floatArrayOf(0f, 0f, 0f, 1f),
                    floatArrayOf(
                        0f,
                        kotlin.math.sin(it.toFloat()),
                        0f,
                        kotlin.math.cos(it.toFloat()),
                    ),
                )
            assertTrue(abs(q.sumOf { (it * it).toDouble() } - 1) < .0001)
        }
    }

    @Test
    fun packRoundTripPreservesBinary() {
        val (j, b) = ModelCodec.unpack(fixture())
        val (j2, b2) = ModelCodec.unpack(ModelCodec.pack(j, b))
        assertEquals(j.toString(), j2.toString())
        assertArrayEquals(b, b2)
    }

    @Test
    fun backgroundAppSwitchDoesNotHideForegroundPet() {
        assertFalse(ResourcePolicy.pausePetForMemory(20))
        assertFalse(ResourcePolicy.pausePetForMemory(5))
        assertFalse(ResourcePolicy.pausePetForMemory(10))
        assertTrue(ResourcePolicy.pausePetForMemory(15))
        assertTrue(ResourcePolicy.pausePetForMemory(60))
        assertTrue(ResourcePolicy.pausePetForMemory(80))
    }

    @Test
    fun meshNodeReferenceOverrunRejected() {
        val (j, b) = ModelCodec.unpack(fixture())
        j.getJSONArray("nodes").getJSONObject(0).put("mesh", 9999)
        rejects { ModelCodec.convert(ModelCodec.pack(j, b)) }
    }

    @Test
    fun invalidSkinJointReferenceRejected() {
        val (j, b) = ModelCodec.unpack(fixture())
        j.getJSONArray("skins").getJSONObject(0).getJSONArray("joints").put(0, 9999)
        rejects { ModelCodec.convert(ModelCodec.pack(j, b)) }
    }

    @Test
    fun tPoseArmsAreRetargetedToDownwardRest() {
        val (j, b) = ModelCodec.unpack(fixture())
        val ns = j.getJSONArray("nodes")
        fun index(name: String) =
            (0 until ns.length()).first { ns.getJSONObject(it).getString("name") == name }
        ns.getJSONObject(index("lowerArmLeft")).put("translation", JSONArray(listOf(-.3, 0, 0)))
        ns.getJSONObject(index("lowerArmRight")).put("translation", JSONArray(listOf(.3, 0, 0)))
        val result = ModelCodec.convert(ModelCodec.pack(j, b))
        assertTrue(result.rigged)
        val (converted, bin) = ModelCodec.unpack(result.bytes)
        val animation = converted.getJSONArray("animations").getJSONObject(0)
        val channels = animation.getJSONArray("channels")
        for (side in listOf("Left", "Right")) {
            val channel =
                (0 until channels.length())
                    .map { channels.getJSONObject(it) }
                    .first { it.getJSONObject("target").getInt("node") == index("upperArm$side") }
            val sampler =
                animation.getJSONArray("samplers").getJSONObject(channel.getInt("sampler"))
            val accessor =
                converted.getJSONArray("accessors").getJSONObject(sampler.getInt("output"))
            val view =
                converted.getJSONArray("bufferViews").getJSONObject(accessor.getInt("bufferView"))
            val buffer = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN)
            buffer.position(view.getInt("byteOffset"))
            val q = FloatArray(4) { buffer.float }
            assertEquals(if (side == "Left") .7071f else -.7071f, q[2], .001f)
            assertEquals(.7071f, q[3], .001f)
        }
    }
    @Test fun knownSwiftShader4FailureIsDetected() {
        assertTrue(GpuCompatibility.isKnownBrokenDriver("Android Emulator OpenGL ES Translator (Google SwiftShader)","OpenGL ES 3.0 SwiftShader 4.0.0.1",256))
    }
    @Test fun glesUniformBufferDevicesAreNotRejectedByStandaloneUniformCount() {
        assertFalse(GpuCompatibility.isKnownBrokenDriver("Adreno (TM) 750","OpenGL ES 3.2",256))
        assertFalse(GpuCompatibility.isKnownBrokenDriver("ANGLE (Google, SwiftShader Device (Subzero))","OpenGL ES 3.1",256))
    }
    @Test fun capableSwiftShaderDriverIsNotBlanketBlocked() {
        assertFalse(GpuCompatibility.isKnownBrokenDriver("SwiftShader","OpenGL ES 3.0 SwiftShader 4.0.0.1",1024))
    }
}
