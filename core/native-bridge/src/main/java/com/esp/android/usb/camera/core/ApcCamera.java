package com.esp.android.usb.camera.core;

import android.graphics.SurfaceTexture;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;
import com.esp.android.usb.camera.core.USBMonitor;
import com.esp.android.usb.camera.core.glrender.RenderHandler;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/* JADX INFO: loaded from: classes.dex */
public final class ApcCamera extends UVCCamera {
    public static final int APC_KEEP_DATA_FAIL_CALIBRATIONLOG = -105;
    public static final int APC_KEEP_DATA_FAIL_FWTAG = -108;
    public static final int APC_KEEP_DATA_FAIL_ISP = -107;
    public static final int APC_KEEP_DATA_FAIL_LUT = -106;
    public static final int APC_KEEP_DATA_FAIL_RECTIFYTABLE = -103;
    public static final int APC_KEEP_DATA_FAIL_SENSORPOSITION = -102;
    public static final int APC_KEEP_DATA_FAIL_SERIALNUMBER = -101;
    public static final int APC_KEEP_DATA_FAIL_ZDTABLE = -104;
    public static final int AUTO_WHITE_BALANCE_OFF = 0;
    public static final int AUTO_WHITE_BALANCE_ON = 1;
    private static final boolean DEBUG = false;

    @Deprecated
    public static final short DEPTH_DATA_11_BITS = 4;
    public static final short DEPTH_DATA_11_BITS_COMBINED_RECTIFY = 13;

    @Deprecated
    public static final short DEPTH_DATA_11_BITS_RAW = 9;

    @Deprecated
    public static final short DEPTH_DATA_14_BITS = 2;

    @Deprecated
    public static final short DEPTH_DATA_14_BITS_RAW = 7;

    @Deprecated
    public static final short DEPTH_DATA_8_BITS = 1;

    @Deprecated
    public static final short DEPTH_DATA_8_BITS_RAW = 6;

    @Deprecated
    public static final short DEPTH_DATA_8_BITS_x80 = 3;

    @Deprecated
    public static final short DEPTH_DATA_8_BITS_x80_RAW = 8;

    @Deprecated
    public static final short DEPTH_DATA_OFF_RAW = 0;

    @Deprecated
    public static final short DEPTH_DATA_OFF_RECTIFY = 5;
    public static final int DEVICE_FIND_FAIL = -25;
    public static final int DEVICE_NOT_SUPPORT = -33;
    public static final int DO_DEPTH_FILTER = 0;
    public static final int EDGE_PRESERVING_FILTER = 2;
    public static final int EXPOSURE_MODE_AUTO_APERTURE = 8;
    public static final int EXPOSURE_MODE_MANUAL = 1;
    public static final int EYS_AppendFront_ERROR = -2489;
    private static final String EYS_DIR_NAME = "VIN";
    public static final int EYS_ERROR = -1;
    public static final int EYS_LoadLUT_ERROR = -2490;
    public static final int EYS_MapLUT_ERROR = -2488;
    public static final int EYS_OK = 1;
    public static final int EYS_ParaLUT_ERROR = -2487;
    public static final int EYS_UVCCAMERA_NOT_OPEN = -2;
    public static final int FG_Address_1Byte = 1;
    public static final int FG_Address_2Byte = 2;
    public static final int FG_Value_1Byte = 16;
    public static final int FG_Value_2Byte = 32;
    private static final int FIRMWARE_ADDRESS_IR_CURRENT_VALUE = 224;
    private static final int FIRMWARE_ADDRESS_IR_CURRENT_VALUE_8029 = 129;
    private static final int FIRMWARE_ADDRESS_IR_MAX_VALUE = 226;
    private static final int FIRMWARE_ADDRESS_IR_MAX_VALUE_8029 = 16;
    private static final int FIRMWARE_ADDRESS_IR_MIN_VALUE = 225;
    private static final int FIRMWARE_ADDRESS_IR_MODE = 227;
    private static final int FIRMWARE_ADDRESS_VIDEO_MODE = 240;
    private static final int FIRMWARE_INTERLEAVE_MODE = 237;
    private static final int FIRMWARE_INTERLEAVE_MODE_OFF = 0;
    private static final int FIRMWARE_INTERLEAVE_MODE_ON = 1;
    public static final int FLYING_POINT_FILTER = 5;
    public static final int HOLE_FILL = 3;
    private static final int IR_FW_MAX = 15;
    private static final int IR_FW_MIN = 0;
    public static final int LOW_LIGHT_COMPENSATION_OFF = 0;
    public static final int LOW_LIGHT_COMPENSATION_ON = 1;
    public static final int PRODUCT_ID_ROSIE4 = 518;
    public static final String PRODUCT_VERSION_EX8029 = "EX8029";
    public static final String PRODUCT_VERSION_EX8030 = "EX8030";
    public static final String PRODUCT_VERSION_EX8031 = "EX8031";
    public static final String PRODUCT_VERSION_EX8032 = "EX8032";
    public static final String PRODUCT_VERSION_EX8036 = "EX8036";
    public static final String PRODUCT_VERSION_EX8037 = "EX8037";
    public static final String PRODUCT_VERSION_EX8038 = "EX8038";
    public static final String PRODUCT_VERSION_EX8052 = "EX8052";
    public static final String PRODUCT_VERSION_EX8059 = "EX8059";
    public static final String PRODUCT_VERSION_MARY = "MARY";
    public static final String PRODUCT_VERSION_YX8053 = "YX8053";
    public static final String PRODUCT_VERSION_YX8059 = "YX8059";
    public static final String PRODUCT_VERSION_YX8062 = "YX8062";
    public static final String PRODUCT_VERSION_YX8071 = "HYPATIA";
    public static final int SENSOR_MODE_1 = 0;
    public static final int SENSOR_MODE_2 = 1;
    public static final int SENSOR_MODE_3 = 3;
    public static final int SENSOR_MODE_4 = 4;
    public static final int SENSOR_MODE_All = 2;
    public static final int SUBSAMPLE = 1;
    private static final String TAG = "ApcCamera";
    public static final int TEMPORAL_FILTER = 4;
    private static final int USERDATA_SECTION_0 = 0;
    private static final int USERDATA_SECTION_3 = 3;
    public static final int UVC_ERROR_ACCESS = -3;
    private boolean mHasSurface;
    private IMUData mIMUData;
    private long mNativePtrSwPostProc;
    private String mProductVersion;
    private RenderHandler mRenderHandler;
    private int mStreamInfoIndexColor;
    private int mStreamInfoIndexDepth;
    private int mUserDataIndex;

    public static class AutoFocusInfo {
        public static final int ADDRESS_AF_BYPASS = 61698;
        public static final int ADDRESS_YUV_EDGE_bypass = 61698;
        public static final int ADDRESS_YUV_PROC_BYPASS = 61698;
        public static final int LCAM_ADDRESS_AF_H_SIZE = 62594;
        public static final int LCAM_ADDRESS_AF_H_SKIP = 62596;
        public static final int LCAM_ADDRESS_AF_H_START = 62592;
        public static final int LCAM_ADDRESS_AF_REPORT6 = 62598;
        public static final int LCAM_ADDRESS_AF_REPORT7 = 62599;
        public static final int LCAM_ADDRESS_AF_REPORT8 = 62600;
        public static final int LCAM_ADDRESS_AF_THD = 62597;
        public static final int LCAM_ADDRESS_AF_V_SIZE = 62595;
        public static final int LCAM_ADDRESS_AF_V_SKIP = 62596;
        public static final int LCAM_ADDRESS_AF_V_START = 62593;
        public static final int RCAM_ADDRESS_AF_H_SIZE = 62658;
        public static final int RCAM_ADDRESS_AF_H_SKIP = 62660;
        public static final int RCAM_ADDRESS_AF_H_START = 62656;
        public static final int RCAM_ADDRESS_AF_REPORT6 = 62662;
        public static final int RCAM_ADDRESS_AF_REPORT7 = 62663;
        public static final int RCAM_ADDRESS_AF_REPORT8 = 62664;
        public static final int RCAM_ADDRESS_AF_THD = 62661;
        public static final int RCAM_ADDRESS_AF_V_SIZE = 62659;
        public static final int RCAM_ADDRESS_AF_V_SKIP = 62660;
        public static final int RCAM_ADDRESS_AF_V_START = 62657;
    }

    public static class VideoMode {
        public static final int COLOR_ONLY = 0;
        public static final int COLOR_ONLY_INTERLEAVE_MODE = 16;
        public static final int DEPTH_DATA_SCALE_DOWN_MODE_OFFSET = 32;
        public static final int OFF_RECTIFY = 5;
        public static final int OFF_RECTIFY_INTERLEAVE_MODE = 21;
        public static final int RAW_11_BITS = 9;
        public static final int RAW_11_BITS_INTERLEAVE_MODE = 25;
        public static final int RAW_14_BITS = 7;
        public static final int RAW_14_BITS_INTERLEAVE_MODE = 23;
        public static final int RAW_8_BITS = 6;
        public static final int RAW_8_BITS_INTERLEAVE_MODE = 22;
        public static final int RAW_8_BITS_x80 = 8;
        public static final int RAW_8_BITS_x80_INTERLEAVE_MODE = 24;
        public static final int RECTIFY_11_BITS = 4;
        public static final int RECTIFY_11_BITS_INTERLEAVE_MODE = 20;
        public static final int RECTIFY_14_BITS = 2;
        public static final int RECTIFY_14_BITS_INTERLEAVE_MODE = 18;
        public static final int RECTIFY_8_BITS = 1;
        public static final int RECTIFY_8_BITS_INTERLEAVE_MODE = 17;
        public static final int RECTIFY_8_BITS_x80 = 3;
        public static final int RECTIFY_8_BITS_x80_INTERLEAVE_MODE = 19;
        public static final int SCALE_DOWN2_11_BITS = 68;
        public static final int SCALE_DOWN2_11_BITS_RAW = 73;
        public static final int SCALE_DOWN2_14_BITS = 66;
        public static final int SCALE_DOWN2_14_BITS_RAW = 71;
        public static final int SCALE_DOWN2_8_BITS = 65;
        public static final int SCALE_DOWN2_8_BITS_RAW = 70;
        public static final int SCALE_DOWN2_ILM_11_BITS = 84;
        public static final int SCALE_DOWN2_ILM_11_BITS_RAW = 89;
        public static final int SCALE_DOWN2_ILM_14_BITS = 82;
        public static final int SCALE_DOWN2_ILM_14_BITS_RAW = 87;
        public static final int SCALE_DOWN2_ILM_8_BITS = 81;
        public static final int SCALE_DOWN2_ILM_8_BITS_RAW = 86;
        public static final int SCALE_DOWN_11_BITS = 36;
        public static final int SCALE_DOWN_11_BITS_RAW = 41;
        public static final int SCALE_DOWN_14_BITS = 34;
        public static final int SCALE_DOWN_14_BITS_RAW = 39;
        public static final int SCALE_DOWN_8_BITS = 33;
        public static final int SCALE_DOWN_8_BITS_RAW = 38;
        public static final int SCALE_DOWN_ILM_11_BITS = 52;
        public static final int SCALE_DOWN_ILM_11_BITS_RAW = 57;
        public static final int SCALE_DOWN_ILM_14_BITS = 50;
        public static final int SCALE_DOWN_ILM_14_BITS_RAW = 55;
        public static final int SCALE_DOWN_ILM_8_BITS = 49;
        public static final int SCALE_DOWN_ILM_8_BITS_RAW = 54;
    }

    private static final native int nativeAdjustFocalLength(long j, int i, int i2);

    private static final native int nativeAdjustFocalLengthP(long j, int i, int i2, int i3);

    private static final native int nativeCheckCipher(long j, String str);

    private static final native void nativeCloseIMU(long j);

    private static final native long nativeCreateSwPostProc(int i);

    private static final native int nativeDisableAE(long j);

    private static final native int nativeDisableAWB(long j);

    private static final native int nativeDoIMUCalibration(long j, IIMUCallback iIMUCallback);

    private static final native int nativeDoImagePostProcessing(long j, byte[] bArr, long j2, byte[] bArr2, long j3, int i);

    private static final native int nativeDoSwPostProc(long j, byte[] bArr, boolean z, byte[] bArr2, byte[] bArr3, int i, int i2);

    private static final native int nativeEnableAE(long j);

    private static final native int nativeEnableAWB(long j);

    private static final native int nativeEnableIMUDataOutput(long j, boolean z);

    private static final native int nativeFactoryReset(long j);

    private static final native int nativeGenerateLUTFile(long j, int i);

    private static final native int nativeGetAEStatus(long j);

    private static final native int nativeGetAWBStatus(long j);

    private static final native int nativeGetAutoWhiteBalance(long j);

    private static final native char[] nativeGetCurrentDistanceLimit(long j);

    private static final native int nativeGetCurrentFileIndex(long j);

    private static final native int nativeGetCurrentFrameRate(long j, double[] dArr, double[] dArr2, double[] dArr3, double[] dArr4, double[] dArr5, int i);

    private static final native int nativeGetCurrentWhiteBalance(long j);

    private static final native String nativeGetDepthFilterVersion(long j);

    private static final native int[] nativeGetDepthZOfROI(long j, byte[] bArr, int i, int i2, int i3, int i4);

    private static final native int nativeGetDeviceFocalLength(long j, int[] iArr, int[] iArr2, int[] iArr3, int[] iArr4);

    private static final native int nativeGetDeviceType(long j);

    private static final native char[] nativeGetDistanceLimitInZDTable(long j);

    private static final native int nativeGetFWRegister(long j, int i, int[] iArr);

    private static final native byte[] nativeGetFileData(long j, int i);

    private static final native int nativeGetFileIDInfo(long j, int[] iArr, int[] iArr2, int i);

    private static final native int nativeGetFlashFocalLength(long j, int i, int i2, int[] iArr, int[] iArr2, int[] iArr3, int[] iArr4, int[] iArr5);

    private static final native int nativeGetFocalLength(long j, float[] fArr, int i);

    private static final native int nativeGetFwVersion(long j, int[] iArr, int i, int[] iArr2);

    private static final native int nativeGetGPIOValue(long j, int i, int[] iArr);

    private static final native boolean nativeGetHWPostProcess(long j);

    private static final native int nativeGetHWRegister(long j, int i, int[] iArr);

    private static final native int nativeGetIMUDataFormat(long j);

    private static final native int nativeGetIMUDataOutputByte(long j, int i);

    private static final native void nativeGetIMUFWVersion(long j, int[] iArr, int[] iArr2);

    private static final native void nativeGetIMUModuleName(long j, int[] iArr, int[] iArr2);

    private static final native boolean nativeGetIsUSB3(long j);

    private static final native byte[] nativeGetLogData(long j, int i, int i2);

    private static final native int nativeGetPidVid(long j, int[] iArr, int[] iArr2);

    private static final native int nativeGetPrincipalPoint(long j, float[] fArr, int i);

    private static final native RectifyLogData nativeGetRectifyLogData(long j, int i);

    private static final native ByteBuffer nativeGetRectifyLogFromCalibrationLog(long j);

    private static final native byte[] nativeGetRectifyTable(long j, int i);

    private static final native int nativeGetSensorRegister(long j, int i, int i2, int[] iArr, int i3, int i4);

    private static final native int nativeGetSerialNumber(long j, int[] iArr, int i, int[] iArr2);

    private static final native int nativeGetStructLen(long j);

    private static final native int nativeGetSurfaceResolution(long j, int[] iArr, int[] iArr2);

    private static final native int nativeGetUnpAreaStartSec(long j);

    private static final native int nativeGetUserData(long j, int[] iArr, int i, int i2);

    private static final native short nativeGetVideoMode(long j);

    private static final native int[] nativeGetWhiteBalanceLimit(long j);

    private static final native byte[] nativeGetYOffset(long j, int i);

    private static final native int[] nativeGetZDTable(long j, int i, int i2);

    private static final native boolean nativeIsIMUEnabled(long j);

    private static final native boolean nativeIsProtectedFlash(long j);

    private static final native boolean nativeIsSupportOpenCL();

    protected static final native int nativeOnStartLivePly(long j, ILivePlyCallback iLivePlyCallback);

    protected static final native int nativeOnStopLivePly(long j);

    private static final native int nativeReadFlashData(long j, byte[] bArr, long j2, long[] jArr);

    private static final native IMUData nativeReadIMUData(long j);

    private static final native int nativeReadIMUDataByCallback(long j, IIMUCallback iIMUCallback, IMUData iMUData);

    private static final native int nativeReleaseSwPostProc(long j);

    private static final native int nativeResetIMU(long j);

    private static final native int nativeResetLogData(long j, int i);

    private static final native int nativeResetRectifyTable(long j, int i);

    private static final native int nativeResetYOffset(long j, int i);

    private static final native int nativeResetZDTable(long j, int i);

    private static final native int nativeSaveStaticPly(long j, String str);

    private static final native int nativeSaveStaticPlyWithFilter(long j, String str, boolean z);

    private static final native int nativeSet360VR(long j, boolean z);

    private static final native int nativeSetAutoWhiteBalance(long j, boolean z);

    private static final native int nativeSetCurrentWhiteBalance(long j, int i);

    private static final native int nativeSetDepthFilterByType(long j, int i, boolean z);

    private static final native int nativeSetDepthFilterEdgePreservingParams(long j, int i, float f, float f2);

    private static final native int nativeSetDepthFilterHoleFillingParams(long j, int i, boolean z);

    private static final native int nativeSetDepthFilterSubSampleParams(long j, int i, int i2);

    private static final native int nativeSetDepthFilterTemporalParams(long j, float f, int i);

    private static final native int nativeSetDepthFiltersEnable(long j, boolean z, boolean z2, boolean z3, boolean z4, boolean z5, boolean z6);

    private static final native int nativeSetDistanceFilters(long j, char c, char c2);

    private static final native int nativeSetExternalStoragePublicDirectory(long j, String str);

    private static final native int nativeSetFWRegister(long j, int i, int i2);

    private static final native int nativeSetFileData(long j, byte[] bArr, int i);

    private static final native int nativeSetFishTag(long j, String str, String str2, boolean z);

    private static final native int nativeSetFishTag_eYs3D(long j, String str, boolean z);

    private static final native int nativeSetGPIOValue(long j, int i, int i2);

    private static final native int nativeSetHWPostProcess(long j, boolean z);

    private static final native int nativeSetHWRegister(long j, int i, int i2);

    private static final native int nativeSetIMUDataFormat(long j, int i);

    private static final native int nativeSetInterleaveMode(long j, boolean z);

    private static final native int nativeSetLogData(long j, byte[] bArr, int i, int i2);

    private static final native int nativeSetMonitorFrameRate(long j, boolean z, int i);

    private static final native int nativeSetParaLUT(long j, int i);

    private static final native int nativeSetPidVid(long j, int i, int i2);

    private static final native int nativeSetRectifyTable(long j, byte[] bArr, int i);

    private static final native int nativeSetSensorRegister(long j, int i, int i2, int i3, int i4, int i5);

    private static final native int nativeSetSerialNumber(long j, int[] iArr, int i);

    private static final native int nativeSetUserData(long j, int[] iArr, int i, int i2);

    private static final native int nativeSetVideoMode(long j, short s);

    private static final native int nativeSetYOffset(long j, byte[] bArr, int i);

    private static final native int nativeSetZDTable(long j, byte[] bArr, int i, int i2);

    private static final native int nativeStartIMULogData(long j, String str);

    private static final native int nativeStartRecord(long j, int i, boolean z);

    private static final native int nativeStopIMULogData(long j);

    private static final native int nativeStopReadIMUData(long j);

    private static final native int nativeStopRecord(long j);

    private static final native int nativeWriteFlashData(long j, byte[] bArr, long j2, boolean z, boolean z2, boolean z3, boolean z4, boolean z5, boolean z6, boolean z7, boolean z8);

    private static final native int nativeWriteFlashDataASIC(long j, byte[] bArr, byte[] bArr2, long j2);

    public ApcCamera() {
        this.mIMUData = new IMUData();
        this.mStreamInfoIndexColor = -1;
        this.mStreamInfoIndexDepth = -1;
        this.mUserDataIndex = 0;
        try {
            File file = new File(Environment.getExternalStorageDirectory(), EYS_DIR_NAME);
            file.mkdirs();
            String str = file.toString() + "/";
            String str2 = TAG;
            Log.d(str2, "sdcard_dir path=" + file.toString());
            Log.d(str2, "sdcard_path path=" + str);
            nativeSetExternalStoragePublicDirectory(this.mNativePtr, str);
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera Create Exception:" + e.toString());
        }
    }

    public ApcCamera(String str) {
        this();
        nativeCheckCipher(this.mNativePtr, str);
    }

    public static String getSDKVerion() {
        return String.format("%s", "1.2.0.12");
    }

    public void setUsbControlBlock(USBMonitor.UsbControlBlock usbControlBlock) {
        for (int i = 0; i < this.mCtrlBlocks.size(); i++) {
            if (this.mCtrlBlocks.get(i) == usbControlBlock) {
                return;
            }
        }
        this.mCtrlBlocks.add(usbControlBlock);
    }

    public int open() {
        if (this.mCtrlBlocks.size() > 0) {
            return open(this.mCtrlBlocks.get(0));
        }
        return -1;
    }

    @Override // com.esp.android.usb.camera.core.UVCCamera
    public int open(USBMonitor.UsbControlBlock usbControlBlock) {
        boolean z = true;
        for (int i = 0; i < this.mCtrlBlocks.size(); i++) {
            try {
                if (this.mCtrlBlocks.get(i) == usbControlBlock) {
                    z = false;
                }
            } catch (Exception e) {
                Log.e(TAG, "UVCCamera open Exception:" + e.toString());
                return -1;
            }
        }
        if (z) {
            this.mCtrlBlocks.add(usbControlBlock);
        }
        int iNativeConnect = nativeConnect(this.mNativePtr, usbControlBlock.getVenderId(), usbControlBlock.getProductId(), usbControlBlock.getFileDescriptor(), usbControlBlock.getBusNum(), usbControlBlock.getDevNum(), getUSBFSName(usbControlBlock));
        if (iNativeConnect != 0) {
            Log.d(TAG, "error:open uvc camera, please check error code:" + iNativeConnect);
            this.mNativePtr = 0L;
            this.mCtrlBlocks.remove(usbControlBlock);
            return iNativeConnect;
        }
        if (this.mNativePtr != 0 && (usbControlBlock.getVenderId() != 7758 || usbControlBlock.getProductId() != 355)) {
            getSupportedSize();
            getProductVersion();
            generateStreamInfoList();
        }
        return 1;
    }

    @Override // com.esp.android.usb.camera.core.UVCCamera
    public void close() {
        try {
            stopPreview(0);
            stopPreview(1);
            stopReadIMUData();
            if (this.mNativePtr != 0) {
                nativeRelease(this.mNativePtr);
            }
            for (int i = 0; i < this.mCtrlBlocks.size(); i++) {
                this.mCtrlBlocks.get(i).close();
            }
            this.mCtrlBlocks.clear();
            this.mProcSupports = 0L;
            this.mControlSupports = 0L;
            this.mCurrentPreviewMode = -1;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera close Exception:" + e.toString());
        }
    }

    @Override // com.esp.android.usb.camera.core.UVCCamera
    public void setPreviewTexture(SurfaceTexture surfaceTexture, int i) {
        try {
            if (i == 2) {
                int surfaceWidth = getSurfaceWidth();
                int surfaceHeight = getSurfaceHeight();
                this.mHasSurface = true;
                surfaceTexture.setDefaultBufferSize(surfaceWidth, surfaceHeight);
                RenderHandler renderHandlerCreateHandler = RenderHandler.createHandler(surfaceTexture, this.mCurrentPreviewWidth, this.mCurrentPreviewHeight, Environment.getExternalStorageDirectory() + String.format("/VIN/eYsGlobeK%d.lut", Integer.valueOf(this.mUserDataIndex)));
                this.mRenderHandler = renderHandlerCreateHandler;
                nativeSetPreviewDisplay(this.mNativePtr, new Surface(renderHandlerCreateHandler.getPreviewTexture()), 0);
            } else {
                nativeSetPreviewDisplay(this.mNativePtr, new Surface(surfaceTexture), i);
            }
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera setPreviewTexture Exception:" + e.toString());
        }
    }

    @Override // com.esp.android.usb.camera.core.UVCCamera
    public void stopPreview(int i) {
        try {
            setFrameCallback(null, 0, i);
            Iterator<USBMonitor.UsbControlBlock> it = this.mCtrlBlocks.iterator();
            while (it.hasNext()) {
                if (!it.next().isIMU()) {
                    if (i == 2) {
                        RenderHandler renderHandler = this.mRenderHandler;
                        if (renderHandler != null) {
                            renderHandler.release();
                            this.mRenderHandler = null;
                        }
                        this.mHasSurface = false;
                        nativeStopPreview(this.mNativePtr, 0);
                        return;
                    }
                    nativeStopPreview(this.mNativePtr, i);
                    return;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera stopPreview Exception:" + e.toString());
        }
    }

    public int generateLUTFile() {
        int iNativeGenerateLUTFile = -1;
        try {
            if (this.mNativePtr != 0) {
                List<Size> supportedSizeList = getSupportedSizeList((!getIsUSB3() ? 1 : 0) > 0 ? 6 : 4, getSupportedSize());
                if (!supportedSizeList.isEmpty()) {
                    if (supportedSizeList.size() >= 1 && (iNativeGenerateLUTFile = nativeGenerateLUTFile(this.mNativePtr, 0)) < 0) {
                        return iNativeGenerateLUTFile;
                    }
                    if (supportedSizeList.size() == 2) {
                        iNativeGenerateLUTFile = nativeGenerateLUTFile(this.mNativePtr, 3);
                        if (iNativeGenerateLUTFile < 0) {
                            return iNativeGenerateLUTFile;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera generateLUTFile Exception:" + e.toString());
        }
        return iNativeGenerateLUTFile;
    }

    public int getDeviceType() {
        try {
            if (this.mNativePtr != 0) {
                return nativeGetDeviceType(this.mNativePtr);
            }
            Log.e(TAG, "No device");
            return -1;
        } catch (Exception e) {
            Log.e(TAG, "UVCCaemra getIsUSB3 Exception:" + e.toString());
            return -1;
        }
    }

    public boolean getIsUSB3() {
        try {
            if (this.mNativePtr != 0) {
                return nativeGetIsUSB3(this.mNativePtr);
            }
            Log.e(TAG, "No device");
            return false;
        } catch (Exception e) {
            Log.e(TAG, "UVCCaemra getIsUSB3 Exception:" + e.toString());
            return false;
        }
    }

    private void setParaLUT(int i, int i2) {
        List<Size> supportedSizeList = getSupportedSizeList((!getIsUSB3() ? 1 : 0) > 0 ? 6 : 4, getSupportedSize());
        if (supportedSizeList.isEmpty()) {
            return;
        }
        for (int i3 = 0; i3 < supportedSizeList.size(); i3++) {
            Size size = supportedSizeList.get(i3);
            if (i == size.width && i2 == size.height) {
                if (i3 == 0) {
                    this.mUserDataIndex = 0;
                }
                if (i3 == 1) {
                    this.mUserDataIndex = 3;
                }
                nativeSetParaLUT(this.mNativePtr, this.mUserDataIndex);
            }
        }
    }

    public StreamInfo[] getStreamInfoList(int i) {
        if (i == 1) {
            return this.mStreamInfoListColor;
        }
        if (i != 2) {
            return null;
        }
        return this.mStreamInfoListDepth;
    }

    public void setPreviewSize(StreamInfo streamInfo) {
        int i = streamInfo.width;
        int i2 = streamInfo.height;
        boolean z = streamInfo.bIsFormatMJPEG;
        setPreviewSize(i, i2, 1, 30, z ? 1 : 0, 1.0f, streamInfo.interfaceNumber == 2 ? 1 : 0);
    }

    public void setPreviewSize(StreamInfo streamInfo, int i) {
        int i2 = streamInfo.width;
        int i3 = streamInfo.height;
        boolean z = streamInfo.bIsFormatMJPEG;
        setPreviewSize(i2, i3, 1, i, z ? 1 : 0, 1.0f, streamInfo.interfaceNumber == 2 ? 1 : 0);
    }

    public int getIndexOfStreamInfo(int i, int i2, int i3, boolean z) {
        return getIndexOfStreamInfo(new StreamInfo(i, i2, i3, z));
    }

    public int getIndexOfStreamInfo(StreamInfo streamInfo) {
        int i = streamInfo.interfaceNumber;
        int i2 = 0;
        int i3 = -1;
        if (i == 1) {
            while (i2 < this.mStreamInfoListColor.length) {
                if (this.mStreamInfoListColor[i2].isEqual(streamInfo)) {
                    i3 = i2;
                }
                i2++;
            }
        } else if (i == 2) {
            while (i2 < this.mStreamInfoListDepth.length) {
                if (this.mStreamInfoListDepth[i2].isEqual(streamInfo)) {
                    i3 = i2;
                }
                i2++;
            }
        }
        return i3;
    }

    public byte[] getFileData(int i) {
        try {
            if (this.mNativePtr != 0) {
                return nativeGetFileData(this.mNativePtr, i);
            }
            return null;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera getFileDataValue Exception:" + e.toString());
            return null;
        }
    }

    public int setFileData(byte[] bArr, int i) {
        try {
            if (this.mNativePtr != 0) {
                return nativeSetFileData(this.mNativePtr, bArr, i);
            }
            return -1;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera setFileDataValue Exception:" + e.toString());
            return -1;
        }
    }

    public int getSensorRegisterValue(String[] strArr, int i, int i2, int i3, int i4) {
        int iNativeGetSensorRegister = -1;
        try {
            if (this.mNativePtr == 0) {
                return -1;
            }
            int[] iArr = new int[1];
            String str = TAG;
            Log.i(str, "UVCCamera getSensorRegisterValue nId:" + i);
            Log.i(str, "UVCCamera getSensorRegisterValue address:" + i2);
            Log.i(str, "UVCCamera getSensorRegisterValue flag:" + i3);
            iNativeGetSensorRegister = nativeGetSensorRegister(this.mNativePtr, i, i2, iArr, i3, i4);
            StringBuilder sb = new StringBuilder();
            Log.i(str, "UVCCamera getSensorRegisterValue:" + String.format("0x%X", Integer.valueOf(iArr[0])));
            sb.append(String.format("%X", Integer.valueOf(iArr[0])));
            strArr[0] = sb.toString();
            return iNativeGetSensorRegister;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera getSensorRegisterValue Exception:" + e.toString());
            return iNativeGetSensorRegister;
        }
    }

    public int setSensorRegisterValue(int i, int i2, int i3, int i4, int i5) {
        try {
            if (this.mNativePtr != 0) {
                return nativeSetSensorRegister(this.mNativePtr, i, i2, i3, i4, i5);
            }
            return -1;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera setSensorRegisterValue Exception:" + e.toString());
            return -1;
        }
    }

    public boolean isIRSupported() {
        try {
            if (this.mNativePtr != 0) {
                if (getPid() == 518) {
                    return true;
                }
                String productVersion = getProductVersion();
                if (!productVersion.equals(PRODUCT_VERSION_EX8029) && !productVersion.equals(PRODUCT_VERSION_EX8036) && !productVersion.equals(PRODUCT_VERSION_EX8037) && !productVersion.equals(PRODUCT_VERSION_EX8059) && !productVersion.equals(PRODUCT_VERSION_EX8052) && !productVersion.equals(PRODUCT_VERSION_YX8059) && !productVersion.equals(PRODUCT_VERSION_EX8038) && !productVersion.equals(PRODUCT_VERSION_YX8062) && !productVersion.equals(PRODUCT_VERSION_YX8071) && !productVersion.equals(PRODUCT_VERSION_MARY)) {
                    if (!productVersion.equals(PRODUCT_VERSION_YX8053)) {
                        return false;
                    }
                }
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera isIRSupported Exception:" + e.toString());
        }
        return false;
    }

    public int getFWRegisterValue(String[] strArr, int i) {
        int iNativeGetFWRegister = -1;
        try {
            if (this.mNativePtr == 0) {
                return -1;
            }
            int[] iArr = new int[1];
            String str = TAG;
            Log.i(str, "UVCCamera getFWRegisterValue address:" + i);
            iNativeGetFWRegister = nativeGetFWRegister(this.mNativePtr, i, iArr);
            StringBuilder sb = new StringBuilder();
            Log.i(str, "UVCCamera getFWRegisterValue:" + String.format("0x%X", Integer.valueOf(iArr[0])));
            sb.append(String.format("%X", Integer.valueOf(iArr[0])));
            strArr[0] = sb.toString();
            Log.i(str, "UVCCamera getFWRegisterValue value:" + strArr);
            return iNativeGetFWRegister;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera getFWRegisterValue Exception:" + e.toString());
            return iNativeGetFWRegister;
        }
    }

    public int getFWRegisterValue(int[] iArr, int i) {
        try {
            if (this.mNativePtr != 0) {
                return nativeGetFWRegister(this.mNativePtr, i, iArr);
            }
            return -1;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera getFWRegisterValue Exception:" + e.toString());
            return -1;
        }
    }

    public int SetFWRegisterValue(int i, int i2) {
        try {
            if (this.mNativePtr != 0) {
                return nativeSetFWRegister(this.mNativePtr, i, i2);
            }
            return -1;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera SetFWRegisterValue Exception:" + e.toString());
            return -1;
        }
    }

    public int getHWRegisterValue(String[] strArr, int i) {
        int iNativeGetHWRegister = -1;
        try {
            if (this.mNativePtr == 0) {
                return -1;
            }
            int[] iArr = new int[1];
            iNativeGetHWRegister = nativeGetHWRegister(this.mNativePtr, i, iArr);
            strArr[0] = String.format("%X", Integer.valueOf(iArr[0]));
            return iNativeGetHWRegister;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera getHWRegisterValue Exception : " + e.toString());
            return iNativeGetHWRegister;
        }
    }

    public int setHWRegisterValue(int i, int i2) {
        try {
            if (this.mNativePtr != 0) {
                return nativeSetHWRegister(this.mNativePtr, i, i2);
            }
            return -1;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera setHWRegisterValue Exception : " + e.toString());
            return -1;
        }
    }

    public String getFwVersionValue() {
        try {
            if (this.mNativePtr == 0) {
                return null;
            }
            int[] iArr = new int[256];
            int[] iArr2 = new int[1];
            if (nativeGetFwVersion(this.mNativePtr, iArr, 256, iArr2) != 0) {
                return null;
            }
            int i = iArr2[0];
            StringBuilder sb = new StringBuilder();
            for (int i2 = 0; i2 < i; i2++) {
                sb.append(Character.toString((char) iArr[i2]));
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera getFwVersionValue Exception:" + e.toString());
            return null;
        }
    }

    public String getProductVersion() {
        try {
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera getProductVersion Exception:" + e.toString());
        }
        if (!TextUtils.isEmpty(this.mProductVersion)) {
            return this.mProductVersion;
        }
        if (this.mNativePtr != 0) {
            String fwVersionValue = getFwVersionValue();
            this.mProductVersion = fwVersionValue;
            int iIndexOf = fwVersionValue.indexOf("-");
            if (iIndexOf != -1) {
                String strSubstring = fwVersionValue.substring(0, iIndexOf);
                this.mProductVersion = strSubstring;
                return strSubstring;
            }
            Log.e(TAG, "Product version string format incorrect!");
            return null;
        }
        return null;
    }

    public int setIRCurrentValue(int i) {
        try {
            if (isIRSupported()) {
                return getProductVersion().equals(PRODUCT_VERSION_EX8029) ? SetFWRegisterValue(FIRMWARE_ADDRESS_IR_CURRENT_VALUE_8029, i) : SetFWRegisterValue(FIRMWARE_ADDRESS_IR_CURRENT_VALUE, i);
            }
            return -1;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera SetCurrentIRValue() Exception:" + e.toString());
            return -1;
        }
    }

    public int getIRCurrentValue() {
        try {
            if (!isIRSupported()) {
                return -1;
            }
            int[] iArr = new int[1];
            if (getProductVersion().equals(PRODUCT_VERSION_EX8029)) {
                getFWRegisterValue(iArr, FIRMWARE_ADDRESS_IR_CURRENT_VALUE_8029);
            } else {
                getFWRegisterValue(iArr, FIRMWARE_ADDRESS_IR_CURRENT_VALUE);
            }
            return iArr[0];
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera SetCurrentIRValue() Exception:" + e.toString());
            return -1;
        }
    }

    public int getIRMinValue() {
        try {
            if (isIRSupported() && !getProductVersion().equals(PRODUCT_VERSION_EX8029)) {
                int[] iArr = new int[1];
                getFWRegisterValue(iArr, FIRMWARE_ADDRESS_IR_MIN_VALUE);
                return iArr[0];
            }
            return -1;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera SetCurrentIRValue() Exception:" + e.toString());
            return -1;
        }
    }

    public int getIRMaxValue() {
        try {
            if (isIRSupported() && !getProductVersion().equals(PRODUCT_VERSION_EX8029)) {
                int[] iArr = new int[1];
                getFWRegisterValue(iArr, FIRMWARE_ADDRESS_IR_MAX_VALUE);
                return iArr[0];
            }
            return -1;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera SetCurrentIRValue() Exception:" + e.toString());
            return -1;
        }
    }

    public int setIRMaxValue(int i) {
        try {
            if (isIRSupported() && (getProductVersion() == null || !PRODUCT_VERSION_EX8029.equals(getProductVersion()))) {
                return SetFWRegisterValue(FIRMWARE_ADDRESS_IR_MAX_VALUE, i);
            }
            return -1;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera setIRMaxValue() Exception:" + e.toString());
            return -1;
        }
    }

    public int getIRMode() {
        try {
            if (isIRSupported() && !getProductVersion().equals(PRODUCT_VERSION_EX8029)) {
                int[] iArr = new int[1];
                getFWRegisterValue(iArr, FIRMWARE_ADDRESS_IR_MODE);
                return iArr[0];
            }
            return -1;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera SetCurrentIRValue() Exception:" + e.toString());
            return -1;
        }
    }

    public int SetIRMode(int i) {
        try {
            if (isIRSupported() && !getProductVersion().equals(PRODUCT_VERSION_EX8029)) {
                return SetFWRegisterValue(FIRMWARE_ADDRESS_IR_MODE, i);
            }
            return -1;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera SetCurrentIRValue() Exception:" + e.toString());
            return -1;
        }
    }

    public int getPid() {
        try {
            if (this.mNativePtr == 0) {
                return -1;
            }
            int[] iArr = new int[1];
            if (nativeGetPidVid(this.mNativePtr, iArr, new int[1]) == 0) {
                return iArr[0];
            }
            return -1;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera getPidValue Value:" + e.toString());
            return -1;
        }
    }

    public String getPidValue() {
        try {
            if (this.mNativePtr == 0) {
                return null;
            }
            int[] iArr = new int[1];
            if (nativeGetPidVid(this.mNativePtr, iArr, new int[1]) != 0) {
                return null;
            }
            return String.format("%04X", Integer.valueOf(iArr[0]));
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera getPidValue Value:" + e.toString());
            return null;
        }
    }

    public String getVidValue() {
        try {
            if (this.mNativePtr == 0) {
                return null;
            }
            int[] iArr = new int[1];
            if (nativeGetPidVid(this.mNativePtr, new int[1], iArr) != 0) {
                return null;
            }
            return String.format("%04X", Integer.valueOf(iArr[0]));
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera getVidValue Exception:" + e.toString());
            return null;
        }
    }

    public int setPidVidValue(int i, int i2) {
        try {
            if (this.mNativePtr != 0) {
                return nativeSetPidVid(this.mNativePtr, i, i2);
            }
            return -1;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera setPidVidValue Exception:" + e.toString());
            return -1;
        }
    }

    public int enableSenorIF(boolean z) {
        int[] iArr = new int[1];
        nativeGetHWRegister(this.mNativePtr, 61619, iArr);
        Log.i(TAG, "UVCCamera getHWRegisterValue:" + String.format("0x%X", Integer.valueOf(iArr[0])));
        String.format("%X", Integer.valueOf(iArr[0]));
        if (z) {
            if (iArr[0] != 85) {
                setHWRegisterValue(61619, 85);
            }
        } else if (iArr[0] != 0) {
            setHWRegisterValue(61619, 68);
        }
        return 1;
    }

    public String getSerialNumberValue() {
        try {
            if (this.mNativePtr == 0) {
                return null;
            }
            int[] iArr = new int[512];
            int[] iArr2 = new int[1];
            if (nativeGetSerialNumber(this.mNativePtr, iArr, 512, iArr2) != 0) {
                return null;
            }
            int i = iArr2[0];
            StringBuilder sb = new StringBuilder();
            for (int i2 = 0; i2 < i / 2; i2++) {
                sb.append(Character.toString((char) iArr[i2]));
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera getSerialNumberValue Exception:" + e.toString());
            return null;
        }
    }

    public int setSerialNumberValue(String str) {
        try {
            if (this.mNativePtr == 0) {
                return -1;
            }
            byte[] bytes = str.getBytes("ASCII");
            int length = bytes.length;
            if (length <= 0) {
                Log.e(TAG, "UVCCamera setSerialNumberValue error: str num is 0");
                return -1;
            }
            if (length > 126) {
                Log.e(TAG, "UVCCamera setSerialNumberValue error: str num is over 126");
                return -1;
            }
            int i = length * 2;
            int[] iArr = new int[i];
            for (int i2 = 0; i2 < length; i2++) {
                int i3 = i2 * 2;
                iArr[i3] = bytes[i2];
                iArr[i3 + 1] = 0;
            }
            return nativeSetSerialNumber(this.mNativePtr, iArr, i);
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera setSerialNumberValue Exception:" + e.toString());
            return -1;
        }
    }

    public byte[] getYOffsetValue() {
        return getYOffsetValue(0);
    }

    public byte[] getYOffsetValue(int i) {
        try {
            if (this.mNativePtr != 0) {
                return nativeGetYOffset(this.mNativePtr, i);
            }
            return null;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera getYOffsetValue Exception:" + e.toString());
            return null;
        }
    }

    public int setYOffsetValue(byte[] bArr, int i) {
        try {
            if (this.mNativePtr != 0) {
                return nativeSetYOffset(this.mNativePtr, bArr, i);
            }
            return -1;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera setYOffsetValue Exception:" + e.toString());
            return -1;
        }
    }

    public int resetYOffsetValue(int i) {
        try {
            if (this.mNativePtr != 0) {
                return nativeResetYOffset(this.mNativePtr, i);
            }
            return -1;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera resetYOffsetValue Exception:" + e.toString());
            return -1;
        }
    }

    public byte[] getRectifyTableValue() {
        return getRectifyTableValue(0);
    }

    public byte[] getRectifyTableValue(int i) {
        try {
            if (this.mNativePtr != 0) {
                return nativeGetRectifyTable(this.mNativePtr, i);
            }
            return null;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera getRectifyTableValue Exception:" + e.toString());
            return null;
        }
    }

    public int setRectifyTableValue(byte[] bArr, int i) {
        try {
            if (this.mNativePtr != 0) {
                return nativeSetRectifyTable(this.mNativePtr, bArr, i);
            }
            return -1;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera setRectifyTableValue Exception:" + e.toString());
            return -1;
        }
    }

    public int resetRectifyTableValue(int i) {
        try {
            if (this.mNativePtr != 0) {
                return nativeResetRectifyTable(this.mNativePtr, i);
            }
            return -1;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera resetRectifyTableValue Exception:" + e.toString());
            return -1;
        }
    }

    public int[] getZDTableValue() {
        return getZDTableValue(0, 0);
    }

    public int[] getZDTableValue(int i) {
        return getZDTableValue(i, 0);
    }

    public int[] getZDTableValue(int i, int i2) {
        try {
            if (this.mNativePtr == 0) {
                return null;
            }
            int[] iArrNativeGetZDTable = nativeGetZDTable(this.mNativePtr, i, i2);
            Log.e(TAG, String.format("getZDTableValue type " + i2 + "getZDTable zdBuffer.length:" + iArrNativeGetZDTable.length + "index:" + i, new Object[0]));
            return iArrNativeGetZDTable;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera getZDTableValue Exception:" + e.toString());
            return null;
        }
    }

    public int setZDTableValue(byte[] bArr, int i, int i2) {
        try {
            if (this.mNativePtr != 0) {
                return nativeSetZDTable(this.mNativePtr, bArr, i, i2);
            }
            return -1;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera setRectifyTableValue Exception:" + e.toString());
            return -1;
        }
    }

    public int resetZDTableValue(int i) {
        try {
            if (this.mNativePtr != 0) {
                return nativeResetZDTable(this.mNativePtr, i);
            }
            return -1;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera resetZDTableValue Exception:" + e.toString());
            return -1;
        }
    }

    @Deprecated
    public short getDepthDataType() {
        try {
            if (this.mNativePtr != 0) {
                return nativeGetVideoMode(this.mNativePtr);
            }
            return (short) 0;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera getDepthDataType Exception:" + e.toString());
            return (short) 0;
        }
    }

    @Deprecated
    public int setDepthDataType(short s) {
        try {
            if (this.mNativePtr != 0) {
                return nativeSetVideoMode(this.mNativePtr, s);
            }
            return 0;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera setDepthDataType Exception:" + e.toString());
            return 0;
        }
    }

    public int getVideoMode() {
        try {
            if (this.mNativePtr != 0) {
                return nativeGetVideoMode(this.mNativePtr);
            }
            return 0;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera getVideoMode Exception:" + e.toString());
            return 0;
        }
    }

    public int setVideoMode(int i) {
        try {
            if (this.mNativePtr != 0) {
                return nativeSetVideoMode(this.mNativePtr, (short) i);
            }
            return 0;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera setVideoMode Exception:" + e.toString());
            return 0;
        }
    }

    public RectifyLogData getRectifyLogData(int i) {
        Log.i(TAG, "getRectifyLogData()");
        try {
            return this.mNativePtr != 0 ? nativeGetRectifyLogData(this.mNativePtr, i) : new RectifyLogData();
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera getRectifyLogData() Exception:" + e.toString());
            return null;
        }
    }

    public byte[] getLogDataValue(int i, int i2) {
        try {
            if (this.mNativePtr != 0) {
                return nativeGetLogData(this.mNativePtr, i, i2);
            }
            return null;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera getLogDataValue Exception:" + e.toString());
            return null;
        }
    }

    public int setLogDataValue(byte[] bArr, int i, int i2) {
        try {
            if (this.mNativePtr != 0) {
                return nativeSetLogData(this.mNativePtr, bArr, i, i2);
            }
            return -1;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera getLogDataValue Exception:" + e.toString());
            return -1;
        }
    }

    public int resetLogDataValue(int i) {
        try {
            if (this.mNativePtr != 0) {
                return nativeResetLogData(this.mNativePtr, i);
            }
            return -1;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera resetLogDataValue Exception:" + e.toString());
            return -1;
        }
    }

    public int factoryReset() {
        try {
            if (this.mNativePtr != 0) {
                return nativeFactoryReset(this.mNativePtr);
            }
            return -1;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera factoryReset Exception:" + e.toString());
            return -1;
        }
    }

    public int getStructLen() {
        try {
            if (this.mNativePtr != 0) {
                return nativeGetStructLen(this.mNativePtr);
            }
            return -1;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera getStructLen Exception:" + e.toString());
            return -1;
        }
    }

    public int getUnpAreaStartSec() {
        try {
            if (this.mNativePtr != 0) {
                return nativeGetUnpAreaStartSec(this.mNativePtr);
            }
            return -1;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera getUnpAreaStartSec Exception:" + e.toString());
            return -1;
        }
    }

    public boolean isProtectedFlash() {
        try {
            if (this.mNativePtr != 0) {
                return nativeIsProtectedFlash(this.mNativePtr);
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera isProtectedFlash Exception:" + e.toString());
            return false;
        }
    }

    public int checkCipher(String str) {
        try {
            if (this.mNativePtr != 0) {
                return nativeCheckCipher(this.mNativePtr, str);
            }
            return -1;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera checkCipher Exception:" + e.toString());
            return -1;
        }
    }

    public boolean getHWPostProcess() {
        try {
            if (this.mNativePtr != 0) {
                return nativeGetHWPostProcess(this.mNativePtr);
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera getHWPostProcess Exception:" + e.toString());
            return false;
        }
    }

    public int setHWPostProcess(boolean z) {
        try {
            if (this.mNativePtr != 0) {
                return nativeSetHWPostProcess(this.mNativePtr, z);
            }
            return 0;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera setHWPostProcess Exception:" + e.toString());
            return 0;
        }
    }

    public byte[] readFlashData() {
        try {
            if (this.mNativePtr != 0) {
                byte[] bArr = new byte[106496];
                if (nativeReadFlashData(this.mNativePtr, bArr, 106496L, new long[1]) == 0) {
                    return bArr;
                }
            } else {
                Log.e(TAG, "UVCCamera readFlashData mNativePtr ==0");
            }
            return null;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera readFlashData Exception:" + e.toString());
            return null;
        }
    }

    public int writeFlashData(byte[] bArr, boolean z, boolean z2, boolean z3, boolean z4, boolean z5, boolean z6, boolean z7, boolean z8) {
        try {
            if (this.mNativePtr != 0 && bArr.length >= 40960 && bArr.length <= 106496) {
                return nativeWriteFlashData(this.mNativePtr, Arrays.copyOf(bArr, bArr.length), 106496L, z, z2, z3, z4, z5, z6, z7, z8);
            }
            return -1;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera writeFlashData Exception:" + e.toString());
            return -1;
        }
    }

    public int writeFlashDataASIC(byte[] bArr, byte[] bArr2) {
        try {
            if (this.mNativePtr == 0) {
                return -1;
            }
            if (bArr.length != 106496) {
                Log.e(TAG, "Invalid bin file");
                return -1;
            }
            if (bArr2.length != 106496) {
                Log.e(TAG, "Invalid backup bin file");
                return -1;
            }
            return nativeWriteFlashDataASIC(this.mNativePtr, Arrays.copyOf(bArr, bArr.length), Arrays.copyOf(bArr2, bArr2.length), 106496);
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera writeFlashData Exception:" + e.toString());
            return -1;
        }
    }

    public boolean getAEStatusEnabled() {
        try {
            if (this.mNativePtr != 0) {
                return nativeGetAEStatus(this.mNativePtr) == 0;
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera getAEStatusEnabled Exception:" + e.toString());
            return false;
        }
    }

    public int setEnableAE() {
        try {
            if (this.mNativePtr != 0) {
                return nativeEnableAE(this.mNativePtr);
            }
            return -1;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera setEnableAE Exception:" + e.toString());
            return -1;
        }
    }

    public int setDisableAE() {
        try {
            if (this.mNativePtr != 0) {
                return nativeDisableAE(this.mNativePtr);
            }
            return -1;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera setDisableAE Exception:" + e.toString());
            return -1;
        }
    }

    public int getAutoWhiteBalance() {
        try {
            if (this.mNativePtr != 0) {
                return nativeGetAutoWhiteBalance(this.mNativePtr);
            }
            return -1;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera getAutoWhiteBalanceStatus Exception : " + e.toString());
            return -1;
        }
    }

    public int setAutoWhiteBalance(boolean z) {
        try {
            if (this.mNativePtr != 0) {
                return nativeSetAutoWhiteBalance(this.mNativePtr, z);
            }
            return -1;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera setAutoWhiteBalance : " + z + ", Exception : " + e.toString());
            return -1;
        }
    }

    public int[] getWhiteBalanceLimit() {
        try {
            if (this.mNativePtr != 0) {
                return nativeGetWhiteBalanceLimit(this.mNativePtr);
            }
            return null;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera getWhiteBalanceLimit Exception : " + e.toString());
            return null;
        }
    }

    public int getCurrentWhiteBalance() {
        try {
            if (this.mNativePtr != 0) {
                return nativeGetCurrentWhiteBalance(this.mNativePtr);
            }
            return -1;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera getCurrentWhiteBalance Exception : " + e.toString());
            return -1;
        }
    }

    public int setCurrentWhiteBalance(int i) {
        try {
            if (this.mNativePtr != 0) {
                return nativeSetCurrentWhiteBalance(this.mNativePtr, i);
            }
            return -1;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera setCurrentWhiteBalance Exception : " + e.toString());
            return -1;
        }
    }

    public int[] getPowerlineFrequencyLimit() {
        try {
            if (this.mNativePtr != 0) {
                return nativeUpdatePowerlineFrequencyLimit(this.mNativePtr);
            }
            return null;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera getPowerlineFrequencyLimit Exception : " + e.toString());
            return null;
        }
    }

    public int getCurrentPowerlineFrequency() {
        try {
            if (this.mNativePtr != 0) {
                return nativeGetPowerlineFrequency(this.mNativePtr);
            }
            return -1;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera getCurrentPowerlineFrequency Exception : " + e.toString());
            return -1;
        }
    }

    public int setCurrentPowerlineFrequency(int i) {
        try {
            if (this.mNativePtr != 0) {
                return nativeSetPowerlineFrequency(this.mNativePtr, i);
            }
            return -1;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera setCurrentPowerlineFrequency Exception : " + e.toString());
            return -1;
        }
    }

    public int getExposure() {
        int iNativeGetExposure = -1;
        try {
            if (this.mNativePtr == 0) {
                return -1;
            }
            iNativeGetExposure = nativeGetExposure(this.mNativePtr);
            Log.e(TAG, "[esp_ae] UVCCamera getExposure result " + iNativeGetExposure);
            return iNativeGetExposure;
        } catch (Exception e) {
            Log.e(TAG, "[esp_ae] UVCCamera getExposure Exception:" + e.toString());
            return iNativeGetExposure;
        }
    }

    public int setExposureMode(int i) {
        int iNativeSetExposureMode = -1;
        try {
            if (this.mNativePtr == 0) {
                return -1;
            }
            iNativeSetExposureMode = nativeSetExposureMode(this.mNativePtr, i);
            Log.e(TAG, "[esp_ae] UVCCamera setExposureMode result " + iNativeSetExposureMode);
            return iNativeSetExposureMode;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera setExposureMode Exception:" + e.toString());
            return iNativeSetExposureMode;
        }
    }

    public int getExposureMode() {
        int iNativeGetExposureMode = -1;
        try {
            if (this.mNativePtr == 0) {
                return -1;
            }
            iNativeGetExposureMode = nativeGetExposureMode(this.mNativePtr);
            Log.e(TAG, "[esp_ae] UVCCamera getExposureMode result " + iNativeGetExposureMode);
            return iNativeGetExposureMode;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera getExposureMode Exception:" + e.toString());
            return iNativeGetExposureMode;
        }
    }

    public int setExposureAbsoluteTime(int i) {
        int iNativeSetExposure = -1;
        try {
            if (this.mNativePtr == 0) {
                return -1;
            }
            iNativeSetExposure = nativeSetExposure(this.mNativePtr, i);
            Log.e(TAG, "[esp_ae] UVCCamera setExposureAbsoluteTime result " + iNativeSetExposure);
            return iNativeSetExposure;
        } catch (Exception e) {
            Log.e(TAG, "[esp_ae] UVCCamera setExposureAbsoluteTime Exception:" + e.toString());
            return iNativeSetExposure;
        }
    }

    public int getExposureAbsoluteTime() {
        int iNativeGetExposure = -25;
        try {
            if (this.mNativePtr == 0) {
                return -25;
            }
            iNativeGetExposure = nativeGetExposure(this.mNativePtr);
            Log.e(TAG, "[esp_ae] UVCCamera getExposureAbsoluteTime result " + iNativeGetExposure);
            return iNativeGetExposure;
        } catch (Exception e) {
            Log.e(TAG, "[esp_ae] UVCCamera getExposureAbsoluteTime Exception:" + e.toString());
            return iNativeGetExposure;
        }
    }

    public int getExposurePriority() {
        try {
            if (this.mNativePtr != 0) {
                return nativeGetExposurePriority(this.mNativePtr);
            }
            return -1;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera setExposurePriority Exception:" + e.toString());
            return -1;
        }
    }

    public int setExposurePriority(int i) {
        try {
            if (this.mNativePtr != 0) {
                return nativeSetExposurePriority(this.mNativePtr, i);
            }
            return -1;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera setExposurePriority Exception:" + e.toString());
            return -1;
        }
    }

    public int setFishTag_eYs3D(String str, boolean z) {
        try {
            return nativeSetFishTag_eYs3D(this.mNativePtr, str, z);
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera setFishTag_eYs3D Exception:" + e.toString());
            return -1;
        }
    }

    public int setFishTag(String str, boolean z) {
        int i = -1;
        try {
            File file = new File(str);
            if (str.lastIndexOf(".") == -1) {
                Log.e(TAG, "setFishTag File path error");
                return -1;
            }
            String str2 = str.substring(0, str.lastIndexOf(".")) + "_meta_data_temp.mp4";
            String str3 = TAG;
            Log.i(str3, "setFishTag output_file:" + str2);
            int iNativeSetFishTag = nativeSetFishTag(this.mNativePtr, str, str2, z);
            i = iNativeSetFishTag;
            if (iNativeSetFishTag != 1) {
                Log.e(str3, "setFishTag fail:");
                return iNativeSetFishTag;
            }
            File file2 = new File(str2);
            if (file2.length() >= file.length() && file2.length() >= 128) {
                Log.i(str3, "outputFile:" + file2 + " size:" + file2.length());
                if (file.exists() && file2.exists()) {
                    if (!file.delete()) {
                        Log.e(str3, "setFishTag delete input_file fail" + file);
                        return -1;
                    }
                    File file3 = new File(str);
                    if (file2.renameTo(file3)) {
                        return iNativeSetFishTag;
                    }
                    Log.e(str3, "setFishTag rename fail:" + file3);
                    return -1;
                }
                Log.e(str3, "setFishTag file doesn't exist: inputFile:" + file + " +outputFile:" + file2);
                return -1;
            }
            Log.e(str3, "setFishTag fail: file size error:" + file2.length());
            return -1;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera setFishTag Exception:" + e.toString());
            return i;
        }
    }

    public int getSurfaceWidth() {
        try {
            if (this.mNativePtr == 0) {
                return -1;
            }
            int[] iArr = new int[1];
            nativeGetSurfaceResolution(this.mNativePtr, iArr, new int[1]);
            return iArr[0];
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera getSurfaceWidth Exception:" + e.toString());
            return -1;
        }
    }

    public int getSurfaceHeight() {
        try {
            if (this.mNativePtr == 0) {
                return -1;
            }
            int[] iArr = new int[1];
            nativeGetSurfaceResolution(this.mNativePtr, new int[1], iArr);
            return iArr[0];
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera getSurfaceHeight Exception:" + e.toString());
            return -1;
        }
    }

    public CurrentFrameRate getCurrentFrameRate(int i) {
        try {
            if (this.mNativePtr == 0) {
                return null;
            }
            double[] dArr = new double[1];
            double[] dArr2 = new double[1];
            double[] dArr3 = new double[1];
            double[] dArr4 = new double[1];
            double[] dArr5 = new double[1];
            if (i == 2) {
                nativeGetCurrentFrameRate(this.mNativePtr, dArr, dArr2, dArr3, dArr4, dArr5, 0);
            } else {
                nativeGetCurrentFrameRate(this.mNativePtr, dArr, dArr2, dArr3, dArr4, dArr5, i);
            }
            CurrentFrameRate currentFrameRate = new CurrentFrameRate();
            currentFrameRate.mFrameRateUvc = dArr[0];
            currentFrameRate.mFrameRatePreview = dArr2[0];
            currentFrameRate.mProcessedTime1 = dArr3[0];
            currentFrameRate.mProcessedTime2 = dArr4[0];
            currentFrameRate.mProcessedTime3 = dArr5[0];
            return currentFrameRate;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera getCurrentFrameRate Exception:" + e.toString());
            return null;
        }
    }

    public void setMonitorFrameRate(boolean z, int i) {
        try {
            if (this.mNativePtr != 0) {
                if (i == 2) {
                    nativeSetMonitorFrameRate(this.mNativePtr, z, 0);
                } else {
                    nativeSetMonitorFrameRate(this.mNativePtr, z, i);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera setMonitorFrameRate Exception:" + e.toString());
        }
    }

    public int getFileIDHeader(int i) {
        try {
            if (this.mNativePtr == 0) {
                return -1;
            }
            int[] iArr = new int[1];
            int[] iArr2 = new int[1];
            if (i == 2) {
                nativeGetFileIDInfo(this.mNativePtr, iArr, iArr2, 0);
            } else {
                nativeGetFileIDInfo(this.mNativePtr, iArr, iArr2, i);
            }
            return iArr[0];
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera getFileIDHeader Exception:" + e.toString());
            return -1;
        }
    }

    public int getFileIDVersion(int i) {
        try {
            if (this.mNativePtr == 0) {
                return -1;
            }
            int[] iArr = new int[1];
            int[] iArr2 = new int[1];
            if (i == 2) {
                nativeGetFileIDInfo(this.mNativePtr, iArr, iArr2, 0);
            } else {
                nativeGetFileIDInfo(this.mNativePtr, iArr, iArr2, i);
            }
            return iArr2[0];
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera getFileIDVersion Exception:" + e.toString());
            return -1;
        }
    }

    public void createSwPostProc(int i) {
        this.mNativePtrSwPostProc = nativeCreateSwPostProc(i);
    }

    public int releaseSwPostProc() {
        return nativeReleaseSwPostProc(this.mNativePtr);
    }

    public int doSwPostProc(byte[] bArr, boolean z, byte[] bArr2, byte[] bArr3, int i, int i2) {
        return nativeDoSwPostProc(this.mNativePtrSwPostProc, bArr, z, bArr2, bArr3, i, i2);
    }

    public static boolean isSupportOpenCL() {
        return nativeIsSupportOpenCL();
    }

    public boolean setInterleaveMode(boolean z) {
        int iSetFWRegisterValue = -1;
        try {
        } catch (Exception e) {
            Log.e(TAG, "Failed to change interleave mode:" + e.getLocalizedMessage());
        }
        if (this.mNativePtr != 0) {
            if (nativeSetInterleaveMode(this.mNativePtr, z) != 0) {
                Log.e(TAG, "[esp_interleave] Drop frame not set");
                return false;
            }
            iSetFWRegisterValue = z ? SetFWRegisterValue(FIRMWARE_INTERLEAVE_MODE, 1) : SetFWRegisterValue(FIRMWARE_INTERLEAVE_MODE, 0);
            if (iSetFWRegisterValue != 0) {
                Log.e(TAG, "[esp_interleave] SetFWRegisterValue Not set ED");
                nativeSetInterleaveMode(this.mNativePtr, !z);
            }
            if (iSetFWRegisterValue == 0) {
                return true;
            }
        }
        return false;
    }

    public DistanceLimit getDistanceLimitInZDTable() {
        DistanceLimit distanceLimit = new DistanceLimit((char) 0, (char) 0);
        try {
            if (this.mNativePtr != 0) {
                char[] cArrNativeGetDistanceLimitInZDTable = nativeGetDistanceLimitInZDTable(this.mNativePtr);
                if (cArrNativeGetDistanceLimitInZDTable == null) {
                    Log.e(TAG, "[esp_palette] Parse ZD Table Error");
                    return distanceLimit;
                }
                distanceLimit.nearest = cArrNativeGetDistanceLimitInZDTable[0];
                distanceLimit.farthest = cArrNativeGetDistanceLimitInZDTable[1];
                Log.e(TAG, "[esp_palette] Parse ZD Table successfully " + ((int) ((short) distanceLimit.nearest)) + " , " + ((int) ((short) distanceLimit.farthest)));
            }
        } catch (Exception e) {
            Log.e(TAG, "[esp_palette] Failed to parse zd table" + e.getLocalizedMessage());
        }
        return distanceLimit;
    }

    public int setDistanceFilter(int i, int i2) {
        try {
            if (this.mNativePtr != 0) {
                return nativeSetDistanceFilters(this.mNativePtr, (char) i, (char) i2);
            }
            return -1;
        } catch (Exception e) {
            Log.e(TAG, "[esp_palette] Failed to parse zd table" + e.getLocalizedMessage());
            return -1;
        }
    }

    public int getCurrentFileIndex() {
        try {
            if (this.mNativePtr != 0) {
                return nativeGetCurrentFileIndex(this.mNativePtr);
            }
            return -1;
        } catch (Exception e) {
            Log.e(TAG, "getCurrentFileIndex Exception : " + e.toString());
            return -1;
        }
    }

    public DistanceLimit getDistanceLimit() {
        DistanceLimit distanceLimit = new DistanceLimit((char) 0, (char) 0);
        try {
            if (this.mNativePtr != 0) {
                char[] cArrNativeGetCurrentDistanceLimit = nativeGetCurrentDistanceLimit(this.mNativePtr);
                if (cArrNativeGetCurrentDistanceLimit == null) {
                    Log.e(TAG, "[esp_palette] Parse ZD Table Error");
                    return distanceLimit;
                }
                distanceLimit.nearest = cArrNativeGetCurrentDistanceLimit[0];
                distanceLimit.farthest = cArrNativeGetCurrentDistanceLimit[1];
                Log.e(TAG, "[esp_palette] getDistanceLimit" + ((int) ((short) distanceLimit.nearest)) + " , " + ((int) ((short) distanceLimit.farthest)));
            }
        } catch (Exception e) {
            Log.e(TAG, "[esp_palette] Failed to parse zd table" + e.getLocalizedMessage());
        }
        return distanceLimit;
    }

    public int saveStaticPly(String str) {
        int iOrdinal = eys_error.EYS_ERROR_OTHER.ordinal();
        if (str == null || str.equals("")) {
            return eys_error.EYS_ERROR_INVALID_FILENAME.ordinal();
        }
        try {
            if (this.mNativePtr != 0 && (iOrdinal = nativeSaveStaticPly(this.mNativePtr, str)) != eys_error.EYS_SUCCESS.ordinal()) {
                Log.e(TAG, "[esp_ply] nativeSaveStaticPly Error: " + iOrdinal);
                return iOrdinal;
            }
        } catch (Exception e) {
            Log.e(TAG, "[esp_ply] nativeSaveStaticPly exception" + e.getLocalizedMessage());
        }
        return iOrdinal;
    }

    public int saveStaticPlyWithFilter(String str, boolean z) {
        int iOrdinal = eys_error.EYS_ERROR_OTHER.ordinal();
        if (str == null || str.equals("")) {
            return eys_error.EYS_ERROR_INVALID_FILENAME.ordinal();
        }
        try {
            if (this.mNativePtr != 0 && (iOrdinal = nativeSaveStaticPlyWithFilter(this.mNativePtr, str, z)) != eys_error.EYS_SUCCESS.ordinal()) {
                Log.e(TAG, "[esp_ply] nativeSaveStaticPlyWithFilter Error: " + iOrdinal);
                return iOrdinal;
            }
        } catch (Exception e) {
            Log.e(TAG, "[esp_ply] nativeSaveStaticPlyWithFilter exception" + e.getLocalizedMessage());
        }
        return iOrdinal;
    }

    public String getDepthFilterVersion() {
        try {
            return this.mNativePtr != 0 ? nativeGetDepthFilterVersion(this.mNativePtr) : "";
        } catch (Exception e) {
            Log.e(TAG, "[esp_filter] getDepthFilterVersion exception" + e.getLocalizedMessage());
            return "";
        }
    }

    public int setDepthFilterByType(int i, boolean z) {
        int iOrdinal = eys_error.EYS_ERROR_OTHER.ordinal();
        try {
            if (this.mNativePtr != 0 && (iOrdinal = nativeSetDepthFilterByType(this.mNativePtr, i, z)) != eys_error.EYS_SUCCESS.ordinal()) {
                Log.e(TAG, "[esp_filter] setDepthFiltersEnable Error: " + iOrdinal);
                return iOrdinal;
            }
        } catch (Exception e) {
            Log.e(TAG, "[esp_filter] setDepthFiltersEnable exception" + e.getLocalizedMessage());
        }
        return iOrdinal;
    }

    public int setDepthFilters(boolean z, boolean z2, boolean z3, boolean z4, boolean z5, boolean z6) {
        int iOrdinal = eys_error.EYS_ERROR_OTHER.ordinal();
        try {
            if (this.mNativePtr != 0 && (iOrdinal = nativeSetDepthFiltersEnable(this.mNativePtr, z, z2, z3, z4, z5, z6)) != eys_error.EYS_SUCCESS.ordinal()) {
                Log.e(TAG, "[esp_filter] setDepthFilters Error: " + iOrdinal);
                return iOrdinal;
            }
        } catch (Exception e) {
            Log.e(TAG, "[esp_filter] setDepthFilters exception" + e.getLocalizedMessage());
        }
        return iOrdinal;
    }

    public int setDepthFilterSubSampleParams(int i, int i2) {
        int iOrdinal = eys_error.EYS_ERROR_OTHER.ordinal();
        try {
            if (this.mNativePtr != 0 && (iOrdinal = nativeSetDepthFilterSubSampleParams(this.mNativePtr, i, i2)) != eys_error.EYS_SUCCESS.ordinal()) {
                Log.e(TAG, "[esp_filter] setDepthFilterSubSampleParams Error: " + iOrdinal);
                return iOrdinal;
            }
        } catch (Exception e) {
            Log.e(TAG, "[esp_filter] setDepthFilterSubSampleParams exception" + e.getLocalizedMessage());
        }
        return iOrdinal;
    }

    public int setDepthFilterEdgePreservingParams(int i) {
        int iOrdinal = eys_error.EYS_ERROR_OTHER.ordinal();
        try {
            if (this.mNativePtr != 0 && (iOrdinal = nativeSetDepthFilterEdgePreservingParams(this.mNativePtr, i, 0.015f, 0.1f)) != eys_error.EYS_SUCCESS.ordinal()) {
                Log.e(TAG, "[esp_filter] setDepthFilterEdgePreservingParams Error: " + iOrdinal);
                return iOrdinal;
            }
        } catch (Exception e) {
            Log.e(TAG, "[esp_filter] setDepthFilterEdgePreservingParams exception" + e.getLocalizedMessage());
        }
        return iOrdinal;
    }

    public int setDepthFilterHoleFillingParams(int i, boolean z) {
        int iOrdinal = eys_error.EYS_ERROR_OTHER.ordinal();
        try {
            if (this.mNativePtr != 0 && (iOrdinal = nativeSetDepthFilterHoleFillingParams(this.mNativePtr, i, z)) != eys_error.EYS_SUCCESS.ordinal()) {
                Log.e(TAG, "[esp_filter] setDepthFilterHoleFillingParams Error: " + iOrdinal);
                return iOrdinal;
            }
        } catch (Exception e) {
            Log.e(TAG, "[esp_filter] setDepthFilterHoleFillingParams exception" + e.getLocalizedMessage());
        }
        return iOrdinal;
    }

    public int setDepthFilterTemporalParams(float f) {
        int iOrdinal = eys_error.EYS_ERROR_OTHER.ordinal();
        try {
            if (this.mNativePtr != 0 && (iOrdinal = nativeSetDepthFilterTemporalParams(this.mNativePtr, f, 3)) != eys_error.EYS_SUCCESS.ordinal()) {
                Log.e(TAG, "[esp_filter] setDepthFilterTemporalParams Error: " + iOrdinal);
                return iOrdinal;
            }
        } catch (Exception e) {
            Log.e(TAG, "[esp_filter] setDepthFilterTemporalParams exception" + e.getLocalizedMessage());
        }
        return iOrdinal;
    }

    public int onStartLivePly(ILivePlyCallback iLivePlyCallback) {
        int iOrdinal = eys_error.EYS_ERROR_OTHER.ordinal();
        try {
            if (this.mNativePtr != 0) {
                iOrdinal = nativeOnStartLivePly(this.mNativePtr, iLivePlyCallback);
                if (iOrdinal != eys_error.EYS_SUCCESS.ordinal()) {
                    Log.e(TAG, "[esp_dynamic_ply] nativeSaveStaticPly Error: " + iOrdinal);
                    return iOrdinal;
                }
            } else {
                Log.e(TAG, "[esp_dynamic_ply] onStartLivePly++ NULL");
            }
        } catch (Exception e) {
            Log.e(TAG, "[esp_dynamic_ply] onStartLivePly Exception:" + e.toString());
        }
        return iOrdinal;
    }

    public int onStopLivePly() {
        int iOrdinal = eys_error.EYS_ERROR_OTHER.ordinal();
        try {
            if (this.mNativePtr != 0) {
                iOrdinal = nativeOnStopLivePly(this.mNativePtr);
                if (iOrdinal != eys_error.EYS_SUCCESS.ordinal()) {
                    Log.e(TAG, "[esp_dynamic_ply] onStopLivePly Error: " + iOrdinal);
                    return iOrdinal;
                }
            } else {
                Log.e(TAG, "[esp_dynamic_ply] onStopLivePly++ NULL");
            }
        } catch (Exception e) {
            Log.e(TAG, "[esp_dynamic_ply] onStopLivePly Exception:" + e.toString());
        }
        return iOrdinal;
    }

    public int setModuleSync() {
        return SetFWRegisterValue(228, 1);
    }

    public void closeIMU() {
        try {
            Iterator<USBMonitor.UsbControlBlock> it = this.mCtrlBlocks.iterator();
            while (it.hasNext()) {
                if (it.next().isIMU() && this.mNativePtr != 0) {
                    stopReadIMUData();
                    nativeCloseIMU(this.mNativePtr);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "closeIMU() Exception:" + e.toString());
        }
    }

    public boolean isIMUEnabled() {
        try {
            Iterator<USBMonitor.UsbControlBlock> it = this.mCtrlBlocks.iterator();
            while (it.hasNext()) {
                if (it.next().isIMU() && this.mNativePtr != 0) {
                    return nativeIsIMUEnabled(this.mNativePtr);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "isIMUEnabled() Exception:" + e.toString());
        }
        Log.e(TAG, "isIMUEnabled() fail, please check IMU device is connected");
        return false;
    }

    public String getIMUModuleName() {
        try {
            if (this.mNativePtr == 0) {
                return null;
            }
            int[] iArr = new int[256];
            int[] iArr2 = new int[1];
            nativeGetIMUModuleName(this.mNativePtr, iArr, iArr2);
            int i = iArr2[0];
            StringBuilder sb = new StringBuilder();
            for (int i2 = 0; i2 < i; i2++) {
                sb.append(Character.toString((char) iArr[i2]));
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "getIMUModuleName Exception:" + e.toString());
            return null;
        }
    }

    public String getIMUFWVersion() {
        try {
            if (this.mNativePtr == 0) {
                return null;
            }
            int[] iArr = new int[256];
            int[] iArr2 = new int[1];
            nativeGetIMUFWVersion(this.mNativePtr, iArr, iArr2);
            int i = iArr2[0];
            StringBuilder sb = new StringBuilder();
            for (int i2 = 0; i2 < i; i2++) {
                sb.append(Character.toString((char) iArr[i2]));
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "getIMUFWVersion Exception:" + e.toString());
            return null;
        }
    }

    public int getIMUDataOutputByte(int i) {
        int iOrdinal = eys_error.EYS_ERROR_OTHER.ordinal();
        try {
            Iterator<USBMonitor.UsbControlBlock> it = this.mCtrlBlocks.iterator();
            while (it.hasNext()) {
                if (it.next().isIMU() && this.mNativePtr != 0) {
                    return nativeGetIMUDataOutputByte(this.mNativePtr, i);
                }
            }
            Log.e(TAG, "getIMUDataOutputByte(int) fail, please check IMU device is connected");
            return iOrdinal;
        } catch (Exception e) {
            Log.e(TAG, "getIMUDataOutputByte(int) Exception:" + e.toString());
            return iOrdinal;
        }
    }

    public int setIMUDataFormat(int i) {
        int iOrdinal = eys_error.EYS_ERROR_OTHER.ordinal();
        try {
            Iterator<USBMonitor.UsbControlBlock> it = this.mCtrlBlocks.iterator();
            while (it.hasNext()) {
                if (it.next().isIMU() && this.mNativePtr != 0) {
                    return nativeSetIMUDataFormat(this.mNativePtr, i);
                }
            }
            Log.e(TAG, "setIMUDataFormat(int) fail, please check IMU device is connected");
            return iOrdinal;
        } catch (Exception e) {
            Log.e(TAG, "setIMUDataFormat(int) Exception:" + e.toString());
            return iOrdinal;
        }
    }

    public int getIMUDataFormat() {
        try {
            Iterator<USBMonitor.UsbControlBlock> it = this.mCtrlBlocks.iterator();
            while (it.hasNext()) {
                if (it.next().isIMU() && this.mNativePtr != 0) {
                    return nativeGetIMUDataFormat(this.mNativePtr);
                }
            }
            Log.e(TAG, "getIMUDataFormat() fail, please check IMU device is connected");
            return 0;
        } catch (Exception e) {
            Log.e(TAG, "getIMUDataFormat() Exception:" + e.toString());
            return 0;
        }
    }

    public int enableIMUDataOutput(boolean z) {
        int iOrdinal = eys_error.EYS_ERROR_OTHER.ordinal();
        try {
            Iterator<USBMonitor.UsbControlBlock> it = this.mCtrlBlocks.iterator();
            while (it.hasNext()) {
                if (it.next().isIMU() && this.mNativePtr != 0) {
                    return nativeEnableIMUDataOutput(this.mNativePtr, z);
                }
            }
            Log.e(TAG, "enableIMUDataOutput(boolean) fail, please check IMU device is connected");
            return iOrdinal;
        } catch (Exception e) {
            Log.e(TAG, "enableIMUDataOutput(boolean) Exception:" + e.toString());
            return iOrdinal;
        }
    }

    public IMUData readIMUData() {
        try {
            Iterator<USBMonitor.UsbControlBlock> it = this.mCtrlBlocks.iterator();
            while (it.hasNext()) {
                if (it.next().isIMU() && this.mNativePtr != 0) {
                    return nativeReadIMUData(this.mNativePtr);
                }
            }
            Log.e(TAG, "readIMUData() null, please check IMU device is connected");
            return null;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera readIMUData() Exception:" + e.toString());
            return null;
        }
    }

    public int readIMUData(IIMUCallback iIMUCallback) {
        int iOrdinal = eys_error.EYS_ERROR_OTHER.ordinal();
        try {
            Iterator<USBMonitor.UsbControlBlock> it = this.mCtrlBlocks.iterator();
            while (it.hasNext()) {
                if (it.next().isIMU() && this.mNativePtr != 0) {
                    return nativeReadIMUDataByCallback(this.mNativePtr, iIMUCallback, this.mIMUData);
                }
            }
            Log.e(TAG, "readIMUData(IIMUCallback) fail, please check IMU device is connected");
            return iOrdinal;
        } catch (Exception e) {
            Log.e(TAG, "readIMUData(IIMUCallback) Exception:" + e.toString());
            return iOrdinal;
        }
    }

    public int stopReadIMUData() {
        int iOrdinal = eys_error.EYS_ERROR_OTHER.ordinal();
        try {
            Iterator<USBMonitor.UsbControlBlock> it = this.mCtrlBlocks.iterator();
            while (it.hasNext()) {
                if (it.next().isIMU() && this.mNativePtr != 0) {
                    return nativeStopReadIMUData(this.mNativePtr);
                }
            }
            Log.e(TAG, "stopReadIMUData fail, please check IMU device is connected");
            return iOrdinal;
        } catch (Exception e) {
            Log.e(TAG, "stopReadIMUData Exception:" + e.toString());
            return iOrdinal;
        }
    }

    public int doIMUCalibration(IIMUCallback iIMUCallback) {
        int iOrdinal = eys_error.EYS_ERROR_OTHER.ordinal();
        try {
            Iterator<USBMonitor.UsbControlBlock> it = this.mCtrlBlocks.iterator();
            while (it.hasNext()) {
                if (it.next().isIMU() && this.mNativePtr != 0) {
                    return nativeDoIMUCalibration(this.mNativePtr, iIMUCallback);
                }
            }
            Log.e(TAG, "doIMUCalibration(IIMUCallback) fail, please check IMU device is connected");
            return iOrdinal;
        } catch (Exception e) {
            Log.e(TAG, "doIMUCalibration(IIMUCallback) Exception:" + e.toString());
            return iOrdinal;
        }
    }

    public int startIMULogData(String str) {
        int iOrdinal = eys_error.EYS_ERROR_OTHER.ordinal();
        try {
            Iterator<USBMonitor.UsbControlBlock> it = this.mCtrlBlocks.iterator();
            while (it.hasNext()) {
                if (it.next().isIMU() && this.mNativePtr != 0) {
                    return nativeStartIMULogData(this.mNativePtr, str);
                }
            }
            Log.e(TAG, "startIMULogData(String) fail, please check IMU device is connected");
            return iOrdinal;
        } catch (Exception e) {
            Log.e(TAG, "startIMULogData(String) Exception:" + e.toString());
            return iOrdinal;
        }
    }

    public int stopIMULogData() {
        int iOrdinal = eys_error.EYS_ERROR_OTHER.ordinal();
        try {
            Iterator<USBMonitor.UsbControlBlock> it = this.mCtrlBlocks.iterator();
            while (it.hasNext()) {
                if (it.next().isIMU() && this.mNativePtr != 0) {
                    return nativeStopIMULogData(this.mNativePtr);
                }
            }
            Log.e(TAG, "stopIMULogData() fail, please check IMU device is connected");
            return iOrdinal;
        } catch (Exception e) {
            Log.e(TAG, "stopIMULogData() Exception:" + e.toString());
            return iOrdinal;
        }
    }

    public int resetIMU() {
        int iOrdinal = eys_error.EYS_ERROR_OTHER.ordinal();
        try {
            Iterator<USBMonitor.UsbControlBlock> it = this.mCtrlBlocks.iterator();
            while (it.hasNext()) {
                if (it.next().isIMU() && this.mNativePtr != 0) {
                    return nativeResetIMU(this.mNativePtr);
                }
            }
            Log.e(TAG, "resetIMU() fail, please check IMU device is connected");
            return iOrdinal;
        } catch (Exception e) {
            Log.e(TAG, "resetIMU() Exception:" + e.toString());
            return iOrdinal;
        }
    }

    public int adjustFocalLength(int i, int i2) {
        int iOrdinal = eys_error.EYS_ERROR_OTHER.ordinal();
        try {
            if (this.mNativePtr != 0) {
                iOrdinal = nativeAdjustFocalLength(this.mNativePtr, i, i2);
                if (iOrdinal != eys_error.EYS_SUCCESS.ordinal()) {
                    Log.e(TAG, "adjustFocalLength Error: " + iOrdinal);
                    return iOrdinal;
                }
            } else {
                Log.e(TAG, "adjustFocalLength NULL");
            }
        } catch (Exception e) {
            Log.e(TAG, "adjustFocalLength Exception:" + e.toString());
        }
        return iOrdinal;
    }

    public int adjustFocalLength(int i, int i2, int i3) {
        int iOrdinal = eys_error.EYS_ERROR_OTHER.ordinal();
        try {
            if (this.mNativePtr != 0) {
                iOrdinal = nativeAdjustFocalLengthP(this.mNativePtr, i, i2, i3);
                if (iOrdinal != eys_error.EYS_SUCCESS.ordinal()) {
                    Log.e(TAG, "adjustFocalLength Error: " + iOrdinal);
                    return iOrdinal;
                }
            } else {
                Log.e(TAG, "adjustFocalLength NULL");
            }
        } catch (Exception e) {
            Log.e(TAG, "adjustFocalLength Exception:" + e.toString());
        }
        return iOrdinal;
    }

    public int[] getDeviceFocalLength() {
        eys_error.EYS_ERROR_OTHER.ordinal();
        try {
        } catch (Exception e) {
            Log.e(TAG, "getDeviceFocalLength Exception:" + e.toString());
        }
        if (this.mNativePtr != 0) {
            int[] iArr = new int[1];
            int[] iArr2 = new int[1];
            int[] iArr3 = new int[1];
            int[] iArr4 = new int[1];
            int iNativeGetDeviceFocalLength = nativeGetDeviceFocalLength(this.mNativePtr, iArr, iArr2, iArr3, iArr4);
            if (iNativeGetDeviceFocalLength == eys_error.EYS_SUCCESS.ordinal()) {
                return new int[]{iArr[0], iArr2[0], iArr3[0], iArr4[0]};
            }
            Log.e(TAG, "getDeviceFocalLength Error: " + iNativeGetDeviceFocalLength);
            return null;
        }
        Log.e(TAG, "getDeviceFocalLength NULL");
        return null;
    }

    public int[] getFlashFocalLength(int i, int i2) {
        eys_error.EYS_ERROR_OTHER.ordinal();
        try {
        } catch (Exception e) {
            Log.e(TAG, "getFlashFocalLength Exception:" + e.toString());
        }
        if (this.mNativePtr != 0) {
            int[] iArr = new int[1];
            int[] iArr2 = new int[1];
            int[] iArr3 = new int[1];
            int[] iArr4 = new int[1];
            int[] iArr5 = new int[1];
            int iNativeGetFlashFocalLength = nativeGetFlashFocalLength(this.mNativePtr, i, i2, iArr, iArr2, iArr3, iArr4, iArr5);
            if (iNativeGetFlashFocalLength == eys_error.EYS_SUCCESS.ordinal()) {
                return new int[]{iArr[0], iArr2[0], iArr3[0], iArr4[0], iArr5[0]};
            }
            Log.e(TAG, "getFlashFocalLength Error: " + iNativeGetFlashFocalLength);
            return null;
        }
        Log.e(TAG, "getFlashFocalLength NULL");
        return null;
    }

    public int[] getDepthZOfROI(byte[] bArr, int i, int i2, int i3, int i4) {
        try {
            if (this.mNativePtr != 0) {
                return nativeGetDepthZOfROI(this.mNativePtr, bArr, i, i2, i3, i4);
            }
            return null;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera getDepthZOfROI Exception:" + e.toString());
            return null;
        }
    }

    public int enableAFBypass() {
        try {
            if (this.mNativePtr == 0) {
                return -1;
            }
            int[] iArr = new int[1];
            nativeGetHWRegister(this.mNativePtr, 61698, iArr);
            String str = TAG;
            Log.i(str, "ADDRESS_YUV_PROC_BYPASS pValue:" + iArr[0]);
            int i = iArr[0] & (-9);
            Log.i(str, "ADDRESS_YUV_PROC_BYPASS set RegValue:" + i);
            nativeSetHWRegister(this.mNativePtr, 61698, i);
            nativeGetHWRegister(this.mNativePtr, 61698, iArr);
            Log.i(str, "ADDRESS_YUV_EDGE_bypass pValue:" + iArr[0]);
            int i2 = iArr[0] & (-17);
            Log.i(str, "ADDRESS_YUV_EDGE_bypass set RegValue:" + i2);
            nativeSetHWRegister(this.mNativePtr, 61698, i2);
            nativeGetHWRegister(this.mNativePtr, 61698, iArr);
            Log.i(str, "ADDRESS_AF_BYPASS pValue:" + iArr[0]);
            int i3 = iArr[0] & (-65);
            Log.i(str, "ADDRESS_AF_BYPASS set RegValue:" + i3);
            return nativeSetHWRegister(this.mNativePtr, 61698, i3);
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera enableAFBypass Exception : " + e.toString());
            return -1;
        }
    }

    public int SetAFSettings(AutoFocusCamValue autoFocusCamValue, AutoFocusCamValue autoFocusCamValue2) {
        try {
            if (this.mNativePtr == 0) {
                return -1;
            }
            int[] iArr = new int[1];
            String str = TAG;
            Log.i(str, "Set_AF_Settings set LCAM_ADDRESS_AF_H_START :" + autoFocusCamValue.Value_AF_H_START);
            nativeSetHWRegister(this.mNativePtr, AutoFocusInfo.LCAM_ADDRESS_AF_H_START, autoFocusCamValue.Value_AF_H_START);
            Log.i(str, "Set_AF_Settings set LCAM_ADDRESS_AF_V_START :" + autoFocusCamValue.Value_AF_V_START);
            nativeSetHWRegister(this.mNativePtr, AutoFocusInfo.LCAM_ADDRESS_AF_V_START, autoFocusCamValue.Value_AF_V_START);
            Log.i(str, "Set_AF_Settings set LCAM_ADDRESS_AF_H_SIZE :" + autoFocusCamValue.Value_AF_H_SIZE);
            nativeSetHWRegister(this.mNativePtr, AutoFocusInfo.LCAM_ADDRESS_AF_H_SIZE, autoFocusCamValue.Value_AF_H_SIZE);
            Log.i(str, "Set_AF_Settings set LCAM_ADDRESS_AF_V_SIZE :" + autoFocusCamValue.Value_AF_V_SIZE);
            nativeSetHWRegister(this.mNativePtr, AutoFocusInfo.LCAM_ADDRESS_AF_V_SIZE, autoFocusCamValue.Value_AF_V_SIZE);
            Log.i(str, "Set_AF_Settings LCam.Value_AF_H_SKIP :" + autoFocusCamValue.Value_AF_H_SKIP);
            nativeGetHWRegister(this.mNativePtr, 62596, iArr);
            int i = iArr[0] & 15;
            int i2 = (autoFocusCamValue.Value_AF_H_SKIP & 15) << 4;
            int i3 = i | i2;
            Log.i(str, "Set_AF_Settings set LCAM_ADDRESS_AF_H_SKIP :" + i3);
            nativeSetHWRegister(this.mNativePtr, 62596, i3);
            Log.i(str, "Set_AF_Settings LCam.Value_AF_V_SKIP :" + autoFocusCamValue.Value_AF_V_SKIP);
            nativeGetHWRegister(this.mNativePtr, 62596, iArr);
            int i4 = (iArr[0] & FIRMWARE_ADDRESS_VIDEO_MODE) | i2;
            Log.i(str, "Set_AF_Settings set LCAM_ADDRESS_AF_H_SKIP :" + i4);
            nativeSetHWRegister(this.mNativePtr, 62596, i4);
            Log.i(str, "Set_AF_Settings set LCam.Value_AF_THD :" + autoFocusCamValue.Value_AF_THD);
            nativeSetHWRegister(this.mNativePtr, AutoFocusInfo.LCAM_ADDRESS_AF_THD, autoFocusCamValue.Value_AF_THD);
            Log.i(str, "Set_AF_Settings set RCAM_ADDRESS_AF_H_START :" + autoFocusCamValue2.Value_AF_H_START);
            nativeSetHWRegister(this.mNativePtr, AutoFocusInfo.RCAM_ADDRESS_AF_H_START, autoFocusCamValue2.Value_AF_H_START);
            Log.i(str, "Set_AF_Settings set RCAM_ADDRESS_AF_V_START :" + autoFocusCamValue2.Value_AF_V_START);
            nativeSetHWRegister(this.mNativePtr, AutoFocusInfo.RCAM_ADDRESS_AF_V_START, autoFocusCamValue2.Value_AF_V_START);
            Log.i(str, "Set_AF_Settings set RCAM_ADDRESS_AF_H_SIZE :" + autoFocusCamValue2.Value_AF_H_SIZE);
            nativeSetHWRegister(this.mNativePtr, AutoFocusInfo.RCAM_ADDRESS_AF_H_SIZE, autoFocusCamValue2.Value_AF_H_SIZE);
            Log.i(str, "Set_AF_Settings set RCAM_ADDRESS_AF_V_SIZE :" + autoFocusCamValue2.Value_AF_V_SIZE);
            nativeSetHWRegister(this.mNativePtr, AutoFocusInfo.RCAM_ADDRESS_AF_V_SIZE, autoFocusCamValue2.Value_AF_V_SIZE);
            Log.i(str, "Set_AF_Settings RCam.Value_AF_H_SKIP :" + autoFocusCamValue2.Value_AF_H_SKIP);
            nativeGetHWRegister(this.mNativePtr, 62660, iArr);
            int i5 = iArr[0] & 15;
            int i6 = (autoFocusCamValue2.Value_AF_H_SKIP & 15) << 4;
            int i7 = i5 | i6;
            Log.i(str, "Set_AF_Settings set RCAM_ADDRESS_AF_H_SKIP :" + i7);
            nativeSetHWRegister(this.mNativePtr, 62660, i7);
            Log.i(str, "Set_AF_Settings RCam.Value_AF_V_SKIP :" + autoFocusCamValue2.Value_AF_V_SKIP);
            nativeGetHWRegister(this.mNativePtr, 62660, iArr);
            int i8 = (iArr[0] & FIRMWARE_ADDRESS_VIDEO_MODE) | i6;
            Log.i(str, "Set_AF_Settings set RCAM_ADDRESS_AF_V_SKIP :" + i8);
            nativeSetHWRegister(this.mNativePtr, 62660, i8);
            Log.i(str, "Set_AF_Settings set RCam.Value_AF_THD :" + autoFocusCamValue2.Value_AF_THD);
            return nativeSetHWRegister(this.mNativePtr, AutoFocusInfo.RCAM_ADDRESS_AF_THD, autoFocusCamValue2.Value_AF_THD);
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera SetAFSettings Exception : " + e.toString());
            return -1;
        }
    }

    public int GetAFReport(AutoFocusCamValue autoFocusCamValue, AutoFocusCamValue autoFocusCamValue2) {
        int iNativeGetHWRegister = -1;
        try {
            if (this.mNativePtr == 0) {
                return -1;
            }
            int[] iArr = new int[1];
            iNativeGetHWRegister = nativeGetHWRegister(this.mNativePtr, AutoFocusInfo.LCAM_ADDRESS_AF_REPORT6, iArr);
            String str = TAG;
            Log.i(str, "LCAM_ADDRESS_AF_REPORT6 pValue:" + iArr[0]);
            int i2 = iArr[0];
            nativeGetHWRegister(this.mNativePtr, AutoFocusInfo.LCAM_ADDRESS_AF_REPORT7, iArr);
            Log.i(str, "LCAM_ADDRESS_AF_REPORT7 pValue:" + iArr[0]);
            int i3 = (iArr[0] << 8) | i2;
            nativeGetHWRegister(this.mNativePtr, AutoFocusInfo.LCAM_ADDRESS_AF_REPORT8, iArr);
            Log.i(str, "LCAM_ADDRESS_AF_REPORT8 pValue:" + iArr[0]);
            autoFocusCamValue.Value_AF_REPORT = (iArr[0] << 16) | i3;
            Log.i(str, "LCam.Value_AF_REPORT pValue:" + autoFocusCamValue.Value_AF_REPORT);
            nativeGetHWRegister(this.mNativePtr, AutoFocusInfo.RCAM_ADDRESS_AF_REPORT6, iArr);
            Log.i(str, "RCAM_ADDRESS_AF_REPORT6 pValue:" + iArr[0]);
            int i4 = iArr[0];
            nativeGetHWRegister(this.mNativePtr, AutoFocusInfo.RCAM_ADDRESS_AF_REPORT7, iArr);
            Log.i(str, "RCAM_ADDRESS_AF_REPORT7 pValue:" + iArr[0]);
            int i5 = i4 | (iArr[0] << 8);
            iNativeGetHWRegister = nativeGetHWRegister(this.mNativePtr, AutoFocusInfo.RCAM_ADDRESS_AF_REPORT8, iArr);
            Log.i(str, "RCAM_ADDRESS_AF_REPORT8 pValue:" + iArr[0]);
            autoFocusCamValue2.Value_AF_REPORT = i5 | (iArr[0] << 16);
            Log.i(str, "RCam.Value_AF_REPORT pValue:" + autoFocusCamValue2.Value_AF_REPORT);
            return iNativeGetHWRegister;
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera GetAFReport Exception : " + e.toString());
            return iNativeGetHWRegister;
        }
    }

    public class DistanceLimit {
        public char farthest;
        public char nearest;

        DistanceLimit(char c, char c2) {
            this.nearest = c;
            this.farthest = c2;
        }
    }

    public class CurrentFrameRate {
        public double mFrameRateUvc = -1.0d;
        public double mFrameRatePreview = -1.0d;
        private double mProcessedTime1 = -1.0d;
        private double mProcessedTime2 = -1.0d;
        private double mProcessedTime3 = -1.0d;

        public CurrentFrameRate() {
        }
    }

    public enum eys_error {
        EYS_SUCCESS(0),
        EYS_ERROR_OTHER(1),
        EYS_ERROR_INVALID_FILENAME(2),
        EYS_ERROR_INVALID_DIRECTORY(3),
        EYS_ERROR_NULLITY(4),
        EYS_ERROR_IO(5),
        EYS_ERROR_FIRMWARE_IO(6),
        EYS_VERIFY_DATA_FAIL(-8);

        private final int mValue;

        eys_error(int i) {
            this.mValue = i;
        }
    }
}
