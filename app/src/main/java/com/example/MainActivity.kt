package com.example

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.api.GeminiRepository
import com.example.data.AppDatabase
import com.example.data.VideoDownload
import com.example.data.VideoDownloadRepository
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Setup Room Database and Repository
        val database = AppDatabase.getDatabase(this)
        val repository = VideoDownloadRepository(database.videoDownloadDao)
        val geminiRepository = GeminiRepository()

        // Create ViewModel Factory
        val viewModelFactory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                    @Suppress("UNCHECKED_CAST")
                    return MainViewModel(repository, geminiRepository) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class")
            }
        }

        setContent {
            MyApplicationTheme {
                val viewModel: MainViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = viewModelFactory)
                MainScreen(viewModel = viewModel)
            }
        }
    }
}

// VM handling all core downloader actions, database storage, and AI interactions
class MainViewModel(
    private val repository: VideoDownloadRepository,
    private val geminiRepository: GeminiRepository
) : ViewModel() {

    // Input States
    var sourceUrl by mutableStateOf("")
    var cleaningResultMsg by mutableStateOf<String?>(null)

    // UI Tab State (0 = Downloader, 1 = Web Sniffer, 2 = AI Assistant)
    var currentTab by mutableStateOf(0)

    // Download/Analyze processing states
    var isAnalyzing by mutableStateOf(false)
    var parsedVideoDetails by mutableStateOf<ParsedVideoInfo?>(null)

    // Web Sniffer Webview Details
    var browserUrl by mutableStateOf("https://www.instagram.com")
    var lastSniffedVideoUrl by mutableStateOf<String?>(null)
    var isBrowserLoading by mutableStateOf(false)

    // AI Chat History List (pair of: text to isUser)
    private val _chatHistory = MutableStateFlow<List<Pair<String, Boolean>>>(
        listOf(
            "Welcome! I am your low-latency AI Downloader Assistant.\n\nAsk me anything about sharing links, technical extraction details, or copyright policies!" to false
        )
    )
    val chatHistory: StateFlow<List<Pair<String, Boolean>>> = _chatHistory

    var isChatSending by mutableStateOf(false)

    // Reactively stream video download history logs from database Room flow!
    val downloadHistory = repository.allDownloads

    fun sendChatMessage(prompt: String) {
        if (prompt.trim().isEmpty()) return
        
        viewModelScope.launch {
            // Append user prompt to state
            val currentList = _chatHistory.value.toMutableList()
            currentList.add(prompt to true)
            _chatHistory.value = currentList
            isChatSending = true

            // Get response utilizing quick-model
            val response = geminiRepository.getAssistantResponse(prompt, currentList)
            
            val updatedList = _chatHistory.value.toMutableList()
            updatedList.add(response to false)
            _chatHistory.value = updatedList
            isChatSending = false
        }
    }

    // Strip analytics and tracking tokens from social links
    fun cleanCurrentUrl() {
        if (sourceUrl.isEmpty()) return
        try {
            val uri = Uri.parse(sourceUrl)
            val cleanBuilder = uri.buildUpon().clearQuery()
            // Keep specific video IDs for platforms if necessary (but most query keys match UTM or tracking)
            val cleaned = cleanBuilder.build().toString()
            sourceUrl = cleaned
            cleaningResultMsg = "Link cleaned! Trackers stripped."
        } catch (e: Exception) {
            cleaningResultMsg = "Unable to parse URL."
        }
    }

    // Trigger local download operation utilizing DownloadManager
    fun triggerDownload(context: Context, videoUrl: String, title: String, sourceUrl: String, platform: String) {
        viewModelScope.launch {
            try {
                // Set public file destination
                val cleanTitle = title.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(40)
                val filename = "Downloaded_Media_${cleanTitle}_${System.currentTimeMillis() % 10000}.mp4"

                val request = DownloadManager.Request(Uri.parse(videoUrl))
                    .setTitle(title)
                    .setDescription("Downloading Social Video file")
                    .setMimeType("video/mp4")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(true)

                val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                downloadManager.enqueue(request)

                // Log entry inside Room DB for tracking
                val absolutePath = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), filename).absolutePath
                val logItem = VideoDownload(
                    title = title,
                    sourceUrl = sourceUrl,
                    extractedVideoUrl = videoUrl,
                    filePath = absolutePath,
                    platform = platform,
                    status = "COMPLETED", // DownloadManager does actual fetching in background async
                    fileSize = "Pending download"
                )
                repository.insert(logItem)

                Toast.makeText(context, "Download scheduled with system. View in notification drawer.", Toast.LENGTH_LONG).show()
                parsedVideoDetails = null
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to start download: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Mock/Simulate standard parsing of social links to return a downloadable video
    fun analyzeUrlAndSimulate(context: Context) {
        if (sourceUrl.isEmpty()) {
            Toast.makeText(context, "Please paste or enter a link first", Toast.LENGTH_SHORT).show()
            return
        }
        isAnalyzing = true
        viewModelScope.launch {
            kotlinx.coroutines.delay(1200) // Simulate fast parsing logic
            val lowCase = sourceUrl.lowercase()
            val platform = when {
                lowCase.contains("instagram") -> "Instagram"
                lowCase.contains("tiktok") -> "TikTok"
                lowCase.contains("youtube") || lowCase.contains("youtu.be") -> "YouTube"
                lowCase.contains("twitter") || lowCase.contains("x.com") -> "X"
                else -> "Other Platform"
            }

            // High-quality public demo videos
            val possibleVideoUrls = listOf(
                "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4",
                "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerEscapes.mp4",
                "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerFun.mp4",
                "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerJoyrides.mp4"
            )
            val demoVideoUrl = possibleVideoUrls[Random().nextInt(possibleVideoUrls.size)]

            parsedVideoDetails = ParsedVideoInfo(
                title = "$platform Video Post [Resolved]",
                platform = platform,
                directVideoUrl = demoVideoUrl,
                estimatedSize = "4.2 MB",
                thumbnailPlaceholderChar = platform.first()
            )
            isAnalyzing = false
        }
    }

    fun deleteHistoryLog(id: Long) {
        viewModelScope.launch {
            repository.deleteById(id)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            repository.clearAll()
        }
    }
}

data class ParsedVideoInfo(
    val title: String,
    val platform: String,
    val directVideoUrl: String,
    val estimatedSize: String,
    val thumbnailPlaceholderChar: Char
)

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    var inputQueryChat by remember { mutableStateOf("") }
    val downloadsByRoom by viewModel.downloadHistory.collectAsStateWithLifecycle(initialValue = emptyList())
    val keyboardController = LocalSoftwareKeyboardController.current

    Scaffold(
        bottomBar = {
            NavigationBar(
                tonalElevation = 12.dp,
                modifier = Modifier.testTag("primary_navigation_bar")
            ) {
                NavigationBarItem(
                    selected = viewModel.currentTab == 0,
                    label = { Text("Manager", fontWeight = FontWeight.Bold) },
                    icon = { Icon(Icons.Default.Download, contentDescription = "Tab Download Manager") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.testTag("nav_tab_managerShared"),
                    onClick = { viewModel.currentTab = 0 }
                )
                NavigationBarItem(
                    selected = viewModel.currentTab == 1,
                    label = { Text("Web Sniffer", fontWeight = FontWeight.Bold) },
                    icon = { Icon(Icons.Default.Language, contentDescription = "Tab Web Video Sniffer") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.testTag("nav_tab_browserShared"),
                    onClick = { viewModel.currentTab = 1 }
                )
                NavigationBarItem(
                    selected = viewModel.currentTab == 2,
                    label = { Text("AI Assistant", fontWeight = FontWeight.Bold) },
                    icon = { Icon(Icons.Default.AutoAwesome, contentDescription = "Tab AI chat") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.testTag("nav_tab_aiChatShared"),
                    onClick = { viewModel.currentTab = 2 }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF070B08),
                            Color(0xFF0F1411)
                        )
                    )
                )
        ) {
            when (viewModel.currentTab) {
                0 -> { // Downloader manager tab
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        contentPadding = PaddingValues(top = 16.dp, bottom = 120.dp)
                    ) {
                        item {
                            // High Polish visual top banner
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(
                                        Brush.linearGradient(
                                            listOf(
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                                MaterialTheme.colorScheme.secondary.copy(alpha = 0.05f)
                                            )
                                        )
                                    )
                                    .padding(vertical = 24.dp, horizontal = 16.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(56.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.DownloadForOffline,
                                            contentDescription = "Social Logo Icon",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(34.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column {
                                        Text(
                                            "Social Fetch AI",
                                            fontSize = 22.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Color.White,
                                            fontFamily = FontFamily.SansSerif
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(0xFF00FF7F))
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                "Active Sniffer & Assist Engine Online",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(20.dp))

                            Text(
                                "Paste Video URL",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            // Custom input container with clean emerald borders
                            OutlinedTextField(
                                value = viewModel.sourceUrl,
                                onValueChange = {
                                    viewModel.sourceUrl = it
                                    viewModel.cleaningResultMsg = null
                                },
                                placeholder = { Text("https://www.instagram.com/reel/...") },
                                maxLines = 2,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("url_input_field"),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Uri,
                                    imeAction = ImeAction.Go
                                ),
                                keyboardActions = KeyboardActions(onGo = {
                                    keyboardController?.hide()
                                    viewModel.analyzeUrlAndSimulate(context)
                                }),
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Link,
                                        contentDescription = "Link Icon",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                },
                                trailingIcon = {
                                    if (viewModel.sourceUrl.isNotEmpty()) {
                                        IconButton(onClick = { viewModel.sourceUrl = "" }) {
                                            Icon(
                                                Icons.Default.Clear,
                                                contentDescription = "Clear field"
                                            )
                                        }
                                    }
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                    unfocusedLabelColor = Color.LightGray,
                                    focusedLabelColor = MaterialTheme.colorScheme.primary
                                )
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            // Quick cleaner result message
                            viewModel.cleaningResultMsg?.let { msg ->
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2E24)),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.TaskAlt, contentDescription = null, tint = Color(0xFF00FF7F))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(msg, fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium)
                                    }
                                }
                            }

                            // Tool Row (Clean parameters / Analyze / Simulated helper)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = { viewModel.cleanCurrentUrl() },
                                    enabled = viewModel.sourceUrl.isNotEmpty(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        contentColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("clean_tracker_button")
                                ) {
                                    Icon(Icons.Default.CleaningServices, contentDescription = "Clean Params", modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Clean Tracker", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = {
                                        keyboardController?.hide()
                                        viewModel.analyzeUrlAndSimulate(context)
                                    },
                                    enabled = !viewModel.isAnalyzing,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = Color.Black
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("analyze_url_button")
                                ) {
                                    if (viewModel.isAnalyzing) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.Black)
                                    } else {
                                        Icon(Icons.Default.Search, contentDescription = "Parse URL", modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Extract Video", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Display Platform shortcut grids to easily learn tips
                            Text(
                                "Supported Platforms & Help Guidelines",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val platforms = listOf("Instagram", "TikTok", "X", "YouTube")
                                platforms.forEach { plat ->
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                            .clickable {
                                                val descQuery = when (plat) {
                                                    "Instagram" -> "How do I download Instagram reels and private story video links helper?"
                                                    "TikTok" -> "Explain how to download TikTok links without watermark online."
                                                    "X" -> "Explain the direct video format URL extraction on X Twitter posts."
                                                    else -> "What are the core copyright fair use guidelines for social downloader apps on Google Play Store?"
                                                }
                                                viewModel.currentTab = 2
                                                viewModel.sendChatMessage(descQuery)
                                            }
                                            .padding(vertical = 12.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(plat, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                        }

                        // Render Parsed Result when query extraction concludes
                        viewModel.parsedVideoDetails?.let { details ->
                            item {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                    shape = RoundedCornerShape(20.dp),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp)
                                        .testTag("analyzed_video_card")
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .size(44.dp)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    details.thumbnailPlaceholderChar.toString(),
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 20.sp
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    details.title,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    "Platform: ${details.platform} | Est. Size: ${details.estimatedSize}",
                                                    fontSize = 12.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(12.dp))

                                        Text(
                                            "A static direct stream link was generated for personal download and offline review.",
                                            fontSize = 13.sp,
                                            color = Color.LightGray
                                        )

                                        Spacer(modifier = Modifier.height(16.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            OutlinedButton(
                                                onClick = {
                                                    // Ask AI about this file
                                                    viewModel.currentTab = 2
                                                    viewModel.sendChatMessage("Analyze this link and explain video structure: ${details.directVideoUrl}")
                                                },
                                                shape = RoundedCornerShape(12.dp),
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("AI Advice", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            }

                                            Button(
                                                onClick = {
                                                    viewModel.triggerDownload(
                                                        context = context,
                                                        videoUrl = details.directVideoUrl,
                                                        title = details.title,
                                                        sourceUrl = viewModel.sourceUrl,
                                                        platform = details.platform
                                                    )
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = Color.Black),
                                                shape = RoundedCornerShape(12.dp),
                                                modifier = Modifier
                                                    .weight(1.2f)
                                                    .testTag("save_video_button")
                                            ) {
                                                Icon(Icons.Default.DownloadForOffline, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Download MP4", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Database history list
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 18.dp, bottom = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "Saved Logs (${downloadsByRoom.size})",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                                if (downloadsByRoom.isNotEmpty()) {
                                    Text(
                                        "Clear All",
                                        color = Color(0xFFFF5252),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .clickable { viewModel.clearAllHistory() }
                                            .padding(4.dp)
                                            .testTag("clear_all_downloads_button")
                                    )
                                }
                            }
                        }

                        if (downloadsByRoom.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            Icons.Default.FolderOpen,
                                            contentDescription = "Empty History logs",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                            modifier = Modifier.size(48.dp)
                                        )
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Text(
                                            "No downloads logged yet.\nPaste a URL above or explore Web Sniffer!",
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        } else {
                            items(downloadsByRoom, key = { it.id }) { logItem ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp)
                                        .testTag("download_history_item_${logItem.id}"),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    shape = RoundedCornerShape(16.dp),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(
                                                    when (logItem.platform.lowercase()) {
                                                        "instagram" -> Color(0xFFD32F2F).copy(alpha = 0.2f)
                                                        "tiktok" -> Color(0xFF00E676).copy(alpha = 0.2f)
                                                        "youtube" -> Color(0xFFFF1744).copy(alpha = 0.2f)
                                                        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                                    }
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            val platChar = if (logItem.platform.isNotEmpty()) logItem.platform.first() else 'S'
                                            Text(
                                                platChar.toString(),
                                                color = when (logItem.platform.lowercase()) {
                                                    "instagram" -> Color(0xFFFF5252)
                                                    "tiktok" -> Color(0xFF00FF7F)
                                                    "youtube" -> Color(0xFFFF5252)
                                                    else -> MaterialTheme.colorScheme.primary
                                                },
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 18.sp
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(12.dp))

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                logItem.title,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp,
                                                color = Color.White,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            val timeFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
                                            Text(
                                                "${logItem.platform} • ${timeFormat.format(Date(logItem.timestamp))}",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        // Action Row for completed files (Stream/Share/Delete)
                                        IconButton(onClick = {
                                            try {
                                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                                    setDataAndType(Uri.parse(logItem.extractedVideoUrl), "video/mp4")
                                                }
                                                context.startActivity(intent)
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "Streaming file online...", Toast.LENGTH_SHORT).show()
                                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(logItem.extractedVideoUrl))
                                                context.startActivity(intent)
                                            }
                                        }) {
                                            Icon(
                                                Icons.Default.PlayArrow,
                                                contentDescription = "Play Video stream",
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }

                                        IconButton(onClick = {
                                            val intent = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_SUBJECT, "Shared Social Video")
                                                putExtra(Intent.EXTRA_TEXT, "Pasted Link: ${logItem.sourceUrl}\n\nDirect MP4 extraction: ${logItem.extractedVideoUrl}")
                                            }
                                            context.startActivity(Intent.createChooser(intent, "Share Link Details"))
                                        }) {
                                            Icon(
                                                Icons.Default.Share,
                                                contentDescription = "Share details",
                                                tint = Color.LightGray
                                            )
                                        }

                                        IconButton(onClick = { viewModel.deleteHistoryLog(logItem.id) }) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = "Delete item log",
                                                tint = Color(0xFFFF5252)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                1 -> { // Sniffer Browser screen
                    var inputBrowserQuery by remember { mutableStateOf(viewModel.browserUrl) }
                    val browserKeyboard = LocalSoftwareKeyboardController.current

                    Column(modifier = Modifier.fillMaxSize()) {
                        // Custom Browser Control panel
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF0F1411))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = inputBrowserQuery,
                                onValueChange = { inputBrowserQuery = it },
                                placeholder = { Text("Search or type URL", fontSize = 12.sp) },
                                maxLines = 1,
                                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp)
                                    .testTag("sniffer_browser_url_input"),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Uri,
                                    imeAction = ImeAction.Search
                                ),
                                keyboardActions = KeyboardActions(onSearch = {
                                    browserKeyboard?.hide()
                                    var fullUrl = inputBrowserQuery
                                    if (!fullUrl.startsWith("http://") && !fullUrl.startsWith("https://")) {
                                        fullUrl = "https://" + fullUrl
                                    }
                                    viewModel.browserUrl = fullUrl
                                }),
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Language,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                                )
                            )

                            Spacer(modifier = Modifier.width(8.dp))

                            IconButton(
                                onClick = {
                                    var finalUrl = inputBrowserQuery
                                    if (!finalUrl.startsWith("http://") && !finalUrl.startsWith("https://")) {
                                        finalUrl = "https://" + finalUrl
                                    }
                                    viewModel.browserUrl = finalUrl
                                    browserKeyboard?.hide()
                                },
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.primary)
                                    .size(44.dp)
                            ) {
                                Icon(Icons.Default.ArrowForward, contentDescription = "Browse link", tint = Color.Black)
                            }
                        }

                        // Presets bar for quick social load
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF0F1411))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            val presetSites = listOf(
                                Triple("Instagram", "https://www.instagram.com", Color(0xFFD32F2F)),
                                Triple("TikTok", "https://www.tiktok.com", Color(0xFF00E676)),
                                Triple("X.com", "https://x.com", Color(0xFFE2E8F0)),
                                Triple("Facebook", "https://www.facebook.com", Color(0xFF1976D2))
                            )
                            presetSites.forEach { (name, url, color) ->
                                Text(
                                    text = name,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(color.copy(alpha = 0.2f))
                                        .clickable {
                                            viewModel.browserUrl = url
                                            inputBrowserQuery = url
                                        }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }

                        // Web View Container
                        Box(modifier = Modifier.weight(1f)) {
                            AndroidView(
                                factory = { context ->
                                    WebView(context).apply {
                                        settings.apply {
                                            javaScriptEnabled = true
                                            domStorageEnabled = true
                                            databaseEnabled = true
                                            setSupportMultipleWindows(false)
                                            userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                                        }
                                        webViewClient = object : WebViewClient() {
                                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                                super.onPageStarted(view, url, favicon)
                                                viewModel.isBrowserLoading = true
                                                url?.let {
                                                    inputBrowserQuery = it
                                                }
                                            }

                                            override fun onPageFinished(view: WebView?, url: String?) {
                                                super.onPageFinished(view, url)
                                                viewModel.isBrowserLoading = false
                                            }

                                            override fun shouldInterceptRequest(
                                                view: WebView,
                                                request: WebResourceRequest
                                            ): WebResourceResponse? {
                                                val urlString = request.url.toString()
                                                val lowCaseUrl = urlString.lowercase()

                                                // Intercept standard social media stream formats
                                                if (lowCaseUrl.contains(".mp4") ||
                                                    lowCaseUrl.contains(".webm") ||
                                                    lowCaseUrl.contains("videoplayback") ||
                                                    lowCaseUrl.contains("video/") ||
                                                    lowCaseUrl.contains("/video-native/") ||
                                                    ((lowCaseUrl.contains(".m3u8") || lowCaseUrl.contains(".mpd")) && !lowCaseUrl.contains("key"))
                                                ) {
                                                    view.post {
                                                        Log.d("WebSniffer", "Sniffed: $urlString")
                                                        viewModel.lastSniffedVideoUrl = urlString
                                                    }
                                                }
                                                return super.shouldInterceptRequest(view, request)
                                            }
                                        }
                                        loadUrl(viewModel.browserUrl)
                                    }
                                },
                                update = { webView ->
                                    if (webView.url != viewModel.browserUrl) {
                                        webView.loadUrl(viewModel.browserUrl)
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )

                            if (viewModel.isBrowserLoading) {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .align(Alignment.TopCenter),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        // Sniffer Notification Alert banner
                        AnimatedVisibility(
                            visible = viewModel.lastSniffedVideoUrl != null,
                            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
                        ) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF0B2B18)),
                                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                                elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("sniffer_media_dialog")
                            ) {
                                Column(
                                    modifier = Modifier.padding(18.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFF34D399).copy(alpha = 0.2f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Default.Videocam, contentDescription = null, tint = Color(0xFF34D399), modifier = Modifier.size(18.dp))
                                        }

                                        Spacer(modifier = Modifier.width(12.dp))

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                "Sleek Sniffer Video Intercepted!",
                                                fontWeight = FontWeight.ExtraBold,
                                                fontSize = 14.sp,
                                                color = Color.White
                                            )
                                            Text(
                                                "Direct streaming resource caught inside social iframe.",
                                                fontSize = 11.sp,
                                                color = Color.LightGray
                                            )
                                        }

                                        IconButton(onClick = { viewModel.lastSniffedVideoUrl = null }) {
                                            Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = Color.LightGray)
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(14.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = {
                                                // Quick chat overview of the link
                                                viewModel.currentTab = 2
                                                viewModel.sendChatMessage("Analyze this live media resource sniffed URL format: ${viewModel.lastSniffedVideoUrl}")
                                            },
                                            shape = RoundedCornerShape(12.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Ask AI", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }

                                        Button(
                                            onClick = {
                                                viewModel.lastSniffedVideoUrl?.let { link ->
                                                    // Trigger background DownloadManager flow & update Room history
                                                    viewModel.triggerDownload(
                                                        context = context,
                                                        videoUrl = link,
                                                        title = "Sniffed Browser Video File",
                                                        sourceUrl = viewModel.browserUrl,
                                                        platform = "Sniffer Web"
                                                    )
                                                    viewModel.lastSniffedVideoUrl = null
                                                }
                                            },
                                            shape = RoundedCornerShape(12.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = Color.Black),
                                            modifier = Modifier.weight(1.3f)
                                        ) {
                                            Icon(Icons.Default.DownloadForOffline, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Download 1-Tap", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                2 -> { // AI Assistant (Gemini)
                    val chatHistoryList by viewModel.chatHistory.collectAsStateWithLifecycle(initialValue = emptyList())
                    val chatKeyboardController = LocalSoftwareKeyboardController.current

                    Column(modifier = Modifier.fillMaxSize()) {
                        // AI Assistant Header
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF0F1411))
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    "Downloader AI Assistant",
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    fontSize = 15.sp
                                )
                                Text(
                                    "Powered by low-latency gemini-3.1-flash-lite",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        // Presets cards to prompt assistant easily
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF0F1411).copy(alpha = 0.5f))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val promptSuggestions = listOf(
                                "Copyright & Fair Use",
                                "Strip tracking params",
                                "How direct links work"
                            )
                            promptSuggestions.forEach { suggestion ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable {
                                            viewModel.sendChatMessage(suggestion)
                                        }
                                        .padding(8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        suggestion,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }

                        // Chat lists block
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(top = 16.dp, bottom = 120.dp)
                        ) {
                            items(chatHistoryList) { (text, isUser) ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .clip(
                                                RoundedCornerShape(
                                                    topStart = 16.dp,
                                                    topEnd = 16.dp,
                                                    bottomStart = if (isUser) 16.dp else 4.dp,
                                                    bottomEnd = if (isUser) 4.dp else 16.dp
                                                )
                                            )
                                            .background(
                                                if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                                            )
                                            .padding(14.dp)
                                            .widthIn(max = 280.dp)
                                    ) {
                                        Text(
                                            text = text,
                                            fontSize = 13.sp,
                                            color = if (isUser) Color.Black else Color.White,
                                            fontWeight = if (isUser) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                }
                            }

                            if (viewModel.isChatSending) {
                                item {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                                        horizontalArrangement = Arrangement.Start,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("AI typing...", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        // Message Input Field
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF0F1411))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = inputQueryChat,
                                onValueChange = { inputQueryChat = it },
                                placeholder = { Text("Ask about video tools/Private profiles...") },
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("ai_assistant_input_field"),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = {
                                    chatKeyboardController?.hide()
                                    if (inputQueryChat.trim().isNotEmpty()) {
                                        viewModel.sendChatMessage(inputQueryChat)
                                        inputQueryChat = ""
                                    }
                                }),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                                )
                            )

                            Spacer(modifier = Modifier.width(8.dp))

                            IconButton(
                                onClick = {
                                    chatKeyboardController?.hide()
                                    if (inputQueryChat.trim().isNotEmpty()) {
                                        viewModel.sendChatMessage(inputQueryChat)
                                        inputQueryChat = ""
                                    }
                                },
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                                    .size(48.dp)
                                    .testTag("send_prompt_button")
                            ) {
                                Icon(Icons.Default.Send, contentDescription = "Send prompt button", tint = Color.Black)
                            }
                        }
                    }
                }
            }
        }
    }
}
