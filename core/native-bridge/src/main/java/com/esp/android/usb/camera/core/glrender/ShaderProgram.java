package com.esp.android.usb.camera.core.glrender;

import android.opengl.GLES20;
import android.util.Log;

/* JADX INFO: loaded from: classes.dex */
public class ShaderProgram {
    public static final String TAG = "ShaderProgram";
    private int shaderProgramHandle;

    public int getShaderHandle() {
        return this.shaderProgramHandle;
    }

    public ShaderProgram(String str, String str2) {
        this.shaderProgramHandle = createProgram(str, str2);
    }

    public void release() {
        GLES20.glDeleteProgram(this.shaderProgramHandle);
        this.shaderProgramHandle = -1;
    }

    private static void checkLocation(int i, String str) {
        if (i < 0) {
            throw new RuntimeException("Could not find location for " + str);
        }
    }

    public int getAttribute(String str) {
        int iGlGetAttribLocation = GLES20.glGetAttribLocation(this.shaderProgramHandle, str);
        checkLocation(iGlGetAttribLocation, str);
        return iGlGetAttribLocation;
    }

    public int getUniform(String str) {
        int iGlGetUniformLocation = GLES20.glGetUniformLocation(this.shaderProgramHandle, str);
        checkLocation(iGlGetUniformLocation, str);
        return iGlGetUniformLocation;
    }

    private static int createProgram(String str, String str2) {
        int iLoadShader = loadShader(35633, str);
        int iLoadShader2 = loadShader(35632, str2);
        int iGlCreateProgram = GLES20.glCreateProgram();
        GLHelpers.checkGlError("glCreateProgram");
        if (iGlCreateProgram == 0) {
            Log.e(TAG, "Could not create program");
            return 0;
        }
        GLES20.glAttachShader(iGlCreateProgram, iLoadShader);
        GLHelpers.checkGlError("glAttachShader");
        GLES20.glAttachShader(iGlCreateProgram, iLoadShader2);
        GLHelpers.checkGlError("glAttachShader");
        GLES20.glLinkProgram(iGlCreateProgram);
        int[] iArr = new int[1];
        GLES20.glGetProgramiv(iGlCreateProgram, 35714, iArr, 0);
        if (iArr[0] == 1) {
            return iGlCreateProgram;
        }
        String str3 = TAG;
        Log.e(str3, "Could not link program: ");
        Log.e(str3, GLES20.glGetProgramInfoLog(iGlCreateProgram));
        GLES20.glDeleteProgram(iGlCreateProgram);
        return 0;
    }

    private static int loadShader(int i, String str) {
        int iGlCreateShader = GLES20.glCreateShader(i);
        GLHelpers.checkGlError("glCreateShader type=" + i);
        GLES20.glShaderSource(iGlCreateShader, str);
        GLES20.glCompileShader(iGlCreateShader);
        int[] iArr = new int[1];
        GLES20.glGetShaderiv(iGlCreateShader, 35713, iArr, 0);
        if (iArr[0] != 0) {
            return iGlCreateShader;
        }
        String str2 = TAG;
        Log.e(str2, "Could not compile shader " + i + ":");
        Log.e(str2, " " + GLES20.glGetShaderInfoLog(iGlCreateShader));
        GLES20.glDeleteShader(iGlCreateShader);
        return 0;
    }
}
