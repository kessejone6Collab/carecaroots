package com.nexusfuturistica.screenrecordpro

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
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
            val running = getSharedPreferences("recorder", MODE_PRIVATE).getBoolean("running", false)
            start.text = if (running) "PARAR GRAVAÇÃO" else "●  GRAVAR AGORA"
            start.setBackgroundColor(if (running) 0xFF9C2940.toInt() else 0xFF087A9B.toInt())
            state.text = if (running) "GRAVANDO • mantenha esta notificação ativa" else "Pronto para gravar • sem internet, anúncios ou marca-d’água"
            val started = getSharedPreferences("recorder", MODE_PRIVATE).getLong("started", 0L)
            timer.text = if (running && started > 0) "${(System.currentTimeMillis() - started) / 1000}s" else ""
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
        mode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Econômico • 720p / 30 FPS", "Equilibrado • 720p / 30 FPS", "Alta qualidade • até 1080p / 30 FPS"))
        mode.setSelection(prefs.getInt("mode", 1).coerceIn(0, 2))
        mode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { prefs.edit().putInt("mode", position).apply() }
        }
        content.addView(mode, params(top = 5, bottom = 16))
        state = label("Pronto para gravar", 14, 0xFFCCD7E4.toInt(), false)
        content.addView(state, matchWrap())
        timer = label("", 14, 0xFFFFC857.toInt(), true)
        content.addView(timer, params(bottom = 6))
        start = Button(this).apply { textSize = 16f; setTextColor(0xFFFFFFFF.toInt()); isAllCaps = false; setPadding(dp(14), dp(12), dp(14), dp(12)); setOnClickListener { onRecordTap() } }
        content.addView(start, params(top = 8, bottom = 14))
        val gallery = Button(this).apply { text = "MINHAS GRAVAÇÕES"; isAllCaps = false; setOnClickListener { showGallery() } }
        content.addView(gallery, params(bottom = 18))

        content.addView(label("COMO USAR", 15, 0xFFFFC857.toInt(), true), params(bottom = 6))
        content.addView(label("1. Escolha um perfil.\n2. Toque em Gravar e confirme a captura na janela oficial do Android.\n3. Para encerrar, use o botão nesta tela ou a notificação.\n\nA captura de áudio não está incluída nesta primeira versão. Telas protegidas pelo Android ou por aplicativos podem aparecer pretas.", 14, 0xFFCCD7E4.toInt(), false), params(bottom = 20))
        content.addView(label("NEXUS SCREEN RECORD PRO  •  versão 0.1.0\nVídeos salvos em Movies/NexusScreenRecordPro", 11, 0xFF8495A9.toInt(), false), matchWrap())
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(root)
    }

    override fun onResume() { super.onResume(); ui.post(poll) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(poll) }

    private fun onRecordTap() {
        val prefs = getSharedPreferences("recorder", MODE_PRIVATE)
        if (prefs.getBoolean("running", false)) {
            startService(Intent(this, ScreenRecordService::class.java).setAction(ScreenRecordService.ACTION_STOP))
            return
        }
        val projection = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION") startActivityForResult(projection.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    @Deprecated("Android activity result API for broad API compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CAPTURE && resultCode == RESULT_OK && data != null) {
            val intent = Intent(this, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_START
                putExtra(ScreenRecordService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenRecordService.EXTRA_DATA, data)
                putExtra(ScreenRecordService.EXTRA_MODE, mode.selectedItemPosition)
            }
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        }
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

    companion object { private const val REQUEST_CAPTURE = 341 }
}

private object AlertDialogCompat {
    fun show(activity: Activity, items: Array<String>, choose: (Int) -> Unit) {
        android.app.AlertDialog.Builder(activity).setTitle("Gravações").setItems(items) { _, which -> choose(which) }
            .setNegativeButton("Fechar", null).show()
    }
}
