package com.esp.android.usb.camera.core;

import java.nio.ByteBuffer;

/* JADX INFO: loaded from: classes.dex */
public interface IFrameCallback {
    void onFrame(ByteBuffer byteBuffer, int i);
}
