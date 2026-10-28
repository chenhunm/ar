package com.huanban.ar

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class LegacyModelCodecTest {
    private class Output {
        val bytes=ByteArrayOutputStream()
        fun i(value:Int){bytes.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())}
        fun s(value:Int){bytes.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array())}
        fun f(vararg values:Float){values.forEach{bytes.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(it).array())}}
        fun fixed(value:String,n:Int){val data=value.toByteArray(charset("Shift_JIS"));bytes.write(data);bytes.write(ByteArray(n-data.size))}
        fun text(value:String){val b=value.toByteArray();require(b.size<128);bytes.write(b.size);bytes.write(b)}
    }
    private fun pmd():ByteArray=Output().apply {
        fixed("Pmd",3);f(1f);fixed("model",20);fixed("",256);i(3)
        repeat(3){v->f(if(v==1)1f else 0f,if(v==2)1f else 0f,0f,0f,0f,1f,0f,0f);s(0);s(0);bytes.write(100);bytes.write(0)}
        i(3);s(0);s(1);s(2);i(1);f(1f,.5f,.3f,1f,0f,0f,0f,0f,0f,0f,0f);s(0);i(3);fixed("",20)
        s(1);fixed("下半身",20);s(65535);s(0);bytes.write(0);s(0);f(0f,0f,0f);s(0);s(0)
    }.bytes.toByteArray()

    @Test fun pmdGeometrySkinAndHandednessSurviveRealGlbValidation() {
        val out=LegacyModelCodec.convert("model.pmd",pmd()){error("No texture")}
        val result=ModelCodec.convert(out)
        val (j,bin)=ModelCodec.unpack(result.bytes)
        assertEquals(1,j.getJSONArray("skins").length())
        val primitive=j.getJSONArray("meshes").getJSONObject(0).getJSONArray("primitives").getJSONObject(0)
        val accessor=j.getJSONArray("accessors").getJSONObject(primitive.getInt("indices"))
        val view=j.getJSONArray("bufferViews").getJSONObject(accessor.getInt("bufferView"))
        val indices=ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN);indices.position(view.optInt("byteOffset"))
        assertEquals(2,indices.int);assertEquals(1,indices.int);assertEquals(0,indices.int)
    }
    @Test fun pmdOversizedVertexCountIsRejectedBeforeAllocation() {
        val bytes=pmd();ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(283,750001)
        assertThrows(IllegalArgumentException::class.java){LegacyModelCodec.convert("model.pmd",bytes){byteArrayOf()}}
    }
    @Test fun xpsAsciiProducesRealMesh() {
        val lines=mutableListOf("0","1","mesh","1","0","3")
        repeat(3){i->lines+=listOf("${if(i==1)1 else 0} ${if(i==2)1 else 0} 0","0 0 1","255 255 255 255","0 0")}
        lines+=listOf("1","0 1 2")
        val glb=LegacyModelCodec.convert("model.mesh.ascii",lines.joinToString("\n").toByteArray()){error("No texture")}
        assertEquals(1,ModelCodec.unpack(ModelCodec.convert(glb).bytes).first.getJSONArray("meshes").length())
    }
    @Test fun xpsBinaryLegacyTangentLayoutIsReadCorrectly() {
        val bytes=Output().apply {
            i(0);i(1);text("mesh");i(1);i(0);i(3)
            repeat(3){v->f(if(v==1)1f else 0f,if(v==2)1f else 0f,0f,0f,0f,1f);repeat(4){bytes.write(255)};f(0f,0f,1f,0f,0f,1f)}
            i(1);i(0);i(1);i(2)
        }.bytes.toByteArray()
        val glb=LegacyModelCodec.convert("generic_item.mesh",bytes){error("No texture")}
        assertEquals(1,ModelCodec.unpack(ModelCodec.convert(glb).bytes).first.getJSONArray("meshes").length())
        assertThrows(IllegalArgumentException::class.java){LegacyModelCodec.convert("bad.xps",bytes+byteArrayOf(0)){byteArrayOf()}}
    }
}
