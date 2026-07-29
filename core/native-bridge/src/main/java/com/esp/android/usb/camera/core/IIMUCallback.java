package com.esp.android.usb.camera.core;

/* JADX INFO: loaded from: classes.dex */
public interface IIMUCallback {
    void onCalibration(boolean z);

    void onData(IMUData iMUData);
}
