package com.esp.android.usb.camera.core;

import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import com.esp.android.usb.camera.core.USBMonitor;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/* JADX INFO: loaded from: classes.dex */
public abstract class UVCCamera {
    public static final int CAMERA_360 = 2;
    public static final int CAMERA_COLOR = 0;
    public static final int CAMERA_DEPTH = 1;
    private static final boolean DEBUG = true;
    public static final float DEFAULT_BANDWIDTH = 1.0f;
    public static final int DEFAULT_PREVIEW_HEIGHT = 480;
    public static final int DEFAULT_PREVIEW_MAX_FPS = 30;
    public static final int DEFAULT_PREVIEW_MIN_FPS = 1;
    public static final int DEFAULT_PREVIEW_MODE = 0;
    public static final int DEFAULT_PREVIEW_WIDTH = 640;
    private static final String DEFAULT_USBFS = "/dev/bus/usb";
    public static final int FRAME_FORMAT_MJPEG = 1;
    public static final int FRAME_FORMAT_YUYV = 0;
    public static final int INTERFACE_NUMBER_COLOR = 1;
    public static final int INTERFACE_NUMBER_DEPTH = 2;
    public static final int PIXEL_FORMAT_RGBX = 3;
    private static final String[] SUPPORTS_CTRL;
    private static final String[] SUPPORTS_PROC;
    private static final String TAG = "UVCCamera";
    private static boolean isLoaded;
    protected int mAnalogVideoLockStateDef;
    protected int mAnalogVideoLockStateMax;
    protected int mAnalogVideoLockStateMin;
    protected int mAnalogVideoStandardDef;
    protected int mAnalogVideoStandardMax;
    protected int mAnalogVideoStandardMin;
    protected int mAutoFocusDef;
    protected int mAutoFocusMax;
    protected int mAutoFocusMin;
    protected int mAutoWhiteBlanceCompoDef;
    protected int mAutoWhiteBlanceCompoMax;
    protected int mAutoWhiteBlanceCompoMin;
    protected int mAutoWhiteBlanceDef;
    protected int mAutoWhiteBlanceMax;
    protected int mAutoWhiteBlanceMin;
    protected int mBacklightCompDef;
    protected int mBacklightCompMax;
    protected int mBacklightCompMin;
    protected int mBrightnessDef;
    protected int mBrightnessMax;
    protected int mBrightnessMin;
    protected int mContrastDef;
    protected int mContrastMax;
    protected int mContrastMin;
    protected long mControlSupports;
    protected int mExposureDef;
    protected int mExposureMax;
    protected int mExposureMin;
    protected int mExposureModeDef;
    protected int mExposureModeMax;
    protected int mExposureModeMin;
    protected int mExposurePriorityDef;
    protected int mExposurePriorityMax;
    protected int mExposurePriorityMin;
    protected int mFocusDef;
    protected int mFocusMax;
    protected int mFocusMin;
    protected int mFocusRelDef;
    protected int mFocusRelMax;
    protected int mFocusRelMin;
    protected int mFocusSimpleDef;
    protected int mFocusSimpleMax;
    protected int mFocusSimpleMin;
    protected int mGainDef;
    protected int mGainMax;
    protected int mGainMin;
    protected int mGammaDef;
    protected int mGammaMax;
    protected int mGammaMin;
    protected int mHueDef;
    protected int mHueMax;
    protected int mHueMin;
    protected int mIrisDef;
    protected int mIrisMax;
    protected int mIrisMin;
    protected int mIrisRelDef;
    protected int mIrisRelMax;
    protected int mIrisRelMin;
    protected int mMultiplierDef;
    protected int mMultiplierLimitDef;
    protected int mMultiplierLimitMax;
    protected int mMultiplierLimitMin;
    protected int mMultiplierMax;
    protected int mMultiplierMin;
    protected int mPanDef;
    protected int mPanMax;
    protected int mPanMin;
    protected int mPanRelDef;
    protected int mPanRelMax;
    protected int mPanRelMin;
    protected int mPowerlineFrequencyDef;
    protected int mPowerlineFrequencyMax;
    protected int mPowerlineFrequencyMin;
    protected int mPrivacyDef;
    protected int mPrivacyMax;
    protected int mPrivacyMin;
    protected long mProcSupports;
    protected int mRollDef;
    protected int mRollMax;
    protected int mRollMin;
    protected int mRollRelDef;
    protected int mRollRelMax;
    protected int mRollRelMin;
    protected int mSaturationDef;
    protected int mSaturationMax;
    protected int mSaturationMin;
    protected int mScanningModeDef;
    protected int mScanningModeMax;
    protected int mScanningModeMin;
    protected int mSharpnessDef;
    protected int mSharpnessMax;
    protected int mSharpnessMin;
    protected int mTiltDef;
    protected int mTiltMax;
    protected int mTiltMin;
    protected int mTiltRelDef;
    protected int mTiltRelMax;
    protected int mTiltRelMin;
    protected int mWhiteBlanceCompoDef;
    protected int mWhiteBlanceCompoMax;
    protected int mWhiteBlanceCompoMin;
    protected int mWhiteBlanceDef;
    protected int mWhiteBlanceMax;
    protected int mWhiteBlanceMin;
    protected int mWhiteBlanceRelDef;
    protected int mWhiteBlanceRelMax;
    protected int mWhiteBlanceRelMin;
    protected int mZoomDef;
    protected int mZoomMax;
    protected int mZoomMin;
    protected int mZoomRelDef;
    protected int mZoomRelMax;
    protected int mZoomRelMin;
    protected final ArrayList<USBMonitor.UsbControlBlock> mCtrlBlocks = new ArrayList<>();
    protected int mCurrentPreviewMode = 0;
    protected int mCurrentPreviewWidth = DEFAULT_PREVIEW_WIDTH;
    protected int mCurrentPreviewHeight = DEFAULT_PREVIEW_HEIGHT;
    protected String mSupportedSize = null;
    StreamInfo[] mStreamInfoListColor = null;
    StreamInfo[] mStreamInfoListDepth = null;
    protected long mNativePtr = nativeCreate();

    protected static final native int nativeGetAnalogVideoLoackState(long j);

    protected static final native int nativeGetAnalogVideoStandard(long j);

    protected static final native int nativeGetAutoContrast(long j);

    protected static final native int nativeGetAutoFocus(long j);

    protected static final native int nativeGetAutoHue(long j);

    protected static final native int nativeGetAutoWhiteBlance(long j);

    protected static final native int nativeGetAutoWhiteBlanceCompo(long j);

    protected static final native int nativeGetBacklightComp(long j);

    protected static final native int nativeGetBrightness(long j);

    protected static final native int nativeGetContrast(long j);

    protected static final native long nativeGetCtrlSupports(long j);

    protected static final native int nativeGetDigitalMultiplier(long j);

    protected static final native int nativeGetDigitalMultiplierLimit(long j);

    protected static final native int nativeGetExposure(long j);

    protected static final native int nativeGetExposureMode(long j);

    protected static final native int nativeGetExposurePriority(long j);

    protected static final native int nativeGetExposureRel(long j);

    protected static final native int nativeGetFocus(long j);

    protected static final native int nativeGetFocusRel(long j);

    protected static final native int nativeGetGain(long j);

    protected static final native int nativeGetGamma(long j);

    protected static final native int nativeGetHue(long j);

    protected static final native int nativeGetIris(long j);

    protected static final native int nativeGetIrisRel(long j);

    protected static final native int nativeGetPan(long j);

    protected static final native int nativeGetPanRel(long j);

    protected static final native int nativeGetPowerlineFrequency(long j);

    protected static final native int nativeGetPrivacy(long j);

    protected static final native long nativeGetProcSupports(long j);

    protected static final native int nativeGetRoll(long j);

    protected static final native int nativeGetRollRel(long j);

    protected static final native int nativeGetSaturation(long j);

    protected static final native int nativeGetScanningMode(long j);

    protected static final native int nativeGetSharpness(long j);

    protected static final native String nativeGetSupportedSize(long j);

    protected static final native int nativeGetTilt(long j);

    protected static final native int nativeGetTiltRel(long j);

    protected static final native int nativeGetWhiteBlance(long j);

    protected static final native int nativeGetWhiteBlanceCompo(long j);

    protected static final native int nativeGetZoom(long j);

    protected static final native int nativeGetZoomRel(long j);

    protected static final native int nativeRelease(long j);

    protected static final native int nativeSetAnalogVideoLoackState(long j, int i);

    protected static final native int nativeSetAnalogVideoStandard(long j, int i);

    protected static final native int nativeSetAutoContrast(long j, boolean z);

    protected static final native int nativeSetAutoFocus(long j, boolean z);

    protected static final native int nativeSetAutoHue(long j, boolean z);

    protected static final native int nativeSetAutoWhiteBlance(long j, boolean z);

    protected static final native int nativeSetAutoWhiteBlanceCompo(long j, boolean z);

    protected static final native int nativeSetBacklightComp(long j, int i);

    protected static final native int nativeSetBrightness(long j, int i);

    protected static final native int nativeSetButtonCallback(long j, IButtonCallback iButtonCallback);

    protected static final native int nativeSetCaptureDisplay(long j, Surface surface);

    protected static final native int nativeSetContrast(long j, int i);

    protected static final native int nativeSetDigitalMultiplier(long j, int i);

    protected static final native int nativeSetDigitalMultiplierLimit(long j, int i);

    protected static final native int nativeSetErrorCallback(long j, IErrorCallback iErrorCallback, int i);

    protected static final native int nativeSetExposure(long j, int i);

    protected static final native int nativeSetExposureMode(long j, int i);

    protected static final native int nativeSetExposurePriority(long j, int i);

    protected static final native int nativeSetExposureRel(long j, int i);

    protected static final native int nativeSetFocus(long j, int i);

    protected static final native int nativeSetFocusRel(long j, int i);

    protected static final native int nativeSetFrameCallback(long j, IFrameCallback iFrameCallback, int i, int i2);

    protected static final native int nativeSetGain(long j, int i);

    protected static final native int nativeSetGamma(long j, int i);

    protected static final native int nativeSetHue(long j, int i);

    protected static final native int nativeSetIris(long j, int i);

    protected static final native int nativeSetIrisRel(long j, int i);

    protected static final native int nativeSetPan(long j, int i);

    protected static final native int nativeSetPanRel(long j, int i);

    protected static final native int nativeSetPowerlineFrequency(long j, int i);

    protected static final native int nativeSetPreviewDisplay(long j, Surface surface, int i);

    protected static final native int nativeSetPreviewSize(long j, int i, int i2, int i3, int i4, int i5, float f, int i6);

    protected static final native int nativeSetPrivacy(long j, boolean z);

    protected static final native int nativeSetRoll(long j, int i);

    protected static final native int nativeSetRollRel(long j, int i);

    protected static final native int nativeSetSaturation(long j, int i);

    protected static final native int nativeSetScanningMode(long j, int i);

    protected static final native int nativeSetSharpness(long j, int i);

    protected static final native int nativeSetStatusCallback(long j, IStatusCallback iStatusCallback);

    protected static final native int nativeSetTilt(long j, int i);

    protected static final native int nativeSetTiltRel(long j, int i);

    protected static final native int nativeSetWhiteBlance(long j, int i);

    protected static final native int nativeSetWhiteBlanceCompo(long j, int i);

    protected static final native int nativeSetZoom(long j, int i);

    protected static final native int nativeSetZoomRel(long j, int i);

    protected static final native int nativeStartPreview(long j, int i);

    protected static final native int nativeStopPreview(long j, int i);

    public abstract void close();

    protected final native int nativeConnect(long j, int i, int i2, int i3, int i4, int i5, String str);

    protected final native long nativeCreate();

    protected final native void nativeDestroy(long j);

    protected final native int nativeUpdateAnalogVideoLockStateLimit(long j);

    protected final native int nativeUpdateAnalogVideoStandardLimit(long j);

    protected final native int nativeUpdateAutoContrastLimit(long j);

    protected final native int nativeUpdateAutoFocusLimit(long j);

    protected final native int nativeUpdateAutoHueLimit(long j);

    protected final native int nativeUpdateAutoWhiteBlanceCompoLimit(long j);

    protected final native int nativeUpdateAutoWhiteBlanceLimit(long j);

    protected final native int nativeUpdateBacklightCompLimit(long j);

    protected final native int nativeUpdateBrightnessLimit(long j);

    protected final native int nativeUpdateContrastLimit(long j);

    protected final native int nativeUpdateDigitalMultiplierLimit(long j);

    protected final native int nativeUpdateDigitalMultiplierLimitLimit(long j);

    protected final native int nativeUpdateExposureLimit(long j);

    protected final native int nativeUpdateExposureModeLimit(long j);

    protected final native int nativeUpdateExposurePriorityLimit(long j);

    protected final native int nativeUpdateExposureRelLimit(long j);

    protected final native int nativeUpdateFocusLimit(long j);

    protected final native int nativeUpdateFocusRelLimit(long j);

    protected final native int nativeUpdateGainLimit(long j);

    protected final native int nativeUpdateGammaLimit(long j);

    protected final native int nativeUpdateHueLimit(long j);

    protected final native int nativeUpdateIrisLimit(long j);

    protected final native int nativeUpdateIrisRelLimit(long j);

    protected final native int nativeUpdatePanLimit(long j);

    protected final native int nativeUpdatePanRelLimit(long j);

    protected final native int[] nativeUpdatePowerlineFrequencyLimit(long j);

    protected final native int nativeUpdatePrivacyLimit(long j);

    protected final native int nativeUpdateRollLimit(long j);

    protected final native int nativeUpdateRollRelLimit(long j);

    protected final native int nativeUpdateSaturationLimit(long j);

    protected final native int nativeUpdateScanningModeLimit(long j);

    protected final native int nativeUpdateSharpnessLimit(long j);

    protected final native int nativeUpdateTiltLimit(long j);

    protected final native int nativeUpdateTiltRelLimit(long j);

    protected final native int nativeUpdateWhiteBlanceCompoLimit(long j);

    protected final native int nativeUpdateWhiteBlanceLimit(long j);

    protected final native int nativeUpdateZoomLimit(long j);

    protected final native int nativeUpdateZoomRelLimit(long j);

    public abstract int open(USBMonitor.UsbControlBlock usbControlBlock);

    public abstract void setPreviewTexture(SurfaceTexture surfaceTexture, int i);

    public abstract void stopPreview(int i);

    public void setStatusCallback(IStatusCallback iStatusCallback) {
        try {
            long j = this.mNativePtr;
            if (j != 0) {
                nativeSetStatusCallback(j, iStatusCallback);
            }
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera setStatusCallback Exception:" + e.toString());
        }
    }

    public void setButtonCallback(IButtonCallback iButtonCallback) {
        try {
            long j = this.mNativePtr;
            if (j != 0) {
                nativeSetButtonCallback(j, iButtonCallback);
            }
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera setButtonCallback Exception:" + e.toString());
        }
    }

    @Deprecated
    public UsbDevice getDevice() {
        if (this.mCtrlBlocks.size() > 0) {
            return this.mCtrlBlocks.get(0).getDevice();
        }
        return null;
    }

    public UsbDevice getDevice(boolean z) {
        for (int i = 0; i < this.mCtrlBlocks.size(); i++) {
            if (this.mCtrlBlocks.get(i).isIMU() == z) {
                return this.mCtrlBlocks.get(i).getDevice();
            }
        }
        return null;
    }

    @Deprecated
    public String getDeviceName() {
        if (this.mCtrlBlocks.size() > 0) {
            return this.mCtrlBlocks.get(0).getDeviceName();
        }
        return null;
    }

    public String getDeviceName(boolean z) {
        for (int i = 0; i < this.mCtrlBlocks.size(); i++) {
            if (this.mCtrlBlocks.get(i).isIMU() == z) {
                return this.mCtrlBlocks.get(i).getDeviceName();
            }
        }
        return null;
    }

    @Deprecated
    public USBMonitor.UsbControlBlock getUsbControlBlock() {
        if (this.mCtrlBlocks.size() > 0) {
            return this.mCtrlBlocks.get(0);
        }
        return null;
    }

    public USBMonitor.UsbControlBlock getUsbControlBlock(boolean z) {
        for (int i = 0; i < this.mCtrlBlocks.size(); i++) {
            if (this.mCtrlBlocks.get(i).isIMU() == z) {
                return this.mCtrlBlocks.get(i);
            }
        }
        return null;
    }

    public synchronized String getSupportedSize() {
        String strNativeGetSupportedSize;
        if (TextUtils.isEmpty(this.mSupportedSize)) {
            strNativeGetSupportedSize = nativeGetSupportedSize(this.mNativePtr);
            this.mSupportedSize = strNativeGetSupportedSize;
        } else {
            strNativeGetSupportedSize = this.mSupportedSize;
        }
        return strNativeGetSupportedSize;
    }

    public Size getPreviewSize() {
        Iterator<Size> it = getSupportedSizeList().iterator();
        while (it.hasNext()) {
            Size next = it.next();
            if (next.width == this.mCurrentPreviewWidth || next.height == this.mCurrentPreviewHeight) {
                return next;
            }
        }
        return null;
    }

    public void setPreviewSize(int i, int i2, int i3) {
        setPreviewSize(i, i2, 1, 30, this.mCurrentPreviewMode, 0.0f, i3);
    }

    public void setPreviewSize(int i, int i2, int i3, int i4) {
        setPreviewSize(i, i2, 1, 30, i3, 0.0f, i4);
    }

    public void setPreviewSize(int i, int i2, int i3, float f, int i4) {
        setPreviewSize(i, i2, 1, 30, i3, f, i4);
    }

    public void setPreviewSize(int i, int i2, int i3, int i4, int i5, float f, int i6) {
        try {
            String str = TAG;
            Log.d(str, ">>>> nativeSetPreviewSize start mNativePtr:" + this.mNativePtr);
            Log.d(str, "width:" + i);
            Log.d(str, "height:" + i2);
            Log.d(str, "min_fps:" + i3);
            Log.d(str, "max_fps:" + i4);
            Log.d(str, "mode:" + i5);
            Log.d(str, "bandwidth:" + f);
            Log.d(str, "camera_switch:" + i6);
            if (i == 0 || i2 == 0) {
                Log.e(str, "setPreviewSize error:invalid preview size");
                throw new IllegalArgumentException("Failed to set preview size");
            }
            long j = this.mNativePtr;
            if (j != 0) {
                if ((i6 == 2 ? nativeSetPreviewSize(j, i, i2, i3, i4, i5, f, 0) : nativeSetPreviewSize(j, i, i2, i3, i4, i5, f, i6)) != 0) {
                    Log.e(str, "setPreviewSize error:Failed to set preview size");
                    throw new IllegalArgumentException("Failed to set preview size");
                }
                this.mCurrentPreviewMode = i5;
                this.mCurrentPreviewWidth = i;
                this.mCurrentPreviewHeight = i2;
            }
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera setPreviewSize Exception:" + e.toString());
        }
    }

    public List<Size> getSupportedSizeList() {
        return getSupportedSizeList(-1, -1);
    }

    public List<Size> getSupportedSizeList(int i) {
        return getSupportedSizeList(i, -1);
    }

    public List<Size> getSupportedSizeList(int i, int i2) {
        return getSupportedSizeList(getSupportedSize(), i, i2);
    }

    public static List<Size> getSupportedSizeList(int i, String str) {
        return getSupportedSizeList(str, i, -1);
    }

    public static List<Size> getSupportedSizeList(String str, int i) {
        return getSupportedSizeList(str, -1, i);
    }

    public static List<Size> getSupportedSizeList(String str, int i, int i2) {
        ArrayList arrayList = new ArrayList();
        if (!TextUtils.isEmpty(str)) {
            try {
                JSONArray jSONArray = new JSONObject(str).getJSONArray("formats");
                int length = jSONArray.length();
                for (int i3 = 0; i3 < length; i3++) {
                    JSONObject jSONObject = jSONArray.getJSONObject(i3);
                    int i4 = jSONObject.getInt("type");
                    int i5 = jSONObject.getInt("endpointAddress");
                    int i6 = jSONObject.getInt("interfaceNumber");
                    if ((i == i4 || i == -1) && (i2 == i6 || i2 == -1)) {
                        addSize(jSONObject, i4, i5, i6, arrayList);
                    }
                }
            } catch (JSONException unused) {
            }
        }
        return arrayList;
    }

    protected void generateStreamInfoList() {
        List<Size> supportedSizeList = getSupportedSizeList(-1, 1);
        this.mStreamInfoListColor = new StreamInfo[supportedSizeList.size()];
        int i = 0;
        int i2 = 0;
        while (true) {
            StreamInfo[] streamInfoArr = this.mStreamInfoListColor;
            if (i2 >= streamInfoArr.length) {
                break;
            }
            streamInfoArr[i2] = new StreamInfo(supportedSizeList.get(i2));
            i2++;
        }
        List<Size> supportedSizeList2 = getSupportedSizeList(-1, 2);
        this.mStreamInfoListDepth = new StreamInfo[supportedSizeList2.size()];
        while (true) {
            StreamInfo[] streamInfoArr2 = this.mStreamInfoListDepth;
            if (i >= streamInfoArr2.length) {
                return;
            }
            streamInfoArr2[i] = new StreamInfo(supportedSizeList2.get(i));
            i++;
        }
    }

    private static final void addSize(JSONObject jSONObject, int i, int i2, int i3, List<Size> list) throws JSONException {
        JSONArray jSONArray = jSONObject.getJSONArray("size");
        int length = jSONArray.length();
        for (int i4 = 0; i4 < length; i4++) {
            String[] strArrSplit = jSONArray.getString(i4).split("x");
            try {
                list.add(new Size(i, i4, i2, i3, Integer.parseInt(strArrSplit[0]), Integer.parseInt(strArrSplit[1])));
            } catch (Exception unused) {
                return;
            }
        }
    }

    public void setPreviewDisplay(SurfaceHolder surfaceHolder, int i) {
        try {
            Log.d(TAG, "nativeSetPreviewDisplay SurfaceHolder ret:" + (i == 2 ? nativeSetPreviewDisplay(this.mNativePtr, surfaceHolder.getSurface(), 0) : nativeSetPreviewDisplay(this.mNativePtr, surfaceHolder.getSurface(), i)));
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera setPreviewDisplay SurfaceHolder Exception:" + e.toString());
        }
    }

    public void setPreviewDisplay(Surface surface, int i) {
        try {
            Log.d(TAG, "nativeSetPreviewDisplay Surface ret:" + (i == 2 ? nativeSetPreviewDisplay(this.mNativePtr, surface, 0) : nativeSetPreviewDisplay(this.mNativePtr, surface, i)));
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera setPreviewDisplay Surface Exception:" + e.toString());
        }
    }

    public void setFrameCallback(IFrameCallback iFrameCallback, int i, int i2) {
        try {
            long j = this.mNativePtr;
            if (j != 0) {
                if (i2 == 2) {
                    nativeSetFrameCallback(j, iFrameCallback, i, 0);
                } else {
                    nativeSetFrameCallback(j, iFrameCallback, i, i2);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera setFrameCallback Exception:" + e.toString());
        }
    }

    public void startPreview(int i) {
        try {
            for (USBMonitor.UsbControlBlock usbControlBlock : this.mCtrlBlocks) {
                if (usbControlBlock != null && !usbControlBlock.isIMU()) {
                    Log.d(TAG, "nativeStartPreview ret:" + (i == 2 ? nativeStartPreview(this.mNativePtr, 0) : nativeStartPreview(this.mNativePtr, i)));
                    return;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera startPreview Exception:" + e.toString());
        }
    }

    public void destroy() {
        try {
            close();
            long j = this.mNativePtr;
            if (j != 0) {
                nativeDestroy(j);
                this.mNativePtr = 0L;
            }
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera destroy Exception:" + e.toString());
        }
    }

    private static final void dumpControls(long j) {
        Log.i(TAG, String.format("controlSupports=%x", Long.valueOf(j)));
        int i = 0;
        while (true) {
            String[] strArr = SUPPORTS_CTRL;
            if (i >= strArr.length) {
                return;
            }
            Log.i(TAG, strArr[i] + ((((long) (1 << i)) & j) != 0 ? "=enabled" : "=disabled"));
            i++;
        }
    }

    private static final void dumpProc(long j) {
        Log.i(TAG, String.format("procSupports=%x", Long.valueOf(j)));
        int i = 0;
        while (true) {
            String[] strArr = SUPPORTS_PROC;
            if (i >= strArr.length) {
                return;
            }
            Log.i(TAG, strArr[i] + ((((long) (1 << i)) & j) != 0 ? "=enabled" : "=disabled"));
            i++;
        }
    }

    protected final String getUSBFSName(USBMonitor.UsbControlBlock usbControlBlock) {
        String deviceName = usbControlBlock.getDeviceName();
        String string = null;
        String[] strArrSplit = !TextUtils.isEmpty(deviceName) ? deviceName.split("/") : null;
        if (strArrSplit != null && strArrSplit.length > 2) {
            StringBuilder sb = new StringBuilder(strArrSplit[0]);
            for (int i = 1; i < strArrSplit.length - 2; i++) {
                sb.append("/").append(strArrSplit[i]);
            }
            string = sb.toString();
        }
        if (!TextUtils.isEmpty(string)) {
            return string;
        }
        Log.w(TAG, "failed to get USBFS path, try to use default path:" + deviceName);
        return DEFAULT_USBFS;
    }

    public void setErrorCallback(IErrorCallback iErrorCallback, int i) {
        try {
            long j = this.mNativePtr;
            if (j != 0) {
                if (i == 2) {
                    nativeSetErrorCallback(j, iErrorCallback, 0);
                } else {
                    Log.e(TAG, "esp_catch setError Callback");
                    nativeSetErrorCallback(this.mNativePtr, iErrorCallback, i);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "UVCCamera setFrameCallback Exception:" + e.toString());
        }
    }

    static {
        // ★ gomob 改造(2026-06-15):【不】走 System.loadLibrary —— 由 native 侧 NativeBridge.bindEys3dVendorJni()
        //   dlopen libUVCCamera.so(RTLD_LOCAL,隔离其自带 libusb100 不遮蔽 gomob libusb-1.0)+ 手调 JNI_OnLoad
        //   完成 RegisterNatives + setVM。调用方须在 `new ApcCamera()` 前先调 bindEys3dVendorJni()。
        isLoaded = true;
        SUPPORTS_CTRL = new String[]{"D0:  Scanning Mode", "D1:  Auto-Exposure Mode", "D2:  Auto-Exposure Priority", "D3:  Exposure Time (Absolute)", "D4:  Exposure Time (Relative)", "D5:  Focus (Absolute)", "D6:  Focus (Relative)", "D7:  Iris (Absolute)", "D8:  Iris (Relative)", "D9:  Zoom (Absolute)", "D10: Zoom (Relative)", "D11: PanTilt (Absolute)", "D12: PanTilt (Relative)", "D13: Roll (Absolute)", "D14: Roll (Relative)", "D15: Reserved", "D16: Reserved", "D17: Focus, Auto", "D18: Privacy", "D19: Focus, Simple", "D20: Window", "D21: Region of Interest", "D22: Reserved, set to zero", "D23: Reserved, set to zero"};
        SUPPORTS_PROC = new String[]{"D0: Brightness", "D1: Contrast", "D2: Hue", "D3: Saturation", "D4: Sharpness", "D5: Gamma", "D6: White Balance Temperature", "D7: White Balance Component", "D8: Backlight Compensation", "D9: Gain", "D10: Power Line Frequency", "D11: Hue, Auto", "D12: White Balance Temperature, Auto", "D13: White Balance Component, Auto", "D14: Digital Multiplier", "D15: Digital Multiplier Limit", "D16: Analog Video Standard", "D17: Analog Video Lock Status", "D18: Contrast, Auto", "D19: Reserved. Set to zero", "D20: Reserved. Set to zero", "D21: Reserved. Set to zero", "D22: Reserved. Set to zero", "D23: Reserved. Set to zero"};
    }
}
