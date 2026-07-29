package com.esp.android.usb.camera.core.glrender;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/* JADX INFO: loaded from: classes.dex */
public class EGLBase {
    private static final boolean DEBUG = true;
    private static final int EGL_RECORDABLE_ANDROID = 12610;
    private static final String TAG = "EGLBase SDK";
    private EGLConfig mEglConfig = null;
    private EGLContext mEglContext = EGL14.EGL_NO_CONTEXT;
    private EGLDisplay mEglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext mDefaultContext = EGL14.EGL_NO_CONTEXT;

    public EGLContext getContext() {
        return this.mEglContext;
    }

    public EGLBase(EGLContext eGLContext, boolean z, boolean z2) {
        Log.i(TAG, "EGLBase:");
        init(eGLContext, z, z2);
    }

    public void release() {
        Log.i(TAG, "release:");
        if (this.mEglDisplay != EGL14.EGL_NO_DISPLAY) {
            destroyContext();
            EGL14.eglTerminate(this.mEglDisplay);
            EGL14.eglReleaseThread();
        }
        this.mEglDisplay = EGL14.EGL_NO_DISPLAY;
        this.mEglContext = EGL14.EGL_NO_CONTEXT;
    }

    public EglSurface createFromSurface(Object obj) {
        Log.i(TAG, "createFromSurface:");
        EglSurface eglSurface = new EglSurface(this, obj);
        eglSurface.makeCurrent();
        return eglSurface;
    }

    public EglSurface createOffscreen(int i, int i2) {
        Log.i(TAG, "createOffscreen:");
        EglSurface eglSurface = new EglSurface(this, i, i2);
        eglSurface.makeCurrent();
        return eglSurface;
    }

    private void init(EGLContext eGLContext, boolean z, boolean z2) {
        Log.i(TAG, "init:");
        if (this.mEglDisplay != EGL14.EGL_NO_DISPLAY) {
            throw new RuntimeException("EGL already set up");
        }
        EGLDisplay eGLDisplayEglGetDisplay = EGL14.eglGetDisplay(0);
        this.mEglDisplay = eGLDisplayEglGetDisplay;
        if (eGLDisplayEglGetDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new RuntimeException("eglGetDisplay failed");
        }
        int[] iArr = new int[2];
        if (!EGL14.eglInitialize(this.mEglDisplay, iArr, 0, iArr, 1)) {
            this.mEglDisplay = null;
            throw new RuntimeException("eglInitialize failed");
        }
        if (eGLContext == null) {
            eGLContext = EGL14.EGL_NO_CONTEXT;
        }
        if (this.mEglContext == EGL14.EGL_NO_CONTEXT) {
            EGLConfig config = getConfig(z, z2);
            this.mEglConfig = config;
            if (config == null) {
                throw new RuntimeException("chooseConfig failed");
            }
            this.mEglContext = createContext(eGLContext);
        }
        int[] iArr2 = new int[1];
        EGL14.eglQueryContext(this.mEglDisplay, this.mEglContext, 12440, iArr2, 0);
        Log.i(TAG, "EGLContext created, client version " + iArr2[0]);
        makeDefault();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean makeCurrent(EGLSurface eGLSurface) {
        if (this.mEglDisplay == null) {
            Log.i(TAG, "makeCurrent:eglDisplay not initialized");
        }
        if (eGLSurface == null || eGLSurface == EGL14.EGL_NO_SURFACE) {
            if (EGL14.eglGetError() == 12299) {
                Log.i(TAG, "makeCurrent:returned EGL_BAD_NATIVE_WINDOW.");
            }
            return false;
        }
        if (EGL14.eglMakeCurrent(this.mEglDisplay, eGLSurface, eGLSurface, this.mEglContext)) {
            return true;
        }
        Log.w(TAG, "eglMakeCurrent:" + EGL14.eglGetError());
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void makeDefault() {
        Log.i(TAG, "makeDefault:");
        if (EGL14.eglMakeCurrent(this.mEglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)) {
            return;
        }
        Log.w("TAG", "makeDefault" + EGL14.eglGetError());
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int swap(EGLSurface eGLSurface) {
        if (EGL14.eglSwapBuffers(this.mEglDisplay, eGLSurface)) {
            return 12288;
        }
        int iEglGetError = EGL14.eglGetError();
        Log.w(TAG, "swap:err=" + iEglGetError);
        return iEglGetError;
    }

    private EGLContext createContext(EGLContext eGLContext) {
        EGLContext eGLContextEglCreateContext = EGL14.eglCreateContext(this.mEglDisplay, this.mEglConfig, eGLContext, new int[]{12440, 2, 12344}, 0);
        checkEglError("eglCreateContext");
        return eGLContextEglCreateContext;
    }

    private void destroyContext() {
        Log.i(TAG, "destroyContext:");
        if (!EGL14.eglDestroyContext(this.mEglDisplay, this.mEglContext)) {
            Log.i("destroyContext", "display:" + this.mEglDisplay + " context: " + this.mEglContext);
            Log.i(TAG, "eglDestroyContex:" + EGL14.eglGetError());
        }
        this.mEglContext = EGL14.EGL_NO_CONTEXT;
        if (this.mDefaultContext != EGL14.EGL_NO_CONTEXT) {
            if (!EGL14.eglDestroyContext(this.mEglDisplay, this.mDefaultContext)) {
                Log.i("destroyContext", "display:" + this.mEglDisplay + " context: " + this.mDefaultContext);
                Log.i(TAG, "eglDestroyContex:" + EGL14.eglGetError());
            }
            this.mDefaultContext = EGL14.EGL_NO_CONTEXT;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public EGLSurface createWindowSurface(Object obj) {
        Log.i(TAG, "createWindowSurface:nativeWindow=" + obj);
        try {
            return EGL14.eglCreateWindowSurface(this.mEglDisplay, this.mEglConfig, obj, new int[]{12344}, 0);
        } catch (IllegalArgumentException e) {
            Log.i(TAG, "eglCreateWindowSurface", e);
            return null;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public EGLSurface createOffscreenSurface(int i, int i2) {
        Log.i(TAG, "createOffscreenSurface:");
        int[] iArr = {12375, i, 12374, i2, 12344};
        EGLSurface eGLSurfaceEglCreatePbufferSurface = null;
        try {
            eGLSurfaceEglCreatePbufferSurface = EGL14.eglCreatePbufferSurface(this.mEglDisplay, this.mEglConfig, iArr, 0);
            checkEglError("eglCreatePbufferSurface");
            if (eGLSurfaceEglCreatePbufferSurface == null) {
                throw new RuntimeException("surface was null");
            }
        } catch (IllegalArgumentException e) {
            Log.i(TAG, "createOffscreenSurface", e);
        } catch (RuntimeException e2) {
            Log.i(TAG, "createOffscreenSurface", e2);
        }
        return eGLSurfaceEglCreatePbufferSurface;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void destroyWindowSurface(EGLSurface eGLSurface) {
        Log.i(TAG, "destroySurface:");
        if (eGLSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(this.mEglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(this.mEglDisplay, eGLSurface);
        }
        EGLSurface eGLSurface2 = EGL14.EGL_NO_SURFACE;
        Log.i(TAG, "destroySurface:finished");
    }

    private void checkEglError(String str) {
        int iEglGetError = EGL14.eglGetError();
        if (iEglGetError == 12288) {
            return;
        }
        Log.i(TAG, "checkEglError:" + str + ": EGL error: 0x" + Integer.toHexString(iEglGetError));
        throw new RuntimeException(str + ": EGL error: 0x" + Integer.toHexString(iEglGetError));
    }

    private EGLConfig getConfig(boolean z, boolean z2) {
        int[] iArr = {12352, 4, 12324, 8, 12323, 8, 12322, 8, 12321, 8, 12344, 12344, 12344, 12344, 12344, 12344, 12344};
        int i = 10;
        if (z) {
            iArr[10] = 12325;
            iArr[11] = 16;
            i = 12;
        }
        if (z2) {
            int i2 = i + 1;
            iArr[i] = EGL_RECORDABLE_ANDROID;
            i += 2;
            iArr[i2] = 1;
        }
        for (int i3 = 16; i3 >= i; i3--) {
            iArr[i3] = 12344;
        }
        EGLConfig[] eGLConfigArr = new EGLConfig[1];
        if (EGL14.eglChooseConfig(this.mEglDisplay, iArr, 0, eGLConfigArr, 0, 1, new int[1], 0)) {
            return eGLConfigArr[0];
        }
        Log.w(TAG, "unable to find RGBA8888 /  EGLConfig");
        return null;
    }

    public static class EglSurface {
        private final EGLBase mEgl;
        private EGLSurface mEglSurface;

        EglSurface(EGLBase eGLBase, Object obj) {
            this.mEglSurface = EGL14.EGL_NO_SURFACE;
            Log.i(EGLBase.TAG, "EglSurface:");
            if (!(obj instanceof SurfaceView) && !(obj instanceof Surface) && !(obj instanceof SurfaceHolder) && !(obj instanceof SurfaceTexture)) {
                throw new IllegalArgumentException("unsupported surface");
            }
            this.mEgl = eGLBase;
            this.mEglSurface = eGLBase.createWindowSurface(obj);
            if (obj instanceof SurfaceTexture) {
                Log.i(EGLBase.TAG, "mEgl: SurfaceTexture " + eGLBase);
            }
            if (obj instanceof Surface) {
                Log.i(EGLBase.TAG, "mEgl: Surface " + eGLBase);
            }
        }

        EglSurface(EGLBase eGLBase, int i, int i2) {
            this.mEglSurface = EGL14.EGL_NO_SURFACE;
            Log.i(EGLBase.TAG, "EglSurface:");
            this.mEgl = eGLBase;
            this.mEglSurface = eGLBase.createOffscreenSurface(i, i2);
        }

        public void makeCurrent() {
            this.mEgl.makeCurrent(this.mEglSurface);
        }

        public void swap() {
            this.mEgl.swap(this.mEglSurface);
        }

        public EGLContext getContext() {
            return this.mEgl.getContext();
        }

        public void release() {
            Log.i(EGLBase.TAG, "EglSurface:release:");
            this.mEgl.makeDefault();
            this.mEgl.destroyWindowSurface(this.mEglSurface);
            this.mEglSurface = EGL14.EGL_NO_SURFACE;
        }
    }
}
