package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.accessibility.AccessibilityManager
import com.example.model.AnnouncementPriority
import com.example.model.MediaDuckingLevel
import com.example.model.PlaybackTraceRecord
import com.example.model.VoiceProfilePreset
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TRACE_TAG = "BatteryBossAudioTrace"

/**
 * Decoded RIFF WAV metadata produced by [VoicePlaybackEngine.decodeRiffWavHeader].
 */
data class DecodedWavInfo(
    val audioFormatCode: Int,
    val channels: Int,
    val sampleRateHz: Int,
    val bitsPerSample: Int,
    val dataOffsetBytes: Long,
    val dataSizeBytes: Long
) {
    val formatSummary: String
        get() = "${sampleRateHz} Hz • ${bitsPerSample}-bit PCM • ${if (channels == 2) "Stereo" else "Mono"} (${dataSizeBytes} bytes)"
}

/**
 * 1. IndependentVoiceController
 *
 * Manages Battery Boss's independent application-level digital voice gain (0.0f..1.0f, default 1.0f / 100%).
 * Applies gain in a single stage (16-bit PCM sample scaling with AudioTrack locked at 1.0f unity gain)
 * without ever modifying system Media, Alarm, Ringtone, or Notification volume sliders,
 * and without ever applying gain twice.
 */
class IndependentVoiceController {

    private val _configuredVolume = MutableStateFlow(1.0f)
    val configuredVolume: StateFlow<Float> = _configuredVolume.asStateFlow()

    private val _independentModeEnabled = MutableStateFlow(true)
    val independentModeEnabled: StateFlow<Boolean> = _independentModeEnabled.asStateFlow()

    private val _applyToAllAnnouncements = MutableStateFlow(true)
    val applyToAllAnnouncements: StateFlow<Boolean> = _applyToAllAnnouncements.asStateFlow()

    @Volatile
    private var activeAudioTrack: AudioTrack? = null

    @Volatile
    private var activeTrackIsPcm16Scaled: Boolean = true

    fun updateConfiguration(
        volume: Float,
        independentEnabled: Boolean,
        applyToAll: Boolean
    ) {
        val clamped = volume.coerceIn(0.0f, 1.0f)
        _configuredVolume.value = clamped
        _independentModeEnabled.value = independentEnabled
        _applyToAllAnnouncements.value = applyToAll

        // For 16-bit PCM streams, software sample scaling in applyPcm16VolumeInPlace handles live
        // gain changes per chunk, so AudioTrack stays at 1.0f unity gain (never applying gain twice).
        runCatching {
            activeAudioTrack?.setVolume(getAudioTrackStreamGain(activeTrackIsPcm16Scaled))
        }
    }

    fun bindActiveAudioTrack(track: AudioTrack?, isPcm16SoftwareScaled: Boolean = true) {
        activeAudioTrack = track
        activeTrackIsPcm16Scaled = isPcm16SoftwareScaled
        if (track != null) {
            runCatching {
                track.setVolume(getAudioTrackStreamGain(isPcm16SoftwareScaled))
            }
        }
    }

    fun getEffectiveGain(isPreviewOrTest: Boolean = false): Float {
        return if (_applyToAllAnnouncements.value || isPreviewOrTest) {
            _configuredVolume.value.coerceIn(0.0f, 1.0f)
        } else {
            1.0f
        }
    }

    /**
     * Returns the AudioTrack stream multiplier so that gain is NEVER applied twice:
     * - When 16-bit PCM software scaling is active (isPcm16SoftwareScaled = true), AudioTrack
     *   volume is locked to 1.0f (unity) because applyPcm16VolumeInPlace already scales the samples.
     * - Only when 8-bit PCM is used (isPcm16SoftwareScaled = false) does AudioTrack apply the gain.
     */
    fun getAudioTrackStreamGain(
        isPcm16SoftwareScaled: Boolean,
        isPreviewOrTest: Boolean = false
    ): Float {
        return if (isPcm16SoftwareScaled) {
            1.0f
        } else {
            getEffectiveGain(isPreviewOrTest)
        }
    }

    /**
     * Scales 16-bit Little-Endian PCM audio samples in-place using non-clipping linear gain.
     * Ensures distortion-free single-stage playback across 0.0f..1.0f even when live volume changes occur.
     */
    fun applyPcm16VolumeInPlace(buffer: ByteArray, bytesRead: Int, gain: Float) {
        val safeGain = gain.coerceIn(0.0f, 1.0f)
        if (safeGain >= 0.995f) return
        if (safeGain <= 0.001f) {
            for (i in 0 until bytesRead) {
                buffer[i] = 0
            }
            return
        }

        var i = 0
        while (i + 1 < bytesRead) {
            val low = buffer[i].toInt() and 0xFF
            val high = buffer[i + 1].toInt() // signed
            val sample = (high shl 8) or low
            val scaled = (sample * safeGain).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buffer[i] = (scaled and 0xFF).toByte()
            buffer[i + 1] = ((scaled shr 8) and 0xFF).toByte()
            i += 2
        }
    }
}

/**
 * 2. AudioFocusController
 *
 * Manages Android AudioFocus, configurable Media Ducking (OFF, LOW, MEDIUM, HIGH),
 * phone call / alarm detection, output device inspection, and OS stream routing diagnostics.
 * Never calls AudioManager.setStreamVolume().
 */
class AudioFocusController(
    private val context: Context,
    private val onTransientInterruption: (reason: String, shouldStopPlayback: Boolean) -> Unit
) {
    private val audioManager: AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val accessibilityManager: AccessibilityManager? =
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager

    private var activeFocusRequest: AudioFocusRequest? = null

    private val _audioFocusStateText = MutableStateFlow("Idle (No Active Focus Request)")
    val audioFocusStateText: StateFlow<String> = _audioFocusStateText.asStateFlow()

    private val _callOrAlarmStateText = MutableStateFlow("Normal (No Call/Alarm Active)")
    val callOrAlarmStateText: StateFlow<String> = _callOrAlarmStateText.asStateFlow()

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                _audioFocusStateText.value = "Focus Lost Permanently (Stopped)"
                onTransientInterruption("AudioFocus Loss", true)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                _audioFocusStateText.value = "Paused (Phone Call / Alarm / System Interruption)"
                _callOrAlarmStateText.value = "Interrupted by Call / Alarm (Transient Loss)"
                onTransientInterruption("Transient Call/Alarm Interruption", true)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                _audioFocusStateText.value = "Active (Coexisting / Ducked)"
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                _audioFocusStateText.value = "Focus Gained"
                _callOrAlarmStateText.value = "Normal (No Call/Alarm Active)"
            }
        }
    }

    /**
     * Checks whether a phone call, VoIP communication, or incoming ringtone is active.
     */
    fun isPhoneCallOrRingtoneActive(): Boolean {
        val mode = audioManager?.mode ?: return false
        val inCall = mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_RINGTONE ||
            mode == AudioManager.MODE_CALL_SCREENING
        _callOrAlarmStateText.value = when (mode) {
            AudioManager.MODE_IN_CALL -> "Active Phone Call (Announcements Deferred)"
            AudioManager.MODE_IN_COMMUNICATION -> "Active VoIP Call (Announcements Deferred)"
            AudioManager.MODE_RINGTONE -> "Incoming Call Ringing (Announcements Deferred)"
            else -> "Normal (No Call/Alarm Active)"
        }
        return inCall
    }

    /**
     * Reads the current Android OS STREAM_MUSIC volume percentage (0..100) for diagnostics
     * without ever modifying it.
     */
    fun readSystemMediaVolumePercent(): Int? {
        val am = audioManager ?: return null
        return runCatching {
            val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            ((current.toFloat() / max.toFloat()) * 100f).roundToInt().coerceIn(0, 100)
        }.getOrNull()
    }

    /**
     * Checks whether an Android AccessibilityService is enabled on the OS.
     * In AOSP AudioPolicyManager (Engine.cpp), USAGE_ASSISTANCE_ACCESSIBILITY is only routed
     * to a separate STREAM_ACCESSIBILITY hardware curve when AccessibilityService is enabled
     * (and supported by the OEM ROM); otherwise the OS aliases it to STREAM_MUSIC (Media Volume).
     */
    fun isOsAccessibilityServiceEnabled(): Boolean {
        return runCatching {
            accessibilityManager?.isEnabled == true
        }.getOrDefault(false)
    }

    /**
     * Produces an honest, non-fabricated explanation of how the current Android device/OEM
     * routes USAGE_ASSISTANCE_ACCESSIBILITY at the OS mixer level.
     */
    fun buildOsStreamRoutingNote(): String {
        val mediaPct = readSystemMediaVolumePercent()
        val a11yEnabled = isOsAccessibilityServiceEnabled()
        val oem = "${Build.MANUFACTURER} (${Build.MODEL}, Android API ${Build.VERSION.SDK_INT})"
        return if (!a11yEnabled) {
            "OS AudioPolicyManager Limitation on $oem: Because no Android AccessibilityService (e.g., TalkBack) is active, Android's native AudioPolicyManager routes USAGE_ASSISTANCE_ACCESSIBILITY (11) through the STREAM_MUSIC (Media Volume, currently ${mediaPct ?: "N/A"}%) master hardware curve. Battery Boss controls 16-bit PCM digital gain (0%–100%) independently inside the app, but if system Media Volume is 0%, the Android OS hardware mixer mutes the output."
        } else {
            "Accessibility Service Active on $oem: Android routes USAGE_ASSISTANCE_ACCESSIBILITY (11) to STREAM_ACCESSIBILITY unless overridden by OEM mixer policy (System Media Volume is currently ${mediaPct ?: "N/A"}%)."
        }
    }

    fun requestFocusForSpeech(
        duckingLevel: MediaDuckingLevel,
        independentModeEnabled: Boolean
    ): Boolean {
        if (isPhoneCallOrRingtoneActive()) {
            _audioFocusStateText.value = "Deferred (Phone Call Active)"
            return false
        }

        if (duckingLevel == MediaDuckingLevel.OFF) {
            _audioFocusStateText.value = "Bypassed (Media Ducking = OFF)"
            return true
        }

        val am = audioManager ?: return true
        val focusGainType = when (duckingLevel) {
            MediaDuckingLevel.OFF -> return true
            MediaDuckingLevel.LOW -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            MediaDuckingLevel.MEDIUM -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            MediaDuckingLevel.HIGH -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        }

        val usage = if (independentModeEnabled) {
            AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
        } else {
            AudioAttributes.USAGE_MEDIA
        }

        return runCatching {
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val reqBuilder = AudioFocusRequest.Builder(focusGainType)
                    .setAudioAttributes(attrs)
                    .setWillPauseWhenDucked(duckingLevel == MediaDuckingLevel.HIGH)
                    .setOnAudioFocusChangeListener(focusChangeListener)
                val req = reqBuilder.build()
                activeFocusRequest = req
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    focusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    focusGainType
                )
            }
            val granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            _audioFocusStateText.value = if (granted) {
                "Granted (Ducking: ${duckingLevel.label})"
            } else {
                "Denied by System Audio Policy"
            }
            granted
        }.getOrDefault(true)
    }

    fun abandonFocus() {
        val am = audioManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activeFocusRequest?.let { am.abandonAudioFocusRequest(it) }
                activeFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(focusChangeListener)
            }
            _audioFocusStateText.value = "Idle (Focus Released Cleanly)"
        }
    }

    /**
     * Queries the currently active audio output route for accurate Audio Diagnostics.
     */
    fun getSelectedAudioOutputDeviceName(): String {
        val am = audioManager ?: return "N/A"
        return runCatching {
            val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            if (devices.isEmpty()) return@runCatching "Default Output Route"

            val preferred = devices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_USB_DEVICE
            } ?: devices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            } ?: devices.first()

            when (preferred.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth Audio (${preferred.productName})"
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headphones / Headset"
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Audio DAC (${preferred.productName})"
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in Device Speaker"
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Built-in Earpiece"
                else -> "System Output (${preferred.productName})"
            }
        }.getOrDefault("N/A")
    }
}

/**
 * Queued announcement item ordered by Priority (P1 Critical -> P2 Important -> P3 Normal)
 * and FIFO sequence number.
 */
data class QueuedVoiceAnnouncement(
    val id: String = UUID.randomUUID().toString(),
    val eventType: String,
    val rawMessage: String,
    val clauses: List<String>,
    val priority: AnnouncementPriority,
    val preset: VoiceProfilePreset,
    val speed: Float,
    val pitch: Float,
    val isPreviewOrTest: Boolean = false,
    val repeatCountRemaining: Int = 0,
    val sequenceNumber: Long = sequenceGenerator.incrementAndGet()
) : Comparable<QueuedVoiceAnnouncement> {

    override fun compareTo(other: QueuedVoiceAnnouncement): Int {
        val priorityDiff = this.priority.rank.compareTo(other.priority.rank)
        if (priorityDiff != 0) return priorityDiff
        return this.sequenceNumber.compareTo(other.sequenceNumber)
    }

    companion object {
        private val sequenceGenerator = AtomicLong(0L)
    }
}

/**
 * 3. VoicePlaybackEngine
 *
 * Implements the strict Independent Voice Playback pipeline:
 * - In Independent Mode (`independentModeEnabled == true`):
 *   1. Uses `TextToSpeech.synthesizeToFile()` ONLY as an offline speech wave generator.
 *   2. NEVER calls `TextToSpeech.speak()` in Independent Mode.
 *   3. Decodes the RIFF PCM WAV header (`fmt ` and `data` subchunks).
 *   4. Plays decoded PCM samples exclusively through `AudioTrack` (`USAGE_ASSISTANCE_ACCESSIBILITY`,
 *      `CONTENT_TYPE_SPEECH`) with single-stage software PCM gain (`applyPcm16VolumeInPlace`) and
 *      unity `AudioTrack.setVolume(1.0f)`.
 *   5. If synthesis, WAV decoding, or `AudioTrack` initialization fails in Independent Mode,
 *      reports `"Independent Voice Playback Unavailable – TTS fallback is disabled."` instead
 *      of silently switching to `TextToSpeech.speak()`.
 */
class VoicePlaybackEngine(
    private val context: Context,
    private val volumeController: IndependentVoiceController
) {
    companion object {
        const val ERROR_INDEPENDENT_PLAYBACK_UNAVAILABLE =
            "Independent Voice Playback Unavailable – TTS fallback is disabled."

        /**
         * Parses a RIFF WAV file by walking all subchunks (`fmt `, `JUNK`, `LIST`, `data`, etc.)
         * to extract exact PCM format parameters and the exact byte offset and length of the `data` chunk.
         */
        fun decodeRiffWavHeader(wavFile: File): DecodedWavInfo? {
            if (!wavFile.exists() || wavFile.length() <= 44L) return null
            return runCatching {
                RandomAccessFile(wavFile, "r").use { raf ->
                    val riffHeader = ByteArray(12)
                    raf.readFully(riffHeader)
                    val riffId = String(riffHeader, 0, 4, Charsets.US_ASCII)
                    val waveId = String(riffHeader, 8, 4, Charsets.US_ASCII)
                    if (riffId != "RIFF" || waveId != "WAVE") {
                        return@use null
                    }

                    var audioFormatCode = 1
                    var channels = 1
                    var sampleRate = 22050
                    var bitsPerSample = 16
                    var foundFmt = false
                    var dataOffset = -1L
                    var dataSize = 0L

                    val chunkHeader = ByteArray(8)
                    while (raf.filePointer + 8 <= raf.length()) {
                        raf.readFully(chunkHeader)
                        val chunkId = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                        val chunkSize = ByteBuffer.wrap(chunkHeader, 4, 4)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .int.toLong() and 0xFFFFFFFFL
                        val chunkDataStart = raf.filePointer

                        if (chunkId == "fmt " && chunkSize >= 16L) {
                            val fmtBytes = ByteArray(16)
                            raf.readFully(fmtBytes)
                            val fmtBuf = ByteBuffer.wrap(fmtBytes).order(ByteOrder.LITTLE_ENDIAN)
                            audioFormatCode = fmtBuf.getShort(0).toInt() and 0xFFFF
                            channels = fmtBuf.getShort(2).toInt().coerceIn(1, 2)
                            sampleRate = fmtBuf.getInt(4).coerceIn(8000, 96000)
                            bitsPerSample = fmtBuf.getShort(14).toInt()
                            foundFmt = true
                        } else if (chunkId == "data") {
                            dataOffset = chunkDataStart
                            val availableBytes = (raf.length() - chunkDataStart).coerceAtLeast(0L)
                            dataSize = if (chunkSize in 1L..availableBytes) chunkSize else availableBytes
                            break
                        }

                        val nextPos = chunkDataStart + chunkSize + (chunkSize % 2)
                        if (nextPos >= raf.length() || nextPos <= chunkDataStart) break
                        raf.seek(nextPos)
                    }

                    if (!foundFmt || dataOffset < 0L || dataSize <= 0L) {
                        null
                    } else {
                        DecodedWavInfo(
                            audioFormatCode = audioFormatCode,
                            channels = channels,
                            sampleRateHz = sampleRate,
                            bitsPerSample = bitsPerSample,
                            dataOffsetBytes = dataOffset,
                            dataSizeBytes = dataSize
                        )
                    }
                }
            }.getOrNull()
        }
    }

    @Volatile
    private var isCancelled = false

    private val _playbackEngineLabel = MutableStateFlow("SynthesizeToFile -> AudioTrack PCM (Direct TTS Disabled)")
    val playbackEngineLabel: StateFlow<String> = _playbackEngineLabel.asStateFlow()

    private val _playbackStateLabel = MutableStateFlow("Idle")
    val playbackStateLabel: StateFlow<String> = _playbackStateLabel.asStateFlow()

    private val _lastErrorText = MutableStateFlow("None")
    val lastErrorText: StateFlow<String> = _lastErrorText.asStateFlow()

    private val _lastTraceRecord = MutableStateFlow(PlaybackTraceRecord())
    val lastTraceRecord: StateFlow<PlaybackTraceRecord> = _lastTraceRecord.asStateFlow()

    private val synthCompletionMap = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    fun onUtteranceSynthDone(utteranceId: String?, success: Boolean) {
        if (utteranceId != null) {
            synthCompletionMap.remove(utteranceId)?.complete(success)
        }
    }

    fun cancelCurrentPlayback() {
        isCancelled = true
        synthCompletionMap.values.forEach { it.complete(false) }
        synthCompletionMap.clear()
        volumeController.bindActiveAudioTrack(null)
        _playbackStateLabel.value = "Idle"
    }

    suspend fun synthesizeAndPlayItem(
        tts: TextToSpeech,
        item: QueuedVoiceAnnouncement,
        naturalProsodyEnabled: Boolean,
        systemMediaVolumePercent: Int? = null,
        synthesizeTimeoutMs: Long = 10_000L
    ): Boolean = withContext(Dispatchers.IO) {
        isCancelled = false
        val cacheDir = File(context.cacheDir, "tts_independent_wav").apply {
            if (!exists()) mkdirs()
        }

        val independentEnabled = volumeController.independentModeEnabled.value
        val selectedEngineName = runCatching { tts.defaultEngine ?: "System Default TTS" }
            .getOrDefault("System Default TTS")
        val usageLabel = if (independentEnabled) {
            "USAGE_ASSISTANCE_ACCESSIBILITY (11)"
        } else {
            "USAGE_MEDIA (1)"
        }
        val contentTypeLabel = "CONTENT_TYPE_SPEECH (1)"

        for ((index, clause) in item.clauses.withIndex()) {
            if (isCancelled || !isActive) return@withContext false

            val utteranceId = "synth_${UUID.randomUUID()}"
            val wavFile = File(cacheDir, "$utteranceId.wav")
            val effectivePcmGain = volumeController.getEffectiveGain(item.isPreviewOrTest)

            try {
                _playbackStateLabel.value = "Synthesizing WAV via $selectedEngineName..."
                val deferred = CompletableDeferred<Boolean>()
                synthCompletionMap[utteranceId] = deferred

                // Pass KEY_PARAM_UTTERANCE_ID inside Bundle as well as utteranceId argument
                // so all Android and OEM TTS engines reliably fire onDone(utteranceId).
                val synthParams = Bundle().apply {
                    putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                }

                val synthResult = tts.synthesizeToFile(clause, synthParams, wavFile, utteranceId)
                val callbackCompleted = if (synthResult == TextToSpeech.SUCCESS) {
                    withTimeoutOrNull(synthesizeTimeoutMs) { deferred.await() } == true
                } else {
                    synthCompletionMap.remove(utteranceId)
                    false
                }

                // Also verify file existence/size in case an engine wrote the file synchronously
                val synthSucceeded = (callbackCompleted || (synthResult == TextToSpeech.SUCCESS && wavFile.exists() && wavFile.length() > 64L)) &&
                    wavFile.exists() && wavFile.length() > 44L

                if (isCancelled) return@withContext false

                val decodedWav = if (synthSucceeded) decodeRiffWavHeader(wavFile) else null
                val wavDecoded = decodedWav != null

                if (independentEnabled) {
                    // REQUIREMENT 2: In Independent Mode, NEVER use TextToSpeech.speak() fallback.
                    if (!synthSucceeded || decodedWav == null) {
                        _playbackEngineLabel.value = "Independent AudioTrack PCM (Direct TTS Disabled)"
                        _playbackStateLabel.value = ERROR_INDEPENDENT_PLAYBACK_UNAVAILABLE
                        _lastErrorText.value = ERROR_INDEPENDENT_PLAYBACK_UNAVAILABLE

                        recordAndLogTrace(
                            PlaybackTraceRecord(
                                entryPoint = item.eventType,
                                selectedTtsEngine = selectedEngineName,
                                synthesizeToFileSucceeded = synthSucceeded,
                                pcmWavDecoded = wavDecoded,
                                decodedWavFormatSummary = decodedWav?.formatSummary ?: "Decode Failed",
                                audioTrackCreated = false,
                                actualAudioAttributesUsage = usageLabel,
                                actualAudioAttributesContentType = contentTypeLabel,
                                audioTrackPlaybackState = "NOT_CREATED",
                                directTtsFallbackUsed = false,
                                effectivePcmGain = effectivePcmGain,
                                audioTrackStreamGain = 1.0f,
                                systemMediaVolumePercent = systemMediaVolumePercent,
                                errorOrLimitation = ERROR_INDEPENDENT_PLAYBACK_UNAVAILABLE
                            )
                        )
                        return@withContext false
                    }

                    _playbackEngineLabel.value = "SynthesizeToFile -> AudioTrack PCM (Direct TTS Disabled)"
                    val trackResult = playWavFileThroughAudioTrack(
                        wavFile = wavFile,
                        decodedWav = decodedWav,
                        independentModeEnabled = true,
                        isPreviewOrTest = item.isPreviewOrTest,
                        entryPoint = item.eventType,
                        selectedEngineName = selectedEngineName,
                        systemMediaVolumePercent = systemMediaVolumePercent
                    )
                    if (!trackResult) {
                        _playbackStateLabel.value = ERROR_INDEPENDENT_PLAYBACK_UNAVAILABLE
                        _lastErrorText.value = ERROR_INDEPENDENT_PLAYBACK_UNAVAILABLE
                        return@withContext false
                    } else {
                        _lastErrorText.value = "None"
                    }
                } else {
                    // Independent Mode is explicitly OFF: use WAV -> AudioTrack on USAGE_MEDIA if available,
                    // or fallback to direct TextToSpeech.speak() ONLY because Independent Mode is disabled.
                    if (synthSucceeded && decodedWav != null) {
                        _playbackEngineLabel.value = "SynthesizeToFile -> AudioTrack (System Media Stream)"
                        playWavFileThroughAudioTrack(
                            wavFile = wavFile,
                            decodedWav = decodedWav,
                            independentModeEnabled = false,
                            isPreviewOrTest = item.isPreviewOrTest,
                            entryPoint = item.eventType,
                            selectedEngineName = selectedEngineName,
                            systemMediaVolumePercent = systemMediaVolumePercent
                        )
                    } else {
                        _playbackEngineLabel.value = "Fallback Direct TTS Stream (Independent Mode OFF)"
                        playFallbackDirectTtsWhenIndependentDisabled(
                            tts = tts,
                            clause = clause,
                            isPreviewOrTest = item.isPreviewOrTest,
                            entryPoint = item.eventType,
                            selectedEngineName = selectedEngineName,
                            systemMediaVolumePercent = systemMediaVolumePercent
                        )
                    }
                }

                if (naturalProsodyEnabled && index < item.clauses.lastIndex && !isCancelled) {
                    delay(200L)
                }
            } catch (t: Throwable) {
                val errMsg = if (independentEnabled) {
                    ERROR_INDEPENDENT_PLAYBACK_UNAVAILABLE
                } else {
                    t.localizedMessage ?: "AudioTrack playback exception"
                }
                _lastErrorText.value = errMsg
                _playbackStateLabel.value = errMsg
                recordAndLogTrace(
                    PlaybackTraceRecord(
                        entryPoint = item.eventType,
                        selectedTtsEngine = selectedEngineName,
                        synthesizeToFileSucceeded = false,
                        pcmWavDecoded = false,
                        decodedWavFormatSummary = "Exception: ${t.javaClass.simpleName}",
                        audioTrackCreated = false,
                        actualAudioAttributesUsage = usageLabel,
                        actualAudioAttributesContentType = contentTypeLabel,
                        audioTrackPlaybackState = "ERROR",
                        directTtsFallbackUsed = false,
                        effectivePcmGain = effectivePcmGain,
                        audioTrackStreamGain = 1.0f,
                        systemMediaVolumePercent = systemMediaVolumePercent,
                        errorOrLimitation = errMsg
                    )
                )
                return@withContext false
            } finally {
                runCatching { if (wavFile.exists()) wavFile.delete() }
            }
        }

        _playbackStateLabel.value = "Idle"
        true
    }

    private suspend fun playWavFileThroughAudioTrack(
        wavFile: File,
        decodedWav: DecodedWavInfo,
        independentModeEnabled: Boolean,
        isPreviewOrTest: Boolean,
        entryPoint: String,
        selectedEngineName: String,
        systemMediaVolumePercent: Int?
    ): Boolean {
        val channelOut = if (decodedWav.channels == 2) {
            AudioFormat.CHANNEL_OUT_STEREO
        } else {
            AudioFormat.CHANNEL_OUT_MONO
        }
        val encoding = if (decodedWav.bitsPerSample == 8) {
            AudioFormat.ENCODING_PCM_8BIT
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }
        val isPcm16 = encoding == AudioFormat.ENCODING_PCM_16BIT

        val minBufSize = AudioTrack.getMinBufferSize(decodedWav.sampleRateHz, channelOut, encoding)
            .coerceAtLeast(4096)

        val usage = if (independentModeEnabled) {
            AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
        } else {
            AudioAttributes.USAGE_MEDIA
        }
        val contentType = AudioAttributes.CONTENT_TYPE_SPEECH

        val usageLabel = if (usage == AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY) {
            "USAGE_ASSISTANCE_ACCESSIBILITY (11)"
        } else {
            "USAGE_MEDIA (1)"
        }
        val contentTypeLabel = "CONTENT_TYPE_SPEECH (1)"

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(contentType)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setSampleRate(decodedWav.sampleRateHz)
            .setEncoding(encoding)
            .setChannelMask(channelOut)
            .build()

        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(minBufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull()

        if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { track?.release() }
            recordAndLogTrace(
                PlaybackTraceRecord(
                    entryPoint = entryPoint,
                    selectedTtsEngine = selectedEngineName,
                    synthesizeToFileSucceeded = true,
                    pcmWavDecoded = true,
                    decodedWavFormatSummary = decodedWav.formatSummary,
                    audioTrackCreated = false,
                    actualAudioAttributesUsage = usageLabel,
                    actualAudioAttributesContentType = contentTypeLabel,
                    audioTrackPlaybackState = "STATE_UNINITIALIZED",
                    directTtsFallbackUsed = false,
                    effectivePcmGain = volumeController.getEffectiveGain(isPreviewOrTest),
                    audioTrackStreamGain = volumeController.getAudioTrackStreamGain(isPcm16, isPreviewOrTest),
                    systemMediaVolumePercent = systemMediaVolumePercent,
                    errorOrLimitation = ERROR_INDEPENDENT_PLAYBACK_UNAVAILABLE
                )
            )
            return false
        }

        RandomAccessFile(wavFile, "r").use { raf ->
            raf.seek(decodedWav.dataOffsetBytes)

            try {
                volumeController.bindActiveAudioTrack(track, isPcm16SoftwareScaled = isPcm16)
                track.play()
                val playStateAfterStart = track.playState
                val currentPcmGain = volumeController.getEffectiveGain(isPreviewOrTest)
                val currentTrackGain = volumeController.getAudioTrackStreamGain(isPcm16, isPreviewOrTest)

                _playbackStateLabel.value =
                    "Playing AudioTrack PCM (${(currentPcmGain * 100).roundToInt()}% Gain • $usageLabel)"

                recordAndLogTrace(
                    PlaybackTraceRecord(
                        entryPoint = entryPoint,
                        selectedTtsEngine = selectedEngineName,
                        synthesizeToFileSucceeded = true,
                        pcmWavDecoded = true,
                        decodedWavFormatSummary = decodedWav.formatSummary,
                        audioTrackCreated = true,
                        actualAudioAttributesUsage = usageLabel,
                        actualAudioAttributesContentType = contentTypeLabel,
                        audioTrackPlaybackState = "PLAYSTATE_PLAYING ($playStateAfterStart)",
                        directTtsFallbackUsed = false,
                        effectivePcmGain = currentPcmGain,
                        audioTrackStreamGain = currentTrackGain,
                        systemMediaVolumePercent = systemMediaVolumePercent,
                        errorOrLimitation = "None"
                    )
                )

                val chunkBytes = ByteArray(2048)
                var remainingDataBytes = decodedWav.dataSizeBytes
                var totalBytesWritten = 0L

                while (!isCancelled && remainingDataBytes > 0L) {
                    val toRead = minOf(chunkBytes.size.toLong(), remainingDataBytes).toInt()
                    val read = raf.read(chunkBytes, 0, toRead)
                    if (read <= 0) break
                    remainingDataBytes -= read

                    val liveGain = volumeController.getEffectiveGain(isPreviewOrTest)
                    if (isPcm16) {
                        // Single-stage gain: scale 16-bit PCM samples once in software; keep AudioTrack at 1.0f unity
                        track.setVolume(1.0f)
                        volumeController.applyPcm16VolumeInPlace(chunkBytes, read, liveGain)
                    } else {
                        track.setVolume(liveGain)
                    }

                    val written = track.write(chunkBytes, 0, read)
                    if (written > 0) {
                        totalBytesWritten += written
                    }
                }

                // Ensure AudioTrack in MODE_STREAM always crosses minBufSize start threshold
                if (!isCancelled && totalBytesWritten in 1 until minBufSize.toLong()) {
                    val padSize = (minBufSize - totalBytesWritten.toInt()).coerceAtLeast(0)
                    if (padSize > 0) {
                        val silencePad = ByteArray(padSize)
                        track.write(silencePad, 0, padSize)
                        totalBytesWritten += padSize
                    }
                }

                // Wait for the hardware playback head to finish draining queued PCM frames before releasing
                if (!isCancelled && totalBytesWritten > 0L) {
                    val bytesPerFrame = (decodedWav.channels * (decodedWav.bitsPerSample / 8)).coerceAtLeast(2)
                    val totalFrames = (totalBytesWritten / bytesPerFrame).toInt()
                    val expectedDurationMs = ((totalFrames.toLong() * 1000L) / decodedWav.sampleRateHz).coerceIn(50L, 15_000L)
                    val startTime = System.currentTimeMillis()
                    while (!isCancelled && (System.currentTimeMillis() - startTime) < expectedDurationMs + 250L) {
                        val head = runCatching { track.playbackHeadPosition }.getOrDefault(totalFrames)
                        if (head >= totalFrames) break
                        delay(25L)
                    }
                }

                runCatching { track.stop() }
                return true
            } finally {
                volumeController.bindActiveAudioTrack(null)
                runCatching { track.release() }
            }
        }
    }

    /**
     * Used ONLY when Independent Voice Volume is explicitly turned OFF by the user
     * and file synthesis is unavailable. Never invoked in Independent Mode.
     */
    private suspend fun playFallbackDirectTtsWhenIndependentDisabled(
        tts: TextToSpeech,
        clause: String,
        isPreviewOrTest: Boolean,
        entryPoint: String,
        selectedEngineName: String,
        systemMediaVolumePercent: Int?
    ) {
        val utteranceId = "fallback_${UUID.randomUUID()}"
        val deferred = CompletableDeferred<Boolean>()
        synthCompletionMap[utteranceId] = deferred

        val gain = volumeController.getEffectiveGain(isPreviewOrTest)
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, gain)
        }
        _playbackStateLabel.value = "Playing Fallback TTS Stream (Independent Mode OFF • ${(gain * 100).roundToInt()}%)"

        recordAndLogTrace(
            PlaybackTraceRecord(
                entryPoint = entryPoint,
                selectedTtsEngine = selectedEngineName,
                synthesizeToFileSucceeded = false,
                pcmWavDecoded = false,
                decodedWavFormatSummary = "N/A (Independent Mode Disabled)",
                audioTrackCreated = false,
                actualAudioAttributesUsage = "USAGE_MEDIA (1)",
                actualAudioAttributesContentType = "CONTENT_TYPE_SPEECH (1)",
                audioTrackPlaybackState = "DIRECT_TTS_SPEAK",
                directTtsFallbackUsed = true,
                effectivePcmGain = gain,
                audioTrackStreamGain = gain,
                systemMediaVolumePercent = systemMediaVolumePercent,
                errorOrLimitation = "Fallback mode active (Independent Mode is disabled by user)"
            )
        )

        val res = tts.speak(clause, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (res == TextToSpeech.SUCCESS) {
            withTimeoutOrNull(8_000L) { deferred.await() }
        } else {
            synthCompletionMap.remove(utteranceId)
        }
    }

    private fun recordAndLogTrace(record: PlaybackTraceRecord) {
        _lastTraceRecord.value = record
        runCatching {
            Log.i(
                TRACE_TAG,
                "EntryPoint=${record.entryPoint} | Engine=${record.selectedTtsEngine} | " +
                    "synthToFile=${record.synthesizeToFileSucceeded} | wavDecoded=${record.pcmWavDecoded} (${record.decodedWavFormatSummary}) | " +
                    "audioTrackCreated=${record.audioTrackCreated} | usage=${record.actualAudioAttributesUsage} | " +
                    "contentType=${record.actualAudioAttributesContentType} | trackState=${record.audioTrackPlaybackState} | " +
                    "directTtsFallbackUsed=${record.directTtsFallbackUsed} | pcmGain=${record.effectivePcmGain} | " +
                    "trackStreamGain=${record.audioTrackStreamGain} | sysMediaVol=${record.systemMediaVolumePercent}% | " +
                    "note=${record.errorOrLimitation}"
            )
        }
    }
}

/**
 * 4. VoiceQueueManager
 *
 * Centralized priority queue for all Battery Boss announcements:
 * - Priority 1 (CRITICAL): Overheating, critical low battery, safety alerts
 * - Priority 2 (IMPORTANT): Charger connected, disconnected, 100% full charge
 * - Priority 3 (NORMAL): Charging percentage milestones (20%, 50%, 80%, intervals)
 *
 * Guarantees zero overlapping announcements, deduplicates identical pending alerts,
 * avoids cutting off important announcements, and honors the configured Repeat Policy.
 */
class VoiceQueueManager(
    private val onProcessItem: suspend (QueuedVoiceAnnouncement) -> Unit,
    private val onQueueStateChanged: (queueSize: Int, summary: String, currentPriority: String) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val priorityQueue = PriorityBlockingQueue<QueuedVoiceAnnouncement>()

    @Volatile
    private var currentlyPlayingItem: QueuedVoiceAnnouncement? = null

    @Volatile
    private var isProcessingLoopActive = false

    fun enqueue(
        item: QueuedVoiceAnnouncement,
        onInterruptLowerPriority: () -> Unit
    ) {
        val isDuplicatePending = priorityQueue.any {
            it.rawMessage == item.rawMessage && it.priority == item.priority
        }
        if (isDuplicatePending && !item.isPreviewOrTest) {
            return
        }

        val active = currentlyPlayingItem
        if (item.isPreviewOrTest) {
            priorityQueue.clear()
            priorityQueue.put(item)
            if (active != null) {
                onInterruptLowerPriority()
            }
        } else if (
            item.priority == AnnouncementPriority.CRITICAL &&
            active != null &&
            active.priority == AnnouncementPriority.NORMAL
        ) {
            priorityQueue.put(item)
            onInterruptLowerPriority()
        } else {
            priorityQueue.put(item)
        }

        notifyState()
        ensureLoopRunning()
    }

    fun cancelAll(onStopCurrentPlayback: () -> Unit) {
        priorityQueue.clear()
        currentlyPlayingItem = null
        onStopCurrentPlayback()
        notifyState()
    }

    private fun ensureLoopRunning() {
        synchronized(this) {
            if (isProcessingLoopActive) return
            isProcessingLoopActive = true
        }

        scope.launch {
            try {
                while (isActive) {
                    val next = priorityQueue.poll() ?: break
                    currentlyPlayingItem = next
                    notifyState()

                    onProcessItem(next)

                    if (next.repeatCountRemaining > 0) {
                        delay(450L)
                        val repeated = next.copy(
                            id = UUID.randomUUID().toString(),
                            repeatCountRemaining = next.repeatCountRemaining - 1
                        )
                        currentlyPlayingItem = repeated
                        notifyState()
                        onProcessItem(repeated)
                    }

                    currentlyPlayingItem = null
                    notifyState()
                }
            } finally {
                synchronized(this@VoiceQueueManager) {
                    isProcessingLoopActive = false
                }
                currentlyPlayingItem = null
                notifyState()
                if (priorityQueue.isNotEmpty()) {
                    ensureLoopRunning()
                }
            }
        }
    }

    private fun notifyState() {
        val active = currentlyPlayingItem
        val pendingCount = priorityQueue.size
        val summary = when {
            active != null && pendingCount > 0 ->
                "Speaking (${active.eventType}) • $pendingCount queued"
            active != null ->
                "Speaking (${active.eventType}) • 0 pending"
            pendingCount > 0 ->
                "$pendingCount announcement(s) queued"
            else ->
                "Queue Empty (0 pending)"
        }
        val priLabel = active?.priority?.badgeText
            ?: priorityQueue.peek()?.priority?.badgeText
            ?: "None"
        onQueueStateChanged(pendingCount, summary, priLabel)
    }
}
