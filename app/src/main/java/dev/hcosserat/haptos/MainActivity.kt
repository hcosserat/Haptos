package dev.hcosserat.haptos

import android.content.Context
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.audiofx.HapticGenerator
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.hcosserat.haptos.databinding.ActivityMainBinding

data class PlaylistItem(val uri: Uri, val name: String, val durationMs: Long) {
    val durationFormatted: String
        get() {
            if (durationMs <= 0) return "-:--"
            val totalSeconds = durationMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return String.format("%d:%02d", minutes, seconds)
        }
}

class AudioPlaylistAdapter(
    private val getPlaylist: () -> List<PlaylistItem>,
    private val onTrackClick: (Int) -> Unit,
    private val getCurrentPlayingIndex: () -> Int
) : RecyclerView.Adapter<AudioPlaylistAdapter.ViewHolder>() {

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val trackName: TextView = itemView.findViewById(R.id.tvPlaylistItemName)
        val trackDuration: TextView = itemView.findViewById(R.id.tvPlaylistItemDuration)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.playlist_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getPlaylist()[position]
        holder.trackName.text = item.name
        holder.trackDuration.text = item.durationFormatted
        holder.itemView.setOnClickListener { onTrackClick(position) }

        if (position == getCurrentPlayingIndex()) {
            holder.itemView.setBackgroundColor(
                ContextCompat.getColor(
                    holder.itemView.context,
                    R.color.highlight_color
                )
            )
        } else {
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
        }
    }

    override fun getItemCount(): Int = getPlaylist().size
}

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    private val playlist = mutableListOf<PlaylistItem>()
    private var currentPlaylistIndex = -1
    private lateinit var playlistAdapter: AudioPlaylistAdapter

    private val updateSeekBar = object : Runnable {
        override fun run() {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    binding.seekBar.progress = it.currentPosition
                    updateCurrentTimeText(it.currentPosition)
                    handler.postDelayed(this, 100)
                }
            }
        }
    }

    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                val fileName = getFileName(it) ?: "Unknown File"
                val duration = getFileDuration(applicationContext, it) ?: 0L
                val newItem = PlaylistItem(it, fileName, duration)
                loadPlaylist(listOf(newItem))
            }
        }

    private val folderPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
            treeUri?.let {
                val newPlaylistItems = mutableListOf<PlaylistItem>()
                val documentFile = DocumentFile.fromTreeUri(applicationContext, it)
                documentFile?.listFiles()?.forEach { file ->
                    if (file.isFile && (file.type?.startsWith("audio/") == true || isAudioFile(file.name))) {
                        val fileName = file.name ?: "Unknown Track"
                        val duration = getFileDuration(applicationContext, file.uri) ?: 0L
                        newPlaylistItems.add(PlaylistItem(file.uri, fileName, duration))
                    }
                }
                if (newPlaylistItems.isNotEmpty()) {
                    loadPlaylist(newPlaylistItems)
                } else {
                    clearPlaylist()
                    val toast = Toast.makeText(this, "No audio files found in this folder", Toast.LENGTH_SHORT)
                    toast.show()
                }
            }
        }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        enableEdgeToEdge()
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        checkSupport()
        setupRecyclerView()
        setupUI()
        clearPlayerUI()
    }

    private fun checkSupport() {
        if (!HapticGenerator.isAvailable()) {
            MaterialAlertDialogBuilder(this, R.style.RoundedAlertDialog)
                .setTitle("Your device is not supported :(")
                .setMessage("This app won't work on your device. You can still use it as a media player.")
                .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                .setNegativeButton("Quit") { _, _ -> finish() }
                .setCancelable(false)
                .show()
        }
    }

    private fun setupRecyclerView() {
        playlistAdapter = AudioPlaylistAdapter(
            getPlaylist = { playlist },
            onTrackClick = { index ->
                playTrack(index)
            },
            getCurrentPlayingIndex = { currentPlaylistIndex }
        )
        binding.rvPlaylist.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = playlistAdapter
        }
    }

    private fun setupUI() {
        binding.btnSelectFile.setOnClickListener {
            filePickerLauncher.launch(arrayOf("audio/*"))
        }

        binding.btnSelectFolder.setOnClickListener {
            folderPickerLauncher.launch(null) // No initial URI needed for folder picker
        }

        binding.btnPlayPause.setOnClickListener {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.pause()
                    binding.btnPlayPause.setIconResource(android.R.drawable.ic_media_play)
                    handler.removeCallbacks(updateSeekBar)
                } else {
                    it.start()
                    binding.btnPlayPause.setIconResource(android.R.drawable.ic_media_pause)
                    handler.post(updateSeekBar)
                }
            }
        }

        binding.btnNext.setOnClickListener {
            playNext()
        }

        binding.btnPrevious.setOnClickListener {
            playPrevious()
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    mediaPlayer?.seekTo(progress)
                    updateCurrentTimeText(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                mediaPlayer?.let { if (it.isPlaying) handler.removeCallbacks(updateSeekBar) }
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                mediaPlayer?.let { if (it.isPlaying) handler.post(updateSeekBar) }
            }
        })
    }

    private fun loadPlaylist(newItems: List<PlaylistItem>) {
        mediaPlayer?.stop()
        mediaPlayer?.reset() // Reset before release or setting new source

        playlist.clear()
        playlist.addAll(newItems)
        playlistAdapter.notifyDataSetChanged()

        if (playlist.isNotEmpty()) {
            currentPlaylistIndex = 0
            playTrack(currentPlaylistIndex)
            binding.rvPlaylist.visibility = View.VISIBLE
            binding.emptyPlaylistView.visibility = View.GONE
        } else {
            clearPlaylist()
        }
    }

    private fun clearPlaylist() {
        mediaPlayer?.stop()
        mediaPlayer?.reset()
        playlist.clear()
        currentPlaylistIndex = -1
        binding.emptyPlaylistView.visibility = View.VISIBLE
        binding.rvPlaylist.visibility = View.GONE
        playlistAdapter.notifyDataSetChanged()
        clearPlayerUI()
    }

    private fun clearPlayerUI() {
        binding.btnPlayPause.isEnabled = false
        binding.seekBar.isEnabled = false
        binding.btnNext.isEnabled = false
        binding.btnPrevious.isEnabled = false
        binding.tvCurrentTime.text = "-:--"
        binding.tvTotalTime.text = "-:--"
        binding.seekBar.progress = 0
        binding.btnPlayPause.setIconResource(android.R.drawable.ic_media_play)
    }


    private fun playTrack(index: Int) {
        if (index in playlist.indices) {
            currentPlaylistIndex = index
            val item = playlist[index]
            setupMediaPlayer(item) // This will prepare the media player
            mediaPlayer?.start()
            binding.btnPlayPause.setIconResource(android.R.drawable.ic_media_pause)
            handler.post(updateSeekBar)
            enablePlayerButtons()
            playlistAdapter.notifyDataSetChanged() // To highlight current track
        } else {
            clearPlayerUI() // Or handle error
        }
    }

    private fun enablePlayerButtons() {
        binding.btnPlayPause.isEnabled = true
        binding.seekBar.isEnabled = true
        binding.btnNext.isEnabled = playlist.size > 1
        binding.btnPrevious.isEnabled = playlist.size > 1
    }


    private fun setupMediaPlayer(item: PlaylistItem) {
        mediaPlayer?.release() // Release previous instance fully
        mediaPlayer = null

        try {
            val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
            val audioSessionId = audioManager.generateAudioSessionId()

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setHapticChannelsMuted(false) // Ensure this is intended
                        .build()
                )
                if (audioSessionId > 0) {
                    setAudioSessionId(audioSessionId)
                }
                setDataSource(applicationContext, item.uri)
                prepare() // Prepare synchronously

                setOnCompletionListener {
                    playNext(true) // Auto play next
                }

                setOnErrorListener { mp, what, extra ->
                    clearPlaylist()
                    true // True if the error has been handled
                }

                updateCurrentTimeText(0)
                binding.seekBar.max = item.durationMs.toInt()
                updateTotalTimeText(item.durationMs.toInt())


                if (HapticGenerator.isAvailable() && audioSessionId > 0) {
                    try {
                        val hapticGenerator = HapticGenerator.create(audioSessionId)
                        hapticGenerator.setEnabled(true)
                    } catch (e: Exception) {
                        // Log error if HapticGenerator fails to create or enable
                        e.printStackTrace()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            clearPlaylist()
        }
    }

    private fun playNext(fromCompletion: Boolean = false) {
        if (playlist.isEmpty()) return

        currentPlaylistIndex++
        if (currentPlaylistIndex >= playlist.size) {
            currentPlaylistIndex = 0 // Loop to the beginning
            if (playlist.size == 1 && fromCompletion) { // If only one song and it completed
                mediaPlayer?.seekTo(0)
                mediaPlayer?.pause()
                binding.btnPlayPause.setIconResource(android.R.drawable.ic_media_play)
                handler.removeCallbacks(updateSeekBar)
                updateCurrentTimeText(0)
                playlistAdapter.notifyDataSetChanged()
                return
            }
        }
        playTrack(currentPlaylistIndex)
    }

    private fun playPrevious() {
        if (playlist.isEmpty()) return

        currentPlaylistIndex--
        if (currentPlaylistIndex < 0) {
            currentPlaylistIndex = playlist.size - 1 // Loop to the end
        }
        playTrack(currentPlaylistIndex)
    }

    private fun getFileName(uri: Uri): String? {
        var fileName: String? = null
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (nameIndex != -1) {
                                fileName = cursor.getString(nameIndex)
                            }
                        }
                    }
            } catch (e: Exception) {
                // Could not query content resolver
                e.printStackTrace()
            }
        }
        if (fileName == null) {
            fileName = uri.path
            val cut = fileName?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                fileName = fileName?.substring(cut + 1)
            }
        }
        return fileName ?: "Unknown"
    }

    private fun getFileDuration(context: Context, uri: Uri): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durationStr =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            durationStr?.toLongOrNull()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun isAudioFile(fileName: String?): Boolean {
        if (fileName == null) return false
        val lowerName = fileName.lowercase()
        return lowerName.endsWith(".mp3") || lowerName.endsWith(".wav") ||
                lowerName.endsWith(".aac") || lowerName.endsWith(".m4a") ||
                lowerName.endsWith(".ogg") || lowerName.endsWith(".flac") ||
                lowerName.endsWith(".opus")
    }


    private fun updateCurrentTimeText(milliseconds: Int) {
        val totalSeconds = milliseconds / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        binding.tvCurrentTime.text = String.format("%d:%02d", minutes, seconds)
    }

    private fun updateTotalTimeText(milliseconds: Int) {
        if (milliseconds <= 0) {
            binding.tvTotalTime.text = "-:--"
            return
        }
        val totalSeconds = milliseconds / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        binding.tvTotalTime.text = String.format("%d:%02d", minutes, seconds)
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaPlayer = null
        handler.removeCallbacks(updateSeekBar)
    }
}
