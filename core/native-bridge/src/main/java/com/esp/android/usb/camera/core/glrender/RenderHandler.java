package com.esp.android.usb.camera.core.glrender;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.Surface;
import com.esp.android.usb.camera.core.glrender.EGLBase;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.GregorianCalendar;
import java.util.Locale;

/* JADX INFO: loaded from: classes.dex */
public class RenderHandler extends Handler implements SurfaceTexture.OnFrameAvailableListener {
    private static boolean DEBUG = true;
    private static final int MSG_CREATE_SURFACE = 3;
    private static final int MSG_REQUEST_RENDER = 1;
    private static final int MSG_TERMINATE = 9;
    private static String TAG = "RenderHandler SDK";
    private static int cnt = 0;
    private static int mCaptureImageHeight = 1080;
    private static int mCaptureImageInterval = 0;
    private static int mCaptureImageWidth = 2048;
    private boolean mIsActive;
    private RenderThread mThread;

    public void setCaptureImageInterval(int i) {
        mCaptureImageInterval = 1;
    }

    public static final RenderHandler createHandler(SurfaceTexture surfaceTexture, int i, int i2, String str) {
        RenderThread renderThread = new RenderThread(surfaceTexture, i, i2, str);
        renderThread.start();
        return renderThread.getHandler();
    }

    public static final RenderHandler createHandler(Surface surface) {
        RenderThread renderThread = new RenderThread(surface);
        renderThread.start();
        return renderThread.getHandler();
    }

    private RenderHandler(RenderThread renderThread) {
        this.mIsActive = true;
        this.mThread = renderThread;
    }

    public final SurfaceTexture getPreviewTexture() {
        SurfaceTexture surfaceTexture;
        if (DEBUG) {
            Log.i(TAG, "getPreviewTexture:");
        }
        synchronized (this.mThread.mSync) {
            sendEmptyMessage(3);
            try {
                this.mThread.mSync.wait();
            } catch (InterruptedException unused) {
            }
            surfaceTexture = this.mThread.mPreviewSurface;
        }
        return surfaceTexture;
    }

    public final void release() {
        if (DEBUG) {
            Log.i(TAG, "release:");
        }
        if (this.mIsActive) {
            this.mIsActive = false;
            removeMessages(1);
            sendEmptyMessage(9);
        }
    }

    @Override // android.graphics.SurfaceTexture.OnFrameAvailableListener
    public final void onFrameAvailable(SurfaceTexture surfaceTexture) {
        if (this.mIsActive) {
            sendEmptyMessage(1);
        }
    }

    @Override // android.os.Handler
    public final void handleMessage(Message message) {
        if (this.mThread == null) {
            return;
        }
        int i = message.what;
        if (i == 1) {
            // onDrawFrame 链路声明 throws Throwable（saveCurrentFrame），但 Handler.handleMessage 不允许
            // 抛检查异常，这里就地兜住，行为与原 GL 渲染线程一致。
            try {
                this.mThread.onDrawFrame();
            } catch (Throwable t) {
                Log.e(RenderHandler.TAG, "onDrawFrame error:" + t.toString());
            }
            return;
        }
        if (i == 3) {
            this.mThread.updatePreviewSurface();
        } else if (i == 9) {
            Looper.myLooper().quit();
            this.mThread = null;
        } else {
            super.handleMessage(message);
        }
    }

    private static final class RenderThread extends Thread {
        private int mDisplayMode;
        private GLDrawer2D mDrawer;
        private EGLBase mEgl;
        private EGLBase.EglSurface mEglSurface;
        private RenderHandler mHandler;
        private String mLUTPath;
        private SurfaceTexture mPreviewSurface;
        private int mSrcFrameHeight;
        private int mSrcFrameWidth;
        private final float[] mStMatrix;
        private final Object mSurface;
        private final Object mSync;
        private int mTexId;

        public RenderThread(SurfaceTexture surfaceTexture, int i, int i2, String str) {
            this.mSync = new Object();
            this.mTexId = -1;
            this.mStMatrix = new float[16];
            this.mSurface = surfaceTexture;
            this.mSrcFrameWidth = i;
            this.mSrcFrameHeight = i2;
            this.mLUTPath = str;
            setName("RenderThread");
        }

        public RenderThread(Surface surface) {
            this.mSync = new Object();
            this.mTexId = -1;
            this.mStMatrix = new float[16];
            this.mSurface = surface;
            setName("RenderThread");
        }

        public final RenderHandler getHandler() {
            if (RenderHandler.DEBUG) {
                Log.i(RenderHandler.TAG, "RenderThread#getHandler:");
            }
            synchronized (this.mSync) {
                if (this.mHandler == null) {
                    try {
                        this.mSync.wait();
                    } catch (InterruptedException unused) {
                    }
                }
            }
            return this.mHandler;
        }

        public final void updatePreviewSurface() {
            if (RenderHandler.DEBUG) {
                Log.i(RenderHandler.TAG, "RenderThread#updatePreviewSurface:");
            }
            synchronized (this.mSync) {
                if (this.mPreviewSurface != null) {
                    if (RenderHandler.DEBUG) {
                        Log.i(RenderHandler.TAG, "release mPreviewSurface");
                    }
                    this.mPreviewSurface.setOnFrameAvailableListener(null);
                    this.mPreviewSurface.release();
                    this.mPreviewSurface = null;
                }
                this.mEglSurface.makeCurrent();
                int i = this.mTexId;
                if (i >= 0) {
                    GLDrawer2D.deleteTex(i);
                }
                this.mTexId = GLDrawer2D.initTex();
                if (RenderHandler.DEBUG) {
                    Log.i(RenderHandler.TAG, "getPreviewSurface:tex_id=" + this.mTexId);
                }
                SurfaceTexture surfaceTexture = new SurfaceTexture(this.mTexId);
                this.mPreviewSurface = surfaceTexture;
                surfaceTexture.setOnFrameAvailableListener(this.mHandler);
                this.mSync.notifyAll();
            }
        }

        public final void onDrawFrame() throws Throwable {
            this.mEglSurface.makeCurrent();
            this.mPreviewSurface.updateTexImage();
            this.mPreviewSurface.getTransformMatrix(this.mStMatrix);
            this.mDrawer.draw(this.mTexId, this.mStMatrix);
            if (RenderHandler.mCaptureImageInterval > 0) {
                int i = RenderHandler.cnt + 1;
                RenderHandler.cnt = i;
                if (i % RenderHandler.mCaptureImageInterval == 0) {
                    Log.i(RenderHandler.TAG, "cnt:" + RenderHandler.cnt);
                    saveCurrentFrame();
                }
            }
            this.mEglSurface.swap();
        }

        private static final String getDateTimeString() {
            return new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(new GregorianCalendar().getTime());
        }

        public void saveCurrentFrame() throws Throwable {
            ByteBuffer currentFrame = getCurrentFrame();
            BufferedOutputStream bufferedOutputStream2 = null;
            try {
                Log.i(RenderHandler.TAG, "saveCurrentFrame");
                File file = new File(Environment.getExternalStorageDirectory() + "/" + Environment.DIRECTORY_DCIM, "1_" + getDateTimeString() + ".png");
                BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(new FileOutputStream(file));
                bufferedOutputStream2 = bufferedOutputStream;
                Bitmap bitmapCreateBitmap = Bitmap.createBitmap(RenderHandler.mCaptureImageWidth, RenderHandler.mCaptureImageHeight, Bitmap.Config.ARGB_8888);
                currentFrame.rewind();
                bitmapCreateBitmap.copyPixelsFromBuffer(currentFrame);
                bitmapCreateBitmap.compress(Bitmap.CompressFormat.PNG, 100, bufferedOutputStream);
                bitmapCreateBitmap.recycle();
                Log.i(RenderHandler.TAG, "" + RenderHandler.cnt + ":" + RenderHandler.mCaptureImageWidth + "x" + RenderHandler.mCaptureImageHeight + " frame as '" + file.getAbsolutePath() + "'");
                bufferedOutputStream.close();
                bufferedOutputStream2 = null;
            } catch (Exception e) {
                Log.e(RenderHandler.TAG, "saveCurrentFrame error:" + e.toString());
                if (bufferedOutputStream2 != null) {
                    try {
                        bufferedOutputStream2.close();
                    } catch (Exception unused) {
                    }
                }
            }
        }

        public ByteBuffer getCurrentFrame() {
            ByteBuffer byteBufferAllocateDirect = ByteBuffer.allocateDirect(RenderHandler.mCaptureImageWidth * RenderHandler.mCaptureImageHeight * 4);
            byteBufferAllocateDirect.order(ByteOrder.LITTLE_ENDIAN);
            byteBufferAllocateDirect.rewind();
            GLES20.glReadPixels(0, 0, RenderHandler.mCaptureImageWidth, RenderHandler.mCaptureImageHeight, 6408, 5121, byteBufferAllocateDirect);
            return byteBufferAllocateDirect;
        }

        @Override // java.lang.Thread, java.lang.Runnable
        public final void run() {
            Log.d(RenderHandler.TAG, getName() + " started");
            init();
            Looper.prepare();
            synchronized (this.mSync) {
                this.mHandler = new RenderHandler(this);
                this.mSync.notify();
            }
            Looper.loop();
            Log.d(RenderHandler.TAG, getName() + " finishing");
            release();
            synchronized (this.mSync) {
                this.mHandler = null;
                this.mSync.notify();
            }
        }

        private final void init() {
            if (RenderHandler.DEBUG) {
                Log.i(RenderHandler.TAG, "RenderThread#init:");
            }
            EGLBase eGLBase = new EGLBase(null, false, false);
            this.mEgl = eGLBase;
            EGLBase.EglSurface eglSurfaceCreateFromSurface = eGLBase.createFromSurface(this.mSurface);
            this.mEglSurface = eglSurfaceCreateFromSurface;
            eglSurfaceCreateFromSurface.makeCurrent();
            this.mDrawer = new GLDrawer2D(this.mSrcFrameWidth, this.mSrcFrameHeight, this.mLUTPath);
        }

        private final void release() {
            if (RenderHandler.DEBUG) {
                Log.i(RenderHandler.TAG, "RenderThread#release:");
            }
            GLDrawer2D gLDrawer2D = this.mDrawer;
            if (gLDrawer2D != null) {
                gLDrawer2D.release();
                this.mDrawer = null;
            }
            SurfaceTexture surfaceTexture = this.mPreviewSurface;
            if (surfaceTexture != null) {
                surfaceTexture.release();
                this.mPreviewSurface = null;
            }
            int i = this.mTexId;
            if (i >= 0) {
                GLDrawer2D.deleteTex(i);
                this.mTexId = -1;
            }
            EGLBase.EglSurface eglSurface = this.mEglSurface;
            if (eglSurface != null) {
                eglSurface.release();
                this.mEglSurface = null;
            }
            EGLBase eGLBase = this.mEgl;
            if (eGLBase != null) {
                eGLBase.release();
                this.mEgl = null;
            }
        }
    }
}
