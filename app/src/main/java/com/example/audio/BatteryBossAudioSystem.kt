package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.model.AnnouncementPriority
import com.example.model.AnnouncementRepeatPolicy
import com.example.model.MediaDuckingLevel
import com.example.model.VoiceLanguage
import com.example.model.VoiceProfilePreset
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 1. IndependentVoiceController
 *
 * Manages Battery Boss's independent application-level voice volume (0.0f..1.0f, default 1.0f / 100%).
 * Applies gain in a single stage (16-bit PCM sample scaling with AudioTrack at 1.0f unity gain)
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
 * Manages intelligent Android Audio Focus, Media Ducking (OFF, LOW, MEDIUM, HIGH),
 * and phone call / alarm interruption detection.
 */
class AudioFocusController(
    context: Context,
    private val onInterruptionDetected: (reason: String, shouldStopImmediately: Boolean) -> Unit
) {
    private val audioManager: AudioManager? =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var activeFocusRequest: AudioFocusRequest? = null

    private val _audioFocusStateText = MutableStateFlow("Idle (No Active Focus Request)")
    val audioFocusStateText: StateFlow<String> = _audioFocusStateText.asStateFlow()

    private val _callOrAlarmStateText = MutableStateFlow("Normal (No Call/Alarm Active)")
    val callOrAlarmStateText: StateFlow<String> = _callOrAlarmStateText.asStateFlow()

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                _audioFocusStateText.value = "Lost Permanent Focus (Interrupted)"
                onInterruptionDetected("AudioFocus Loss", true)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                _audioFocusStateText.value = "Lost Transient Focus (Call / Alarm Active)"
                _callOrAlarmStateText.value = "Interrupted by Call or Alarm"
                onInterruptionDetected("Incoming Call or Alarm Focus Loss", true)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                _audioFocusStateText.value = "Transient Ducking Requested by System"
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                _audioFocusStateText.value = "Audio Focus Active"
                _callOrAlarmStateText.value = "Normal (No Call/Alarm Active)"
            }
        }
    }

    /**
     * Checks if a phone call or ringtone is currently active via AudioManager.mode
     * without requiring sensitive READ_PHONE_STATE permissions.
     */
    fun isPhoneCallOrRingtoneActive(): Boolean {
        val mode = audioManager?.mode ?: return false
        val inCall = mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_RINGTONE ||
            mode == AudioManager.MODE_CALL_SCREENING
        _callOrAlarmStateText.value = if (inCall) {
            "Phone Call / Ringtone Active (Speech Paused)"
        } else {
            "Normal (No Call/Alarm Active)"
        }
        return inCall
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
            _audioFocusStateText.value = "Media Ducking OFF (No Focus Requested)"
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
            AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
        }

        val attributes = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        return runCatching {
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(focusGainType)
                    .setAudioAttributes(attributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setWillPauseWhenDucked(duckingLevel == MediaDuckingLevel.HIGH)
                    .setOnAudioFocusChangeListener(focusChangeListener)
                    .build()
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
                "Granted • Ducking: ${duckingLevel.label}"
            } else {
                "Denied by System (Alarm / Call Priority)"
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

            // Prioritize active external routes (Bluetooth, Wired Headset, USB) over built-in speaker
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

            val typeLabel = when (preferred.type) {
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
            typeLabel
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
        return if (priorityDiff != 0) priorityDiff else this.sequenceNumber.compareTo(other.sequenceNumber)
    }

    companion object {
        private val sequenceGenerator = AtomicLong(0L)
    }
}

/**
 * 3. VoicePlaybackEngine
 *
 * Implements the reliable 5-step independent audio pipeline:
 * 1. Generates speech using the selected TTS engine (`synthesizeToFile`).
 * 2. Parses the generated 16-bit PCM WAV audio data.
 * 3. Plays the audio through a dedicated `AudioTrack` (`USAGE_ASSISTANCE_ACCESSIBILITY` in
 *    independent mode so Media, Alarm, Ringtone, and Notification sliders do not alter it).
 * 4. Applies `IndependentVoiceController` volume gain (0.0..1.0) in real time per 40ms frame.
 * 5. Falls back transparently to direct `TextToSpeech.speak()` if an OEM TTS engine does not
 *    support file synthesis, clearly reporting the active engine in Audio Diagnostics.
 */
class VoicePlaybackEngine(
    private val context: Context,
    private val volumeController: IndependentVoiceController
) {
    @Volatile
    private var isCancelled = false

    private val _playbackEngineLabel = MutableStateFlow("SynthesizeToFile -> AudioTrack PCM (Independent Gain)")
    val playbackEngineLabel: StateFlow<String> = _playbackEngineLabel.asStateFlow()

    private val _playbackStateLabel = MutableStateFlow("Idle")
    val playbackStateLabel: StateFlow<String> = _playbackStateLabel.asStateFlow()

    private val _lastErrorText = MutableStateFlow("None")
    val lastErrorText: StateFlow<String> = _lastErrorText.asStateFlow()

    private val synthCompletionMap = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

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
        naturalProsodyEnabled: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        isCancelled = false
        val cacheDir = File(context.cacheDir, "tts_independent_wav").apply {
            if (!exists()) mkdirs()
        }

        val independentEnabled = volumeController.independentModeEnabled.value

        for ((index, clause) in item.clauses.withIndex()) {
            if (isCancelled || !isActive) return@withContext false

            val utteranceId = "synth_${UUID.randomUUID()}"
            val wavFile = File(cacheDir, "$utteranceId.wav")

            try {
                _playbackStateLabel.value = "Synthesizing Neural/Offline Audio..."
                val deferred = CompletableDeferred<Boolean>()
                synthCompletionMap[utteranceId] = deferred

                val synthResult = tts.synthesizeToFile(clause, Bundle(), wavFile, utteranceId)
                val fileReady = if (synthResult == TextToSpeech.SUCCESS) {
                    withTimeoutOrNull(6_000L) { deferred.await() } == true &&
                        wavFile.exists() && wavFile.length() > 64L
                } else {
                    synthCompletionMap.remove(utteranceId)
                    false
                }

                if (isCancelled) return@withContext false

                if (fileReady) {
                    _playbackEngineLabel.value = if (independentEnabled) {
                        "SynthesizeToFile -> AudioTrack PCM (Independent Gain)"
                    } else {
                        "SynthesizeToFile -> AudioTrack PCM (System Media Stream)"
                    }
                    playWavFileThroughAudioTrack(
                        wavFile = wavFile,
                        independentModeEnabled = independentEnabled,
                        isPreviewOrTest = item.isPreviewOrTest
                    )
                } else {
                    // Documented fallback when an OEM TTS engine or JVM test stub does not write WAV files
                    _playbackEngineLabel.value = "Fallback Direct TTS Stream (KEY_PARAM_VOLUME)"
                    playFallbackDirectTts(
                        tts = tts,
                        clause = clause,
                        independentModeEnabled = independentEnabled,
                        isPreviewOrTest = item.isPreviewOrTest
                    )
                }

                if (naturalProsodyEnabled && index < item.clauses.lastIndex && !isCancelled) {
                     kotlinx.coroutines.delay(200L)
                }
            } catch (t: Throwable) {
                _lastErrorText.value = t.localizedMessage ?: "AudioTrack playback exception"
                return@withContext false
            } finally {
                runCatching { if (wavFile.exists()) wavFile.delete() }
            }
        }

        _playbackStateLabel.value = "Idle"
        true
    }

    private fun playWavFileThroughAudioTrack(
        wavFile: File,
        independentModeEnabled: Boolean,
        isPreviewOrTest: Boolean
    ) {
        RandomAccessFile(wavFile, "r").use { raf ->
            if (raf.length() <= 44L) return

            // Parse RIFF WAV header to discover exact sampleRate, channels, and data chunk offset
            val headerBytes = ByteArray(44)
            raf.readFully(headerBytes)
            val headerBuf = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)

            val channels = headerBuf.getShort(22).toInt().coerceIn(1, 2)
            val sampleRate = headerBuf.getInt(24).coerceIn(8000, 48000)
            val bitsPerSample = headerBuf.getShort(34).toInt()

            // Locate "data" subchunk in case extended chunks precede it
            var dataOffset = 44L
            raf.seek(12L)
            val chunkHeader = ByteArray(8)
            while (raf.filePointer + 8 <= raf.length()) {
                raf.readFully(chunkHeader)
                val chunkId = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                val chunkSize = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                if (chunkId == "data") {
                    dataOffset = raf.filePointer
                    break
                } else {
                    val nextPos = raf.filePointer + chunkSize + (chunkSize % 2)
                    if (nextPos >= raf.length()) break
                    raf.seek(nextPos)
                }
            }
            raf.seek(dataOffset)

            val channelOut = if (channels == 2) {
                AudioFormat.CHANNEL_OUT_STEREO
            } else {
                AudioFormat.CHANNEL_OUT_MONO
            }
            val encoding = if (bitsPerSample == 8) {
                AudioFormat.ENCODING_PCM_8BIT
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }

            val minBufSize = AudioTrack.getMinBufferSize(sampleRate, channelOut, encoding)
                .coerceAtLeast(4096)

            // In Independent Mode, route via USAGE_ASSISTANCE_ACCESSIBILITY so changing
            // Media, Alarm, Ringtone, or Notification sliders does not alter Battery Boss volume.
            val usage = if (independentModeEnabled) {
                AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
            } else {
                AudioAttributes.USAGE_MEDIA
            }

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(encoding)
                .setChannelMask(channelOut)
                .build()

            val track = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(minBufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            val isPcm16 = encoding == AudioFormat.ENCODING_PCM_16BIT

            try {
                volumeController.bindActiveAudioTrack(track, isPcm16SoftwareScaled = isPcm16)
                track.play()
                _playbackStateLabel.value = "Playing Independent PCM Audio (${(volumeController.getEffectiveGain(isPreviewOrTest) * 100).roundToInt()}%)"

                // Stream in ~40ms chunks so live volume changes apply instantaneously.
                // Single-stage gain: 16-bit PCM scales samples in software while AudioTrack stays at 1.0f unity gain.
                val chunkBytes = ByteArray(2048)
                while (!isCancelled) {
                    val read = raf.read(chunkBytes)
                    if (read <= 0) break

                    val currentGain = volumeController.getEffectiveGain(isPreviewOrTest)
                    if (isPcm16) {
                        track.setVolume(1.0f)
                        volumeController.applyPcm16VolumeInPlace(chunkBytes, read, currentGain)
                    } else {
                        track.setVolume(currentGain)
                    }
                    track.write(chunkBytes, 0, read)
                }

                track.stop()
            } finally {
                volumeController.bindActiveAudioTrack(null)
                runCatching { track.release() }
            }
        }
    }

    private suspend fun playFallbackDirectTts(
        tts: TextToSpeech,
        clause: String,
        independentModeEnabled: Boolean,
        isPreviewOrTest: Boolean
    ) {
        val utteranceId = "fallback_${UUID.randomUUID()}"
        val deferred = CompletableDeferred<Boolean>()
        synthCompletionMap[utteranceId] = deferred

        val gain = volumeController.getEffectiveGain(isPreviewOrTest)
        val usage = if (independentModeEnabled) {
            AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
        } else {
            AudioAttributes.USAGE_MEDIA
        }
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, gain)
        }
        _playbackStateLabel.value = "Playing Fallback TTS Stream (${(gain * 100).roundToInt()}%)"
        val res = tts.speak(clause, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (res == TextToSpeech.SUCCESS) {
            withTimeoutOrNull(6_000L) { deferred.await() }
        } else {
            synthCompletionMap.remove(utteranceId)
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
        // Avoid queuing duplicate identical messages already waiting in the queue
        val isDuplicatePending = priorityQueue.any {
            it.rawMessage == item.rawMessage && it.priority == item.priority
        }
        if (isDuplicatePending && !item.isPreviewOrTest) {
            return
        }

        // If user tapped Preview/Test or a Priority 1 CRITICAL alert arrives while a
        // Priority 3 NORMAL milestone is playing, interrupt the lower-priority announcement.
        val active = currentlyPlayingItem
        if (item.isPreviewOrTest) {
            priorityQueue.clear()
            priorityQueue.put(item)
            if (active != null) {
                onInterruptLowerPriority()
            }
        } else if (item.priority == AnnouncementPriority.CRITICAL &&
            active != null &&
            active.priority == AnnouncementPriority.NORMAL
        ) {
            priorityQueue.put(item)
            onInterruptLowerPriority()
        } else {
            priorityQueue.put(item)
        }

        publishQueueState()
        ensureWorkerRunning()
    }

    fun cancelAll(onStopEngine: () -> Unit) {
        priorityQueue.clear()
        currentlyPlayingItem = null
        onStopEngine()
        publishQueueState()
    }

    private fun ensureWorkerRunning() {
        if (isProcessingLoopActive) return
        synchronized(this) {
            if (isProcessingLoopActive) return
            isProcessingLoopActive = true
        }
        scope.launch {
            try {
                while (true) {
                    val next = priorityQueue.poll() ?: break
                    currentlyPlayingItem = next
                    publishQueueState()

                    onProcessItem(next)

                    // Handle repeat policy for Priority 1 Critical warnings
                    if (next.repeatCountRemaining > 0 && currentlyPlayingItem != null) {
                        kotlinx.coroutines.delay(450L)
                        priorityQueue.put(next.copy(repeatCountRemaining = next.repeatCountRemaining - 1))
                    }
                    currentlyPlayingItem = null
                    publishQueueState()
                }
            } finally {
                isProcessingLoopActive = false
                currentlyPlayingItem = null
                publishQueueState()
            }
        }
    }

    private fun publishQueueState() {
        val pendingCount = priorityQueue.size
        val active = currentlyPlayingItem
        val currentPri = active?.priority?.badgeText ?: "None"
        val summary = when {
            active != null && pendingCount > 0 ->
                "Speaking [${active.eventType}] • $pendingCount queued"
            active != null ->
                "Speaking [${active.eventType}] • 0 queued"
            pendingCount > 0 ->
                "$pendingCount queued (Next: ${priorityQueue.peek()?.priority?.badgeText ?: ""})"
            else ->
                "Queue Empty (0 pending)"
        }
        onQueueStateChanged(pendingCount, summary, currentPri)
    }
}
