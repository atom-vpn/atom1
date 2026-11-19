package com.atomvpn.mobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TrafficSpeedState(
    val downloadBytesPerSecond: Long = 0L,
    val uploadBytesPerSecond: Long = 0L,
)

object TrafficSpeedRepository {
    private val mutableState = MutableStateFlow(TrafficSpeedState())
    val state: StateFlow<TrafficSpeedState> = mutableState.asStateFlow()

    fun update(downloadBytesPerSecond: Long, uploadBytesPerSecond: Long) {
        mutableState.value = TrafficSpeedState(
            downloadBytesPerSecond = downloadBytesPerSecond.coerceAtLeast(0L),
            uploadBytesPerSecond = uploadBytesPerSecond.coerceAtLeast(0L),
        )
    }

    fun clear() {
        mutableState.value = TrafficSpeedState()
    }
}
