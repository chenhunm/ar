package com.huanban.ar

/** A uniform count alone is not a skinning capability test: GLES 3 uses uniform buffer blocks. */
internal object GpuCompatibility {
    fun isKnownBrokenDriver(renderer: String, version: String, vertexUniforms: Int): Boolean =
        vertexUniforms in 1..256 && version.contains("SwiftShader 4.", ignoreCase = true) &&
            !renderer.contains("ANGLE", ignoreCase = true)
}
