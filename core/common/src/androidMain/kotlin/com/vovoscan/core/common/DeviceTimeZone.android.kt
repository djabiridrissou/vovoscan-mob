package com.vovoscan.core.common

import java.util.TimeZone

actual object DeviceTimeZone : UtcOffset {
    actual override fun offsetMillis(epochMillis: Long): Long = TimeZone.getDefault().getOffset(epochMillis).toLong()
}
