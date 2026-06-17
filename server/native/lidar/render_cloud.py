import sys, numpy as np
from PIL import Image

def load_pcd(path):
    raw = open(path,'rb').read()
    off = raw.find(b'DATA binary\n')+len(b'DATA binary\n')
    # find fields/size from header
    pts = np.frombuffer(raw, dtype=np.float32, count=((len(raw)-off)//12)*3, offset=off).reshape(-1,3).astype(np.float64)
    return pts

def render(pts, ax0, ax1, color_ax, fn, W=1000, flip1=True):
    a = pts[:, ax0]; b = pts[:, ax1]; c = pts[:, color_ax]
    amin,amax = np.percentile(a,0.2), np.percentile(a,99.8)
    bmin,bmax = np.percentile(b,0.2), np.percentile(b,99.8)
    sa = (W-1)/(amax-amin+1e-9)
    H = int((bmax-bmin)*sa)+1; H=max(H,1)
    ia = np.clip(((a-amin)*sa),0,W-1).astype(int)
    ib = np.clip(((b-bmin)*sa),0,H-1).astype(int)
    if flip1: ib = H-1-ib
    cn = np.clip((c-np.percentile(c,2))/(np.percentile(c,98)-np.percentile(c,2)+1e-9),0,1)
    img = np.zeros((H,W,3),np.uint8)
    # height colormap: blue(low)->green->red(high)
    R=(np.clip(1.5-abs(cn-1)*2,0,1)*255).astype(np.uint8)
    G=(np.clip(1.5-abs(cn-0.5)*2,0,1)*255).astype(np.uint8)
    Bc=(np.clip(1.5-abs(cn-0)*2,0,1)*255).astype(np.uint8)
    img[ib,ia,0]=np.maximum(img[ib,ia,0],R)
    img[ib,ia,1]=np.maximum(img[ib,ia,1],G)
    img[ib,ia,2]=np.maximum(img[ib,ia,2],Bc)
    Image.fromarray(img).save(fn)
    print(f"  {fn}: {H}x{W}  axis{ax0} [{amin:.1f},{amax:.1f}]  axis{ax1} [{bmin:.1f},{bmax:.1f}]")

path = sys.argv[1]; prefix = sys.argv[2]
pts = load_pcd(path)
print(f"loaded {len(pts)} pts  bbox x[{pts[:,0].min():.1f},{pts[:,0].max():.1f}] y[{pts[:,1].min():.1f},{pts[:,1].max():.1f}] z[{pts[:,2].min():.1f},{pts[:,2].max():.1f}]")
render(pts, 0,1,2, f"{prefix}_top.png")    # top-down X-Y, color=Z
render(pts, 0,2,2, f"{prefix}_front.png")  # X-Z, color=Z
render(pts, 1,2,2, f"{prefix}_side.png")   # Y-Z, color=Z
