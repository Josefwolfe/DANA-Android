package com.dana.ai

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.view.KeyEvent
import android.provider.AlarmClock
import android.net.Uri
import android.app.NotificationManager
import android.os.Build
import android.os.BatteryManager
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** DANA Alpha: اجرای سبک، بدون ضبط دائمی یا دسترسی مخفی. */
class MainActivity : Activity() {
    companion object {
        @Volatile var visibleNow: Boolean = false
            private set
    }
    private enum class DialogState { COMMAND, NAME, PICK_CONTACT, ALIAS_NAME, ALIAS_CONFIRM, SMS_TEXT, CONFIRM_CALL, CONFIRM_SMS }
    private enum class ContactPurpose { CALL, SMS }
    private var state = DialogState.COMMAND
    private var purpose = ContactPurpose.CALL
    private var pendingContactName = ""
    private var pendingSmsText: String? = null
    private var candidateContacts = emptyList<FoundContact>()
    private var selectedContact: FoundContact? = null
    private var aliasToSave: String? = null
    private var aliasContactPendingConsent: FoundContact? = null
    private var queuedPermissionAction: (() -> Unit)? = null
    private var lastWasTorch = false
    private var torchEnabled = false
    private var appVisible = false
    private var aiRequestActive = false
    private var relistenAfterResponse = false
    private lateinit var conversationButton: Button
    private lateinit var wakeButton: Button

    private lateinit var memory: UserMemory
    private lateinit var gatewaySecrets: SecureTokenStore
    private lateinit var finder: ContactFinder
    private lateinit var voice: DanaVoice
    private lateinit var speech: SpeechManager
    private lateinit var offlineSpeech: OfflineSpeechManager
    private var installingWakeModel = false
    private lateinit var testLog: TestLog
    private lateinit var personalStore: PersonalStore
    private lateinit var reminderStore: ReminderStore
    private lateinit var reminderScheduler: ReminderScheduler
    private lateinit var quickAccess: QuickAccessNotification
    private lateinit var greeting: TextView
    private lateinit var status: TextView
    private lateinit var heard: TextView
    private lateinit var answer: TextView
    private lateinit var namePanel: View
    private lateinit var nameInput: EditText
    private lateinit var commandInput: EditText
    private lateinit var drivingButton: Button
    private lateinit var restButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        memory = UserMemory(this)
        gatewaySecrets = SecureTokenStore(this)
        finder = ContactFinder(this)
        testLog = TestLog(this)
        personalStore = PersonalStore(this)
        reminderStore = ReminderStore(this)
        reminderScheduler = ReminderScheduler(this)
        quickAccess = QuickAccessNotification(this)
        greeting = findViewById(R.id.greeting)
        status = findViewById(R.id.statusText)
        heard = findViewById(R.id.heardText)
        answer = findViewById(R.id.answerText)
        namePanel = findViewById(R.id.namePanel)
        nameInput = findViewById(R.id.nameInput)
        commandInput = findViewById(R.id.commandInput)
        drivingButton = findViewById(R.id.drivingButton)
        restButton = findViewById(R.id.sleepButton)
        refreshUserUI()
        conversationButton = findViewById(R.id.conversationModeButton)
        updateConversationButton()
        conversationButton.setOnClickListener {
            memory.conversationMode = !memory.conversationMode
            relistenAfterResponse = false
            updateConversationButton()
            if (memory.conversationMode && !voice.isReady()) {
                say("حالت مکالمه فعال شد، اما برای پاسخ صوتی اول باید صدای فارسی گوشی را نصب کنی.")
            } else {
                say(if (memory.conversationMode)
                    "مکالمه صوتی فعال شد. بعد از پاسخ، تا وقتی دانا باز باشد دوباره گوش می‌دهم."
                    else "مکالمه پیوسته خاموش شد. برای فرمان بعدی دکمه میکروفون را بزن.")
            }
        }
        findViewById<Button>(R.id.voiceSettingsButton).setOnClickListener { showPersianVoices() }

        offlineSpeech = OfflineSpeechManager(
            onResult = { phrase -> if (appVisible) receiveSpeech(phrase) },
            onStatus = { message -> if (appVisible) status.text = message },
            onError = { message -> if (appVisible) {
                relistenAfterResponse = false
                status.text = message
                answer.text = "دانا: $message"
                if (::voice.isInitialized && voice.isReady()) voice.speak(message)
            } })
        speech = SpeechManager(this,
            onResult = { phrase -> runOnUiThread { receiveSpeech(phrase) } },
            onStatus = { value -> runOnUiThread { status.text = value } },
            onError = { error -> runOnUiThread {
                if (!appVisible) return@runOnUiThread
                // Android speech services may lack the Persian language pack. Use the user's
                // imported Vosk model instead of reporting a dead end.
                val root = getDir(DanaWakeService.MODEL_DIR, Context.MODE_PRIVATE)
                if ((error.contains("زبان فارسی") || error.contains("بسته تشخیص فارسی")) &&
                    WakeModelInstaller.installed(root) && !offlineSpeech.isActive()) {
                    speech.close()
                    offlineSpeech.listen(root)
                } else {
                    relistenAfterResponse = false // Never loop on a recognition error.
                    status.text = error
                    answer.text = "دانا: $error"
                    if (::voice.isInitialized && voice.isReady()) voice.speak(error)
                }
            } })
        voice = DanaVoice(this) { available ->
            if (!available) {
                status.text = "پاسخ صوتی غیرفعال: صدای فارسی موتور گفتار نصب نیست. دکمه تنظیم صدای فارسی را بزن."
                answer.text = "دانا: برای شنیدن جواب‌ها باید صدای فارسی گوشی را فعال کنی."
            } else if (memory.name.isNullOrBlank()) {
                say("سلام! من دانا هستم. دوست داری چه اسمی صدات کنم؟")
            } else {
                say("سلام ${memory.name}، آماده ام.")
            }
        }

        findViewById<Button>(R.id.saveNameButton).setOnClickListener { saveName(nameInput.text.toString()) }
        findViewById<Button>(R.id.voiceNameButton).setOnClickListener {
            state = DialogState.NAME
            say("اسمت رو بگو.") { requestMicAndListen() }
        }
        findViewById<Button>(R.id.micButton).setOnClickListener {
            voice.stop()
            requestMicAndListen()
        }
        findViewById<Button>(R.id.runCommandButton).setOnClickListener {
            val phrase = commandInput.text.toString().trim()
            if (phrase.isBlank()) {
                answer.text = "ابتدا فرمانی بنویس."
            } else {
                voice.stop()
                commandInput.text.clear()
                receiveSpeech(phrase)
            }
        }
        findViewById<Button>(R.id.timeButton).setOnClickListener { execute(DanaAction.Time) }
        findViewById<Button>(R.id.torchButton).setOnClickListener {
            execute(DanaAction.Flashlight(!torchEnabled))
        }
        findViewById<Button>(R.id.cameraButton).setOnClickListener { execute(DanaAction.Camera) }
        drivingButton.setOnClickListener {
            memory.drivingMode = !memory.drivingMode
            refreshModes()
            say(if (memory.drivingMode) "حالت رانندگی فعال شد؛ پاسخ ها کوتاه تر می شوند." else "حالت رانندگی خاموش شد.")
        }
        restButton.setOnClickListener {
            memory.restMode = !memory.restMode
            refreshModes()
            say(if (memory.restMode) "حالت استراحت فعال شد؛ پاسخ ها کوتاه تر می شوند." else "حالت استراحت خاموش شد.")
        }
        findViewById<Button>(R.id.contactsButton).setOnClickListener { execute(DanaAction.Contacts) }
        findViewById<Button>(R.id.calendarButton).setOnClickListener { execute(DanaAction.Calendar) }
        findViewById<Button>(R.id.batteryButton).setOnClickListener { execute(DanaAction.Battery) }
        findViewById<Button>(R.id.notesButton).setOnClickListener { execute(DanaAction.NoteList) }
        findViewById<Button>(R.id.tasksButton).setOnClickListener { execute(DanaAction.TaskList) }
        findViewById<Button>(R.id.notificationButton).setOnClickListener {
            say("برای خواندن اعلان ها، دسترسی دانا را در صفحه بعد فعال کن.")
            safeLaunch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.notificationSummaryButton).setOnClickListener {
            val summary = DanaNotificationService.active?.safeSummary()
                ?: "دسترسی اعلان ها فعال نیست؛ از تنظیمات دسترسی بده."
            answer.text = summary
            say(summary)
        }
        findViewById<Button>(R.id.nameChangeButton).setOnClickListener {
            namePanel.visibility = View.VISIBLE
            nameInput.setText(memory.name ?: "")
            nameInput.requestFocus()
        }
        findViewById<Button>(R.id.remindersButton).setOnClickListener { listReminders() }
        findViewById<Button>(R.id.settingsButton).setOnClickListener { showPersonalSettings() }
        findViewById<Button>(R.id.quickAccessButton).setOnClickListener { toggleQuickAccess() }
        wakeButton = findViewById(R.id.wakeButton)
        wakeButton.setOnClickListener { toggleWakeMode() }
        findViewById<Button>(R.id.importWakeModelButton).setOnClickListener { importWakeModel() }
        findViewById<Button>(R.id.aiSettingsButton).setOnClickListener { showCloudAiSettings() }
        updateWakeButton()

        findViewById<Button>(R.id.logButton).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("گزارش آزمایشی دانا")
                .setMessage(testLog.report())
                .setNegativeButton("بستن", null)
                .setNeutralButton("پاک کردن آمار") { _, _ -> testLog.clear(); say("گزارش آزمایش پاک شد.") }
                .show()
        }
    }

    private fun updateConversationButton() {
        if (::conversationButton.isInitialized) conversationButton.text =
            if (memory.conversationMode) "🎧 مکالمه صوتی پیوسته: روشن" else "🎧 مکالمه صوتی پیوسته: خاموش"
    }

    private fun refreshModes() {
        findViewById<TextView>(R.id.privacyStatus).text =
            if (memory.privateMode) "حالت خصوصی: روشن • گزارش و یادگیری غیرفعال" else
                "حالت خصوصی: خاموش • حافظه قابل تنظیم"
        drivingButton.text = if (memory.drivingMode) "حالت رانندگی: روشن" else "حالت رانندگی: خاموش"
        restButton.text = if (memory.restMode) "حالت استراحت: روشن" else "حالت استراحت: خاموش"
    }

    private fun refreshUserUI() {
        val name = memory.name
        greeting.text = if (name == null) "سلام، من دانا هستم" else "سلام $name، آماده ام"
        namePanel.visibility = if (name == null) View.VISIBLE else View.GONE
        refreshModes()
    }

    private fun saveName(input: String) {
        val cleaned = CommandEngine.normalize(input)
            .replace(Regex("^(?:اسم من|اسمم|من) "), "")
            .replace(Regex("(?: هستم| است)$"), "").trim()
        if (cleaned.isBlank() || cleaned.length > 35) {
            say("لطفا یک اسم کوتاه و مشخص بگو.")
            return
        }
        memory.name = cleaned
        state = DialogState.COMMAND
        refreshUserUI()
        say("خوشحالم $cleaned. اسمت رو ذخیره کردم و آماده ام.")
    }

    private fun requestMicAndListen() {
        val root = getDir(DanaWakeService.MODEL_DIR, Context.MODE_PRIVATE)
        val voskInstalled = WakeModelInstaller.installed(root)
        if (installingWakeModel) {
            say("مدل فارسی هنوز در حال نصب است؛ کمی صبر کن.")
            return
        }
        if (DanaWakeService.running(this)) {
            say("بیدارباش دانا روشن است. بدون زدن دکمه بگو دانا و بعد فرمانت رو بگو؛ یا اول بیدارباش رو خاموش کن.")
            return
        }
        if (memory.privateMode && !voskInstalled && !speech.hasOnDeviceRecognizer()) {
            say("در حالت خصوصی باید مدل فارسی Vosk رو نصب کنی؛ بسته آفلاین اندروید روی گوشی در دسترس نیست.")
            return
        }
        // Vosk runs fully on device. Only ask for online speech-provider consent when
        // neither a local model nor the Android on-device recognizer can be used.
        if (!memory.privateMode && !memory.microphoneDisclosureAccepted && !voskInstalled) {
            AlertDialog.Builder(this).setTitle("حریم خصوصی تشخیص صوتی")
                .setMessage("موتور گفتار گوشی ممکن است صدا را برای پردازش ارسال کند. برای فرمان کاملاً آفلاین، مدل فارسی Vosk را از ZIP نصب کن. اجازه استفاده از موتور گوشی را می‌دهی؟")
                .setNegativeButton("فعلاً نه", null)
                .setPositiveButton("اجازه می‌دهم") { _, _ ->
                    memory.microphoneDisclosureAccepted = true
                    requestMicAndListen()
                }.show()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 401)
            return
        }
        // Private mode is local-only. Offline Vosk also works in ordinary mode when
        // the user has not consented to speech-provider processing.
        if (voskInstalled && (memory.privateMode || !memory.microphoneDisclosureAccepted)) {
            status.text = "در حال بارگذاری موتور فارسی آفلاین…"
            offlineSpeech.listen(root)
        } else {
            status.text = "در انتظار صدای فارسی…"
            speech.listen(allowOnline = !memory.privateMode && memory.microphoneDisclosureAccepted)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        when (requestCode) {
            401 -> if (granted) requestMicAndListen() else say("برای شنیدن فرمان باید مجوز میکروفون رو فعال کنی.")
            407, 408 -> {
                val next = queuedPermissionAction
                queuedPermissionAction = null
                if (granted) next?.invoke() else say("مجوز میکروفون و اعلان برای بیدارباش اختیاری ضروری است.")
            }
            402, 403 -> {
                val next = queuedPermissionAction
                queuedPermissionAction = null
                if (granted) next?.invoke() else if (requestCode == 403) {
                    selectedContact?.let { openDialer(it) }
                } else say("بدون مجوز مخاطبین نمی تونم اسم ها رو جستجو کنم.")
            }
            405, 406, 410 -> {
                val next = queuedPermissionAction
                queuedPermissionAction = null
                if (granted) next?.invoke() else say("برای این قابلیت اجازه اعلان لازم است.")
            }
            409 -> {
                val next = queuedPermissionAction
                queuedPermissionAction = null
                if (granted) next?.invoke() else say("برای جست‌وجو و پخش آهنگ‌های حافظه، اجازه دسترسی به موسیقی را فعال کن.")
            }
            404 -> {
                val next = queuedPermissionAction
                queuedPermissionAction = null
                if (granted) next?.invoke() else say("برای کنترل چراغ قوه به مجوز دوربین نیاز دارم.")
            }
        }
    }

    private fun askPermission(permission: String, requestCode: Int, onGranted: () -> Unit) {
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) onGranted()
        else {
            queuedPermissionAction = onGranted
            requestPermissions(arrayOf(permission), requestCode)
        }
    }

    private fun receiveSpeech(phrase: String) {
        relistenAfterResponse = memory.conversationMode && state == DialogState.COMMAND
        heard.text = "شنیدم: $phrase"
        status.text = "در حال پردازش…"
        val normalized = CommandEngine.normalize(phrase)
        if (normalized in setOf("لغو", "لغو کن", "بی خیال", "منصرف شدم")) {
            if (state == DialogState.COMMAND) say("کاری در انتظار تأیید نیست.") else cancelPending()
            return
        }
        when (state) {
            DialogState.NAME -> saveName(phrase)
            DialogState.PICK_CONTACT -> selectSpokenContact(normalized)
            DialogState.ALIAS_NAME -> {
                val full = normalized.replace(Regex("^(?:اسمش |اسم کاملش )"), "")
                searchContacts(full, fromAlias = true)
            }
            DialogState.ALIAS_CONFIRM -> when {
                isYes(normalized) -> {
                    val contact = aliasContactPendingConsent
                    val alias = aliasToSave
                    aliasContactPendingConsent = null
                    aliasToSave = null
                    if (contact != null) {
                        if (AliasConsentPolicy.allowed(alias, explicitlyConfirmed = true,
                                privateMode = memory.privateMode,
                                learningEnabled = memory.learningEnabled))
                            memory.saveAlias(requireNotNull(alias), contact.id, contact.name)
                        chooseContact(contact)
                    } else cancelPending()
                }
                isNo(normalized) -> {
                    val contact = aliasContactPendingConsent
                    aliasContactPendingConsent = null
                    aliasToSave = null
                    if (contact != null) chooseContact(contact) else cancelPending()
                }
                else -> ask("برای ذخیره نام مستعار فقط بگو بله یا نه.")
            }
            DialogState.SMS_TEXT -> {
                pendingSmsText = phrase.trim().removePrefix("بگو ")
                if (pendingSmsText.isNullOrBlank()) ask("متن پیام رو بگو.")
                else confirmSms()
            }
            DialogState.CONFIRM_CALL -> when {
                isYes(normalized) -> { state = DialogState.COMMAND; selectedContact?.let { placeCall(it) } }
                isNo(normalized) -> cancelPending()
                else -> ask("فقط بگو بله یا نه.")
            }
            DialogState.CONFIRM_SMS -> when {
                isYes(normalized) -> { state = DialogState.COMMAND; selectedContact?.let { openSmsDraft(it, pendingSmsText.orEmpty()) } }
                isNo(normalized) -> cancelPending()
                "تغییر" in normalized || "ویرایش" in normalized -> {
                    state = DialogState.SMS_TEXT
                    ask("متن جدید پیام رو بگو.")
                }
                else -> ask("بگو بله، نه یا تغییر پیام.")
            }
            DialogState.COMMAND -> {
                if (normalized == "خاموشش کن" && lastWasTorch) execute(DanaAction.Flashlight(false))
                else {
                    val local = CommandEngine.parse(phrase)
                    if (local is DanaAction.Unknown && memory.cloudAiEnabled && !memory.privateMode &&
                        !SafetyPolicy.explicitlyNegated(phrase)) resolveWithCloud(phrase)
                    else execute(local)
                }
            }
        }
    }

    private fun resolveWithCloud(phrase: String) {
        if (aiRequestActive) { say("دارم فرمان قبلی رو بررسی می‌کنم."); return }
        if (!CloudIntentClient.isValidEndpoint(memory.cloudEndpoint)) {
            execute(DanaAction.Unknown(phrase)); return
        }
        aiRequestActive = true
        status.text = "در حال تحلیل اختیاری فرمان با سرور…"
        Thread {
            val inferred = runCatching {
                CloudIntentClient(memory.cloudEndpoint, gatewaySecrets.getToken()).infer(phrase)
            }.getOrNull()
            runOnUiThread {
                aiRequestActive = false
                if (isFinishing || isDestroyed || memory.privateMode || !memory.cloudAiEnabled) return@runOnUiThread
                // AI cannot execute anything directly; existing CALL/SMS paths require user confirmation.
                execute(inferred ?: DanaAction.Unknown(phrase))
            }
        }.start()
    }

    private fun isYes(s: String) = s in setOf("بله", "آره", "اره", "تایید", "تأیید", "باشه", "انجام بده", "اوکی")
    private fun isNo(s: String) = s in setOf("نه", "خیر", "لغو", "لغو کن", "نکن", "بی خیال")

    private fun cancelPending() {
        state = DialogState.COMMAND
        pendingSmsText = null
        candidateContacts = emptyList()
        selectedContact = null
        aliasToSave = null
        aliasContactPendingConsent = null
        say("لغو شد.")
    }

    private fun execute(action: DanaAction) {
        val start = System.nanoTime()
        val type = action.javaClass.simpleName
        try {
            when (action) {
                DanaAction.Greeting -> ask("سلام ${friendlyName()}، گوش می دم. چه کاری داری؟")
                is DanaAction.Answer -> say(action.text)
                DanaAction.Time -> {
                    val time = SimpleDateFormat("HH:mm", Locale("fa", "IR")).format(Date())
                    say(if (memory.responseStyle == "short") "ساعت $time" else "${friendlyName()}، الان ساعت $time است.")
                }
                DanaAction.Camera -> {
                    if (safeLaunch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)))
                        say("دوربین رو باز کردم.")
                }
                DanaAction.Contacts -> {
                    if (safeLaunch(Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI)))
                        say("دفترچه تلفن رو باز کردم.")
                }
                DanaAction.Calendar -> {
                    val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR)
                    if (!safeLaunch(i)) safeLaunch(Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI))
                }
                DanaAction.Battery -> reportBattery()
                DanaAction.TodayDate -> {
                    val date = try {
                        android.icu.text.SimpleDateFormat("EEEE d MMMM yyyy",
                            android.icu.util.ULocale("fa_IR@calendar=persian")).format(Date())
                    } catch (_: Exception) {
                        SimpleDateFormat("EEEE d MMMM yyyy", Locale("fa", "IR")).format(Date())
                    }
                    say("امروز $date است.")
                }
                DanaAction.Help -> say("می‌توانم ساعت، تاریخ و باتری را بگویم، برنامه‌ها را باز کنم، " +
                    "چراغ قوه و صدا را کنترل کنم، تماس و پیش‌نویس پیام آماده کنم، " +
                    "یادآوری و تایمر بسازم، در وب و یوتیوب جستجو کنم و مسیریابی را باز کنم.")
                is DanaAction.WebSearch -> {
                    if (safeLaunch(Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://www.google.com/search?q=${Uri.encode(action.query)}"))))
                        say("نتیجه‌های جستجو را در مرورگر باز کردم.")
                }
                is DanaAction.YouTubeSearch -> {
                    if (safeLaunch(Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(action.query)}"))))
                        say("نتیجه‌های یوتیوب را باز کردم.")
                }
                is DanaAction.Navigate -> {
                    val location = Uri.encode(action.destination)
                    val maps = Intent(Intent.ACTION_VIEW,
                        Uri.parse("geo:0,0?q=$location"))
                    if (safeLaunch(maps)) say("مقصد را در نقشه باز کردم؛ مسیر را تأیید کن.")
                }
                is DanaAction.App -> openApp(action.name)
                is DanaAction.Flashlight -> askPermission(Manifest.permission.CAMERA, 404) { setTorch(action.enabled) }
                is DanaAction.Volume -> changeVolume(action.direction)
                is DanaAction.MediaMute -> setMediaMuted(action.enabled)
                is DanaAction.Brightness -> changeBrightness(action)
                is DanaAction.Call -> beginContactAction(action.contact, ContactPurpose.CALL, null)
                is DanaAction.Sms -> beginContactAction(action.contact, ContactPurpose.SMS, action.message)
                is DanaAction.Settings -> {
                    val i = when (action.section) {
                        "wifi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
                        "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                        "airplane", "mobile_data" -> Intent(Settings.ACTION_WIRELESS_SETTINGS)
                        "hotspot" -> Intent("android.settings.TETHER_SETTINGS")
                        "location" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                        "nfc" -> Intent(Settings.ACTION_NFC_SETTINGS)
                        else -> Intent(Settings.ACTION_SETTINGS)
                    }
                    if (safeLaunch(i)) say("صفحه تنظیمات مربوط باز شد؛ تغییر تنظیمات رو خودت تأیید کن.")
                }
                is DanaAction.Media -> controlMedia(action.command)
                is DanaAction.Music -> playLocalMusic(action.query)
                is DanaAction.Connectivity -> openRadioPanel(action.radio, action.enabled)
                is DanaAction.DatedAlarm -> {
                    val now = System.currentTimeMillis()
                    val at = if (action.dayOffset != null)
                        DatedAlarmTime.nextDay(now, action.dayOffset, action.hour, action.minute)
                    else action.weekday?.let {
                        DatedAlarmTime.nextWeekday(now, it, action.hour, action.minute)
                    }
                    if (at == null) say("این ساعت گذشته یا تاریخ معتبر نیست؛ زمان دیگری بگو.")
                    else scheduleDatedAlarm(at, action.label)
                }
                is DanaAction.PersianDateAlarm -> {
                    val at = DatedAlarmTime.nextPersianDate(System.currentTimeMillis(),
                        action.month, action.day, action.hour, action.minute)
                    if (at == null) say("این تاریخ در تقویم معتبر نیست.")
                    else scheduleDatedAlarm(at, action.label)
                }
                is DanaAction.Alarm -> setAlarm(action.hour, action.minute, action.label)
                is DanaAction.Timer -> setTimer(action.minutes, action.label)
                is DanaAction.NoteAdd -> {
                    if (memory.privateMode) say("حالت خصوصی فعاله؛ برای ذخیره یادداشت اون رو خاموش کن.")
                    else { personalStore.addNote(action.text); say("یادداشت ذخیره شد.") }
                }
                DanaAction.NoteList -> speakNotes()
                is DanaAction.TaskAdd -> {
                    if (memory.privateMode) say("حالت خصوصی فعاله؛ برای ذخیره کار اون رو خاموش کن.")
                    else { personalStore.addTask(action.text); say("کار جدید اضافه شد.") }
                }
                DanaAction.TaskList -> speakTasks()
                is DanaAction.TaskDone -> {
                    say(if (personalStore.markFirstMatchingDone(action.text)) "انجام شده ثبت شد." else "این کار رو در فهرست پیدا نکردم.")
                }
                is DanaAction.ReminderIn -> scheduleReminder(
                    ReminderTimeResolver.inMinutes(System.currentTimeMillis(), action.minutes), action.text)
                is DanaAction.ReminderAt -> {
                    val at = ReminderTimeResolver.atLocal(System.currentTimeMillis(), action.dayOffset, action.hour, action.minute)
                    if (at <= System.currentTimeMillis()) say("این ساعت گذشته؛ فردا یا زمان جدیدی بگو.")
                    else scheduleReminder(at, action.text)
                }
                DanaAction.ReminderList -> listReminders()
                is DanaAction.PrivateMode -> {
                    memory.privateMode = action.enabled
                    // Privacy mode must disable network processing, NOT disable local Vosk.
                    speech.cancel()
                    offlineSpeech.cancel()
                    if (DanaWakeService.running(this)) {
                        startService(Intent(this, DanaWakeService::class.java)
                            .setAction(DanaWakeService.ACTION_PRIVACY_CHANGED))
                    }
                    refreshModes()
                    say(if (action.enabled) "حالت خصوصی روشن شد. فرمان‌ها با مدل Vosk فقط روی گوشی پردازش می‌شن؛ هوش مصنوعی آنلاین، گزارش و یادگیری خاموش هستند."
                        else "حالت خصوصی خاموش شد؛ بیدارباش آفلاین همچنان در دسترس است.")
                }
                is DanaAction.LearningMode -> {
                    memory.learningEnabled = action.enabled
                    say(if (action.enabled) "یادگیری نام های مستعار فعال شد." else "یادگیری نام های مستعار غیرفعال شد.")
                }
                is DanaAction.ResponseStyle -> {
                    memory.responseStyle = action.style
                    say(if (action.style == "short") "از این به بعد کوتاه تر جواب می دم." else "لحن پاسخ رو دوستانه تنظیم کردم.")
                }
                is DanaAction.Unknown -> {
                    say("این فرمان هنوز در موتور آفلاین دانا شناخته نمی‌شه. می‌تونی کوتاه‌تر بگی یا بگی راهنما تا فرمان‌های قابل اجرا رو بگم.")
                    logAction("UNKNOWN", "unsupported", (System.nanoTime() - start) / 1_000_000)
                    return
                }
            }
            // Dispatch is not proof that an external application completed its work.
            logAction(type, "dispatched", (System.nanoTime() - start) / 1_000_000)
        } catch (e: Exception) {
            logAction(type, "error", (System.nanoTime() - start) / 1_000_000)
            say("عملیات انجام نشد: ${e.localizedMessage ?: "دسترسی یا برنامه لازم موجود نیست"}")
        }
    }


    private fun logAction(type: String, result: String, elapsed: Long) {
        if (!memory.privateMode) testLog.add(type, result, elapsed)
    }

    /** Reminder alerts use inexact Android alarms: delivery may be delayed by Doze. */
    private fun scheduleReminder(whenMillis: Long, text: String) {
        if (text.isBlank() || text.length > 160) { say("یادآوری باید متن کوتاهی داشته باشه."); return }
        if (whenMillis <= System.currentTimeMillis()) { say("زمان یادآوری باید در آینده باشه."); return }
        val notifier = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 24 && !notifier.areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)) {
            say("اعلان های دانا در تنظیمات گوشی غیرفعاله؛ ابتدا اون ها رو فعال کن.")
            return
        }
        val perform = {
            val item = reminderStore.add(text, whenMillis)
            reminderScheduler.schedule(item)
            val label = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale("fa", "IR")).format(Date(whenMillis))
            say("یادآوری «${item.text}» برای حدود $label ثبت شد. در حالت ذخیره باتری ممکنه کمی دیرتر نمایش داده بشه.")
        }
        if (Build.VERSION.SDK_INT >= 33) askPermission(Manifest.permission.POST_NOTIFICATIONS, 405, perform)
        else perform()
    }

    private fun listReminders() {
        val items = reminderStore.pending().take(8)
        val formatter = SimpleDateFormat("MM/dd HH:mm", Locale("fa", "IR"))
        val summary = if (items.isEmpty()) "یادآوری در انتظار نداری." else
            "یادآوری های بعدی: " + items.joinToString("؛ ") {
                "${it.text}، ${formatter.format(Date(it.dueAt))}"
            }
        answer.text = summary
        say(summary)
    }

    private fun toggleQuickAccess() {
        if (memory.quickAccessEnabled) {
            quickAccess.cancel()
            memory.quickAccessEnabled = false
            refreshModes()
            say("میانبر اعلان دانا حذف شد.")
            return
        }
        val enable = {
            if (quickAccess.show()) {
                memory.quickAccessEnabled = true
                refreshModes()
                say("میانبر دانا به اعلان ها اضافه شد؛ برای فرمان دادن آن را لمس کن.")
            } else say("ابتدا اعلان های دانا را در تنظیمات گوشی فعال کن.")
        }
        if (Build.VERSION.SDK_INT >= 33)
            askPermission(Manifest.permission.POST_NOTIFICATIONS, 406, enable)
        else enable()
    }

    private fun updateWakeButton() {
        if (::wakeButton.isInitialized) {
            wakeButton.text = if (DanaWakeService.running(this))
                "🟢 توقف شنود «دانا»" else "🎙 فعال‌سازی بیدارباش «دانا»"
        }
    }

    private fun stopWakeService() {
        memory.wakeAutoResume = false
        stopService(Intent(this, DanaWakeService::class.java))
        // The service itself clears its running flag in onDestroy.
        window.decorView.postDelayed({ updateWakeButton() }, 250)
    }

    private fun importWakeModel() {
        if (installingWakeModel) { say("نصب مدل هنوز تمام نشده؛ صبر کن."); return }
        if (DanaWakeService.running(this)) {
            say("قبل از تعویض مدل، بیدارباش را متوقف کن."); return
        }
        AlertDialog.Builder(this).setTitle("نصب مدل فارسی")
            .setMessage("فایل vosk-model-small-fa-0.42.zip را از سایت رسمی Vosk دریافت کن (حدود ۵۳ مگابایت). بعد ZIP را انتخاب کن؛ مدل در حافظه خصوصی دانا استخراج می‌شود. فایل ZIP خودکار بارگیری نمی‌شود.")
            .setPositiveButton("انتخاب فایل ZIP") { _, _ ->
                val choose = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/zip"
                }
                startActivityForResult(choose, 709)
            }.setNeutralButton("صفحه رسمی مدل‌ها") { _, _ ->
                safeLaunch(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://alphacephei.com/vosk/models")))
            }.setNegativeButton("انصراف", null).show()
    }

    private fun toggleWakeMode() {
        if (DanaWakeService.running(this)) { stopWakeService(); return }
        if (installingWakeModel) { say("صبر کن نصب مدل کامل بشه."); return }
        val modelDirectory = getDir(DanaWakeService.MODEL_DIR, Context.MODE_PRIVATE)
        if (!WakeModelInstaller.installed(modelDirectory)) {
            say("اول مدل کوچک فارسی را از فایل ZIP نصب کن."); return
        }
        AlertDialog.Builder(this)
            .setTitle("فعال‌سازی بیدارباش بدون دکمه")
            .setMessage("یک‌بار این حالت را روشن کن؛ بعد با گفتن «دانا» یا «سلام دانا» فرمان بده. میکروفون در سرویس پیش‌زمینه با اعلان دائمی فعال می‌ماند و مصرف باتری افزایش می‌یابد. اگر HyperOS سرویس را ببندد، شنود قطع می‌شود. هنگام باز کردن دوباره دانا، در صورت داشتن مجوز، حالت شنود از سر گرفته می‌شود؛ پس از بوت، شروع میکروفون در پس‌زمینه مجاز نیست. برای تشخیص دقیق‌تر فرمان‌ها، اجازه پردازش آنلاین را جداگانه تأیید کن.")
            .setNegativeButton("انصراف", null)
            .setPositiveButton("فعال کن") { _, _ ->
                val run = {
                    val afterNotification = {
                        askPermission(Manifest.permission.RECORD_AUDIO, 407) { startWakeService() }
                    }
                    if (Build.VERSION.SDK_INT >= 33)
                        askPermission(Manifest.permission.POST_NOTIFICATIONS, 408, afterNotification)
                    else afterNotification()
                }
                if (!memory.privateMode && !memory.microphoneDisclosureAccepted) {
                    AlertDialog.Builder(this).setTitle("تشخیص فرمان پس از «دانا»")
                        .setMessage("برای فهم بهتر فارسی می‌توان از موتور تشخیص گفتار گوشی استفاده کرد که ممکن است صدا را به سرویس ارائه‌دهنده بفرستد. خود کلمه بیدارباش با مدل آفلاین تشخیص داده می‌شود. کدام حالت را انتخاب می‌کنی؟")
                        .setPositiveButton("اجازه تشخیص آنلاین") { _, _ ->
                            memory.microphoneDisclosureAccepted = true
                            run()
                        }
                        .setNeutralButton("فقط آفلاین") { _, _ -> run() }
                        .setNegativeButton("انصراف", null).show()
                } else run()
            }.show()
    }

    private fun startWakeService() {
        try {
            // Vosk models can consume hundreds of MB. Do not keep a second Activity model
            // loaded while the always-on wake service initializes its own instance.
            offlineSpeech.releaseModel()
            getSharedPreferences("wake_runtime", MODE_PRIVATE).edit()
                .remove("last_stop_reason").putBoolean("ready", false).apply()
            val request = Intent(this, DanaWakeService::class.java).setAction(DanaWakeService.ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(request)
            else startService(request)
            memory.wakeAutoResume = true
            status.text = "در حال بارگذاری مدل فارسی… وضعیت را در اعلان دانا ببین."
            window.decorView.postDelayed({ updateWakeButton() }, 700)
            pollWakeReady(0)
        } catch (_: RuntimeException) {
            memory.wakeAutoResume = false
            // Android 12+ and some OEMs can reject foreground microphone startup.
            say("سیستم اجازه شروع بیدارباش نداد؛ برنامه باید باز باشد و مجوز میکروفون فعال باشد.")
            updateWakeButton()
        }
    }

    private fun pollWakeReady(attempt: Int) {
        if (!appVisible || attempt > 100) return
        val runtime = getSharedPreferences("wake_runtime", MODE_PRIVATE)
        val failure = runtime.getString("last_stop_reason", null)
        when {
            !failure.isNullOrBlank() -> {
                status.text = "بیدارباش فعال نشد: $failure"
                answer.text = "دانا: $failure"
                updateWakeButton()
            }
            runtime.getBoolean("ready", false) -> {
                status.text = "بیدارباش فارسی فعال است؛ بگو «دانا»."
                updateWakeButton()
            }
            attempt > 10 && !runtime.getBoolean("running", false) -> {
                status.text = "سرویس بیدارباش متوقف شده؛ مجوز میکروفون و تنظیمات باتری را بررسی کن."
                updateWakeButton()
            }
            else -> {
                status.text = "مدل فارسی Vosk در حال بارگذاری است… ${attempt + 1} ثانیه"
                window.decorView.postDelayed({ pollWakeReady(attempt + 1) }, 1_000L)
            }
        }
    }

    @Deprecated("Android activity-result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 709 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (installingWakeModel) return
        installingWakeModel = true
        offlineSpeech.releaseModel()
        status.text = "در حال خواندن ZIP فارسی… برنامه را باز نگه دار."
        answer.text = "دانا: استخراج مدل ممکن است چند دقیقه طول بکشد؛ پیشرفت را همین‌جا می‌بینی."
        Thread {
            val result = runCatching {
                val source = contentResolver.openInputStream(uri) ?: error("خواندن ZIP ممکن نیست.")
                source.use { input ->
                    WakeModelInstaller.install(input,
                        getDir(DanaWakeService.MODEL_DIR, Context.MODE_PRIVATE)) { progress ->
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                val mb = progress.extractedBytes / (1024L * 1024L)
                                status.text = "${progress.stage} $mb مگابایت • ${progress.fileCount} فایل"
                            }
                        }
                    }
                }
            }
            runOnUiThread {
                installingWakeModel = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (result.isSuccess) {
                    status.text = "مدل فارسی با موفقیت نصب شد. حالا بیدارباش یا میکروفون را امتحان کن."
                    say("مدل فارسی Vosk نصب شد. حالا در حالت خصوصی هم می‌تونم فرمان آفلاین بشنوم.")
                } else {
                    val reason = result.exceptionOrNull()?.localizedMessage ?: "ZIP نامعتبر"
                    status.text = "نصب مدل ناموفق بود: $reason"
                    answer.text = "دانا: نصب مدل ناموفق بود: $reason"
                    AlertDialog.Builder(this).setTitle("خطای نصب مدل فارسی")
                        .setMessage("$reason\n\nفایل مدل کوچک فارسی را از سایت رسمی Vosk دوباره دانلود کن. فایل ZIP را استخراج نکن؛ همان ZIP را انتخاب کن. فضای خالی گوشی را هم بررسی کن.")
                        .setPositiveButton("متوجه شدم", null).show()
                }
            }
        }.start()
    }

    private fun showCloudAiSettings() {
        val choices = arrayOf(if (memory.cloudAiEnabled) "غیرفعال کردن AI آنلاین" else "فعال‌سازی AI آنلاین",
            "تنظیم آدرس سرور HTTPS", "رمز دسترسی سرور (رمزگذاری‌شده)", "وضعیت اتصال و حریم خصوصی")
        AlertDialog.Builder(this).setTitle("هوش مصنوعی اختیاری")
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> if (memory.cloudAiEnabled) {
                        memory.cloudAiEnabled = false
                        say("پردازش آنلاین خاموش شد.")
                    } else {
                        if (!CloudIntentClient.isValidEndpoint(memory.cloudEndpoint)) {
                            say("ابتدا آدرس معتبر HTTPS برای سرور شخصی هوش مصنوعی تنظیم کن.")
                        } else AlertDialog.Builder(this).setTitle("اجازه ارسال متن فرمان")
                            .setMessage("فقط وقتی دانا فرمانی را آفلاین نفهمد، متن همان فرمان به سرور HTTPS انتخابی شما ارسال می‌شود. مخاطبین و حافظه شخصی ارسال نمی‌شوند. سرور باید توسط خودتان یا ارائه‌دهنده مورداعتماد اداره شود؛ هرگز کلید API را داخل اپ نگذارید.")
                            .setPositiveButton("اجازه می‌دهم") { _, _ -> memory.cloudAiEnabled = true; say("هوش آنلاین برای فرمان‌های ناشناخته فعال شد.") }
                            .setNegativeButton("انصراف", null).show()
                    }
                    1 -> {
                        val input = EditText(this).apply {
                            setText(memory.cloudEndpoint)
                            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
                            hint = "https://your-server.example/dana/intent"
                        }
                        AlertDialog.Builder(this).setTitle("آدرس سرور AI (بدون کلید داخل گوشی)")
                            .setView(input)
                            .setPositiveButton("ذخیره") { _, _ ->
                                val url = input.text.toString().trim()
                                if (CloudIntentClient.isValidEndpoint(url)) {
                                    memory.cloudEndpoint = url
                                    memory.cloudAiEnabled = false // URL change requires fresh explicit consent.
                                    say("آدرس ذخیره شد. برای فعال‌سازی دوباره اجازه بده.")
                                } else say("آدرس HTTPS معتبر وارد کن.")
                            }.setNegativeButton("انصراف", null).show()
                    }
                    2 -> {
                        val input = EditText(this).apply {
                            hint = "رمز اختصاصی سرور (در گوشی رمزگذاری می‌شود)"
                            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                            setSingleLine(true)
                        }
                        AlertDialog.Builder(this).setTitle("رمز دسترسی درگاه AI")
                            .setMessage("رمز سرور خودت را وارد کن. این رمز با Android Keystore محافظت می‌شود؛ کلید شرکت ارائه‌دهنده مدل را اینجا نگذار. خالی بگذار تا رمز قبلی پاک شود.")
                            .setView(input).setNegativeButton("انصراف", null)
                            .setPositiveButton("ذخیره") { _, _ ->
                                if (gatewaySecrets.setToken(input.text.toString())) {
                                    memory.cloudAiEnabled = false
                                    say("رمز سرور ذخیره شد. اتصال را دوباره فعال کن.")
                                } else say("رمز ذخیره نشد؛ مقدار واردشده را بررسی کن.")
                            }.show()
                    }
                    3 -> AlertDialog.Builder(this).setTitle("پردازش آنلاین")
                        .setMessage("وضعیت: ${if (memory.cloudAiEnabled) "فعال" else "خاموش"}\nرمز سرور: ${if (gatewaySecrets.configured()) "ثبت شده" else "ثبت نشده"}\nدر حالت خصوصی همیشه غیرفعال است.\nاگر سرور پاسخ ندهد، فرمان اجرا نمی‌شود.\nتماس و پیام همچنان تأیید می‌خواهند.")
                        .setPositiveButton("بستن", null).show()
                }
            }.setNegativeButton("بستن", null).show()
    }

    private fun showPersonalSettings() {
        val privacy = if (memory.privateMode) "خاموش کردن حالت خصوصی" else "روشن کردن حالت خصوصی"
        val learning = if (memory.learningEnabled) "خاموش کردن یادگیری مخاطب" else "روشن کردن یادگیری مخاطب"
        val style = if (memory.responseStyle == "short") "پاسخ دوستانه" else "پاسخ کوتاه"
        val choices = arrayOf(privacy, learning, style,
            "نمایش وضعیت حافظه", "حذف تمام اطلاعات و یادآوری ها", "انتخاب و تست صدای فارسی",
            "اجازه اجرای صوتی برنامه‌ها در پس‌زمینه")
        AlertDialog.Builder(this).setTitle("تنظیمات شخصی دانا")
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> execute(DanaAction.PrivateMode(!memory.privateMode))
                    1 -> execute(DanaAction.LearningMode(!memory.learningEnabled))
                    2 -> execute(DanaAction.ResponseStyle(if (memory.responseStyle == "short") "friendly" else "short"))
                    3 -> AlertDialog.Builder(this).setTitle("اطلاعات محلی")
                        .setMessage("نام کاربر: ${memory.name ?: "ثبت نشده"}\nنام های مستعار: ${memory.aliasCount()}\n" +
                            "یادداشت ها: ${personalStore.notes().size}\nکارها: ${personalStore.tasks().size}\n" +
                            "یادآوری های آینده: ${reminderStore.pending().size}\n\n" +
                            "حافظه و یادداشت ها در همین گوشی ذخیره می شوند. ذخیره سازی فعلی رمزگذاری اختصاصی ندارد.")
                        .setPositiveButton("بستن", null).show()
                    4 -> confirmEraseAll()
                    5 -> showPersianVoices()
                    6 -> showHandsFreePermission()
                }
            }.setNegativeButton("انصراف", null).show()
    }

    private fun showHandsFreePermission() {
        AlertDialog.Builder(this)
            .setTitle("اجرای برنامه‌ها با صدای دانا")
            .setMessage("برای باز کردن برنامه‌هایی مثل تلگرام با فرمان صوتی وقتی دانا در پس‌زمینه است، می‌توانی سرویس دسترس‌پذیری اختیاری دانا را فعال کنی. این سرویس محتوای صفحه را نمی‌خواند، کلیک خودکار انجام نمی‌دهد و فقط برای باز کردن برنامه‌ای که خودت نام برده‌ای استفاده می‌شود. هنگام قفل بودن گوشی باید قفل را باز کنی. این دسترسی را هر زمان خواستی از تنظیمات خاموش کن. روی بعضی گوشی‌های شیائومی باید ابتدا در تنظیمات برنامه، Allow restricted settings را تأیید کنی.")
            .setNegativeButton("انصراف", null)
            .setPositiveButton("رفتن به تنظیمات") { _, _ ->
                safeLaunch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }.show()
    }

    /** Only TTS voices actually installed by the user/phone are offered. */
    private fun showPersianVoices() {
        val options = voice.choices()
        if (options.isEmpty()) {
            AlertDialog.Builder(this).setTitle("صدای فارسی")
                .setMessage("موتور فعلی صدای فارسی ندارد. از تنظیمات تبدیل متن به گفتار گوشی بسته فارسی را نصب کنید. جنسیت صدا به موتور نصب شده بستگی دارد.")
                .setPositiveButton("تنظیمات گفتار") { _, _ ->
                    if (!safeLaunch(Intent("com.android.settings.TTS_SETTINGS"))) {
                        safeLaunch(Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.tts")))
                    }
                }.setNegativeButton("بستن", null).show()
            return
        }
        val labels = options.map { (if (it.offline) "آفلاین • " else "اینترنتی • ") + it.description }.toTypedArray()
        AlertDialog.Builder(this).setTitle("انتخاب صدای دانا")
            .setMessage("صدای زن طبیعی تضمین نمی‌شود؛ صدای نصب‌شده دلخواه را انتخاب و آزمایش کنید. صدای اینترنتی ممکن است متن را به سرویس گوشی بفرستد.")
            .setItems(labels) { _, i ->
                if (voice.chooseVoice(options[i].id)) say("سلام ${friendlyName()}، این صدای جدید داناست.")
                else say("این صدا روی گوشی قابل انتخاب نبود.")
            }.setNegativeButton("بستن", null).show()
    }

    private fun confirmEraseAll() {
        AlertDialog.Builder(this).setTitle("پاک کردن تمام داده های دانا؟")
            .setMessage("نام، نام های مستعار، یادداشت ها، کارها، یادآوری ها و گزارش آزمایشی حذف می شوند. یادآوری های آینده هم لغو می شوند؛ این کار برگشت پذیر نیست.")
            .setNegativeButton("انصراف", null)
            .setPositiveButton("پاک کردن") { _, _ ->
                if (DanaWakeService.running(this)) stopWakeService()
                reminderScheduler.cancelAll(reminderStore)
                personalStore.clearAll()
                memory.clearAll()
                gatewaySecrets.clear()
                testLog.clear()
                quickAccess.cancel()
                state = DialogState.COMMAND
                refreshUserUI()
                say("اطلاعات دانا روی این گوشی پاک شد. اگر خواستی اسم جدیدت رو بگو.")
            }.show()
    }

    private fun reportBattery() {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (level in 0..100) say(if (memory.responseStyle == "short") "باتری $level درصد." else "${friendlyName()}، باتری گوشی $level درصد است.")
        else say("نتونستم درصد باتری رو بخونم.")
    }

    private fun playLocalMusic(query: String?) {
        askPermission(DanaMusicService.requiredPermission(), 409) {
            val action = if (query.isNullOrBlank()) DanaMusicService.PLAY_RANDOM
                else DanaMusicService.PLAY_NAMED
            if (DanaMusicService.command(this, action, query)) {
                say(if (query.isNullOrBlank()) "دارم از آهنگ‌های گوشی یکی رو انتخاب می‌کنم."
                    else "دارم آهنگ $query رو در حافظه جست‌وجو می‌کنم.")
            } else say("پخش موسیقی شروع نشد؛ مجوز یا محدودیت اجرای پس‌زمینه رو بررسی کن.")
        }
    }

    /** Media-stream mute is different from silencing phone calls or disabling the microphone. */
    private fun setMediaMuted(enabled: Boolean) {
        val manager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val ok = runCatching {
            manager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                if (enabled) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
        }.isSuccess
        say(if (ok) {
            if (enabled) "صدای موسیقی بی‌صدا شد." else "صدای موسیقی دوباره فعال شد."
        } else "کنترل بی‌صدا کردن در این گوشی ممکن نشد.")
    }

    /** Global brightness requires user-granted WRITE_SETTINGS special access on Android. */
    private fun changeBrightness(action: DanaAction.Brightness) {
        if (!Settings.System.canWrite(this)) {
            say("برای تغییر نور صفحه باید یک بار اجازه تغییر تنظیمات سیستم رو به دانا بدی. " +
                "صفحه مجوز رو باز می‌کنم؛ بعد از تأیید دوباره فرمان رو بگو.")
            safeLaunch(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:$packageName")))
            return
        }
        val current = Settings.System.getInt(contentResolver,
            Settings.System.SCREEN_BRIGHTNESS, 128)
        val level = if (action.percent != null) {
            (action.percent * 255 / 100).coerceIn(1, 255)
        } else (current + action.direction * 35).coerceIn(1, 255)
        val changed = runCatching {
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) &&
                Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, level)
        }.getOrDefault(false)
        say(if (changed) "روشنایی صفحه روی حدود ${level * 100 / 255} درصد تنظیم شد."
            else "گوشی اجازه تغییر روشنایی رو نداد؛ تنظیمات نمایشگر رو بررسی کن.")
    }

    private fun openRadioPanel(radio: String, enabled: Boolean) {
        val panel = if (radio == "wifi" && Build.VERSION.SDK_INT >= 29)
            Intent(Settings.Panel.ACTION_WIFI)
        else Intent(if (radio == "bluetooth") Settings.ACTION_BLUETOOTH_SETTINGS
            else Settings.ACTION_WIFI_SETTINGS)
        if (safeLaunch(panel)) say("اندروید اجازه تغییر مستقیم ${if (radio == "wifi") "وای‌فای" else "بلوتوث"} " +
            "را به دانا نمی‌دهد. پنل باز شد؛ برای ${if (enabled) "روشن کردن" else "خاموش کردن"} خودت تأیید کن.")
    }

    private fun scheduleDatedAlarm(at: Long, label: String?) {
        val save = {
            val alarm = runCatching { DatedAlarmScheduler(this).schedule(at, label) }.getOrNull()
            if (alarm == null) say("ثبت آلارم ممکن نشد؛ تنظیمات اعلان یا ساعت گوشی رو بررسی کن.")
            else {
                val whenText = android.icu.text.SimpleDateFormat("EEEE d MMMM ساعت HH:mm",
                    android.icu.util.ULocale("fa_IR@calendar=persian")).format(Date(at))
                say(if (alarm.exact) "آلارم دقیق برای $whenText ثبت شد."
                    else "آلارم برای $whenText ثبت شد، اما ممکنه با تأخیر اجرا بشه. برای دقت بیشتر، مجوز آلارم دقیق رو در تنظیمات فعال کن.")
                if (!alarm.exact && Build.VERSION.SDK_INT >= 31) {
                    AlertDialog.Builder(this)
                        .setTitle("مجوز آلارم دقیق")
                        .setMessage("آلارم ثبت شد اما ممکن است با تأخیر پخش شود. می‌خواهی برای فعال کردن آلارم دقیق وارد تنظیمات گوشی شوی؟")
                        .setNegativeButton("بعداً", null)
                        .setPositiveButton("تنظیمات") { _, _ ->
                            safeLaunch(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                Uri.parse("package:$packageName")))
                        }.show()
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33)
            askPermission(Manifest.permission.POST_NOTIFICATIONS, 410, save)
        else save()
    }

    private fun controlMedia(command: String) {
        val localAction = when (command) {
            "play", "resume" -> DanaMusicService.RESUME
            "pause" -> DanaMusicService.PAUSE
            "next" -> DanaMusicService.NEXT
            "previous" -> DanaMusicService.PREVIOUS
            "stop" -> DanaMusicService.STOP
            else -> DanaMusicService.PAUSE
        }
        if (DanaMusicService.running && DanaMusicService.command(this, localAction)) {
            say("فرمان کنترل پخش موسیقی رو ارسال کردم.")
            return
        }
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val keyCode = when (command) {
            "play", "resume" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause", "stop" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        say(when (command) {
            "play", "resume" -> "فرمان ادامه پخش به برنامه موسیقی فرستاده شد."
            "pause", "stop" -> "فرمان توقف به برنامه موسیقی فرستاده شد."
            "next" -> "فرمان آهنگ بعدی به برنامه موسیقی ارسال شد."
            "previous" -> "فرمان آهنگ قبلی به برنامه موسیقی ارسال شد."
            else -> "کنترل رسانه انجام شد."
        })
    }

    private fun setAlarm(hour: Int, minute: Int, label: String?) {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        if (!label.isNullOrBlank()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        if (safeLaunch(i)) say("آلارم برای ساعت %02d:%02d آماده شد.".format(hour, minute))
    }

    private fun setTimer(minutes: Int, label: String?) {
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        if (!label.isNullOrBlank()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        if (safeLaunch(i)) say("تایمر $minutes دقیقه ای آماده شد.")
    }

    private fun speakNotes() {
        val items = personalStore.notes().take(5)
        if (items.isEmpty()) say("هنوز یادداشتی نداری.")
        else say("آخرین یادداشت ها: " + items.mapIndexed { i, t -> "${i + 1}، $t" }.joinToString(". "))
    }

    private fun speakTasks() {
        val items = personalStore.tasks().filter { !it.done }.take(6)
        if (items.isEmpty()) say("کار انجام نشده ای در فهرست نیست.")
        else say("کارهای باز: " + items.mapIndexed { i, t -> "${i + 1}، ${t.text}" }.joinToString(". "))
    }

    private fun friendlyName(): String = memory.name ?: "دوست من"

    private fun say(text: String, afterSpeech: (() -> Unit)? = null) {
        answer.text = "دانا: $text"
        status.text = if (voice.isReady()) "در حال پاسخ صوتی…" else
            "پاسخ متنی: برای شنیدن صدا، صدای فارسی گوشی را فعال کن."
        voice.speak(text) {
            if (voice.isReady()) status.text = "آماده دریافت فرمان"
            if (afterSpeech != null) {
                relistenAfterResponse = false
                afterSpeech.invoke()
            } else if (relistenAfterResponse && memory.conversationMode && appVisible &&
                voice.isReady() && state == DialogState.COMMAND && !aiRequestActive) {
                relistenAfterResponse = false
                requestMicAndListen()
            } else {
                relistenAfterResponse = false
            }
        }
    }

    /** پس از تمام شدن صدای دانا، فقط در صفحه باز و با مجوز قبلی دوباره گوش می‌دهد. */
    private fun ask(text: String) = say(text) {
        if (appVisible && checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED) requestMicAndListen()
    }

    private fun safeLaunch(intent: Intent): Boolean {
        return try { startActivity(intent); true }
        catch (e: ActivityNotFoundException) { say("برنامه مناسبی برای این کار پیدا نکردم."); false }
        catch (e: SecurityException) { say("مجوز لازم برای این کار وجود نداره."); false }
    }

    private fun openApp(raw: String) {
        val name = CommandEngine.normalize(raw).removePrefix("برنامه ")
        val packages = mapOf("واتساپ" to "com.whatsapp", "واتس اپ" to "com.whatsapp",
            "تلگرام" to "org.telegram.messenger", "کروم" to "com.android.chrome",
            "اینستاگرام" to "com.instagram.android", "یوتیوب" to "com.google.android.youtube",
            "گوگل مپ" to "com.google.android.apps.maps")
        val packageName = packages.entries.firstOrNull { name.contains(it.key) }?.value
        var launch: Intent? = packageName?.let { packageManager.getLaunchIntentForPackage(it) }
        if (launch == null) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val matches = packageManager.queryIntentActivities(query, 0)
                .filter { CommandEngine.normalize(it.loadLabel(packageManager).toString()).contains(name) }
            if (matches.size == 1) {
                val activity = matches.first().activityInfo
                launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(activity.packageName, activity.name)
            } else if (matches.size > 1) {
                say("چند برنامه با این نام پیدا کردم؛ نام کاملش رو بگو.")
                return
            }
        }
        if (launch == null) say("برنامه $raw رو روی گوشی پیدا نکردم.")
        else if (safeLaunch(launch)) say("$raw رو باز کردم.")
    }

    private fun setTorch(enabled: Boolean) {
        try {
            val camera = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val flashCamera = camera.cameraIdList.firstOrNull {
                val info = camera.getCameraCharacteristics(it)
                info.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    info.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: camera.cameraIdList.firstOrNull {
                camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (flashCamera == null) { say("این گوشی چراغ قوه قابل کنترل نداره."); return }
            camera.setTorchMode(flashCamera, enabled)
            lastWasTorch = true
            torchEnabled = enabled
            say(if (memory.responseStyle == "short") {
                if (enabled) "چراغ روشن." else "چراغ خاموش."
            } else if (enabled) "چراغ قوه روشن شد." else "چراغ قوه خاموش شد.")
        } catch (e: Exception) { say("چراغ قوه در دسترس نیست؛ شاید دوربین مشغوله.") }
    }

    private fun changeVolume(direction: Int) {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
            if (direction < 0) AudioManager.ADJUST_LOWER else AudioManager.ADJUST_RAISE, 0)
        say(if (direction < 0) "صدای رسانه رو یک درجه کم کردم." else "صدای رسانه رو یک درجه زیاد کردم.")
    }

    private fun beginContactAction(name: String, kind: ContactPurpose, message: String?) {
        purpose = kind
        pendingContactName = name
        pendingSmsText = message
        aliasToSave = null
        askPermission(Manifest.permission.READ_CONTACTS, 402) { searchContacts(name) }
    }

    private fun searchContacts(name: String, fromAlias: Boolean = false) {
        try {
            val aliased = if (!fromAlias) memory.resolveAlias(name)?.let { finder.byId(it) } else null
            val found = aliased?.takeIf { it.isNotEmpty() } ?: finder.find(name)
            when (found.size) {
                0 -> {
                    if (fromAlias) {
                        state = DialogState.ALIAS_NAME
                        ask("با این نام هم مخاطبی پیدا نشد. اسم کامل دیگه ای بگو.")
                    } else if (CommandEngine.normalize(name) in
                        setOf("مامان", "مادر", "بابا", "پدر", "همسر", "مدیر", "شرکت")) {
                        aliasToSave = name
                        state = DialogState.ALIAS_NAME
                        ask(if (memory.privateMode || !memory.learningEnabled)
                            "$name در مخاطبین مشخص نیست. اسم کامل رو بگو؛ در این حالت اسم مستعار ذخیره نمی شه."
                            else "$name در مخاطبین مشخص نیست. اسم کامل مخاطب رو بگو تا یاد بگیرم.")
                    } else {
                        state = DialogState.COMMAND
                        say("مخاطبی به نام $name پیدا نکردم.")
                    }
                }
                1 -> {
                    if (fromAlias) confirmAliasIfNeeded(found[0])
                    else chooseContact(found[0])
                }
                else -> {
                    candidateContacts = found.take(8)
                    state = DialogState.PICK_CONTACT
                    val options = candidateContacts.take(4).mapIndexed { i, c ->
                        "${i + 1}: ${c.name}, ${c.number.takeLast(4)}"
                    }.joinToString("؛ ")
                    ask("چند مخاطب پیدا کردم: $options. بگو اولی، دومی یا اسم کامل.")
                }
            }
        } catch (e: SecurityException) { state = DialogState.COMMAND; say("اجازه خواندن مخاطبین لازم است.") }
    }

    private fun selectSpokenContact(spoken: String) {
        val index = when {
            spoken.contains("اول") || spoken == "یک" || spoken == "1" -> 0
            spoken.contains("دوم") || spoken == "دو" || spoken == "2" -> 1
            spoken.contains("سوم") || spoken == "سه" || spoken == "3" -> 2
            spoken.contains("چهارم") || spoken == "چهار" || spoken == "4" -> 3
            else -> -1
        }
        val item = if (index >= 0) candidateContacts.getOrNull(index) else
            candidateContacts.singleOrNull { CommandEngine.normalize(it.name).contains(spoken) }
        if (item == null) ask("مشخص نشد کدام مخاطب رو می خوای. اولی، دومی یا اسم کامل رو بگو.")
        else {
            candidateContacts = emptyList()
            if (aliasToSave != null) confirmAliasIfNeeded(item)
            else chooseContact(item)
        }
    }

    /** Never learn personal contact associations without an explicit yes from the user. */
    private fun confirmAliasIfNeeded(contact: FoundContact) {
        val alias = aliasToSave
        if (alias == null || memory.privateMode || !memory.learningEnabled) {
            aliasToSave = null
            chooseContact(contact)
            return
        }
        aliasContactPendingConsent = contact
        state = DialogState.ALIAS_CONFIRM
        ask("می‌خوای نام $alias رو برای ${contact.name} ذخیره کنم تا دفعه بعد بشناسم؟ بگو بله یا نه.")
    }

    private fun chooseContact(contact: FoundContact) {
        selectedContact = contact
        if (purpose == ContactPurpose.CALL) {
            state = DialogState.CONFIRM_CALL
            ask("${friendlyName()}، با ${contact.name} به شماره ختم به ${contact.number.takeLast(4)} تماس بگیرم؟ بگو بله یا نه.")
        } else if (pendingSmsText.isNullOrBlank()) {
            state = DialogState.SMS_TEXT
            ask("متن پیام برای ${contact.name} رو بگو.")
        } else confirmSms()
    }

    private fun confirmSms() {
        val contact = selectedContact ?: return
        state = DialogState.CONFIRM_SMS
        ask("پیام برای ${contact.name}: ${pendingSmsText.orEmpty()}. پیش نویس رو باز کنم؟ بگو بله، نه یا تغییر.")
    }

    private fun placeCall(contact: FoundContact) {
        // نسخه آزمایشی هرگز تماس را بدون لمس نهایی کاربر شروع نمی‌کند.
        // پس از تأیید صوتی، فقط شماره در Dialer آماده می‌شود.
        openDialer(contact)
    }

    private fun openDialer(contact: FoundContact) {
        say("شماره ${contact.name} در صفحه تماس آماده می شه؛ برای تماس دکمه تماس رو بزن.") {
            safeLaunch(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", contact.number, null)))
        }
    }

    private fun openSmsDraft(contact: FoundContact, body: String) {
        val sms = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", contact.number, null))
            .putExtra("sms_body", body)
        if (safeLaunch(sms)) say("پیش نویس پیام برای ${contact.name} باز شد؛ ارسال نهایی در برنامه پیامک انجام می شه.")
        pendingSmsText = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // onResume must see a notification action if we are not visible yet.
        if (intent.action == DanaWakeService.ACTION_OPEN_COMMAND && appVisible) {
            val text = intent.getStringExtra(DanaWakeService.EXTRA_COMMAND).orEmpty()
            intent.action = null
            intent.removeExtra(DanaWakeService.EXTRA_COMMAND)
            window.decorView.post { if (appVisible && text.isNotBlank()) receiveSpeech(text) }
        }
        if (intent.action == QuickAccessNotification.ACTION_VOICE && appVisible) {
            intent.action = null // Consume only after scheduling the visible-activity action.
            window.decorView.post { if (appVisible) requestMicAndListen() }
        }
    }

    override fun onResume() {
        super.onResume()
        appVisible = true
        visibleNow = true
        if (::wakeButton.isInitialized) updateWakeButton()
        // Android microphone foreground services must be started while the Activity is visible.
        // This is a best-effort opt-in resume, never a background/boot microphone launch.
        if (memory.wakeAutoResume && !DanaWakeService.running(this) &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            WakeModelInstaller.installed(getDir(DanaWakeService.MODEL_DIR, Context.MODE_PRIVATE)) &&
            getSharedPreferences("wake_runtime", MODE_PRIVATE).getString("last_stop_reason", null) == null) {
            startWakeService()
        }
        getSharedPreferences("wake_runtime", MODE_PRIVATE)
            .getString("last_stop_reason", null)?.let { status.text = it }
        if (intent.action == DanaWakeService.ACTION_OPEN_COMMAND) {
            val text = intent.getStringExtra(DanaWakeService.EXTRA_COMMAND).orEmpty()
            intent.action = null
            intent.removeExtra(DanaWakeService.EXTRA_COMMAND)
            if (text.isNotBlank()) window.decorView.post { if (appVisible) receiveSpeech(text) }
        }
        if (::quickAccess.isInitialized && memory.quickAccessEnabled && !quickAccess.show()) {
            memory.quickAccessEnabled = false
            refreshModes()
        }
        if (intent.action == QuickAccessNotification.ACTION_VOICE) {
            intent.action = null // Don't re-open microphone on later resumes or screen rotations.
            window.decorView.post { if (appVisible) requestMicAndListen() }
        }
    }
    override fun onPause() {
        appVisible = false
        visibleNow = false
        relistenAfterResponse = false
        speech.cancel() // میکروفون نباید هنگام باز شدن برنامه دیگر باقی بماند.
        offlineSpeech.releaseModel()
        super.onPause()
    }
    override fun onDestroy() {
        queuedPermissionAction = null
        speech.close()
        offlineSpeech.close()
        voice.close()
        super.onDestroy()
    }
}
