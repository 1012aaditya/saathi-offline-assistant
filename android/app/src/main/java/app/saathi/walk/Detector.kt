package app.saathi.walk

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import ai.onnxruntime.providers.NNAPIFlags
import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.EnumSet

/** The two walk-mode models (walk/models, packaged as assets/walk/ by the copyWalkModels Gradle task). */
enum class WalkModel(val file: String, val names: String) {
    /** COCO, every frame: traffic, people, animals, furniture. */
    Coco("walk/yolov8n-coco-256.onnx", "walk/coco.json"),

    /** Open Images V7, every few frames: doors, stairs, poles, bins… (only the classes COCO lacks). */
    Oiv7("walk/yolov8n-oiv7-256.onnx", "walk/oiv7.json"),
}

/** onnxruntime execution provider. */
enum class Backend { Cpu, Xnnpack, Nnapi }

/**
 * One YOLOv8n model in an onnxruntime session: letterboxed 256 px RGB in, boxes out
 * (as 0..1 fractions of the upright frame), only for the classes walk mode cares about.
 * Not thread-safe: use one Detector per thread.
 */
class Detector private constructor(
    val model: WalkModel,
    val backend: Backend,
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val names: List<String>,
    private val keep: IntArray,
    val size: Int,
    private val threshold: Float,
) : AutoCloseable {
    private val inputName = session.inputNames.first()
    private val input: FloatBuffer =
        ByteBuffer.allocateDirect(3 * size * size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val shape = longArrayOf(1, 3, size.toLong(), size.toLong())
    private var out = FloatArray(0)

    /** Inference time of the last call, ms (model only, not pre/post-processing). */
    var lastMs = 0.0
        private set

    /** pixels: CHW float tensor from [Yolo.preprocess] (3 × size × size). */
    fun detect(pixels: FloatArray, lb: Letterbox): List<Det> {
        input.rewind()
        input.put(pixels, 0, 3 * size * size)
        input.rewind()
        val t0 = SystemClock.elapsedRealtimeNanos()
        OnnxTensor.createTensor(env, input, shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                lastMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
                val o = result.get(0) as OnnxTensor
                val dims = o.info.shape // [1, 4 + classes, anchors]
                val channels = dims[1].toInt()
                val anchors = dims[2].toInt()
                val buf = o.floatBuffer
                if (out.size != buf.remaining()) out = FloatArray(buf.remaining())
                buf.get(out)
                return Yolo.decode(out, channels, anchors, keep, names, lb, threshold)
            }
        }
    }

    override fun close() = session.close()

    companion object {
        private const val TAG = "WalkDetector"

        /** Model files are ~13 MB each; read once per process. */
        private val bytesCache = HashMap<WalkModel, ByteArray>()

        private fun bytes(context: Context, model: WalkModel): ByteArray = synchronized(bytesCache) {
            bytesCache.getOrPut(model) { context.assets.open(model.file).use { it.readBytes() } }
        }

        fun names(context: Context, model: WalkModel): List<String> {
            val arr = JSONArray(context.assets.open(model.names).bufferedReader().use { it.readText() })
            return List(arr.length()) { arr.getString(it) }
        }

        /**
         * Create a session on [backend] with [threads] CPU threads. Throws if the backend isn't
         * available on this phone (the caller falls back to Cpu). Only [labels] are decoded.
         */
        fun load(
            context: Context,
            model: WalkModel,
            backend: Backend,
            threads: Int,
            threshold: Float = 0.3f,
            /** Class names to keep (walk mode's list by default; photo description passes its own). */
            labels: Set<String> = WalkCore.LABELS,
        ): Detector {
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                when (backend) {
                    Backend.Cpu -> setIntraOpNumThreads(threads)
                    Backend.Xnnpack -> {
                        // XNNPACK has its own thread pool; keep ORT's small so they don't fight.
                        addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
                        setIntraOpNumThreads(1)
                        addConfigEntry("session.intra_op.allow_spinning", "0")
                    }
                    Backend.Nnapi -> {
                        addNnapi(EnumSet.of(NNAPIFlags.USE_FP16))
                        setIntraOpNumThreads(threads)
                    }
                }
            }
            val t0 = SystemClock.elapsedRealtime()
            val session = env.createSession(bytes(context, model), opts)
            val info = session.inputInfo.values.first().info as TensorInfo
            val size = info.shape.last().toInt().takeIf { it > 0 } ?: 256
            val names = names(context, model)
            val keep = Yolo.keepIndices(names, labels)
            Log.i(TAG, "${model.name} on $backend×$threads: ${SystemClock.elapsedRealtime() - t0} ms to load, input $size px, ${keep.size} classes kept")
            return Detector(model, backend, env, session, names, keep, size, threshold)
        }
    }
}
