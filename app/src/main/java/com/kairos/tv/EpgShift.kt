package com.kairos.tv

object EpgShift {
    private val values = setOf("0", "1", "2", "-1", "-2")

    fun seconds(value: String): Long =
        value.takeIf { it in values }?.toLongOrNull()?.times(3_600L) ?: 0L

    fun millis(value: String): Long = seconds(value) * 1_000L
}
