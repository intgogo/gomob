package com.esp.android.usb.camera.core.glrender;

import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Environment;
import android.util.Log;
import androidx.core.view.MotionEventCompat;
import androidx.core.view.ViewCompat;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import kotlin.UByte;

/* JADX INFO: loaded from: classes.dex */
public class GLDrawer2D {
    private static final boolean DEBUG = true;
    public static final int DISPLAY_MODE_FISHEYE = 4;
    public static final int DISPLAY_MODE_LEFT_AT_MIDDLE = 2;
    public static final int DISPLAY_MODE_LR = 1;
    public static final int DISPLAY_MODE_RIGHT_AT_MIDDLE = 3;
    public static final int DISPLAY_MODE_TEST = -1;
    private static final int FLOAT_SZ = 4;
    private static final String TAG = "GLDrawer2D SDK";
    private static final int VERTEX_NUM = 4;
    private static final int VERTEX_SZ = 8;
    private static final int VERTICES_LEFT_FRONT = 1;
    private static final int VERTICES_LR = 0;
    private static final int VERTICES_RIGHT_FRONT = 2;
    private static final int VERTICES_TEST = 3;
    private static final String extern_fss = "#extension GL_OES_EGL_image_external : require\nprecision mediump float;\nuniform samplerExternalOES sTexture;\nvarying highp vec2 vTextureCoord;\nvarying highp vec4 vColor;\nvoid main() {\n gl_FragColor = texture2D(sTexture, vTextureCoord)*vColor;}";
    private static final String texture_fss = "#extension GL_OES_EGL_image_external : require\nprecision mediump float;\nuniform sampler2D sTexture;\nvarying highp vec2 vTextureCoord;\nvarying highp vec4 vColor;\nvoid main() {\n gl_FragColor = texture2D(sTexture, vTextureCoord)*vColor;}";
    private static final String vss = "uniform mat4 uMVPMatrix;\nuniform mat4 uTexMatrix;\nattribute highp vec4 aPosition;\nattribute highp vec4 aTextureCoord;\nattribute highp vec4 aColor;\nvarying highp vec2 vTextureCoord;\nvarying highp vec4 vColor;\nvoid main() {\n\n\tgl_Position = uMVPMatrix * aPosition;\n\tvTextureCoord = (uTexMatrix * aTextureCoord).xy;\n vColor = aColor;}\n";
    private int GAP_REDUCTION;
    private int GRID_H_NUM;
    private int GRID_W_NUM;
    private int VERTICES_DATA_STRIDE_BYTES;
    private String filePath;
    private ShaderProgram hExternProgram;
    private ShaderProgram hTextureProgram;
    private boolean mDisplayFlip;
    private int mDisplayMode;
    private boolean mDrawLogo;
    private int mEffectHeight;
    private int mEffectWidth;
    private boolean mFileExist;
    private Grids[] mGrids;
    private boolean mGridsDataExist;
    private int mLRGridCols;
    private int mLUTHeight;
    private int mLUTWidth;
    private float[] mMvpMatrix;
    private int mNonEffectHeight;
    private int mNonEffectWidth;
    private int mOutputHeight;
    private int mOutputWidth;
    private int mOverlapLR;
    private int mOverlapRL;
    private int mRLGridCols;
    private int mSrcHeight;
    private int mSrcWidth;
    private float[] mTexMatrix;
    private int mTextureLogoId;
    private final int mUserDataSize;
    private FloatBuffer pLogoColor;
    private FloatBuffer pLogoTexCoord;
    private FloatBuffer pLogoVertex;
    private float[] pLutFloat;
    private FloatBuffer pOriColor;
    private FloatBuffer pOriTexCoord;
    private FloatBuffer pOriVertex;
    private static final float[] VERTICES = {1.0f, 1.0f, -1.0f, 1.0f, 1.0f, -1.0f, -1.0f, -1.0f};
    private static final float[] TEXCOORD = {1.0f, 1.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f};
    private static final float[] COLOR = {1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f};
    private static float[] LOGO_VERTICES = {1.0f, -0.4f, -1.0f, -0.4f, 1.0f, -1.0f, -1.0f, -1.0f};
    private static float[] LOGO_TEXCOORD = {1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 0.0f, 1.0f};
    private static float[] LOGO_COLOR = {1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f};
    private static int mVerticesSize = 0;

    private void readData() {
    }

    public void setDisplayMode(int i) {
        this.mDisplayMode = i;
    }

    public void setDrawLogo(boolean z) {
        this.mDrawLogo = z;
    }

    public void setSrcSize(int i, int i2) {
        this.mSrcWidth = i;
        this.mSrcHeight = i2;
    }

    public GLDrawer2D(int i, int i2, String str) {
        String str2 = Environment.getExternalStorageDirectory() + "/VIN/eYsGlobeK.lut";
        this.VERTICES_DATA_STRIDE_BYTES = 32;
        this.mGrids = new Grids[5];
        this.mMvpMatrix = new float[16];
        this.mTexMatrix = new float[16];
        this.mUserDataSize = 1024;
        this.mDisplayFlip = false;
        this.mDisplayMode = 1;
        this.mDrawLogo = false;
        this.mGridsDataExist = false;
        this.mFileExist = false;
        this.GRID_W_NUM = 256;
        this.GRID_H_NUM = 128;
        this.mLRGridCols = 5;
        this.mRLGridCols = 5;
        this.GAP_REDUCTION = 0;
        this.mSrcWidth = i;
        this.mSrcHeight = i2;
        this.filePath = str;
        init();
    }

    public void setDisplayFlip(boolean z) {
        this.mDisplayFlip = z;
        if (z) {
            Matrix.setIdentityM(this.mMvpMatrix, 0);
            Matrix.rotateM(this.mMvpMatrix, 0, 180.0f, 0.0f, 0.0f, 1.0f);
            Matrix.rotateM(this.mMvpMatrix, 0, 180.0f, 0.0f, 1.0f, 0.0f);
            return;
        }
        Matrix.setIdentityM(this.mMvpMatrix, 0);
    }

    private void init() {
        Log.i(TAG, "GLDrawer2D start");
        if (!this.mGridsDataExist || !this.mFileExist) {
            this.mGridsDataExist = true;
            boolean zLoadLUT = loadLUT();
            this.mFileExist = zLoadLUT;
            if (zLoadLUT) {
                setDewarpStichVerticesData();
            }
            Log.i(TAG, "mGridsDataExist:setDewarpStichVerticesData(), mFileExist: " + this.mFileExist);
        } else {
            Log.i(TAG, "mGridsDataExist:" + this.mGridsDataExist + ", mFileExist: " + this.mFileExist);
        }
        setVerticesData();
        GLES20.glEnable(3042);
        GLES20.glBlendFunc(770, 771);
        this.hExternProgram = new ShaderProgram(vss, extern_fss);
        this.hTextureProgram = new ShaderProgram(vss, texture_fss);
        Log.i(TAG, "hExternProgram:" + this.hExternProgram.getShaderHandle() + " hTextureProgram :" + this.hTextureProgram.getShaderHandle());
        GLES20.glUseProgram(this.hExternProgram.getShaderHandle());
        Matrix.setIdentityM(this.mTexMatrix, 0);
        Matrix.setIdentityM(this.mMvpMatrix, 0);
        setDisplayFlip(this.mDisplayFlip);
        int i = this.mDisplayMode;
        if (i != 4 && this.mFileExist) {
            setglProgramByMode(i);
        } else {
            setglProgramByMode(4);
        }
        Log.i(TAG, "GLDrawer2D end");
    }

    private void setglProgramByMode(int i) {
        this.mDisplayMode = i;
        if (i == -1) {
            setglProgram(this.hExternProgram, this.mGrids[3].getFloatBufferPosition(), this.mGrids[3].getFloatBufferTexcoord(), this.mGrids[3].getFloatBufferColor());
            return;
        }
        if (i == 1) {
            setglProgram(this.hExternProgram, this.mGrids[0].getFloatBufferPosition(), this.mGrids[0].getFloatBufferTexcoord(), this.mGrids[0].getFloatBufferColor());
            return;
        }
        if (i == 2) {
            setglProgram(this.hExternProgram, this.mGrids[1].getFloatBufferPosition(), this.mGrids[1].getFloatBufferTexcoord(), this.mGrids[1].getFloatBufferColor());
            return;
        }
        if (i == 3) {
            setglProgram(this.hExternProgram, this.mGrids[2].getFloatBufferPosition(), this.mGrids[2].getFloatBufferTexcoord(), this.mGrids[2].getFloatBufferColor());
        } else if (i == 4) {
            setglProgram(this.hExternProgram, this.pOriVertex, this.pOriTexCoord, this.pOriColor);
        } else {
            setglProgram(this.hExternProgram, this.mGrids[0].getFloatBufferPosition(), this.mGrids[0].getFloatBufferTexcoord(), this.mGrids[0].getFloatBufferColor());
            this.mDisplayMode = i;
        }
    }

    private void setglProgram(ShaderProgram shaderProgram, FloatBuffer floatBuffer) {
        GLES20.glUniformMatrix4fv(shaderProgram.getUniform("uMVPMatrix"), 1, false, this.mMvpMatrix, 0);
        GLES20.glUniformMatrix4fv(shaderProgram.getUniform("uTexMatrix"), 1, false, this.mTexMatrix, 0);
        floatBuffer.position(0);
        GLES20.glVertexAttribPointer(shaderProgram.getAttribute("aPosition"), 2, 5126, false, this.VERTICES_DATA_STRIDE_BYTES, (Buffer) floatBuffer);
        checkGlError("glVertexAttribPointer maPositionLoc");
        GLES20.glEnableVertexAttribArray(shaderProgram.getAttribute("aPosition"));
        floatBuffer.position(3);
        GLES20.glVertexAttribPointer(shaderProgram.getAttribute("aTextureCoord"), 2, 5126, false, this.VERTICES_DATA_STRIDE_BYTES, (Buffer) floatBuffer);
        checkGlError("glVertexAttribPointer maTextureCoordLoc");
        GLES20.glEnableVertexAttribArray(shaderProgram.getAttribute("aTextureCoord"));
        floatBuffer.position(5);
        GLES20.glVertexAttribPointer(shaderProgram.getAttribute("aColor"), 4, 5126, false, this.VERTICES_DATA_STRIDE_BYTES, (Buffer) floatBuffer);
        checkGlError("glVertexAttribPointer maColor");
        GLES20.glEnableVertexAttribArray(shaderProgram.getAttribute("aColor"));
    }

    private void setglProgram(ShaderProgram shaderProgram, FloatBuffer floatBuffer, FloatBuffer floatBuffer2, FloatBuffer floatBuffer3) {
        GLES20.glUniformMatrix4fv(shaderProgram.getUniform("uMVPMatrix"), 1, false, this.mMvpMatrix, 0);
        GLES20.glUniformMatrix4fv(shaderProgram.getUniform("uTexMatrix"), 1, false, this.mTexMatrix, 0);
        GLES20.glVertexAttribPointer(shaderProgram.getAttribute("aPosition"), 2, 5126, false, 0, (Buffer) floatBuffer);
        checkGlError("glVertexAttribPointer maPositionLoc");
        GLES20.glVertexAttribPointer(shaderProgram.getAttribute("aTextureCoord"), 2, 5126, false, 0, (Buffer) floatBuffer2);
        checkGlError("glVertexAttribPointer maTextureCoordLoc");
        GLES20.glVertexAttribPointer(shaderProgram.getAttribute("aColor"), 4, 5126, false, 0, (Buffer) floatBuffer3);
        checkGlError("glVertexAttribPointer maColor");
        GLES20.glEnableVertexAttribArray(shaderProgram.getAttribute("aPosition"));
        GLES20.glEnableVertexAttribArray(shaderProgram.getAttribute("aTextureCoord"));
        GLES20.glEnableVertexAttribArray(shaderProgram.getAttribute("aColor"));
    }

    public void checkGlError(String str) {
        int iGlGetError = GLES20.glGetError();
        if (iGlGetError == 0) {
            return;
        }
        Log.e(TAG, str + ": glError " + iGlGetError);
        throw new RuntimeException(str + ": glError " + iGlGetError);
    }

    private boolean loadLUT() {
        try {
            Log.i(TAG, "Filepath=" + this.filePath);
            File file = new File(this.filePath);
            if (!file.exists()) {
                Log.i(TAG, "file.exists() false");
                this.mDisplayMode = 4;
                return false;
            }
            int length = ((int) file.length()) - 1024;
            byte[] bArr = new byte[length];
            byte[] bArr2 = new byte[1024];
            DataInputStream dataInputStream = new DataInputStream(new FileInputStream(file));
            int i = dataInputStream.read(bArr2);
            int i2 = dataInputStream.read(bArr);
            Log.i(TAG, "file.length()   " + file.length());
            Log.i(TAG, "lutData.length: " + length);
            Log.i(TAG, "readUserDataBytes " + i + " bytes , readlut " + i2 + " bytes ");
            ByteBuffer byteBufferAllocate = ByteBuffer.allocate(1024);
            byteBufferAllocate.put(bArr2);
            byteBufferAllocate.position(0);
            this.mLUTWidth = ((byteBufferAllocate.get(355) << 24) & ViewCompat.MEASURED_STATE_MASK) | ((byteBufferAllocate.get(354) << 16) & 16711680) | ((byteBufferAllocate.get(353) << 8) & MotionEventCompat.ACTION_POINTER_INDEX_MASK) | (byteBufferAllocate.get(352) & UByte.MAX_VALUE);
            Log.i(TAG, "GL texture para:mLUTWidth    :" + this.mLUTWidth);
            this.mLUTHeight = ((byteBufferAllocate.get(363) << 24) & ViewCompat.MEASURED_STATE_MASK) | ((byteBufferAllocate.get(362) << 16) & 16711680) | ((byteBufferAllocate.get(361) << 8) & MotionEventCompat.ACTION_POINTER_INDEX_MASK) | (byteBufferAllocate.get(360) & UByte.MAX_VALUE);
            Log.i(TAG, "GL texture para:mLUTHeight   :" + this.mLUTHeight);
            if (length != this.mLUTWidth * this.mLUTHeight * 4) {
                Log.e(TAG, "Error LutSize Confrontation: LUT file size = " + length + " Expected size = " + (this.mLUTWidth * this.mLUTHeight * 4) + " d= " + (length - ((this.mLUTWidth * this.mLUTHeight) * 4)));
                if (!file.delete()) {
                    return false;
                }
                Log.e(TAG, "Delete corrupted file : " + file);
                return false;
            }
            this.mEffectWidth = ((byteBufferAllocate.get(371) << 24) & ViewCompat.MEASURED_STATE_MASK) | ((byteBufferAllocate.get(370) << 16) & 16711680) | ((byteBufferAllocate.get(369) << 8) & MotionEventCompat.ACTION_POINTER_INDEX_MASK) | (byteBufferAllocate.get(368) & UByte.MAX_VALUE);
            Log.i(TAG, "GL texture para:mEffectWidth :" + this.mEffectWidth);
            this.mEffectHeight = ((byteBufferAllocate.get(379) << 24) & ViewCompat.MEASURED_STATE_MASK) | ((byteBufferAllocate.get(378) << 16) & 16711680) | ((byteBufferAllocate.get(377) << 8) & MotionEventCompat.ACTION_POINTER_INDEX_MASK) | (byteBufferAllocate.get(376) & UByte.MAX_VALUE);
            Log.i(TAG, "GL texture para:mEffectHeight:" + this.mEffectHeight);
            this.mOutputWidth = ((byteBufferAllocate.get(387) << 24) & ViewCompat.MEASURED_STATE_MASK) | ((byteBufferAllocate.get(386) << 16) & 16711680) | ((byteBufferAllocate.get(385) << 8) & MotionEventCompat.ACTION_POINTER_INDEX_MASK) | (byteBufferAllocate.get(384) & UByte.MAX_VALUE);
            Log.i(TAG, "GL texture para:mOutputWidth :" + this.mOutputWidth);
            this.mOutputHeight = ((byteBufferAllocate.get(395) << 24) & ViewCompat.MEASURED_STATE_MASK) | ((byteBufferAllocate.get(394) << 16) & 16711680) | ((byteBufferAllocate.get(393) << 8) & MotionEventCompat.ACTION_POINTER_INDEX_MASK) | (byteBufferAllocate.get(392) & UByte.MAX_VALUE);
            Log.i(TAG, "GL texture para:mOutputHeight:" + this.mOutputHeight);
            this.mOverlapLR = ((byteBufferAllocate.get(403) << 24) & ViewCompat.MEASURED_STATE_MASK) | ((byteBufferAllocate.get(402) << 16) & 16711680) | ((byteBufferAllocate.get(401) << 8) & MotionEventCompat.ACTION_POINTER_INDEX_MASK) | (byteBufferAllocate.get(400) & UByte.MAX_VALUE);
            Log.i(TAG, "GL texture para:mOverlapLR   :" + this.mOverlapLR);
            this.mOverlapRL = ((byteBufferAllocate.get(411) << 24) & ViewCompat.MEASURED_STATE_MASK) | ((byteBufferAllocate.get(410) << 16) & 16711680) | ((byteBufferAllocate.get(409) << 8) & MotionEventCompat.ACTION_POINTER_INDEX_MASK) | (byteBufferAllocate.get(408) & UByte.MAX_VALUE);
            Log.i(TAG, "GL texture para:mOverlapRL   :" + this.mOverlapRL);
            this.mNonEffectWidth = ((int) Math.ceil(((this.mLUTWidth - this.mEffectWidth) * 1.0f) / 4.0f)) + this.GAP_REDUCTION;
            Log.i(TAG, "GL texture para mNonEffectWidth:" + this.mNonEffectWidth);
            this.mNonEffectHeight = (int) Math.ceil(((this.mLUTHeight - this.mEffectHeight) * 1.0f) / 2.0f);
            Log.i(TAG, "GL texture para mNonEffectHeight:" + this.mNonEffectHeight);
            this.mEffectWidth = this.mLUTWidth - (this.mNonEffectWidth * 4);
            Log.i(TAG, "GL text ure para Final mEffectWidth:" + this.mEffectWidth);
            this.mEffectHeight = this.mLUTHeight - (this.mNonEffectHeight * 2);
            Log.i(TAG, "GL texture para Final mEffectHeight:" + this.mEffectHeight);
            this.mOutputWidth = (this.mEffectWidth - this.mOverlapLR) - this.mOverlapRL;
            Log.i(TAG, "GL texture para Final mOutputWidth:" + this.mOutputWidth);
            this.mOutputHeight = this.mEffectHeight;
            Log.i(TAG, "GL texture para Final mOutputHeight:" + this.mOutputHeight);
            this.mLRGridCols = (int) Math.ceil((this.mOverlapLR * 1.0f) / ((this.mEffectWidth * 1.0f) / this.GRID_W_NUM));
            Log.i(TAG, "GL texture para mLRGridCols:" + this.mLRGridCols);
            this.mRLGridCols = (int) Math.ceil((this.mOverlapRL * 1.0f) / ((this.mEffectWidth * 1.0f) / this.GRID_W_NUM));
            Log.i(TAG, "GL texture para mRLGridCols:" + this.mRLGridCols);
            if (this.mLRGridCols <= 0) {
                this.mLRGridCols = 1;
            }
            if (this.mRLGridCols <= 0) {
                this.mRLGridCols = 1;
            }
            this.pLutFloat = new float[this.mEffectWidth * this.mEffectHeight * 2];
            int i3 = 0;
            int i4 = 0;
            while (i3 < this.mLUTHeight) {
                int i5 = i4;
                int i6 = 0;
                while (true) {
                    int i7 = this.mLUTWidth;
                    if (i6 >= i7) {
                        break;
                    }
                    int i8 = this.mNonEffectWidth;
                    if (i6 >= i8 && ((i6 < (i7 / 2) - i8 || i6 >= (i7 / 2) + i8) && i6 < i7 - i8)) {
                        int i9 = ((bArr[(((i3 * i7) + i6) * 4) + 1] << 8) & MotionEventCompat.ACTION_POINTER_INDEX_MASK) | (bArr[((i3 * i7) + i6) * 4] & UByte.MAX_VALUE);
                        int i10 = (bArr[(((i7 * i3) + i6) * 4) + 2] & UByte.MAX_VALUE) | ((bArr[(((i3 * i7) + i6) * 4) + 3] << 8) & MotionEventCompat.ACTION_POINTER_INDEX_MASK);
                        float[] fArr = this.pLutFloat;
                        fArr[i5] = ((i9 * 1.0f) / 8.0f) / this.mSrcWidth;
                        fArr[i5 + 1] = ((i10 * 1.0f) / 8.0f) / this.mSrcHeight;
                        i5 += 2;
                    }
                    i6++;
                }
                i3++;
                i4 = i5;
            }
            Log.i(TAG, "Load LUT from file End:Counter:" + i4 + " pLutFloat.lenth" + this.pLutFloat.length + " ");
            return true;
        } catch (IOException e) {
            Log.i(TAG, "Load LUT from file Err");
            e.printStackTrace();
            return false;
        }
    }

    void setDewarpStichVerticesData() {
        this.mGrids[0] = new Grids();
        Grids grids = this.mGrids[0];
        float[] fArr = this.pLutFloat;
        int i = this.mEffectWidth;
        int i2 = this.mEffectHeight;
        grids.putGrids(new Grids(fArr, i, i2, this.GRID_W_NUM / 2, this.GRID_H_NUM, 0, 0, i / 2, i2, 0, 0, this.mOutputWidth, this.mOutputHeight, 0));
        Grids grids2 = this.mGrids[0];
        float[] fArr2 = this.pLutFloat;
        int i3 = this.mEffectWidth;
        int i4 = this.mEffectHeight;
        grids2.putGrids(new Grids(fArr2, i3, i4, this.GRID_W_NUM / 2, this.GRID_H_NUM, (i3 / 2) + this.mOverlapLR, 0, (i3 / 2) - this.mOverlapRL, i4, i3 / 2, 0, this.mOutputWidth, this.mOutputHeight, 0));
        Grids grids3 = this.mGrids[0];
        float[] fArr3 = this.pLutFloat;
        int i5 = this.mEffectWidth;
        int i6 = this.mEffectHeight;
        int i7 = this.mOverlapLR;
        grids3.putGrids(new Grids(fArr3, i5, i6, this.mLRGridCols, this.GRID_H_NUM, i5 / 2, 0, i7, i6, (i5 / 2) - i7, 0, this.mOutputWidth, this.mOutputHeight, 1));
        Grids grids4 = this.mGrids[0];
        float[] fArr4 = this.pLutFloat;
        int i8 = this.mEffectWidth;
        int i9 = this.mEffectHeight;
        int i10 = this.mRLGridCols;
        int i11 = this.GRID_H_NUM;
        int i12 = this.mOverlapRL;
        grids4.putGrids(new Grids(fArr4, i8, i9, i10, i11, i8 - i12, 0, i12, i9, 0, 0, this.mOutputWidth, this.mOutputHeight, 2));
        Grids grids5 = this.mGrids[0];
        float[] fArr5 = this.pLutFloat;
        int i13 = this.mEffectWidth;
        int i14 = this.mEffectHeight;
        int i15 = this.mRLGridCols;
        int i16 = this.GRID_H_NUM;
        int i17 = this.mOutputWidth;
        grids5.putGrids(new Grids(fArr5, i13, i14, i15, i16, 0, 0, 0, i14, i17 - (this.mOverlapRL / 2), 0, i17, this.mOutputHeight, 0));
    }

    void setVerticesData() {
        FloatBuffer floatBufferAsFloatBuffer = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer();
        this.pOriVertex = floatBufferAsFloatBuffer;
        floatBufferAsFloatBuffer.put(VERTICES);
        this.pOriVertex.flip();
        FloatBuffer floatBufferAsFloatBuffer2 = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer();
        this.pOriTexCoord = floatBufferAsFloatBuffer2;
        floatBufferAsFloatBuffer2.put(TEXCOORD);
        this.pOriTexCoord.flip();
        FloatBuffer floatBufferAsFloatBuffer3 = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer();
        this.pOriColor = floatBufferAsFloatBuffer3;
        floatBufferAsFloatBuffer3.put(COLOR);
        this.pOriColor.flip();
        FloatBuffer floatBufferAsFloatBuffer4 = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer();
        this.pLogoVertex = floatBufferAsFloatBuffer4;
        floatBufferAsFloatBuffer4.put(LOGO_VERTICES);
        this.pLogoVertex.flip();
        FloatBuffer floatBufferAsFloatBuffer5 = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer();
        this.pLogoTexCoord = floatBufferAsFloatBuffer5;
        floatBufferAsFloatBuffer5.put(LOGO_TEXCOORD);
        this.pLogoTexCoord.flip();
        FloatBuffer floatBufferAsFloatBuffer6 = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer();
        this.pLogoColor = floatBufferAsFloatBuffer6;
        floatBufferAsFloatBuffer6.put(LOGO_COLOR);
        this.pLogoColor.flip();
    }

    public void release() {
        this.hExternProgram.release();
    }

    public void draw(int i, float[] fArr) {
        int i2;
        GLES20.glUseProgram(this.hExternProgram.getShaderHandle());
        if (fArr != null) {
            GLES20.glUniformMatrix4fv(this.hExternProgram.getUniform("uTexMatrix"), 1, false, fArr, 0);
        }
        setDisplayFlip(this.mDisplayFlip);
        boolean z = this.mFileExist;
        if (z && (i2 = this.mDisplayMode) != 4) {
            setglProgramByMode(i2);
            GLES20.glBindTexture(36197, i);
            GLES20.glDrawArrays(4, 0, this.mGrids[0].getVerticeSize());
        } else if (z) {
            setglProgramByMode(4);
            GLES20.glBindTexture(36197, i);
            GLES20.glDrawArrays(5, 0, 4);
        }
        GLES20.glFinish();
    }

    public static int initTex() {
        Log.v(TAG, "initTex:");
        int[] iArr = new int[1];
        GLES20.glActiveTexture(33984);
        GLES20.glGenTextures(1, iArr, 0);
        GLES20.glBindTexture(36197, iArr[0]);
        GLES20.glTexParameteri(36197, 10242, 33071);
        GLES20.glTexParameteri(36197, 10243, 33071);
        GLES20.glTexParameteri(36197, 10241, 9729);
        GLES20.glTexParameteri(36197, 10240, 9729);
        return iArr[0];
    }

    public static void deleteTex(int i) {
        Log.v(TAG, "deleteTex:");
        GLES20.glDeleteTextures(1, new int[]{i}, 0);
    }
}
