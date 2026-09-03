package com.qcmian.logviewer.core.doc

/** Web 端 GBK 暂不支持（后续可用 TextDecoder 扩展） */
actual fun decodeGbk(bytes: ByteArray, strict: Boolean, offset: Int, length: Int): String? = null
