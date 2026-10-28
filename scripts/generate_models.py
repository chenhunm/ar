"""Original CC0 geometric humanoids. Skinned meshes, 17 joints, no external assets."""
import json,struct,math,pathlib
ROOT=pathlib.Path(__file__).resolve().parents[1]
def generate(chibi):
 data=bytearray(); views=[]; access=[]
 def acc(vals,kind,n,ctype=5126):
  while len(data)%4:data.append(0)
  off=len(data); fmt='f' if ctype==5126 else 'H'
  data.extend(struct.pack('<'+fmt*len(vals),*vals)); v=len(views); views.append(dict(buffer=0,byteOffset=off,byteLength=len(data)-off))
  a=dict(bufferView=v,componentType=ctype,count=len(vals)//n,type=kind)
  if kind=='VEC3':a.update(min=[min(vals[i::n]) for i in range(n)],max=[max(vals[i::n]) for i in range(n)])
  access.append(a);return len(access)-1
 nodes=[]; worlds=[]; names={}
 def joint(name,parent,xyz):
  i=len(nodes); names[name]=i; nodes.append(dict(name=name,translation=xyz)); worlds.append([xyz[j]+(worlds[parent][j] if parent is not None else 0) for j in range(3)])
  if parent is not None:nodes[parent].setdefault('children',[]).append(i)
  return i
 hip=joint('hips',None,[0,.85 if not chibi else .5,0]);sp=joint('spine',hip,[0,.24 if not chibi else .16,0]);ch=joint('chest',sp,[0,.24 if not chibi else .12,0]);neck=joint('neck',ch,[0,.19 if not chibi else .1,0]);head=joint('head',neck,[0,.12 if not chibi else .19,0])
 for side,s in [('Left',-1),('Right',1)]:
  a=joint('upperArm'+side,ch,[s*(.24 if not chibi else .19),.08,0]);b=joint('lowerArm'+side,a,[0,-(.3 if not chibi else .16),0]);joint('hand'+side,b,[0,-(.25 if not chibi else .13),0])
  a=joint('upperLeg'+side,hip,[s*.12,-.08,0]);b=joint('lowerLeg'+side,a,[0,-(.38 if not chibi else .19),0]);joint('foot'+side,b,[0,-(.34 if not chibi else .16),.04])
 for side,s in [('Left',-1),('Right',1)]:joint('eye'+side,head,[s*(.072 if not chibi else .105),.035,.154 if not chibi else .238])
 joint('mouth',head,[0,-.08 if not chibi else -.13,.157 if not chibi else .239])
 pos=[];norm=[];joints=[];weights=[];indices=[];prims=[]
 mats=[dict(name=n,pbrMetallicRoughness=dict(baseColorFactor=c,metallicFactor=0,roughnessFactor=.65)) for n,c in [('衣服',[.2,.65,.65,1] if not chibi else [.68,.43,.91,1]),('皮肤',[.97,.78,.63,1]),('头发',[.12,.18,.26,1]),('眼睛',[.03,.06,.1,1]),('鞋子',[.18,.22,.3,1]),('白色',[.95,.99,1,1])]]
 def box(bone,center,size,mat):
  p=[]; no=[]; ix=[]; w=worlds[bone]
  for normal,corners in [([0,0,1],[(-1,-1,1),(1,-1,1),(1,1,1),(-1,1,1)]),([0,0,-1],[(1,-1,-1),(-1,-1,-1),(-1,1,-1),(1,1,-1)]),([1,0,0],[(1,-1,1),(1,-1,-1),(1,1,-1),(1,1,1)]),([-1,0,0],[(-1,-1,-1),(-1,-1,1),(-1,1,1),(-1,1,-1)]),([0,1,0],[(-1,1,1),(1,1,1),(1,1,-1),(-1,1,-1)]),([0,-1,0],[(-1,-1,-1),(1,-1,-1),(1,-1,1),(-1,-1,1)])]:
   k=len(p)//3
   for v in corners:p.extend(w[t]+center[t]+v[t]*size[t]/2 for t in range(3));no.extend(normal)
   ix.extend(k+t for t in [0,1,2,0,2,3])
  prims.append(dict(attributes=dict(POSITION=acc(p,'VEC3',3),NORMAL=acc(no,'VEC3',3),JOINTS_0=acc([v for _ in range(24) for v in [bone,0,0,0]],'VEC4',4,5123),WEIGHTS_0=acc([v for _ in range(24) for v in [1,0,0,0]],'VEC4',4)),indices=acc(ix,'SCALAR',1,5123),material=mat))
 box(hip,[0,0,0],[.39 if not chibi else .32,.26 if not chibi else .22,.23],4)
 box(neck,[0,-.07 if not chibi else -.04,0],[.11,.25 if not chibi else .15,.12],1)
 box(sp,[0,.09,0],[.4 if not chibi else .34,.43 if not chibi else .3,.22],0)
 box(head,[0,.02,0],[.33 if not chibi else .52,.37 if not chibi else .52,.3 if not chibi else .46],1)
 box(head,[0,.19 if not chibi else .27,-.025],[.36 if not chibi else .55,.09,.34 if not chibi else .5],2)
 for side in ['Left','Right']:
  for part,l,sz,m in [('upperArm',.3 if not chibi else .16,.12,0),('lowerArm',.25 if not chibi else .13,.1,1),('hand',.09,.12,1),('upperLeg',.38 if not chibi else .19,.15,4),('lowerLeg',.34 if not chibi else .16,.13,4),('foot',.08,.16,4)]:box(names[part+side],[0,-l/2,0],[sz,l,sz*1.1],m)
  box(names['eye'+side],[0,0,.005],[.046 if not chibi else .07,.06,.015],3)
 box(names['mouth'],[0,0,0],[.07,.018,.012],3)
 ib=[]
 for p in worlds:
  ib.extend([1,0,0,0,0,1,0,0,0,0,1,0,-p[0],-p[1],-p[2],1])
 skin=dict(joints=list(range(len(nodes))),inverseBindMatrices=acc(ib,'MAT4',16),skeleton=0)
 nodes.append(dict(name='HuanBanMesh',mesh=0,skin=0))
 gl=dict(asset=dict(version='2.0',generator='HuanBan original CC0 geometry'),scene=0,scenes=[dict(nodes=[0,len(nodes)-1])],nodes=nodes,skins=[skin],meshes=[dict(primitives=prims)],materials=mats,bufferViews=views,accessors=access,buffers=[dict(byteLength=len(data))])
 js=json.dumps(gl,separators=(',',':')).encode();js+=b' '*((-len(js))%4);data+=b'\0'*((-len(data))%4)
 out=struct.pack('<III',0x46546c67,2,12+8+len(js)+8+len(data))+struct.pack('<II',len(js),0x4e4f534a)+js+struct.pack('<II',len(data),0x004e4942)+data
 p=ROOT/'app/src/main/assets/models'/('momo.glb' if chibi else 'qing.glb');p.write_bytes(out);print(p.name,len(out))
for c in [False,True]:generate(c)
