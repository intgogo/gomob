package com.esp.android.usb.camera.core.glrender;

/* JADX INFO: loaded from: classes.dex */
public class Vector2 {
    private float x;
    private float y;

    public float getX() {
        return this.x;
    }

    public float getY() {
        return this.y;
    }

    public void setX(float f) {
        this.x = f;
    }

    public void setY(float f) {
        this.y = f;
    }

    public Vector2() {
        this.x = 0.0f;
        this.y = 0.0f;
    }

    public Vector2(float f, float f2) {
        this.x = f;
        this.y = f2;
    }

    public static float length2(Vector2 vector2) {
        float f = vector2.x;
        float f2 = vector2.y;
        return (f * f) + (f2 * f2);
    }

    public static float length(Vector2 vector2) {
        return (float) Math.sqrt(length2(vector2));
    }
}
