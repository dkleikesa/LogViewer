package com.qcmian.logviewer.core.doc

/** Android：可用处理器数 */
actual fun cpuCores(): Int = Runtime.getRuntime().availableProcessors()
