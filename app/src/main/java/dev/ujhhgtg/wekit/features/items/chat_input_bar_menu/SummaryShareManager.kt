package dev.ujhhgtg.wekit.features.items.chat_input_bar_menu

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Environment
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

/**
 * 群总结结果分享管理器（图片版）。
 *
 * 移植自 fkwechat 的 [me.yun.fkwechat.feature.share.SummaryShareManager]，
 * 原实现以 Zygisk/Xposed 方式寄生在微信进程内，直接用标准 Android [Intent] 拉起
 * 微信自身的分享/发送界面。本版本保持同样行为：把总结文本渲染成长图后用系统分享面板
 * 发给微信好友/群聊，或直接拉起朋友圈编辑界面。
 *
 * 图片写入微信自身外部存储目录（getExternalFilesDir），并以同进程 UID 共享给微信，
 * 因此可用 file:// Uri 直接分享，无需 FileProvider。
 */
object SummaryShareManager {

    private const val WECHAT_PACKAGE: String = "com.tencent.mm"
    private const val MOMENTS_ACTIVITY: String = "com.tencent.mm.ui.tools.ShareToTimeLineUI"
    private const val DEFAULT_TITLE: String = "群聊总结"

    /** 复制总结全文到系统剪贴板（兜底工具，不用于图片分享）。 */
    fun copyToClipboard(context: Context, text: String, label: String = DEFAULT_TITLE) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (cm == null) {
            Toast.makeText(context, "无法访问剪贴板", Toast.LENGTH_SHORT).show()
            return
        }
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "已复制总结全文", Toast.LENGTH_SHORT).show()
    }

    /**
     * 把总结渲染成长图并发给微信好友/群聊。
     * 用户在微信的联系人/群列表中选定目标后发送，因此同时覆盖「好友」与「群聊」。
     */
    fun shareImageToWeChatContacts(context: Context, summary: String, title: String = DEFAULT_TITLE) {
        val uri = renderAndSave(context, summary, title) ?: run {
            Toast.makeText(context, "生成图片失败", Toast.LENGTH_SHORT).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, title)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(send, "分享群总结图片到微信").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        safeStart(context, chooser)
    }

    /**
     * 把总结长图直接分享到朋友圈（预填图片 + 文案，用户仍需点发送）。
     * 该 Intent 在不同微信版本/非微信环境下可能不可用，故失败会回退到系统分享面板。
     */
    fun shareImageToMoments(context: Context, summary: String, title: String = DEFAULT_TITLE) {
        val uri = renderAndSave(context, summary, title) ?: run {
            Toast.makeText(context, "生成图片失败", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent().apply {
            setClassName(WECHAT_PACKAGE, MOMENTS_ACTIVITY)
            setDataAndType(uri, "image/*")
            putExtra("android.intent.extra.STREAM", uri)
            putExtra("android.intent.extra.TEXT", title)
            putExtra("Kdescription", summary)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        safeStart(context, intent)
    }

    // -------------------------------------------------------------------------
    // 内部：文本 → 卡片长图 → 文件 Uri
    // -------------------------------------------------------------------------

    /** 将总结渲染为卡片长图并保存到微信外部存储，返回可被微信读取的 file:// Uri。 */
    private fun renderAndSave(context: Context, summary: String, title: String): android.net.Uri? {
        return try {
            val bitmap = renderSummaryCard(context, title, summary)
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                ?: context.filesDir
            val folder = File(dir, "WeKit").apply { mkdirs() }
            val file = File(folder, "summary_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            bitmap.recycle()
            file.setReadable(true, false)
            android.net.Uri.fromFile(file)
        } catch (_: Throwable) {
            null
        }
    }

    /** 在 Canvas 上绘制一张总结卡片长图（标题 + 换行正文 + 页脚）。 */
    private fun renderSummaryCard(context: Context, title: String, body: String): Bitmap {
        val density = context.resources.displayMetrics.density
        val cardWidthDp = 380
        val cardWidth = (cardWidthDp * density).toInt()
        val padding = (24 * density).toInt()

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 22 * density
            color = Color.parseColor("#1A1A1A")
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 16 * density
            color = Color.parseColor("#333333")
        }
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 12 * density
            color = Color.parseColor("#999999")
        }

        val innerWidth = cardWidth - padding * 2
        val lineHeight = (bodyPaint.textSize + 10 * density).toInt()
        val titleLineHeight = (titlePaint.textSize + 12 * density).toInt()
        val footerLineHeight = (footerPaint.textSize + 16 * density).toInt()

        val lines = mutableListOf<Pair<String, Paint>>()
        lines.add(title to titlePaint)
        lines.add("" to bodyPaint) // 标题与正文之间的留白
        for (paragraph in body.split("\n")) {
            if (paragraph.isEmpty()) {
                lines.add("" to bodyPaint)
                continue
            }
            wrapText(bodyPaint, paragraph, innerWidth).forEach { lines.add(it to bodyPaint) }
        }
        lines.add("" to footerPaint) // 留白
        lines.add("— WeKit 群总结 —" to footerPaint)

        val contentHeight = lines.size * lineHeight + titleLineHeight
        val height = padding * 2 + contentHeight + footerLineHeight

        val bitmap = Bitmap.createBitmap(cardWidth, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.parseColor("#FAFAFA"))

        var y = padding + titleLineHeight
        for ((text, paint) in lines) {
            if (text.isNotEmpty()) {
                canvas.drawText(text, padding.toFloat(), y.toFloat(), paint)
            }
            y += if (paint === titlePaint) titleLineHeight else lineHeight
        }
        return bitmap
    }

    /** 贪心换行：按字符测量，超过最大宽度即换行（对中文友好）。 */
    private fun wrapText(paint: Paint, text: String, maxWidth: Int): List<String> {
        val lines = mutableListOf<String>()
        val line = StringBuilder()
        var lineWidth = 0f
        for (ch in text) {
            val w = paint.measureText(ch.toString())
            if (lineWidth + w > maxWidth && line.isNotEmpty()) {
                lines.add(line.toString())
                line.setLength(0)
                lineWidth = 0f
            }
            line.append(ch)
            lineWidth += w
        }
        if (line.isNotEmpty()) lines.add(line.toString())
        return lines
    }

    /** 统一启动并捕获异常，回退到系统分享面板（兜底）。 */
    private fun safeStart(context: Context, intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (_: Throwable) {
            try {
                context.startActivity(
                    Intent.createChooser(Intent(Intent.ACTION_SEND), "分享群总结")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Throwable) {
                Toast.makeText(context, "未找到可用的分享目标", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
