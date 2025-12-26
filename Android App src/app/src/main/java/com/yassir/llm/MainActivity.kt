package com.yassir.llm

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

val DeepBlack = Color(0xFF000000)
val NetflixRed = Color(0xFFE50914)
val DarkGray = Color(0xFF1A1A1A)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = NetflixRed, background = DeepBlack, surface = DarkGray)) {
                var showIntro by remember { mutableStateOf(true) }
                if (showIntro) { IntroScreen { showIntro = false } }
                else { Surface(color = DeepBlack, modifier = Modifier.fillMaxSize()) { StreamChatScreen() } }
            }
        }
    }
}

@Composable
fun IntroScreen(onFinished: () -> Unit) {
    LaunchedEffect(Unit) { delay(1200); onFinished() }
    Box(modifier = Modifier.fillMaxSize().background(DeepBlack), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("YASSER AI", color = NetflixRed, fontSize = 35.sp, fontWeight = FontWeight.Black)
            Spacer(modifier = Modifier.height(24.dp))
            CircularProgressIndicator(color = NetflixRed, strokeWidth = 2.dp, modifier = Modifier.size(40.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamChatScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sharedPrefs = remember { context.getSharedPreferences("ChatStorage", Context.MODE_PRIVATE) }

    // قائمة الـ Hosts جعلتها قابلة للتعديل
    val hosts = remember { mutableStateListOf("http://192.168.1.2:5005", "http://192.168.1.2:8080") }
    var currentBaseUrl by remember { mutableStateOf(hosts[0]) }
    var hostMenuExpanded by remember { mutableStateOf(false) }

    var textInput by remember { mutableStateOf("") }
    val chatMessages = remember { mutableStateListOf<Pair<Boolean, String>>() }
    var isLoading by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val modelsList = remember { mutableStateListOf<String>() }
    var selectedModel by remember { mutableStateOf("تحميل...") }
    var modelExpanded by remember { mutableStateOf(false) }
    var currentCall by remember { mutableStateOf<Call?>(null) }
    var connectionStatus by remember { mutableStateOf("فحص الاتصال...") }
    var statusColor by remember { mutableStateOf(Color.Gray) }

    // دالة لإظهار Dialog إضافة IP جديد
    fun showAddHostDialog() {
        val builder = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        builder.setTitle("إضافة عنوان IP جديد")
        val input = EditText(context).apply {
            hint = "192.168.1.10:11434"
            setTextColor(AndroidColor.WHITE)
        }
        val container = FrameLayout(context).apply {
            addView(input, FrameLayout.LayoutParams(-1, -2).apply { setMargins(60, 40, 60, 40) })
        }
        builder.setView(container)
        builder.setPositiveButton("إضافة") { _, _ ->
            val newIp = input.text.toString().trim()
            if (newIp.isNotEmpty()) {
                val formattedIp = if (newIp.startsWith("http")) newIp else "http://$newIp"
                if (!hosts.contains(formattedIp)) hosts.add(formattedIp)
            }
        }
        builder.setNegativeButton("إلغاء", null)
        builder.show()
    }

    fun getStoreKey() = "chat_${currentBaseUrl.substringAfterLast(":")}_$selectedModel"

    fun saveChat() {
        if (selectedModel == "تحميل...") return
        val array = JSONArray()
        chatMessages.forEach { (isUser, text) ->
            array.put(JSONObject().apply { put("isUser", isUser); put("text", text) })
        }
        sharedPrefs.edit { putString(getStoreKey(), array.toString()) }
    }

    fun loadChat() {
        chatMessages.clear()
        val data = sharedPrefs.getString(getStoreKey(), null)
        data?.let {
            val array = JSONArray(it)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                chatMessages.add(obj.getBoolean("isUser") to obj.getString("text"))
            }
        }
    }

    fun fetchModels(url: String) {
        scope.launch(Dispatchers.IO) {
            val endpoints = listOf("/api/models", "/v1/models", "/api/tags")
            var found = false
            for (end in endpoints) {
                try {
                    val resp = OkHttpClient().newCall(Request.Builder().url(url + end).build()).execute()
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: ""
                        val json = JSONObject(body)
                        val list = mutableListOf<String>()
                        if (json.has("models")) {
                            val arr = json.getJSONArray("models")
                            for (i in 0 until arr.length()) list.add(arr.getJSONObject(i).getString("name"))
                        } else if (json.has("data")) {
                            val arr = json.getJSONArray("data")
                            for (i in 0 until arr.length()) list.add(arr.getJSONObject(i).getString("id"))
                        }
                        scope.launch(Dispatchers.Main) {
                            modelsList.clear(); modelsList.addAll(list)
                            if (list.isNotEmpty()) {
                                selectedModel = list[0]
                                loadChat()
                            }
                        }
                        found = true; break
                    }
                } catch (_: Exception) {}
            }
            if (!found) scope.launch(Dispatchers.Main) { selectedModel = "لا توجد نماذج" }
        }
    }

    LaunchedEffect(currentBaseUrl) {
        fetchModels(currentBaseUrl)
        scope.launch(Dispatchers.IO) {
            while(true) {
                try {
                    OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).build()
                        .newCall(Request.Builder().url(currentBaseUrl).build()).execute().use {
                            connectionStatus = "Online: ${currentBaseUrl.substringAfterLast(":")}"; statusColor = Color(0xFF00FF00)
                        }
                } catch (_: Exception) { connectionStatus = "Offline"; statusColor = Color.Red }
                delay(5000)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Spacer(modifier = Modifier.statusBarsPadding())

        Box(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(
                text = connectionStatus,
                color = statusColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 4.dp)
            )
        }

        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            // زر اختيار الـ Host (المنفذ)
            Box {
                IconButton(onClick = { hostMenuExpanded = true }, modifier = Modifier.background(DarkGray, CircleShape).size(45.dp)) {
                    Icon(Icons.Default.Settings, contentDescription = null, tint = NetflixRed)
                }
                DropdownMenu(expanded = hostMenuExpanded, onDismissRequest = { hostMenuExpanded = false }, modifier = Modifier.background(DarkGray)) {
                    hosts.forEach { host ->
                        DropdownMenuItem(
                            text = { Text("Port: ${host.substringAfterLast(":")}", color = Color.White) },
                            onClick = {
                                hostMenuExpanded = false
                                currentBaseUrl = host
                                chatMessages.clear()
                                fetchModels(host)
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // قائمة النماذج (تأخذ المساحة الأكبر)
            OutlinedCard(onClick = { modelExpanded = true }, shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = DarkGray), modifier = Modifier.weight(1f)) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(selectedModel, color = Color.White, modifier = Modifier.weight(1f), maxLines = 1)
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = NetflixRed)
                }
            }
            DropdownMenu(expanded = modelExpanded, onDismissRequest = { modelExpanded = false }, modifier = Modifier.background(DarkGray)) {
                modelsList.forEach { model ->
                    DropdownMenuItem(text = { Text(model, color = Color.White) }, onClick = { selectedModel = model; modelExpanded = false })
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // زر "+" الجديد على يمين قائمة النماذج
            IconButton(
                onClick = { showAddHostDialog() },
                modifier = Modifier.background(DarkGray, RoundedCornerShape(12.dp)).size(45.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add IP", tint = NetflixRed)
            }
        }

        LazyColumn(modifier = Modifier.weight(1f), state = listState) {
            itemsIndexed(chatMessages) { _, msg ->
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalAlignment = if (msg.first) Alignment.End else Alignment.Start) {
                    Surface(
                        color = if (msg.first) NetflixRed else DarkGray,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.clickable {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("YASSER_AI", msg.second))
                            Toast.makeText(context, "Copied!", Toast.LENGTH_SHORT).show()
                        }.widthIn(max = 300.dp)
                    ) { Text(text = msg.second, color = Color.White, modifier = Modifier.padding(12.dp), fontSize = 15.sp) }
                }
            }
        }

        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(vertical = 12.dp).fillMaxWidth()) {
            IconButton(onClick = { chatMessages.clear(); saveChat() }, modifier = Modifier.padding(bottom = 4.dp).background(DarkGray, CircleShape)) {
                Icon(Icons.Default.Delete, contentDescription = null, tint = Color.Gray)
            }
            Spacer(modifier = Modifier.width(8.dp))
            TextField(
                value = textInput, onValueChange = { textInput = it }, modifier = Modifier.weight(1f).heightIn(max = 160.dp),
                placeholder = { Text("Type something...", color = Color.Gray) },
                colors = TextFieldDefaults.colors(focusedContainerColor = DarkGray, unfocusedContainerColor = DarkGray, focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = NetflixRed, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                shape = RoundedCornerShape(26.dp), enabled = !isLoading
            )
            Spacer(modifier = Modifier.width(8.dp))
            FloatingActionButton(
                onClick = {
                    if (isLoading) { currentCall?.cancel(); isLoading = false }
                    else if (textInput.isNotBlank()) {
                        val prompt = textInput
                        chatMessages.add(true to prompt)
                        val aiIndex = chatMessages.size
                        chatMessages.add(false to "...")
                        textInput = ""; isLoading = true

                        scope.launch(Dispatchers.IO) {
                            try {
                                val is8080 = currentBaseUrl.contains("8080")
                                val url = if (is8080) "$currentBaseUrl/v1/chat/completions" else "$currentBaseUrl/api/generate"
                                val json = JSONObject()
                                if (is8080) {
                                    json.put("model", selectedModel)
                                    json.put("messages", JSONArray().put(JSONObject().apply { put("role", "user"); put("content", prompt) }))
                                    json.put("stream", true)
                                } else {
                                    json.put("model", selectedModel); json.put("prompt", prompt)
                                }

                                val req = Request.Builder().url(url).post(json.toString().toRequestBody("application/json".toMediaType())).build()
                                val client = OkHttpClient.Builder().connectTimeout(60, TimeUnit.SECONDS).readTimeout(10, TimeUnit.MINUTES).build()
                                val call = client.newCall(req); currentCall = call
                                call.execute().use { resp ->
                                    val reader = resp.body?.charStream()?.buffered()
                                    var result = ""
                                    reader?.forEachLine { line ->
                                        if (line.isNotEmpty()) {
                                            val chunk = if (is8080) {
                                                if (line.startsWith("data: ") && !line.contains("[DONE]")) {
                                                    JSONObject(line.substring(6)).getJSONArray("choices").getJSONObject(0).getJSONObject("delta").optString("content", "")
                                                } else ""
                                            } else { JSONObject(line).optString("response", "") }

                                            if (chunk.isNotEmpty() && chunk != "null") {
                                                result += chunk
                                                scope.launch(Dispatchers.Main) { chatMessages[aiIndex] = false to result; listState.scrollToItem(chatMessages.size - 1) }
                                            }
                                        }
                                    }
                                    scope.launch(Dispatchers.Main) { saveChat() }
                                }
                            } catch (_: Exception) {
                                if (currentCall?.isCanceled() != true) scope.launch(Dispatchers.Main) { chatMessages[aiIndex] = false to "Error Connecting" }
                            } finally { scope.launch(Dispatchers.Main) { isLoading = false } }
                        }
                    }
                },
                containerColor = NetflixRed, shape = CircleShape, modifier = Modifier.size(52.dp)
            ) { Icon(if (isLoading) Icons.Default.Close else Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = Color.White) }
        }
        Spacer(modifier = Modifier.navigationBarsPadding())
    }
}