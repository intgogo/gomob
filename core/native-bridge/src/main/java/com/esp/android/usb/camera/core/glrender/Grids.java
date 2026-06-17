package com.esp.android.usb.camera.core.glrender;

import android.util.Log;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/* JADX INFO: loaded from: classes.dex */
public class Grids {
    public static final int COLOR_STRIDE = 4;
    public static final int FLOAT_SIZE = 4;
    public static final int POSITION_STRIDE = 2;
    public static final int TEXTCOORD_STRIDE = 2;
    private boolean DEBUG;
    private String TAG;
    private FloatBuffer mVerticesColor;
    private FloatBuffer mVerticesPosition;
    private int mVerticesSize;
    private FloatBuffer mVerticesTextcoord;

    public FloatBuffer getFloatBufferColor() {
        return this.mVerticesColor;
    }

    public FloatBuffer getFloatBufferPosition() {
        return this.mVerticesPosition;
    }

    public FloatBuffer getFloatBufferTexcoord() {
        return this.mVerticesTextcoord;
    }

    public int getVerticeSize() {
        return this.mVerticesSize;
    }

    public int getVerticesStride() {
        return 32;
    }

    public Grids() {
        this.TAG = "Grids SDK";
        this.DEBUG = true;
        this.mVerticesPosition = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder()).asFloatBuffer();
        this.mVerticesTextcoord = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder()).asFloatBuffer();
        this.mVerticesColor = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder()).asFloatBuffer();
        this.mVerticesSize = 0;
    }

    public Grids(float[] fArr, int i, int i2, int i3, int i4, int i5, int i6, int i7, int i8, int i9, int i10, int i11, int i12, int i13) {
        Grids grids;
        int i14;
        int i15;
        float f;
        float f2;
        float f3;
        float f4;
        int i16 = i;
        int i17 = i3;
        int i18 = i4;
        int i19 = i5;
        int i20 = i6;
        int i21 = i7;
        int i22 = i8;
        this.TAG = "Grids SDK";
        this.DEBUG = true;
        int i23 = i17 * i18;
        this.mVerticesSize = i23 * 6;
        this.mVerticesPosition = ByteBuffer.allocateDirect(i23 * 48).order(ByteOrder.nativeOrder()).asFloatBuffer();
        this.mVerticesTextcoord = ByteBuffer.allocateDirect(this.mVerticesSize * 8).order(ByteOrder.nativeOrder()).asFloatBuffer();
        this.mVerticesColor = ByteBuffer.allocateDirect(this.mVerticesSize * 16).order(ByteOrder.nativeOrder()).asFloatBuffer();
        float f5 = i21;
        float f6 = i11;
        float f7 = (f5 * 2.0f) / f6;
        float f8 = i22;
        float f9 = i12;
        float f10 = ((i9 * 2.0f) / f6) - 1.0f;
        float f11 = ((i10 * 2.0f) / f9) - 1.0f;
        float f12 = i17;
        float f13 = f7 / f12;
        float f14 = i18;
        float f15 = ((f8 * 2.0f) / f9) / f14;
        float f16 = (f5 * 1.0f) / f12;
        float f17 = (f8 * 1.0f) / f14;
        int i24 = 0;
        while (i24 < i18) {
            int i25 = 0;
            while (i25 < i17) {
                float f18 = i25;
                float f19 = f10 + (f18 * f13);
                int i26 = i25 + 1;
                float f20 = f12;
                float f21 = i26;
                float f22 = f10 + (f21 * f13);
                float f23 = f10;
                float f24 = f13;
                float f25 = f11 + (i24 * f15);
                float f26 = f11 + ((i24 + 1) * f15);
                float f27 = f11;
                float f28 = i19;
                int iFloor = (int) Math.floor(f28 + (f16 * f18));
                int iFloor2 = (int) Math.floor(f28 + (f16 * f21));
                float f29 = i20;
                float f30 = f16;
                int iFloor3 = (int) Math.floor((i24 * f17) + f29);
                int iFloor4 = (int) Math.floor(f29 + ((i24 + 1) * f17));
                int i27 = i19 + i21;
                iFloor2 = iFloor2 >= i27 ? i27 - 1 : iFloor2;
                int i28 = i20 + i22;
                iFloor4 = iFloor4 >= i28 ? i28 - 1 : iFloor4;
                iFloor = iFloor >= i27 ? i27 - 1 : iFloor;
                iFloor3 = iFloor3 >= i28 ? i28 - 1 : iFloor3;
                iFloor = iFloor < 0 ? 0 : iFloor;
                iFloor2 = iFloor2 < 0 ? 0 : iFloor2;
                iFloor3 = iFloor3 < 0 ? 0 : iFloor3;
                iFloor4 = iFloor4 < 0 ? 0 : iFloor4;
                if (iFloor >= i16 || iFloor3 >= i2 || iFloor2 >= i16 || iFloor4 >= i2) {
                    iFloor = iFloor >= i16 ? i16 - 1 : iFloor;
                    iFloor3 = iFloor3 >= i2 ? i2 - 1 : iFloor3;
                    iFloor2 = iFloor2 >= i16 ? i16 - 1 : iFloor2;
                    iFloor4 = iFloor4 >= i2 ? i2 - 1 : iFloor4;
                    int i29 = ((iFloor4 * i16) + iFloor2) * 2;
                    float f31 = fArr[i29];
                    float f32 = fArr[i29 + 1];
                }
                if (iFloor >= i16 || iFloor2 >= i16 || iFloor4 >= i2 || iFloor3 >= i2) {
                    grids = this;
                    if (grids.DEBUG) {
                        Log.i(grids.TAG, "lut out edge:(" + i25 + "," + i24 + ")(" + iFloor + "," + iFloor3 + ")(" + iFloor2 + "," + iFloor4 + ")");
                    }
                    i14 = i13;
                    i15 = 1;
                } else {
                    i15 = 1;
                    grids = this;
                    i14 = i13;
                }
                if (i14 == i15) {
                    f = 1.0f;
                    f2 = ((f21 * 1.0f) / f20) * 1.0f;
                    f3 = ((f18 * 1.0f) / f20) * 1.0f;
                } else {
                    f = 1.0f;
                    f2 = 1.0f;
                    f3 = 1.0f;
                }
                if (i14 == 2) {
                    f4 = (f - ((f18 * f) / f20)) * f;
                    f2 = (f - ((f21 * f) / f20)) * f;
                } else {
                    f4 = f3;
                }
                grids.mVerticesPosition.put(f19);
                grids.mVerticesPosition.put(f25);
                int i30 = iFloor3 * i16;
                int i31 = (i30 + iFloor) * 2;
                grids.mVerticesTextcoord.put(fArr[i31]);
                grids.mVerticesTextcoord.put(fArr[i31 + 1]);
                grids.mVerticesColor.put(1.0f);
                grids.mVerticesColor.put(1.0f);
                grids.mVerticesColor.put(1.0f);
                float f33 = f4 * 1.0f;
                grids.mVerticesColor.put(f33);
                grids.mVerticesPosition.put(f22);
                grids.mVerticesPosition.put(f25);
                int i32 = (i30 + iFloor2) * 2;
                grids.mVerticesTextcoord.put(fArr[i32]);
                int i33 = i32 + 1;
                grids.mVerticesTextcoord.put(fArr[i33]);
                grids.mVerticesColor.put(1.0f);
                grids.mVerticesColor.put(1.0f);
                grids.mVerticesColor.put(1.0f);
                float f34 = f2 * 1.0f;
                grids.mVerticesColor.put(f34);
                grids.mVerticesPosition.put(f19);
                grids.mVerticesPosition.put(f26);
                int i34 = iFloor4 * i16;
                int i35 = (iFloor + i34) * 2;
                grids.mVerticesTextcoord.put(fArr[i35]);
                int i36 = i35 + 1;
                grids.mVerticesTextcoord.put(fArr[i36]);
                grids.mVerticesColor.put(1.0f);
                grids.mVerticesColor.put(1.0f);
                grids.mVerticesColor.put(1.0f);
                grids.mVerticesColor.put(f33);
                grids.mVerticesPosition.put(f19);
                grids.mVerticesPosition.put(f26);
                grids.mVerticesTextcoord.put(fArr[i35]);
                grids.mVerticesTextcoord.put(fArr[i36]);
                grids.mVerticesColor.put(1.0f);
                grids.mVerticesColor.put(1.0f);
                grids.mVerticesColor.put(1.0f);
                grids.mVerticesColor.put(f33);
                grids.mVerticesPosition.put(f22);
                grids.mVerticesPosition.put(f26);
                int i37 = (i34 + iFloor2) * 2;
                grids.mVerticesTextcoord.put(fArr[i37]);
                grids.mVerticesTextcoord.put(fArr[i37 + 1]);
                grids.mVerticesColor.put(1.0f);
                grids.mVerticesColor.put(1.0f);
                grids.mVerticesColor.put(1.0f);
                grids.mVerticesColor.put(f34);
                grids.mVerticesPosition.put(f22);
                grids.mVerticesPosition.put(f25);
                grids.mVerticesTextcoord.put(fArr[i32]);
                grids.mVerticesTextcoord.put(fArr[i33]);
                grids.mVerticesColor.put(1.0f);
                grids.mVerticesColor.put(1.0f);
                grids.mVerticesColor.put(1.0f);
                grids.mVerticesColor.put(f34);
                i16 = i;
                i17 = i3;
                i19 = i5;
                i20 = i6;
                i21 = i7;
                i22 = i8;
                f16 = f30;
                i25 = i26;
                f12 = f20;
                f10 = f23;
                f17 = f17;
                f13 = f24;
                f11 = f27;
            }
            i24++;
            i16 = i;
            i17 = i3;
            i18 = i4;
            i19 = i5;
            i20 = i6;
            i21 = i7;
            i22 = i8;
            f13 = f13;
        }
        this.mVerticesPosition.position(0);
        this.mVerticesTextcoord.position(0);
        this.mVerticesColor.position(0);
    }

    public int putGrids(Grids grids) {
        int verticeSize = this.mVerticesSize + grids.getVerticeSize();
        int i = verticeSize * 8;
        FloatBuffer floatBufferAsFloatBuffer = ByteBuffer.allocateDirect(i).order(ByteOrder.nativeOrder()).asFloatBuffer();
        floatBufferAsFloatBuffer.put(getFloatBufferPosition());
        floatBufferAsFloatBuffer.put(grids.getFloatBufferPosition());
        this.mVerticesPosition = floatBufferAsFloatBuffer;
        floatBufferAsFloatBuffer.position(0);
        FloatBuffer floatBufferAsFloatBuffer2 = ByteBuffer.allocateDirect(i).order(ByteOrder.nativeOrder()).asFloatBuffer();
        floatBufferAsFloatBuffer2.put(getFloatBufferTexcoord());
        floatBufferAsFloatBuffer2.put(grids.getFloatBufferTexcoord());
        this.mVerticesTextcoord = floatBufferAsFloatBuffer2;
        floatBufferAsFloatBuffer2.position(0);
        FloatBuffer floatBufferAsFloatBuffer3 = ByteBuffer.allocateDirect(verticeSize * 16).order(ByteOrder.nativeOrder()).asFloatBuffer();
        floatBufferAsFloatBuffer3.put(getFloatBufferColor());
        floatBufferAsFloatBuffer3.put(grids.getFloatBufferColor());
        this.mVerticesColor = floatBufferAsFloatBuffer3;
        floatBufferAsFloatBuffer3.position(0);
        this.mVerticesSize = verticeSize;
        return verticeSize;
    }

    private int max(int[] iArr) {
        int i = iArr[0];
        for (int i2 = 1; i2 < iArr.length; i2++) {
            int i3 = iArr[i2];
            if (i3 > i) {
                i = i3;
            }
        }
        return i;
    }
}
