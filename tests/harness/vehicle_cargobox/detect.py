import numpy as np
from scipy.signal import find_peaks

def load_pcd(path):
    raw=open(path,"rb").read(); m=raw.index(b"DATA binary\n")+12
    hdr=raw[:m].decode("ascii","replace"); body=raw[m:]
    fields=sizes=None; npts=0
    for ln in hdr.splitlines():
        f=ln.split()
        if not f: continue
        if f[0]=="FIELDS": fields=f[1:]
        elif f[0]=="SIZE": sizes=[int(x) for x in f[1:]]
        elif f[0]=="POINTS": npts=int(f[1])
    step=0; off={}
    for i,fn in enumerate(fields):
        if fn in("x","y","z"): off[fn]=step
        step+=sizes[i]
    xs=np.frombuffer(body[:npts*step],np.uint8).reshape(npts,step)
    c=lambda o: xs[:,o:o+4].copy().view(np.float32).ravel()
    P=np.stack([c(off["x"]),c(off["y"]),c(off["z"])],1)
    return P[np.isfinite(P).all(1)]

def detect_cargobox(P, binw=20.0):
    l,w,z=P[:,1].copy(),P[:,0].copy(),P[:,2].copy()
    h,e=np.histogram(l,bins=int((l.max()-l.min())/10),range=(l.min(),l.max()))
    g=np.where(h>h.max()*0.05)[0]; llo,lhi=e[g[0]],e[g[-1]+1]
    m=(l>=llo)&(l<=lhi); l,w,z=l[m],w[m],z[m]
    ground=np.percentile(z,0.3); top=np.percentile(z,99.5); Hveh=top-ground
    nb=int((lhi-llo)/binw)+1; edg=np.linspace(llo,lhi,nb+1)
    idx=np.clip(((l-llo)/binw).astype(int),0,nb-1)
    maxZ=np.full(nb,ground)
    for b in range(nb):
        zz=z[idx==b]
        if len(zz)>20: maxZ[b]=zz.max()
    boxlike=maxZ>=top-0.06*Hveh
    bs,be,cs=-1,-1,None
    for b in range(nb+1):
        on=b<nb and boxlike[b]
        if on and cs is None: cs=b
        if (not on) and cs is not None:
            if be<0 or b-1-cs>be-bs: bs,be=cs,b-1
            cs=None
    if bs<0: return dict(has_box=False,llo=llo,lhi=lhi)
    bl0,bl1=edg[bs],edg[be+1]
    box=(l>=bl0)&(l<=bl1); bx,by,bz=w[box],l[box],z[box]
    rim=bz>top-0.07*Hveh
    outerW=float(np.percentile(bx[rim],98)-np.percentile(bx[rim],2)) if rim.sum()>20 else float(bx.max()-bx.min())
    outerL=float(bl1-bl0)
    znb=int((top-ground)/binw)+1; zedg=np.linspace(ground,top,znb+1)
    widthZ=np.zeros(znb)
    for b in range(znb):
        sb=(bz>=zedg[b])&(bz<zedg[b+1])
        if sb.sum()>30: widthZ[b]=np.percentile(bx[sb],98)-np.percentile(bx[sb],2)
    bed=ground; bestlen=0; b=0
    while b<znb:
        if widthZ[b]<=0: b+=1; continue
        ref=widthZ[b]; j=b
        while j<znb and widthZ[j]>0 and abs(widthZ[j]-ref)<0.10*ref: j+=1
        if j-b>bestlen: bestlen=j-b; bed=zedg[b]
        b=j if j>b else b+1
    boxH=float(top-bed)
    band=(bz>bed+0.1*boxH)&(bz<top-0.1*boxH)
    innerW=np.nan
    if band.sum()>50:
        hx,he=np.histogram(bx[band],bins=50); hc=(he[:-1]+he[1:])/2
        hs=np.convolve(hx,np.ones(3)/3,"same")
        pk,_=find_peaks(hs,height=hs.max()*0.3,distance=4)
        if len(pk)>=2:
            cx=(np.percentile(bx[band],2)+np.percentile(bx[band],98))/2
            px=hc[pk]; left=px[px<cx]; right=px[px>cx]
            if len(left) and len(right): innerW=float(right.min()-left.max())  # 最靠中心两壁=内腔面
            else: innerW=float(px.max()-px.min())
    return dict(has_box=True,llo=float(llo),lhi=float(lhi),bl0=float(bl0),bl1=float(bl1),
                outerL=outerL,outerW=outerW,innerW=innerW,top=float(top),bed=float(bed),
                ground=float(ground),boxH=boxH)

if __name__=="__main__":
    D="/root/WindowsR/JCHY_OFFLINE/Data/100742/"
    P=np.vstack([load_pcd(D+"1.pcd"),load_pcd(D+"2.pcd")])
    P=P[((P>=[270,0,10])&(P<=[1000,2200,800])).all(1)]
    r=detect_cargobox(P)
    iw=f"{r['innerW']:.0f}" if not np.isnan(r['innerW']) else "nan"
    print(f"车长[{r['llo']:.0f},{r['lhi']:.0f}]={r['lhi']-r['llo']:.0f}")
    print(f"货箱区 L∈[{r['bl0']:.0f},{r['bl1']:.0f}] 占车长{r['outerL']/(r['lhi']-r['llo'])*100:.0f}%")
    print(f"外长={r['outerL']:.0f} 外宽={r['outerW']:.0f} 箱顶={r['top']:.0f} bed={r['bed']:.0f} 箱高={r['boxH']:.0f} 内宽={iw}")
    print("EDA基准: rim外长1046/外宽506; 恒宽段z300-780→bed≈300 箱高≈460; 内宽≈444")
