package com.nte.auction.capture

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 第一阶段的进程内帧总线。后续 VisionPipeline 只订阅抽样后的关键帧，
 * 不会让 OCR/CV 对每个 MediaProjection 帧都执行重计算。
 */
object FrameHub {
    private val _frames = MutableSharedFlow<Bitmap>(
        replay = 0,
        extraBufferCapacity = 1,
    )
    val frames = _frames.asSharedFlow()

    fun offer(bitmap: Bitmap) {
        if (!_frames.tryEmit(bitmap)) bitmap.recycle()
    }
}
