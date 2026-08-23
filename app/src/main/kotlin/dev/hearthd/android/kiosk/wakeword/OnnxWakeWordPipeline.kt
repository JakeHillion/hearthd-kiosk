package dev.hearthd.android.kiosk.wakeword

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.nio.FloatBuffer

/**
 * A faithful Kotlin port of openWakeWord's streaming inference chain, run on
 * ONNX Runtime. openWakeWord isn't a library we can call from Android — it's a
 * Python package — but the model side is just three ONNX graphs chained
 * together, and the audio-feature maths lives inside the first of them, so
 * nothing here reimplements DSP:
 *
 *   1. melspectrogram : raw 16 kHz PCM (as float) -> mel frames (32 bins)
 *   2. embedding      : a 76-frame mel window     -> a 96-d speech embedding
 *   3. wake word      : the last N embeddings      -> a score in 0..1
 *
 * Fed exactly [CHUNK] (1280) samples per [process] call, openWakeWord's
 * accumulate-and-stride bookkeeping collapses to "one new embedding per chunk",
 * which is what this implements. Constants and the odd-looking melspec
 * normalisation (`x / 10 + 2`) are taken verbatim from openWakeWord v0.5.1
 * (openwakeword/utils.py) so detections match the reference.
 *
 * Not thread-safe: drive it from a single audio thread. [close] releases the
 * ONNX sessions; the shared [OrtEnvironment] is left alone.
 */
class OnnxWakeWordPipeline(
    private val env: OrtEnvironment,
    melspecModel: ByteArray,
    embeddingModel: ByteArray,
    wakeWordModel: ByteArray,
) : AutoCloseable {

    private val melspec: OrtSession = env.createSession(melspecModel, OrtSession.SessionOptions())
    private val embedding: OrtSession = env.createSession(embeddingModel, OrtSession.SessionOptions())
    private val classifier: OrtSession = env.createSession(wakeWordModel, OrtSession.SessionOptions())

    private val melInName = melspec.inputNames.first()
    private val embInName = embedding.inputNames.first()
    private val clsInName = classifier.inputNames.first()

    // How many embedding frames this wake word consumes — read from the model
    // rather than assumed (it's 16 for the v0.1 models, but stays honest if a
    // future model differs).
    private val classifierFrames: Int = run {
        val shape = (classifier.inputInfo.values.first().info as TensorInfo).shape
        shape.getOrNull(1)?.takeIf { it > 0 }?.toInt() ?: 16
    }

    // Rolling raw-audio tail: only the most recent CHUNK + overlap samples ever
    // feed the melspectrogram, so that's all we keep.
    private val recent = ShortArray(RAW_KEEP)
    private var rawLen = 0

    // Rolling mel frames and speech embeddings. openWakeWord seeds the mel
    // buffer with ones so the first window is full; we do the same.
    private val melBuffer = ArrayDeque<FloatArray>(MEL_MAX).apply {
        repeat(EMB_WINDOW) { add(FloatArray(MEL_BINS) { 1f }) }
    }
    private val featureBuffer = ArrayDeque<FloatArray>(FEATURE_MAX)

    /**
     * Feed one [CHUNK]-sample frame of 16-bit PCM and get the current wake-word
     * score (0..1). Returns 0 during the brief warm-up before enough embeddings
     * have accumulated to run the classifier.
     */
    fun process(chunk: ShortArray): Float {
        appendRaw(chunk)

        // 1. Melspectrogram of the recent tail. openWakeWord feeds raw 16-bit
        //    sample values as float (no normalisation) and applies x/10 + 2 to
        //    the output to match the original TF model.
        val samples = FloatArray(rawLen) { recent[it].toFloat() }
        val melOut = runFloat(melspec, melInName, samples, longArrayOf(1, rawLen.toLong()))
        val frames = melOut.size / MEL_BINS
        for (f in 0 until frames) {
            val row = FloatArray(MEL_BINS)
            for (b in 0 until MEL_BINS) row[b] = melOut[f * MEL_BINS + b] / 10f + 2f
            melBuffer.addLast(row)
        }
        while (melBuffer.size > MEL_MAX) melBuffer.removeFirst()

        // 2. One embedding from the most recent 76-frame mel window.
        if (melBuffer.size >= EMB_WINDOW) {
            val embIn = FloatArray(EMB_WINDOW * MEL_BINS)
            val start = melBuffer.size - EMB_WINDOW
            for (w in 0 until EMB_WINDOW) {
                val row = melBuffer[start + w]
                System.arraycopy(row, 0, embIn, w * MEL_BINS, MEL_BINS)
            }
            val emb = runFloat(embedding, embInName, embIn, longArrayOf(1, 76, 32, 1))
            featureBuffer.addLast(emb)
            while (featureBuffer.size > FEATURE_MAX) featureBuffer.removeFirst()
        }

        // 3. Wake-word score over the last N embeddings.
        if (featureBuffer.size < classifierFrames) return 0f
        val clsIn = FloatArray(classifierFrames * EMB_DIM)
        val start = featureBuffer.size - classifierFrames
        for (i in 0 until classifierFrames) {
            System.arraycopy(featureBuffer[start + i], 0, clsIn, i * EMB_DIM, EMB_DIM)
        }
        val out = runFloat(
            classifier,
            clsInName,
            clsIn,
            longArrayOf(1, classifierFrames.toLong(), EMB_DIM.toLong()),
        )
        return out.firstOrNull() ?: 0f
    }

    private fun appendRaw(chunk: ShortArray) {
        val c = chunk.size
        if (rawLen + c <= RAW_KEEP) {
            System.arraycopy(chunk, 0, recent, rawLen, c)
            rawLen += c
        } else {
            val keep = RAW_KEEP - c
            System.arraycopy(recent, rawLen - keep, recent, 0, keep)
            System.arraycopy(chunk, 0, recent, keep, c)
            rawLen = RAW_KEEP
        }
    }

    /** Run a single-input, single-output float model and return the flat output. */
    private fun runFloat(
        session: OrtSession,
        inputName: String,
        data: FloatArray,
        shape: LongArray,
    ): FloatArray {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { input ->
            session.run(mapOf(inputName to input)).use { result ->
                val out = result[0] as OnnxTensor
                val fb = out.floatBuffer
                return FloatArray(fb.remaining()).also { fb.get(it) }
            }
        }
    }

    override fun close() {
        melspec.close()
        embedding.close()
        classifier.close()
    }

    private companion object {
        const val CHUNK = 1280 // 80 ms @ 16 kHz — openWakeWord's frame size
        const val MEL_BINS = 32
        const val EMB_WINDOW = 76 // mel frames per embedding window
        const val EMB_DIM = 96
        const val MEL_MAX = 10 * 97 // ~10 s of mel history
        const val FEATURE_MAX = 120 // ~10 s of embeddings
        const val OVERLAP = 160 * 3 // melspec context carried between chunks
        const val RAW_KEEP = CHUNK + OVERLAP
    }
}
