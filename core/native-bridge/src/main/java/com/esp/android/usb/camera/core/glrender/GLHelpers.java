package com.esp.android.usb.camera.core.glrender;

import android.opengl.GLES20;
import android.util.Log;

/* JADX INFO: loaded from: classes.dex */
public class GLHelpers {
    private static final String TAG = "GLHelpers SDK";

    public static int generateExternalTexture() {
        int i = -1;
        int[] iArr = new int[1];
        try {
            GLES20.glGenTextures(1, iArr, 0);
            i = iArr[0];
            GLES20.glActiveTexture(33984);
            GLES20.glBindTexture(36197, i);
            GLES20.glTexParameterf(36197, 10241, 9729.0f);
            GLES20.glTexParameterf(36197, 10240, 9729.0f);
            GLES20.glTexParameteri(36197, 10242, 33071);
            GLES20.glTexParameteri(36197, 10243, 33071);
            return i;
        } catch (RuntimeException e) {
            Log.e(TAG, e.toString(), e);
            if (i != -1) {
                GLES20.glDeleteTextures(1, iArr, 0);
            }
            return -1;
        }
    }

    public static void checkGlError(String str) {
        int iGlGetError = GLES20.glGetError();
        if (iGlGetError == 0) {
            return;
        }
        String str2 = str + ": glError 0x" + Integer.toHexString(iGlGetError);
        Log.e(TAG, str2);
        throw new RuntimeException(str2);
    }
}
