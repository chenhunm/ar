package com.huanban.ar

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VrmOrientationTest {
    private fun converted(extension: String?): ByteArray {
        val (json, binary) = ModelCodec.unpack(File("src/main/assets/models/qing.glb").readBytes())
        if (extension != null) json.put("extensions", JSONObject().put(extension, JSONObject()))
        return ModelCodec.convert(ModelCodec.pack(json, binary)).bytes
    }

    @Test fun legacyVrmFacesViewerAfterConversionWithoutChangingModelData() {
        val bytes = converted("VRM")
        val before = bytes.copyOf()
        assertEquals(180f, ModelCodec.defaultViewYaw(bytes), 0f)
        assertArrayEquals("朝向只应改变视图，不能改蒙皮或动作数据", before, bytes)
    }

    @Test fun vrmOneAndOrdinaryGltfKeepTheirOriginalOrientation() {
        assertEquals(0f, ModelCodec.defaultViewYaw(converted("VRMC_vrm")), 0f)
        assertEquals(0f, ModelCodec.defaultViewYaw(converted(null)), 0f)
    }
}
