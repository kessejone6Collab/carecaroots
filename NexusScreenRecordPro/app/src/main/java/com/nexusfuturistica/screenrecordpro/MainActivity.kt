package com.nexusfuturistica.screenrecordpro

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjectionConfig
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var state: TextView
    private lateinit var timer: TextView
    private lateinit var start: Button
    private lateinit var mode: Spinner
    private val ui = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            val prefs = getSharedPreferences("recorder", MODE_PRIVATE)
            val running = prefs.getBoolean("running", false)
            val session = prefs.getBoolean("session", false)
            start.text = when {
                running -> "■  PARAR GRAVAÇÃO"
                session -> "✕  DESATIVAR CONTROLES"
                else -> "●  ATIVAR CONTROLE FLUTUANTE"
            }
            start.setBackgroundColor(if (running) 0xFFB3263E.toInt() else if (session) 0xFF675A2B.toInt() else 0xFF087A9B.toInt())
            val error = prefs.getString("error", null)
            state.text = when {
                running -> "●  GRAVANDO AGORA\nToque na bolha para parar. No seletor do Android, escolha um app para gravar sem a bolha no vídeo."
                session -> "CONTROLE FLUTUANTE ATIVO\nVá para o jogo ou vídeo e toque na bolha para começar."
                error != null -> "Não iniciou: $error"
                else -> "Ative o controle e grave direto no jogo, YouTube ou qualquer tela."
            }
            state.setTextColor(if (running) 0xFFFF6577.toInt() else if (session) 0xFFFFC857.toInt() else 0xFFCCD7E4.toInt())
            state.setBackgroundColor(if (running) 0xFF321520.toInt() else if (session) 0xFF292414.toInt() else 0x00000000)
            state.setPadding(dp(if (running || session) 10 else 0), dp(if (running || session) 10 else 4), dp(if (running || session) 10 else 0), dp(if (running || session) 10 else 4))
            val started = prefs.getLong("started", 0L)
            val seconds = if (started > 0L) ((System.currentTimeMillis() - started) / 1000).coerceAtLeast(0) else 0
            val elapsed = String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60)
            timer.text = if (running && started > 0) elapsed else ""
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF07111F.toInt(); window.navigationBarColor = 0xFF07111F.toInt()
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(28), dp(22), dp(18)); setBackgroundColor(0xFF07111F.toInt()) }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        scroll.addView(content)
        content.addView(label("NEXUS", 20, 0xFFFFC857.toInt(), true), matchWrap())
        content.addView(label("SCREEN RECORD PRO", 28, 0xFFF4F7FB.toInt(), true), matchWrap())
        content.addView(label("NEXUS FUTURISTICA", 13, 0xFF39D9FF.toInt(), true), matchWrap())
        content.addView(label("GRAVAÇÃO LOCAL • PRIVACIDADE EM PRIMEIRO LUGAR", 11, 0xFF9BAABD.toInt(), false), params(top = 14, bottom = 22))

        content.addView(label("PERFIL DE GRAVAÇÃO", 13, 0xFFFFC857.toInt(), true), matchWrap())
        mode = Spinner(this)
        val profiles = listOf("Leve • 540p / 30 FPS", "Equilibrado • 640p / 30 FPS", "Alta qualidade • até 720p / 30 FPS")
        val profileAdapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, profiles) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply { setTextColor(0xFFF4F7FB.toInt()); textSize = 16f }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getDropDownView(position, convertView, parent) as TextView).apply {
                    setTextColor(0xFF07111F.toInt())
                    setBackgroundColor(0xFFE9EFF7.toInt())
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    textSize = 16f
                }
        }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        mode.adapter = profileAdapter
        mode.setSelection(prefs.getInt("mode", 2).coerceIn(0, 2))
        mode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { prefs.edit().putInt("mode", position).apply() }
        }
        content.addView(mode, params(top = 5, bottom = 16))
        state = label("Pronto para gravar", 14, 0xFFCCD7E4.toInt(), false)
        content.addView(state, matchWrap())
        timer = label("", 24, 0xFFFFC857.toInt(), true).apply { typeface = android.graphics.Typeface.MONOSPACE }
        content.addView(timer, params(bottom = 6))
        start = Button(this).apply { textSize = 16f; setTextColor(0xFFFFFFFF.toInt()); isAllCaps = false; setPadding(dp(14), dp(12), dp(14), dp(12)); setOnClickListener { onRecordTap() } }
        content.addView(start, params(top = 8, bottom = 14))
        val gallery = Button(this).apply { text = "MINHAS GRAVAÇÕES"; isAllCaps = false; setOnClickListener { showGallery() } }
        content.addView(gallery, params(bottom = 18))

        content.addView(label("COMO USAR", 15, 0xFFFFC857.toInt(), true), params(bottom = 6))
        content.addView(label("1. Escolha a qualidade e toque em Ativar controle flutuante.\n2. Permita ‘aparecer sobre outros apps’, notificações e áudio interno.\n3. No seletor do Android 14, escolha ‘um app’ e selecione o jogo ou YouTube. A bolha fica visível para você, mas só o app escolhido entra no vídeo.\n4. Toque na bolha para parar. A notificação também tem o botão Parar.\n\nA gravação é salva em Movies/NexusScreenRecordPro. Alguns apps protegidos podem bloquear imagem ou áudio.", 14, 0xFFCCD7E4.toInt(), false), params(bottom = 20))
        content.addView(label("NEXUS SCREEN RECORD PRO  •  teste 0.8.2 para A13\nControle na tela, captura de um app", 11, 0xFF8495A9.toInt(), false), matchWrap())
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(root)
    }

    override fun onResume() { super.onResume(); ui.post(poll) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(poll) }

    private fun onRecordTap() {
        val prefs = getSharedPreferences("recorder", MODE_PRIVATE)
        if (prefs.getBoolean("running", false)) {
            runCatching { startService(Intent(this, ScreenRecordService::class.java).setAction(ScreenRecordService.ACTION_STOP)) }
                .onFailure { showError("Não consegui parar a gravação.") }
            return
        }
        if (prefs.getBoolean("session", false)) {
            runCatching { startService(Intent(this, ScreenRecordService::class.java).setAction(ScreenRecordService.ACTION_CLOSE)) }
                .onFailure { showError("Não consegui desativar os controles.") }
            return
        }
        prefs.edit().remove("error").apply()
        if (android.os.Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            state.text = "Ative ‘aparecer sobre outros apps’ para mostrar a bolha."
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")), REQUEST_OVERLAY)
            } catch (_: Throwable) {
                showError("Não consegui abrir a permissão dos controles flutuantes.")
            }
            return
        }
        requestSessionPermissions()
    }

    private fun requestSessionPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            state.text = "Autorize as notificações para ver o indicador e o cronômetro."
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            return
        }
        requestAudioPermissionOrCapture()
    }

    private fun requestAudioPermissionOrCapture() {
        if (android.os.Build.VERSION.SDK_INT >= 23 && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            state.text = "Autorize o áudio interno; o microfone não será usado."
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO)
            return
        }
        launchCaptureRequest()
    }

    private fun launchCaptureRequest() {
        state.text = "Aguardando autorização de captura da tela…"
        try {
            val projection = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val captureIntent = if (android.os.Build.VERSION.SDK_INT >= 34) {
                projection.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())
            } else projection.createScreenCaptureIntent()
            @Suppress("DEPRECATION") startActivityForResult(captureIntent, REQUEST_CAPTURE)
        } catch (t: Throwable) {
            showError("Não abriu a autorização: ${t.localizedMessage ?: t.javaClass.simpleName}")
        }
    }

    @Deprecated("Android permission callback for broad API compatibility")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_NOTIFICATIONS -> {
                if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) requestAudioPermissionOrCapture()
                else showError("Permita as notificações nas configurações do app para acompanhar o ícone, o cronômetro e o botão Parar.")
            }
            REQUEST_AUDIO -> {
                if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) launchCaptureRequest()
                else showError("Permita o áudio para gravar o som interno. Nenhum microfone é usado.")
            }
        }
    }

    @Deprecated("Android activity result API for broad API compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OVERLAY) {
            if (Settings.canDrawOverlays(this)) requestSessionPermissions()
            else showError("Permita ‘aparecer sobre outros apps’ para usar o botão flutuante.")
            return
        }
        if (requestCode == REQUEST_CAPTURE) {
            if (resultCode != RESULT_OK || data == null) {
                showError("Autorização de captura cancelada. Toque em Gravar e escolha ‘Iniciar agora’.")
                return
            }
            state.text = "Autorização recebida • preparando o controle flutuante…"
            try {
                val intent = Intent(this, ScreenRecordService::class.java).apply {
                    action = ScreenRecordService.ACTION_START_SESSION
                    putExtra(ScreenRecordService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(ScreenRecordService.EXTRA_DATA, data)
                    putExtra(ScreenRecordService.EXTRA_MODE, mode.selectedItemPosition)
                }
                if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
                
            } catch (t: Throwable) {
                showError("Não consegui iniciar o serviço: ${t.localizedMessage ?: t.javaClass.simpleName}")
            }
        }
    }

    private fun showError(message: String) {
        getSharedPreferences("recorder", MODE_PRIVATE).edit().putString("error", message).apply()
        state.text = "Não iniciou: $message"
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun showGallery() {
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE)
        val videos = mutableListOf<Pair<String, Uri>>()
        contentResolver.query(collection, projection, "${MediaStore.Video.Media.RELATIVE_PATH}=?", arrayOf("Movies/NexusScreenRecordPro/"), "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID); val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) videos += cursor.getString(nameCol) to Uri.withAppendedPath(collection, cursor.getLong(idCol).toString())
        }
        if (videos.isEmpty()) { Toast.makeText(this, "Ainda não há gravações salvas.", Toast.LENGTH_SHORT).show(); return }
        val items = videos.map { it.first }.toTypedArray()
        AlertDialogCompat.show(this, items) { index ->
            val uri = videos[index].second
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
    }

    private fun label(text: String, size: Int, color: Int, bold: Boolean) = TextView(this).apply {
        this.text = text; textSize = size.toFloat(); setTextColor(color); gravity = Gravity.CENTER; if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, dp(4), 0, dp(4))
    }
    private fun params(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(top), 0, dp(bottom)) }
    private fun matchWrap() = LinearLayout.LayoutParams(-1, -2)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object { private const val REQUEST_CAPTURE = 341; private const val REQUEST_AUDIO = 342; private const val REQUEST_NOTIFICATIONS = 343; private const val REQUEST_OVERLAY = 344 }
}

private object AlertDialogCompat {
    fun show(activity: Activity, items: Array<String>, choose: (Int) -> Unit) {
        android.app.AlertDialog.Builder(activity).setTitle("Gravações").setItems(items) { _, which -> choose(which) }
            .setNegativeButton("Fechar", null).show()
    }
}
