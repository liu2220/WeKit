package dev.ujhhgtg.wekit.features.items.chat_input_bar_menu

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Format_list_numbered
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.agent.model.LlmMessage
import dev.ujhhgtg.wekit.agent.model.LlmRequest
import dev.ujhhgtg.wekit.agent.model.LlmRole
import dev.ujhhgtg.wekit.agent.model.LlmStreamEvent
import dev.ujhhgtg.wekit.agent.model.OpenAiChatCompletionsClient
import dev.ujhhgtg.wekit.data.KvStore
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.models.WeMessage
import dev.ujhhgtg.wekit.features.api.ui.WeChatInputBarMenuApi
import dev.ujhhgtg.wekit.features.api.ui.WeCurrentConversationApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.runOnUiThread
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.strings.isGroupChatWxId
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 群聊总结。
 *
 * 移植自 fkwechat 的群总结功能（me.yun.fkwechat，源码恢复工程
 * liu2220/FkWeChat-1.2.6-source 的 `feature/share/SummaryShareManager` +
 * `sd.t5` AI 调用逻辑），改为完全基于 WeKit 既有 API 实现，不依赖微信
 * 内部易变特征（类名/日志 tag），因此跨微信版本更稳：
 *
 * - 入口：群聊输入框「+」菜单新增「群聊总结」按钮（[WeChatInputBarMenuApi]，
 *   与 @所有人 同一机制）；
 * - 取材：[WeDatabaseApi.getMessages] 直接读微信消息库最近 N 条文本消息，
 *   群消息 content 的 `wxid:` 前缀还原发送者显示名；
 * - 生成：调用用户配置的 OpenAI 兼容 Chat Completions 接口
 *   （[OpenAiChatCompletionsClient]，兼容 OpenAI/DeepSeek/本地服务等），
 *   与 fkwechat 的 `ai_api_base_url` / `ai_api_path` / `ai_api_key` 配置语义一致；
 * - 分享：复制全文 / 渲染长图发给微信好友群聊 / 朋友圈（[SummaryShareManager]）。
 *
 * 长按菜单项可配置 API 地址、Key、模型名与读取条数。默认关闭，需在
 * WeKit 设置中手动开启。
 */
object SummarizeGroupChat : SwitchFeature() {

    override val technicalId = "群聊总结"
    override val nameRes = R.string.feature_summarize_group_chat_name
    override val descriptionRes = R.string.feature_summarize_group_chat_description
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)

    // 测试版默认开启，省去在 WeKit 设置里手动打开的步骤。
    override val defaultEnabled = true

    // ---- 用户配置（KvStore 持久化；语义对齐 fkwechat 的 ai_api_* 配置）----
    private var apiBaseUrl by KvStore.prefOption(
        "summarize_gc_api_base_url",
        "https://api.openai.com/v1",
    )
    private var apiKey by KvStore.prefOption("summarize_gc_api_key", "")
    private var modelName by KvStore.prefOption("summarize_gc_model", "gpt-4o-mini")
    private var messageCount by KvStore.prefOption("summarize_gc_msg_count", 100)

    /** 共享 Ktor CIO 客户端，配置与 ModelProviderManager 的单例一致。 */
    private val httpClient by lazy {
        HttpClient(CIO) {
            install(UserAgent) { agent = "WeKit/SummarizeGroupChat" }
            install(HttpTimeout) {
                requestTimeoutMillis = 600_000
                socketTimeoutMillis = 600_000
                connectTimeoutMillis = 30_000
            }
        }
    }

    private val provider = WeChatInputBarMenuApi.IActionItemsProvider {
        listOf(
            WeChatInputBarMenuApi.ActionItem(
                id = "summarize_group_chat",
                icon = MaterialSymbols.Outlined.Format_list_numbered,
                label = localizedChatInputString(R.string.summarize_group_chat_action_label),
                isSupported = { _, _ -> WeCurrentConversationApi.value.isGroupChatWxId },
                onClick = { context, _ ->
                    val conv = WeCurrentConversationApi.value
                    if (!conv.isGroupChatWxId) {
                        showToast(
                            context,
                            context.localizedChatInputString(R.string.summarize_group_chat_group_only),
                        )
                        return@ActionItem
                    }
                    showSummaryDialog(context, conv)
                },
                onLongClick = { context, _ ->
                    runOnUiThread { showSettingsDialog(context) }
                },
            )
        )
    }

    override fun onEnable() {
        WeChatInputBarMenuApi.addProvider(provider)
    }

    override fun onDisable() {
        WeChatInputBarMenuApi.removeProvider(provider)
    }

    // -------------------------------------------------------------------------
    // 消息读取与提示词
    // -------------------------------------------------------------------------

    /**
     * 读取当前群聊最近 [count] 条消息并拼装为「发送者: 内容」的转写文本。
     * 只取纯文本（type=1）；群消息 content 以 `wxid:` 前缀标识发送者。
     */
    private fun buildTranscript(conv: String, count: Int): String {
        val messages = WeDatabaseApi.getMessages(conv, 1, count).filter { it.typeCode == 1 }
        if (messages.isEmpty()) return ""
        val groupName = WeDatabaseApi.getDisplayName(conv).takeIf { it.isNotEmpty() } ?: conv
        val sb = StringBuilder()
        sb.append("群聊「").append(groupName).append("」最近 ").append(messages.size)
            .append(" 条消息（按时间正序）：\n\n")
        for (msg in messages.asReversed()) {
            sb.append(senderLabel(msg)).append(": ").append(stripSenderPrefix(msg.content)).append('\n')
        }
        return sb.toString()
    }

    /** 消息发送者显示名：自己固定「我」；他人从 content 前缀的 wxid 查昵称。 */
    private fun senderLabel(msg: WeMessage): String {
        if (msg.isSend == 1) return "我"
        val wxId = extractSenderWxId(msg.content) ?: return "成员"
        return WeDatabaseApi.getDisplayName(wxId).takeIf { it.isNotEmpty() } ?: wxId
    }

    /** 群消息 content 形如 `wxid_xxx:正文`；识别并剥离该前缀。 */
    private fun extractSenderWxId(content: String): String? {
        val idx = content.indexOf(':')
        if (idx !in 1..64) return null
        val maybeWxId = content.substring(0, idx)
        if (maybeWxId.startsWith("wxid_") || maybeWxId.contains('@') ||
            maybeWxId.matches(Regex("^[A-Za-z][A-Za-z0-9_-]{4,}$"))
        ) {
            return maybeWxId
        }
        return null
    }

    private fun stripSenderPrefix(content: String): String {
        val wxId = extractSenderWxId(content) ?: return content
        return content.substring(content.indexOf(':') + 1)
    }

    // -------------------------------------------------------------------------
    // 总结对话框
    // -------------------------------------------------------------------------

    private fun showSummaryDialog(context: Context, conv: String) {
        showComposeDialog(context) {
            var summary by remember { mutableStateOf("") }
            var isLoading by remember { mutableStateOf(true) }
            var errorText by remember { mutableStateOf<String?>(null) }

            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val key = apiKey
                        if (key.isBlank()) {
                            errorText = context.localizedChatInputString(R.string.summarize_group_chat_no_key)
                            isLoading = false
                            return@withContext
                        }
                        val transcript = buildTranscript(conv, messageCount)
                        if (transcript.isBlank()) {
                            errorText = context.localizedChatInputString(R.string.summarize_group_chat_empty)
                            isLoading = false
                            return@withContext
                        }
                        val client = OpenAiChatCompletionsClient(
                            httpClient,
                            apiBaseUrl.trimEnd('/'),
                            key,
                        )
                        val request = LlmRequest(
                            modelIdRemote = modelName.ifBlank { "gpt-4o-mini" },
                            sessionId = UUID.randomUUID().toString(),
                            stream = true,
                            maxTokens = 2000,
                            messages = listOf(
                                LlmMessage(LlmRole.SYSTEM, SYSTEM_PROMPT),
                                LlmMessage(LlmRole.USER, transcript),
                            ),
                        )
                        val buf = StringBuilder()
                        client.stream(request)
                            .onEach { event ->
                                when (event) {
                                    is LlmStreamEvent.TextDelta -> {
                                        buf.append(event.text)
                                        summary = buf.toString() // 边生成边渲染
                                    }
                                    is LlmStreamEvent.ReasoningDelta -> Unit
                                    is LlmStreamEvent.Completed -> Unit
                                    is LlmStreamEvent.Failed -> throw event.error
                                }
                            }
                            .collect()
                        summary = buf.toString().ifBlank {
                            context.localizedChatInputString(R.string.summarize_group_chat_empty_result)
                        }
                        isLoading = false
                    }.onFailure { e ->
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        WeLogger.e(TAG, "summarize group chat failed", e)
                        errorText = e.message?.takeIf { it.isNotBlank() }
                            ?: e.javaClass.simpleName
                        isLoading = false
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = localizedChatInputString(R.string.summarize_group_chat_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                when {
                    errorText != null -> {
                        Text(
                            text = errorText!!,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = { onDismiss() },
                            modifier = Modifier.padding(top = 12.dp),
                        ) {
                            Text(localizedChatInputString(R.string.dialog_close))
                        }
                    }

                    summary.isNotEmpty() -> {
                        Text(text = summary, modifier = Modifier.fillMaxWidth())
                        if (!isLoading) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(onClick = { SummaryShareManager.copyToClipboard(context, summary) }) {
                                    Text(localizedChatInputString(R.string.summarize_group_chat_copy))
                                }
                                Button(onClick = { SummaryShareManager.shareImageToWeChatContacts(context, summary) }) {
                                    Text(localizedChatInputString(R.string.summarize_group_chat_share))
                                }
                                Button(onClick = { SummaryShareManager.shareImageToMoments(context, summary) }) {
                                    Text(localizedChatInputString(R.string.summarize_group_chat_moments))
                                }
                            }
                        }
                    }

                    else -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator()
                        Text(
                            text = localizedChatInputString(R.string.summarize_group_chat_loading),
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // 设置对话框
    // -------------------------------------------------------------------------

    private fun showSettingsDialog(context: Context) {
        showComposeDialog(context) {
            var baseUrl by remember { mutableStateOf(apiBaseUrl) }
            var key by remember { mutableStateOf(apiKey) }
            var model by remember { mutableStateOf(modelName) }
            var countText by remember { mutableStateOf(messageCount.toString()) }

            AlertDialogContent(
                title = { Text(localizedChatInputString(R.string.summarize_group_chat_settings_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingTextField(
                            label = localizedChatInputString(R.string.summarize_group_chat_set_base_url),
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                        )
                        SettingTextField(
                            label = localizedChatInputString(R.string.summarize_group_chat_set_key),
                            value = key,
                            onValueChange = { key = it },
                        )
                        SettingTextField(
                            label = localizedChatInputString(R.string.summarize_group_chat_set_model),
                            value = model,
                            onValueChange = { model = it },
                        )
                        SettingTextField(
                            label = localizedChatInputString(R.string.summarize_group_chat_set_count),
                            value = countText,
                            onValueChange = { countText = it },
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        apiBaseUrl = baseUrl.trim().ifBlank { "https://api.openai.com/v1" }
                        apiKey = key.trim()
                        modelName = model.trim().ifBlank { "gpt-4o-mini" }
                        messageCount = countText.trim().toIntOrNull()?.coerceIn(10, 500) ?: 100
                        onDismiss()
                    }) {
                        Text(localizedChatInputString(R.string.dialog_save))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text(localizedChatInputString(R.string.dialog_close))
                    }
                },
            )
        }
    }

    @Composable
    private fun SettingTextField(
        label: String,
        value: String,
        onValueChange: (String) -> Unit,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    private const val TAG = "SummarizeGroupChat"

    private const val SYSTEM_PROMPT =
        "你是一个微信聊天记录总结助手。请阅读用户给出的群聊记录，输出一份结构化的中文总结：" +
            "先用一句话概括核心结论，再按要点列出关键人物与事件（每条尽量点名相关成员），" +
            "最后单独列出待办/未决事项（如没有则省略）。保持客观，不要编造记录中未提及的信息，" +
            "不要添加主观评价。"
}
