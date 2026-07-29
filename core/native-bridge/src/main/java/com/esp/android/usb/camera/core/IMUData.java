package com.esp.android.usb.camera.core;

/* JADX INFO: loaded from: classes.dex */
public class IMUData {
    public float mAccelX;
    public float mAccelY;
    public float mAccelZ;
    public byte mAccuracy_FLAG;
    public float mCompassX;
    public float mCompassX_TBC;
    public float mCompassY;
    public float mCompassY_TBC;
    public float mCompassZ;
    public float mCompassZ_TBC;
    public int mFrameCount;
    public float mGyroScopeX;
    public float mGyroScopeY;
    public float mGyroScopeZ;
    public int mHour;
    public int mMin;
    public float[] mQuaternion = new float[4];
    public int mSec;
    public int mSubSecond;
    public int mTemprature;

    public static class DataFormat {
        public static final int DMP_DATA_WITHOUT_OFFSET = 4;
        public static final int DMP_DATA_WITH_OFFSET = 5;
        public static final int OFFSET_DATA = 3;
        public static final int RAW_DATA_WITHOUT_OFFSET = 1;
        public static final int RAW_DATA_WITH_OFFSET = 2;
    }
}
