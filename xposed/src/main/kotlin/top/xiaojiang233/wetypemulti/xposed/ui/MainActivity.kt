package top.xiaojiang233.wetypemulti.xposed.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.xiaojiang233.wetypemulti.common.keyboard.AvailableKeyboards
import top.xiaojiang233.wetypemulti.common.keyboard.KeyboardInfo
import top.xiaojiang233.wetypemulti.common.update.AppRelease
import top.xiaojiang233.wetypemulti.xposed.R
import top.xiaojiang233.wetypemulti.xposed.XposedCompat
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.theme.MiuixTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MiuixTheme {
                MainScreen()
            }
        }
    }
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dictManager = remember { AndroidUserDictManager(context) }
    val updateService = remember { AndroidUpdateService() }

    var config by remember { mutableStateOf(AppSettings.load(context)) }
    var dictStats by remember { mutableStateOf(dictManager.getStats()) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<AppRelease?>(null) }
    val showUpdateDialog = remember { mutableStateOf(false) }
    val showClearConfirmDialog = remember { mutableStateOf(false) }
    val showAddKeyboardDialog = remember { mutableStateOf(false) }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val count = runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    dictManager.importDictionary(stream, uri.lastPathSegment ?: "dict")
                } ?: 0
            }.getOrDefault(0)

            withContext(Dispatchers.Main) {
                dictStats = dictManager.getStats()
                Toast.makeText(context, "成功导入 $count 条词库条目", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/tab-separated-values")
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    dictManager.exportUserDictionary(stream)
                }
                true
            }.getOrDefault(false)

            withContext(Dispatchers.Main) {
                if (ok) {
                    Toast.makeText(context, "用户词库导出成功", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "导出失败，请检查文件权限", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "WeType 多语言设置"
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header / Module Status Card
            item {
                StatusCard()
            }

            // Section 1: Keyboard Management (Gboard Style)
            item {
                SmallTitle(text = "键盘管理")
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "点击地球键 🌐 或按 Ctrl+空格 将按下表顺序循环切换：",
                    fontSize = 12.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
                )
                Card {
                    config.enabledKeyboardIds.forEachIndexed { index, kbId ->
                        val kbInfo = AvailableKeyboards.findById(kbId)
                            ?: KeyboardInfo(kbId, kbId, kbId, "标准键盘", "🌐")

                        KeyboardItemRow(
                            info = kbInfo,
                            canMoveUp = index > 0,
                            canMoveDown = index < config.enabledKeyboardIds.lastIndex,
                            canDelete = config.enabledKeyboardIds.size > 1,
                            onMoveUp = {
                                val list = config.enabledKeyboardIds.toMutableList()
                                val temp = list[index]
                                list[index] = list[index - 1]
                                list[index - 1] = temp
                                config = config.copy(enabledKeyboardIds = list)
                                AppSettings.save(context, config)
                            },
                            onMoveDown = {
                                val list = config.enabledKeyboardIds.toMutableList()
                                val temp = list[index]
                                list[index] = list[index + 1]
                                list[index + 1] = temp
                                config = config.copy(enabledKeyboardIds = list)
                                AppSettings.save(context, config)
                            },
                            onDelete = {
                                val list = config.enabledKeyboardIds.toMutableList()
                                list.removeAt(index)
                                config = config.copy(enabledKeyboardIds = list)
                                AppSettings.save(context, config)
                                Toast.makeText(context, "已移除 ${kbInfo.languageName}", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }

                    SuperArrow(
                        title = "+ 添加键盘",
                        summary = "浏览并启用更多语言与键盘布局",
                        onClick = {
                            showAddKeyboardDialog.value = true
                        }
                    )
                }
            }

            // Section 2: Japanese Specific Settings (Shown if any Japanese keyboard is enabled)
            val hasJapanese = config.enabledKeyboardIds.any { it.startsWith("ja") }
            if (hasJapanese) {
                item {
                    SmallTitle(text = "日语输入设置")
                    Spacer(modifier = Modifier.height(4.dp))
                    Card {
                        SuperSwitch(
                            title = "长音键 (-)",
                            summary = "在日语键盘布局的 Z 键左侧插入长音符「ー」按键",
                            checked = config.longVowelKeyEnabled,
                            onCheckedChange = { checked ->
                                config = config.copy(longVowelKeyEnabled = checked)
                                AppSettings.save(context, config)
                            }
                        )
                        SuperSwitch(
                            title = "智能 2-gram 预测",
                            summary = "根据前置词智能预测日常高频搭配与助词",
                            checked = config.smartPredictionEnabled,
                            onCheckedChange = { checked ->
                                config = config.copy(smartPredictionEnabled = checked)
                                AppSettings.save(context, config)
                            }
                        )
                        SuperSwitch(
                            title = "动态输入学习",
                            summary = "自动记忆用户上屏习惯与高频联想词条",
                            checked = config.learningEnabled,
                            onCheckedChange = { checked ->
                                config = config.copy(learningEnabled = checked)
                                AppSettings.save(context, config)
                            }
                        )
                    }
                }
            }

            // Section 3: Dictionary & Data Management
            item {
                SmallTitle(text = "词库与数据管理")
                Spacer(modifier = Modifier.height(4.dp))
                Card {
                    SuperArrow(
                        title = "词库统计",
                        summary = "内置核心词 ${dictStats.coreWordsCount} 条，自学习条目 ${dictStats.learnedBigramsCount} 条",
                        endActions = {
                            Text(text = "核心 12万+", fontSize = 14.sp, color = Color.Gray)
                        },
                        onClick = {
                            dictStats = dictManager.getStats()
                            Toast.makeText(context, "统计已刷新", Toast.LENGTH_SHORT).show()
                        }
                    )
                    SuperArrow(
                        title = "导入扩展词库",
                        summary = "支持选择 .tsv, .txt 或 .dict.yaml 格式的外部词库文件",
                        onClick = {
                            openDocumentLauncher.launch(arrayOf("*/*"))
                        }
                    )
                    SuperArrow(
                        title = "导出用户词库",
                        summary = "将自学习的高频词和二元搭配导出为 TSV 文件",
                        onClick = {
                            createDocumentLauncher.launch("wetype_user_dict.tsv")
                        }
                    )
                    SuperArrow(
                        title = "清空用户学习词库",
                        summary = "删除本地已积累的自学习词频与上下文搭配记录",
                        onClick = {
                            showClearConfirmDialog.value = true
                        }
                    )
                }
            }

            // Section 4: About & Update
            item {
                SmallTitle(text = "关于与更新")
                Spacer(modifier = Modifier.height(4.dp))
                Card {
                    SuperArrow(
                        title = "当前版本",
                        summary = "WeType MultiLanguage (Rime Core)",
                        endActions = {
                            Text(text = "v0.1.0", fontSize = 14.sp, color = Color.Gray)
                        }
                    )
                    SuperArrow(
                        title = "检查更新",
                        summary = if (isCheckingUpdate) "正在查询 GitHub Releases..." else "获取最新版安装包与功能更新",
                        onClick = {
                            if (!isCheckingUpdate) {
                                isCheckingUpdate = true
                                scope.launch(Dispatchers.IO) {
                                    val release = updateService.checkUpdate("v0.1.0")
                                    withContext(Dispatchers.Main) {
                                        isCheckingUpdate = false
                                        if (release != null) {
                                            updateResult = release
                                            showUpdateDialog.value = true
                                        } else {
                                            Toast.makeText(context, "当前已是最新版本！", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }
                        }
                    )
                    SuperArrow(
                        title = "开源主页",
                        summary = "GitHub: xiaojiang233/wetype-multilanguage",
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/xiaojiang233/wetype-multilanguage"))
                            context.startActivity(intent)
                        }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // Dialog for adding keyboards (Gboard style picker)
    SuperDialog(
        show = showAddKeyboardDialog,
        title = "添加键盘",
        renderInRootScaffold = false,
        onDismissRequest = { showAddKeyboardDialog.value = false },
        content = {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "选择要启用的语言或键盘布局：",
                    fontSize = 13.sp,
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.height(12.dp))

                AvailableKeyboards.ALL.forEach { kb ->
                    val isAlreadyAdded = config.enabledKeyboardIds.contains(kb.id)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !isAlreadyAdded) {
                                val list = config.enabledKeyboardIds.toMutableList()
                                list.add(kb.id)
                                config = config.copy(enabledKeyboardIds = list)
                                AppSettings.save(context, config)
                                showAddKeyboardDialog.value = false
                                Toast.makeText(context, "已添加 ${kb.languageName} 键盘", Toast.LENGTH_SHORT).show()
                            }
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(if (isAlreadyAdded) Color.LightGray.copy(alpha = 0.5f) else Color(0xFF07C160).copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = kb.shortLabel,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isAlreadyAdded) Color.Gray else Color(0xFF07C160)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = kb.languageName,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isAlreadyAdded) Color.Gray else Color.Unspecified
                            )
                            Text(
                                text = kb.layoutName,
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }

                        Text(
                            text = if (isAlreadyAdded) "已添加" else "+ 添加",
                            fontSize = 13.sp,
                            color = if (isAlreadyAdded) Color.Gray else Color(0xFF07C160),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(onClick = { showAddKeyboardDialog.value = false }) {
                        Text("关闭")
                    }
                }
            }
        }
    )

    // Dialog for clearing user dictionary
    SuperDialog(
        show = showClearConfirmDialog,
        title = "清空学习词库",
        renderInRootScaffold = false,
        onDismissRequest = { showClearConfirmDialog.value = false },
        content = {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("确定要清空所有已学习的用户词汇与搭配数据吗？此操作无法撤销。")
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(onClick = { showClearConfirmDialog.value = false }) {
                        Text("取消")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            dictManager.clearUserLearning()
                            dictStats = dictManager.getStats()
                            showClearConfirmDialog.value = false
                            Toast.makeText(context, "学习词库已清空", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text("清空")
                    }
                }
            }
        }
    )

    // Dialog for update release details
    if (updateResult != null) {
        val rel = updateResult!!
        SuperDialog(
            show = showUpdateDialog,
            title = "发现新版本 ${rel.tagName}",
            renderInRootScaffold = false,
            onDismissRequest = { showUpdateDialog.value = false },
            content = {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = rel.name.ifBlank { rel.tagName },
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = rel.body.ifBlank { "无详细发布说明" },
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(onClick = { showUpdateDialog.value = false }) {
                            Text("稍后")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val targetUrl = rel.downloadUrl ?: rel.htmlUrl
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl))
                                context.startActivity(intent)
                                showUpdateDialog.value = false
                            }
                        ) {
                            Text("前往下载")
                        }
                    }
                }
            }
        )
    }
}

@Composable
fun KeyboardItemRow(
    info: KeyboardInfo,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canDelete: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Language Badge
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color(0xFF07C160).copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = info.shortLabel,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF07C160)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        // Titles
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = info.languageName,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = info.layoutName,
                fontSize = 12.sp,
                color = Color.Gray
            )
        }

        // Action Buttons: Up, Down, Remove
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Move Up Button
            Text(
                text = "▲",
                fontSize = 14.sp,
                color = if (canMoveUp) Color(0xFF07C160) else Color.LightGray,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(enabled = canMoveUp, onClick = onMoveUp)
                    .padding(8.dp)
            )

            // Move Down Button
            Text(
                text = "▼",
                fontSize = 14.sp,
                color = if (canMoveDown) Color(0xFF07C160) else Color.LightGray,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(enabled = canMoveDown, onClick = onMoveDown)
                    .padding(8.dp)
            )

            Spacer(modifier = Modifier.width(4.dp))

            // Delete Button
            Text(
                text = "✕",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = if (canDelete) Color(0xFFE53935) else Color.LightGray,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(enabled = canDelete, onClick = onDelete)
                    .padding(8.dp)
            )
        }
    }
}

@Composable
fun StatusCard() {
    val isActive = XposedCompat.xposed != null
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF07C160)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground),
                    contentDescription = "App Logo",
                    modifier = Modifier.size(54.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "WeType MultiLanguage",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "多语言输入扩展模块",
                    fontSize = 13.sp,
                    color = Color.Gray
                )
            }

            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (isActive) Color(0xFF07C160).copy(alpha = 0.15f) else Color(0xFFFFA500).copy(alpha = 0.15f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (isActive) "已激活" else "未生效",
                    color = if (isActive) Color(0xFF07C160) else Color(0xFFE65100),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
