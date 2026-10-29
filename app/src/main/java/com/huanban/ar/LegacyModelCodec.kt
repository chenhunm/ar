package com.huanban.ar

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONArray
import org.json.JSONObject

/** PMD 1.0 and XNALara basic geometry, skins and diffuse textures.
 * IK, physics, proprietary material groups and default pose overrides are not baked.
 */
object LegacyModelCodec {
    val extensions=setOf("pmd","ascii","mesh","xps")
    private data class Bone(val name:String,val parent:Int,val position:FloatArray)
    private data class Part(val name:String,val positions:FloatArray,val normals:FloatArray,val uv:FloatArray,
        val indices:IntArray,val joints:IntArray,val weights:FloatArray,val texture:String?,val color:FloatArray=floatArrayOf(1f,1f,1f,1f))
    private class Reader(bytes:ByteArray) {
        val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun u8()=b.get().toInt() and 255
        fun u16()=b.short.toInt() and 65535
        fun s16()=b.short.toInt()
        fun count(max:Int):Int {val n=b.int;require(n in 0..max){"模型数量字段越界"};return n}
        fun floats(n:Int)=FloatArray(n){b.float.also{v->require(v.isFinite()){ "模型坐标不是有限数" }}}
        fun skip(n:Int) {require(n>=0 && n<=b.remaining()){ "模型数据被截断" };b.position(b.position()+n)}
        fun fixed(n:Int):String {val a=ByteArray(n);b.get(a);return String(a.takeWhile{it!=0.toByte()}.toByteArray(),charset("Shift_JIS"))}
        fun text():String {val first=u8();val n=(first and 127)+if(first>=128)u8()*128 else 0;require(n<=4096 && n<=b.remaining());val a=ByteArray(n);b.get(a);return String(a,Charsets.UTF_8)}
    }

    fun convert(name:String,bytes:ByteArray,resolve:(String)->ByteArray):ByteArray {
        require(bytes.size in 1..ModelCodec.MAX_BYTES)
        val (bones,parts)=when(name.substringAfterLast('.').lowercase()) {
            "pmd"->pmd(bytes)
            "ascii"->ascii(bytes)
            else->binaryXps(bytes)
        }
        return glb(bones,parts,resolve)
    }

    private fun pmd(bytes:ByteArray):Pair<List<Bone>,List<Part>> {
        val r=Reader(bytes);require(r.fixed(3)=="Pmd" && r.b.float==1f){"只支持 PMD 1.0"};val name=r.fixed(20);r.skip(256)
        val n=r.count(750000);require(n>0)
        val positions=FloatArray(n*3);val normals=FloatArray(n*3);val uv=FloatArray(n*2);val joints=IntArray(n*4);val weights=FloatArray(n*4)
        repeat(n){i->
            r.floats(3).copyInto(positions,i*3);positions[i*3+2]*=-1f
            r.floats(3).copyInto(normals,i*3);normals[i*3+2]*=-1f
            r.floats(2).copyInto(uv,i*2)
            joints[i*4]=r.u16();joints[i*4+1]=r.u16()
            val w=r.u8();require(w<=100);weights[i*4]=w/100f;weights[i*4+1]=1f-w/100f;r.skip(1)
        }
        val count=r.count(2250000);require(count%3==0)
        val indices=IntArray(count){r.u16().also{require(it<n)}}
        for(i in indices.indices step 3){val a=indices[i];indices[i]=indices[i+2];indices[i+2]=a}
        val materials=r.count(256);require(materials>0)
        val parts=ArrayList<Part>();var start=0
        repeat(materials){i->
            val color=r.floats(4);r.skip(7*4+2)
            val length=r.count(count);require(length%3==0 && start+length<=count)
            val texture=r.fixed(20).split('*').firstOrNull{!it.endsWith(".sph",true)&&!it.endsWith(".spa",true)}?.ifBlank{null}
            if(length>0)parts+=Part("$name-$i",positions,normals,uv,indices.copyOfRange(start,start+length),joints,weights,texture,color)
            start+=length
        }
        require(start==count)
        val boneCount=r.u16();require(boneCount<=256){"PMD 骨骼超过 256，请先导出分皮肤 GLB"}
        val bones=List(boneCount){val label=r.fixed(20);val parent=r.u16();r.skip(5);val p=r.floats(3);p[2]*=-1f;Bone(label,if(parent==65535)-1 else parent,p)}
        require(r.b.remaining()>=4){"PMD 末尾数据损坏"}
        return bones to parts
    }

    private fun ascii(bytes:ByteArray):Pair<List<Bone>,List<Part>> {
        val lines=String(bytes,Charsets.UTF_8).removePrefix("\uFEFF").lineSequence().iterator()
        fun line():String {require(lines.hasNext()){ "XPS 文本被截断" };return lines.next().substringBefore('#').trim()}
        fun integer(max:Int)=line().toInt().also{require(it in 0..max)}
        fun values(n:Int)=line().split(Regex("\\s+")).map{it.toFloat().also{f->require(f.isFinite())}}.also{require(it.size>=n)}
        val boneCount=integer(256)
        val bones=List(boneCount){val name=line();val parent=line().toInt();Bone(name,parent,values(3).take(3).toFloatArray())}
        var total=0
        val parts=List(integer(256)){
            val name=line();val layers=integer(8);require(layers>0)
            val textures=List(integer(32)){val path=line();val layer=integer(layers-1);path to layer}
            val n=integer(750000);total+=n;require(total<=750000)
            val p=FloatArray(n*3);val normal=FloatArray(n*3);val uv=FloatArray(n*2);val joints=IntArray(n*4);val weights=FloatArray(n*4)
            val layer=textures.firstOrNull()?.second?:0
            repeat(n){i->
                values(3).take(3).toFloatArray().copyInto(p,i*3);values(3).take(3).toFloatArray().copyInto(normal,i*3)
                values(4) // vertex colors are not used by the basic diffuse material
                repeat(layers){l->val v=values(2);if(l==layer)v.take(2).toFloatArray().copyInto(uv,i*2)}
                if(boneCount>0){
                    val ids=values(1).map{it.toInt()};val ws=values(1);require(ids.size==ws.size)
                    val sorted=ids.indices.sortedByDescending{ws[it]}.take(4)
                    sorted.forEachIndexed{k,j->joints[i*4+k]=ids[j];weights[i*4+k]=ws[j]}
                }
            }
            val faces=integer(750000)
            val indices=IntArray(faces*3)
            repeat(faces){i->val v=values(3);repeat(3){k->indices[i*3+k]=v[k].toInt().also{require(it in 0 until n)}}}
            Part(name,p,normal,uv,indices,joints,weights,textures.firstOrNull()?.first)
        }
        return bones to parts
    }

    private fun binaryXps(bytes:ByteArray):Pair<List<Bone>,List<Part>> {
        val r=Reader(bytes);var tangent=true;var variable=false
        if(r.b.getInt(0)==323232){
            r.b.int;val major=r.u16();val minor=r.u16();require(major in 1..3){"不支持此 XPS 版本"}
            r.text();val settings=r.count(1024*1024);r.text();r.text();r.text()
            tangent=major<=2 && minor<=12;variable=major>=3
            r.skip(Math.multiplyExact(settings,4))
        }
        val boneCount=r.count(256)
        val bones=List(boneCount){val name=r.text();val parent=r.s16();Bone(name,parent,r.floats(3))}
        var total=0
        val parts=List(r.count(256)){
            val name=r.text();val layers=r.count(8);require(layers>0)
            val textures=List(r.count(32)){val path=r.text();val layer=r.count(layers-1);path to layer}
            val n=r.count(750000);total+=n;require(total<=750000)
            val p=FloatArray(n*3);val normals=FloatArray(n*3);val uv=FloatArray(n*2);val joints=IntArray(n*4);val weights=FloatArray(n*4)
            val layer=textures.firstOrNull()?.second?:0
            repeat(n){i->
                r.floats(3).copyInto(p,i*3);r.floats(3).copyInto(normals,i*3);r.skip(4)
                repeat(layers){l->val v=r.floats(2);if(l==layer)v.copyInto(uv,i*2);if(tangent)r.skip(16)}
                if(boneCount>0){
                    val count=if(variable)r.u16() else 4;require(count in 1..64)
                    val ids=IntArray(count){r.s16()};val ws=r.floats(count)
                    ids.indices.sortedByDescending{ws[it]}.take(4).forEachIndexed{k,j->joints[i*4+k]=ids[j];weights[i*4+k]=ws[j]}
                }
            }
            val faces=r.count(750000);val indices=IntArray(faces*3){r.count(n-1)}
            Part(name,p,normals,uv,indices,joints,weights,textures.firstOrNull()?.first)
        }
        require(!r.b.hasRemaining()){ "XPS 含不支持的附加数据或版本不匹配" }
        return bones to parts
    }

    private fun glb(bones:List<Bone>,parts:List<Part>,resolve:(String)->ByteArray):ByteArray {
        require(parts.isNotEmpty()){"没有可显示的网格"}
        bones.forEachIndexed{i,b->require(b.parent in -1 until bones.size && b.parent!=i){"骨骼父节点无效"}}
        val data=ByteArrayOutputStream();val views=JSONArray();val accessors=JSONArray();val meshes=JSONArray();val materials=JSONArray();val images=JSONArray();val textures=JSONArray()
        fun block(bytes:ByteArray):Int {
            while(data.size()%4!=0)data.write(0)
            require(data.size().toLong()+bytes.size<=ModelCodec.MAX_BYTES){"转换后模型超过 64 MB"}
            val index=views.length();views.put(JSONObject().put("buffer",0).put("byteOffset",data.size()).put("byteLength",bytes.size));data.write(bytes);return index
        }
        fun accessor(bytes:ByteArray,type:String,component:Int,count:Int):Int {
            val i=accessors.length();accessors.put(JSONObject().put("bufferView",block(bytes)).put("componentType",component).put("count",count).put("type",type));return i
        }
        fun floats(values:FloatArray)=ByteBuffer.allocate(values.size*4).order(ByteOrder.LITTLE_ENDIAN).apply{values.forEach{putFloat(it)}}.array()
        parts.forEachIndexed{index,part->
            val count=part.positions.size/3;require(count>0)
            val pos=accessor(floats(part.positions),"VEC3",5126,count)
            accessors.getJSONObject(pos).put("min",JSONArray((0..2).map{axis->(0 until count).minOf{part.positions[it*3+axis]}})).put("max",JSONArray((0..2).map{axis->(0 until count).maxOf{part.positions[it*3+axis]}}))
            val attributes=JSONObject().put("POSITION",pos).put("NORMAL",accessor(floats(part.normals),"VEC3",5126,count)).put("TEXCOORD_0",accessor(floats(part.uv),"VEC2",5126,count))
            if(bones.isNotEmpty()){
                for(i in 0 until count){
                    var sum=0f
                    repeat(4){k->val v=i*4+k;require(part.weights[v].isFinite()&&part.weights[v]>=0f);if(part.weights[v]==0f)part.joints[v]=0 else require(part.joints[v] in bones.indices);sum+=part.weights[v]}
                    require(sum.isFinite()) { "骨骼权重总和超限" }
                    if(sum<.000001f){part.joints[i*4]=0;part.weights[i*4]=1f}else repeat(4){k->part.weights[i*4+k]/=sum}
                }
                val js=ByteBuffer.allocate(part.joints.size*2).order(ByteOrder.LITTLE_ENDIAN).apply{part.joints.forEach{putShort(it.toShort())}}.array()
                attributes.put("JOINTS_0",accessor(js,"VEC4",5123,count)).put("WEIGHTS_0",accessor(floats(part.weights),"VEC4",5126,count))
            }
            val indices=ByteBuffer.allocate(part.indices.size*4).order(ByteOrder.LITTLE_ENDIAN).apply{part.indices.forEach{putInt(it)}}.array()
            val pbr=JSONObject().put("baseColorFactor",JSONArray(part.color.map{it.coerceIn(0f,1f)})).put("metallicFactor",0).put("roughnessFactor",1)
            part.texture?.let{path->
                val safe=ModelCodec.safePath(path.replace('\\','/'))
                val bytes=resolve(safe)
                val mime=when {bytes.size>8 && bytes[0]==137.toByte() && bytes[1]==80.toByte()->"image/png";bytes.size>2&&bytes[0]==255.toByte()&&bytes[1]==216.toByte()->"image/jpeg";else->error("纹理需为 PNG/JPEG：$safe")}
                images.put(JSONObject().put("bufferView",block(bytes)).put("mimeType",mime));textures.put(JSONObject().put("source",images.length()-1))
                pbr.put("baseColorTexture",JSONObject().put("index",textures.length()-1))
            }
            materials.put(JSONObject().put("pbrMetallicRoughness",pbr).put("doubleSided",true).put("alphaMode","BLEND"))
            meshes.put(JSONObject().put("name",part.name).put("primitives",JSONArray().put(JSONObject().put("attributes",attributes).put("indices",accessor(indices,"SCALAR",5125,part.indices.size)).put("material",index))))
        }
        val nodes=JSONArray().put(JSONObject().put("name","ImportedRoot"));val roots=JSONArray()
        parts.forEachIndexed{i,p->nodes.put(JSONObject().put("name",p.name).put("mesh",i).also{if(bones.isNotEmpty())it.put("skin",0)});roots.put(i+1)}
        val base=nodes.length()
        bones.forEachIndexed{i,b->
            val t=b.position.copyOf();if(b.parent>=0)repeat(3){k->t[k]-=bones[b.parent].position[k]}
            val node=JSONObject().put("name",b.name).put("translation",JSONArray(t.toList()))
            val children=bones.indices.filter{bones[it].parent==i}.map{it+base};if(children.isNotEmpty())node.put("children",JSONArray(children))
            nodes.put(node);if(b.parent<0)roots.put(i+base)
        }
        nodes.getJSONObject(0).put("children",roots)
        val j=JSONObject().put("asset",JSONObject().put("version","2.0")).put("nodes",nodes).put("meshes",meshes).put("materials",materials)
            .put("scenes",JSONArray().put(JSONObject().put("nodes",JSONArray().put(0)))).put("scene",0)
        if(bones.isNotEmpty()){
            val matrices=FloatArray(bones.size*16)
            bones.forEachIndexed{i,b->val k=i*16;for(axis in 0..3)matrices[k+axis*5]=1f;repeat(3){axis->matrices[k+12+axis]=-b.position[axis]}}
            val ibm=accessor(floats(matrices),"MAT4",5126,bones.size)
            j.put("skins",JSONArray().put(JSONObject().put("joints",JSONArray(bones.indices.map{it+base})).put("inverseBindMatrices",ibm)))
        }
        j.put("bufferViews",views).put("accessors",accessors).put("images",images).put("textures",textures).put("buffers",JSONArray().put(JSONObject().put("byteLength",data.size())))
        return ModelCodec.pack(j,data.toByteArray())
    }
}

