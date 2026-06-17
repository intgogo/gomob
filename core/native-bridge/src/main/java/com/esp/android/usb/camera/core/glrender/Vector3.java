package com.esp.android.usb.camera.core.glrender;

/* JADX INFO: loaded from: classes.dex */
public class Vector3 {
    public float x;
    public float y;
    public float z;

    public float getX() {
        return this.x;
    }

    public float getY() {
        return this.y;
    }

    public float getZ() {
        return this.z;
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

    public Vector3() {
        this.x = 0.0f;
        this.y = 0.0f;
        this.z = 0.0f;
    }

    public Vector3(float f, float f2, float f3) {
        this.x = f;
        this.y = f2;
        this.z = f3;
    }

    public static float length2(Vector3 vector3) {
        float f = vector3.x;
        float f2 = vector3.y;
        float f3 = vector3.z;
        return (f * f) + (f2 * f2) + (f3 * f3);
    }

    public static float length(Vector3 vector3) {
        return (float) Math.sqrt(length2(vector3));
    }

    public static float dot(Vector3 vector3, Vector3 vector32) {
        return (vector3.x * vector32.x) + (vector3.y * vector32.y) + (vector3.z * vector32.z);
    }

    public static Vector3 cross(Vector3 vector3, Vector3 vector32) {
        float f = vector3.y;
        float f2 = vector32.z;
        float f3 = vector3.z;
        float f4 = vector32.y;
        float f5 = vector32.x;
        float f6 = vector3.x;
        return new Vector3((f * f2) - (f3 * f4), (f3 * f5) - (f2 * f6), (f6 * f4) - (f * f5));
    }

    public Vector3 div(float f) {
        return new Vector3(this.x / f, this.y / f, this.z / f);
    }
}
