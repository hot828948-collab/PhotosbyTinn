
package com.tinn.picture

import android.content.Context
import android.graphics.Typeface
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

private const val FONT_PREFS = "photos_by_tinn_font_prefs"
private const val FONT_SELECTED_KEY = "selected_font_id"

private const val FONT_BASE_URL =
    "https://raw.githubusercontent.com/hot828948-collab/PhotosbyTinn/main/resources/fonts/"

private const val FONT_MANIFEST_URL = "${FONT_BASE_URL}manifest.json"
private const val MAX_FONT_BYTES = 20L * 1024L * 1024L

/**
 * One downloadable TTF font declared by resources/fonts/manifest.json.
 */
data class FontPackEntry(
    val id: String,
    val name: String,
    val description: String,
    val file: String
)

data class FontPackUiState(
    val fonts: List<FontPackEntry> = emptyList(),
    val installedIds: Set<String> = emptySet(),
    val selectedId: String? = null,
    val isLoading: Boolean = false,
    val downloadingId: String? = null,
    val message: String? = null
)

/**
 * Downloads only TTF files from this app's public GitHub repository.
 * Fonts are saved inside filesDir/font_packs.
 */
object FontPackManager {

    private val mutableState = MutableStateFlow(FontPackUiState())
    val state = mutableState.asStateFlow()

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        val directory = fontDirectory(appContext)

        val installed = directory.listFiles()
            ?.filter {
                it.isFile && it.extension.equals("ttf", ignoreCase = true)
            }
            ?.map { it.nameWithoutExtension }
            ?.toSet()
            ?: emptySet()

        val savedId = appContext
            .getSharedPreferences(FONT_PREFS, Context.MODE_PRIVATE)
            .getString(FONT_SELECTED_KEY, null)

        val selected = savedId?.takeIf { it in installed }

        mutableState.value = mutableState.value.copy(
            installedIds = installed,
            selectedId = selected
        )
    }

    suspend fun refreshCatalog(context: Context) {
        mutableState.value = mutableState.value.copy(
            isLoading = true,
            message = null
        )

        try {
            val fonts = withContext(Dispatchers.IO) {
                val jsonText = readUrlText(FONT_MANIFEST_URL)
                val root = JSONObject(jsonText)

                val array = root.optJSONArray("fonts")
                    ?: throw IllegalArgumentException(
                        "manifest.json thiếu mảng 'fonts'"
                    )

                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue

                        val id = item.optString("id").trim()
                        val name = item.optString("name").trim()
                        val file = item.optString("file").trim()
                        val description = item.optString(
                            "description",
                            "Font TTF"
                        )

                        // Validate ID.
                        if (!id.matches(Regex("[A-Za-z0-9_-]{1,48}"))) {
                            continue
                        }

                        // Validate name.
                        if (name.isBlank()) {
                            continue
                        }

                        // Only allow simple TTF filenames.
                        if (!file.matches(
                                Regex(
                                    "[A-Za-z0-9_.-]{1,100}\\.ttf",
                                    RegexOption.IGNORE_CASE
                                )
                            )
                        ) {
                            continue
                        }

                        add(
                            FontPackEntry(
                                id = id,
                                name = name,
                                description = description,
                                file = file
                            )
                        )
                    }
                }
            }

            mutableState.value = mutableState.value.copy(
                fonts = fonts,
                isLoading = false,
                message = if (fonts.isEmpty()) {
                    "Chưa có font hợp lệ trong manifest.json"
                } else {
                    "Đã cập nhật danh sách font"
                }
            )

            initialize(context)

        } catch (error: Exception) {
            mutableState.value = mutableState.value.copy(
                isLoading = false,
                message = "Không tải được danh sách font: ${
                    error.localizedMessage ?: "Lỗi kết nối"
                }"
            )
        }
    }

    suspend fun downloadAndInstall(
        context: Context,
        entry: FontPackEntry
    ) {
        mutableState.value = mutableState.value.copy(
            downloadingId = entry.id,
            message = "Đang tải ${entry.name}..."
        )

        try {
            withContext(Dispatchers.IO) {
                val directory = fontDirectory(context.applicationContext)

                if (!directory.exists() && !directory.mkdirs()) {
                    throw IllegalStateException(
                        "Không tạo được thư mục lưu font"
                    )
                }

                val temporaryFile = File(
                    directory,
                    "${entry.id}.ttf.part"
                )

                val targetFile = File(
                    directory,
                    "${entry.id}.ttf"
                )

                temporaryFile.delete()

                try {
                    downloadToFile(
                        "$FONT_BASE_URL${entry.file}",
                        temporaryFile
                    )

                    // Validate the downloaded font.
                    Typeface.createFromFile(temporaryFile.absolutePath)

                    if (targetFile.exists() && !targetFile.delete()) {
                        throw IllegalStateException(
                            "Không thay thế được font cũ"
                        )
                    }

                    if (!temporaryFile.renameTo(targetFile)) {
                        throw IllegalStateException(
                            "Không lưu được font đã tải"
                        )
                    }

                } finally {
                    temporaryFile.delete()
                }
            }

            initialize(context)

            mutableState.value = mutableState.value.copy(
                downloadingId = null,
                message = "Đã tải ${entry.name}. Nhấn Áp dụng để dùng font."
            )

        } catch (error: Exception) {
            mutableState.value = mutableState.value.copy(
                downloadingId = null,
                message = "Tải font thất bại: ${
                    error.localizedMessage ?: "Lỗi không xác định"
                }"
            )
        }
    }

    fun apply(context: Context, fontId: String) {
        val file = File(
            fontDirectory(context.applicationContext),
            "$fontId.ttf"
        )

        if (!file.isFile) {
            mutableState.value = mutableState.value.copy(
                message = "Font chưa được tải về"
            )
            return
        }

        try {
            Typeface.createFromFile(file.absolutePath)

            context.applicationContext
                .getSharedPreferences(FONT_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(FONT_SELECTED_KEY, fontId)
                .apply()

            mutableState.value = mutableState.value.copy(
                selectedId = fontId,
                message = "Đã áp dụng font. Giao diện đã được cập nhật."
            )

        } catch (error: Exception) {
            mutableState.value = mutableState.value.copy(
                message = "Không thể sử dụng font này: ${
                    error.localizedMessage ?: "Font không hợp lệ"
                }"
            )
        }
    }

    fun resetToDefault(context: Context) {
        context.applicationContext
            .getSharedPreferences(FONT_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(FONT_SELECTED_KEY)
            .apply()

        mutableState.value = mutableState.value.copy(
            selectedId = null,
            message = "Đã khôi phục font mặc định"
        )
    }

    fun fontFamily(
        context: Context,
        fontId: String?
    ): FontFamily {
        if (fontId.isNullOrBlank()) {
            return FontFamily.Default
        }

        return try {
            val file = File(
                fontDirectory(context.applicationContext),
                "$fontId.ttf"
            )

            if (file.isFile) {
                FontFamily(Typeface.createFromFile(file.absolutePath))
            } else {
                FontFamily.Default
            }

        } catch (_: Exception) {
            FontFamily.Default
        }
    }

    private fun fontDirectory(context: Context): File {
        return File(context.filesDir, "font_packs")
    }

    private fun readUrlText(url: String): String {
        val connection = URL(url)
            .openConnection() as HttpURLConnection

        connection.apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            requestMethod = "GET"
            instanceFollowRedirects = true
        }

        try {
            val code = connection.responseCode

            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code")
            }

            val bytes = connection.inputStream.use { input ->
                val result = input.readBytes()

                if (result.size > 1_000_000) {
                    throw IllegalStateException("Manifest quá lớn")
                }

                result
            }

            return bytes.toString(Charsets.UTF_8)

        } finally {
            connection.disconnect()
        }
    }

    private fun downloadToFile(
        url: String,
        destination: File
    ) {
        val connection = URL(url)
            .openConnection() as HttpURLConnection

        connection.apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "GET"
            instanceFollowRedirects = true
        }

        try {
            val code = connection.responseCode

            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code")
            }

            val contentLength = connection.contentLengthLong

            if (contentLength > MAX_FONT_BYTES) {
                throw IllegalStateException("Font vượt quá 20 MB")
            }

            var totalBytes = 0L

            connection.inputStream.use { input ->
                FileOutputStream(destination).use { output ->

                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

                    while (true) {
                        val count = input.read(buffer)

                        if (count < 0) break

                        totalBytes += count

                        if (totalBytes > MAX_FONT_BYTES) {
                            throw IllegalStateException(
                                "Font vượt quá 20 MB"
                            )
                        }

                        output.write(buffer, 0, count)
                    }

                    output.fd.sync()
                }
            }

            if (totalBytes == 0L) {
                throw IllegalStateException("File font rỗng")
            }

        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Wrap the whole app with this composable to apply the selected font globally.
 */
@Composable
fun FontPackTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val state by FontPackManager.state.collectAsState()

    LaunchedEffect(context) {
        FontPackManager.initialize(context)
    }

    // Read the current theme outside remember.
    val parentColorScheme = MaterialTheme.colorScheme
    val parentTypography = MaterialTheme.typography
    val parentShapes = MaterialTheme.shapes

    val fontFamily = remember(context, state.selectedId) {
        FontPackManager.fontFamily(context, state.selectedId)
    }

    val typography = remember(parentTypography, fontFamily) {
        parentTypography.withFontFamily(fontFamily)
    }

    MaterialTheme(
        colorScheme = parentColorScheme,
        typography = typography,
        shapes = parentShapes,
        content = content
    )
}

private fun androidx.compose.material3.Typography.withFontFamily(
    fontFamily: FontFamily
): androidx.compose.material3.Typography = copy(
    displayLarge = displayLarge.copy(fontFamily = fontFamily),
    displayMedium = displayMedium.copy(fontFamily = fontFamily),
    displaySmall = displaySmall.copy(fontFamily = fontFamily),
    headlineLarge = headlineLarge.copy(fontFamily = fontFamily),
    headlineMedium = headlineMedium.copy(fontFamily = fontFamily),
    headlineSmall = headlineSmall.copy(fontFamily = fontFamily),
    titleLarge = titleLarge.copy(fontFamily = fontFamily),
    titleMedium = titleMedium.copy(fontFamily = fontFamily),
    titleSmall = titleSmall.copy(fontFamily = fontFamily),
    bodyLarge = bodyLarge.copy(fontFamily = fontFamily),
    bodyMedium = bodyMedium.copy(fontFamily = fontFamily),
    bodySmall = bodySmall.copy(fontFamily = fontFamily),
    labelLarge = labelLarge.copy(fontFamily = fontFamily),
    labelMedium = labelMedium.copy(fontFamily = fontFamily),
    labelSmall = labelSmall.copy(fontFamily = fontFamily)
)

/**
 * Add this section inside DeveloperInfoScreen's main Column.
 */
@Composable
fun FontPackSettingsSection() {
    val context = LocalContext.current
    val state by FontPackManager.state.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (state.fonts.isEmpty() && !state.isLoading) {
            FontPackManager.refreshCatalog(context)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1A1A1E)
        ),
        border = BorderStroke(1.dp, Color(0xFF2B2B31))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Font Pack",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                "Tải font TTF và áp dụng cho toàn bộ Photos by Tinn.",
                color = Color(0xFF9CA3AF),
                fontSize = 12.sp
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            FontPackManager.refreshCatalog(context)
                        }
                    },
                    enabled = !state.isLoading,
                    modifier = Modifier.weight(1f)
                ) {
                    if (state.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Làm mới")
                    }
                }

                OutlinedButton(
                    onClick = {
                        FontPackManager.resetToDefault(context)
                    },
                    enabled = state.selectedId != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Mặc định")
                }
            }

            if (state.fonts.isEmpty() && !state.isLoading) {
                Text(
                    "Chưa tìm thấy font. Kiểm tra resources/fonts/manifest.json trên GitHub.",
                    color = Color(0xFFFBBF24),
                    fontSize = 12.sp
                )
            }

            state.fonts.forEach { font ->
                val installed = font.id in state.installedIds
                val selected = font.id == state.selectedId

                val previewFamily = remember(font.id, installed) {
                    if (installed) {
                        FontPackManager.fontFamily(context, font.id)
                    } else {
                        FontFamily.Default
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFF242429),
                    border = BorderStroke(
                        1.dp,
                        if (selected) {
                            Color(0xFF60A5FA)
                        } else {
                            Color(0xFF34343C)
                        }
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    font.name,
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold
                                )

                                Text(
                                    font.description,
                                    color = Color(0xFF9CA3AF),
                                    fontSize = 11.sp
                                )
                            }

                            if (selected) {
                                Text(
                                    "Đang dùng",
                                    color = Color(0xFF4ADE80),
                                    fontSize = 11.sp
                                )
                            }
                        }

                        Text(
                            "Aa Bb Cc 123 — Photos by Tinn",
                            color = Color.White,
                            fontFamily = previewFamily,
                            fontSize = 19.sp
                        )

                        when {
                            installed && !selected -> {
                                Button(
                                    onClick = {
                                        FontPackManager.apply(context, font.id)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Áp dụng")
                                }
                            }

                            selected -> {
                                OutlinedButton(
                                    onClick = {
                                        FontPackManager.resetToDefault(context)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Đang áp dụng · Khôi phục mặc định")
                                }
                            }

                            else -> {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            FontPackManager.downloadAndInstall(
                                                context,
                                                font
                                            )
                                        }
                                    },
                                    enabled = state.downloadingId == null,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    if (state.downloadingId == font.id) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp
                                        )

                                        Spacer(Modifier.size(8.dp))
                                        Text("Đang tải...")
                                    } else {
                                        Text("Tải font")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            state.message?.let { message ->
                Text(
                    message,
                    color = Color(0xFF9CA3AF),
                    fontSize = 11.sp
                )
            }
        }
    }
}
