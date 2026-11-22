package com.tinn.picture

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

private data class OtaReleaseInfo(
    val tagName: String,
    val name: String,
    val body: String,
    val pageUrl: String,
    val apkName: String?,
    val apkUrl: String?
)

private data class OtaDownloadProgress(
    val receivedBytes: Long = 0L,
    val totalBytes: Long = -1L
) {
    val fraction: Float
        get() = if (totalBytes > 0L) (receivedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

private enum class OtaDownloadState {
    IDLE, DOWNLOADING, CANCELLED, FAILED, READY, NO_APK
}

/**
 * GitHub OTA card. When a newer release is found, a full-screen black update page
 * opens automatically and starts downloading the APK. Existing MainActivity call
 * remains compatible: GitHubOtaUpdaterSection(owner, repository, currentVersionName).
 */
@Composable
fun GitHubOtaUpdaterSection(
    owner: String,
    repository: String,
    currentVersionName: String
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Kiểm tra bản phát hành mới trên GitHub") }
    var release by remember { mutableStateOf<OtaReleaseInfo?>(null) }
    var showUpdatePage by remember { mutableStateOf(false) }
    var downloadState by remember { mutableStateOf(OtaDownloadState.IDLE) }
    var downloadMessage by remember { mutableStateOf("Đang chuẩn bị tải bản cập nhật...") }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var downloadedApk by remember { mutableStateOf<File?>(null) }
    val progressFlow = remember { MutableStateFlow(OtaDownloadProgress()) }
    val progress by progressFlow.collectAsState()

    fun startDownload(info: OtaReleaseInfo) {
        val apkUrl = info.apkUrl
        if (apkUrl.isNullOrBlank()) {
            downloadState = OtaDownloadState.NO_APK
            downloadMessage = "GitHub Release này chưa đính kèm file APK."
            return
        }

        downloadJob?.cancel()
        downloadedApk = null
        progressFlow.value = OtaDownloadProgress()
        downloadState = OtaDownloadState.DOWNLOADING
        downloadMessage = "Đang tải APK từ GitHub..."
        downloadJob = scope.launch {
            try {
                val apkFile = withContext(Dispatchers.IO) {
                    downloadReleaseApk(
                        cacheDir = context.cacheDir,
                        fileName = info.apkName ?: "PhotosByTinn-update.apk",
                        downloadUrl = apkUrl,
                        onProgress = { received, total ->
                            progressFlow.value = OtaDownloadProgress(received, total)
                        }
                    )
                }
                downloadedApk = apkFile
                downloadState = OtaDownloadState.READY
                downloadMessage = "Tải hoàn tất. Đang mở trình cài đặt Android..."
                downloadMessage = launchApkInstaller(context, apkFile)
            } catch (cancelled: CancellationException) {
                downloadState = OtaDownloadState.CANCELLED
                downloadMessage = "Đã ngừng tải. Bạn có thể tải lại khi sẵn sàng."
                throw cancelled
            } catch (error: Exception) {
                downloadState = OtaDownloadState.FAILED
                downloadMessage = "Tải cập nhật thất bại: ${error.message ?: "lỗi không xác định"}"
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF202027))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Text("GitHub OTA", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                text = "$owner/$repository · đang dùng v$currentVersionName",
                color = Color(0xFF9CA3AF),
                fontSize = 11.sp
            )
            Text(message, color = Color(0xFFD1D5DB), fontSize = 12.sp)

            OutlinedButton(
                onClick = {
                    scope.launch {
                        checking = true
                        message = "Đang kiểm tra GitHub Releases..."
                        try {
                            val latest = withContext(Dispatchers.IO) {
                                fetchLatestGitHubRelease(owner, repository)
                            }
                            release = latest
                            if (compareOtaVersions(latest.tagName, currentVersionName) > 0) {
                                showUpdatePage = true
                                startDownload(latest)
                                message = "Đã tìm thấy bản cập nhật ${latest.tagName}."
                            } else {
                                message = "Bạn đang dùng phiên bản mới nhất (hoặc Release không mới hơn v$currentVersionName)."
                            }
                        } catch (error: Exception) {
                            message = "Không kiểm tra được: ${error.message ?: "lỗi mạng/GitHub"}"
                        } finally {
                            checking = false
                        }
                    }
                },
                enabled = !checking && downloadState != OtaDownloadState.DOWNLOADING,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (checking) "Đang kiểm tra..." else "Kiểm tra update", fontSize = 12.sp)
            }
        }
    }

    if (showUpdatePage) {
        val info = release
        BackHandler(enabled = true) {
            downloadJob?.cancel()
            showUpdatePage = false
        }
        Dialog(
            onDismissRequest = {
                downloadJob?.cancel()
                showUpdatePage = false
            },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(horizontal = 22.dp, vertical = 18.dp)
                ) {
                    Text(
                        text = "PHOTOS BY TINN",
                        color = Color(0xFF4ADE80),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Cập nhật ứng dụng",
                        color = Color.White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(7.dp))
                    Text(
                        text = "v$currentVersionName  →  ${info?.tagName ?: "Bản mới"}",
                        color = Color(0xFF4ADE80),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    info?.name?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(7.dp))
                        Text(it, color = Color(0xFFE5E7EB), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }

                    Spacer(Modifier.height(22.dp))
                    Text(
                        "NỘI DUNG CẬP NHẬT",
                        color = Color(0xFF9CA3AF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.4.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    Column(
                        modifier = Modifier.weight(1f).fillMaxWidth()
                            .background(Color(0xFF111114), RoundedCornerShape(14.dp))
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        Text(
                            text = info?.body?.trim()?.take(6000)?.ifBlank { "Bản cập nhật này chưa có mô tả." }
                                ?: "Không đọc được nội dung cập nhật.",
                            color = Color(0xFFD1D5DB),
                            fontSize = 14.sp,
                            lineHeight = 21.sp
                        )
                    }

                    Spacer(Modifier.height(20.dp))
                    Text(downloadMessage, color = Color(0xFFE5E7EB), fontSize = 12.sp)
                    Spacer(Modifier.height(10.dp))
                    if (progress.totalBytes > 0L) {
                        LinearProgressIndicator(
                            progress = progress.fraction,
                            modifier = Modifier.fillMaxWidth().height(7.dp),
                            color = Color(0xFF22C55E),
                            trackColor = Color(0xFF303038)
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${(progress.fraction * 100).toInt()}%  ·  ${formatOtaBytes(progress.receivedBytes)} / ${formatOtaBytes(progress.totalBytes)}",
                            color = Color(0xFF9CA3AF),
                            fontSize = 11.sp
                        )
                    } else if (downloadState == OtaDownloadState.DOWNLOADING) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(7.dp),
                            color = Color(0xFF22C55E),
                            trackColor = Color(0xFF303038)
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Đã tải ${formatOtaBytes(progress.receivedBytes)}",
                            color = Color(0xFF9CA3AF),
                            fontSize = 11.sp
                        )
                    } else {
                        LinearProgressIndicator(
                            progress = if (downloadState == OtaDownloadState.READY) 1f else 0f,
                            modifier = Modifier.fillMaxWidth().height(7.dp),
                            color = Color(0xFF22C55E),
                            trackColor = Color(0xFF303038)
                        )
                    }

                    Spacer(Modifier.height(18.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                when (downloadState) {
                                    OtaDownloadState.DOWNLOADING -> downloadJob?.cancel()
                                    OtaDownloadState.CANCELLED, OtaDownloadState.FAILED -> info?.let(::startDownload)
                                    OtaDownloadState.READY -> downloadedApk?.let { apk ->
                                        downloadMessage = launchApkInstaller(context, apk)
                                    }
                                    else -> Unit
                                }
                            },
                            enabled = downloadState != OtaDownloadState.IDLE && downloadState != OtaDownloadState.NO_APK,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) {
                            Text(
                                when (downloadState) {
                                    OtaDownloadState.DOWNLOADING -> "Ngừng"
                                    OtaDownloadState.CANCELLED -> "Tải lại"
                                    OtaDownloadState.FAILED -> "Thử lại"
                                    OtaDownloadState.READY -> "Cài lại"
                                    else -> "Ngừng"
                                }
                            )
                        }
                        Button(
                            onClick = {
                                downloadJob?.cancel()
                                showUpdatePage = false
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF202027))
                        ) {
                            Text("Thoát", color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

private fun fetchLatestGitHubRelease(owner: String, repository: String): OtaReleaseInfo {
    require(owner.isNotBlank() && repository.isNotBlank()) { "Thiếu tên GitHub owner/repository" }
    val endpoint = "https://api.github.com/repos/$owner/$repository/releases/latest"
    val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 12_000
        readTimeout = 20_000
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        setRequestProperty("User-Agent", "Photos-by-Tinn-Android")
    }
    try {
        val code = connection.responseCode
        if (code !in 200..299) {
            throw IllegalStateException(
                when (code) {
                    404 -> "Không tìm thấy repo/release công khai. Kiểm tra tên repo và đã Publish Release chưa."
                    403 -> "GitHub đang giới hạn yêu cầu; thử lại sau."
                    else -> "GitHub HTTP $code"
                }
            )
        }
        val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        val assets = json.optJSONArray("assets")
        var apkName: String? = null
        var apkUrl: String? = null
        if (assets != null) {
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                val name = asset.optString("name")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkName = name
                    apkUrl = asset.optString("browser_download_url").takeIf { it.startsWith("https://") }
                    break
                }
            }
        }
        return OtaReleaseInfo(
            tagName = json.optString("tag_name"),
            name = json.optString("name"),
            body = json.optString("body"),
            pageUrl = json.optString("html_url"),
            apkName = apkName,
            apkUrl = apkUrl
        )
    } finally {
        connection.disconnect()
    }
}

private fun compareOtaVersions(remoteTag: String, currentVersion: String): Int {
    fun parts(value: String): List<Int> = Regex("\\d+").findAll(value).mapNotNull { it.value.toIntOrNull() }.toList()
    val remote = parts(remoteTag)
    val current = parts(currentVersion)
    if (remote.isEmpty() || current.isEmpty()) {
        return remoteTag.removePrefix("v").compareTo(currentVersion.removePrefix("v"), ignoreCase = true)
    }
    for (index in 0 until maxOf(remote.size, current.size)) {
        val left = remote.getOrElse(index) { 0 }
        val right = current.getOrElse(index) { 0 }
        if (left != right) return left.compareTo(right)
    }
    return 0
}

private suspend fun downloadReleaseApk(
    cacheDir: File,
    fileName: String,
    downloadUrl: String,
    onProgress: (receivedBytes: Long, totalBytes: Long) -> Unit
): File {
    require(downloadUrl.startsWith("https://github.com/") || downloadUrl.startsWith("https://objects.githubusercontent.com/")) {
        "Link APK không thuộc GitHub Releases"
    }
    val otaDir = File(cacheDir, "ota").apply { mkdirs() }
    val safeName = fileName.substringAfterLast('/').takeIf { it.endsWith(".apk", true) } ?: "PhotosByTinn-update.apk"
    val outputFile = File(otaDir, "PhotosByTinn-update.apk")
    val partialFile = File(otaDir, "$safeName.part")
    val connection = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = true
        connectTimeout = 15_000
        readTimeout = 60_000
        setRequestProperty("Accept", "application/octet-stream")
        setRequestProperty("User-Agent", "Photos-by-Tinn-Android")
    }
    try {
        val responseCode = connection.responseCode
        if (responseCode !in 200..299) throw IllegalStateException("Không tải được APK (HTTP $responseCode)")
        val total = connection.contentLengthLong
        var received = 0L
        connection.inputStream.use { input ->
            FileOutputStream(partialFile).use { output ->
                val buffer = ByteArray(64 * 1024)
                var lastPublish = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    received += count
                    val now = System.currentTimeMillis()
                    if (now - lastPublish >= 100 || (total > 0L && received >= total)) {
                        onProgress(received, total)
                        lastPublish = now
                    }
                }
                output.flush()
            }
        }
        coroutineContext.ensureActive()
        if (!partialFile.exists() || partialFile.length() < 4L) {
            throw IllegalStateException("File APK tải về không hợp lệ")
        }
        if (outputFile.exists()) outputFile.delete()
        if (!partialFile.renameTo(outputFile)) {
            partialFile.copyTo(outputFile, overwrite = true)
            partialFile.delete()
        }
        onProgress(outputFile.length(), if (total > 0L) total else outputFile.length())
        return outputFile
    } catch (error: Throwable) {
        partialFile.delete()
        throw error
    } finally {
        connection.disconnect()
    }
}

private fun launchApkInstaller(context: Context, apkFile: File): String {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
        val settingsIntent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(settingsIntent)
        return "Đã mở cài đặt quyền cài APK. Cho phép nguồn này, quay lại và nhấn Cài lại."
    }

    val contentUri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        apkFile
    )
    val installIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(contentUri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(installIntent)
    return "Đã mở trình cài đặt Android. Xác nhận cập nhật tại hộp thoại hệ thống."
}

private fun formatOtaBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format("%.1f MB", mb)
    return String.format("%.2f GB", mb / 1024.0)
}
