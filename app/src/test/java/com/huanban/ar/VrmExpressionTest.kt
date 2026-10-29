package com.huanban.ar

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VrmExpressionTest {
    private fun fixture() = File("src/main/assets/models/qing.glb").readBytes()
    private fun checkBlinkPresetFallback(vrm1: Boolean, combined: Boolean) {
        val (j,b)=ModelCodec.unpack(fixture())
        val primitive=j.getJSONArray("meshes").getJSONObject(0).getJSONArray("primitives").getJSONObject(0)
        val position=primitive.getJSONObject("attributes").getInt("POSITION")
        primitive.put("targets",JSONArray().put(JSONObject().put("POSITION",position)).put(JSONObject().put("POSITION",position)))
        val nodes=j.getJSONArray("nodes")
        val node=(0 until nodes.length()).first{nodes.getJSONObject(it).optInt("mesh",-1)==0}
        fun bind(index: Int, weight: Double)=JSONObject().put(if(vrm1)"node" else "mesh",if(vrm1)node else 0).put("index",index).put("weight",weight)
        val extension=if(vrm1) {
            val preset=JSONObject()
                .put("blink",JSONObject().put("morphTargetBinds",JSONArray().also{if(combined)it.put(bind(0,.8))}))
                .put("blinkLeft",JSONObject().put("morphTargetBinds",JSONArray().put(bind(0,1.0))))
                .put("blinkRight",JSONObject().put("morphTargetBinds",JSONArray().put(bind(1,.5))))
            JSONObject().put("VRMC_vrm",JSONObject().put("expressions",JSONObject().put("preset",preset)))
        } else {
            fun group(name: String, binds: JSONArray)=JSONObject().put("presetName",name).put("binds",binds)
            val groups=JSONArray().put(group("blink",JSONArray().also{if(combined)it.put(bind(0,80.0))}))
                .put(group("blink_l",JSONArray().put(bind(0,100.0))))
                .put(group("blink_r",JSONArray().put(bind(1,50.0))))
            JSONObject().put("VRM",JSONObject().put("blendShapeMaster",JSONObject().put("blendShapeGroups",groups)))
        }
        j.put("extensions",extension)
        val (out,bin)=ModelCodec.unpack(ModelCodec.convert(ModelCodec.pack(j,b)).bytes)
        val animations=out.getJSONArray("animations")
        val blink=(0 until animations.length()).map{animations.getJSONObject(it)}.single{it.getString("name")=="幻伴·眨眼"}
        val channels=blink.getJSONArray("channels")
        val weights=(0 until channels.length()).map{channels.getJSONObject(it)}.filter{it.getJSONObject("target").getString("path")=="weights"}
        assertEquals("两个眼睛应合并为一个网格权重通道",1,weights.size)
        assertEquals(node,weights.single().getJSONObject("target").getInt("node"))
        val sampler=blink.getJSONArray("samplers").getJSONObject(weights.single().getInt("sampler"))
        val accessor=out.getJSONArray("accessors").getJSONObject(sampler.getInt("output"))
        val view=out.getJSONArray("bufferViews").getJSONObject(accessor.getInt("bufferView"))
        val buffer=ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(view.optInt("byteOffset")+accessor.optInt("byteOffset"))
        val values=FloatArray(accessor.getInt("count")){buffer.float}
        val left=values.indices.filter{it%2==0}.map{values[it]}
        val right=values.indices.filter{it%2==1}.map{values[it]}
        assertEquals(0f,left.first(),.0001f);assertEquals(0f,left.last(),.0001f)
        if(combined) {
            assertTrue(left.max() in .6f.. .8f);assertEquals(0f,right.max(),.0001f)
        } else {
            assertTrue(left.max()>.8f);assertTrue(right.max()>.4f)
            assertTrue(left.all{it in 0f..1f} && right.all{it in 0f.. .5f})
        }
    }
    @Test fun vrm0SeparateEyeBlinksAreMergedWhenCombinedPresetIsEmpty() = checkBlinkPresetFallback(false,false)
    @Test fun vrm1SeparateEyeBlinksAreMergedWhenCombinedPresetIsEmpty() = checkBlinkPresetFallback(true,false)
    @Test fun combinedBlinkPresetTakesPrecedenceOverSeparateEyes() {
        checkBlinkPresetFallback(false,true);checkBlinkPresetFallback(true,true)
    }

}
