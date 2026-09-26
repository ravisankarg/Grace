package com.ravi.grace

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val Ink = Color(0xFF233226)
private val Sand = Color(0xFFFFFBF3)
private val Leaf = Color(0xFF527554)
private val Saffron = Color(0xFFB96736)
private val Mist = Color(0xFFE8E7DC)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { GraceApp() }
    }
}

@Composable
private fun GraceApp() {
    val context = LocalContext.current
    val store = remember { GraceStore(context) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    val today = remember(refresh) { store.day() }
    var showReflection by remember { mutableStateOf(false) }
    var showMeditation by remember { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    MaterialTheme(colorScheme = lightColorScheme(primary = Leaf, secondary = Saffron, background = Sand, surface = Sand, onSurface = Ink)) {
        Scaffold(
            containerColor = Sand,
            bottomBar = {
                NavigationBar(containerColor = Color(0xFFFFF7EA)) {
                    NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Text("☀", fontSize = 22.sp) }, label = { Text("Today") })
                    NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Text("▦", fontSize = 20.sp) }, label = { Text("Journey") })
                    NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Text("◎", fontSize = 20.sp) }, label = { Text("Meetings") })
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding)) {
                when (tab) {
                    0 -> TodayScreen(today, store, { refresh++ }, { showReflection = true }, { showMeditation = true })
                    1 -> JourneyScreen(store, refresh)
                    else -> MeetingsScreen()
                }
            }
        }
        if (showReflection) ReflectionSheet(
            onDismiss = { showReflection = false },
            onSave = { text -> store.save(today.copy(reflections = today.reflections + Reflection(text = text))); refresh++; showReflection = false }
        )
        if (showMeditation) MeditationSheet(onDismiss = { showMeditation = false }, onFinished = { refresh++ })
    }
}

@Composable
private fun TodayScreen(day: DayPractice, store: GraceStore, refresh: () -> Unit, addReflection: () -> Unit, meditate: () -> Unit) {
    val date = LocalDate.parse(day.date)
    val meditation = rememberMeditationStatus(LocalContext.current).value
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painter = androidx.compose.ui.res.painterResource(R.drawable.bhagavan), contentDescription = "Bhagavan", contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp).clip(CircleShape))
            Spacer(Modifier.width(12.dp))
            Column { Text("Grace", fontSize = 28.sp, fontWeight = FontWeight.SemiBold); Text("A gentle return to what is true", color = Leaf, fontSize = 13.sp) }
        }
        Spacer(Modifier.height(24.dp))
        Text(date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()).uppercase(), color = Saffron, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Text(date.format(DateTimeFormatter.ofPattern("d MMMM")), fontSize = 32.sp, fontWeight = FontWeight.Light)
        Spacer(Modifier.height(14.dp))
        if (meditation.active) {
            LiveMeditationCard(meditation)
            Spacer(Modifier.height(14.dp))
        }
        ProgressCard(day)
        Spacer(Modifier.height(22.dp))
        Text("The day, held simply", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        PracticeRow("Before sunrise", "I woke and remembered", day.wokeBeforeSunrise, onToggle = { store.save(day.copy(wokeBeforeSunrise = !day.wokeBeforeSunrise)); refresh() })
        PracticeRow("Before my work", if (day.morningMeditationMinutes > 0) "${day.morningMeditationMinutes} minutes in stillness" else "Begin with meditation", day.morningMeditationMinutes > 0, action = "Meditate", onAction = meditate)
        PracticeRow("Before sleep", if (day.eveningMeditationMinutes > 0) "${day.eveningMeditationMinutes} minutes in stillness" else "Leave room for stillness", day.eveningMeditationMinutes > 0, action = "Meditate", onAction = meditate)
        PracticeRow("Inner freedom", "I did not give myself to these states", day.noSuccumbing, onToggle = { store.save(day.copy(noSuccumbing = !day.noSuccumbing)); refresh() })
        PracticeRow("One thing to do today if it’s my last day?", "I did the one thing that truly mattered", day.lastDayPriority, onToggle = { store.save(day.copy(lastDayPriority = !day.lastDayPriority)); refresh() })
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text("Notice the mind", fontSize = 19.sp, fontWeight = FontWeight.SemiBold); Text("Optional. Private. You may add many.", color = Color.Gray, fontSize = 12.sp) }
            TextButton(onClick = addReflection) { Text("+ Record") }
        }
        if (day.reflections.isEmpty()) EmptyReflection(addReflection) else ReflectionList(day.reflections)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ProgressCard(day: DayPractice) {
    val label = if (day.victorious) "A day held clearly" else "One clear step at a time"
    Card(colors = CardDefaults.cardColors(containerColor = if (day.victorious) Color(0xFFDCEBD9) else Color(0xFFF3ECE0)), shape = RoundedCornerShape(22.dp)) {
        Row(Modifier.padding(18.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(if (day.victorious) Leaf else Saffron), contentAlignment = Alignment.Center) { Text("${day.goalCount}/4", color = Color.White, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(14.dp)); Column { Text(label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold); Text("Remember, be still, stay free, and choose what matters.", color = Color(0xFF59605A), fontSize = 13.sp) }
        }
    }
}

@Composable
private fun PracticeRow(title: String, detail: String, checked: Boolean, action: String? = null, onToggle: (() -> Unit)? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onToggle != null) Checkbox(checked = checked, onCheckedChange = { onToggle() }, colors = CheckboxDefaults.colors(checkedColor = Leaf))
        else Box(Modifier.size(28.dp).clip(CircleShape).background(if (checked) Leaf else Mist), contentAlignment = Alignment.Center) { if (checked) Text("✓", color = Color.White, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Medium); Text(detail, fontSize = 13.sp, color = Color(0xFF70746D)) }
        if (action != null) TextButton(onClick = onAction!!) { Text(action) }
    }
}

@Composable private fun EmptyReflection(add: () -> Unit) = Card(modifier = Modifier.fillMaxWidth().clickable(onClick = add), colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F3E9)), shape = RoundedCornerShape(16.dp)) { Text("Write or speak what arose. Naming it is already a pause.", Modifier.padding(18.dp), color = Color(0xFF777167), fontSize = 14.sp) }

@Composable
private fun ReflectionList(reflections: List<Reflection>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { reflections.forEach { reflection ->
        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp)) {
            Text(reflection.text, Modifier.padding(13.dp), fontSize = 14.sp)
        }
    } }
}

@Composable
private fun LiveMeditationCard(status: MeditationStatus) {
    val context = LocalContext.current
    val totalSeconds = (status.minutes * 60).coerceAtLeast(1)
    val progress = (1f - status.secondsLeft.toFloat() / totalSeconds).coerceIn(0f, 1f)
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE4F0E0)), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(17.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column { Text("Meditation in progress", fontWeight = FontWeight.SemiBold); Text(if (status.paused) "Paused" else status.soundName, color = Leaf, fontSize = 13.sp) }
                Text("%02d:%02d".format(status.secondsLeft / 60, status.secondsLeft % 60), fontSize = 27.sp, fontWeight = FontWeight.Light)
            }
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 13.dp), color = Leaf, trackColor = Color(0xFFC8DCC5))
            Row(Modifier.fillMaxWidth().padding(top = 9.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { MeditationSession.send(context, if (status.paused) MeditationSession.ACTION_RESUME else MeditationSession.ACTION_PAUSE) }) { Text(if (status.paused) "Resume" else "Pause") }
                TextButton(onClick = { MeditationSession.send(context, MeditationSession.ACTION_END) }) { Text("End", color = Saffron) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReflectionSheet(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf("") }; val context = LocalContext.current
    val voiceResult = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result -> result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { text = if (text.isBlank()) it else "$text $it" } }
    val audioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) voiceResult.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your reflection")) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Sand) {
        Column(Modifier.padding(24.dp).padding(bottom = 24.dp)) {
            Text("What arose?", fontSize = 24.sp, fontWeight = FontWeight.SemiBold); Text("This stays on your device.", color = Color.Gray, fontSize = 13.sp)
            Spacer(Modifier.height(14.dp)); OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth().height(150.dp), placeholder = { Text("I noticed…") })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { if (SpeechRecognizer.isRecognitionAvailable(context)) audioPermission.launch(Manifest.permission.RECORD_AUDIO) }) { Text("◉ Speak instead") }
                Button(enabled = text.isNotBlank(), onClick = { onSave(text.trim()) }) { Text("Keep") }
            }
        }
    }
}

private data class Ambient(val label: String, val asset: String?)
private val ambientSounds = listOf(Ambient("Silence", null), Ambient("Real rain", "sounds/feedthestraycats-real-rain-sound-379215.mp3"), Ambient("Fire crackling", "sounds/soundreality-fire-crackling-sound-499636.mp3"), Ambient("Shruti box", "sounds/vsnp-shruti-box-tambura-16553.mp3"))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeditationSheet(onDismiss: () -> Unit, onFinished: () -> Unit) {
    var minutes by remember { mutableIntStateOf(10) }; var sound by remember { mutableStateOf(ambientSounds[1]) }
    val context = LocalContext.current; val statusState = rememberMeditationStatus(context); val status = statusState.value
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Sand) {
        Column(Modifier.padding(24.dp).padding(bottom = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (status.active) "Be here" else "Meditation", fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
            if (status.active) {
                Text("%02d:%02d".format(status.secondsLeft / 60, status.secondsLeft % 60), fontSize = 56.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(vertical = 22.dp))
                Text(if (status.paused) "Paused" else status.soundName, color = Leaf)
                Spacer(Modifier.height(18.dp))
                Button(onClick = { MeditationSession.send(context, if (status.paused) MeditationSession.ACTION_RESUME else MeditationSession.ACTION_PAUSE) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(if (status.paused) "Resume meditation" else "Pause meditation") }
                TextButton(onClick = { MeditationSession.send(context, MeditationSession.ACTION_END); onFinished(); onDismiss() }) { Text("End this session", color = Saffron) }
                Text("The same controls remain on your lock-screen notification.", fontSize = 12.sp, color = Color.Gray, textAlign = TextAlign.Center)
            }
            else {
                Text("Choose a small, faithful container.", color = Color.Gray); Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) { listOf(10, 20, 30, 40, 50, 60).forEach { value -> FilterChip(selected = minutes == value, onClick = { minutes = value }, label = { Text("$value") }) } }
                Spacer(Modifier.height(15.dp)); Text("Ambient sound", modifier = Modifier.fillMaxWidth(), fontWeight = FontWeight.Medium)
                ambientSounds.forEach { option -> Row(Modifier.fillMaxWidth().clickable { sound = option }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = sound == option, onClick = { sound = option }); Text(option.label) } }
                Button(
                    onClick = {
                        MeditationSession.start(context, minutes, sound.asset, sound.label)
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(52.dp)
                ) { Text("Begin $minutes minute practice") }
            }
        }
    }
}

@Composable
private fun rememberMeditationStatus(context: Context): State<MeditationStatus> {
    val state = remember { mutableStateOf(MeditationSession.status(context)) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() { override fun onReceive(receiverContext: Context?, intent: Intent?) { state.value = MeditationSession.status(context) } }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(MeditationSession.ACTION_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    return state
}

@Composable
private fun JourneyScreen(store: GraceStore, refresh: Int) {
    var month by rememberSaveable { mutableStateOf(YearMonth.now()) }
    var selectedDate by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    // This is a small local preference-backed collection; reading it directly
    // also avoids Compose lint misidentifying the cached map as Unit.
    val days = store.allDays().associateBy { it.date }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("Your journey", fontSize = 30.sp, fontWeight = FontWeight.Light); Text("Every honest return is part of the path.", color = Leaf, fontSize = 14.sp)
        Spacer(Modifier.height(26.dp)); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { TextButton(onClick = { month = month.minusMonths(1) }) { Text("‹") }; Text(month.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + month.year, fontSize = 18.sp, fontWeight = FontWeight.SemiBold); TextButton(onClick = { month = month.plusMonths(1) }) { Text("›") } }
        Spacer(Modifier.height(10.dp)); Row(Modifier.fillMaxWidth()) { listOf("M", "T", "W", "T", "F", "S", "S").forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, color = Color.Gray, fontSize = 12.sp) } }
        val firstOffset = (month.atDay(1).dayOfWeek.value + 6) % 7; val total = month.lengthOfMonth()
        Column(Modifier.padding(top = 7.dp)) { (0 until 6).forEach { row -> Row(Modifier.fillMaxWidth()) { (0 until 7).forEach { col -> val number = row * 7 + col - firstOffset + 1; if (number in 1..total) { val date = month.atDay(number).toString(); CalendarCell(number, days[date], date == selectedDate, Modifier.weight(1f)) { selectedDate = date } } else Spacer(Modifier.weight(1f).aspectRatio(1f)) } } } }
        Spacer(Modifier.height(24.dp)); Legend(Color(0xFF577A57), "All four daily anchors"); Legend(Saffron, "A day in practice"); Legend(Mist, "A day of beginning again")
        Spacer(Modifier.height(22.dp))
        JourneyDayDetail(selectedDate, days[selectedDate])
    }
}

@Composable
private fun JourneyDayDetail(date: String, day: DayPractice?) {
    val title = runCatching { LocalDate.parse(date).format(DateTimeFormatter.ofPattern("d MMMM")) }.getOrDefault(date)
    Text(title, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
    if (day == null) {
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF4EFE4)), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().padding(top = 9.dp)) { Text("No practice was recorded for this day.", Modifier.padding(16.dp), color = Color(0xFF5E625C)) }
        return
    }
    Text("${day.goalCount}/4 daily anchors", color = Leaf, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp))
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        JourneyMetric("Awake", if (day.wokeBeforeSunrise) "Before sunrise" else "Not marked", day.wokeBeforeSunrise, Modifier.weight(1f))
        JourneyMetric("Stillness", "${day.meditationMinutes} min", day.meditationMinutes > 0, Modifier.weight(1f))
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        JourneyMetric("Inner freedom", if (day.noSuccumbing) "Held" else "Not marked", day.noSuccumbing, Modifier.weight(1f))
        JourneyMetric("What mattered", if (day.lastDayPriority) "Done" else "Not marked", day.lastDayPriority, Modifier.weight(1f))
    }
    if (day.reflections.isNotEmpty()) {
        Text("Recorded movements", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 22.dp, bottom = 9.dp))
        day.reflections.forEach { reflection ->
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 9.dp)) {
                Text(reflection.text, Modifier.padding(15.dp), fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun JourneyMetric(title: String, value: String, complete: Boolean, modifier: Modifier = Modifier) {
    Card(colors = CardDefaults.cardColors(containerColor = if (complete) Color(0xFFE4F0E0) else Color(0xFFF4EFE4)), shape = RoundedCornerShape(15.dp), modifier = modifier) {
        Column(Modifier.padding(11.dp)) { Text(title, color = Leaf, fontSize = 11.sp, fontWeight = FontWeight.Bold); Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp)) }
    }
}

private enum class MeetingMode { TEAM, LEVEL_UP }

private data class MeetingAnchor(val title: String, val cue: String, val prompt: String)

private val sharedMeetingAnchors = listOf(
    MeetingAnchor("Do not react", "Pause before the first emotion becomes the answer.", "What outcome do I want from my response?"),
    MeetingAnchor("Respond from the role", "Ignore the person, power, and tone. Return to the responsibility of the role.", "What is my role here, and how does this role respond?"),
)

private val teamAnchors = listOf(
    MeetingAnchor("Don't take the ball", "Keep thinking and ownership with the team member.", "What options did you consider? Which one do you recommend?"),
    MeetingAnchor("Give next action", "Close with a clear owner, next step, and return time.", "What will you do next, and by when will you come back?"),
)

private val levelUpAnchors = listOf(
    MeetingAnchor("Is it really my job?", "Separate pressure from responsibility. Answer only the part your role owns.", "What is mine to decide, answer, or commit to?"),
    MeetingAnchor("Who is the right person?", "Route facts, decisions, and execution to the role that actually owns them.", "Who has the authority and context to own this?"),
    MeetingAnchor("Say what I don't know", "Do not guess or fill the silence with detail.", "I don't know that. The right owner can provide the verified answer."),
    MeetingAnchor("Use Point → Proof → Path", "State the outcome, one approved fact, and the existing team path. No correction, extra detail, or question back.", "One point. One proof. One path."),
    MeetingAnchor("Don't take the ball", "Do not accept a vague new action because it was said with authority.", "Can the right team member own the next step?"),
    MeetingAnchor("Give next action", "Name the right owner and the agreed review. Keep the action with that owner.", "The right owner will take this and return at the agreed review."),
)

@Composable
private fun MeetingsScreen() {
    var mode by rememberSaveable { mutableStateOf(MeetingMode.TEAM) }
    val anchors = sharedMeetingAnchors + if (mode == MeetingMode.TEAM) teamAnchors else levelUpAnchors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(18.dp))
        Text("Meetings", fontSize = 30.sp, fontWeight = FontWeight.Light)
        Text("Pause. Return to your role. Keep ownership clear.", color = Leaf, fontSize = 14.sp)
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MeetingModeButton("With team", mode == MeetingMode.TEAM, Modifier.weight(1f)) { mode = MeetingMode.TEAM }
            MeetingModeButton("Level up", mode == MeetingMode.LEVEL_UP, Modifier.weight(1f)) { mode = MeetingMode.LEVEL_UP }
        }
        Spacer(Modifier.height(18.dp))
        anchors.forEachIndexed { index, anchor ->
            AnchorCard(index + 1, anchor, mode)
            Spacer(Modifier.height(10.dp))
        }
        if (mode == MeetingMode.LEVEL_UP) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE4F0E0)), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(17.dp)) {
                    Text("SPEAKABLE STOP", color = Leaf, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Text("The current commitment remains the team path through the agreed review. That review is the next verified update.", fontWeight = FontWeight.Medium, lineHeight = 21.sp, modifier = Modifier.padding(top = 6.dp))
                    Text("If pressed, repeat it once. Add nothing new. Then stop.", color = Color(0xFF5D625D), fontSize = 13.sp, modifier = Modifier.padding(top = 7.dp))
                }
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun MeetingModeButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick, modifier = modifier.height(52.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Leaf)) { Text(label, fontWeight = FontWeight.SemiBold) }
    else OutlinedButton(onClick = onClick, modifier = modifier.height(52.dp), shape = RoundedCornerShape(16.dp)) { Text(label, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun AnchorCard(number: Int, anchor: MeetingAnchor, mode: MeetingMode) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(if (mode == MeetingMode.TEAM) Leaf else Saffron), contentAlignment = Alignment.Center) {
                Text(number.toString(), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(anchor.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(anchor.cue, color = Color(0xFF5D625D), fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp))
                Text(anchor.prompt, color = if (mode == MeetingMode.TEAM) Leaf else Saffron, fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 19.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable private fun CalendarCell(number: Int, day: DayPractice?, selected: Boolean, modifier: Modifier, onClick: () -> Unit) { val color = when { day?.victorious == true -> Color(0xFF577A57); day != null && day.goalCount > 0 -> Saffron; day != null -> Mist; else -> Color.Transparent }; Box(modifier.aspectRatio(1f).padding(4.dp).clip(CircleShape).background(color).then(if (selected) Modifier.border(2.dp, Ink, CircleShape) else Modifier).clickable(onClick = onClick), contentAlignment = Alignment.Center) { Text(number.toString(), color = if (color == Color.Transparent || color == Mist) Ink else Color.White, fontWeight = if (day?.victorious == true) FontWeight.Bold else FontWeight.Normal) } }
@Composable private fun Legend(color: Color, text: String) { Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(12.dp).clip(CircleShape).background(color)); Spacer(Modifier.width(9.dp)); Text(text, fontSize = 13.sp, color = Color(0xFF5E625C)) } }
