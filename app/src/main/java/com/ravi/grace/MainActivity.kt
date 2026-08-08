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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
    var showGuidance by remember { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Record packaged-index readiness after the first frame. Source PDFs
        // are embedded on the laptop before this APK is built, never here.
        delay(900)
        LibraryIndexScheduler.enqueueIfReady(context)
    }
    MaterialTheme(colorScheme = lightColorScheme(primary = Leaf, secondary = Saffron, background = Sand, surface = Sand, onSurface = Ink)) {
        Scaffold(
            containerColor = Sand,
            bottomBar = {
                NavigationBar(containerColor = Color(0xFFFFF7EA)) {
                    NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Text("☀", fontSize = 22.sp) }, label = { Text("Today") })
                    NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Text("▦", fontSize = 20.sp) }, label = { Text("Journey") })
                    NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Text("⚙", fontSize = 20.sp) }, label = { Text("Settings") })
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding)) {
                when (tab) {
                    0 -> TodayScreen(today, store, { refresh++ }, { showReflection = true }, { showMeditation = true }, { showGuidance = true })
                    1 -> JourneyScreen(store, refresh)
                    else -> SettingsScreen()
                }
            }
        }
        if (showReflection) ReflectionSheet(
            onDismiss = { showReflection = false },
            onSave = { text -> store.save(today.copy(reflections = today.reflections + Reflection(text = text))); refresh++; showReflection = false }
        )
        if (showMeditation) MeditationSheet(onDismiss = { showMeditation = false }, onFinished = { refresh++ })
        if (showGuidance) GuidanceSheet(
            today,
            onDismiss = { showGuidance = false },
            onCompleted = { results ->
                val completed = results.associateBy { it.reflectionId }
                store.save(today.copy(reflections = today.reflections.map { reflection ->
                    completed[reflection.id]?.let { result -> reflection.copy(state = result.state, washed = true, guidance = result.guidance) } ?: reflection
                }))
                refresh++
            },
        )
    }
}

@Composable
private fun TodayScreen(day: DayPractice, store: GraceStore, refresh: () -> Unit, addReflection: () -> Unit, meditate: () -> Unit, wash: () -> Unit) {
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
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text("Notice the mind", fontSize = 19.sp, fontWeight = FontWeight.SemiBold); Text("Optional. Private. You may add many.", color = Color.Gray, fontSize = 12.sp) }
            TextButton(onClick = addReflection) { Text("+ Record") }
        }
        if (day.reflections.isEmpty()) EmptyReflection(addReflection) else ReflectionList(day.reflections)
        if (day.reflections.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            val pending = day.reflections.any { !it.washed }
            Button(enabled = pending, onClick = wash, modifier = Modifier.fillMaxWidth().height(54.dp), colors = ButtonDefaults.buttonColors(containerColor = Saffron), shape = RoundedCornerShape(16.dp)) {
                Text(if (pending) "Wash with Jñāna" else "Already washed with Jñāna", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(if (pending) "GPU guidance grounded in your private source library" else "Record a new movement of mind to begin another wash.", modifier = Modifier.fillMaxWidth().padding(top = 6.dp), textAlign = TextAlign.Center, fontSize = 12.sp, color = Color.Gray)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ProgressCard(day: DayPractice) {
    val label = when { day.victorious -> "A victorious day"; day.gnanaPractised -> "Jñāna practised"; else -> "One clear step at a time" }
    Card(colors = CardDefaults.cardColors(containerColor = if (day.victorious) Color(0xFFDCEBD9) else Color(0xFFF3ECE0)), shape = RoundedCornerShape(22.dp)) {
        Row(Modifier.padding(18.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(if (day.victorious) Leaf else Saffron), contentAlignment = Alignment.Center) { Text("${day.goalCount}/3", color = Color.White, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(14.dp)); Column { Text(label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold); Text("Morning, practice, and an honest return.", color = Color(0xFF59605A), fontSize = 13.sp) }
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
            Column(Modifier.padding(13.dp)) {
                Row(verticalAlignment = Alignment.Top) { Column(Modifier.weight(1f)) { Text(reflection.text, fontSize = 14.sp); if (reflection.washed) Text("Washed with Jñāna", fontSize = 11.sp, color = Leaf, fontWeight = FontWeight.SemiBold) }; if (reflection.state != null) Tag(reflection.state) else Text("To be seen", color = Color.Gray, fontSize = 11.sp) }
                reflection.guidance?.let { guidance -> GuidanceEvidence(guidance) }
            }
        }
    } }
}

@Composable
private fun GuidanceEvidence(guidance: JnanaGuidance) {
    Column(Modifier.padding(top = 12.dp)) {
        Text(if (guidance.sourceGrounded) "Bhagavan’s guidance" else "Simple Gemma response", color = Saffron, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        if (!guidance.sourceGrounded) Text("No source passage reached cosine 0.30.", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
        Text(guidance.answer, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 3.dp))
        guidance.passages.forEachIndexed { index, passage ->
            Text("${index + 1}. ${passage.book} · p. ${passage.page} · cosine ${"%.2f".format(passage.score)}", color = Leaf, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            Text(passage.text, color = Color(0xFF5D625D), fontSize = 12.sp, lineHeight = 17.sp, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

@Composable private fun Tag(state: InnerState) { Surface(color = Color(state.tint), shape = RoundedCornerShape(20.dp)) { Text(state.display, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) } }

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GuidanceSheet(day: DayPractice, onDismiss: () -> Unit, onCompleted: (List<WashResult>) -> Unit) {
    val context = LocalContext.current
    val pending = day.reflections.filter { !it.washed }
    var progress by remember { mutableStateOf<WashProgress?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var completed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(pending.map { it.id }, attempt) {
        if (pending.isEmpty()) return@LaunchedEffect
        error = null
        completed = false
        progress = WashProgress(WashStage.STARTING_GPU, 0, pending.size)
        runCatching { JnanaWash.run(context, pending) { progress = it } }
            .onSuccess { results -> onCompleted(results); completed = true }
            .onFailure { failure -> error = failure.message ?: "The private Wash could not finish. Please try again." }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Sand) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp).padding(bottom = 30.dp)) {
            Text("Wash with Jñāna", fontSize = 25.sp, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(8.dp))
            Text("Gemma works only on this device: GPU load, private retrieval, then concise guidance grounded in your source passages.", color = Color(0xFF5D625D), lineHeight = 21.sp)
            Spacer(Modifier.height(16.dp))
            WashStatusCard(progress, error)
            day.reflections.filter { it.washed && it.guidance != null }.forEach { reflection ->
                Spacer(Modifier.height(12.dp))
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF2E8D8)), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp)) { Text(reflection.text, fontWeight = FontWeight.Medium); reflection.state?.let { Tag(it) }; reflection.guidance?.let { GuidanceEvidence(it) } }
                }
            }
            Spacer(Modifier.height(18.dp))
            when {
                error != null -> Button(onClick = { attempt++ }, modifier = Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = Saffron)) { Text("Try Wash again") }
                completed || pending.isEmpty() -> Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = Leaf)) { Text("Done") }
                else -> Button(enabled = false, onClick = {}, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Washing privately…") }
            }
        }
    }
}

@Composable
private fun WashStatusCard(progress: WashProgress?, error: String?) {
    val stage = progress?.stage
    val label = when {
        progress?.usingFallback == true && stage == WashStage.ANSWERING -> "No source passage reached cosine 0.30. Gemma is preparing a simple response from this record."
        else -> when (stage) {
        WashStage.STARTING_GPU -> "Loading Gemma 4 E4B on GPU…"
        WashStage.GPU_READY -> "Gemma 4 E4B is loaded on GPU."
        WashStage.TAGGING -> "Labelling record ${progress.record} of ${progress.total} with Gemma…"
        WashStage.SEARCHING -> "Embedding record ${progress.record} of ${progress.total}; finding top 3 passages…"
        WashStage.ANSWERING -> "Creating a grounded 25-word response for record ${progress.record} of ${progress.total}…"
        WashStage.COMPLETE -> "Wash complete. The record, evidence, and guidance are saved privately."
        null -> "Preparing your private Wash…"
        }
    }
    Card(colors = CardDefaults.cardColors(containerColor = if (error == null) Color(0xFFF2E8D8) else Color(0xFFF7E0DE)), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(if (error == null) "Private Wash status" else "Wash paused", color = if (error == null) Leaf else Saffron, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp)); Text(error ?: label, lineHeight = 20.sp)
            if (stage == WashStage.SEARCHING) Text("Cosine cutoff: 0.30 · highest three only", color = Leaf, fontSize = 12.sp, modifier = Modifier.padding(top = 7.dp))
            if (progress?.passages?.isNotEmpty() == true) {
                Text("Top matching passages · cosine ≥ 0.30", color = Saffron, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                progress.passages.forEachIndexed { index, passage ->
                    Text("${index + 1}. ${passage.book} · p. ${passage.page} · ${"%.2f".format(passage.score)}", color = Leaf, fontSize = 12.sp, modifier = Modifier.padding(top = 7.dp))
                    Text(passage.text, fontSize = 12.sp, lineHeight = 17.sp, color = Color(0xFF5D625D), maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
        }
    }
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
        Spacer(Modifier.height(24.dp)); Legend(Color(0xFF577A57), "Victorious — free, or consciously washed"); Legend(Saffron, "Jñāna practised"); Legend(Mist, "A day of beginning again")
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
    Text("${day.goalCount}/3 daily anchors", color = Leaf, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp))
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        JourneyMetric("Awake", if (day.wokeBeforeSunrise) "Before sunrise" else "Not marked", day.wokeBeforeSunrise, Modifier.weight(1f))
        JourneyMetric("Stillness", "${day.meditationMinutes} min", day.meditationMinutes > 0, Modifier.weight(1f))
        JourneyMetric("Jñāna", "${day.reflections.count { it.washed }} washed", day.gnanaPractised, Modifier.weight(1f))
    }
    if (day.reflections.isNotEmpty()) {
        Text("Recorded movements and guidance", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 22.dp, bottom = 9.dp))
        day.reflections.forEach { reflection ->
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 9.dp)) {
                Column(Modifier.padding(15.dp)) {
                    Row(verticalAlignment = Alignment.Top) { Text(reflection.text, Modifier.weight(1f), fontSize = 14.sp); reflection.state?.let { Tag(it) } }
                    reflection.guidance?.let { GuidanceEvidence(it) }
                        ?: Text(if (reflection.washed) "Washed in an earlier version; no saved guidance." else "Not yet washed with Jñāna.", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }
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

@Composable
private fun SettingsScreen() {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); refresh++ } }
    val gemma = remember(refresh) { ModelStatus.gemma(context) }
    val embedding = remember(refresh) { ModelStatus.embedding(context) }
    val library = remember(refresh) { ModelStatus.library(context) }
        val download = remember(refresh) { ModelDownloads.status(context) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Spacer(Modifier.height(8.dp))
        Text("Settings", fontSize = 30.sp, fontWeight = FontWeight.Light)
        Text("Everything stays on this device.", color = Leaf, fontSize = 14.sp)
        Spacer(Modifier.height(26.dp))
        Text("Local intelligence", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(9.dp))
        ModelStatusCard(gemma)
        Spacer(Modifier.height(10.dp))
        ModelStatusCard(embedding)
        Spacer(Modifier.height(14.dp))
        Button(enabled = !download.active && !(gemma.ready && embedding.ready), onClick = { ModelDownloads.enqueue(context); refresh++ }, modifier = Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = Leaf)) {
            Text(if (download.active) "Downloading models…" else if (gemma.ready && embedding.ready) "Models downloaded" else "Download models")
        }
        Text("Installs EmbeddingGemma first (0.2 GB), then Gemma 4 E4B (3.4 GB). Wi‑Fi is recommended; downloads resume and verify safely after interruption. Allow about 4.1 GB free.", Modifier.padding(top = 7.dp), color = Color.Gray, fontSize = 12.sp, lineHeight = 17.sp)
        Spacer(Modifier.height(24.dp))
        Text("Private source library", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(9.dp))
        ModelStatusCard(library)
        Text("The three PDFs were embedded offline on the laptop before this app was built. On your phone, EmbeddingGemma encodes only your private query and matches it against the included index; no PDF is re-indexed or sent to a server.", Modifier.padding(top = 8.dp), color = Color.Gray, fontSize = 12.sp, lineHeight = 17.sp)
        Spacer(Modifier.height(24.dp))
        Text("Meditation", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF4EFE4)), shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(top = 9.dp).fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Lock-screen controls", fontWeight = FontWeight.SemiBold)
                Text("An active practice runs as a foreground session. Pause, resume, or end it from its ongoing notification or from Grace.", color = Color(0xFF63665F), fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("Daily return", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        Text("At 06:00 and 20:00 local time: “Get back to the source.” These reminders stay on this device.", Modifier.padding(top = 7.dp), color = Color.Gray, fontSize = 13.sp)
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun ModelStatusCard(status: LocalModelStatus) {
    Card(colors = CardDefaults.cardColors(containerColor = if (status.ready) Color(0xFFE4F0E0) else Color(0xFFF6ECDD)), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(11.dp).clip(CircleShape).background(if (status.ready) Leaf else Saffron))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) { Text(status.title, fontWeight = FontWeight.SemiBold); Text(status.detail, color = Color(0xFF5E625C), fontSize = 13.sp) }
            Text(if (status.ready) "Ready" else "Waiting", color = if (status.ready) Leaf else Saffron, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        }
    }
}

@Composable private fun CalendarCell(number: Int, day: DayPractice?, selected: Boolean, modifier: Modifier, onClick: () -> Unit) { val color = when { day?.victorious == true -> Color(0xFF577A57); day?.gnanaPractised == true -> Saffron; day != null -> Mist; else -> Color.Transparent }; Box(modifier.aspectRatio(1f).padding(4.dp).clip(CircleShape).background(color).then(if (selected) Modifier.border(2.dp, Ink, CircleShape) else Modifier).clickable(onClick = onClick), contentAlignment = Alignment.Center) { Text(number.toString(), color = if (color == Color.Transparent || color == Mist) Ink else Color.White, fontWeight = if (day?.victorious == true) FontWeight.Bold else FontWeight.Normal) } }
@Composable private fun Legend(color: Color, text: String) { Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(12.dp).clip(CircleShape).background(color)); Spacer(Modifier.width(9.dp)); Text(text, fontSize = 13.sp, color = Color(0xFF5E625C)) } }
