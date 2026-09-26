package dev.ggtv.capecraft.image

object StackedPngDecoder {
    const val FRAME_DURATION_MS = 100

    fun decode(data: ByteArray, source: String? = null): AnimatedImage {
        val decoded = PngDecoder.decode(data, source)
        if (decoded.isAnimated || decoded.loopCount != -1) {
            throw ImageDecodeException("stacked PNG должен быть статичным", source)
        }

        val frameHeight = decoded.width / 2
        if (frameHeight <= 0) {
            throw ImageDecodeException("ширина stacked PNG должна быть не меньше 2", source)
        }
        val frameCount = decoded.height / frameHeight
        if (frameCount <= 0) {
            throw ImageDecodeException("высота stacked PNG меньше высоты кадра", source)
        }

        val sourcePixels = decoded.frames[0].pixels
        val frames = ArrayList<Frame>(frameCount)
        for (frameIndex in 0 until frameCount) {
            val start = frameIndex * frameHeight * decoded.width
            val end = start + frameHeight * decoded.width
            frames += Frame(sourcePixels.copyOfRange(start, end), FRAME_DURATION_MS)
        }
        return AnimatedImage(decoded.width, frameHeight, frames)
    }
}
