package com.nexusfuturistica.screenrecordpro

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.AudioPlaybackCaptureConfiguration
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.io.FileNotFoundException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class ScreenRecordService : Service() {
    private val stopping = AtomicBoolean(false)
    @Volatile private var recording = false
    private var worker: Thread? = null
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var outputPfd: android.os.ParcelFileDescriptor? = null
    private var outputUri: android.net.Uri? = null
    @Volatile private var muxerStarted = false
    private var videoTrack = -1
    private var audioTrack = -1
    private val muxerLock = Any()
    private val finalized = AtomicBoolean(false)
    @Volatile private var videoDrainDone = false
    @Volatile private var audioDrainDone = false
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var audioEncoder: MediaCodec? = null
    private var audioWorker: Thread? = null
    private var displayWidth = 720
    private var displayHeight = 1280
    private var bitRate = 4_000_000

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopCapture(); return START_NOT_STICKY }
        if (intent?.action != ACTION_START || recording) return START_NOT_STICKY
        try {
            createNotificationChannel()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification("Preparando captura…"), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIFICATION_ID, notification("Preparando captura…"))
            }
            val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java) else @Suppress("DEPRECATION") (intent.getParcelableExtra(EXTRA_DATA) as? Intent)
            if (code != Activity.RESULT_OK || data == null) { stopSelf(); return START_NOT_STICKY }
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(code, data)
            projection?.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopCapture() } }, android.os.Handler(mainLooper))
            val mode = intent.getIntExtra(EXTRA_MODE, 1)
            configureDimensions(mode)
            finalized.set(false); videoDrainDone = false; audioDrainDone = false
            getSharedPreferences("recorder", MODE_PRIVATE).edit().remove("error").apply()
            openOutput()
            prepareEncoder()
            prepareAudioCapture()
            val density = resources.displayMetrics.densityDpi
            display = projection?.createVirtualDisplay("NexusScreenRecordPro", displayWidth, displayHeight, density, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, encoder!!.createInputSurface(), null, null)
                ?: error("Android não criou o display virtual")
            audioRecord?.startRecording()
            recording = true; stopping.set(false)
            getSharedPreferences("recorder", MODE_PRIVATE).edit().putBoolean("running", true).putLong("started", System.currentTimeMillis()).apply()
            updateNotification("Tela e áudio interno em gravação")
            worker = Thread({ drainEncoder() }, "NexusRecorderEncoder").apply { start() }
            audioWorker = Thread({ captureAndDrainAudio() }, "NexusPlaybackAudio").apply { start() }
        } catch (t: Throwable) {
            Log.e(TAG, "Falha ao iniciar gravação", t)
            val reason = t.localizedMessage ?: t.javaClass.simpleName
            getSharedPreferences("recorder", MODE_PRIVATE).edit().putString("error", reason).apply()
            stopCapture()
        }
        return START_NOT_STICKY
    }

    private fun configureDimensions(mode: Int) {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = (getSystemService(WINDOW_SERVICE) as WindowManager).currentWindowMetrics.bounds
            metrics.widthPixels = bounds.width(); metrics.heightPixels = bounds.height(); metrics.densityDpi = resources.displayMetrics.densityDpi
        } else (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val sourceW = metrics.widthPixels; val sourceH = metrics.heightPixels
        val longEdge = when (mode) { 0 -> 720; 2 -> 1080; else -> 720 }
        val scale = minOf(1f, longEdge.toFloat() / maxOf(sourceW, sourceH))
        displayWidth = even((sourceW * scale).toInt()); displayHeight = even((sourceH * scale).toInt())
        bitRate = when (mode) { 0 -> 2_000_000; 2 -> 8_000_000; else -> 4_000_000 }
    }

    private fun prepareEncoder() {
        val encoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } &&
                runCatching { info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) }.getOrDefault(false)
        }
        var selected: MediaCodecInfo? = null
        for (info in encoders) {
            val caps = runCatching { info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC) }.getOrNull() ?: continue
            val video = caps.videoCapabilities ?: continue
            if (!video.areSizeAndRateSupported(displayWidth, displayHeight, 30.0)) continue
            selected = info
            bitRate = bitRate.coerceIn(video.bitrateRange.lower, video.bitrateRange.upper)
            break
        }
        if (selected == null && (displayWidth > 720 || displayHeight > 720)) {
            val scale = minOf(1f, 720f / maxOf(displayWidth, displayHeight))
            displayWidth = even((displayWidth * scale).toInt()); displayHeight = even((displayHeight * scale).toInt())
            bitRate = minOf(bitRate, 4_000_000)
            selected = encoders.firstOrNull { info ->
                runCatching { info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).let { it.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) && it.videoCapabilities.areSizeAndRateSupported(displayWidth, displayHeight, 30.0) } }.getOrDefault(false)
            }
            selected?.let { info ->
                val range = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities.bitrateRange
                bitRate = bitRate.coerceIn(range.lower, range.upper)
            }
        }
        selected ?: error("Este aparelho não oferece um codificador AVC compatível com a resolução de captura")
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, displayWidth, displayHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate); setInteger(MediaFormat.KEY_FRAME_RATE, 30); setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            if (Build.VERSION.SDK_INT >= 23) setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        }
        encoder = MediaCodec.createByCodecName(selected.name).apply { configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); start() }
        getSharedPreferences("recorder", MODE_PRIVATE).edit().putString("codec", selected.name).putInt("width", displayWidth).putInt("height", displayHeight).apply()
    }

    private fun prepareAudioCapture() {
        val activeProjection = projection ?: error("A autorização de captura expirou; tente novamente")
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(activeProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(AUDIO_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) error("Este aparelho não oferece captura de áudio interno compatível")
        val record = AudioRecord.Builder()
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(maxOf(minBuffer * 2, AUDIO_BUFFER_BYTES))
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            error("O Android não conseguiu preparar a captura do áudio interno")
        }
        audioRecord = record

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, AUDIO_CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AUDIO_BUFFER_BYTES)
        }
        audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
    }

    private fun captureAndDrainAudio() {
        var inputEosQueued = false
        var outputEosSeen = false
        val info = MediaCodec.BufferInfo()
        try {
            val record = audioRecord ?: error("Captura de áudio não foi preparada")
            val codec = audioEncoder ?: error("Codificador de áudio não foi preparado")
            while (!outputEosSeen) {
                if (!stopping.get() && !inputEosQueued) {
                    val inputIndex = codec.dequeueInputBuffer(AUDIO_CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex) ?: error("Buffer de áudio indisponível")
                        input.clear()
                        val read = record.read(input, input.remaining(), AudioRecord.READ_BLOCKING)
                        if (read > 0 && !stopping.get()) {
                            codec.queueInputBuffer(inputIndex, 0, read, System.nanoTime() / 1_000L, 0)
                        } else if (read < 0 && !stopping.get()) {
                            error("O Android retornou erro ao ler o áudio interno ($read)")
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, 0, System.nanoTime() / 1_000L, 0)
                        }
                    }
                } else if (!inputEosQueued) {
                    val inputIndex = codec.dequeueInputBuffer(AUDIO_CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, System.nanoTime() / 1_000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEosQueued = true
                    }
                }

                var outputIndex = codec.dequeueOutputBuffer(info, AUDIO_CODEC_TIMEOUT_US)
                while (outputIndex >= 0 || outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    when {
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> registerTrack(isAudio = true, format = codec.outputFormat)
                        outputIndex >= 0 -> {
                            val buffer = codec.getOutputBuffer(outputIndex)
                            if (buffer != null && info.size > 0) writeEncodedSample(isAudio = true, buffer = buffer, info = info)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputEosSeen = true
                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                    if (outputEosSeen) break
                    outputIndex = codec.dequeueOutputBuffer(info, 0)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Falha na captura do áudio interno", t)
            getSharedPreferences("recorder", MODE_PRIVATE).edit()
                .putString("error", "Áudio interno: ${t.localizedMessage ?: t.javaClass.simpleName}").apply()
            if (recording) stopCapture()
        } finally {
            audioDrainDone = true
            finishWhenDrained()
        }
    }

    private fun registerTrack(isAudio: Boolean, format: MediaFormat) {
        synchronized(muxerLock) {
            if (muxerStarted) error("O formato de áudio/vídeo mudou após o início do MP4")
            if (isAudio) {
                if (audioTrack < 0) audioTrack = muxer!!.addTrack(format)
            } else {
                if (videoTrack < 0) videoTrack = muxer!!.addTrack(format)
            }
            if (!muxerStarted && videoTrack >= 0 && audioTrack >= 0) {
                muxer!!.start()
                muxerStarted = true
            }
        }
    }

    private fun writeEncodedSample(isAudio: Boolean, buffer: java.nio.ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(muxerLock) {
            if (!muxerStarted || info.size <= 0) return
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            muxer!!.writeSampleData(if (isAudio) audioTrack else videoTrack, buffer, info)
        }
    }

    private fun finishWhenDrained() {
        if (videoDrainDone && audioDrainDone) finishCapture()
    }

    private fun openOutput() {
        val name = "Nexus_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4"
        val values = android.content.ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name); put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/NexusScreenRecordPro"); put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        outputUri = contentResolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw FileNotFoundException("Não foi possível criar o arquivo de vídeo")
        outputPfd = contentResolver.openFileDescriptor(outputUri!!, "w") ?: throw FileNotFoundException("Não foi possível abrir o arquivo de vídeo")
        muxer = MediaMuxer(outputPfd!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    private fun drainEncoder() {
        val info = MediaCodec.BufferInfo()
        try {
            var emptyAfterStop = 0
            while (true) {
                val codec = encoder ?: break
                val index = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (stopping.get() && ++emptyAfterStop >= 3) break
                    }
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        registerTrack(isAudio = false, format = codec.outputFormat)
                    }
                    index >= 0 -> {
                        emptyAfterStop = 0
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer == null) { codec.releaseOutputBuffer(index, false); continue }
                        if (info.size > 0) writeEncodedSample(isAudio = false, buffer = buffer, info = info)
                        codec.releaseOutputBuffer(index, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Encoder/muxer failed", t)
            getSharedPreferences("recorder", MODE_PRIVATE).edit().putString("error", t.localizedMessage ?: "Falha de codificação").apply()
            if (recording) stopCapture()
        }
        finally { videoDrainDone = true; finishWhenDrained() }
    }

    private fun stopCapture() {
        if (!stopping.compareAndSet(false, true)) return
        if (!recording) { finishCapture(); return }
        try { if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) audioRecord?.stop() } catch (_: Exception) { }
        try { encoder?.signalEndOfInputStream() } catch (_: Exception) { }
        if (worker == null && audioWorker == null) finishCapture()
    }

    @Synchronized private fun finishCapture() {
        if (!finalized.compareAndSet(false, true)) return
        stopping.set(true); recording = false
        try { display?.release() } catch (_: Exception) { }; display = null
        try { encoder?.stop() } catch (_: Exception) { }
        try { encoder?.release() } catch (_: Exception) { }; encoder = null
        try { audioRecord?.release() } catch (_: Exception) { }; audioRecord = null
        try { audioEncoder?.stop() } catch (_: Exception) { }
        try { audioEncoder?.release() } catch (_: Exception) { }; audioEncoder = null
        val validMp4 = synchronized(muxerLock) {
            val valid = muxerStarted && videoTrack >= 0 && audioTrack >= 0
            if (muxerStarted) try { muxer?.stop() } catch (_: Exception) { }
            valid
        }
        try { muxer?.release() } catch (_: Exception) { }; muxer = null; muxerStarted = false
        try { outputPfd?.close() } catch (_: Exception) { }; outputPfd = null
        try { projection?.stop() } catch (_: Exception) { }; projection = null
        outputUri?.let { uri ->
            if (validMp4 && getSharedPreferences("recorder", MODE_PRIVATE).getString("error", null) == null) {
                val values = android.content.ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }; contentResolver.update(uri, values, null, null)
            } else contentResolver.delete(uri, null, null)
        }
        outputUri = null; videoTrack = -1; audioTrack = -1
        getSharedPreferences("recorder", MODE_PRIVATE).edit().putBoolean("running", false).putLong("started", 0).apply()
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    override fun onDestroy() { if (recording) stopCapture(); super.onDestroy() }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL_ID, "Gravação de tela", NotificationManager.IMPORTANCE_LOW))
    }
    private fun notification(text: String): Notification {
        val stop = Intent(this, ScreenRecordService::class.java).setAction(ACTION_STOP)
        val pending = PendingIntent.getService(this, 5, stop, PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        return Notification.Builder(this, if (Build.VERSION.SDK_INT >= 26) CHANNEL_ID else "")
            .setSmallIcon(android.R.drawable.presence_video_online).setContentTitle("NEXUS SCREEN RECORD PRO").setContentText(text)
            .setOngoing(true).addAction(Notification.Action.Builder(null, "Parar", pending).build()).build()
    }
    private fun updateNotification(text: String) { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification(text)) }
    private fun even(value: Int) = (value.coerceAtLeast(2) / 2) * 2

    companion object {
        const val ACTION_START = "com.nexusfuturistica.screenrecordpro.START"
        const val ACTION_STOP = "com.nexusfuturistica.screenrecordpro.STOP"
        const val EXTRA_RESULT_CODE = "result_code"; const val EXTRA_DATA = "projection_data"; const val EXTRA_MODE = "mode"
        private const val AUDIO_SAMPLE_RATE = 48_000
        private const val AUDIO_CHANNELS = 2
        private const val AUDIO_BIT_RATE = 128_000
        private const val AUDIO_BUFFER_BYTES = 16_384
        private const val AUDIO_CODEC_TIMEOUT_US = 10_000L
        private const val CHANNEL_ID = "nexus_recording"; private const val NOTIFICATION_ID = 401; private const val TAG = "NexusRecorder"
    }
}
