package com.esp.android.usb.camera.core.glrender;

import android.opengl.Matrix;
import android.util.Log;

/* JADX INFO: loaded from: classes.dex */
class ArcBallMatrix {
    private float[] m_Matrix;
    private float m_fScale;
    private Vector3 m_vPan;

    float[] GetMatrix() {
        return this.m_Matrix;
    }

    void SetMatrix(float[] fArr) {
        this.m_Matrix = fArr;
    }

    void SetPan(Vector3 vector3) {
        this.m_vPan = vector3;
    }

    void SetScale(float f) {
        this.m_fScale = f;
    }

    ArcBallMatrix() {
        float[] fArr = {1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f};
        this.m_Matrix = fArr;
        Matrix.setIdentityM(fArr, 0);
        this.m_fScale = 1.0f;
        this.m_vPan = new Vector3();
    }

    void SetRotation(Vector4 vector4) {
        float fDot = Vector4.dot(vector4, vector4);
        float f = fDot > 0.0f ? 2.0f / fDot : 0.0f;
        float x = vector4.getX() * f;
        float y = vector4.getY() * f;
        float z = vector4.getZ() * f;
        float w = vector4.getW() * x;
        float w2 = vector4.getW() * y;
        float w3 = vector4.getW() * z;
        float x2 = vector4.getX() * x;
        float x3 = vector4.getX() * y;
        float x4 = vector4.getX() * z;
        float y2 = vector4.getY() * y;
        float y3 = vector4.getY() * z;
        float z2 = vector4.getZ() * z;
        float[] fArr = this.m_Matrix;
        fArr[0] = 1.0f - (y2 + z2);
        fArr[4] = x3 - w3;
        fArr[8] = x4 + w2;
        fArr[12] = 0.0f;
        fArr[1] = x3 + w3;
        fArr[5] = 1.0f - (z2 + x2);
        fArr[9] = y3 - w;
        fArr[13] = 0.0f;
        fArr[2] = x4 - w2;
        fArr[6] = y3 + w;
        fArr[10] = 1.0f - (x2 + y2);
        fArr[14] = 0.0f;
        fArr[3] = 0.0f;
        fArr[7] = 0.0f;
        fArr[11] = 0.0f;
        fArr[15] = 1.0f;
        float f2 = this.m_fScale;
        Matrix.scaleM(fArr, 0, f2, f2, f2);
        Matrix.translateM(this.m_Matrix, 0, this.m_vPan.getX(), this.m_vPan.getY(), 0.0f);
    }

    void Reset() {
        Log.e("esp_arcball", "Reset");
        Matrix.setIdentityM(this.m_Matrix, 0);
        this.m_fScale = 1.0f;
        this.m_vPan.setX(0.0f);
        this.m_vPan.setY(0.0f);
        this.m_vPan.setZ(0.0f);
    }
}
