package io.gomob.feature.scan3d.stream

import java.io.ByteArrayOutputStream

/**
 * 极简 protobuf(proto3) 写入器 —— 只够编码 RgbdFrame, 不引第三方 protobuf gradle 插件。
 *
 * 字段号 / 线型严格对齐 gorob `proto/rgbd.proto`(已复制到 `server/proto/rgbd.proto`), 由 gorob 边缘的
 * `proto.Unmarshal(&gorobpb.RgbdFrame)` 解码。proto3 语义: 零值字段省略(解码端默认补 0), 故省 0 安全。
 *
 * 线型: 0=varint(int32/int64/enum/bool), 1=fixed64(double), 2=length-delimited(bytes/嵌套消息/packed)。
 */
internal class ProtoWriter {
    private val buf = ByteArrayOutputStream()

    fun toByteArray(): ByteArray = buf.toByteArray()

    private fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())

    private fun varint(value: Long) {
        var x = value
        while (true) {
            val b = (x and 0x7F).toInt()
            x = x ushr 7
            if (x != 0L) buf.write(b or 0x80) else { buf.write(b); break }
        }
    }

    private fun fixed64(bits: Long) {
        for (i in 0 until 8) buf.write(((bits ushr (8 * i)) and 0xFF).toInt())
    }

    fun int64(field: Int, v: Long) { if (v == 0L) return; tag(field, 0); varint(v) }
    fun int32(field: Int, v: Int) { if (v == 0) return; tag(field, 0); varint(v.toLong()) }
    fun enum(field: Int, v: Int) { if (v == 0) return; tag(field, 0); varint(v.toLong()) }
    fun bool(field: Int, v: Boolean) { if (!v) return; tag(field, 0); varint(1) }
    fun double(field: Int, v: Double) { if (v == 0.0) return; tag(field, 1); fixed64(java.lang.Double.doubleToLongBits(v)) }

    fun bytes(field: Int, v: ByteArray?) {
        if (v == null || v.isEmpty()) return
        tag(field, 2); varint(v.size.toLong()); buf.write(v)
    }

    fun message(field: Int, sub: ByteArray) {
        if (sub.isEmpty()) return
        tag(field, 2); varint(sub.size.toLong()); buf.write(sub)
    }

    /** proto3 repeated double 默认 packed: 一个 length-delimited 内连写若干 fixed64。 */
    fun packedDoubles(field: Int, vals: DoubleArray) {
        if (vals.isEmpty() || vals.all { it == 0.0 }) return
        val inner = ByteArrayOutputStream()
        for (d in vals) {
            val bits = java.lang.Double.doubleToLongBits(d)
            for (i in 0 until 8) inner.write(((bits ushr (8 * i)) and 0xFF).toInt())
        }
        val b = inner.toByteArray()
        tag(field, 2); varint(b.size.toLong()); buf.write(b)
    }
}
