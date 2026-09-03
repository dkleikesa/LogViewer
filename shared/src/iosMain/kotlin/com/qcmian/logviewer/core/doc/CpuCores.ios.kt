package com.qcmian.logviewer.core.doc

import platform.Foundation.NSProcessInfo

/** iOS：处理器核数 */
actual fun cpuCores(): Int = NSProcessInfo.processInfo.processorCount.toInt()
