package com.nexusfuturistica.screenrecordpro

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
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
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.widget.TextView
import java.io.FileNotFoundException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class ScreenRecordService : Service() {
    private val stopping = AtomicBoolean(false)
    @Volatile private var recording = false
    @Volatile private var sessionActive = false
    @Volatile private var closeAfterStop = false
    private var captureMode = 2
    private var worker: Thread? = null
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var videoInputSurface: android.view.Surface? = null
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
    private var windowManager: WindowManager? = null
    private var floatingView: TextView? = null
    private var floatingParams: WindowManager.LayoutParams? = null
    private var removeTargetView: TextView? = null
    private var removeTargetParams: WindowManager.LayoutParams? = null
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchOriginX = 0
    private var touchOriginY = 0
    private var dragging = false
    private var recordingStartedElapsed = 0L
    private val overlayHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val overlayRefresh = object : Runnable {
        override fun run() {
            if (!sessionActive) return
            refreshFloatingControl()
            overlayHandler.postDelayed(this, 1_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopCapture(); return START_NOT_STICKY }
            ACTION_TOGGLE -> { toggleRecording(); return START_NOT_STICKY }
            ACTION_CLOSE -> { closeSession(); return START_NOT_STICKY }
            ACTION_START_SESSION -> if (sessionActive) return START_NOT_STICKY
            else -> return START_NOT_STICKY
        }
        try {
            createNotificationChannel()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification("Preparando controles flutuantes…"), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIFICATION_ID, notification("Preparando controles flutuantes…"))
            }
            val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java) else @Suppress("DEPRECATION") (intent.getParcelableExtra(EXTRA_DATA) as? Intent)
            if (code != Activity.RESULT_OK || data == null) error("A autorização de captura não foi recebida")
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(code, data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    if (recording) {
                        closeAfterStop = true
                        stopCapture()
                    } else closeSession()
                }
            }, overlayHandler)
            captureMode = intent.getIntExtra(EXTRA_MODE, 2)
            configureDimensions(captureMode)
            getSharedPreferences("recorder", MODE_PRIVATE).edit().remove("error").apply()
            sessionActive = true
            stopping.set(false)
            closeAfterStop = false
            getSharedPreferences("recorder", MODE_PRIVATE).edit().putBoolean("session", true).putBoolean("running", false).putLong("started", 0L).apply()
            showFloatingControls()
            updateNotification("Controle pronto • toque no botão flutuante para gravar")
        } catch (t: Throwable) {
            Log.e(TAG, "Falha ao ativar controles flutuantes", t)
            val reason = t.localizedMessage ?: t.javaClass.simpleName
            getSharedPreferences("recorder", MODE_PRIVATE).edit().putString("error", reason).apply()
            closeSession()
        }
        return START_NOT_STICKY
    }

    private fun toggleRecording() {
        if (!sessionActive || stopping.get()) return
        if (recording) stopCapture() else startCapture()
    }

    private fun startCapture() {
        if (!sessionActive || recording || stopping.get()) return
        try {
            stopping.set(false)
            closeAfterStop = false
            finalized.set(false)
            videoDrainDone = false
            audioDrainDone = false
            videoTrack = -1
            audioTrack = -1
            muxerStarted = false
            worker = null
            audioWorker = null
            getSharedPreferences("recorder", MODE_PRIVATE).edit().remove("error").apply()
            openOutput()
            prepareEncoder()
            prepareAudioCapture()
            val surface = videoInputSurface ?: error("Superfície do codificador não foi preparada")
            if (display == null) {
                val density = resources.displayMetrics.densityDpi
                display = projection?.createVirtualDisplay(
                    "NexusScreenRecordPro",
                    displayWidth,
                    displayHeight,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface,
                    null,
                    null
                ) ?: error("Android não criou o display virtual")
            } else {
                display?.setSurface(surface)
            }
            audioRecord?.startRecording()
            if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) error("A captura de áudio interno não iniciou")
            recording = true
            recordingStartedElapsed = SystemClock.elapsedRealtime()
            getSharedPreferences("recorder", MODE_PRIVATE).edit().putBoolean("running", true).putLong("started", System.currentTimeMillis()).apply()
            worker = Thread({ drainEncoder() }, "NexusRecorderEncoder").apply { start() }
            audioWorker = Thread({ captureAndDrainAudio() }, "NexusPlaybackAudio").apply { start() }
            updateNotification("Tela e áudio interno em gravação")
            refreshFloatingControl()
        } catch (t: Throwable) {
            Log.e(TAG, "Falha ao iniciar gravação", t)
            getSharedPreferences("recorder", MODE_PRIVATE).edit()
                .putString("error", t.localizedMessage ?: t.javaClass.simpleName)
                .putBoolean("running", false)
                .putLong("started", 0L)
                .apply()
            recording = false
            stopping.set(true)
            videoDrainDone = true
            audioDrainDone = true
            finishCapture()
        }
    }

    private fun configureDimensions(mode: Int) {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = (getSystemService(WINDOW_SERVICE) as WindowManager).currentWindowMetrics.bounds
            metrics.widthPixels = bounds.width(); metrics.heightPixels = bounds.height(); metrics.densityDpi = resources.displayMetrics.densityDpi
        } else (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val sourceW = metrics.widthPixels; val sourceH = metrics.heightPixels
        // Choose by the screen's short edge. Using the long edge here reduced the A13
        // recording to 322x720 and made the saved video look blurred.
        // A13 produced large frame gaps at its full 1080x2408 panel size. Keep
        // the largest profile at a 720px short edge so the hardware encoder can
        // sustain a smoother 30 FPS capture while retaining a clear portrait image.
        val targetShortEdge = when (mode) { 0 -> 540; 1 -> 640; else -> 720 }
        val scale = minOf(1f, targetShortEdge.toFloat() / minOf(sourceW, sourceH).coerceAtLeast(1))
        displayWidth = even((sourceW * scale).toInt()); displayHeight = even((sourceH * scale).toInt())
        bitRate = when (mode) { 0 -> 2_500_000; 1 -> 3_800_000; else -> 5_500_000 }
    }

    private fun prepareEncoder() {
        val encoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            info.isEncoder && info.isHardwareAccelerated && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } &&
                runCatching { info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) }.getOrDefault(false)
        }.sortedByDescending { it.isHardwareAccelerated }
        val targetWidth = displayWidth
        val targetHeight = displayHeight
        var selected: MediaCodecInfo? = null
        var selectedVideo: MediaCodecInfo.VideoCapabilities? = null
        for (info in encoders) {
            val caps = runCatching { info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC) }.getOrNull() ?: continue
            val video = caps.videoCapabilities ?: continue
            var scale = 1.0f
            while (scale >= 0.45f) {
                val candidateWidth = alignDown((targetWidth * scale).toInt(), video.widthAlignment)
                val candidateHeight = alignDown((targetHeight * scale).toInt(), video.heightAlignment)
                if (candidateWidth < 320 || candidateHeight < 320) break
                val supported = runCatching { video.areSizeAndRateSupported(candidateWidth, candidateHeight, 30.0) }.getOrDefault(false)
                if (supported) {
                    selected = info
                    selectedVideo = video
                    displayWidth = candidateWidth
                    displayHeight = candidateHeight
                    break
                }
                scale -= 0.01f
            }
            if (selected != null) break
        }
        val codecInfo = selected ?: error("Este aparelho não oferece codificador AVC por hardware compatível com a gravação a 30 FPS")
        val videoCaps = selectedVideo ?: error("O codificador não informou os limites de vídeo")
        bitRate = bitRate.coerceIn(videoCaps.bitrateRange.lower, videoCaps.bitrateRange.upper)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, displayWidth, displayHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate); setInteger(MediaFormat.KEY_FRAME_RATE, 30); setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            if (Build.VERSION.SDK_INT >= 23) setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        }
        encoder = MediaCodec.createByCodecName(codecInfo.name).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            videoInputSurface = createInputSurface()
            start()
        }
        getSharedPreferences("recorder", MODE_PRIVATE).edit().putString("codec", codecInfo.name).putInt("width", displayWidth).putInt("height", displayHeight).apply()
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
        if (!recording) return
        if (!stopping.compareAndSet(false, true)) return
        try { if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) audioRecord?.stop() } catch (_: Exception) { }
        try { encoder?.signalEndOfInputStream() } catch (_: Exception) { }
        if (worker == null) videoDrainDone = true
        if (audioWorker == null) audioDrainDone = true
        finishWhenDrained()
    }

    @Synchronized private fun finishCapture() {
        if (!finalized.compareAndSet(false, true)) return
        stopping.set(true); recording = false
        try { display?.setSurface(null) } catch (_: Exception) { }
        try { encoder?.stop() } catch (_: Exception) { }
        try { encoder?.release() } catch (_: Exception) { }; encoder = null
        try { videoInputSurface?.release() } catch (_: Exception) { }; videoInputSurface = null
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
        outputUri?.let { uri ->
            if (validMp4 && getSharedPreferences("recorder", MODE_PRIVATE).getString("error", null) == null) {
                val values = android.content.ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }; contentResolver.update(uri, values, null, null)
            } else contentResolver.delete(uri, null, null)
        }
        outputUri = null; videoTrack = -1; audioTrack = -1
        worker = null; audioWorker = null
        getSharedPreferences("recorder", MODE_PRIVATE).edit().putBoolean("running", false).putLong("started", 0).apply()
        overlayHandler.post { refreshFloatingControl() }
        if (closeAfterStop) {
            closeAfterStop = false
            closeSession()
        } else if (sessionActive) {
            stopping.set(false)
            updateNotification("Controle pronto • toque no botão flutuante para gravar")
        }
    }

    private fun closeSession() {
        if (recording) {
            closeAfterStop = true
            stopCapture()
            return
        }
        if (!sessionActive && projection == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        sessionActive = false
        getSharedPreferences("recorder", MODE_PRIVATE).edit()
            .putBoolean("session", false).putBoolean("running", false).putLong("started", 0L).apply()
        removeFloatingControls()
        try { display?.release() } catch (_: Exception) { }
        display = null
        val oldProjection = projection
        projection = null
        try { oldProjection?.stop() } catch (_: Exception) { }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (recording) {
            closeAfterStop = true
            stopCapture()
        } else if (sessionActive || projection != null) closeSession()
        super.onDestroy()
    }

    private fun showFloatingControls() {
        if (!Settings.canDrawOverlays(this)) error("Permita que o Nexus apareça sobre outros apps para usar o botão flutuante")
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val bubble = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(10), dp(8), dp(10), dp(8))
            minWidth = dp(66)
            minHeight = dp(58)
            elevation = dp(8).toFloat()
            contentDescription = "Controle flutuante do gravador. Toque para iniciar ou parar; arraste para mover."
            background = bubbleBackground()
            setOnClickListener { toggleRecording() }
            setOnTouchListener { view, event ->
                val params = floatingParams ?: return@setOnTouchListener false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        touchDownX = event.rawX
                        touchDownY = event.rawY
                        touchOriginX = params.x
                        touchOriginY = params.y
                        dragging = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - touchDownX).toInt()
                        val dy = (event.rawY - touchDownY).toInt()
                        if (!dragging && (kotlin.math.abs(dx) > dp(7) || kotlin.math.abs(dy) > dp(7))) {
                            dragging = true
                            showRemoveTarget()
                        }
                        if (dragging) {
                            params.x = (touchOriginX + dx).coerceAtLeast(0)
                            params.y = (touchOriginY + dy).coerceAtLeast(dp(24))
                            runCatching { wm.updateViewLayout(view, params) }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (dragging) {
                            val remove = isOverRemoveTarget()
                            hideRemoveTarget()
                            dragging = false
                            if (remove) closeSession() else {
                                getSharedPreferences("recorder", MODE_PRIVATE).edit()
                                    .putInt("overlay_x", params.x).putInt("overlay_y", params.y).apply()
                            }
                        } else view.performClick()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        hideRemoveTarget()
                        dragging = false
                        true
                    }
                    else -> true
                }
            }
        }
        val overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val windowFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_SECURE
        val params = WindowManager.LayoutParams(dp(76), dp(62), overlayType, windowFlags, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            val prefs = getSharedPreferences("recorder", MODE_PRIVATE)
            x = prefs.getInt("overlay_x", (resources.displayMetrics.widthPixels - dp(92)).coerceAtLeast(dp(8)))
            y = prefs.getInt("overlay_y", dp(190))
        }
        try {
            wm.addView(bubble, params)
            floatingView = bubble
            floatingParams = params
            refreshFloatingControl()
            overlayHandler.removeCallbacks(overlayRefresh)
            overlayHandler.post(overlayRefresh)
        } catch (t: Throwable) {
            floatingView = null
            floatingParams = null
            throw IllegalStateException("Não consegui mostrar o botão flutuante: ${t.localizedMessage ?: t.javaClass.simpleName}", t)
        }
    }

    private fun bubbleBackground(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(24).toFloat()
            setColor(if (recording) 0xFF9C1830.toInt() else 0xFF10283A.toInt())
            setStroke(dp(2), if (recording) 0xFFFF526B.toInt() else 0xFF39D9FF.toInt())
        }

    private fun refreshFloatingControl() {
        val bubble = floatingView ?: return
        val elapsed = if (recording) (SystemClock.elapsedRealtime() - recordingStartedElapsed).coerceAtLeast(0) / 1000 else 0
        val clock = String.format(Locale.US, "%02d:%02d", elapsed / 60, elapsed % 60)
        bubble.text = if (recording) "■\n$clock" else "●\nGRAVAR"
        bubble.background = bubbleBackground()
        bubble.contentDescription = if (recording) "Parar gravação. Tempo $clock" else "Começar gravação de tela"
    }

    private fun showRemoveTarget() {
        if (removeTargetView != null) return
        val wm = windowManager ?: return
        val target = TextView(this).apply {
            text = "✕\nSolte para fechar"
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(0xFFB3263E.toInt())
                setStroke(dp(2), 0xFFFFD8DE.toInt())
            }
        }
        val targetWidth = dp(124)
        val targetHeight = dp(76)
        val (screenWidth, screenHeight) = screenSize()
        val params = WindowManager.LayoutParams(
            targetWidth,
            targetHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ((screenWidth - targetWidth) / 2).coerceAtLeast(0)
            y = (screenHeight - targetHeight - dp(96)).coerceAtLeast(dp(24))
        }
        runCatching { wm.addView(target, params) }
            .onSuccess { removeTargetView = target; removeTargetParams = params }
    }

    private fun isOverRemoveTarget(): Boolean {
        val bubble = floatingView ?: return false
        val bubbleParams = floatingParams ?: return false
        val target = removeTargetView ?: return false
        val targetParams = removeTargetParams ?: return false
        val centerX = bubbleParams.x + bubble.width / 2
        val centerY = bubbleParams.y + bubble.height / 2
        return centerX in targetParams.x..(targetParams.x + target.width) &&
            centerY in targetParams.y..(targetParams.y + target.height)
    }

    private fun hideRemoveTarget() {
        val target = removeTargetView ?: return
        runCatching { windowManager?.removeView(target) }
        removeTargetView = null
        removeTargetParams = null
    }

    private fun removeFloatingControls() {
        overlayHandler.removeCallbacks(overlayRefresh)
        hideRemoveTarget()
        floatingView?.let { view -> runCatching { windowManager?.removeView(view) } }
        floatingView = null
        floatingParams = null
        windowManager = null
    }

    private fun screenSize(): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = (getSystemService(WINDOW_SERVICE) as WindowManager).currentWindowMetrics.bounds
            return bounds.width() to bounds.height()
        }
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL_ID, "Gravação de tela", NotificationManager.IMPORTANCE_LOW))
    }
    private fun notification(text: String): Notification {
        val stopAction = if (recording) ACTION_STOP else ACTION_CLOSE
        val stopLabel = if (recording) "Parar gravação" else "Desativar controle"
        val stop = Intent(this, ScreenRecordService::class.java).setAction(stopAction)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val pending = PendingIntent.getService(this, 5, stop, flags)
        val openApp = PendingIntent.getActivity(this, 6, Intent(this, MainActivity::class.java), flags)
        val started = getSharedPreferences("recorder", MODE_PRIVATE).getLong("started", 0L)
        return Notification.Builder(this, if (Build.VERSION.SDK_INT >= 26) CHANNEL_ID else "")
            .setSmallIcon(R.drawable.ic_recording_dot)
            .setContentTitle(if (recording) "● NEXUS SCREEN RECORD PRO" else "NEXUS SCREEN RECORD PRO")
            .setContentText(text)
            .setWhen(if (started > 0L) started else System.currentTimeMillis())
            .setUsesChronometer(recording && started > 0L)
            .setShowWhen(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, stopLabel, pending).build())
            .build()
    }
    private fun updateNotification(text: String) { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification(text)) }
    private fun even(value: Int) = (value.coerceAtLeast(2) / 2) * 2
    private fun alignDown(value: Int, alignment: Int): Int {
        val safeAlignment = alignment.coerceAtLeast(2)
        return (value.coerceAtLeast(safeAlignment) / safeAlignment) * safeAlignment
    }

    companion object {
        const val ACTION_START_SESSION = "com.nexusfuturistica.screenrecordpro.START_SESSION"
        const val ACTION_STOP = "com.nexusfuturistica.screenrecordpro.STOP"
        const val ACTION_TOGGLE = "com.nexusfuturistica.screenrecordpro.TOGGLE"
        const val ACTION_CLOSE = "com.nexusfuturistica.screenrecordpro.CLOSE"
        const val EXTRA_RESULT_CODE = "result_code"; const val EXTRA_DATA = "projection_data"; const val EXTRA_MODE = "mode"
        private const val AUDIO_SAMPLE_RATE = 48_000
        private const val AUDIO_CHANNELS = 2
        private const val AUDIO_BIT_RATE = 128_000
        private const val AUDIO_BUFFER_BYTES = 16_384
        private const val AUDIO_CODEC_TIMEOUT_US = 10_000L
        private const val CHANNEL_ID = "nexus_recording"; private const val NOTIFICATION_ID = 401; private const val TAG = "NexusRecorder"
    }
}
