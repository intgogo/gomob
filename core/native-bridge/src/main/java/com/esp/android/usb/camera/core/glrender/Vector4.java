package com.esp.android.usb.camera.core.glrender;

/* JADX INFO: loaded from: classes.dex */
public class Vector4 {
    private float w;
    private float x;
    private float y;
    private float z;

    public float getW() {
        return this.w;
    }

    public float getX() {
        return this.x;
    }

    public float getY() {
        return this.y;
    }

    public float getZ() {
        return this.z;
    }

    public void setW(float f) {
        this.w = f;
    }

    public void setX(float f) {
        this.x = f;
    }

    public void setY(float f) {
        this.y = f;
    }

    public void setZ(float f) {
        this.z = f;
    }

    public Vector4() {
        this.x = 0.0f;
        this.y = 0.0f;
        this.z = 0.0f;
        this.w = 0.0f;
    }

    public Vector4(float f, float f2, float f3, float f4) {
        this.x = f;
        this.y = f2;
        this.z = f3;
        this.w = f4;
    }

    public static float dot(Vector4 vector4, Vector4 vector42) {
        return (vector4.x * vector42.x) + (vector4.y * vector42.y) + (vector4.z * vector42.z) + (vector4.w * vector42.w);
    }
}
