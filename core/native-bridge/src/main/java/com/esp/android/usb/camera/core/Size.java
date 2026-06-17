package com.esp.android.usb.camera.core;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.Locale;

/* JADX INFO: loaded from: classes.dex */
public class Size implements Parcelable {
    public static final Parcelable.Creator<Size> CREATOR = new Parcelable.Creator<Size>() { // from class: com.esp.android.usb.camera.core.Size.1
        /* JADX WARN: Can't rename method to resolve collision */
        @Override // android.os.Parcelable.Creator
        public Size createFromParcel(Parcel parcel) {
            return new Size(parcel);
        }

        /* JADX WARN: Can't rename method to resolve collision */
        @Override // android.os.Parcelable.Creator
        public Size[] newArray(int i) {
            return new Size[i];
        }
    };
    public boolean bIsFormatMJPEG;
    public int endpointAddress;
    public int height;
    public int index;
    public int interfaceNumber;
    public int type;
    public int width;

    @Override // android.os.Parcelable
    public int describeContents() {
        return 0;
    }

    public Size(int i, int i2, int i3, int i4, int i5, int i6) {
        this.type = i;
        this.index = i2;
        this.endpointAddress = i3;
        this.interfaceNumber = i4;
        this.width = i5;
        this.height = i6;
        this.bIsFormatMJPEG = i == 4;
    }

    private Size(Parcel parcel) {
        this.type = parcel.readInt();
        this.index = parcel.readInt();
        this.endpointAddress = parcel.readInt();
        this.interfaceNumber = parcel.readInt();
        this.width = parcel.readInt();
        this.height = parcel.readInt();
    }

    public Size set(Size size) {
        if (size != null) {
            this.type = size.type;
            this.index = size.index;
            this.endpointAddress = size.endpointAddress;
            this.interfaceNumber = size.interfaceNumber;
            this.width = size.width;
            this.height = size.height;
        }
        return this;
    }

    @Override // android.os.Parcelable
    public void writeToParcel(Parcel parcel, int i) {
        parcel.writeInt(this.type);
        parcel.writeInt(this.index);
        parcel.writeInt(this.endpointAddress);
        parcel.writeInt(this.interfaceNumber);
        parcel.writeInt(this.width);
        parcel.writeInt(this.height);
    }

    public String toString() {
        return String.format(Locale.US, "Size(%dx%d,type:%d,index:%d,endpointAddress:%d,interfaceNumber:%d)", Integer.valueOf(this.width), Integer.valueOf(this.height), Integer.valueOf(this.type), Integer.valueOf(this.index), Integer.valueOf(this.endpointAddress), Integer.valueOf(this.interfaceNumber));
    }
}
