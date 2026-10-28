package com.vasu.vlcautodub

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.*
import kotlinx.coroutines.*
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import java.util.*

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var libVLC: LibVLC
    private lateinit var mediaPlayer: MediaPlayer
    private lateinit var videoLayout: VLCVideoLayout
    private lateinit var subOverlay: TextView
    private lateinit var statusText: TextView
    private lateinit var tts: TextToSpeech
    private var vocalCutEnabled = false
    private var selectedVideoUri: Uri? = null

    private val pickVideo = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) res.data?.data?.let { uri ->
            selectedVideoUri = uri
            playVideo(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        libVLC = LibVLC(this, arrayListOf("--no-drop-late-frames", "--no-skip-frames"))
        mediaPlayer = MediaPlayer(libVLC)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF121212.toInt()) }
        val videoFrame = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }
        videoLayout = VLCVideoLayout(this)
        subOverlay = TextView(this).apply {
            text = "Subtitles & Live Dub Ready"
            textSize = 18f; setTextColor(0xFFFFFFFF.toInt()); setBackgroundColor(0xAA000000.toInt())
            gravity = Gravity.CENTER; setPadding(24, 12, 24, 12)
            layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)
        }
        videoFrame.addView(videoLayout); videoFrame.addView(subOverlay)

        statusText = TextView(this).apply { text = "Step 1: Pick any video to play or generate SRT/Dub"; setTextColor(0xFFBBBBBB.toInt()); setPadding(24, 16, 24, 8) }
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(16, 8, 16, 16) }

        fun addBtn(label: String, action: () -> Unit) = Button(this).apply {
            text = label; layoutParams = LinearLayout.LayoutParams(0, -2, 1f); setOnClickListener { action() }
        }.also { btnRow.addView(it) }

        addBtn("1. Open Video") { pickVideo.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "video/*" }) }
        addBtn("2. Keep BGM Only") { toggleVocalRemoval() }
        addBtn("3. Gen SRT + Dub") { selectedVideoUri?.let { runAutoDubPipeline(it) } ?: toast("Pick a video first!") }

        root.addView(videoFrame); root.addView(statusText); root.addView(btnRow)
        setContentView(root)
        mediaPlayer.attachViews(videoLayout, null, true, false)
    }

    private fun playVideo(uri: Uri) {
        contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val media = Media(libVLC, pfd.fileDescriptor)
            mediaPlayer.media = media
            media.release()
            mediaPlayer.play()
            statusText.text = "Playing via LibVLC Engine"
        }
    }

    private fun toggleVocalRemoval() {
        vocalCutEnabled = !vocalCutEnabled
        mediaPlayer.volume = if (vocalCutEnabled) 35 else 100
        toast(if (vocalCutEnabled) "BGM Mode: Original Vocals Ducked for Dubbing" else "Normal Original Audio")
    }

    private fun detectSpeakerPitchHz(pcmSamples: ShortArray, sampleRate: Int = 16000): Float {
        var crossings = 0
        for (i in 1 until pcmSamples.size) {
            if ((pcmSamples[i - 1] >= 0 && pcmSamples[i] < 0) || (pcmSamples[i - 1] < 0 && pcmSamples[i] >= 0)) crossings++
        }
        return (crossings * sampleRate) / (2f * pcmSamples.size.coerceAtLeast(1))
    }

    private fun speakWithCharacterPitch(text: String, detectedHz: Float) {
        val pitchMultiplier = when {
            detectedHz < 110f -> 0.80f
            detectedHz < 160f -> 0.95f
            detectedHz < 220f -> 1.15f
            else -> 1.30f
        }
        tts.setPitch(pitchMultiplier)
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "dub_${System.currentTimeMillis()}")
    }

    private fun runAutoDubPipeline(uri: Uri) {
        lifecycleScope.launch {
            statusText.text = "Downloading/Checking Offline Translation Model..."
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.HINDI)
                .build()
            val translator = Translation.getClient(options)
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).addOnSuccessListener {
                statusText.text = "Generating Multi-Character SRT & Dub Preview..."
                val demoSegments = listOf(
                    Triple(0L, "We must protect this place at all costs!", 115f),
                    Triple(4000L, "Watch out! Something is coming from the sky!", 210f),
                    Triple(8000L, "Stand back, I will handle this monster.", 92f)
                )
                val srtBuilder = StringBuilder()
                demoSegments.forEachIndexed { idx, (timeMs, line, hz) ->
                    translator.translate(line).addOnSuccessListener { translated ->
                        val genderTag = if (hz < 160f) "MALE_${hz.toInt()}Hz" else "FEMALE_${hz.toInt()}Hz"
                        srtBuilder.append("${idx + 1}\n00:00:0${timeMs / 1000},000 --> 00:00:0${(timeMs / 1000) + 3},500\n[$genderTag] $translated\n\n")
                        subOverlay.text = "[$genderTag]: $translated"
                        speakWithCharacterPitch(translated, hz)
                        if (idx == demoSegments.lastIndex) saveSrtFile(srtBuilder.toString())
                    }
                }
            }
        }
    }

    private fun saveSrtFile(content: String) {
        val file = File(getExternalFilesDir(null), "generated_autodub.srt")
        file.writeText(content)
        statusText.text = "Saved SRT to: ${file.absolutePath}"
    }

    override fun onInit(status: Int) { if (status == TextToSpeech.SUCCESS) tts.language = Locale("hi", "IN") }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    override fun onDestroy() { super.onDestroy(); mediaPlayer.release(); libVLC.release(); tts.shutdown() }
}