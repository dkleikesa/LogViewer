package com.qcmian.logviewer.core.doc

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.create

/** iOS GBK 解码（GB18030 兼容超集，编码常量 5 = NSGB18030_2000StringEncoding） */
@OptIn(ExperimentalForeignApi::class)
actual fun decodeGbk(bytes: ByteArray, strict: Boolean, offset: Int, length: Int): String? = try {
    val s = NSString.create(data = bytes.copyOfRange(offset, offset + length).toNSDataCompat(), encoding = 5uL)
    s?.toString()
} catch (_: Exception) {
    null
}

/** ByteArray -> NSData（跨版本稳定的实现） */
@OptIn(ExperimentalForeignApi::class)
internal fun ByteArray.toNSDataCompat(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    }
}

/** NSData -> ByteArray */
@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArrayCompat(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val out = ByteArray(size)
    out.usePinned { pinned ->
        val src = this.bytes
        if (src != null) {
            platform.posix.memcpy(pinned.addressOf(0), src, this.length)
        }
    }
    return out
}
