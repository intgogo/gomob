package com.esp.android.usb.camera.core;

/* JADX INFO: loaded from: classes.dex */
public class StreamInfo {
    public boolean bIsFormatMJPEG;
    public int height;
    public int interfaceNumber;
    public int width;

    StreamInfo(int i, int i2, int i3, int i4) {
        this(i, i2, i3, i4 == 6);
    }

    StreamInfo(int i, int i2, int i3, boolean z) {
        this.width = i;
        this.height = i2;
        this.interfaceNumber = i3;
        this.bIsFormatMJPEG = z;
    }

    StreamInfo(Size size) {
        this.width = size.width;
        this.height = size.height;
        this.interfaceNumber = size.interfaceNumber;
        this.bIsFormatMJPEG = size.type == 6;
    }

    public String toString() {
        return this.width + "x" + this.height + (this.bIsFormatMJPEG ? "MJPEG" : "YUYV") + "_IF" + this.interfaceNumber;
    }

    public boolean isEqual(StreamInfo streamInfo) {
        return streamInfo.width == this.width && streamInfo.height == this.height && streamInfo.interfaceNumber == this.interfaceNumber && streamInfo.bIsFormatMJPEG == this.bIsFormatMJPEG;
    }
}
