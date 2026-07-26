package com.esp.android.usb.camera.core;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConfiguration;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/* JADX INFO: loaded from: classes.dex */
public final class USBMonitor {
    private static final String ACTION_USB_PERMISSION_BASE = "com.esp.android.usb.camera.core.USB_PERMISSION.";
    // 原 jadx 代码 import 自缺失的 com.jiangdg.usb.USBMonitorRGB1300（RGB1300 相机用），gomob 不用该相机。
    // 这里只用它的 IntentFilter action 字符串，内联成本地常量即可，行为等价。
    private static final String ACTION_USB_DEVICE_ATTACHED_RGB1300 = "com.jiangdg.usb.USB_DEVICE_ATTACHED";
    private static final boolean DEBUG = false;
    private static final int HID_INTERFACE_CLASS = 3;
    private static final String TAG = "USBMonitor";
    private static final int VIDEO_INTERFACE_CLASS = 14;
    private static final ConcurrentHashMap<String, WeakReference<USBMonitor>> sPermissionMonitors = new ConcurrentHashMap<>();
    private final OnDeviceConnectListener mOnDeviceConnectListener;
    private final UsbManager mUsbManager;
    private final WeakReference<Context> mWeakContext;
    private final String ACTION_USB_PERMISSION = ACTION_USB_PERMISSION_BASE + hashCode();
    private final Handler mHandler = new Handler();
    private ArrayList<UsbDevice> mRequestDeviceList = new ArrayList<>();
    private final HashSet<String> mPendingPermissionDeviceKeys = new HashSet<>();
    private List<DeviceFilter> mDeviceFilters = new ArrayList();
    private final ConcurrentHashMap<UsbDevice, UsbControlBlock> mCtrlBlocks = new ConcurrentHashMap<>();
    private PendingIntent mPermissionIntent = null;
    private volatile int mDeviceCounts = 0;
    private final Runnable mDeviceCheckRunnable = new Runnable() { // from class: com.esp.android.usb.camera.core.USBMonitor.1
        @Override // java.lang.Runnable
        public void run() {
            int deviceCount = USBMonitor.this.getDeviceCount();
            if (deviceCount != USBMonitor.this.mDeviceCounts && deviceCount > USBMonitor.this.mDeviceCounts) {
                USBMonitor.this.mDeviceCounts = deviceCount;
                if (USBMonitor.this.mOnDeviceConnectListener != null) {
                    USBMonitor.this.mOnDeviceConnectListener.onAttach(null);
                }
            }
            USBMonitor.this.mHandler.postDelayed(this, 2000L);
        }
    };
    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() { // from class: com.esp.android.usb.camera.core.USBMonitor.2
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            UsbDevice usbDevice;
            UsbDevice usbDevice2;
            String action = intent.getAction();
            if (USBMonitor.this.ACTION_USB_PERMISSION.equals(action)) {
                USBMonitor.this.handlePermissionIntent(intent);
                return;
            }
            if (!"android.hardware.usb.action.USB_DEVICE_DETACHED".equals(action)) {
                if (ACTION_USB_DEVICE_ATTACHED_RGB1300.equals(action)) {
                    if (Build.VERSION.SDK_INT >= 33) {
                        usbDevice = (UsbDevice) intent.getParcelableExtra("device", UsbDevice.class);
                    } else {
                        usbDevice = (UsbDevice) intent.getParcelableExtra("device");
                    }
                    USBMonitor.this.processAttach(usbDevice);
                    return;
                }
                return;
            }
            if (Build.VERSION.SDK_INT >= 33) {
                usbDevice2 = (UsbDevice) intent.getParcelableExtra("device", UsbDevice.class);
            } else {
                usbDevice2 = (UsbDevice) intent.getParcelableExtra("device");
            }
            if (usbDevice2 != null) {
                UsbControlBlock usbControlBlock = (UsbControlBlock) USBMonitor.this.mCtrlBlocks.remove(usbDevice2);
                if (usbControlBlock != null) {
                    usbControlBlock.close();
                }
                USBMonitor.this.mDeviceCounts = 0;
                USBMonitor.this.mRequestDeviceList.clear();
                USBMonitor.this.mPendingPermissionDeviceKeys.clear();
                USBMonitor.this.processDetach(usbDevice2);
            }
        }
    };

    public interface OnDeviceConnectListener {
        void onAttach(UsbDevice usbDevice);

        void onCancel();

        void onConnect(UsbDevice usbDevice, UsbControlBlock usbControlBlock, boolean z);

        void onDetach(UsbDevice usbDevice);

        void onDisconnect(UsbDevice usbDevice, UsbControlBlock usbControlBlock);
    }

    private void usbLog(UsbDevice usbDevice) {
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handlePermissionIntent(Intent intent) {
        UsbDevice usbDevice;
        synchronized (this) {
            if (Build.VERSION.SDK_INT >= 33) {
                usbDevice = (UsbDevice) intent.getParcelableExtra("device", UsbDevice.class);
            } else {
                usbDevice = (UsbDevice) intent.getParcelableExtra("device");
            }
            boolean zRemoveQueuedRequest = removeQueuedRequest(usbDevice);
            boolean zClearPendingPermissionState = clearPendingPermissionState(usbDevice);
            if (!zRemoveQueuedRequest && !zClearPendingPermissionState) {
                if (usbDevice != null) {
                    Log.i(TAG, "ignore stale permission result for device " + usbDevice.getDeviceName());
                } else {
                    Log.i(TAG, "ignore stale permission result for null device");
                }
                requestNextPendingPermissionLocked();
                return;
            }
            if (!intent.getBooleanExtra("permission", false)) {
                processCancel(usbDevice);
            } else if (usbDevice != null) {
                if (this.mCtrlBlocks.containsKey(usbDevice)) {
                    Log.i(TAG, "ignore duplicate permission success for already-connected device " + usbDevice.getDeviceName());
                } else {
                    processConnect(usbDevice);
                }
            }
            requestNextPendingPermissionLocked();
        }
    }

    public USBMonitor(Context context, OnDeviceConnectListener onDeviceConnectListener) {
        this.mWeakContext = new WeakReference<>(context);
        this.mUsbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        this.mOnDeviceConnectListener = onDeviceConnectListener;
    }

    static void dispatchPermissionIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (TextUtils.isEmpty(action)) {
            return;
        }
        ConcurrentHashMap<String, WeakReference<USBMonitor>> concurrentHashMap = sPermissionMonitors;
        WeakReference<USBMonitor> weakReference = concurrentHashMap.get(action);
        USBMonitor uSBMonitor = weakReference != null ? weakReference.get() : null;
        if (uSBMonitor == null) {
            concurrentHashMap.remove(action);
        } else {
            uSBMonitor.handlePermissionIntent(intent);
        }
    }

    public void destroy() {
        unregister();
        Set<UsbDevice> setKeySet = this.mCtrlBlocks.keySet();
        if (setKeySet != null) {
            try {
                Iterator<UsbDevice> it = setKeySet.iterator();
                while (it.hasNext()) {
                    this.mCtrlBlocks.remove(it.next()).close();
                }
            } catch (Exception e) {
                Log.e(TAG, "destroy:", e);
            }
            this.mCtrlBlocks.clear();
        }
    }

    public synchronized void register() {
        if (this.mPermissionIntent == null) {
            Context context = this.mWeakContext.get();
            if (context != null) {
                Intent intent = new Intent(context, (Class<?>) UsbPermissionReceiver.class);
                intent.setAction(this.ACTION_USB_PERMISSION);
                intent.setPackage(context.getPackageName());
                int i = PendingIntent.FLAG_UPDATE_CURRENT |
                        (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                sPermissionMonitors.put(this.ACTION_USB_PERMISSION, new WeakReference<>(this));
                try {
                    this.mPermissionIntent = PendingIntent.getBroadcast(context, 0, intent, i);
                    IntentFilter intentFilter = new IntentFilter(this.ACTION_USB_PERMISSION);
                    intentFilter.addAction(ACTION_USB_DEVICE_ATTACHED_RGB1300);
                    intentFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
                    if (Build.VERSION.SDK_INT >= 33) {
                        context.registerReceiver(this.mUsbReceiver, intentFilter, Context.RECEIVER_EXPORTED);
                    } else {
                        context.registerReceiver(this.mUsbReceiver, intentFilter);
                    }
                } catch (RuntimeException e) {
                    sPermissionMonitors.remove(this.ACTION_USB_PERMISSION);
                    this.mPermissionIntent = null;
                    throw new IllegalStateException("Failed to register USB monitor", e);
                }
            }
            this.mDeviceCounts = 0;
            this.mRequestDeviceList.clear();
            this.mPendingPermissionDeviceKeys.clear();
            this.mHandler.postDelayed(this.mDeviceCheckRunnable, 1000L);
        }
    }

    public synchronized void unregister() {
        sPermissionMonitors.remove(this.ACTION_USB_PERMISSION);
        if (this.mPermissionIntent != null) {
            Context context = this.mWeakContext.get();
            if (context != null) {
                context.unregisterReceiver(this.mUsbReceiver);
            }
            this.mPermissionIntent = null;
        }
        this.mDeviceCounts = 0;
        this.mRequestDeviceList.clear();
        this.mPendingPermissionDeviceKeys.clear();
        this.mHandler.removeCallbacks(this.mDeviceCheckRunnable);
    }

    public synchronized boolean isRegistered() {
        return this.mPermissionIntent != null;
    }

    public boolean hasPermission(UsbDevice usbDevice) {
        return usbDevice != null && this.mUsbManager.hasPermission(usbDevice);
    }

    public synchronized void requestPermission(UsbDevice usbDevice) {
        if (this.mPermissionIntent != null && usbDevice != null) {
            if (this.mUsbManager.hasPermission(usbDevice)) {
                clearPendingPermissionState(usbDevice);
                removeQueuedRequest(usbDevice);
                if (!this.mCtrlBlocks.containsKey(usbDevice)) {
                    processConnect(usbDevice);
                } else {
                    Log.i(TAG, "ignore duplicate requestPermission for already-connected device " + usbDevice.getDeviceName());
                }
            } else {
                String deviceKey = getDeviceKey(usbDevice);
                if (deviceKey != null && this.mPendingPermissionDeviceKeys.contains(deviceKey)) {
                    return;
                }
                if (hasQueuedRequest(usbDevice)) {
                    return;
                }
                if (deviceKey != null) {
                    this.mPendingPermissionDeviceKeys.add(deviceKey);
                }
                this.mRequestDeviceList.add(usbDevice);
                this.mUsbManager.requestPermission(usbDevice, this.mPermissionIntent);
            }
        } else {
            processCancel(usbDevice);
        }
    }

    private static String getDeviceKey(UsbDevice usbDevice) {
        if (usbDevice == null) {
            return null;
        }
        return usbDevice.getDeviceName() + "#" + usbDevice.getVendorId() + "#" + usbDevice.getProductId() + "#" + usbDevice.getDeviceId();
    }

    private boolean clearPendingPermissionState(UsbDevice usbDevice) {
        String deviceKey = getDeviceKey(usbDevice);
        return deviceKey != null && this.mPendingPermissionDeviceKeys.remove(deviceKey);
    }

    private boolean hasQueuedRequest(UsbDevice usbDevice) {
        if (usbDevice == null) {
            return false;
        }
        String deviceKey = getDeviceKey(usbDevice);
        Iterator<UsbDevice> it = this.mRequestDeviceList.iterator();
        while (it.hasNext()) {
            if (isSameDevice(deviceKey, it.next())) {
                return true;
            }
        }
        return false;
    }

    private boolean removeQueuedRequest(UsbDevice usbDevice) {
        boolean z = false;
        if (usbDevice == null) {
            return false;
        }
        String deviceKey = getDeviceKey(usbDevice);
        for (int size = this.mRequestDeviceList.size() - 1; size >= 0; size--) {
            if (isSameDevice(deviceKey, this.mRequestDeviceList.get(size))) {
                this.mRequestDeviceList.remove(size);
                z = true;
            }
        }
        return z;
    }

    private static boolean isSameDevice(String str, UsbDevice usbDevice) {
        return (str == null || usbDevice == null || !str.equals(getDeviceKey(usbDevice))) ? false : true;
    }

    private void requestNextPendingPermissionLocked() {
        if (this.mPermissionIntent == null || this.mRequestDeviceList.size() <= 0) {
            return;
        }
        UsbDevice usbDevice = this.mRequestDeviceList.get(0);
        if (usbDevice == null) {
            this.mRequestDeviceList.remove(0);
            requestNextPendingPermissionLocked();
            return;
        }
        if (this.mUsbManager.hasPermission(usbDevice)) {
            removeQueuedRequest(usbDevice);
            clearPendingPermissionState(usbDevice);
            if (!this.mCtrlBlocks.containsKey(usbDevice)) {
                processConnect(usbDevice);
            } else {
                Log.i(TAG, "ignore duplicate queued permission success for already-connected device " + usbDevice.getDeviceName());
            }
            requestNextPendingPermissionLocked();
            return;
        }
        String deviceKey = getDeviceKey(usbDevice);
        if (deviceKey != null) {
            this.mPendingPermissionDeviceKeys.add(deviceKey);
        }
        try {
            this.mUsbManager.requestPermission(usbDevice, this.mPermissionIntent);
        } catch (RuntimeException e) {
            removeQueuedRequest(usbDevice);
            clearPendingPermissionState(usbDevice);
            Log.e(TAG, "requestNextPendingPermissionLocked failed for device " + usbDevice.getDeviceName(), e);
            processCancel(usbDevice);
            requestNextPendingPermissionLocked();
        }
    }

    public void setDeviceFilter(DeviceFilter deviceFilter) {
        this.mDeviceFilters.clear();
        this.mDeviceFilters.add(deviceFilter);
    }

    public void setDeviceFilter(List<DeviceFilter> list) {
        this.mDeviceFilters.clear();
        this.mDeviceFilters.addAll(list);
    }

    public int getDeviceCount() {
        return getDeviceList().size();
    }

    public Iterator<UsbDevice> getDevices() {
        HashMap<String, UsbDevice> deviceList = this.mUsbManager.getDeviceList();
        if (deviceList != null) {
            return deviceList.values().iterator();
        }
        return null;
    }

    public List<UsbDevice> getDeviceList() {
        return getDeviceList(this.mDeviceFilters);
    }

    public List<UsbDevice> getDeviceList(DeviceFilter deviceFilter) {
        HashMap<String, UsbDevice> deviceList = this.mUsbManager.getDeviceList();
        ArrayList arrayList = new ArrayList();
        if (deviceList != null) {
            filteringEachUsbDevice(deviceFilter, arrayList, deviceList.values().iterator());
        }
        return arrayList;
    }

    public List<UsbDevice> getDeviceList(List<DeviceFilter> list) {
        HashMap<String, UsbDevice> deviceList = this.mUsbManager.getDeviceList();
        ArrayList arrayList = new ArrayList();
        if (deviceList != null) {
            if (list.isEmpty()) {
                filteringEachUsbDevice(null, arrayList, deviceList.values().iterator());
            } else {
                Iterator<DeviceFilter> it = list.iterator();
                while (it.hasNext()) {
                    filteringEachUsbDevice(it.next(), arrayList, deviceList.values().iterator());
                }
            }
        }
        return arrayList;
    }

    public final void dumpDevices() {
        HashMap<String, UsbDevice> deviceList = this.mUsbManager.getDeviceList();
        if (deviceList != null) {
            Set<String> setKeySet = deviceList.keySet();
            if (setKeySet != null && setKeySet.size() > 0) {
                StringBuilder sb = new StringBuilder();
                for (String str : setKeySet) {
                    UsbDevice usbDevice = deviceList.get(str);
                    int interfaceCount = usbDevice != null ? usbDevice.getInterfaceCount() : 0;
                    sb.setLength(0);
                    for (int i = 0; i < interfaceCount; i++) {
                        sb.append(String.format("interface%d:%s", Integer.valueOf(i), usbDevice.getInterface(i).toString()));
                    }
                    Log.i(TAG, "key=" + str + ":" + usbDevice + ":" + sb.toString());
                }
                return;
            }
            Log.i(TAG, "no device");
            return;
        }
        Log.i(TAG, "no device");
    }

    private void filteringEachUsbDevice(DeviceFilter deviceFilter, List<UsbDevice> list, Iterator<UsbDevice> it) {
        while (it.hasNext()) {
            UsbDevice next = it.next();
            usbLog(next);
            UsbConfiguration configuration = null;
            try {
                configuration = next.getConfiguration(0);
            } catch (Exception unused) {
            }
            if (configuration == null) {
                continue;
            }
            if (isFilterInterfaceDevice(configuration, 14) || isFilterInterfaceDevice(configuration, 3)) {
                matchClassAndSubClass(deviceFilter, list, next);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean isFilterInterfaceDevice(UsbConfiguration usbConfiguration, int i) {
        int interfaceCount = usbConfiguration.getInterfaceCount();
        for (int i2 = 0; i2 < interfaceCount; i2++) {
            if (usbConfiguration.getInterface(i2).getInterfaceClass() != i) {
                return false;
            }
        }
        return true;
    }

    private void matchClassAndSubClass(DeviceFilter deviceFilter, List<UsbDevice> list, UsbDevice usbDevice) {
        if ((usbDevice.getDeviceClass() == 239 && usbDevice.getDeviceSubclass() == 2) || ((usbDevice.getDeviceClass() == 202 && usbDevice.getDeviceSubclass() == 8) || (usbDevice.getDeviceClass() == 0 && usbDevice.getDeviceSubclass() == 0))) {
            if (deviceFilter == null || deviceFilter.matches(usbDevice)) {
                list.add(usbDevice);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final void processAttach(final UsbDevice usbDevice) {
        if (this.mOnDeviceConnectListener != null) {
            this.mHandler.post(new Runnable() { // from class: com.esp.android.usb.camera.core.USBMonitor.3
                @Override // java.lang.Runnable
                public void run() {
                    USBMonitor.this.mOnDeviceConnectListener.onAttach(usbDevice);
                }
            });
        }
    }

    private final void processConnect(final UsbDevice usbDevice) {
        this.mHandler.post(new Runnable() { // from class: com.esp.android.usb.camera.core.USBMonitor.4
            @Override // java.lang.Runnable
            public void run() {
                boolean z;
                UsbControlBlock usbControlBlock = (UsbControlBlock) USBMonitor.this.mCtrlBlocks.get(usbDevice);
                if (usbControlBlock == null) {
                    usbControlBlock = new UsbControlBlock(USBMonitor.this, usbDevice);
                    USBMonitor.this.mCtrlBlocks.put(usbDevice, usbControlBlock);
                    z = true;
                } else {
                    z = false;
                }
                if (USBMonitor.this.mOnDeviceConnectListener != null) {
                    USBMonitor.this.mOnDeviceConnectListener.onConnect(usbDevice, usbControlBlock, z);
                }
            }
        });
    }

    private final void processCancel(UsbDevice usbDevice) {
        if (this.mOnDeviceConnectListener != null) {
            this.mHandler.post(new Runnable() { // from class: com.esp.android.usb.camera.core.USBMonitor.5
                @Override // java.lang.Runnable
                public void run() {
                    USBMonitor.this.mOnDeviceConnectListener.onCancel();
                }
            });
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final void processDetach(final UsbDevice usbDevice) {
        if (this.mOnDeviceConnectListener != null) {
            this.mHandler.post(new Runnable() { // from class: com.esp.android.usb.camera.core.USBMonitor.6
                @Override // java.lang.Runnable
                public void run() {
                    USBMonitor.this.mOnDeviceConnectListener.onDetach(usbDevice);
                }
            });
        }
    }

    public static final class UsbControlBlock {
        private final int mBusNum;
        protected UsbDeviceConnection mConnection;
        private final int mDevNum;
        private final SparseArray<UsbInterface> mInterfaces = new SparseArray<>();
        private boolean mIsIMU;
        private final WeakReference<UsbDevice> mWeakDevice;
        private final WeakReference<USBMonitor> mWeakMonitor;

        public int getBusNum() {
            return this.mBusNum;
        }

        public int getDevNum() {
            return this.mDevNum;
        }

        public UsbDeviceConnection getUsbDeviceConnection() {
            return this.mConnection;
        }

        public boolean isIMU() {
            return this.mIsIMU;
        }

        public UsbControlBlock(USBMonitor uSBMonitor, UsbDevice usbDevice) {
            int i;
            int i2;
            this.mWeakMonitor = new WeakReference<>(uSBMonitor);
            this.mWeakDevice = new WeakReference<>(usbDevice);
            this.mConnection = uSBMonitor.mUsbManager.openDevice(usbDevice);
            String deviceName = usbDevice.getDeviceName();
            String[] strArrSplit = !TextUtils.isEmpty(deviceName) ? deviceName.split("/") : null;
            if (strArrSplit != null) {
                i2 = Integer.parseInt(strArrSplit[strArrSplit.length - 2]);
                i = Integer.parseInt(strArrSplit[strArrSplit.length - 1]);
            } else {
                i = 0;
                i2 = 0;
            }
            this.mBusNum = i2;
            this.mDevNum = i;
            UsbDeviceConnection usbDeviceConnection = this.mConnection;
            if (usbDeviceConnection != null) {
                Log.i(USBMonitor.TAG, String.format(Locale.US, "name=%s,desc=%d,busNum=%d,devNum=%d,rawDesc=", deviceName, Integer.valueOf(usbDeviceConnection.getFileDescriptor()), Integer.valueOf(i2), Integer.valueOf(i)) + this.mConnection.getRawDescriptors());
            } else {
                Log.e(USBMonitor.TAG, "could not connect to device " + deviceName);
            }
            this.mIsIMU = USBMonitor.isFilterInterfaceDevice(usbDevice.getConfiguration(0), 3);
        }

        public UsbDevice getDevice() {
            return this.mWeakDevice.get();
        }

        public String getDeviceName() {
            UsbDevice usbDevice = this.mWeakDevice.get();
            return usbDevice != null ? usbDevice.getDeviceName() : "";
        }

        public synchronized int getFileDescriptor() {
            UsbDeviceConnection usbDeviceConnection;
            usbDeviceConnection = this.mConnection;
            return usbDeviceConnection != null ? usbDeviceConnection.getFileDescriptor() : -1;
        }

        public byte[] getRawDescriptors() {
            UsbDeviceConnection usbDeviceConnection = this.mConnection;
            if (usbDeviceConnection != null) {
                return usbDeviceConnection.getRawDescriptors();
            }
            return null;
        }

        public int getVenderId() {
            UsbDevice usbDevice = this.mWeakDevice.get();
            if (usbDevice != null) {
                return usbDevice.getVendorId();
            }
            return 0;
        }

        public int getProductId() {
            UsbDevice usbDevice = this.mWeakDevice.get();
            if (usbDevice != null) {
                return usbDevice.getProductId();
            }
            return 0;
        }

        public synchronized String getSerial() {
            UsbDeviceConnection usbDeviceConnection;
            usbDeviceConnection = this.mConnection;
            return usbDeviceConnection != null ? usbDeviceConnection.getSerial() : null;
        }

        public synchronized UsbInterface open(int i) {
            UsbInterface usbInterface;
            UsbDevice usbDevice = this.mWeakDevice.get();
            usbInterface = this.mInterfaces.get(i);
            if (usbInterface == null && (usbInterface = usbDevice.getInterface(i)) != null) {
                synchronized (this.mInterfaces) {
                    this.mInterfaces.append(i, usbInterface);
                }
            }
            return usbInterface;
        }

        public void close(int i) {
            synchronized (this.mInterfaces) {
                UsbInterface usbInterface = this.mInterfaces.get(i);
                if (usbInterface != null) {
                    this.mInterfaces.delete(i);
                    this.mConnection.releaseInterface(usbInterface);
                }
            }
        }

        public synchronized void close() {
            if (this.mConnection != null) {
                int size = this.mInterfaces.size();
                for (int i = 0; i < size; i++) {
                    this.mConnection.releaseInterface(this.mInterfaces.get(this.mInterfaces.keyAt(i)));
                }
                this.mConnection.close();
                this.mConnection = null;
                USBMonitor uSBMonitor = this.mWeakMonitor.get();
                if (uSBMonitor != null) {
                    UsbDevice usbDevice = this.mWeakDevice.get();
                    if (uSBMonitor.mOnDeviceConnectListener != null) {
                        uSBMonitor.mOnDeviceConnectListener.onDisconnect(usbDevice, this);
                    }
                    if (usbDevice != null) {
                        uSBMonitor.mCtrlBlocks.remove(usbDevice);
                    }
                }
            }
        }
    }

    public static class UsbDeviceInfo {
        public String manufacturer;
        public String product;
        public String serial;
        public String usb_version;
        public String version;

        private void clear() {
            this.serial = null;
            this.version = null;
            this.product = null;
            this.manufacturer = null;
            this.usb_version = null;
        }

        public String toString() {
            Object[] objArr = new Object[5];
            String str = this.usb_version;
            if (str == null) {
                str = "";
            }
            objArr[0] = str;
            String str2 = this.manufacturer;
            if (str2 == null) {
                str2 = "";
            }
            objArr[1] = str2;
            String str3 = this.product;
            if (str3 == null) {
                str3 = "";
            }
            objArr[2] = str3;
            String str4 = this.version;
            if (str4 == null) {
                str4 = "";
            }
            objArr[3] = str4;
            String str5 = this.serial;
            objArr[4] = str5 != null ? str5 : "";
            return String.format("UsbDevice:usb_version=%s,manufacturer=%s,product=%s,version=%s,serial=%s", objArr);
        }
    }
}
