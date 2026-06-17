package com.esp.android.usb.camera.core.glrender;

import android.opengl.Matrix;

/* JADX INFO: loaded from: classes.dex */
public class ArcBall {
    float m_fEpsilon = 1.0E-5f;
    private Vector3 m_vClickVector = new Vector3();
    private Vector3 m_vDragVector = new Vector3();
    private float m_fWidth = 1.0f;
    private float m_fHeight = 1.0f;
    private int m_iWidthCanvas = 1;
    private int m_iHeightCanvas = 1;
    private ArcBallMatrix m_mLastTranf = new ArcBallMatrix();
    private ArcBallMatrix m_mThisTranf = new ArcBallMatrix();
    private Vector2 m_MouseStart = new Vector2();

    void OnMouseUp(Vector2 vector2) {
    }

    public void Resize(float f, float f2) {
        this.m_iWidthCanvas = (int) f;
        this.m_iHeightCanvas = (int) f2;
        this.m_fWidth = 1.0f / ((f - 1.0f) * 0.5f);
        this.m_fHeight = 1.0f / ((f2 - 1.0f) * 0.5f);
    }

    public float[] GetTransformation() {
        return this.m_mThisTranf.GetMatrix();
    }

    public void Reset() {
        this.m_mLastTranf.Reset();
        this.m_mThisTranf.Reset();
    }

    Vector3 mapToSphere(Vector2 vector2) {
        Vector3 vector3 = new Vector3();
        Vector2 vector22 = new Vector2(vector2.getX(), vector2.getY());
        vector22.setX((vector22.getX() * this.m_fWidth) - 1.0f);
        vector22.setY(1.0f - (vector22.getY() * this.m_fHeight));
        float fLength2 = Vector2.length2(vector22);
        if (fLength2 > 1.0f) {
            float fSqrt = (float) (1.0d / Math.sqrt(fLength2));
            vector3.setX(vector22.getX() * fSqrt);
            vector3.setY(vector22.getY() * fSqrt);
            vector3.setZ(0.0f);
        } else {
            vector3.setX(vector22.getX());
            vector3.setY(vector22.getY());
            vector3.setZ((float) Math.sqrt(1.0f - fLength2));
        }
        return vector3;
    }

    public void OnMouseDown(Vector2 vector2) {
        this.m_mLastTranf.SetMatrix((float[]) this.m_mThisTranf.GetMatrix().clone());
        this.m_vClickVector = mapToSphere(vector2);
        this.m_MouseStart = vector2;
    }

    public void OnMouseMove(Vector2 vector2, int i) {
        Vector3 vector3MapToSphere = mapToSphere(vector2);
        this.m_vDragVector = vector3MapToSphere;
        Vector3 vector3Cross = Vector3.cross(vector3MapToSphere, this.m_vClickVector);
        Vector4 vector4 = new Vector4();
        if (Vector3.length(vector3Cross) > this.m_fEpsilon) {
            vector4.setX(vector3Cross.getX());
            vector4.setY(vector3Cross.getY());
            vector4.setZ(vector3Cross.getZ());
            vector4.setW(Vector3.dot(this.m_vClickVector, this.m_vDragVector));
        }
        if (i != -1) {
            if (i == 2) {
                this.m_mThisTranf.SetPan(new Vector3());
                this.m_mThisTranf.SetScale(1.0f);
                this.m_mThisTranf.SetRotation(vector4);
                float[] fArr = new float[16];
                Matrix.multiplyMM(fArr, 0, this.m_mThisTranf.GetMatrix(), 0, this.m_mLastTranf.GetMatrix(), 0);
                this.m_mThisTranf.SetMatrix(fArr);
                return;
            }
            return;
        }
        double dSqrt = Math.sqrt((this.m_MouseStart.getX() * this.m_MouseStart.getX()) + (this.m_MouseStart.getY() * this.m_MouseStart.getY())) / Math.sqrt((vector2.getX() * vector2.getX()) + (vector2.getY() * vector2.getY()));
        Vector4 vector42 = new Vector4();
        this.m_mThisTranf.SetPan(new Vector3());
        this.m_mThisTranf.SetScale((float) dSqrt);
        this.m_mThisTranf.SetRotation(vector42);
        float[] fArr2 = new float[16];
        Matrix.multiplyMM(fArr2, 0, this.m_mThisTranf.GetMatrix(), 0, this.m_mLastTranf.GetMatrix(), 0);
        this.m_mThisTranf.SetMatrix(fArr2);
    }
}
