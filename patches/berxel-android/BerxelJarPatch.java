// SPDX-License-Identifier: 内部工具
// Berxel Android SDK 9.9.190 二进制兼容性补丁
//
// Why 存在：
//   1. SDK 内部 BerxelHawkUsbManager.requestDevicePermission 用
//   PendingIntent.getBroadcast(ctx, 0, intent, /*flags*/ 0)
//   Android 12 (API 31) 起强制要求显式 IMMUTABLE 或 MUTABLE flag，否则 IllegalArgumentException。
//   所有 BerxelHawkDevice.openDevice() 重载都强制走这条路，没有备用入口。
//   厂家 SDK 9.9.190 (2026-03 build) 还没修，等不及，自己 patch。
//
//   2. BerxelHawkUsbManager.getUsbDeviceList() 在按 VID/PID 筛选前，对 bus 上每个
//   UsbDevice 调 getSerialNumber()。外接 USB-C 扩展坞会暴露 HID/LAN 节点，HyperOS 对这些
//   非 Berxel 节点直接抛 SecurityException，导致 Berxel 相机无法 openDevice。
//   serial 只用于 SDK 日志，不参与筛选；这里把该方法里的 getSerialNumber() 改成 null。
//
// 原理：
//   1. ASM 找 BerxelHawkUsbManager.class 内 invokestatic PendingIntent.getBroadcast 之前那个
//   ICONST_0（推 0 上栈作 flags 参数），换成 LDC 0x14000000
//   = FLAG_IMMUTABLE (0x04000000) | FLAG_UPDATE_CURRENT (0x10000000)。
//   2. ASM 找 getUsbDeviceList() 内 UsbDevice.getSerialNumber() 调用，把前置 ALOAD + 调用
//   改成 ACONST_NULL，保留后续日志 append(null) 形态，避免重写整段控制流。
//   ASM 自动管常量池 + maxStack。
//
// 用法：
//   javac -cp asm-9.6.jar:asm-tree-9.6.jar BerxelJarPatch.java
//   java -cp .:asm-9.6.jar:asm-tree-9.6.jar BerxelJarPatch <input.jar> <output.jar>
//
// SDK 升级时重跑此 patch；如果新 SDK 自己修了，patch 找不到目标会报错，对应通过 verifyPatchNeeded 决定是否 skip。

import java.io.*;
import java.nio.file.*;
import java.util.zip.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public class BerxelJarPatch {

    static final String TARGET_CLASS = "com/berxel/berxelInterface/api/admitmanager/BerxelHawkUsbManager.class";
    static final String PENDING_INTENT_OWNER = "android/app/PendingIntent";
    static final String PENDING_INTENT_METHOD = "getBroadcast";
    static final String USB_DEVICE_OWNER = "android/hardware/usb/UsbDevice";
    static final String USB_DEVICE_GET_SERIAL = "getSerialNumber";
    static final String GET_USB_DEVICE_LIST = "getUsbDeviceList";

    // FLAG_IMMUTABLE (0x04000000) | FLAG_UPDATE_CURRENT (0x10000000) = 0x14000000
    //
    // Why IMMUTABLE: SDK 内部 Intent = `new Intent("<pkgname>.USB_PERMISSION")`，没 setPackage，
    // 是 implicit Intent。Android 14 (API 34) 起对 implicit + 无 NO_CREATE 的 PendingIntent **禁止 MUTABLE**。
    // USB 权限广播的 EXTRA_PERMISSION_GRANTED / EXTRA_DEVICE 是 system 派发广播时填的，**不**需要业务侧 mutate
    // PendingIntent 的 Intent，所以 IMMUTABLE 完全够用 — 也是 Google 官方 USB host sample 的推荐 flag。
    static final int PI_FLAGS_PATCHED = 0x14000000;

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: BerxelJarPatch <input.jar> <output.jar>");
            System.exit(2);
        }
        Path in = Paths.get(args[0]);
        Path out = Paths.get(args[1]);
        if (!Files.isRegularFile(in)) {
            System.err.println("Input jar not found: " + in);
            System.exit(2);
        }

        boolean patched = false;
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(in));
             ZipOutputStream zout = new ZipOutputStream(Files.newOutputStream(out))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = zin.read(buf)) > 0) baos.write(buf, 0, n);
                byte[] data = baos.toByteArray();

                if (e.getName().equals(TARGET_CLASS)) {
                    System.out.println("[patch] target hit " + e.getName() + " (" + data.length + " bytes)");
                    data = patch(data);
                    patched = true;
                }

                ZipEntry ne = new ZipEntry(e.getName());
                if (e.getTime() > 0) ne.setTime(e.getTime());
                zout.putNextEntry(ne);
                zout.write(data);
                zout.closeEntry();
            }
        }

        if (!patched) {
            throw new RuntimeException("Target class not found in jar: " + TARGET_CLASS);
        }
        System.out.println("[patch] wrote " + out + " (" + Files.size(out) + " bytes)");
    }

    static byte[] patch(byte[] in) {
        ClassReader cr = new ClassReader(in);
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);

        int pendingIntentHits = 0;
        int serialHits = 0;
        for (MethodNode m : cn.methods) {
            InsnList il = m.instructions;
            for (AbstractInsnNode insn = il.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() == Opcodes.INVOKEVIRTUAL) {
                    MethodInsnNode mi = (MethodInsnNode) insn;
                    if (GET_USB_DEVICE_LIST.equals(m.name)
                        && USB_DEVICE_OWNER.equals(mi.owner)
                        && USB_DEVICE_GET_SERIAL.equals(mi.name)
                        && "()Ljava/lang/String;".equals(mi.desc)) {
                        AbstractInsnNode prev = insn.getPrevious();
                        while (prev != null && (prev.getOpcode() < 0)) prev = prev.getPrevious();
                        if (prev == null || prev.getOpcode() != Opcodes.ALOAD) {
                            throw new RuntimeException("无法定位 getSerialNumber 的 receiver ALOAD：method="
                                + m.name + m.desc);
                        }
                        il.remove(prev);
                        il.set(insn, new InsnNode(Opcodes.ACONST_NULL));
                        serialHits++;
                        System.out.println("[patch] " + cn.name + "." + m.name + m.desc
                            + " UsbDevice.getSerialNumber() → ACONST_NULL");
                        continue;
                    }
                }

                if (insn.getOpcode() != Opcodes.INVOKESTATIC) continue;
                MethodInsnNode mi = (MethodInsnNode) insn;
                if (!PENDING_INTENT_OWNER.equals(mi.owner)) continue;
                if (!PENDING_INTENT_METHOD.equals(mi.name)) continue;

                // 第四参 flags 是栈顶倒数第一参 — 紧前面那个推 int 的指令就是它。
                // 走 skipLabelFrameLine 跳过非真实指令节点（FrameNode/LineNumberNode/LabelNode）。
                AbstractInsnNode prev = insn.getPrevious();
                while (prev != null && (prev.getOpcode() < 0)) prev = prev.getPrevious();
                if (prev == null) {
                    throw new RuntimeException("无法定位 flags 参数推栈指令：method=" + m.name + m.desc);
                }
                if (prev.getOpcode() != Opcodes.ICONST_0) {
                    // 已经是非 0 flag（厂家可能后来自己修了）— 不重复 patch
                    System.out.println("[patch] skip: flags 推栈指令不是 ICONST_0 而是 opcode="
                        + prev.getOpcode() + " in " + m.name + m.desc);
                    continue;
                }

                il.set(prev, new LdcInsnNode(Integer.valueOf(PI_FLAGS_PATCHED)));
                pendingIntentHits++;
                System.out.println("[patch] " + cn.name + "." + m.name + m.desc
                    + " flags ICONST_0 → LDC 0x" + Integer.toHexString(PI_FLAGS_PATCHED));
            }
        }
        if (pendingIntentHits == 0) {
            throw new RuntimeException("没找到任何 PendingIntent.getBroadcast(...,ICONST_0) 调用点 — "
                + "可能 SDK 已自己修了，需复核 patch 必要性");
        }
        if (serialHits == 0) {
            throw new RuntimeException("没找到 getUsbDeviceList() 内的 UsbDevice.getSerialNumber() 调用点 — "
                + "可能 SDK 已自己修了，需复核 patch 必要性");
        }
        System.out.println("[patch] pendingIntentHits=" + pendingIntentHits + " serialHits=" + serialHits);

        // COMPUTE_FRAMES 会触发 frame 重算，需 ClassLoader resolve 类引用，超本地类没有 — 用 COMPUTE_MAXS 即可
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }
}
