/* (C) 2026 Bengkel Scan contributors. GPL-2.0-or-later. */
package com.fr3ts0n.ecu.gui.androbd.diagnosis.data

import android.content.Context
import android.os.SystemClock
import com.fr3ts0n.ecu.gui.androbd.BuildConfig
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.AudioInference
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.ExpertKnowledge
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.TriageResult
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.VehicleContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.InterpreterApi
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

sealed interface AudioInferenceState {
    data object Preparing : AudioInferenceState
    data class Running(val completedWindows: Int, val totalWindows: Int) : AudioInferenceState
    data class Complete(val result: AudioAnalysis) : AudioInferenceState
}

data class AudioAnalysis(
    val modelId: String,
    val synthetic: Boolean,
    val probabilities: Map<String, Double>,
    val rejected: Boolean,
    val trimStart: Int,
    val trimEnd: Int,
    val windows: Int,
    val meanLogits: List<Double>,
    val elapsedMs: Double,
    val triage: TriageResult?,
)

/** One interpreter per cold Flow collection; buffers are reused for every window and always closed. */
class AudioDiagnosticEngine(
    context: Context,
    private val modelPrefix: String = if (BuildConfig.DEBUG) "diagnosis/synthetic_test_model" else "diagnosis/vehicle_research_model",
) {
    private val assets = context.applicationContext.assets
    private val bundle = AudioModelBundle(assets, modelPrefix)

    suspend fun metadata(): AudioModelMetadata = withContext(Dispatchers.IO) { bundle.metadata() }

    fun analyze(
        audio: () -> InputStream,
        knowledge: ExpertKnowledge,
        vehicle: VehicleContext,
        symptoms: Set<String> = emptySet(),
    ): Flow<AudioInferenceState> = flow {
        val started = SystemClock.elapsedRealtimeNanos()
        emit(AudioInferenceState.Preparing)
        val metadata = withContext(Dispatchers.IO) { bundle.metadata() }
        val known = knowledge.entries.mapTo(HashSet()) { it.id }
        require(metadata.classes.all { it in known }) { "audio.unknown_expert_class" }
        val pcm = withContext(Dispatchers.IO) { audio().use { AudioInference.decodeWave(it, metadata.preprocessing) } }
        currentCoroutineContext().ensureActive()
        val prepared = AudioInference.preprocess(pcm, metadata.preprocessing)
        val mapped = withContext(Dispatchers.IO) { bundle.map(metadata) }
        currentCoroutineContext().ensureActive()
        val options = Interpreter.Options().setNumThreads(2).setUseXNNPACK(true)
            .setRuntime(InterpreterApi.Options.TfLiteRuntime.FROM_APPLICATION_ONLY)
        val classCount = metadata.classes.size
        val windowSize = metadata.preprocessing.windowSamples
        val mean = DoubleArray(classCount)
        Interpreter(mapped, options).use { interpreter ->
            require(interpreter.inputTensorCount == 1 && interpreter.outputTensorCount == 1 &&
                interpreter.getInputTensor(0).dataType() == DataType.FLOAT32 &&
                interpreter.getInputTensor(0).shape().contentEquals(intArrayOf(windowSize)) &&
                interpreter.getOutputTensor(0).dataType() == DataType.FLOAT32 &&
                interpreter.getOutputTensor(0).shape().contentEquals(intArrayOf(1, classCount))) { "audio.invalid_tensor_contract" }
            val input = ByteBuffer.allocateDirect(windowSize * 4).order(ByteOrder.nativeOrder())
            val floats = input.asFloatBuffer()
            val output = ByteBuffer.allocateDirect(classCount * 4).order(ByteOrder.nativeOrder())
            for (window in 0 until prepared.windowCount) {
                currentCoroutineContext().ensureActive()
                floats.clear()
                floats.put(prepared.samples, window * metadata.preprocessing.hopSamples, windowSize)
                input.rewind(); output.clear()
                interpreter.run(input, output)
                output.rewind()
                for (index in mean.indices) {
                    val value = output.float.toDouble()
                    require(value.isFinite()) { "audio.nonfinite_output" }
                    mean[index] += value
                }
                emit(AudioInferenceState.Running(window + 1, prepared.windowCount))
            }
        }
        for (index in mean.indices) mean[index] /= prepared.windowCount.toDouble()
        // Training calibrated MEAN LOGITS, not a mean of calibrated window probabilities.
        // Keep rare/transient windows: statistical trimming could erase a genuine safety event.
        val probabilities = AudioInference.calibratedProbabilities(mean, metadata.temperature)
        val rejected = probabilities.maxOrNull()!! < metadata.rejectionThreshold
        val distribution = metadata.classes.indices.associate { metadata.classes[it] to probabilities[it] }
        val triage = AudioExpertFusion.merge(metadata, knowledge, vehicle, symptoms, distribution, rejected)
        val elapsed = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
        currentCoroutineContext().ensureActive()
        emit(AudioInferenceState.Complete(AudioAnalysis(metadata.modelId, metadata.synthetic, distribution, rejected,
            prepared.trimStart, prepared.trimEnd, prepared.windowCount, mean.toList(), elapsed, triage)))
    }.flowOn(Dispatchers.Default)
}
