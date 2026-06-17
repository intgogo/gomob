import numpy as np, glob, sys
from PIL import Image

def load_pcd(p):
    raw=open(p,'rb').read(); off=raw.find(b'DATA binary\n')+len(b'DATA binary\n')
    return np.frombuffer(raw,dtype=np.float32,count=((len(raw)-off)//12)*3,offset=off).reshape(-1,3).astype(np.float64)

def save_pcd(path,pts):
    n=len(pts)
    h=f"# .PCD v0.7\nVERSION 0.7\nFIELDS x y z\nSIZE 4 4 4\nTYPE F F F\nCOUNT 1 1 1\nWIDTH {n}\nHEIGHT 1\nVIEWPOINT 0 0 0 1 0 0 0\nPOINTS {n}\nDATA binary\n"
    open(path,'wb').write(h.encode()+pts.astype(np.float32).tobytes())

P=load_pcd(glob.glob('out_live/toycar/UNKNOWN/2026-06-03/clouds_*/clouds.pcd')[0])
o1=np.array([0,0,0.]); o2=np.array([0.42,2.80,-0.18]); mid=(o1+o2)/2
print(f"fused {len(P)} pts")

# 1. crop to a generous cylinder around the lane midpoint (XY), keep full Z
dxy=np.linalg.norm(P[:,:2]-mid[:2],axis=1)
crop=P[dxy<2.2]
print(f"central crop (XY<2.2m of midpoint): {len(crop)}")

# 2. RANSAC: remove the dominant planar surface (floor / table the car rests on)
def ransac_plane(pts, iters=400, thr=0.03, seed=0):
    rng=np.random.default_rng(seed); best_in=None; best_n=0
    N=len(pts)
    for _ in range(iters):
        idx=rng.integers(0,N,3); p=pts[idx]
        v1=p[1]-p[0]; v2=p[2]-p[0]; nrm=np.cross(v1,v2)
        nl=np.linalg.norm(nrm)
        if nl<1e-6: continue
        nrm=nrm/nl; d=-nrm@p[0]
        dist=np.abs(pts@nrm+d)
        c=(dist<thr).sum()
        if c>best_n: best_n=c; best_in=(nrm,d)
    nrm,d=best_in; dist=np.abs(pts@nrm+d)
    return dist<thr, nrm, d

floor_mask,nrm,d=ransac_plane(crop)
print(f"dominant plane: normal=({nrm[0]:.2f},{nrm[1]:.2f},{nrm[2]:.2f}) inliers={floor_mask.sum()} ({100*floor_mask.sum()/len(crop):.0f}%)")
above=crop[~floor_mask]
print(f"after removing dominant plane: {len(above)}")

# 3. voxel connected-components (5 cm)
V=0.05
keys=np.floor(above/V).astype(np.int64)
occ={}
for i,k in enumerate(map(tuple,keys)):
    occ.setdefault(k,[]).append(i)
visited=set(); clusters=[]
neigh=[(dx,dy,dz) for dx in(-1,0,1) for dy in(-1,0,1) for dz in(-1,0,1) if not(dx==0 and dy==0 and dz==0)]
for start in occ:
    if start in visited: continue
    stack=[start]; visited.add(start); comp=[]
    while stack:
        c=stack.pop(); comp.append(c)
        cx,cy,cz=c
        for dx,dy,dz in neigh:
            nb=(cx+dx,cy+dy,cz+dz)
            if nb in occ and nb not in visited:
                visited.add(nb); stack.append(nb)
    pidx=[i for vox in comp for i in occ[vox]]
    clusters.append(pidx)
clusters.sort(key=len,reverse=True)
print(f"clusters: {len(clusters)}; top sizes={[len(c) for c in clusters[:6]]}")
for ci,c in enumerate(clusters[:6]):
    pts=above[c]; ctr=pts.mean(0); sp=pts.max(0)-pts.min(0)
    dmid=np.linalg.norm(ctr[:2]-mid[:2])
    print(f"  cluster{ci}: n={len(c)} span=({sp[0]:.2f},{sp[1]:.2f},{sp[2]:.2f}) center=({ctr[0]:.2f},{ctr[1]:.2f},{ctr[2]:.2f}) dXY_mid={dmid:.2f}")

# pick: largest cluster whose center XY is within 1.5m of midpoint
cand=[c for c in clusters if np.linalg.norm(above[c].mean(0)[:2]-mid[:2])<1.5 and len(c)>200]
car=above[cand[0]] if cand else above[clusters[0]]
print(f"CAR cluster: {len(car)} pts span=({(car.max(0)-car.min(0))[0]:.2f},{(car.max(0)-car.min(0))[1]:.2f},{(car.max(0)-car.min(0))[2]:.2f})m")
save_pcd('out_live/car_only.pcd', car.astype(np.float32))

# 4. oblique 3D render of the car cluster
def render3d(pts, fn, az=35, el=22, W=720, col_axis=2):
    a=np.radians(az); e=np.radians(el)
    Rz=np.array([[np.cos(a),-np.sin(a),0],[np.sin(a),np.cos(a),0],[0,0,1.]])
    Rx=np.array([[1,0,0],[0,np.cos(e),-np.sin(e)],[0,np.sin(e),np.cos(e)]])
    Q=(pts-pts.mean(0))@ (Rx@Rz).T
    u=Q[:,0]; v=Q[:,2]; depth=Q[:,1]
    col=pts[:,col_axis]
    order=np.argsort(depth)            # far first
    u,v,col=u[order],v[order],col[order]
    umn,umx=u.min(),u.max(); vmn,vmx=v.min(),v.max()
    sc=(W-40)/max(umx-umn,1e-9); H=int((vmx-vmn)*sc)+40
    iu=(20+(u-umn)*sc).astype(int); iv=(H-20-(v-vmn)*sc).astype(int)
    iu=np.clip(iu,0,W-1); iv=np.clip(iv,0,H-1)
    cn=np.clip((col-col.min())/(col.max()-col.min()+1e-9),0,1)
    img=np.zeros((H,W,3),np.uint8)
    R=(np.clip(1.5-abs(cn-1)*2,0,1)*255); G=(np.clip(1.5-abs(cn-.5)*2,0,1)*255); B=(np.clip(1.5-abs(cn-0)*2,0,1)*255)
    for x,y,r,g,b in zip(iu,iv,R,G,B):
        img[y,x]=[r,g,b]
        if y+1<H: img[y+1,x]=[r,g,b]   # 2px for visibility
    Image.fromarray(img).save(fn); print(f"  {fn} {H}x{W}")

render3d(car,'out_live/car_only_3d.png', az=35, el=20)
render3d(car,'out_live/car_only_3d_b.png', az=125, el=20)
# clean orthographic side (X-Z) and top (X-Y)
def ortho(pts,a0,a1,fn,W=600,flip=True,col=2):
    a=pts[:,a0];b=pts[:,a1];c=pts[:,col]
    amn,amx=a.min(),a.max();bmn,bmx=b.min(),b.max()
    sc=(W-20)/max(amx-amn,1e-9);H=int((bmx-bmn)*sc)+20
    ia=(10+(a-amn)*sc).astype(int);ib=(H-10-(b-bmn)*sc).astype(int) if flip else (10+(b-bmn)*sc).astype(int)
    ia=np.clip(ia,0,W-1);ib=np.clip(ib,0,H-1)
    cn=np.clip((c-c.min())/(c.max()-c.min()+1e-9),0,1)
    img=np.zeros((H,W,3),np.uint8)
    img[ib,ia,0]=np.clip(1.5-abs(cn-1)*2,0,1)*255
    img[ib,ia,1]=np.clip(1.5-abs(cn-.5)*2,0,1)*255
    img[ib,ia,2]=np.clip(1.5-abs(cn-0)*2,0,1)*255
    Image.fromarray(img).save(fn);print(f"  {fn} {H}x{W}")
ortho(car,0,2,'out_live/car_only_side.png')
ortho(car,0,1,'out_live/car_only_top.png')
