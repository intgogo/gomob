package com.esp.android.usb.camera.core;

import android.util.Log;

/* JADX INFO: loaded from: classes.dex */
public class RectifyLogData {
    public short InImgHeight;
    public short InImgWidth;
    public short OutImgHeight;
    public short OutImgWidth;
    public float RECT_AvgErr;
    public int RECT_CropEnable;
    public short RECT_Crop_Col_BG_L;
    public short RECT_Crop_Col_ED_L;
    public short RECT_Crop_Row_BG;
    public short RECT_Crop_Row_ED;
    public int RECT_ScaleEnable;
    public short RECT_ScaleHeight;
    public short RECT_ScaleWidth;
    public byte RECT_Scale_Col_M;
    public byte RECT_Scale_Col_N;
    public byte RECT_Scale_Row_M;
    public byte RECT_Scale_Row_N;
    public short nLineBuffers;
    public byte[] uByteArray = new byte[1024];
    public float[] CamMat1 = new float[9];
    public float[] CamDist1 = new float[8];
    public float[] CamMat2 = new float[9];
    public float[] CamDist2 = new float[8];
    public float[] RotaMat = new float[9];
    public float[] TranMat = new float[3];
    public float[] LRotaMat = new float[9];
    public float[] RRotaMat = new float[9];
    public float[] NewCamMat1 = new float[12];
    public float[] NewCamMat2 = new float[12];
    public float[] ReProjectMat = new float[16];

    public String toString() {
        StringBuilder sb = new StringBuilder();
        Log.i("RectifyLogData", "toString()");
        sb.append(String.format("InImgWidth = %d\n", Short.valueOf(this.InImgWidth)));
        sb.append(String.format("InImgHeight = %d\n", Short.valueOf(this.InImgHeight)));
        sb.append(String.format("OutImgWidth = %d\n", Short.valueOf(this.OutImgWidth)));
        sb.append(String.format("OutImgHeight = %d\n", Short.valueOf(this.OutImgHeight)));
        sb.append(String.format("RECT_ScaleWidth = %d\n", Short.valueOf(this.RECT_ScaleWidth)));
        sb.append(String.format("RECT_ScaleHeight = %d\n", Short.valueOf(this.RECT_ScaleHeight)));
        sb.append(String.format("CamMat1 = ", new Object[0]));
        for (int i = 0; i < 9; i++) {
            sb.append(String.format("%.8f, ", Float.valueOf(this.CamMat1[i])));
        }
        sb.append(String.format("\n", new Object[0]));
        sb.append(String.format("CamDist1 = ", new Object[0]));
        for (int i2 = 0; i2 < 8; i2++) {
            sb.append(String.format("%.8f, ", Float.valueOf(this.CamDist1[i2])));
        }
        sb.append(String.format("\n", new Object[0]));
        sb.append(String.format("CamMat2 = ", new Object[0]));
        for (int i3 = 0; i3 < 9; i3++) {
            sb.append(String.format("%.8f, ", Float.valueOf(this.CamMat2[i3])));
        }
        sb.append(String.format("\n", new Object[0]));
        sb.append(String.format("CamDist2 = ", new Object[0]));
        for (int i4 = 0; i4 < 8; i4++) {
            sb.append(String.format("%.8f, ", Float.valueOf(this.CamDist2[i4])));
        }
        sb.append(String.format("\n", new Object[0]));
        sb.append(String.format("RotaMat = ", new Object[0]));
        for (int i5 = 0; i5 < 9; i5++) {
            sb.append(String.format("%.8f, ", Float.valueOf(this.RotaMat[i5])));
        }
        sb.append(String.format("\n", new Object[0]));
        sb.append(String.format("TranMat = ", new Object[0]));
        for (int i6 = 0; i6 < 3; i6++) {
            sb.append(String.format("%.8f, ", Float.valueOf(this.TranMat[i6])));
        }
        sb.append(String.format("\n", new Object[0]));
        sb.append(String.format("LRotaMat = ", new Object[0]));
        for (int i7 = 0; i7 < 9; i7++) {
            sb.append(String.format("%.8f, ", Float.valueOf(this.LRotaMat[i7])));
        }
        sb.append(String.format("\n", new Object[0]));
        sb.append(String.format("RRotaMat = ", new Object[0]));
        for (int i8 = 0; i8 < 9; i8++) {
            sb.append(String.format("%.8f, ", Float.valueOf(this.RRotaMat[i8])));
        }
        sb.append(String.format("\n", new Object[0]));
        sb.append(String.format("NewCamMat1 = ", new Object[0]));
        for (int i9 = 0; i9 < 12; i9++) {
            sb.append(String.format("%.8f, ", Float.valueOf(this.NewCamMat1[i9])));
        }
        sb.append(String.format("\n", new Object[0]));
        sb.append(String.format("NewCamMat2 = ", new Object[0]));
        for (int i10 = 0; i10 < 12; i10++) {
            sb.append(String.format("%.8f, ", Float.valueOf(this.NewCamMat2[i10])));
        }
        sb.append(String.format("\n", new Object[0]));
        sb.append(String.format("RECT_Crop_Row_BG = %d\n", Short.valueOf(this.RECT_Crop_Row_BG)));
        sb.append(String.format("RECT_Crop_Row_ED = %d\n", Short.valueOf(this.RECT_Crop_Row_ED)));
        sb.append(String.format("RECT_Crop_Col_BG_L = %d\n", Short.valueOf(this.RECT_Crop_Col_BG_L)));
        sb.append(String.format("RECT_Crop_Col_ED_L = %d\n", Short.valueOf(this.RECT_Crop_Col_ED_L)));
        sb.append(String.format("RECT_Scale_Col_M = %d\n", Byte.valueOf(this.RECT_Scale_Col_M)));
        sb.append(String.format("RECT_Scale_Col_N = %d\n", Byte.valueOf(this.RECT_Scale_Col_N)));
        sb.append(String.format("RECT_Scale_Row_M = %d\n", Byte.valueOf(this.RECT_Scale_Row_M)));
        sb.append(String.format("RECT_Scale_Row_N = %d\n", Byte.valueOf(this.RECT_Scale_Row_N)));
        sb.append(String.format("RECT_AvgErr = %.8f\n", Float.valueOf(this.RECT_AvgErr)));
        sb.append(String.format("nLineBuffers = %d\n", Short.valueOf(this.nLineBuffers)));
        if (this.ReProjectMat.length > 0) {
            sb.append(String.format("ReProjectMat = ", new Object[0]));
            for (int i11 = 0; i11 < 16; i11++) {
                sb.append(String.format("%.8f, ", Float.valueOf(this.ReProjectMat[i11])));
            }
            sb.append(String.format("\n", new Object[0]));
        }
        return sb.toString();
    }

    public float[] getNormalizationReProjectMat() {
        float[] fArr = this.ReProjectMat;
        int i = 0;
        float f = fArr[0];
        if (f != 1.0f && f != 0.0f) {
            fArr = new float[16];
            while (true) {
                float[] fArr2 = this.ReProjectMat;
                if (i >= fArr2.length) {
                    break;
                }
                fArr[i] = fArr2[i] / f;
                i++;
            }
        }
        return fArr;
    }
}
