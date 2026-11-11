package dev.aravind.assistant

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.telecom.TelecomManager
import android.view.KeyEvent
import androidx.core.content.FileProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import dev.aravind.assistant.policy.ContactMatcher
import dev.aravind.assistant.policy.SecretRedactor
import dev.aravind.assistant.policy.WebAddress
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** A song stored on the phone. */
data class Song(val uri: Uri, val title: String)

/**
 * A simple built-in player for songs stored on the phone, so "play all Darling songs" can queue every
 * matching song, whatever music app is installed. It asks for audio focus and pauses when another app takes it.
 */
object MusicPlayer {
    private var player: MediaPlayer? = null
    private var queue: List<Song> = emptyList()
    private var index = 0
    private var focus: AudioFocusRequest? = null
    private var app: Context? = null

    @Volatile
    var nowPlaying: String? = null
        private set

    val isActive: Boolean get() = player != null

    val isPlaying: Boolean get() = runCatching { player?.isPlaying == true }.getOrDefault(false)

    @Synchronized
    fun play(context: Context, songs: List<Song>): Boolean {
        stop()
        if (songs.isEmpty()) return false
        app = context.applicationContext
        queue = songs
        index = 0
        val audio = context.getSystemService(AudioManager::class.java)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes())
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause()
            }
            .build()
        focus = request
        audio.requestAudioFocus(request)
        return startCurrent()
    }

    private fun attributes() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    /** Starts the song at [index], skipping songs that can't be played. */
    private fun startCurrent(): Boolean {
        val context = app ?: return false
        while (index < queue.size) {
            val song = queue[index]
            val created = runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(attributes())
                    setDataSource(context, song.uri)
                    setOnCompletionListener { next() }
                    prepare()
                    start()
                }
            }.getOrNull()
            if (created != null) {
                player = created
                nowPlaying = song.title
                return true
            }
            index++
        }
        stop()
        return false
    }

    private fun releasePlayer() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
    }

    @Synchronized
    fun next(): Boolean {
        if (queue.isEmpty()) return false
        releasePlayer()
        index++
        return startCurrent()
    }

    @Synchronized
    fun previous(): Boolean {
        if (queue.isEmpty()) return false
        releasePlayer()
        index = (index - 1).coerceAtLeast(0)
        return startCurrent()
    }

    @Synchronized
    fun pause(): Boolean {
        val current = player ?: return false
        if (current.isPlaying) current.pause()
        return true
    }

    @Synchronized
    fun resume(): Boolean {
        val current = player ?: return false
        current.start()
        return true
    }

    @Synchronized
    fun stop() {
        releasePlayer()
        queue = emptyList()
        index = 0
        nowPlaying = null
        val context = app
        val request = focus
        if (context != null && request != null) context.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(request)
        focus = null
    }
}

/** A file found by a search, kept so later steps can refer to it by number. */
data class FoundFile(val id: Long, val name: String, val path: String, val mime: String, val size: Long, val modified: Long)

/**
 * The agent's phone tools beyond the basic commands: music, files, photos, camera, calendar, memory,
 * websites and calls. Each one does the real action and checks that it worked before reporting success.
 */
class PhoneTools(private val context: Context) {
    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun canReadStorage() = Environment.isExternalStorageManager() || granted(Manifest.permission.READ_EXTERNAL_STORAGE)

    private val storageHint = "Allow Storage, or All files access in Control Center, first"

    private fun day(millis: Long) = SimpleDateFormat("d MMM", Locale.ENGLISH).format(Date(millis))

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    // ---- Music

    private val musicFillers = setOf(
        "song", "songs", "movie", "film", "music", "all", "the", "play", "from", "of", "my", "phone", "stored", "on", "in",
        "audio", "track", "tracks", "album", "artist", "local", "please", "and", "a",
    )

    /** Songs whose title, album, artist, file name or folder contain the words of [query]. */
    private fun findSongs(query: String?): List<Song> {
        val words = query.orEmpty().lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 1 && it !in musicFillers }
        val columns = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.RELATIVE_PATH,
        )
        val all = ArrayList<Pair<Song, String>>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, columns, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null,
            "${MediaStore.Audio.Media.TITLE} ASC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val title = cursor.getString(1).orEmpty().ifBlank { cursor.getString(4).orEmpty() }
                val searchable = (1..5).joinToString(" ") { cursor.getString(it).orEmpty() }.lowercase()
                all += Song(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id), title) to searchable
            }
        }
        if (words.isEmpty()) return all.map { it.first }
        val exact = all.filter { (_, text) -> words.all { text.contains(it) } }
        if (exact.isNotEmpty()) return exact.map { it.first }
        // If no song has every word, the songs with the most matching words are used.
        val scored = all.map { it to words.count { word -> it.second.contains(word) } }.filter { it.second > 0 }
        val best = scored.maxOfOrNull { it.second } ?: return emptyList()
        return scored.filter { it.second == best }.map { it.first.first }
    }

    fun searchMusic(query: String): String {
        if (!canReadStorage()) return storageHint
        val songs = findSongs(query)
        if (songs.isEmpty()) return "No songs on the phone match $query"
        return "${songs.size} song(s) match $query: ${songs.take(10).joinToString { it.title }}"
    }

    fun playMusic(query: String?): String {
        if (!canReadStorage()) return storageHint
        val songs = findSongs(query)
        if (songs.isEmpty()) return if (query.isNullOrBlank()) "There are no songs stored on this phone" else "No songs on the phone match $query"
        if (!MusicPlayer.play(context, songs)) return "Those songs couldn't be played"
        query?.takeIf { it.isNotBlank() }?.let { MemoryStore.recordUsage(context, "music", it) }
        // Checks that playback really started before saying so.
        return if (MusicPlayer.isPlaying) "Playing ${songs.size} song(s), starting with ${MusicPlayer.nowPlaying}" else "The player started but isn't playing"
    }

    fun mediaControl(action: String): String {
        if (MusicPlayer.isActive) {
            val ok = when (action) {
                "pause" -> MusicPlayer.pause()
                "resume" -> MusicPlayer.resume()
                "next" -> MusicPlayer.next()
                "previous" -> MusicPlayer.previous()
                else -> {
                    MusicPlayer.stop()
                    true
                }
            }
            return when {
                !ok -> "There's no next song"
                action == "stop" -> "Stopped the music"
                action == "pause" -> "Paused"
                else -> MusicPlayer.nowPlaying?.let { "Playing $it" } ?: "Done"
            }
        }
        // Another app is playing: media keys work with any player.
        val audio = context.getSystemService(AudioManager::class.java)
        val code = when (action) {
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "resume" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_STOP
        }
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        Thread.sleep(400)
        val playing = audio.isMusicActive
        return when (action) {
            "pause", "stop" -> if (playing) "I pressed $action, but something is still playing" else "Done, nothing is playing now"
            "resume" -> if (playing) "Playing again" else "I pressed play, but nothing started. Open the music app first"
            else -> if (playing) "Skipped" else "I pressed $action, but nothing is playing"
        }
    }

    // ---- Files

    private fun read(cursor: Cursor) = FoundFile(
        id = cursor.getLong(0),
        name = cursor.getString(1).orEmpty(),
        path = cursor.getString(2).orEmpty(),
        mime = cursor.getString(3).orEmpty(),
        size = cursor.getLong(4),
        modified = cursor.getLong(5) * 1000,
    )

    private val fileColumns = arrayOf(
        MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.DATA,
        MediaStore.Files.FileColumns.MIME_TYPE, MediaStore.Files.FileColumns.SIZE, MediaStore.Files.FileColumns.DATE_MODIFIED,
    )

    private fun describe(files: List<FoundFile>) = files.mapIndexed { i, f ->
        val folder = File(f.path).parentFile?.name.orEmpty()
        "${i + 1}. ${f.name} ($folder, %.1f MB, ${day(f.modified)})".format(f.size / 1_048_576.0)
    }.joinToString("; ")

    fun searchFiles(query: String, kind: String?): String {
        if (!canReadStorage()) return storageHint
        val words = query.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 1 && it !in setOf("my", "the", "file", "files", "find") }
        val conditions = ArrayList<String>()
        val args = ArrayList<String>()
        words.forEach {
            conditions += "(${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ? OR ${MediaStore.Files.FileColumns.DATA} LIKE ?)"
            args += "%$it%"
            args += "%$it%"
        }
        val mime = MediaStore.Files.FileColumns.MIME_TYPE
        val kindFilter = when (kind) {
            "pdf" -> "$mime = 'application/pdf'"
            "document" -> "($mime LIKE 'application/%' OR $mime LIKE 'text/%')"
            "image" -> "$mime LIKE 'image/%'"
            "video" -> "$mime LIKE 'video/%'"
            "audio" -> "$mime LIKE 'audio/%'"
            else -> null
        }
        if (kindFilter != null) {
            conditions += kindFilter
        }
        conditions += "${MediaStore.Files.FileColumns.MIME_TYPE} IS NOT NULL"
        val found = ArrayList<FoundFile>()
        context.contentResolver.query(
            MediaStore.Files.getContentUri("external"), fileColumns, conditions.joinToString(" AND "), args.toTypedArray(),
            "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
        )?.use { cursor ->
            while (cursor.moveToNext() && found.size < 8) found += read(cursor)
        }
        lastFiles = found
        if (found.isEmpty()) {
            val hint = if (!Environment.isExternalStorageManager()) " Turn on All files access to search documents too" else ""
            return "No files match $query.$hint"
        }
        return "Found ${found.size}: ${describe(found)}"
    }

    /** "2", "the second", or part of a file name from the last search. */
    private fun resolve(file: String): FoundFile? {
        val list = lastFiles
        val ordinals = mapOf("first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5, "last" to list.size)
        val number = file.trim().toIntOrNull() ?: ordinals.entries.firstOrNull { file.lowercase().contains(it.key) }?.value
        if (number != null) return list.getOrNull(number - 1)
        val wanted = file.lowercase().trim()
        return list.firstOrNull { it.name.lowercase() == wanted }
            ?: list.firstOrNull { it.name.lowercase().contains(wanted) }
            ?: list.firstOrNull { f -> wanted.split(' ').filter { it.length > 2 }.all { f.name.lowercase().contains(it) } }
    }

    private fun shareUri(file: FoundFile): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(file.path))

    fun openFile(file: String): String {
        val found = resolve(file) ?: return "Search for the file first, then open it by its number"
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(shareUri(found), found.mime.ifBlank { "*/*" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return if (start(intent)) "Opened ${found.name}" else "No app on this phone can open ${found.name}"
    }

    fun readDocument(file: String): String {
        val found = resolve(file) ?: return "Search for the file first, then read it by its number"
        val source = File(found.path)
        if (!source.canRead()) return "This app can't read ${found.name}. Turn on All files access"
        val text = runCatching {
            when {
                found.mime == "application/pdf" || found.name.endsWith(".pdf", ignoreCase = true) -> {
                    PDFBoxResourceLoader.init(context.applicationContext)
                    PDDocument.load(source).use { doc ->
                        val stripper = PDFTextStripper().apply { endPage = minOf(doc.numberOfPages, 20) }
                        "(${doc.numberOfPages} pages) " + stripper.getText(doc)
                    }
                }
                found.mime.startsWith("text/") || found.name.substringAfterLast('.').lowercase() in setOf("txt", "md", "csv", "json", "html", "xml") ->
                    source.bufferedReader().use { reader ->
                        val buffer = CharArray(200_000)
                        val count = reader.read(buffer)
                        if (count > 0) String(buffer, 0, count) else ""
                    }
                else -> null
            }
        }.getOrElse { return "${found.name} couldn't be read (${it.javaClass.simpleName})" }
            ?: return "I can only read PDF and text files, not ${found.name}"
        val clean = SecretRedactor.redact(text).replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return "${found.name} has no readable text. It may be a scanned PDF"
        return "Text of ${found.name}: ${clean.take(2_000)}"
    }

    fun shareFile(file: String, app: String?): String {
        val found = resolve(file) ?: return "Search for the file first, then share it by its number"
        val send = Intent(Intent.ACTION_SEND)
            .setType(found.mime.ifBlank { "*/*" })
            .putExtra(Intent.EXTRA_STREAM, shareUri(found))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val target = app?.let { name ->
            context.packageManager.queryIntentActivities(send, 0).firstOrNull {
                it.loadLabel(context.packageManager).toString().contains(name, ignoreCase = true)
            }?.activityInfo?.packageName
        }
        if (target != null) send.setPackage(target)
        val opened = start(if (target != null) send else Intent.createChooser(send, "Share ${found.name}"))
        return when {
            !opened -> "Sharing couldn't be opened"
            target != null -> "Opened $app to share ${found.name}. Pick who to send it to"
            else -> "Opened the share screen for ${found.name}. Pick an app"
        }
    }

    private fun rescan(vararg paths: String) = MediaScannerConnection.scanFile(context, paths, null, null)

    fun renameFile(file: String, newName: String): String {
        if (!Environment.isExternalStorageManager()) return "Turn on All files access first, so I can rename files"
        val found = resolve(file) ?: return "Search for the file first, then rename it by its number"
        val source = File(found.path)
        val extension = source.extension
        val cleanName = newName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val finalName = if (extension.isNotEmpty() && !cleanName.endsWith(".$extension", true)) "$cleanName.$extension" else cleanName
        val target = File(source.parentFile, finalName)
        if (target.exists()) return "A file named $finalName already exists there"
        if (!source.renameTo(target) || !target.exists()) return "Renaming ${found.name} failed"
        rescan(source.path, target.path)
        lastFiles = lastFiles.map { if (it.path == source.path) it.copy(name = finalName, path = target.path) else it }
        return "Renamed ${found.name} to $finalName"
    }

    fun moveFile(file: String, folder: String): String {
        if (!Environment.isExternalStorageManager()) return "Turn on All files access first, so I can move files"
        val found = resolve(file) ?: return "Search for the file first, then move it by its number"
        val source = File(found.path)
        @Suppress("DEPRECATION")
        val dir = Environment.getExternalStoragePublicDirectory(folder)
        dir.mkdirs()
        val target = File(dir, source.name)
        if (target.exists()) return "$folder already has a file named ${source.name}"
        if (!source.renameTo(target) || !target.exists()) return "Moving ${found.name} failed"
        rescan(source.path, target.path)
        lastFiles = lastFiles.map { if (it.path == source.path) it.copy(path = target.path) else it }
        return "Moved ${found.name} to $folder"
    }

    // ---- Photos and videos

    fun findMedia(kind: String, daysAgo: Int): String {
        if (!canReadStorage()) return storageHint
        val dayStart = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -daysAgo)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val dayEnd = dayStart + 86_400_000L
        val type = if (kind == "video") MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO else MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
        val found = ArrayList<FoundFile>()
        var total = 0
        context.contentResolver.query(
            MediaStore.Files.getContentUri("external"), fileColumns,
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? AND " +
                "((${MediaStore.MediaColumns.DATE_TAKEN} >= ? AND ${MediaStore.MediaColumns.DATE_TAKEN} < ?) OR " +
                "(${MediaStore.MediaColumns.DATE_TAKEN} IS NULL AND ${MediaStore.Files.FileColumns.DATE_ADDED} >= ? AND ${MediaStore.Files.FileColumns.DATE_ADDED} < ?))",
            arrayOf(type.toString(), dayStart.toString(), dayEnd.toString(), (dayStart / 1000).toString(), (dayEnd / 1000).toString()),
            "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
        )?.use { cursor ->
            total = cursor.count
            while (cursor.moveToNext() && found.size < 8) found += read(cursor)
        }
        lastFiles = found
        val label = when (daysAgo) {
            0 -> "today"
            1 -> "yesterday"
            else -> "on ${day(dayStart)}"
        }
        val noun = if (kind == "video") "video" else "photo"
        if (total == 0) return "No ${noun}s from $label"
        return "$total ${noun}(s) from $label. Newest: ${describe(found)}"
    }

    // ---- Camera

    private fun newestImageTime(): Long = context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media.DATE_ADDED), null, null,
        "${MediaStore.Images.Media.DATE_ADDED} DESC",
    )?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L

    private fun newestVideoTime(): Long = context.contentResolver.query(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Video.Media.DATE_ADDED), null, null,
        "${MediaStore.Video.Media.DATE_ADDED} DESC",
    )?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L

    fun openCamera(mode: String?): String {
        val action = if (mode == "video") MediaStore.INTENT_ACTION_VIDEO_CAMERA else MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
        return if (start(Intent(action))) "Opened the camera" else "No camera app was found"
    }

    private fun cameraInFront() = AutomationService.instance?.targetPackage().orEmpty().contains("camera", ignoreCase = true)

    /** Presses the shutter: first by its label, then at the usual shutter spot at the bottom middle. */
    private fun pressShutter(labels: List<String>): Boolean {
        val service = AutomationService.instance ?: return false
        return service.tapAny(labels) || service.tapFraction(0.5f, 0.87f)
    }

    suspend fun takePhoto(): String {
        if (AutomationService.instance == null) return "Turn on the Accessibility Service in Control Center first"
        val before = newestImageTime()
        if (!cameraInFront()) {
            if (!start(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))) return "No camera app was found"
            delay(2_500)
        }
        if (!pressShutter(listOf("shutter", "take photo", "take picture", "capture", "photo"))) return "I couldn't press the shutter"
        // Success means a new photo really appeared in the gallery.
        repeat(10) {
            delay(500)
            if (newestImageTime() > before) return "Took a photo. It's saved in your gallery"
        }
        return "I pressed the shutter, but no new photo was saved. Try pressing it yourself"
    }

    suspend fun recordVideo(action: String): String {
        if (AutomationService.instance == null) return "Turn on the Accessibility Service in Control Center first"
        if (action == "stop") {
            val before = newestVideoTime()
            if (!pressShutter(listOf("stop recording", "stop", "shutter"))) return "I couldn't find the stop button"
            repeat(10) {
                delay(500)
                if (newestVideoTime() > before) return "Stopped recording. The video is saved"
            }
            return "I pressed stop, but no new video was saved yet"
        }
        if (!cameraInFront()) {
            if (!start(Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA))) return "No camera app was found"
            delay(2_500)
        }
        if (!pressShutter(listOf("start recording", "record", "shutter", "video"))) return "I couldn't press record"
        return "Recording started. Say stop recording when you're done"
    }

    // ---- Calendar and reminders

    private fun writableCalendar(): Long? {
        if (!granted(Manifest.permission.WRITE_CALENDAR)) return null
        val columns = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY)
        return context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, columns,
            "${CalendarContract.Calendars.VISIBLE} = 1 AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}",
            null, "${CalendarContract.Calendars.IS_PRIMARY} DESC",
        )?.use { if (it.moveToFirst()) it.getLong(0) else null }
    }

    private fun startTime(daysFromToday: Int, hour: Int, minute: Int): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, daysFromToday)
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun whenText(millis: Long) = SimpleDateFormat("EEE d MMM 'at' h:mm a", Locale.ENGLISH).format(Date(millis))

    private fun insertEvent(calendar: Long, title: String, begin: Long, minutes: Int, reminderMinutes: Int): Long? {
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendar)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, begin)
            put(CalendarContract.Events.DTEND, begin + minutes * 60_000L)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            put(CalendarContract.Events.HAS_ALARM, 1)
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: return null
        val id = ContentUris.parseId(uri)
        context.contentResolver.insert(
            CalendarContract.Reminders.CONTENT_URI,
            ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, id)
                put(CalendarContract.Reminders.MINUTES, reminderMinutes)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            },
        )
        return id
    }

    fun createEvent(title: String, daysFromToday: Int, hour: Int, minute: Int, minutes: Int): String {
        if (!granted(Manifest.permission.WRITE_CALENDAR)) return "Allow the Calendar permission in Settings first"
        val calendar = writableCalendar() ?: return "There's no calendar on this phone to add events to. Add a Google account first"
        val begin = startTime(daysFromToday, hour, minute)
        val id = insertEvent(calendar, title, begin, minutes, 10) ?: return "The event couldn't be saved"
        return "Added \"$title\" on ${whenText(begin)} (event $id)"
    }

    fun createReminder(text: String, daysFromToday: Int, hour: Int, minute: Int): String {
        val begin = startTime(daysFromToday, hour, minute)
        if (begin < System.currentTimeMillis()) return "That time has already passed"
        val calendar = writableCalendar()
        if (calendar != null) {
            val id = insertEvent(calendar, "Reminder: $text", begin, 15, 0) ?: return "The reminder couldn't be saved"
            return "I'll remind you to $text on ${whenText(begin)} (event $id)"
        }
        // Without a calendar, an alarm works for anything in the next 24 hours.
        if (begin - System.currentTimeMillis() > 86_400_000L) return "Reminders more than a day away need a calendar. Add a Google account first"
        val alarm = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Reminder: $text")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return if (start(alarm)) "I set an alarm at ${whenText(begin)} to remind you to $text" else "No alarm app was found"
    }

    fun deleteEvent(id: Long): String {
        if (!granted(Manifest.permission.WRITE_CALENDAR)) return "Allow the Calendar permission in Settings first"
        val rows = context.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null)
        return if (rows > 0) "Deleted event $id" else "There's no event with id $id"
    }

    // ---- Calls, websites, apps, memory

    @Suppress("DEPRECATION")
    /**
     * Switches the speakerphone for the current call. It waits a few seconds for a call that is just starting,
     * then uses the audio settings, and if the phone ignores them, taps the Speaker button in the call screen.
     */
    suspend fun speakerphone(on: Boolean): String {
        val audio = context.getSystemService(AudioManager::class.java)
        fun inCall() = audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION
        fun speakerOn(): Boolean {
            @Suppress("DEPRECATION")
            val old = audio.isSpeakerphoneOn
            return old || audio.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        }
        var waited = 0
        while (!inCall() && waited < 8_000) {
            delay(250)
            waited += 250
        }
        if (!inCall()) return "No call is active, so the speaker wasn't changed"
        if (speakerOn() == on) return if (on) "The speaker is on" else "The speaker is off"
        if (on) {
            audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?.let { audio.setCommunicationDevice(it) }
        } else {
            audio.clearCommunicationDevice()
        }
        @Suppress("DEPRECATION")
        audio.isSpeakerphoneOn = on
        delay(400)
        if (speakerOn() == on) return if (on) "Turned on the speaker" else "Turned off the speaker"
        // Some phones only let the call screen change it, so press its Speaker button.
        val service = AutomationService.instance
        if (service != null && service.tapAny(listOf("speaker", "speakerphone", "loudspeaker", "speaker on", "audio"))) {
            delay(500)
            if (speakerOn() == on) return if (on) "Turned on the speaker" else "Turned off the speaker"
            return "Pressed the Speaker button"
        }
        return "I couldn't change the speaker. Tap Speaker on the call screen"
    }

    fun callControl(action: String): String {
        if (!granted(Manifest.permission.ANSWER_PHONE_CALLS)) return "Allow Answer calls in Settings first"
        val audio = context.getSystemService(AudioManager::class.java)
        if (audio.mode != AudioManager.MODE_RINGTONE) return "No call is ringing"
        val telecom = context.getSystemService(TelecomManager::class.java)
        return if (action == "answer") {
            telecom.acceptRingingCall()
            "Answered the call"
        } else {
            if (telecom.endCall()) "Rejected the call" else "The call couldn't be rejected"
        }
    }

    fun openWebsite(url: String): String {
        val address = WebAddress.normalized(url)
        return if (start(Intent(Intent.ACTION_VIEW, Uri.parse(address)))) "Opened $address in the browser" else "No browser was found"
    }

    fun listApps(query: String?): String {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val names = context.packageManager.queryIntentActivities(launcher, 0)
            .map { it.loadLabel(context.packageManager).toString() }.distinct().sortedBy { it.lowercase() }
        if (query.isNullOrBlank()) return "${names.size} apps: ${names.take(60).joinToString(", ")}"
        val matches = names.filter { it.contains(query, ignoreCase = true) }.ifEmpty { ContactMatcher.top(query, names) }
        return if (matches.isEmpty()) "No installed app matches $query" else "Installed: ${matches.take(10).joinToString(", ")}"
    }

    fun remember(fact: String, utterance: String): String {
        if (!MemoryStore.userAskedToRemember(utterance)) return "I only save things when you ask me to remember them"
        return MemoryStore.add(context, fact)
    }

    fun forget(fact: String): String = MemoryStore.forget(context, fact)

    companion object {
        /** The last search results, so "open the second one" works across steps and requests. */
        @Volatile
        var lastFiles: List<FoundFile> = emptyList()
    }
}
