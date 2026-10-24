// ---------------------------------------------------------------------
// Copyright (c) 2026 Qualcomm Technologies, Inc. and/or its subsidiaries.
// SPDX-License-Identifier: BSD-3-Clause
// ---------------------------------------------------------------------
package com.geniex.demo

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.SimpleAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import com.geniex.demo.bean.ModelData
import com.geniex.demo.bean.getSupportPluginIds
import com.geniex.demo.bean.isNpuModel
import com.geniex.demo.databinding.ActivityMainBinding
import com.geniex.demo.databinding.DialogSelectPluginIdBinding
import com.geniex.demo.listeners.CustomDialogInterface
import com.geniex.demo.utils.ExecShell
import com.geniex.demo.utils.GgufVisionConfig
import com.geniex.demo.utils.GgufVisionReader
import com.geniex.demo.utils.ImgUtil
import com.geniex.demo.utils.inflate
import com.geniex.sdk.GenieXSdk
import com.geniex.sdk.LlmWrapper
import com.geniex.sdk.ModelManagerWrapper
import com.geniex.sdk.VlmWrapper
import com.geniex.sdk.bean.ChatMessage
import com.geniex.sdk.bean.ComputeUnitValue
import com.geniex.sdk.bean.HubSource
import com.geniex.sdk.bean.LlmCreateInput
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.ModelPullInput
import com.geniex.sdk.bean.VlmChatMessage
import com.geniex.sdk.bean.VlmContent
import com.geniex.sdk.bean.VlmCreateInput
import com.gyf.immersionbar.ktx.immersionBar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.Locale

class MainActivity : FragmentActivity() {
    private val binding: ActivityMainBinding by inflate()
    private var downloadJob: Job? = null
    private var downloadingModelData: ModelData? = null
    private lateinit var llDownloading: LinearLayout
    private lateinit var tvDownloadProgress: TextView
    private lateinit var flDownloadTrack: android.widget.FrameLayout
    private lateinit var vDownloadFill: View
    private var maxPercent = 0
    private var lastProgressTs = 0L
    private var lastUiTs = 0L
    private var lastUiDone = 0L
    private var dlStartTs = 0L
    private var dlStartDone = 0L
    private var dlBaselineSet = false
    private val rawStreamSb = StringBuilder()
    private var streamingText = ""
    private var streamingSaved = false
    private var streamStartTs = 0L
    private var lastStreamUiTs = 0L
    private var pinBottom = true

    /** True while we scroll on purpose, so onScrolled() won't fight the auto-stick. */
    private var programmaticScroll = false
    private var useContext = true
    private var kbEnabled = false

    /** 最近一次提问命中的知识库条目，回答完成后渲染成「参考资料」行 */
    private var pendingRefs: List<Pair<String, String>> = emptyList()
    private var userTouchingTs = 0L
    private lateinit var spModelList: Spinner
    private lateinit var btnDownload: Button
    private lateinit var btnLoadModel: Button
    private lateinit var btnUnloadModel: Button
    private lateinit var btnStop: Button
    private lateinit var etInput: EditText
    private lateinit var btnSend: Button
    private lateinit var btnAddImage: Button

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ChatAdapter

    private lateinit var scrollImages: HorizontalScrollView
    private lateinit var topScrollContainer: LinearLayout
    private lateinit var llLoading: LinearLayout
    private lateinit var vTip: View

    private lateinit var llmWrapper: LlmWrapper
    private lateinit var vlmWrapper: VlmWrapper
    private val modelScope = CoroutineScope(Dispatchers.IO)

    private val chatList = arrayListOf<ChatMessage>()
    private val vlmChatList = arrayListOf<VlmChatMessage>()
    private lateinit var modelList: List<ModelData>
    private var selectModelId = ""

    private var isLoadLlmModel = false
    private var isLoadVlmModel = false

    /**
     * Vision geometry of the currently loaded VLM, read from its mmproj GGUF.
     * Null for LLM-only models, and when the mmproj declares nothing usable 闂?     * image preprocessing then falls back to [FALLBACK_VLM_IMAGE_SIZE].
     */
    private var vlmVisionConfig: GgufVisionConfig? = null

    private var enableThinking = false
    private var isGenerating = false

    private val savedImageFiles = mutableListOf<File>()
    private val messages = arrayListOf<Message>()

    private val savedTurns = arrayListOf<SavedMessage>()

    private var pendingRestore = true

    private val sessions = arrayListOf<ChatSession>()
    private var currentSessionId = ""
    private lateinit var tvModelChip: TextView
    private lateinit var tvTokenCount: TextView
    private var maxOutputTokens = 2048
    private lateinit var lvSessions: ListView
    private var loadingMessageIndex: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        immersionBar {
            statusBarColorInt(Color.WHITE)
            statusBarDarkFont(true)
        }
        initData()
        initView()
        modelScope.launch {
            // 闂傚倸鍊搁崐椋庣矆娓氣偓楠炴牠顢曢敃鈧懜褰掓煛鐏炶鍔氱紒鈧€ｎ偁浜滈柟鐑樺灥閳ь剛鏁诲畷鎴﹀箻濞茬粯鏅╁┑鐐存綑妤犲摜绱炴繝鍥х畺闁冲搫鎳忛崐缁樹繆椤栨粠鍎犻柍褜鍓氶崝娆忣潖閾忚瀚氶柍銉ㄦ珪閻忓秴鈹戦悙璺虹毢闁哥姴閰ｉ幃鎯х暋閹锋梹妫冨畷銊╊敊闂傚鏆楅梻浣烘嚀閸氬骞嗗畝鍕獥闁哄稁鍘奸悿楣冩煥濠靛棭妲归柍閿嬪灴閺岀喓绮欓幐搴㈠闯闂佹剚鍨卞ú婊堟儉椤忓牆绾ч悹鎭掑壉閿濆惓?zhwiki.zim 闂傚倸鍊峰ù鍥х暦閻㈢绐楅柟鎵閸嬶繝鏌曟径鍫濆壔婵炴垯鍨圭猾宥夋煕鐏炲墽鐭屾い?zh_top_nopic
            val legacy = java.io.File(filesDir, "kb/zhwiki.zim")
            val modern = java.io.File(filesDir, "kb/zh_top_nopic.zim")
            if (legacy.exists() && !modern.exists()) legacy.renameTo(modern)
            kbEnabled = KbManager.openActive(this@MainActivity)
            Log.i(TAG, "KB ready=$kbEnabled")
        }
        setListeners()
        binding.root.postDelayed({ if (pendingRestore) restoreHistory() }, 400)
    }

    private val memTicker =
        object : Runnable {
            override fun run() {
                updateMemoryBar()
                binding.llMemory.postDelayed(this, 3000)
            }
        }
    override fun onResume() {
        super.onResume()
        binding.llMemory.removeCallbacks(memTicker)
        binding.llMemory.post(memTicker)
        updateDeviceInfo()

    }

    override fun onPause() {
        super.onPause()
        runCatching { binding.llMemory.removeCallbacks(memTicker) }
        if (isGenerating && streamingText.isNotBlank() && !streamingSaved) {
            recordTurn("assistant", streamingText)
            streamingSaved = true
            modelScope.launch {
                runCatching {
                    if (isLoadVlmModel) vlmWrapper.stopStream() else if (isLoadLlmModel) llmWrapper.stopStream()
                }
            }
        }
        persistHistory()
    }

    /** 闂傚倸鍊峰ù鍥х暦閸偅鍙忛柟鎯板Г閸婂潡鏌ㄩ弴妤€浜惧銈庡幖閻忔繆鐏掗梺鍏肩ゴ閺呮繈藝椤栫偞鍊垫鐐茬仢閸旀碍淇婇锝囨创闁糕斂鍨藉顕€宕奸悢鍝勫笚闁荤喐绮嶇划鎾崇暦濠婂喚娼╂い鎺戭槺閸斿嘲顪冮妶鍛闁绘绮撳顐ｇ節閸曨厽锛忛梺鍝勵槸閻忔繈鎳滅憴鍕闁割偆鍠愰崐鎰版煛瀹€瀣М闁诡喒鏅犲畷锝嗗緞婵犲孩袩缂傚倸鍊峰ù鍥敋瑜斿畷鎰板锤濡も偓缁犵娀鏌ｉ弬鍨倯闁稿瀚伴弻锝夊箻閾忣偅宕冲銈忚礋閸斿矂鍩為幋锔藉€烽柛娆忣樈濡偟绱撴担铏瑰笡闁告梹娲熼、姘舵晲閸涱垱娈鹃梺鎼炲劘閸斿矂寮搁幋锔解拺缂備焦锕╁▓鏃堟煟濡も偓閿曪附绂嶉幖渚囨晣闁绘垵妫涢崬鐢告偡濠婂啰绠查柟渚垮姂閹晫鍠婂Ο杞板闂佹眹鍨藉褏鏁懜鐐逛簻妞ゆ劧绲跨粻鎾绘煟閿濆洤鍘寸€规洖鐖兼俊鎼佸閿涘嫪绨?*/
    private fun recordTurn(
        role: String,
        content: String,
    ) {
        if (content.isBlank()) return
        savedTurns.add(SavedMessage(role, content))
        saveCurrentSession()
    }

    private fun persistHistory() {
        saveCurrentSession()
    }

    // ---------------- 婵犵數濮烽弫鍛婃叏娴兼潙鍨傚┑鍌滎焾閺勩儵鏌″搴″箺闁稿孩顨嗛妵鍕籍閸屾矮澹曞銈庡亝濞茬喖寮婚悢灏佹灁闁割煈鍠楅悘宥夋煟鎼淬垻顣茬痪鏉跨Ф閹广垹鈹戠€ｎ偒妫冨┑鐐村灦閼归箖鍩涢崼銉︹拺闁告稑锕ら悞褰掓煕鐎ｎ偅宕屾慨?----------------

    private fun updateModelChip() {
        val name = modelList.firstOrNull { it.id == selectModelId }?.displayName ?: "未选择模型"
        tvModelChip.text = name.replace(" (GGUF)", "").replace(" (NPU)", "")
    }

    private fun refreshSessionList() {
        runOnUiThread {
            val adapter = lvSessions.adapter as? SessionAdapter ?: return@runOnUiThread
            adapter.setData(sessions.filter { it.turns.isNotEmpty() }.sortedByDescending { it.updatedAt })
            val title = sessions.firstOrNull { it.id == currentSessionId }?.title ?: "\u65B0\u5BF9\u8BDD"
            binding.tvSessionTitle.text = title
        }
    }

    /** 闂傚倸鍊搁崐椋庣矆娓氣偓楠炲鏁嶉崟顒佹闂佺粯鍔曢顓犵不妤ｅ啯鐓冪憸婊堝礈濮樿鲸宕叉繛鎴欏灩瀹告繃銇勯幘璺哄壉闁告柨顦辩槐鎾存媴閸撴彃鍓板銈忓閺佽鐣峰ú顏勭劦妞ゆ帊闄嶆禍婊堟煙閻戞ê鐏ユい蹇婃櫇缁辨帡鎮▎蹇斿闁绘挸鍟撮弻鏇熷緞閸繂濮庨梺鍝勬娴滃爼寮诲☉婊庢Щ闂佹寧娲︽禍顏勵嚕鐠囨祴妲堟俊顖炴敱閻庡姊洪崷顓炲妺闁搞劏顫夊鍕礋椤撶姷锛濇繛杈剧导缁瑩宕崫鍕电唵闁荤喓澧楅ˉ銏☆殽?*/
    private fun onDeleteSession(session: ChatSession) {
        AlertDialog
            .Builder(this)
            .setMessage("\u5220\u9664\u4F1A\u8BDD\u300C${session.title}\u300D\uFF1F")
            .setPositiveButton("\u5220\u9664") { _, _ ->
                sessions.removeAll { it.id == session.id }
                SessionStore.save(this, sessions)
                if (session.id == currentSessionId) {
                    savedTurns.clear()
                    messages.clear()
                    chatList.clear()
                    currentSessionId = ""
                    startNewSession()
                } else {
                    refreshSessionList()
                }
            }
            .setNegativeButton("\u53D6\u6D88", null)
            .show()
    }

    /** 婵?profile 闂傚倸鍊搁崐椋庣矆娓氣偓楠炴牠顢曢敃鈧壕鍦磼鐎ｎ偓绱╂繛宸簼閺呮繈鏌嶈閸撶喖寮崘顔碱潊闁炽儲鍓氶崵銈夋煟鎼淬垻鈯曟い顓炴喘瀹曘垽骞掑Δ浣糕偓鐢告煕韫囨搩妲稿ù婊堢畺濮婃椽宕ㄦ繝鍌氼潓闂佸摜鍣ラ崹鎶藉极椤曗偓閸ㄩ箖骞囨担鍛婎吙闂備礁缍婂Λ璺ㄧ矆娴ｈ櫣涓嶆繛鎴炵懀娴滄粓鏌熼幑鎰【濞寸姍鍕箚妞ゆ牗鑹鹃顓熸叏?*/
    private fun parseProfile(content: String): TurnStat? {
        fun num(key: String): Int =
            Regex("$key: (\\d+)").find(content)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        fun str(key: String): String =
            Regex("$key: ([\\d.]+)").find(content)?.groupValues?.get(1) ?: "-"
        val model =
            runCatching {
                Regex(Regex.escape(PROFILE_MODEL_PREFIX) + "(.+)")
                    .find(content)?.groupValues?.get(1)?.trim()
            }.getOrNull() ?: "-"
        return TurnStat(num("Prompt Tokens"), num("Generated Tokens"), str("TTFT"), str("Prefilling Speed"), str("Decoding Speed"), model)
    }

    private fun profileStats(): List<TurnStat> =
        savedTurns.toList().filter { it.role == "profile" }.mapNotNull { parseProfile(it.content) }

    /** 闂傚倸鍊搁崐椋庣矆娓氣偓楠炴牠顢曢埛姘そ婵¤埖寰勭€ｎ亙妲愰梻渚€娼ц墝闁哄懏鐩幏鎴︽偄鐏忎焦鏂€闂佺粯蓱瑜板啴寮抽悙鐑樼厱閹艰揪绲介弸娑㈡煛瀹€瀣瘈鐎规洖鐖兼俊鐑藉Ψ瑜岄幃锝夋⒒娴ｅ憡鍟為柡宀嬬秮瀹曟洟鎮界粙鑳憰?token 缂傚倸鍊搁崐鎼佸磹閹间礁纾归柣鎴ｅГ閸ゅ嫰鏌涢锝嗙缂佹劖顨婇弻锟犲炊閵夈儳鍔撮梺?*/
    private fun refreshTokenStats() {
        val stats = profileStats()
        val total = stats.sumOf { it.generated } + (stats.lastOrNull()?.prompt ?: 0)
        runOnUiThread {
            tvTokenCount.text =
                if (total >= 1000) {
                    String.format(Locale.US, "%.1fk tok", total / 1000.0)
                } else {
                    "$total tok"
                }
        }
    }

    private var tokenPopup: android.widget.PopupWindow? = null

    /** 闂?token 闂傚倸鍊搁崐椋庣矆娴ｉ潻鑰块梺顒€绉埀顒婄畵瀹曞ジ濡风€ｎ亝鍠橀柟顔炬櫕缁瑧鎹勯…鎴濇暪闂傚倷鐒﹂崕鍐裁瑰璺虹；闁糕剝绋戦悡婵嬫煛閸屾氨姘ㄩ柡鈧禒瀣厽婵☆垵顕х徊濠氭煛閸℃瑥浠遍柡宀嬬秮楠炴鈧潧鎲￠崳顕€姊洪柅鐐茶嫰婢ь垱銇勯弮鈧悧鐘茬暦閺夋娼╅悹楦挎閻ゅ洭姊虹憴鍕妞ゆ泦鍥цЕ閻熸瑥瀚ㄦ禍婊堟煙閹冭埞闁诲浚浜弻娑氣偓锝冨妼婵倿鏌＄仦鐐缂佺粯鐩幃娆擃敆娴ｅ搫绲藉┑鐘垫暩閸嬫盯骞婂畝鍕９婵犻潧顑呴拑鐔兼煥閻斿搫孝缂佲偓鐎ｎ偁浜滈柟鏉垮缁嬭崵鈧娲栫壕顓犳閹惧鐟归柛銉戝嫮浜紓鍌欒兌缁垶鏁冮姀鐙€鍤曟い鎰╁焺閸氬顭跨捄鐚磋含闁哥偠娉涢埞鎴︽偐鐠囇冧紣闂佺娅曢崝娆撶嵁婢跺簺浜归柟鐑樻尨閹峰搫鈹戦悙璺虹毢缂侇噮鍨辩换娑㈠炊椤掍胶鍘遍梺鍝勫€藉▔鏇㈡倿閸涘浜滈柕蹇娾偓鍐叉懙濡炪們鍨洪悷鈺呭箖濞嗘搩鏁嗛柍褜鍓熼幃鐢稿级鎼存挻鏂€闂佺粯顭囩划顖氣槈瑜庣换娑㈠级閹存績鍋撻崹顕呭殨闁靛ň鏅滈悞鑲┾偓骞垮劚濡盯宕ｉ崱妞绘斀闁绘劖娼欓悘锕傛煥閺囨娅呮い顒€锕ㄩˇ瀵哥磼鏉堛劌绗氭繛鐓庣箻婵℃瓕顦撮柣锝囨暬閹泛顫濋鐘冲櫚闂佺粯鎼╅崑濠傜暦閹偊妲奸梺鍦缁查箖濡甸崟顔剧杸婵炴垶顨嗛崰姘舵倵濞堝灝鏋涢柣鏍с偢楠炲啫顭ㄩ崼婵嗚€垮┑掳鍊撻懗鑸电椤忓牊鐓熼柣鏂挎憸閻苯顭胯椤ㄥ﹪骞冮敓鐘虫櫢闁绘灏欓悾鍝勨攽閻愬弶顥為柟绋款煼瀹曟垿鏁撻悩宕囧幐婵犮垼娉涢鍛搭敋濠婂牊鐓熼柨鏃傛櫕婢х敻鏌″畝瀣М闁诡喒鏅犲畷锝嗗緞鐎ｎ亜澹嶉梻鍌欑劍閻綊宕愰弴鐏绘椽濡舵径瀣暢?*/
    private fun showTokenDetails() {
        tokenPopup?.takeIf { it.isShowing }?.let {
            it.dismiss()
            return
        }
        val stats = profileStats()
        val popupView = layoutInflater.inflate(R.layout.popup_token_stats, null)
        val title = popupView.findViewById<android.widget.TextView>(R.id.tv_token_popup_title)
        val summary = popupView.findViewById<android.widget.TextView>(R.id.tv_token_popup_summary)
        val rows = popupView.findViewById<android.widget.LinearLayout>(R.id.ll_token_rows)

        title.text = getString(R.string.token_title)
        rows.removeAllViews()
        if (stats.isEmpty()) {
            summary.text = getString(R.string.token_empty)
        } else {
            summary.text =
                getString(
                    R.string.token_summary,
                    stats.size,
                    stats.sumOf { it.generated },
                    stats.last().prompt,
                )
            val density = resources.displayMetrics.density
            stats.forEachIndexed { index, s ->
                val row =
                    android.widget.LinearLayout(this).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
                        isClickable = true
                        setBackgroundColor(0x00000000)
                    }
                val head =
                    android.widget.TextView(this).apply {
                        text = getString(R.string.token_round, index + 1, s.model)
                        textSize = 12f
                        setTextColor(0xFF1F1F1F.toInt())
                    }
                val detail =
                    android.widget.TextView(this).apply {
                        text = getString(R.string.token_detail, s.prompt, s.generated, s.decode)
                        textSize = 11f
                        setTextColor(0xFF5A6B85.toInt())
                    }
                row.addView(head)
                row.addView(detail)
                row.setOnClickListener {
                    jumpToRound(index)
                }
                rows.addView(row)
            }
        }

        val density = resources.displayMetrics.density
        val widthPx = (264 * density).toInt()
        val maxHeight = (330 * density).toInt()
        popupView.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(widthPx, android.view.View.MeasureSpec.AT_MOST),
            android.view.View.MeasureSpec.makeMeasureSpec(maxHeight, android.view.View.MeasureSpec.AT_MOST),
        )
        val height = popupView.measuredHeight.coerceAtMost(maxHeight)
        val popup = android.widget.PopupWindow(popupView, widthPx, height, true)
        popup.isOutsideTouchable = true
        popup.elevation = 10 * density
        val loc = IntArray(2)
        binding.tvTokenCount.getLocationOnScreen(loc)
        val x = loc[0].coerceAtLeast((8 * density).toInt())
        val y = loc[1] - height - (8 * density).toInt()
        popup.showAtLocation(binding.tvTokenCount, android.view.Gravity.NO_GRAVITY, x, y)
        tokenPopup = popup
    }

    /** 闂傚倸鍊搁崐鐑芥嚄閸撲礁鍨濇い鏍仜缁€澶嬩繆閵堝懏鍣圭紒鐘靛█閺岀喖骞戦幇闈涙闂佸憡淇洪～澶愬Φ閸曨垰妫橀柛顭戝枓閹稿啰绱?N 闂傚倸鍊风粈渚€骞栭位鍥焼瀹ュ懐锛熼梺鍦濠㈡﹢姊婚娑栦簻闁哄秲鍔庨惌瀣亜椤愶絾绀冪紒缁樼箞濡啫鈽夊▎娆戠闂備胶绮幐鍫曞磿閺屻儱绠為柕濞垮労濞笺劑鏌涢埄鍐炬當妞ゎ偀鏅犻幃璺侯潩閸楃偞鐏堝┑顔硷攻濡炶棄鐣烽妸锔剧瘈闁稿本绮庨幊鏍р攽閻樻剚鍟忛柛顭戝灣閸掓帒鈻庨幘鎵佸亾娴ｅ壊娼╂い鎺戭槹閸嶉潧顪冮妶鍡樺暗闁哥姴閰ｉ敐鐐哄灳閹颁焦瀵岄梺闈涚墕濡瑩鍩涢幒妤佺厱閻庯綆鍋呭畷宀勬煙椤曗偓缁犳牠寮婚崱妤婂悑闁糕剝鐟ラ獮宥囩磽閸屾艾鈧兘鎮為敃鍌涙櫔濠电姵顔栭崰鏍矓閸偆鈹?*/
    private fun jumpToRound(round: Int) {
        var seen = -1
        var target = -1
        for (i in messages.indices) {
            if (messages[i].type == MessageType.PROFILE) {
                seen++
                if (seen == round) {
                    var j = i - 1
                    while (j >= 0 && messages[j].type != MessageType.USER) j--
                    target = j
                    break
                }
            }
        }
        tokenPopup?.dismiss()
        if (target >= 0) {
            binding.rvChat.scrollToPosition(target)
        }
    }

    /** 濠电姷鏁告慨鐑姐€傞挊澹╋綁宕ㄩ弶鎴狅紱闂侀€炲苯澧撮柡灞剧〒閳ь剨缍嗛崑鍛焊閹殿喚纾肩紓浣诡焽濞插鈧鍠栭悥濂哥嵁鐎ｎ噮鏁囬柣鎰絻椤ュ秹姊婚崒娆掑厡缁绢厼鐖煎鎻掆槈閵忕姴鐝樺銈嗗笒閸婂鎯屽▎蹇ｇ唵閻犺桨璀﹂崕鎴犵磼閳锯偓閸嬫挸鈹戦悙鑸靛涧缂傚秮鍋撳┑鐐叉嫅缂嶄線鐛径鎰垫晪闁逞屽墮椤繐煤椤忓嫪绱堕梺鍛婃处閸嬧偓闁稿鎸剧划娆徫涢崹顐ｃ仢濠碘€崇埣瀹曞爼鈥﹂幋鐐电◥闂傚倷娴囬～澶婄暦濮椻偓瀹曟椽寮介鐐磋緢濠电姴锕ら悧濠囨偂閺囩喓绠鹃柟瀵稿剳閸忣剟鏌￠埀顒傜磼濡晲绨婚柨婊冾煼瀹曞爼濡搁姀鈥愁伖闂傚倷绀佸﹢杈╁垝椤栨粍鏆滈柣鎰硾閳ь剚鎸冲缁樻媴閸︻厽鑿囬梺绋匡攻濞叉牞鐏嬪┑鐐叉濞存岸鎮為挊澶嗘斀闁绘ê鐤囨竟妯肩磼閻樺磭鈯曢柕鍥у瀵粙鎮介悽鐬垿姊洪崫鍕偓鐢稿磻閹剧粯鈷掗柛灞剧懅椤︼箓鏌熺喊鍗炰喊鐎规洦鍨堕、娑橆潩閹规劏鍋撻崸妤佺厱闁挎棁顕ч獮妯肩磼閻樿櫕顥堥柡灞剧〒娴狅箓宕滆閳ь剚甯￠弻宥囩磼濡椿妫冮梺璇″枟椤ㄥ﹪寮幇顓熷劅闁炽儴灏欓崙褰掓⒒娴ｇ瓔鍤欓悗鍨浮瀹曟娊鏁愭径濠呮憰闂侀潧顭堥崕顔嘉ｉ崼鐔剁箚妞ゆ牗绮岀敮鑸点亜閿濆懏璐＄紒杈ㄦ尰閹峰懐鎷犻埄鍐殽婵＄偑鍊х€靛矂宕归懜鍏哥箚?UI闂?*/
    private fun resetRuntimeContext() {
        modelScope.launch {
            runCatching {
                if (isLoadVlmModel) {
                    vlmChatList.clear()
                    vlmWrapper.reset()
                } else {
                    chatList.clear()
                    llmWrapper.reset()
                }
            }
            runOnUiThread {
                Toast.makeText(this@MainActivity, R.string.ctx_reset, Toast.LENGTH_LONG).show()
            }
        }
    }

    private var maxOutputPopup: android.widget.PopupWindow? = null

    private fun updateMaxOutputButton() {
        binding.btnMaxOutput.text = getString(R.string.btn_max_output, maxOutputTokens)
    }
    private fun showMaxOutputDialog() {
        maxOutputPopup?.takeIf { it.isShowing }?.let {
            it.dismiss()
            return
        }

        val options = intArrayOf(512, 1024, 2048, 4096, 8192, 0)
        val popupView = layoutInflater.inflate(R.layout.popup_max_output, null)
        val container = popupView.findViewById<android.widget.LinearLayout>(R.id.ll_max_output_options)

        val density = resources.displayMetrics.density
        val padH = (16 * density).toInt()
        val padV = (10 * density).toInt()

        options.forEach { value ->
            val row =
                android.widget.TextView(this).apply {
                    text = if (value == 0) getString(R.string.max_output_unlimited) else value.toString()
                    textSize = 15f
                    setPadding(padH, padV, padH, padV)
                    val selected = value == maxOutputTokens
                    setTextColor(if (selected) 0xFF2F6BFF.toInt() else 0xFF1F1F1F.toInt())
                    if (selected) {
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                    setOnClickListener {
                        maxOutputTokens = value
                        getSharedPreferences("geniex_ui", MODE_PRIVATE)
                            .edit()
                            .putInt("max_out_tokens", maxOutputTokens)
                            .apply()
                        updateMaxOutputButton()
                        maxOutputPopup?.dismiss()
                    }
                }
            container.addView(row)
        }

        val popup =
            android.widget.PopupWindow(
                popupView,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                true,
            )
        popup.isOutsideTouchable = true
        popup.elevation = 10 * density
        popupView.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
        )
        val popupHeight = popupView.measuredHeight
        val popupWidth = popupView.measuredWidth

        val loc = IntArray(2)
        binding.btnMaxOutput.getLocationOnScreen(loc)
        val x = loc[0]
        val y = loc[1] - popupHeight - (8 * density).toInt()
        popup.showAtLocation(binding.btnMaxOutput, android.view.Gravity.NO_GRAVITY, x, y)
        maxOutputPopup = popup
    }

    /**
     * 闂傚倸鍊搁崐椋庣矆娓氣偓楠炴牠顢曢敂缁樻櫈闂佸憡渚楅崹顏堝磻閹炬剚娼╂い鎰╁灩缁侇噣姊虹化鏇熸澒闁?prompt闂傚倸鍊搁崐鐑芥倿閿旈敮鍋撶粭娑樻噽閻瑩鏌熼悜姗嗘畷闁稿鍊曢埞鎴﹀磼濞戞氨顦梻鍌氬€搁崐鐑芥倿閿旈敮鍋撶粭娑樻噽閻瑩鏌熺€涙绠ラ柣鎺旀櫕缁辨帡鎮滃Ο鑲╂啴rt闂傚倸鍊搁崐鐑芥倿閿旈敮鍋撶粭娑樻噽閻瑩鏌熷▎陇顕уú顓€佸☉妯锋敠闁诡垎鍌氼棜闂備礁澹婇悡鍫ュ磻閹剧粯鍤€閻犳亽鍔夐崑鎾舵喆閸曨剛顦梺绋跨箲钃卞ǎ鍥э躬楠炴牗鎷呴懖婵勫姂閺屾洘寰勯崼婵嗗缂傚倸绉撮ˇ闈涱潖缂佹ɑ濯撮柛娑橈工閺嗗牓姊洪懡銈呮瀭闁告柨瀛╃粋宥夊箹娓氬洦鏅滈梺绯曞墲閻熝囨偂鐎ｎ喗鐓熼幖娣灮閳洟鎷戦崡鐏荤懓鈹冮崹顔瑰亾濠靛钃熸繛鎴欏灩鍞梺鐟扮摠缁诲倿寮冲▎鎴犵＜濡増绻傞獮妤冪磼缂佹娲存鐐差儔閹瑩寮堕幋鐙€鏆￠梻鍌欑劍鐎笛兠洪埡鍐ㄧ筏閻犳亽鍔岄崹婵囥亜閹惧崬鐏╃€瑰憡绻堥弻鈩冨緞鐎ｎ亞浠稿銈冨劜缁诲牆顫忓ú顏勭闁绘劖褰冩慨宀勬⒑閸涘﹥鐓ョ紒澶岊攰濡喖鎮峰鍐ф繝鈧笟鈧铏圭矙鐠恒劎顔掑┑鈽嗗亝鐎笛呯矉瀹ュ顫呴柕蹇嬪灮閿涙粎绱撻崒娆戝妽闁挎岸鏌ｈ箛鎿冨殶闁逞屽墲椤煤閺嶎厽鍋夊┑鍌滎焾缁犳牗绻涢崱妯虹仴闁哄鐗婃穱濠囶敍濠婂懎绗″┑鐐叉噹閻楀﹦鎹㈠┑鍫濇瀳婵☆垱妞垮鎴︽⒑閸撹尙鍘涘ù婊庝簻閻ｅ嘲顭ㄩ崘锝嗘杸闂佺硶妾ч弲婵嬪船闂堟稈鏀芥い鏂款潟娴犳粓鏌涚€ｎ偅宕岄柡?     * 闂傚倸鍊搁崐鎼佸磹妞嬪孩顐介柨鐔哄Т绾惧鏌涘☉娆愮稇缂佲偓婢跺鍙忔俊鐐额嚙娴滈箖姊虹拠鈥虫灈婵炲皷鈧磭鏆﹂柛妤冨€ｉ悢杞扮剨闁哄秲鍔庢禍娆撴⒒閸屾瑧绐旀繛浣冲懏宕查柟鐑樻尰閸欏繐顪冪€ｎ亪顎楁い銉﹁壘铻栭柨婵嗘噹閺嗙偤鏌ｉ幘瀵告创闁哄本鐩、鏇㈡晲閸繍妫熷┑鐐茬摠缁挾绮婚弽褜娼栨繛宸簻娴肩娀鏌涢弴銊ヤ簼婵炲牊绮撳娲传閸曨剙娅ょ紓浣筋嚙閸婂潡宕洪姀銈呯閻犲洩灏欓鍝勨攽閻樼粯娑ч柛鐕佸灦閿濈偛顓兼径瀣ф嫼闂佸憡鎸昏ぐ鍐╃閻愮儤鐓曢柣妯垮皺鑲栧┑鈥冲级閸旀瑥顕ｆ禒瀣垫晣闁绘柨鎼獮妤呮⒒娴ｇ鏆遍柟纰卞亰瀹曟劙宕烽鐘殿槸闂佺鎻梽鍕偂閺囥垺鐓冮弶鐐靛缁佷即鎮楀顐㈠祮闁硅棄鐖奸弫鎰緞鐎ｎ剙甯楅梻浣哥枃濡椼劎绮堟笟鈧鎶芥倷閻㈢數锛滈梺鐓庢憸閺佹悂宕ｉ埀顒€鈹戦纭峰伐妞ゎ厼鍢查悾閿嬬附缁嬫娼婇梺缁橆焾妞存悂宕哄☉銏♀拻濞达絽鎽滈弸鍐┿亜椤愩埄妯€闁诡啫鍥ㄥ亹閻犲洤寮堕悗顒勬倵楠炲灝鍔氶柟铏姍瀵娊宕卞☉娆戝幈闂佸搫娲㈤崝瀣姳閻ｅ瞼纾奸弶鍫涘妼閸濈儤鎱ㄦ繝鍛仩缂侇喗鐟ч幑鍕Ω瑜滈崬鍓佺磽閸屾瑧顦︽い锔诲灦閹ê顫濋鐘敵婵犵數濮村ú銈嗗閻樼粯鐓忓璇″灠閹虫劙宕?prompt 闂傚倸鍊搁崐鎼佸磹妞嬪孩顐介柨鐔哄Т绾惧鏌涘畝鈧崑鐐哄吹閸曨垱鐓曢柟鎹愬皺椤︼箓鏌涢妸锔剧疄闁哄备鍓濆鍕償椤斿吋顔曢梻浣虹帛濮婂宕㈣閹ê鐣烽崶锝呬壕閻熸瑥瀚粈鈧┑鐐跺皺婵炩偓鐎规洘鍨块獮妯肩磼濡　鍋撴繝姘參婵☆垳灏ㄧ槐?     */
    private suspend fun buildPromptWithTrim(
        base: List<ChatMessage>,
        toolsJson: String?,
        thinking: Boolean,
    ): Pair<String, Int>? {
        var working = base.toMutableList()
        var guard = 0
        while (guard < 80) {
            val deferred = kotlinx.coroutines.CompletableDeferred<String?>()
            llmWrapper
                .applyChatTemplate(working.toTypedArray(), toolsJson, thinking)
                .onSuccess { if (!deferred.isCompleted) deferred.complete(it.formattedText) }
                .onFailure { if (!deferred.isCompleted) deferred.complete(null) }
            val text = deferred.await()
            if (!text.isNullOrEmpty()) {
                return text to (base.size - working.size)
            }
            if (working.size <= 2) return null
            working = working.drop(2).toMutableList()
            guard++
        }
        return null
    }

    private var selectionMode = false
    private val selectedIdx = sortedSetOf<Int>()

    private fun enterSelection(initial: Int? = null) {
        selectionMode = true
        selectedIdx.clear()
        initial?.let { selectedIdx.add(it) }
        adapter.selectionMode = true
        adapter.selected.clear()
        adapter.selected.addAll(selectedIdx)
        binding.llSelectionBar.visibility = View.VISIBLE
        binding.llInputActions.visibility = View.GONE
        binding.btnSelectMode.text = getString(R.string.cancel)
        binding.etInput.visibility = View.GONE
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(binding.etInput.windowToken, 0)
    }

    private fun exitSelection() {
        selectionMode = false
        selectedIdx.clear()
        adapter.selected.clear()
        adapter.selectionMode = false
        binding.llSelectionBar.visibility = View.GONE
        binding.llInputActions.visibility = View.VISIBLE
        binding.btnSelectMode.text = getString(R.string.select_mode)
        binding.etInput.visibility = View.VISIBLE
    }

    private fun toggleSelection(position: Int) {
        if (!selectedIdx.add(position)) selectedIdx.remove(position)
        adapter.selected.clear()
        adapter.selected.addAll(selectedIdx)
        adapter.notifyItemChanged(position)
    }

    /**
     * Qwen/llama 闂?BPE 闂傚倸鍊搁崐椋庣矆娓氣偓楠炴牠顢曢妶鍌氫壕婵ê宕崢瀵糕偓瑙勬礉椤鈧絻鍋愰埀顒佺⊕閿氬ù鐘讳憾閹嘲顭ㄩ崟顒夋濡ょ姷鍋涢敃顏堝箖濞嗘挸浼犻柛鏇ㄥ弿缁卞弶绻濋悽闈浶㈤柨鏇樺€濋幃褑绠涢幘鑸垫濠电姴锕ら悧濠囧煕閹寸偞鍙忛柣鐔哄閹兼劕鈹戦垾鐐藉仮闁诡喗顨呴～婵嬵敆娴ｇ懓鏋戞繝纰樷偓鍐茬骇闁告梹鐟╅獮鍡涘礃椤忎礁浜鹃柛锔诲幗缁犵垁n 闂傚倸鍊搁崐鎼佸磹閻戣姤鍊块柨鏇氶檷娴滃綊鏌涢幇闈涙灍闁搞倖鍔栭妵鍕冀椤愵澀娌梺绋款儍閸婃繈寮婚弴鐔虹闁绘劦鍓氶悵鏇㈡⒑缁嬪潡顎楃紒澶婄秺瀵鈽夐姀鐘插祮闂侀潧顭堥崕閬嶏綖椤忓棛纾兼俊銈勭閻忓瓨鎱ㄦ繝鍐┿仢婵☆偄鍟埥澶娾枎濡椿妫ч梻鍌欑閸氬顪冮崸妤€鍨傞柛顐ｆ礀缁犳牕螖閿旂瓔鍎ユ俊顖氬濮?闂?闂傚倸鍊搁崐椋庣矆娓氣偓楠炲鏁撻悩鍐蹭画濡炪倖鐗滈崑娑㈠垂閸岀偛绾ч柛顐ｇ☉婵¤姤绻涢崨顖氣枅闁哄被鍔戝鎾倷濞村浜鹃柛婵勫劜閸忔粓鏌嶈閸撶喎顫忓ú顏勪紶闁告洖鐏氭瓏婵犵數鍋涢ˇ鏉棵洪悢鑲╁祦婵°倕鎳忛崑鍕煣韫囨挻璐￠柨娑欑矊閳规垶骞婇柛濠冩礋椤㈡牠宕奸姀銏㈢劶闁诲函缍嗛崰妤呮偂閸愵亝鍠愭繝濠傜墕缁€鍫熺箾閸℃ê鐏╅柣顓熸崌濮婂宕奸悢宄板箯闂佹寧绻傞悧婊兾ｉ悙顒傜閻庢稒顭囬惌瀣煙椤旂厧鈧悂鎮鹃悜鑺ュ亜缁炬媽椴搁弲婵嬫⒑閹稿海绠撻柟鍐差樀瀹曟垿骞樼紒妯轰缓闂佸憡绋戦敃锕傚储娴犲鈷戦梻鍫熶緱閻掗箖鏌涙惔銊ゆ喚妞ゃ垺妫冮崺锟犲川椤旀儳骞楅梻浣瑰墯閸ㄩ亶鎮烽妷鈺佺疇闁归偊鍠氭稉宥夋煛閸愩劎澧涢柣鎾卞劦閺岋繝宕橀妸锕€鍋嶉梺閫炲苯鍘撮柛瀣崌濮?     * 闂傚倸鍊搁崐椋庣矆娓氣偓楠炴牠顢曢敃鈧€氬銇勯幒鎴濐仼缂佲偓婢舵劖鐓欓柣鎴炆戦ˉ鍡椻攽?token闂傚倸鍊搁崐鐑芥倿閿旈敮鍋撶粭娑樻噽閻瑩鏌熸潏楣冩闁稿顑夐弻娑㈠焺閸愵亖妲堢紓浣哄С閸楁娊寮婚弴鐔虹闁绘劦鍓氶悵鏃傜磽娴ｆ彃浜鹃梺绋跨灱閸嬬偤宕戦敐澶婄骇闁绘劖娼欓婊勩亜閹烘柨鏋戝ǎ鍥э躬椤㈡稑顫濋悡搴″闂備浇顕栭崰妤呫€冮崨鏉戠叀濠㈣泛艌閺嬪孩淇婇婵嗗惞闁哄棭鍨伴埞鎴︽偐濞堟寧姣屽┑鈩冨絻閹虫ê鐣烽姀銈嗗仼鐎光偓閳ь剟鎯屽▎蹇ｇ唵闁兼悂娼ф慨锕傛煛娴ｅ憡鍠橀柡宀嬬秮瀵剟骞愭惔銏犳瀾闂備線鈧偛鑻晶濠氭煕閿濆繒绉€殿喖顭锋俊鎼佸Ψ閵忊槅娼旀繝娈垮枟椤ㄥ懎螞濞嗘挸鐤鹃柣婵堢〔 濠电姷鏁告慨鐑藉极閹间礁纾绘繛鎴炵懃缁剁偤鏌涢妷顔煎缂佲偓閸喓绡€闂傚牊绋掗敍鏃堟煠閺夎法浠㈤棁澶愭煥濠靛棛澧辨繛鍏煎姇闇夋繝褍鐏濋埀顒佺墬缁岃鲸绻濋崶鑸垫櫖濠殿喗锕╅崢浠嬪磿閹剧粯鈷戦柛婵嗗閳ь剚鍨垮畷鍫曞Ω瑜嶉獮?UTF-8闂傚倸鍊搁崐鐑芥倿閿旈敮鍋撶粭娑樻噽閻瑩鏌熸潏楣冩闁稿孩妫冮弻锝夊箻瀹曞洨妲忓┑鐐叉▕娴滄粓鏌ㄩ妶鍡曠箚闁靛牆鍊告禍鍓х磽娴ｅ搫校濠电偛锕濠氬即閻旈绐為梺鍓插亝缁诲倹鎱ㄩ弴銏♀拺缂佸灏呴崝鐔兼煕韫囨棑鑰跨€殿喛顕ч埥澶愬閻樻鍚呴梻浣瑰缁诲倸螞鎼淬劌鐒垫い鎺嗗亾闁哥喐澹嗗Σ鎰板箳濡炵儵鍋撻敃鍌氱闁哄啫鍊诲Σ鍥煟鎼淬埄鍟忛柛鐘虫礋瀹曪繝骞庨挊澶岀暫闂佸啿鎼幊蹇涘吹閹寸偑浜滈柟鍝勭Ф鐠愪即鏌涢悢閿嬪殗婵﹨娅ｉ幏鐘诲箵閹烘繂濡风紓鍌欑椤戝棛鏁敓鐘参ラ柛鎰靛枛闁卞洭鏌曟径妯烘灍闁哄懐鍋炵换娑㈠箻閺夋垹鍔伴梺绋款儐閹瑰洭寮?     * 婵犵數濮烽弫鎼佸磻濞戙垺鍋ら柕濞у啫鐏婇悗鍏夊亾闁逞屽墴閸┾偓妞ゆ帊鑳堕埢鎾绘煛閸涱喚鈽夌€规挸瀚板楦裤亹閹烘搫绱甸梺缁樺釜缁犳挸鐣烽幋锕€鐓涢柛鎰剁到娴滈箖鏌涜箛鎿冩Ц濞存粓绠栧娲川婵犲啫顦╅梺鍛婃尰瀹€鍛婁繆閹绢喖绀冮柍鍝勫暟椤旀洟姊洪悷閭﹀殶闁稿鍋熸禍鎼侇敇閵忥紕鍙嗛梺鍝勬处閿氶柍褜鍓氱换鍌炴偩閻戣姤鍋ㄩ柛鎾冲级閺咃綁姊洪棃娑氱畾闁逞屽墮閸樻牠鎼?婵犵數濮甸鏍窗濡ゅ嫭鎳岄梻浣哥秺閳ь剚绋撻崝宥夋煛閸涱厾鍩ｆ鐐叉喘椤㈡﹢鎮㈤崫銉ュ▏?闂傚倸鍊风粈渚€骞栭位鍥敃閿曗偓閻ょ偓绻濇繝鍌滃闁稿绱曢幉鎼佸籍閸喐娅滈梺缁樺姇閹碱偆绮婚幎鑺ョ厵闁诡垎鍛偗闂佸搫顑嗛悷鈺呭蓟閳╁啫绶炲┑鐘插椤ｇ敻姊洪崫鍕櫤缂佽鐗嗛悾鐤亹閹烘垹顔呴梺鍏间航閸庢娊宕㈤鍫熲拺缂侇垱娲栨晶鍙夈亜閵娿儲鍤囬柛鈹惧亾濡炪倖甯掗崐鐟扳枍閺囥垺鐓欐鐐茬仢閻忚尙鈧鍠曠划娆撱€佸鈧幃銏ゅ传閸曨偆鐟╅梻鍌欐祰椤曆呮崲閹烘纾婚柣鎰惈绾惧潡鏌熼幆鏉啃撻柛?GPT-2/Qwen 闂傚倸鍊搁崐鐑芥倿閿曞倹鍎戠憸鐗堝笒缁€澶屸偓鍏夊亾闁逞屽墴閸┾偓妞ゆ帊绀侀崵顒勬煕濡厧甯剁€规挸瀚伴幃宄邦煥閸愵亞顔夌紓浣稿€哥粔褰掔嵁閺嶃劍濯撮柛婵勫労濡茶淇婇悙顏勨偓鏍涙笟鈧俊鎾焵椤掑嫭鐓曢悗锝庡亝瀹曞瞼鈧娲橀敃銏ゃ€佸▎鎾村仼閻忕偠妫勫暩闂傚倸鍊烽懗鍓佹兜閸洖绀堟繝闈涱儍閳ь剙鍟村畷鍗炩槈濡》绱┑鐘垫暩婵敻鎳濋崜褏妫憸鏃堝蓟瀹ュ浼犻柛鏇ㄤ簽缁€鈧俊鐐€曠换鎰涘▎鎾虫辈婵犲﹤瀚ㄦ禍婊堟煙閺夊灝顣抽柣锝堥哺濞?     *
     * 濠电姷鏁告慨鐑藉极閹间礁纾绘繛鎴旀嚍閸ヮ剦鏁囬柕蹇曞Х椤︻噣鎮楅獮鍨姎妞わ富鍨崇划鍫ュ醇濠㈡繂缍婇幃鈩冩償閿濆棙鍠栭梻浣告惈閹峰宕滃璺虹疄闁靛鍎哄銊╂煕閳╁喚娈旈悗娑崇秮濮婃椽宕ㄦ繝鍐幐闂佺顑嗛幑鍥ь潖閸濆嫅褔宕惰閸旀挳姊洪幖鐐插闁告濞婇悰顔嘉旈埀顒勨€﹂妸鈺佺闁挎繂妫涙禍鏍⒒娴ｄ警鏀伴柟娲讳簽缁骞嬮敐鍥╊啍闂傚倷娴囬褏鎹㈤幒妤€纾绘繛鎴炲焹閸嬫挸顫濋悡搴＄睄闂佽鍠楅敋妞ゎ偅绻勯幑鍕惞閻熸澘袝濠碉紕鍋戦崐鏍ь啅婵犳艾纾婚柟鎯у绾惧ジ鎮楅敐搴濈盎妞ゅ浚鍋呮穱濠囧矗婢跺﹤顫屽?00B7闂傚倸鍊搁崐鐑芥倿閿旈敮鍋撶粭娑樻噽閻瑩鏌熷▎陇顕уú顓烆嚕閸洖绀岄梽鍥焵椤掆偓閻忔繈婀侀梺缁樻尭鐎垫帡宕甸悢鍏肩厱婵炲棙鍔栫粈鍐煏閸パ冾伃妤犵偛娲崺鈩冩媴鏉炴壆妫梻鍌欑劍閻綊宕濆澶婂瀭闁革富鍘介～鏇㈡煙閻戞ê鐏嶆俊鎻掔墛閹便劌螖閳ь剙螞濞嗗繑姣勭紓鍌氬€搁崐椋庢閿熺姵鍋傞柛顐犲劚缁€鍫熺箾閹存瑥鐏╅柣鎺戠仛閵囧嫰骞掑鍫濆帯闂佹剚鍨卞ú姗€濡甸崟顖毼╅柕澶樺枟鏁堝┑?0xB7闂傚倸鍊搁崐鐑芥倿閿旈敮鍋撶粭娑樻噽閻瑩鏌熸潏鍓х暠閻庢艾顦伴妵鍕箳瀹ュ洤濡藉┑鐐叉噽婵兘鍩€椤掆偓缁犲秹宕曢崡鐐嶆稑鈽夐～顑藉亾閸涘瓨鍊婚柤鎭掑劤閸橆亝绻濋姀锝嗙【闁绘妫濆畷婵嗩潩椤撶姷顔曢梺鐟板暱閸㈡彃煤閿曞倹鍋傛繛鎴欏灪閻撴洘銇勯幇鍓佹偧缂佺姰鍎叉穱濠囧箵閹烘挻鍠愮紓浣介哺閹告悂顢樻總绋挎そ濞达絿顭堟竟澶岀磽閸屾瑧顦︽い鎴濇嚇钘濋柣銏㈩焾閺勩儵鏌嶈閸撴岸濡甸崟顖氱鐎广儱鐗嗛崢锟犳⒑瀹曞洨甯涙俊顐㈠暣瀵鎮㈤搹鍦紲濠碘槅鍨靛▍锝夘敄閸岀偞鈷戦柛锔诲幗濞呮洟鏌涢悩宕囧⒌鐎?     * 闂傚倸鍊搁崐鐑芥嚄閸洖鍌ㄧ憸鏃堝箖閸ф鐐婃い顒夊墯閻╊垰鐣烽悢纰辨晬婵炴垶眉閹絾淇婇悙顏勨偓鏍暜閹烘柡鍋撳鐓庡⒋鐎殿喖鎲￠幏鍛村捶椤撶媭鍟庨梻浣瑰缁诲倹顨ラ幖浣稿偍闁圭虎鍠楅悡鏇㈡倵閿濆骸浜濋悘蹇庡嵆閺岀喎鐣￠悧鍫濇闂佷紮缍囩换婵嬬嵁瀹ュ鏁婇柛顭戝枤閸樻挳姊婚崒姘偓椋庣矆娓氣偓楠炴牠顢曢妶鍥╃厠闂佸壊鍋呭ú鏍磼閵娧勫枑鐎广儱顦伴弲婵嬫煏韫囧﹥鍣伴柛瀣崌濡啫鈽夊▎蹇旀畼闂備礁鎽滄慨鏉懳涘┑瀣摕闁靛鍎弨浠嬫煕閳╁喛渚涙俊顐ゅ仱濮婅櫣鎷犻懠顒傤唶濠电偛妯婇崣鍐嚕鐠囨祴妲堟慨妤€妫欓崓闈涱渻閵堝棗绗掗柛濠冩倐璺柍褜鍓涚槐?UTF-8 闂傚倸鍊搁崐椋庣矆娓氣偓楠炴牠顢曢敃鈧悿顕€鏌熼幆鐗堫棄闁哄嫨鍎甸弻鈥愁吋閸愩劌顬夊┑鐐叉噽婵炩偓闁哄矉绲借灒婵炲棙鍎冲▓顓㈡⒑閸涘娈曞┑鐐诧躬瀵鎮㈤搹鍦厯闂佺鍕垫闁逞屽墯椤ㄥ﹪鐛弽顓炵骇闁规惌鍨甸崥顐︽⒑閸濆嫯顫﹂柛鏂跨焸閸┿儲寰勬繛銏㈠枛瀹曞綊顢氶崨顒傜＞闂傚倸鍊峰ù鍥敋閺嶎厼鍨傛い鏍ㄧ矋椤愯姤鎱ㄥ鍡楀闁哄棴闄勯幈銊ヮ潨閸℃骞嬮梺鍝勵儏闁帮綁鎮￠锕€鐐婇柕濠忕畱闂夊秹姊洪崫鍕靛剶缂佺姵鐗曢～蹇撁洪鍕炊闂佸憡娲﹂崢婊堝Ψ閿斿墽顔曢梺瑙勫礃濞呮洜鎷归敓鐘崇厵妤犵偛鐏濋悘鈺傘亜椤愶絿绠為柟铏矒濡啫鈽夊Δ浣镐簼婵犵數濮烽弫鎼佸磻濞戞娑樜旈崘顏嗙厠濡炪倖娲嶉崑鎾垛偓?     */
    private val byteDecodeMap: Map<Int, Int> by lazy {
        val bs = mutableListOf<Int>()
        bs.addAll(33..126)
        bs.addAll(161..172)
        bs.addAll(174..255)
        val cs = bs.toMutableList()
        var n = 0
        for (b in 0..255) {
            if (b !in bs) {
                bs.add(b)
                cs.add(256 + n)
                n++
            }
        }
        val map = HashMap<Int, Int>(256)
        bs.indices.forEach { i -> map[cs[i]] = bs[i] }
        map
    }

    /** 闂傚倸鍊峰ù鍥敋瑜忛埀顒佺▓閺呮繄鍒掑▎鎾崇婵＄偛鐨烽崑鎾诲礃閳轰胶绐為柣搴岛閺呮繄绮?闂?闂傚倸鍊搁崐椋庣矆娓氣偓楠炲鏁撻悩顐熷亾閿曞倸鐐婃い鎺嗗亾缂佹劖顨婇獮鏍箹椤撶姴甯ㄧ紓鍌氱У閻楃娀寮婚悢鍏煎亱闁割偆鍠撻崙锟犳⒑閸涘﹦鎳冮柨鏇ㄤ邯瀵鏁愰崨鍌滃枛瀹曞綊顢欓悙顒夊殑濠电姷鏁搁崑娑㈡偋婵犲洤绠栭柛宀€鍋涚粻鏍ㄤ繆閵堝倸浜鹃梺瀹犳椤︻垶鍩㈡惔鈭ョ喖鎳￠妶鍡樼様婵犵绱曢崑鎴﹀磹閺嶎厼鍨傜€规洖娲﹂鑺ユ叏濡寧纭鹃柡瀣╄兌閳ь剙绠嶉崕閬嶅箠閹版澘姹查柛鈩冪⊕閻撳啰鎲稿鍫濈闁绘柨顨庨崵鏇熴亜閹板墎鐣遍崶瀛樼節閵忥絾纭鹃柨鏇檮椤ㄣ儵宕堕浣叉嫼闂傚倸鐗婃笟妤€危閸洘鐓曢柡鍌濆劵鐎氫即鏌嶇紒妯诲磳妤犵偛绉归、娆撳礈瑜濈槐鍐测攽閻愯埖褰х紓宥佸亾濡炪倖娲橀悧鐘诲箖閻愬搫绀堢憸搴ㄥ磻閺嶃劎绠鹃柛鈩兩戠亸浼存煟閹捐泛鏋戦柕鍥у瀵噣宕惰閹偤姊洪崨濠冪叆缂佸鐏氱粚杈ㄧ節閸ャ劌鈧兘鏌熺紒妯哄潑闁稿鎹囬幃鐑芥焽閿旀儳濮洪梺鑽ゅТ濞层倕顕ｉ鍕亜闁绘挸绨肩槐鍫曟⒑閸涘﹥澶勯柛鎾村哺閹﹢骞庨懞銉㈡嫽婵炶揪绲肩拃锕€顭囬幒妤佺厱闁规澘鍚€缁ㄨ姤銇勯弬鎸庡枠婵﹥妞介幃婊堝煛娓氬洦缍掗梻浣规偠閸庮垶宕濈仦瑙ｆ灁闁绘柨鍚嬮埛鎴犵磼鐎ｎ厽纭堕柣鎺戠仢閳规垿鍨鹃搹顐㈢獩缂備緡鍠栭悧鎾崇暦閻旂⒈鏁冮柣鏂挎啞閺夋悂姊绘担鍝勪缓闁稿酣浜跺畷顖炲锤濡も偓閻掑灚銇勯幒宥囧妽闁稿﹥鍔栭妵鍕閳藉棙鐣堕梺瀹狀潐閸ㄥ灝鐣锋總绋款潊闁绘灏闂傚倷娴囬褔宕欓悾宀€绀婇柛鈩冪☉缁€鍫熸叏濡灝鐓愰柛?闂傚倷娴囧畷鍨叏瀹曞洦濯伴柨鏇炲€搁崹鍌炴煕椤愶絾绀€闁?*/
    private val byteEncodeMap: Map<Int, Char> by lazy {
        val bs = mutableListOf<Int>()
        bs.addAll(33..126)
        bs.addAll(161..172)
        bs.addAll(174..255)
        val cs = bs.toMutableList()
        var n = 0
        for (b in 0..255) {
            if (b !in bs) {
                bs.add(b)
                cs.add(256 + n)
                n++
            }
        }
        val map = HashMap<Int, Char>(256)
        bs.indices.forEach { i -> map[bs[i]] = cs[i].toChar() }
        map
    }

    private fun decodeWithFallback(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val need =
                when {
                    b0 < 0x80 -> 0
                    b0 in 0xC2..0xDF -> 1
                    b0 in 0xE0..0xEF -> 2
                    b0 in 0xF0..0xF4 -> 3
                    else -> -1
                }
            var valid = need >= 0 && i + need < bytes.size
            if (valid && need > 0) {
                for (k in 1..need) {
                    val bk = bytes[i + k].toInt() and 0xFF
                    if (bk and 0xC0 != 0x80) {
                        valid = false
                        break
                    }
                }
            }
            if (valid && need == 0) {
                sb.append(b0.toChar())
                i++
            } else if (valid) {
                sb.append(String(bytes.copyOfRange(i, i + need + 1), Charsets.UTF_8))
                i += need + 1
            } else {
                sb.append(byteEncodeMap[b0] ?: '?')
                i++
            }
        }
        return sb.toString()
    }

    private fun decodeStrict(bytes: ByteArray): String? =
        runCatching {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()

    private fun decodeIgnore(bytes: ByteArray): String? =
        runCatching {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.IGNORE)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.IGNORE)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()
    private fun repairByteLevel(text: String): String {
        if (text.isEmpty()) return text
        var needs = false
        for (ch in text) {
            val b = byteDecodeMap[ch.code]
            if (b != null && b > 127) {
                needs = true
                break
            }
        }
        if (!needs) return text

        val out = StringBuilder(text.length)
        val bytes = java.io.ByteArrayOutputStream()
        val raw = StringBuilder()

        fun flush() {
            if (bytes.size() == 0) return
            val data = bytes.toByteArray()
            val decoded = decodeStrict(data) ?: decodeWithFallback(data)
            out.append(decoded)
            bytes.reset()
            raw.setLength(0)
        }

        for (ch in text) {
            val b = byteDecodeMap[ch.code]
            if (b == null) {
                flush()
                out.append(ch)
            } else {
                bytes.write(b)
                raw.append(ch)
            }
        }
        flush()
        return out.toString()
            .replace(Regex("[\\u0080-\\u02FF\\u25C6][A-Za-z]"), "")
            .replace(Regex("[\\u0080-\\u02FF\\u25C6]"), "")
    }

    /** 婵犵數濮烽弫鎼佸磻閻愬樊鐒芥繛鍡樻尭鐟欙箓鎮楅敐搴′簽闁绘繂鐖奸弻娑㈠焺閸愵亖濮囬梺?prompt token闂傚倸鍊搁崐鐑芥倿閿旈敮鍋撶粭娑樻噽閻瑩鏌熸潏楣冩闁搞倖鍔栭妵鍕冀椤愵澀娌梺鎼炲€栭崝娆撳蓟閿濆绫嶉柛灞捐壘娴犳绱撴担鍝勵€岄柛鐘崇墵閸╃偤骞嬮敂钘変汗闂佸憡鐟ラˇ顖氣枍瑜忕槐鎾诲磼濮樺崬顣哄銈庡幘閸忔ê顕ｇ拠娴嬫闁靛繒濮烽娲⒑閹稿海绠撴俊顐㈤叄閸┾偓妞ゆ巻鍋撻柛鐔稿濡叉劙骞掗幊铏閸┾偓妞ゆ帒瀚壕褰掓煕椤垵娅樻繛鍏肩墪闇夐柣妯烘▕閸庡繒绱掗悩宕団姇闁靛洤瀚伴獮鎺楀幢濡炴儳顥氱紓鍌氬€烽悞锕€螞閸愵亞涓嶉柡宥庡幖閽冪喐绻涢幋娆忕仼缂佺姵濞婇弻鐔煎箚瑜滈崵鐔兼煕閹存柨澧茬紒缁樼箞閸╂盯鍩€椤掑嫬绀嬫い鎰靛亜濞堟繈姊绘担鐑樺殌闁搞垺鐓￠獮濠冩償閵娿儳鍘洪柟鍏肩暘閸ㄦ椽宕曢悢鍏肩叆婵犻潧妫欐径鍕煕閻斿憡绶查柍瑙勫灴閹瑩寮堕幋鐘辨闂備胶鎳撻幉锟犲箖閸岀偑鈧線寮介鐐茶€垮┑鐐村灦閿氶柛銈嗗灴濮婃椽宕ㄦ繝鍌氼潊闂佸搫鎳忕换鍫ュΥ閸涘瓨鍊婚柤鎭掑劤閸橆亝绻濋姀锝嗙【闁绘妫濆畷婵嗩潩閼哥數鍘?*/
    private fun effectiveMaxTokens(
        promptChars: Int,
        isNpu: Boolean,
    ): Int {
        val wanted = if (maxOutputTokens <= 0) 4096 else maxOutputTokens
        if (!isNpu) return wanted
        val ctx = 4096
        val estPrompt = promptChars / 2 + 32
        val room = (ctx - estPrompt - 64).coerceAtLeast(128)
        return minOf(wanted, room)
    }
    // ---------------- 闂傚倸鍊搁崐椋庣矆娓氣偓楠炲鏁撻悩鑼槷闂佸搫娲㈤崹鍦不閻樿绠规繛锝庡墮婵′粙鏌?/ 闂傚倸鍊峰ù鍥х暦閸偅鍙忛柟鎯板Г閸婂潡鏌ㄩ弴鐐测偓鍝ュ閸ф鐓欓悹鍝勬惈椤?/ 濠电姷鏁告慨鐑姐€傞挊澹╋綁宕ㄩ弶鎴濈€銈呯箰閻楀棝鎮為崹顐犱簻闁瑰搫妫楁禍鍓х磼閸撗嗘闁告瑥鍟村畷娲焵椤掍降浜滈柟鐑樺灥椤忓瓨淇婇锝呯伌闁哄瞼鍠愰ˇ鐗堟償閳锯偓閺嬪懘鎮楃憴鍕闁哥姵鐗犻妴渚€寮崼婵堫槹濡炪倖鍔х徊浠嬫儍?----------------

    private var modelMemBytes = 0L

    private fun totalMemBytes(): Long {
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.totalMem
    }

    private fun availMemBytes(): Long {
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.availMem
    }

    /** 闂傚倷娴囬褍霉閻戣棄绠犻柟鎹愵嚙缁犵喖姊介崶顒€桅闁圭増婢樼粈鍐煃閸濆嫬鈧顕ｉ崸妤佲拺缂備焦锚婵洭鏌ｆ幊閸斿秹骞堥妸銉叆闁割偆鍠撻崢楣冩⒑鐠団€冲箺閻㈩垱甯″畷婊堫敊缁楄　鍋撻幒鎴僵闁挎繂鎳嶆竟鏇㈡煟鎼淬値娼愭繛鍙夌墪閿曘垽鏌嗗鍛姦濡炪倖甯掗敃锔剧矓闂堟耽鐟邦煥閸曨厾鐓傜紓渚囧櫙缁插€熺亙闂佸憡渚楅崰姘跺储閽樺娓婚柕鍫濋娴滀粙鏌涢幘瀵告噭濞ｅ洤锕獮鎰償閿濆浂鍟庨梻浣稿閸嬪棝宕版惔銊﹀€堕柍杞扮贰閻斿棝鎮峰▎蹇擃仾缂佲偓閸愵喗鐓欑€瑰嫮澧楅崵鍥煙椤旂晫鐭婇摶锝囩棯椤撱埄妫戦柛鐘成戠换婵嬫偨闂堟刀鐐烘煕閵娿儳绉洪柟顔哄劦婵偓闁靛牆鎳愰ˇ銊ヮ渻閵堝懐绠伴柟铏姇鍗辨俊銈呮噺閳锋帒霉閿濆洦鍤€妞ゆ洘绮庣槐鎺斺偓锝庡亜閻忥妇绱掓潪鎵煓鐎殿噮鍣ｅ畷鎺戭煥閸滀焦笑闂傚倷绀侀幖顐﹀疮閻樿纾婚柟鎯у绾惧ジ鎮楅敐搴濇喚婵☆垪鍋撻柣搴ゎ潐濞诧箓宕戞繝鍌滄殾闁绘梻鈷堥弫鍐煥濠靛棙顥炴い鏃€妫冨缁樻媴缁涘娈柣搴㈢▓閺呮繄鍒掑▎鎾崇婵°倓鐒﹀▍鏃堟⒑鐠囧弶鍞夋い顐㈩槸鐓ら柟杈剧畱绾偓闂佽鍎煎Λ鍕不閺嶎灐褰掓偐瀹割喖鍓伴柟顖滃枛閹嘲顭ㄩ崘顔煎及闂佽鍣ｇ粻鏍х暦閿熺姵鍊堕悹鍥皺缁夎櫣鈧娲橀〃濠囧箖閳╁啯鍎熸い鏃傜摂濡棝姊?*/
    private fun computeModelMemBytes(): Long {
        if (!hasLoadedModel()) return 0L
        val name = modelList.firstOrNull { it.id == selectModelId }?.modelName ?: return 0L
        val candidates =
            listOf(
                java.io.File(filesDir, "geniex/models/$name"),
                java.io.File(filesDir, "geniex/models/qualcomm/${name.substringAfterLast('/')}"),
            )
        for (dir in candidates) {
            if (dir.exists()) {
                return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            }
        }
        return 0L
    }

    /** 闂傚倸鍊搁崐椋庣矆娓氣偓楠炴牠顢曢埛姘そ婵¤埖寰勭€ｎ亙妲愰梻渚€娼ц墝闁哄懏鐩幏鎴︽偄鐏忎焦鏂€闂佺粯蓱瑜板啴寮抽弬妫电懓顭ㄩ崟顓犵厜闂佸搫鏈惄顖炪€侀弴銏℃櫜闁糕剝鐟Σ顒勬⒒娴ｅ憡鍟為柡宀嬬秮瀹曟洟鎮界粙鑳憰濠电偞鍨崹鍦不濞戞瑣浜滈柟鍝勭Ф椤︼附绻涢崪鍐М婵﹨娅ｉ埀顒傛闂勫嫬顭垮Ο缁樺弿闁稿本澹曢崑鎾斥枔閸喗鐏堥梺缁樼墪閵堢鐣峰ú顏勎ㄩ柍杞扮缁愭稑顪冮妶鍡欏缂侇喖楠搁悾鐑藉醇閺囩啿鎷?缂傚倸鍊搁崐鎼佸磹妞嬪孩顐介柨鐔哄Т閸ㄥ倿姊婚崼鐔恒€掗柡鍡畵閺岋綁濮€閵堝棙閿梺?闂傚倸鍊搁崐鐑芥嚄閸洍鈧箓宕奸姀鈥冲簥闂佺懓鐡ㄧ换鎰版嚀閸ф鐓曢柨鏃囶嚙楠炴牠鏌涢妸锔剧畺闁靛洤瀚伴獮鎺戭吋閸パ冾瀴闂備胶顭堝ù鐑藉窗閺嶎厼钃熼柨鐔哄Т缁€鍐煏婵炲灝鍔楅柛瀣崄閵囨劙骞掗幋鐐剁发闂傚鍋勫ú锔剧矙閹烘鍋傞柕澶涘缁犻箖鏌熺€电鍓遍柣鎺嶇矙閺岋綁骞掗弬鍨箣闂?濠电姷鏁告慨鐑姐€傞挊澹╋綁宕ㄩ弶鎴濈€銈呯箰閻楀棝鎮為崹顐犱簻闁瑰搫妫楁禍鍓х磼閸撗嗘闁告瑥鍟村畷娲焵?*/
    private fun updateMemoryBar() {
        val total = totalMemBytes().coerceAtLeast(1L)
        val avail = availMemBytes()
        if (modelMemBytes <= 0L || !hasLoadedModel()) modelMemBytes = computeModelMemBytes()
        val model = modelMemBytes.coerceAtMost(total)
        val used = (total - avail).coerceIn(0L, total)
        val sysOther = (used - model).coerceAtLeast(0L)
        val free = (total - used).coerceAtLeast(0L)
        val sum = (sysOther + model + free).coerceAtLeast(1L)
        val sysPct = (sysOther * 100.0 / sum).toFloat()
        val modelPct = (model * 100.0 / sum).toFloat()

        runOnUiThread {
            binding.vMemSys.layoutParams =
                (binding.vMemSys.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
                    weight = sysPct
                }
            binding.vMemModel.layoutParams =
                (binding.vMemModel.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
                    weight = modelPct
                }
            binding.vMemSys.requestLayout()
            binding.vMemModel.requestLayout()
            val usedGb = used / 1024.0 / 1024.0 / 1024.0
            val totalGb = total / 1024.0 / 1024.0 / 1024.0
            val modelGb = model / 1024.0 / 1024.0 / 1024.0
            binding.tvMemInfo.text =
                if (model > 0) {
                    String.format(Locale.US, "%.1f/%.1fG 模型 %.1fG", usedGb, totalGb, modelGb)
                } else {
                    String.format(Locale.US, "%.1f/%.1fG", usedGb, totalGb)
                }
        }
    }

    /** 濠电姷鏁告慨鐑藉极閹间礁纾婚柣鎰惈缁犱即鏌熼梻瀵割槮缂佺姷濞€閺岀喖鎮ч崼鐔哄嚒缂備胶濮甸悧鏇㈠煘閹达附鍋愰柛娆忣槹閹瑩鏌ｉ悢鍝ユ嚂缂佺姵鎹囬悰顕€寮介銏犵亰闂佺绻愰ˇ顖涚妤ｅ啯鐓欓柟顖滃椤ュ鎲搁悧鍫㈡噰婵﹥妞藉畷顐﹀礋閸倣銏ゆ⒑閸濆嫭鍣虹紒顔兼捣閸掓帒鈻庨幘宕囶啋闁荤姴娲╃亸娆撳储閻戞绡€闂傚牊绋戦埀顒佹倐楠炲鏁撻悩鑼煣濡炪倖甯掗崐鐢稿磻閹捐绀傚璺猴梗婢规洟姊绘担鍛婂暈婵炶绠撳畷鎴﹀川鐎涙ê浠ч梺姹囧灩閹诧繝鍩涢幋锔藉仩婵炴垶宸婚崑鎾诲礂閸涱収妫滈梻鍌氬€峰ù鍥敋閺嶎厼鍨傞幖娣妼缁€鍐煃閸濆嫬鈧綊顢欓弮鍫熲拻?*/
    private fun cleanMemory() {
        val beforeMb = availMemBytes() / 1024 / 1024
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        val self = packageName
        runCatching {
            val pm = packageManager
            pm.getInstalledApplications(0).forEach { app ->
                if (app.packageName != self) {
                    am.killBackgroundProcesses(app.packageName)
                }
            }
        }
        System.gc()
        modelScope.launch {
            kotlinx.coroutines.delay(600)
            runOnUiThread {
                modelMemBytes = 0L
                updateMemoryBar()
                val afterMb = availMemBytes() / 1024 / 1024
                val freed = (afterMb - beforeMb).coerceAtLeast(0)
                Toast
                    .makeText(
                        this@MainActivity,
                        if (freed > 0) "\u5DF2\u6E05\u7406 ${freed} MB" else "\u5DF2\u6E05\u7406\u540E\u53F0\u8FDB\u7A0B",
                        Toast.LENGTH_SHORT,
                    ).show()
            }
        }
    }

    /** 婵犵數濮烽弫鎼佸磻閻愬搫绠板┑鐘崇閸庡秵绻濇繝鍌滃妤犵偑鍨虹换娑㈠幢濡櫣浠存俊銈囧Т鐎氭澘顫忕紒妯诲闁告稑锕ラ崕鎾剁磽娴ｅ壊妲告い鏇ㄥ弮楠炴垿濮€閻樻牗妫冨畷顏呮媴鐟欏嫭鐝﹂梻鍌欑閹碱偄煤閵娾晛绐楅幖杈剧稻椤愪粙鏌ｉ幇顔煎妺闁绘挻娲熼弻鏇熷緞濡儤鐏堟繝鈷€灞芥珝闁诡喕绮欓、娑橆潩閹插绀婄紓?+ 闂傚倸鍊搁崐宄懊归崶顒婄稏濠㈣泛顑囬々鎻捗归悩宸剰缂佲偓婢舵劗鍙撻柛銉ｅ妽鐏忕敻鏌￠崱顓㈡濞ｅ洤锕、娑橆煥閸涱厾顐煎┑鐐茬摠閸ゅ酣宕愬┑瀣摕闁绘柨鍚嬮悞浠嬫煥閺囨浜鹃梺璇茬箻娴滃爼寮婚敓鐘茬劦妞ゆ帊鑳堕々鐑芥倵閿濆骸浜為柛妯绘倐濮婅櫣绮欓幐搴㈡嫳缂備緡鍠栫粔鐟扮暦閻㈢鐒垫い鎺戝€荤壕?*/
    private fun updateDeviceInfo() {
        val soc =
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                android.os.Build.SOC_MODEL ?: android.os.Build.HARDWARE
            } else {
                android.os.Build.HARDWARE
            }
        val cores = Runtime.getRuntime().availableProcessors()
        val totalGb = totalMemBytes() / 1024.0 / 1024.0 / 1024.0
        val availGb = availMemBytes() / 1024.0 / 1024.0 / 1024.0
        val stat = android.os.StatFs(filesDir.absolutePath)
        val freeGb = stat.availableBytes / 1024.0 / 1024.0 / 1024.0
        val profile = DeviceProfiles.evaluate(soc, cores, totalGb)
        runOnUiThread {
            binding.tvDeviceName.text =
                "\u8BBE\u5907\uFF1A" + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
            binding.tvCpuModel.text = profile.cpu.detail
            binding.tvGpuModel.text = profile.gpu.detail
            binding.tvNpuModel.text = if (profile.npu.available) profile.npu.detail else "\u65E0"
            binding.tvRecommend.text =
                String.format(
                    Locale.US,
                    "%s - %.1f tok/s",
                    profile.best.name,
                    profile.best.tokensPerSecond,
                )
        }
    }

    private fun npuNameFor(soc: String): String? {
        val s = soc.lowercase()
        return when {
            s.contains("sm8750") || s.contains("sun") || s.contains("8 elite") -> "Hexagon v79"
            s.contains("sm8650") || s.contains("pineapple") || s.contains("8 gen 3") -> "Hexagon v75"
            s.contains("sm8550") || s.contains("kalama") || s.contains("8 gen 2") -> "Hexagon v73"
            s.contains("sm8475") || s.contains("cape") || s.contains("8+ gen 1") -> "Hexagon v69"
            s.contains("mt") || s.contains("dimensity") -> "APU（MediaTek）"
            else -> null
        }
    }

    private fun showDownloadFailedDialog() {
        runOnUiThread {
            binding.llDownloading.visibility = View.GONE
            runCatching { downloadJob?.cancel() }
            AlertDialog
                .Builder(this)
                .setTitle(R.string.download_failed_title)
                .setMessage(R.string.download_failed_body)
                .setPositiveButton(R.string.ok, null)
                .show()
        }
    }

    /** 闂傚倸鍊风粈渚€骞栭位鍥敃閿曗偓閻ょ偓绻涢幋鐐╂（婵炲樊浜濋弲婵嬫煕鐏炵偓鐨戞い鏂挎嚇濮婃椽妫冨☉姘暫濡炪倧缂氶崡瀹犳＂闂佸壊鍋侀崕鏌ユ偂閻斿吋鐓欓梺顓ㄧ畱婢у鏌涢妶鍥ф灈闁?UTF-16/UTF-8 闂傚倸鍊峰ù鍥敋瑜忛埀顒佺▓閺呮繄鍒掑▎鎾崇婵＄偛鐨烽崑鎾诲礃椤斿ジ鍞堕梺闈涱樈閸犳寮查悙瀵哥闁圭偓娼欓悞褰掓煕鐎ｎ偅宕岄柡灞稿墲閹峰懘宕妷鎰╁€濋弻宥堫檨闁告挻姘ㄧ划娆撳箳濡も偓缁犵偟绱?闂傚倸鍊峰ù鍥敋瑜旈弻濠囨晲閸滀胶鍔烽悷婊冪箳濡叉劙鎮欑€靛摜鐦堥梺绋胯閸婃宕濆畡閭︽富闁靛牆鎳愮粻浼存倵濮樷偓閸パ冨殤?Modified UTF-8 婵犵數濮烽弫鎼佸磻閻愬樊鐒芥繛鍡樻尭鐟欙箓鎮楅敐搴濇喚婵炲皷鏅犻弻鏇熺箾閸喖澹勫┑鐐叉▕娴滄粓鏌ㄩ妶鍡曠箚闁靛牆鍊告禍鍓х磽?abort */
    private fun sanitizeForSdk(s: String): String {
        if (s.isEmpty()) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) {
                    sb.append(c).append(s[i + 1])
                    i += 2
                } else {
                    i++
                }
                continue
            }
            if (Character.isLowSurrogate(c)) {
                i++
                continue
            }
            if (c < ' ' && c != '\n' && c != '\t') {
                i++
                continue
            }
            sb.append(c)
            i++
        }
        return String(sb.toString().toByteArray(Charsets.UTF_8), Charsets.UTF_8)
    }
    private fun onMessageLongClick(position: Int) {
        enterSelection(position)
    }

    private fun onMessageClick(position: Int) {
        if (selectionMode) toggleSelection(position)
    }

    /** 闂傚倸鍊搁崐椋庣矆娓氣偓楠炲鏁嶉崟顒佹闂佺粯鍔曢顓犵不妤ｅ啯鐓冪憸婊堝礈濮樿鲸宕叉繛鎴欏灩瀹告繃銇勯幘鍗炵仼鐎殿喗婢樿灃闁绘﹢娼ф禒婊呯磼婢跺﹦绉虹€规洘妞介崺鈧い鎺嶉檷娴滄粓鏌熼悜妯虹仴妞ゅ繆鏅犻幃妤€顫濋澶嬓﹀銈庡幖濞硷繝骞婂鍫燁棃婵炴垶锕╁濠囨⒒娴ｄ警鐒炬い鎴濇楠炴劗鎷犲顔兼闂佸憡娲﹂崜娆戞閻愬搫绠圭紒顔煎帨閸嬫挸鐣烽崶鈺傛闂傚倸鍊搁崐鐑芥倿閿曞倹鍎戠憸鐗堝笒缁€澶屸偓鍏夊亾闁逞屽墴閸┾偓妞ゆ帊绀侀崵顒勬煕濡搫鈷旈柛鎺撳浮瀵噣宕奸悢灏佸亾婵犳碍鐓㈡俊顖欒濡叉椽鏌ｉ敐鍛毐妞ゎ亜鍟存俊鍫曞幢濞嗗繆鍙洪梻浣侯焾閿曘儵銆冮崼銏犲灊闁割偆鍠庣欢鐐烘倵閿濆懐浠涙い鏃€妫冨铏圭磼濡搫顫戝┑鐐差槹閻╊垶骞冮敓鐘茬疀妞ゆ柨澧介敍婊堟⒑閸濆嫬鈧綊顢栧▎鎾村亗濠靛倸鎲￠悡娆愩亜閺冨洤浜归柛鈺嬬悼閳ь剝顫夊ú鏍х暦椤掑啰浜欓梻浣告啞閸斿繘寮插鍫熷仾濞达絿纭堕弨浠嬫煟閹邦剚鈻曢柛銈囧枎閳规垿顢涢敐鍛睄閻庤娲樼划宀勫煡婢跺á鐔兼煥鐎ｎ兛绨撮梻鍌欒兌椤㈠﹪骞撻鍡欎笉闁瑰濮锋稉宥吤归悡搴ｆ憼闁绘挻娲熼弻鏇熷緞濞戞艾鍩屾繝纰樷偓铏仴闁哄本鐩俊鎼佸Ψ閿濆懍鍒掗梻浣筋嚃閸犳稑鈻斿☉銏犵叀濠㈣泛谩閻斿摜鐟规い鏍ㄤ緱閸炶尙绱撻崒姘偓鐑芥倿閿曞倶鈧啴宕ㄩ弶鎴濈彉濡炪倖宸婚崑鎾淬亜椤撶偞澶勭紒缁樼箓椤繈顢楅崒婊勬緫濠碉紕鍋戦崐鏍偋椤撶姴绶ゅΔ锝呭暙濡﹢鏌嶈閸撶喎顫忕紒妯诲闁告稑锕ラ崕鎾绘⒑閸濄儱娅忛柛銊ゅ嵆閺佸啴濮€閵堝懏宓嶅銈嗘尵婵兘鎮鹃崼鏇熲拺缂佸瀵у﹢鎵磼鐎ｎ偅宕岄柛鈹垮灲瀵噣宕奸悢灏佸亾閻㈠憡鐓ユ繝闈涙椤庢霉濠婂嫮绠為柡?*/
    private fun deleteSelectedMessages() {
        if (selectedIdx.isEmpty()) return
        val doomed = sortedSetOf<Int>()
        selectedIdx.forEach { i ->
            if (i in messages.indices) {
                doomed.add(i)
                if (i + 1 < messages.size && messages[i + 1].type == MessageType.PROFILE) doomed.add(i + 1)
            }
        }
        doomed.sortedDescending().forEach { if (it in messages.indices) messages.removeAt(it) }

        chatList.clear()
        savedTurns.clear()
        messages.forEach { m ->
            when (m.type) {
                MessageType.USER -> {
                    chatList.add(ChatMessage("user", m.content))
                    savedTurns.add(SavedMessage("user", m.content))
                }

                MessageType.ASSISTANT -> {
                    chatList.add(ChatMessage("assistant", m.content))
                    savedTurns.add(SavedMessage("assistant", m.content))
                }

                MessageType.PROFILE -> savedTurns.add(SavedMessage("profile", m.content))

                MessageType.REFS -> savedTurns.add(SavedMessage("refs", m.content))

                else -> Unit
            }
        }
        saveCurrentSession()
        reloadRecycleView()
        refreshTokenStats()
        updateResendButton()
        exitSelection()
    }

    override fun onBackPressed() {
        if (selectionMode) {
            exitSelection()
            return
        }
        if (tokenPopup?.isShowing == true) {
            tokenPopup?.dismiss()
            return
        }
        if (maxOutputPopup?.isShowing == true) {
            maxOutputPopup?.dismiss()
            return
        }
        if (binding.drawerLayout.isDrawerOpen(Gravity.START)) {
            binding.drawerLayout.closeDrawer(Gravity.START)
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }
    /** 闂傚倸鍊搁崐鎼佸磹閻戣姤鍊块柨鏇氶檷娴滃綊鏌涢幇鍏哥敖闁活厽鎹囬弻鐔虹磼閵忕姵鐏堢紓浣哄Х婵炩偓闁哄被鍔戦幃銏ゅ传閸曟垯鍨介幃宄扳枎濞嗗浚娲梺閫炲苯澧紒鐘茬Ч瀹曟洘娼忛埞鎯т壕婵鍘ч弸銈囩磼椤曞棛鍒伴摶鏍煕濞戝崬鏋涘ù?*/
    private fun showRenameDialog(session: ChatSession) {
        val density = resources.displayMetrics.density
        val input =
            android.widget.EditText(this).apply {
                setText(session.title)
                setSelection(session.title.length)
                setPadding((20 * density).toInt(), (12 * density).toInt(), (20 * density).toInt(), (12 * density).toInt())
            }
        AlertDialog
            .Builder(this)
            .setTitle(R.string.rename_session)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = input.text.toString().trim().ifEmpty { session.title }
                val idx = sessions.indexOfFirst { it.id == session.id }
                if (idx >= 0) {
                    sessions[idx] = sessions[idx].copy(title = name)
                    SessionStore.save(this, sessions)
                }
                refreshSessionList()
            }.setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 闂傚倸鍊风粈浣革耿闁秴鍌ㄧ憸鏃堝箖濞差亜惟闁靛鍟浠嬪箖閵忋倖鍋傞幖杈剧秶缁辩敻姊绘担鍛婅础闁惧繐閰ｅ畷浼村冀椤愩倓绗夐梺姹囧灩閹诧繝鎮″▎鎾粹拺妞ゆ挶鍔庨悾閬嶆煛鐎ｎ偅鐓ラ棁澶愭煟濡櫣锛嶆繛鍙夋尦閺岋紕浠︾拠鎻掝瀳闂佸疇妫勯ˇ顖炲煝鎼淬劌绠抽柟鎼幗椤ャ倝姊婚崒娆戝妽闁活亜缍婂畷娲礋椤栨氨锛涙繝鐢靛Т閸婂寮抽敃鍌涚厱妞ゆ劧绲剧粈鍐煕婵犲洦鏁遍柕鍥у楠炴帡骞嬪┑鍐ㄤ壕闁归棿鐒﹂崐鍫曟⒑椤掆偓缁夌敻鍩涢幋锔解拻闁割偆鍠撻埊鏇熴亜閵夛妇鎳囬柡灞诲€濆畷姗€鎳犻鈧悡鐔兼倵鐟欏嫭绀冮柣鐔叉櫊楠炲﹪鎮欓崫鍕庛劍銇勯弮鈧崹婵堝垝椤栫偞鈷掑ù锝囧劋閸も偓闂佸鏉垮闁瑰箍鍨藉畷鐔碱敆閸屾鏁垫繝鐢靛仜濡﹥绂嶅┑瀣亗闁靛濡囩粻楣冩煙鐎电鈧牠骞嬪┑鎾光偓鍨归悡搴ｆ憼闁稿缍侀弻鐔兼焽閿旇法鐩庨悗瑙勬尭濡盯鍩€椤掑喚娼愭繛鍙夛耿閵嗗啯绻濋崘褏绠氶梺缁樺灱濡嫬鏁梻浣瑰濞插秹宕戦幘缁樼厓鐟滄粓宕滃☉銏犳瀬濠电姵鑹鹃拑鐔兼煏閸繃顥犻梻鍕椤法鎹勬笟顖浶ㄩ梺鐑╂櫅鐎涒晝鎹㈠┑鍡忔灁闁割煈鍠楅悘鈧梻浣告惈閹冲繒绮欓弽顑句汗濞撴埃鍋撴慨濠冩そ閹瑩鎸婃径濠傤潛闂備礁鎽滈崰搴ㄦ倶濠靛鏆板┑鐘灱閸╂牠宕濋弽顓熷亗闁靛濡囩粻楣冩煙鐎甸晲绱虫い蹇撶墑閳ь剙鍟埢搴ㄥ箣閻樼绱叉繝寰锋澘鈧劙宕戦幘娣簻闁挎洖鍊瑰☉褎銇勯銏㈢闁硅櫕鐗犻崺锟犲磼濮樺崬閰遍梺璇叉捣閹虫挻绔熸繝鍥х獥婵°倕鎳庡Ч鎻掋€掑锝呬壕濠殿喖锕ュ浠嬪箖閻戣棄绠ユい鏃€妲掑鍝ョ磽閸屾瑧顦︽い锔诲灣婢规洟顢橀悩鎰佹綗?*/
    private fun showCenterHint(text: String) {
        val view = binding.llHint
        binding.tvHintText.text = text
        view.animate().cancel()
        view.alpha = 0f
        view.visibility = View.VISIBLE
        view.animate().alpha(1f).setDuration(160).start()
        view.removeCallbacks(hideHintRunnable)
        view.postDelayed(hideHintRunnable, 1800)
    }

    private val hideHintRunnable =
        Runnable {
            val view = binding.llHint
            view.animate().alpha(0f).setDuration(220).withEndAction { view.visibility = View.GONE }.start()
        }
    private fun saveCurrentSession() {
        if (savedTurns.isEmpty()) {
            if (currentSessionId.isNotEmpty()) {
                val idx = sessions.indexOfFirst { it.id == currentSessionId }
                if (idx >= 0 && sessions[idx].turns.isEmpty()) {
                    sessions.removeAt(idx)
                    SessionStore.save(this, sessions)
                    refreshSessionList()
                }
            }
            return
        }
        if (currentSessionId.isEmpty()) {
            currentSessionId = java.util.UUID.randomUUID().toString()
        }
        var idx = sessions.indexOfFirst { it.id == currentSessionId }
        if (idx < 0) {
            sessions.add(
                ChatSession(
                    id = currentSessionId,
                    title = "\u65B0\u5BF9\u8BDD",
                    updatedAt = System.currentTimeMillis(),
                    turns = emptyList(),
                ),
            )
            idx = sessions.size - 1
        }
        val firstUser = savedTurns.firstOrNull { it.role == "user" }?.content
        val title =
            when {
                !firstUser.isNullOrBlank() -> firstUser.take(18)
                else -> "新对话"
            }
        sessions[idx] = sessions[idx].copy(turns = savedTurns.toList(), title = title, updatedAt = System.currentTimeMillis())
        SessionStore.save(this, sessions)
        refreshSessionList()
        refreshTokenStats()
    }

    /** 闂傚倸鍊峰ù鍥х暦閸偅鍙忛柡澶嬪殮濞差亜鐓涢柛婊€鐒﹂弲顏堟偡濠婂嫬鐏村┑锛勬暬楠炲洭寮剁捄銊モ偓鐐烘偡濠婂嫮鐭掔€规洦鍨跺畷銊р偓娑欙供濡懎顪冮妶鍡楀Е闁稿瀚伴悰顔嘉熼懖鈺冿紲闂佺粯顭堟禍顒勬儗濞嗘挻鐓欐い鏃傜摂濞堟棃鏌嶉挊澶樻Ц闁宠楠歌灒闁绘挸楠告婵犵數濮烽弫鎼佸磻濞戙垺鍋嬪┑鐘叉搐閸ㄥ倿鏌涢幘鑼跺厡闁活厼顦甸弻鐔兼倻濮楀棙鐣烽梺鍝勬噺閻擄繝寮诲☉銏犖ㄩ柨婵嗘噹椤牓姊洪柅鐐茶嫰婢т即鏌涚€ｃ劌鈧繂顕ｇ拠娴嬫闁靛繆鍓濆▍鍡涙⒑閸忛棿鑸柛搴㈠▕瀹曟瑩鍨鹃弬銉︽杸?*/
    private fun savedProxy(): String =
        getSharedPreferences("geniex_ui", MODE_PRIVATE).getString("net_proxy", "") ?: ""

    /** 闂傚倸鍊搁崐鐑芥嚄閸洏鈧焦绻濋崒妤佺亙濠电偞鍨崹娲疾濠靛鐓ユ繝闈涙－濡叉悂鏌ゅ宀婃綈闁靛洤瀚伴獮鎺楀箣濠垫劒鐥梻浣侯焾椤戝洦绻涢埀顒勬煛鐏炲墽娲撮柛鈺佸瀹曟﹢鎮欓鍌涙闂傚倷绀侀幖顐﹀箠韫囨稑绠栭柛宀€鍋涢拑鐔哥箾閹寸偟鐭岄柣銈傚亾闂佽鍑界紞鍡樼濠婂嫭鍙忛柕鍫濇川绾捐棄霉閿濆牊顏犻悽顖涚⊕閵囧嫰顢橀悙鏉戠獩闂佸憡甯楃敮鎺楀煝鎼淬劌绠ｉ柡鍌氥仒婢规洟姊洪幐搴ｇ畵濡ょ姴鎲￠弲鍫曨敊閸撗咃紲闂佺粯锚閻ゅ洦绔熷Ο姹囦簻妞ゆ挴鈧啿濮峰銈忛檮閻擄繝寮诲☉銏″亞濞达綁鏅查崙浠嬫⒑闂堟稒澶勯柛鏃€鐗曢銉╁礋椤栨氨鐤€濡炪倖鎸炬慨瀵告暜閹剧粯鈷掑┑鐘查娴滄粍绻涚仦鍌氱伈鐎规洘娲熼獮搴ㄦ偩鐏炵晫銈︽俊鐐€х徊楣冨绩椤ょve 婵犵數濮烽弫鎼佸磻閻愬搫鍨傞柛顐ｆ礀缁犱即鏌熼梻瀵歌窗闁轰礁瀚伴弻娑㈩敃閿濆洩绌块悷婊呭鐢帡鎮欐繝鍐︿簻闁瑰搫妫楁禍楣冩⒒閸屾艾浜為柛銊ョ埣瀵寮撮悢铏圭槇闁硅壈鎻徊鐣屾暜閵夆晜鈷戦柟鑲╁仜閳ь剚鍨垮畷瑙勭鐎ｎ亣鎽?HTTP(S)_PROXY / ALL_PROXY */
    private fun applyProxyEnv(raw: String) {
        val value = raw.trim()
        runCatching {
            if (value.isEmpty()) {
                android.system.Os.unsetenv("HTTP_PROXY")
                android.system.Os.unsetenv("HTTPS_PROXY")
                android.system.Os.unsetenv("ALL_PROXY")
            } else {
                val p = if (value.startsWith("http://") || value.startsWith("https://")) value else "http://$value"
                android.system.Os.setenv("HTTP_PROXY", p, true)
                android.system.Os.setenv("HTTPS_PROXY", p, true)
                android.system.Os.setenv("ALL_PROXY", p, true)
            }
            android.system.Os.setenv("NO_PROXY", "127.0.0.1,localhost", true)
            android.system.Os.setenv("no_proxy", "127.0.0.1,localhost", true)
        }
        Log.i(TAG, "proxy env applied: [$value]")
    }

    /** 缂傚倸鍊搁崐鎼佸磹閹间礁纾归柟闂寸閻ゎ喗銇勯弽顐粶闁搞劌鍊块弻娑㈡晜鐠囨彃绠归梺鎼炲妽缁诲牓寮婚妶澶婁紶闁靛闄勫В鍕渻閵堝倹娅囬柛蹇旓耿瀵濡搁妷銏℃杸闂佺硶鍓濋敋妞わ腹鏅犲铏圭矙閸栤€冲闂佺顑冮崐婵嬬嵁閸愵亝鍠嗛柛鏇ㄥ墮椤庢挾绱撴担鍓插剰缂併劑浜堕獮鎰板礃椤旇В鎷哄┑顔炬嚀濞层倝鍩€椤掍焦绀€閾荤偞淇婇妶鍛闁轰礁顑夐弻銊モ攽閸♀晜笑闂佸湱顢婇崺鏍Φ閸曨垰绠婚悹铏规磪閵忋倖鐓?*/
    private fun showProxyDialog() {
        val density = resources.displayMetrics.density
        val input =
            android.widget.EditText(this).apply {
                setText(savedProxy())
                setSelection(text.length)
                hint = "127.0.0.1:7890"
                setPadding((20 * density).toInt(), (12 * density).toInt(), (20 * density).toInt(), (12 * density).toInt())
            }
        AlertDialog
            .Builder(this)
            .setTitle(R.string.proxy_title)
            .setMessage(R.string.proxy_hint)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val v = input.text.toString().trim()
                getSharedPreferences("geniex_ui", MODE_PRIVATE).edit().putString("net_proxy", v).apply()
                applyProxyEnv(v)
                Toast.makeText(this, R.string.proxy_saved, Toast.LENGTH_LONG).show()
            }.setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 婵犵數濮烽弫鎼佸磻閻愬搫鍨傞柛顐ｆ礀缁犱即鏌熼梻瀵稿妽闁哄懏绻堥弻鏇熷緞閸℃ɑ鐝﹂梺杞扮閸燁偊鍩為幋锕€纾兼繝濠傛捣閸旀悂姊虹紒妯哄闁挎洦浜濠氭晲婢跺﹦鐤€濡炪倖妫佹慨銈呅掗崟顖涒拺缁绢厼鎳庤濠电偠灏欓崰鏍嵁閸愵亝宕夊〒姘煎灡閸嶇敻姊虹紒妯诲碍缂併劏鍋愰懞閬嶆偨閸涘ň鎷洪梺缁樻尭濞撮攱绂掑☉銏＄厱闁靛鍎虫禒銏°亜閺囶亞绉い銏☆殜瀹曠喖宕归锝嗙彎闂傚倷鑳剁划顖炲蓟閵婏妇鐝跺┑鐘蹭迹閿濆鏁嶉柣鎰ˉ閹锋椽姊洪崷顓х劸婵炲鍏橀崺銉﹀緞閹邦厾鍘搁梺鍓插亽閸嬪嫭鏅堕鈧幗鍫曟晲閸滀胶鍞甸柣鐘荤細濞咃綁鎮橀柆宥嗙厽妞ゆ挾鍋為ˉ銏ゆ煛瀹€鈧崰鏍€佸鈧幃銏犵暋閻楀牏绉归梻鍌欒兌椤牓顢栭崱娑樼闁归棿鐒﹂崑鈺傜節闂堟侗鍎忕紒鈧€ｎ偁浜滈柟鏉垮缁夐潧霉閻樻瑥娲﹂埛鎺懨归敐鍛暈闁诡垰鐗撻弻娑欑節閸曨剙娈屽銈庡幖閻忔繆鐏掗柣鐔堕檷閸婃挸效濡ゅ懏鈷戠紒瀣硶閻忛亶鏌涚€ｎ剙浠遍柟顔惧仧閹风姷鎲楁担鍝ョШ闁轰焦鍔欏畷鍫曟嚋閸偆妲ｉ梻鍌欒兌椤牓顢栭崱娑樼闁归棿绶￠弫鍥р攽閸屾粠鐒炬鐐灪娣囧﹪濡堕崨顓熸闂佸疇顕ч柊锝咁潖?*/
    private fun updateResendButton() {
        if (isGenerating) {
            binding.btnResend.visibility = android.view.View.GONE
            return
        }
        val last = savedTurns.lastOrNull()
        val show = last != null && last.role == "user" && last.content.isNotBlank()
        binding.btnResend.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
    }

    /** 闂傚倸鍊搁崐鐑芥嚄閸洏鈧焦绻濋崒妤佺亙濠电偞鍨崹娲疾濠靛鐓忓┑鐐靛亾濞呮梻绱撳鍡欏⒌闁哄被鍔戝顕€宕掑☉娆戝涧闂備礁纾划顖氼潖瑜版帒桅闁告洦鍨扮粻濠氭偣閾忕懓鍔嬮柣搴ㄧ畺濮婅櫣绱掑Ο铏圭憪缂備緡鍠楅悷鈺侇嚕婵犳碍鏅插璺好￠埡鍛厪濠㈣泛鐗嗛崝鏉懨归悡搴℃殻婵﹨娅ｇ划娆忊枎閹冨闂備胶顢婂▍鏇㈡晪濡炪倖娲╃紞浣哥暦婵傜鍗抽柣鎰版涧婵爼姊绘担鍛婂暈闁告梹鍨垮畷婵囨償閵娿儳锛欓梺鍦劋椤ㄥ棝鍩涢幒妤佺厱閻忕偞宕樻竟妯好瑰鍛槐闁哄本鐩幃鈺佺暦閸パ€鎷伴梻浣芥〃閻掞箓宕濆▎鎾崇畺婵炲棙鎸告导鐘绘煕閺囥劌骞栭柍褜鍓涢崑銈咁潖閾忓湱纾兼俊顖氭惈椤本绻濋埛鈧崘鈺傚闯缂備浇椴搁悷锔炬崲濠靛棭娼╂い鎾跺Т楠炲牓姊绘繝搴′簻婵炲眰鍊濋、鏍川閺夋垹顦┑鐘诧工鐎氥劍绂嶅鍫熺厪闊洦娲栭宀勬煕鎼淬垺顥堥柡灞稿墲閹峰懘宕妷鎰╁€曢湁闁绘瑥鎳愰悾鐢告煙閾忣偆澧甸柛鈺冨仱閹瑩顢楁担鍦絻婵犵數濮烽弫鎼佸磻濞戙垺鍋ら柕濞у啫鐏婇悗鍏夊亾闁告洦鍓涢崢鎰版⒑閹稿海绠撴い锔诲灦濮婂顢涢悙瀵稿幍闂傚倸鍊搁顓⑺囬敃鍌涚厽?*/
    private fun resendLastQuestion() {
        val lastIdx = savedTurns.size - 1
        val last = savedTurns.getOrNull(lastIdx)
        if (last == null || last.role != "user") {
            binding.btnResend.visibility = android.view.View.GONE
            return
        }
        val text = last.content
        // The attachment of this turn is the "images" record right before it.
        // After the app is killed the bitmaps are gone, but the files are still
        // on disk, so re-attach them and a resend keeps the original picture.
        val imgsTurn = savedTurns.getOrNull(lastIdx - 1)?.takeIf { it.role == "images" }
        val restored =
            imgsTurn?.content
                ?.split("|")
                ?.map { java.io.File(it) }
                ?.filter { it.exists() }
                .orEmpty()
        savedTurns.removeAt(savedTurns.size - 1)
        if (imgsTurn != null && savedTurns.isNotEmpty()) {
            savedTurns.removeAt(savedTurns.size - 1)
        }
        if (messages.isNotEmpty() && messages.last().type == MessageType.USER) {
            messages.removeAt(messages.size - 1)
        }
        if (restored.isNotEmpty() && messages.isNotEmpty() && messages.last().type == MessageType.IMAGES) {
            messages.removeAt(messages.size - 1)
        }
        if (chatList.isNotEmpty()) chatList.removeAt(chatList.size - 1)
        savedImageFiles.clear()
        if (restored.isNotEmpty()) {
            savedImageFiles.addAll(restored)
            refreshTopScrollContainer()
        }
        saveCurrentSession()
        reloadRecycleView()
        binding.etInput.setText(text)
        binding.etInput.setSelection(text.length)
        binding.btnResend.visibility = android.view.View.GONE
        refreshSendButtonState()
        val tip = if (restored.isNotEmpty()) R.string.resend_filled_image else R.string.resend_filled
        Toast.makeText(this, tip, Toast.LENGTH_SHORT).show()
        pinBottom = true
        scrollToBottom()
    }

    /** 闂傚倸鍊搁崐椋庣矆娓氣偓楠炲鏁撻悩鑼槱閻熸粎澧楃敮鎺楀垂閸岀偞鐓熸俊銈傚亾闁绘锕畷锝堢疀濞戞瑧鍘撻梺鍛婄箓鐎氼參宕抽崷顓犵＜妞ゆ劑鍨绘晥闂佽鍠栫紞濠傜暦椤愶箑绀嬫い鎺嶇閸撳灚淇婇悙顏勨偓銈夊磻閹惧墎绀婂ù锝呭閸ゆ洟鏌涢锝嗙闁稿﹦鍏橀弻鈩冨緞鐎ｎ亞浠兼繛瀛樼矋婢瑰棛妲愰幘瀛樺濞寸姴顑呴幗鍨節閵忋垺鍤€婵☆偅绻堥悰顔跨疀濞戞瑥浜瑰┑鐐存綑椤戝棝寮埀顒勬⒒娴ｈ櫣銆婇柛鎾寸箞閵嗗啴宕奸悢渚祫濠殿喗顭堥崺鏍偂閻斿吋鐓熼柟閭﹀墻閸ょ喖鏌嶇粭鍝勨偓婵嬪蓟濞戙垹妫橀悹鎭掑妿閸旑垶姊洪悷鎵紞闁哥姵鐗犻獮鍐閵堝棗浜楅柟鑹版彧缁插潡寮抽姀銈嗏拻濞撴埃鍋撴繛浣冲懏宕查柟鐑樻尭閸ㄦ梹銇勯幘璺衡偓锝夋晲閸℃瑧鐦堝┑顔斤供閸橀箖宕㈤幖浣光拺闁告挻褰冩禍婵嬫煠閸︻厼浜剧紒鍌氱У閵堬綁宕橀埡鍐ㄥ箰濠电姰鍨煎▔娑樜涘▎蹇婃瀺闁糕剝绋掗悡鏇㈡煏婵炲灝鍔ら柨娑樼Ф缁辨帡顢欑憴鍕彋閻庢鍠栭悥濂哥嵁鐎ｎ喖绫嶉柍褜鍓熼幃妤佺節濮橆厸鎷洪梺缁樺姌濡嫰宕濆鍫熺厱闁圭偓娼欓崫铏光偓瑙勬礈閺佸骞冮埡鍐＜婵☆垵妗ㄩ崠?*/
    private fun restoreHistory() {
        if (!pendingRestore) return
        pendingRestore = false
        val loadedRaw = SessionStore.load(this)
        val loaded =
            loadedRaw.map { s ->
                s.copy(turns = s.turns.map { t -> t.copy(content = repairByteLevel(t.content)) })
            }
        sessions.clear()
        sessions.addAll(loaded.filter { it.turns.isNotEmpty() })
        if (sessions.size != loaded.size) SessionStore.save(this, sessions)
        if (sessions.isEmpty()) {
            val legacy = ChatHistory.load(this)
            if (legacy.isNotEmpty()) {
                val migrated =
                    ChatSession(
                        id = java.util.UUID.randomUUID().toString(),
                        title = legacy.firstOrNull { it.role == "user" }?.content?.take(18) ?: "\u65B0\u5BF9\u8BDD",
                        updatedAt = System.currentTimeMillis(),
                        turns = legacy,
                    )
                sessions.add(migrated)
                SessionStore.save(this, sessions)
                ChatHistory.clear(this)
            }
        }
        if (sessions.isEmpty()) {
            startNewSession()
            return
        }
        refreshSessionList()
        val latest = sessions.maxByOrNull { it.updatedAt } ?: return
        loadSession(latest, closeDrawer = false)
    }

    private fun loadSession(
        session: ChatSession,
        closeDrawer: Boolean = true,
    ) {
        if (isGenerating) {
            modelScope.launch {
                runCatching {
                    if (isLoadVlmModel) vlmWrapper.stopStream() else llmWrapper.stopStream()
                }
            }
            isGenerating = false
            runOnUiThread {
                refreshSendButtonState()
                btnStop.visibility = android.view.View.GONE
            }
        }
        currentSessionId = session.id
        savedTurns.clear()
        savedTurns.addAll(session.turns)
        messages.clear()
        chatList.clear()
        vlmChatList.clear()
        session.turns.forEach { turn ->
            when (turn.role) {
                "user" -> {
                    messages.add(Message(turn.content, MessageType.USER))
                    chatList.add(ChatMessage("user", sanitizeForSdk(turn.content)))
                }

                "assistant" -> {
                    messages.add(Message(turn.content, MessageType.ASSISTANT))
                    chatList.add(ChatMessage("assistant", sanitizeForSdk(turn.content)))
                }

                "profile" -> {
                    messages.add(Message(turn.content, MessageType.PROFILE))
                }

                "refs" -> {
                    messages.add(Message(turn.content, MessageType.REFS))
                }

                "images" -> {
                    val files = turn.content.split("|").map { java.io.File(it) }.filter { it.exists() }
                    if (files.isNotEmpty()) messages.add(Message("", MessageType.IMAGES, files))
                }
            }
        }
        reloadRecycleView()
        refreshSessionList()
        refreshTokenStats()
        updateResendButton()
        if (closeDrawer) binding.drawerLayout.closeDrawer(Gravity.START)
        scrollToBottom()
    }

    private fun startNewSession() {
        saveCurrentSession()
        currentSessionId = ""
        savedTurns.clear()
        messages.clear()
        chatList.clear()
        vlmChatList.clear()
        reloadRecycleView()
        SessionStore.save(this, sessions)
        refreshSessionList()
        refreshTokenStats()
        updateResendButton()
        scrollToBottom()
        binding.drawerLayout.closeDrawer(Gravity.START)
    }

    /** 濠电姷鏁告慨鐑姐€傞挊澹╋綁宕ㄩ弶鎴濈€銈呯箰閻楀棝鎮為崹顐犱簻闁瑰搫妫楁禍鍓х磼閸撗嗘闁告瑥鍟村畷娲焵椤掍降浜滈柟鐑樺灥閳ь剚鎮傚畷銏ゅ箻椤旂晫鍘搁柣蹇曞仩婵倝宕抽搹鍏夊亾閸偅绶查悗姘煎櫍閸┾偓妞ゆ帒锕︾粔闈浢瑰鍡楃厫缂佸倹甯￠獮姗€顢欓挊澶嗗亾閸偆绠鹃柛顐ｇ箘娴犮垽鏌＄€ｎ偆鈯曠紒缁樼箞閸┾偓妞ゆ帒瀚悞鑲┾偓骞垮劚濡绂掗幒妤佲拺闂傚牊渚楀Σ鎾煛閸涱喚鐭掔€殿喗鐓￠、妤佹媴閻熸澘浼庢繝娈垮枟椤ㄥ懎螞濡や焦娅犳い鏃傛櫕缁犻箖鏌熼崘鎻掓Щ婵炶绠撻幃锟犲即閵忥紕鍘甸梺缁樺灦钃遍柟顖氬缁绘盯宕奸悢鍓叉闂佸搫鏈惄顖炵嵁鐎ｎ喖妫橀柛顭戝枓閹枫倗绱撻崒娆戭槮妞ゆ垵鎳樿棟闂侇剙绉撮悞鍨亜閹烘埊鍔熺紒澶屾暬閺屾稑螣閸忓吋鐝紓?/ 闂傚倸鍊搁崐椋庣矆娓氣偓楠炴牠顢曢妶鍌氫壕婵鍘ф晶顖炴煛閸涙澘鐓愮紒鍌涘笧閳ь剨缍嗛埀顒夊弿闂勫嫰骞堥妸銉庣喖骞愭惔锝冣偓鎰磽娴ｆ彃浜鹃梺绋跨灱閸嬬偤鎮¤箛鎾斀闁绘劙娼ф禍鐐箾閸涱厽鍤囬柡灞剧洴瀵剛鎷犻幓鎺戭潥婵＄偑鍊戦崝濠囧磿閻㈢绠栨繛鍡樺灦瀹曞鏌ц箛鏇熷殌閻㈩垰娼″缁樻媴閾忕懓绗￠梺缁橆殢閸樺墽妲愰悙瀛樺闁告縿鍎插▓鎯р攽閻愬弶顥為柟绋款煼瀹曟劗绱掑Ο鍦畾濡炪倖鐗楃换宥夊吹濞嗘垹纾奸柟鎻掝儔濡绢喚绱掔紒妯兼创妤犵偛顑夐幃娆撳级閹寸媭鏆″┑锛勫亼閸婃牠宕归悽鍛婂亱闁糕剝绋掔粻?*/
    private fun refsListOf(json: String): List<Pair<String, String>> =
        runCatching {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                o.optString("t") to o.optString("s")
            }
        }.getOrDefault(emptyList())

    /** 点「参考资料」：先列出命中的条目，再点进去看正文 */
    private fun onRefsClick(position: Int) {
        val msg = messages.getOrNull(position) ?: return
        val refs = refsListOf(msg.content)
        if (refs.isEmpty()) return
        val labels =
            refs.mapIndexed { i, (title, snip) ->
                if (snip.isBlank()) "${i + 1}. $title" else "${i + 1}. $title\n${snip.take(70)}"
            }.toTypedArray()
        AlertDialog
            .Builder(this)
            .setTitle(getString(R.string.refs_title, refs.size))
            .setItems(labels) { _, which -> showArticleDialog(refs[which].first) }
            .setNegativeButton(R.string.back, null)
            .show()
    }

    /** 从 ZIM 里取整篇正文 */
    private fun showArticleDialog(title: String) {
        val body = KnowledgeBase.article(title)
        if (body.isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.refs_empty, title), Toast.LENGTH_SHORT).show()
            return
        }
        val tv =
            TextView(this).apply {
                text = body
                textSize = 14f
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setTextIsSelectable(true)
            }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = android.widget.ScrollView(this).apply { addView(tv) }
        box.addView(
            scroll,
            LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                dp(420),
            ),
        )
        AlertDialog
            .Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(R.string.back, null)
            .show()
    }

    /** 过滤掉没有正文的命中：只有标题的参考资料会让模型自己编内容 */
    private fun groundRefs(hits: List<Pair<String, String>>): List<Pair<String, String>> =
        hits.mapNotNull { (title, snip) ->
            // 索引给的 snippet 常常只是标题本身，太短就不要，现取正文开头一段
            val ex = if (snip.length >= 80) snip.take(300) else KnowledgeBase.excerpt(title, 300)
            if (ex.isBlank()) null else title to ex
        }

    private fun refsBlockOf(grounded: List<Pair<String, String>>): String =
        if (grounded.isEmpty()) {
            ""
        } else {
            buildString {
                append("下面是与问题相关的维基百科资料，只用来帮助作答：\n")
                grounded.forEach { (title, ex) ->
                    append("· ").append(title).append("：").append(ex).append('\n')
                }
                append("\n请直接回答，不要复述这段资料的格式，也不要编造资料里没有的内容。\n\n")
            }
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 能力 / 参数 / 大小 / 内存 对照表，覆盖 model_list.json 里每个条目。 */
    private fun showModelInfoDialog() {
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(4), dp(8), dp(2))
            }

        fun cell(
            value: String,
            weight: Float,
            bold: Boolean = false,
            gray: Boolean = false,
        ): TextView =
            TextView(this).apply {
                text = value
                textSize = if (bold) 10f else 11f
                maxLines = 3
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(2), dp(6), dp(2), dp(6))
                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        weight,
                    )
                if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(if (gray) 0xFF777777.toInt() else 0xFF222222.toInt())
            }

        fun rowOf(
            values: List<Pair<String, Float>>,
            bold: Boolean = false,
            gray: Boolean = false,
        ): LinearLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                values.forEach { (v, w) -> addView(cell(v, w, bold, gray)) }
            }

        root.addView(
            rowOf(
                listOf(
                    "模型" to 1.75f,
                    "参数" to 0.58f,
                    "中文" to 0.6f,
                    "推理" to 0.6f,
                    "数学" to 0.6f,
                    "识图" to 0.6f,
                    "大小" to 0.82f,
                    "内存" to 0.82f,
                ),
                bold = true,
                gray = true,
            ),
        )
        root.addView(
            android.view.View(this).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(1),
                    )
                setBackgroundColor(0xFFDDDDDD.toInt())
            },
        )

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // 名字里看不出参数量的条目，这里补一下。
        val paramOverrides =
            mapOf(
                "granite-4.0-micro-GGUF" to 3.0,
                "Phi-4-mini-instruct-GGUF" to 3.8,
                "phi-4-GGUF" to 14.0,
            )
        modelList.forEach { md ->
            val params =
                paramOverrides[md.id]
                    ?: Regex("(\\d+(?:\\.\\d+)?)\\s*[Bb]\\b")
                        .find(md.displayName)?.groupValues?.get(1)?.toDoubleOrNull()
                    ?: 0.0
            val sizeGb = params * 0.62
            val memGb = if (sizeGb > 0) sizeGb + 1.2 else 0.0
            val score = scoresOf(md)
            // 整数参数量写成 14B 而不是 14.0B，窄列里才不会折行
            val paramText =
                when {
                    params <= 0 -> "-"
                    params == params.toInt().toDouble() -> String.format(Locale.US, "%.0fB", params)
                    else -> String.format(Locale.US, "%.1fB", params)
                }
            body.addView(
                rowOf(
                    listOf(
                        md.displayName.replace(" (GGUF)", "").replace(" (NPU)", "") to 1.75f,
                        paramText to 0.58f,
                        score[0].toString() to 0.6f,
                        score[1].toString() to 0.6f,
                        score[2].toString() to 0.6f,
                        (if (score[3] > 0) score[3].toString() else "-") to 0.6f,
                        (if (sizeGb > 0) String.format(Locale.US, "~%.1fG", sizeGb) else "-") to 0.82f,
                        (if (memGb > 0) String.format(Locale.US, "~%.1fG", memGb) else "-") to 0.82f,
                    ),
                ),
            )
        }

        val scroll =
            android.widget.ScrollView(this).apply {
                addView(body)
                descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                postDelayed({ scrollTo(0, 0) }, 120)
            }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                dp(400),
            ),
        )

        AlertDialog
            .Builder(this)
            .setTitle(R.string.model_info_title)
            .setView(root)
            .setPositiveButton(R.string.back, null)
            .show()
    }

    /**
     * 中文 / 推理 / 数学 / 识图 四项 1-10 的基线评分。
     * intArrayOf(中文, 推理, 数学, 识图)，识图 0 表示该模型不支持看图（表格里显示 -）。
     * 按各模型家族的公开评测人工估计，想调哪个直接改数字即可。
     */
    private val modelScores: Map<String, IntArray> =
        mapOf(
            "Qwen3-4B-Thinking-2507-GGUF" to intArrayOf(8, 8, 8, 0),
            "Qwen3-0.6B-GGUF" to intArrayOf(6, 4, 4, 0),
            "Qwen3-1.7B-GGUF" to intArrayOf(7, 5, 5, 0),
            "Ministral-3-3B-Instruct-2512-GGUF" to intArrayOf(5, 5, 5, 0),
            "granite-4.0-micro-GGUF" to intArrayOf(4, 4, 3, 0),
            "Phi-4-mini-instruct-GGUF" to intArrayOf(5, 6, 7, 0),
            "gemma-4-E2B-it-qat-q4_0-gguf" to intArrayOf(6, 6, 5, 0),
            "gemma-4-E4B-it-qat-q4_0-gguf" to intArrayOf(7, 7, 6, 0),
            "Qwen3.5-0.8B-GGUF" to intArrayOf(7, 6, 6, 6),
            "Qwen3.5-2B-GGUF" to intArrayOf(8, 7, 7, 7),
            "Qwen3-VL-2B-Instruct-GGUF" to intArrayOf(7, 6, 6, 7),
            "Qwen3-VL-4B-Instruct-GGUF" to intArrayOf(8, 7, 7, 8),
            "gpt-oss-20b-GGUF" to intArrayOf(6, 8, 7, 0),
            "Qwen3-4B" to intArrayOf(8, 7, 7, 0),
            "Qwen3-4B-Instruct-2507" to intArrayOf(8, 7, 7, 0),
            "Qwen2.5-VL-7B-Instruct" to intArrayOf(8, 7, 7, 8),
            "DeepSeek-R1-Distill-Qwen-7B-GGUF" to intArrayOf(8, 8, 8, 0),
            "DeepSeek-R1-Distill-Qwen-1.5B-GGUF" to intArrayOf(7, 6, 7, 0),
            "DeepSeek-R1-0528-Qwen3-8B-GGUF" to intArrayOf(8, 9, 8, 0),
            "DeepSeek-R1-Distill-Llama-8B-GGUF" to intArrayOf(6, 8, 7, 0),
            // 数学最强：R1 论文 MATH-500 93.9 / AIME24 69.7，这批里公开成绩最高
            "DeepSeek-R1-Distill-Qwen-14B-GGUF" to intArrayOf(8, 8, 9, 0),
            "Qwen2.5-7B-Instruct-GGUF" to intArrayOf(9, 7, 7, 0),
            "Qwen2.5-14B-Instruct-GGUF" to intArrayOf(9, 8, 8, 0),
            "Qwen2.5-Coder-7B-Instruct-GGUF" to intArrayOf(8, 6, 6, 0),
            "Qwen2.5-Coder-14B-Instruct-GGUF" to intArrayOf(8, 7, 7, 0),
            "phi-4-GGUF" to intArrayOf(5, 7, 8, 0),
            "gemma-3-4b-it-GGUF" to intArrayOf(6, 6, 5, 0),
            "Dolphin3.0-Llama3.2-1B-GGUF" to intArrayOf(3, 3, 3, 0),
            "Dolphin3.0-Qwen2.5-1.5B-GGUF" to intArrayOf(5, 4, 4, 0),
            "Dolphin3.0-Llama3.2-3B-GGUF" to intArrayOf(4, 5, 5, 0),
            "Dolphin3.0-Qwen2.5-3b-GGUF" to intArrayOf(6, 5, 5, 0),
            "dolphin-2.8-mistral-7b-v02-GGUF" to intArrayOf(5, 6, 5, 0),
            "Qwen2.5-Coder-7B-Instruct-abliterated-GGUF" to intArrayOf(7, 6, 6, 0),
            "dolphin-2.9-llama3-8b-GGUF" to intArrayOf(5, 6, 5, 0),
            "Dolphin3.0-Llama3.1-8B-GGUF" to intArrayOf(5, 7, 6, 0),
            "Qwen2.5-Coder-14B-Instruct-abliterated-GGUF" to intArrayOf(8, 7, 7, 0),
        )

    private fun scoresOf(md: ModelData): IntArray = modelScores[md.id] ?: intArrayOf(5, 5, 5, 0)

    private fun showModelPicker(downloadedOnly: Boolean) {
        modelScope.launch {
            val rows = modelList.map { md -> Pair(md, isModelDownloaded(md)) }
                .filter { !downloadedOnly || it.second }
            runOnUiThread {
                if (rows.isEmpty()) {
                    Toast.makeText(this@MainActivity, "没有可选的模型，请先下载", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val labels =
                    rows.map { (md, dl) ->
                        val short = md.displayName.replace(" (GGUF)", "").replace(" (NPU)", "")
                        if (dl) "$short  已下载" else "${md.displayName}   未下载"
                    }.toMutableList()
                if (!downloadedOnly) labels.add("删除已下载的模型…")
                if (!downloadedOnly) labels.add(getString(R.string.proxy_menu))
                val titleRow =
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setPadding(dp(20), dp(12), dp(6), dp(4))
                    }
                titleRow.addView(
                    TextView(this@MainActivity).apply {
                        text = if (downloadedOnly) "选择模型" else "选择或下载模型"
                        textSize = 18f
                        setTextColor(0xFF1F1F1F.toInt())
                        layoutParams =
                            LinearLayout.LayoutParams(
                                0,
                                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                                1f,
                            )
                    },
                )
                titleRow.addView(
                    TextView(this@MainActivity).apply {
                        text = getString(R.string.model_info)
                        textSize = 13f
                        setTextColor(0xFF1E6FFF.toInt())
                        setPadding(dp(10), dp(8), dp(10), dp(8))
                        isClickable = true
                        setOnClickListener { showModelInfoDialog() }
                    },
                )
                AlertDialog
                    .Builder(this@MainActivity)
                    .setCustomTitle(titleRow)
                    .setItems(labels.toTypedArray()) { _, i ->
                        if (i == rows.size) {
                            showDeletePicker()
                            return@setItems
                        }
                        if (i == rows.size + 1) {
                            showProxyDialog()
                            return@setItems
                        }
                        val (md, downloaded) = rows[i]
                        if (downloaded) {
                            switchModel(md)
                        } else {
                            startDownload(md)
                        }
                    }.setNegativeButton(R.string.back, null)
                    .show()
            }
        }
    }

    /** 闂傚倸鍊搁崐椋庣矆娓氣偓楠炲鏁嶉崟顒佹闂佺粯鍔曢顓犵不妤ｅ啯鐓冪憸婊堝礈濮樿鲸宕叉繛鎴欏灩瀹告繃銇勯幘鍗炵労闁逞屽墯椤ㄥ棝濡甸崟顖氭閻犳亽鍔庨鎺楁⒑缂佹ɑ鎯勯柛瀣躬閵嗕礁顫滈埀顒勫箖閳哄懎鍨傛い鎰╁灪濞堝姊婚崒娆戭槮闁圭⒈鍋婅棟闂侇剙绉寸壕濠氭煟濡寧鐝柛娆忕箻閺屾洟宕煎┑鍫濐暫闂佸搫妫涢崑銈咁潖?*/
    private fun showDeletePicker() {
        modelScope.launch {
            val rows = modelList.filter { isModelDownloaded(it) }
            runOnUiThread {
                if (rows.isEmpty()) {
                    Toast.makeText(this@MainActivity, "没有已下载的模型", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val labels = rows.map { it.displayName }.toTypedArray()
                AlertDialog
                    .Builder(this@MainActivity)
                    .setTitle("删除模型")
                    .setItems(labels) { _, i ->
                        val md = rows[i]
                        AlertDialog
                            .Builder(this@MainActivity)
                            .setMessage("确定要删除 ${md.displayName} 吗？模型文件会从本机移除。")
                            .setPositiveButton("删除") { _, _ -> deleteModel(md) }
                            .setNegativeButton("取消", null)
                            .show()
                    }.show()
            }
        }
    }

    private fun deleteModel(md: ModelData) {
        modelScope.launch {
            val rc =
                runCatching { ModelManagerWrapper.remove(md.modelName) }
                    .getOrElse { -1 }
            runOnUiThread {
                Toast
                    .makeText(
                        this@MainActivity,
                        if (rc == 0) "\u5220\u9664\u6210\u529F\uFF1A${md.displayName}" else "\u5220\u9664\u5931\u8D25\uFF0C\u9519\u8BEF\u7801\uFF1A$rc",
                        Toast.LENGTH_SHORT,
                    ).show()
                updateModelChip()
            }
        }
    }
    private fun startDownload(md: ModelData) {
        if (downloadJob?.isActive == true) {
            Toast.makeText(this, "正在下载 ${downloadingModelData?.displayName}，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        selectModelId = md.id
        getSharedPreferences("geniex_ui", MODE_PRIVATE).edit()
            .putString("last_model_id", md.id).apply()
        updateModelChip()
        Toast.makeText(this, "开始下载 ${md.displayName}", Toast.LENGTH_SHORT).show()
        downloadModel(md)
    }

    /** 闂傚倸鍊搁崐椋庣矆娓氣偓楠炲鏁嶉崟顒佹闂佸湱鍎ら崵姘洪鍛珖闂侀€炲苯澧撮柟顕€绠栭幃婊堟寠婢跺矈鍞介梻浣烘嚀缁犲秹宕濇惔銊ョ獥闁哄稁鍘奸崹鍌炴煙椤栧棗鎳忓▓楣冩⒑閹肩偛鍔电紒鑼跺Г缁傚秵銈ｉ崘鈺冨幗闂佺鎻徊鍊燁暱闂備礁鎼幏瀣礈濮樿泛绠為柕濞垮労濞笺劑鏌涢埄鍐︿沪缂併劎鏅槐鎾存媴閸濆嫅锛勭磼椤旂晫鎳囩€殿喖顭烽弫鎰緞鐎ｎ偅鐝抽梻浣虹《閸撴繈鎮烽敃鍌氶棷鐟滃繒妲愰幘璇茬＜婵ɑ鐦烽姀掳浜滈柟瀛樼箥濡叉悂鏌ｉ敐鍛Щ妞ゎ厹鍔戝畷姗€骞嗚閸婎垶姊绘担鍛婂暈缂侇喖鐗忕划鍫熸媴缁洘鐏侀梺褰掓？閻掞箓鎮￠悢鍏肩厽婵☆垰鎼痪褍顭跨捄鍝勵伀缂佽鲸甯￠幃婊堝煛娴ｅ嘲顥氱紓鍌氬€搁崐鐑芥倿閿曞倶鈧啴宕ㄩ弶鎴狅紮闂侀潻瀵岄崢楣冩儗濞嗗浚鐔嗛悹鍝勬惈椤︻剟鏌涚€ｎ偅宕岄柡浣瑰姈閹棃鍨鹃懠顒€鍤梻?*/
    private fun switchModel(md: ModelData) {
        if (isGenerating) {
            Toast.makeText(this, "正在生成中，暂时不能切换模型", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectModelId == md.id && hasLoadedModel()) return
        selectModelId = md.id
        getSharedPreferences("geniex_ui", MODE_PRIVATE).edit()
            .putString("last_model_id", md.id).apply()
        updateModelChip()
        binding.drawerLayout.closeDrawer(Gravity.START)
        modelScope.launch {
            if (hasLoadedModel()) {
                if (isLoadVlmModel) {
                    vlmWrapper.stopStream()
                    vlmWrapper.destroy()
                } else {
                    llmWrapper.stopStream()
                    llmWrapper.destroy()
                }
                resetLoadState()
            }
            runOnUiThread {
                btnUnloadModel.visibility = View.GONE
                btnStop.visibility = View.GONE
                startLoadModel(md)
            }
        }
    }
    private fun resetLoadState() {
        isLoadLlmModel = false
        isLoadVlmModel = false
        // Stale geometry would size preprocessing for the previous model.
        vlmVisionConfig = null
        updateLoadButtonStyle()
        binding.btnResend.setOnClickListener { resendLastQuestion() }
        binding.btnCleanMemory.setOnClickListener { cleanMemory() }
        updateMemoryBar()
        updateDeviceInfo()

        applyProxyEnv(savedProxy())
        useContext = getSharedPreferences("geniex_ui", MODE_PRIVATE).getBoolean("use_context", true)
    }

    private fun initView() {
        adapter = ChatAdapter(messages, ::onMessageLongClick, ::onMessageClick, ::onRefsClick)
        binding.rvChat.adapter = adapter
        binding.rvChat.itemAnimator = null
        binding.rvChat.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(
                    rv: RecyclerView,
                    dx: Int,
                    dy: Int,
                ) {
                    // Our own scrolls must not flip the auto-stick off, and while
                    // the streamed bubble grows the layout re-fires onScrolled
                    // with dy == 0 — that also must not count as "user scrolled up".
                    if (programmaticScroll) return
                    if (isGenerating && dy == 0) return
                    val lm = rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return
                    val last = lm.findLastVisibleItemPosition()
                    val atLast = last >= (rv.adapter?.itemCount ?: 0) - 1
                    val nearBottom =
                        rv.computeVerticalScrollRange() - rv.computeVerticalScrollExtent() -
                            rv.computeVerticalScrollOffset() < 120
                    pinBottom = atLast && nearBottom
                }

                override fun onScrollStateChanged(
                    rv: RecyclerView,
                    newState: Int,
                ) {
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                        userTouchingTs = System.currentTimeMillis()
                    }
                }
            },
        )

        llDownloading = findViewById(R.id.ll_downloading)
        tvDownloadProgress = findViewById(R.id.tv_download_progress)
        flDownloadTrack = findViewById(R.id.fl_download_track)
        vDownloadFill = findViewById(R.id.v_download_fill)
        flDownloadTrack.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            setDownloadProgress(maxPercent)
        }
        // ---- 婵犵數濮烽。顔炬閺囥垹纾婚柟杈剧畱绾捐绻涢幋娆忕仼闁哄嫨鍎甸弻鈥愁吋鎼粹€崇闂?/ 闂傚倸鍊搁崐宄懊归崶顒婄稏濠㈣埖鍔曠壕鍧楁煟閹惧啿顔傛繛鎴欏灩缁€瀣亜閺嶃劎鈽夐柛?/ 濠电姷鏁告慨鐑姐€傞挊澹╋綁宕ㄩ弶鎴濈€銈呯箰閻楀棝鎮為崹顐犱簻闁瑰搫妫楁禍鍓х磼閸撗嗘闁告瑥鍟村畷娲焵椤掍降浜滈柟鐑樺灥閳ь剚鎮傚畷銏ゅ箻椤旂晫鍘甸梺鎯ф禋閸嬫帒鈻撳鈧弻鐔兼偡閺夋浼冮梺璇″枟缁矁鐏掗柣鐘叉搐瀵?----
        binding.btnMenu.setOnClickListener { binding.drawerLayout.openDrawer(Gravity.START) }
        binding.btnNewChat.setOnClickListener { startNewSession() }
        lvSessions = binding.lvSessions
        lvSessions.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        lvSessions.adapter = SessionAdapter(this, arrayListOf(), ::onDeleteSession, ::showRenameDialog)
        lvSessions.setOnItemLongClickListener { _, _, position, _ ->
            val sortedLong = sessions.sortedByDescending { it.updatedAt }
            sortedLong.getOrNull(position)?.let { showRenameDialog(it) }
            true
        }
        lvSessions.setOnItemClickListener { _, _, position, _ ->
            val sorted = sessions.sortedByDescending { it.updatedAt }
            sorted.getOrNull(position)?.let { loadSession(it) }
        }
        tvModelChip = binding.tvModelChip
        tvModelChip.setOnClickListener { showModelPicker(downloadedOnly = true) }
        tvTokenCount = binding.tvTokenCount
        tvTokenCount.setOnClickListener { showTokenDetails() }
        maxOutputTokens = getSharedPreferences("geniex_ui", MODE_PRIVATE).getInt("max_out_tokens", 2048)
        updateMaxOutputButton()
        binding.btnMaxOutput.setOnClickListener { showMaxOutputDialog() }
        val prefs = getSharedPreferences("geniex_ui", MODE_PRIVATE)
        val savedId = prefs.getString("last_model_id", null)
        selectModelId =
            if (savedId != null && modelList.any { it.id == savedId }) {
                savedId
            } else {
                modelList.firstOrNull()?.id ?: ""
            }
        updateModelChip()
        if (savedId == null) {
            modelScope.launch {
                val firstDownloaded = modelList.firstOrNull { isModelDownloaded(it) }
                if (firstDownloaded != null) {
                    runOnUiThread {
                        if (selectModelId == modelList.firstOrNull()?.id) {
                            selectModelId = firstDownloaded.id
                            updateModelChip()
                        }
                    }
                }
            }
        }
        btnDownload = findViewById(R.id.btn_download)
        btnLoadModel = findViewById(R.id.btn_load_model)
        updateLoadButtonStyle()
        binding.btnResend.setOnClickListener { resendLastQuestion() }
        binding.btnCleanMemory.setOnClickListener { cleanMemory() }
        updateMemoryBar()
        updateDeviceInfo()

        applyProxyEnv(savedProxy())
        useContext = getSharedPreferences("geniex_ui", MODE_PRIVATE).getBoolean("use_context", true)
        binding.btnSelectMode.setOnClickListener {
            if (selectionMode) exitSelection() else enterSelection()
        }
        binding.btnSelCancel.setOnClickListener { exitSelection() }
        binding.btnSelDelete.setOnClickListener { deleteSelectedMessages() }
        btnUnloadModel = findViewById(R.id.btn_unload_model)
        btnStop = findViewById(R.id.btn_stop)
        etInput = findViewById(R.id.et_input)
        btnAddImage = findViewById(R.id.btn_add_image)

        btnSend = findViewById(R.id.btn_send)
        btnSend.isEnabled = false
        etInput.doAfterTextChanged { refreshSendButtonState() }
        scrollImages = findViewById(R.id.scroll_images)
        topScrollContainer = findViewById(R.id.ll_images_container)
        llLoading = findViewById(R.id.ll_loading)
        vTip = findViewById<View>(R.id.v_tip)

        findViewById<Button>(R.id.btn_test).setOnClickListener {
            Thread {
                val exeFile = File(filesDir, "geniex_test_llm")
                val chmodProcess = Runtime.getRuntime().exec("chmod 755 " + exeFile.absolutePath)
                chmodProcess.waitFor()
                Log.d(TAG, "exeFile exe? ${exeFile.canExecute()}")
                Log.d(TAG, "Exe Thread:${Thread.currentThread().name}")
                ExecShell()
                    .executeCommand(
                        arrayOf(
                            "cat",
                            "/sys/devices/soc0/sku",
                        ),
                    ).forEach {
                        Log.d(TAG, "cmd:$it")
                    }
            }.start()
        }

        findViewById<View>(R.id.v_tip).setOnClickListener {
            Toast.makeText(this, R.string.need_unload, Toast.LENGTH_SHORT).show()
        }
    }

    private fun parseModelList() {
        try {
            val baseJson = assets.open("model_list.json").bufferedReader().use { it.readText() }
            val json = Json { ignoreUnknownKeys = true }
            modelList = json.decodeFromString<List<ModelData>>(baseJson)
        } catch (e: Exception) {
            Log.e(TAG, "parseModelList: $e")
        }
    }

    /**
     * Step 0. Parse the model list and initialise the SDK. Model presence
     * is queried from the Rust model manager, not tracked client-side.
     */
    private fun initData() {
        parseModelList()
        initGenieXSdk()
    }

    /**
     * Step 1. initGenieXSdk environment
     */
    private fun initGenieXSdk() {
        GenieXSdk.getInstance().init(
            this,
            object : GenieXSdk.InitCallback {
                override fun onSuccess() {
                }

                override fun onFailure(reason: String) {
                    Log.e(TAG, "GenieXSdk init failed: $reason")
                }
            },
        )
    }

    private var loadingStartMs = 0L
    private val loadingTicker =
        object : Runnable {
            override fun run() {
                val elapsed = (System.currentTimeMillis() - loadingStartMs) / 1000.0
                binding.tvLoadingElapsed.text = String.format(Locale.US, "%.1f s", elapsed)
                binding.llLoading.postDelayed(this, 100)
            }
        }

    private fun beginLoadingUi(modelName: String) {
        loadingStartMs = System.currentTimeMillis()
        binding.tvLoadingModel.text = modelName
        binding.tvLoadingElapsed.text = "0.0 s"
        binding.llLoading.removeCallbacks(loadingTicker)
        binding.llLoading.post(loadingTicker)
    }

    private fun endLoadingUi() {
        binding.llLoading.removeCallbacks(loadingTicker)
    }
    private fun onLoadModelSuccess(tip: String) {
        endLoadingUi()
        modelMemBytes = 0L
        updateMemoryBar()
        updateLoadButtonStyle()
        binding.btnResend.setOnClickListener { resendLastQuestion() }
        binding.btnCleanMemory.setOnClickListener { cleanMemory() }
        updateMemoryBar()
        updateDeviceInfo()

        applyProxyEnv(savedProxy())
        useContext = getSharedPreferences("geniex_ui", MODE_PRIVATE).getBoolean("use_context", true)
        runOnUiThread {
            Toast
                .makeText(
                    this@MainActivity,
                    tip,
                    Toast.LENGTH_SHORT,
                ).show()
            // change UI
            btnAddImage.visibility = View.VISIBLE
            btnUnloadModel.visibility = View.VISIBLE
            llLoading.visibility = View.INVISIBLE
            vTip.visibility = View.GONE
            btnStop.visibility = View.VISIBLE
            refreshSendButtonState()
        }
    }

    private fun onLoadModelFailed(tip: String) {
        endLoadingUi()
        runOnUiThread {
            vTip.visibility = View.GONE
            Toast.makeText(this@MainActivity, tip, Toast.LENGTH_SHORT).show()
            // change UI
            btnAddImage.visibility = View.VISIBLE
            btnUnloadModel.visibility = View.GONE
            llLoading.visibility = View.INVISIBLE
        }
    }

    /** 闂傚倸鍊搁崐鐑芥嚄閸洖鍌ㄧ憸鏃堝Υ閸愨晜鍎熼柕蹇嬪焺濞叉悂姊虹紒妯哄婵炰匠鍥у瀭婵犻潧娲㈡禍婊堟煛瀹ュ啫濡块柍顖涙礀闇夋繝褍鐏濋埀顒佺墱閹广垹鈽夐姀鐘殿吅闂佽绻戝鎸庣椤忓牄鈧礁鈻庨幇顔剧槇闂佹悶鍎崝搴ㄥ储閸涘﹦绡€闁靛骏绲剧涵鐐繆椤愶絿鎳囬柨婵堝仦瀵板嫬鐣濋埀顒傚婵傚憡鐓曟繛鍡樺厧椤忓嫮鏆ら柛鈩冪憿閸嬫挾鎲撮崟顒傗敍缂傚倸绉崇欢姘跺Υ娓氣偓瀵噣宕煎┑鍫О婵＄偑鍊栭幐鐐垔椤撶倣锝夋偡閹佃櫕鏂€闂佺粯顭囩划顖氣槈瑜庨妵鍕晜鐠恒劎鐓撻悗瑙勬礃閸ㄥ灝鐣烽悢纰辨晬婵﹩鍓欐禍鍫曟⒒娴ｅ憡鎯堢紒瀣╃窔瀹曘垺绂掔€ｎ偄鈧灝螖閿濆懎鏆為柣鎾崇箰閳规垿鎮欓懠顑胯檸闂佸憡姊瑰畝鎼佸蓟閿濆棙鍎熼柍钘夋閸氬姊虹拠鈥虫灕妞ゎ偄顦甸獮蹇涘川椤栨粎鐓撻柟鐓庣摠閹歌崵鈧灚鐗犲缁樻媴閸︻厽鑿囬梺绋匡攻濞叉牞鐏嬪┑鐐村灟閸ㄥ綊鎮為崹顐犱簻闁圭儤鍨垫禍鐐烘煛閳ь剟鎮ч崼婊呯畾濡炪倖鍔戦崹褰掝敂椤愶附鐓冪憸婊堝礈濠靛缍栧璺侯儑缁犳儳鈹戦悩鍙夋悙闁哄鑳堕埀顒€绠嶉崕鍗灻洪敐鍛煢妞ゅ繐鐗婇悡鐔兼煙娴煎瓨娑у褜鍨遍妵鍕籍閳ь剛鏁垾鎰佹綎婵炲樊浜滄导鐘绘煕閺囩偟浠涙い搴℃缁辨挻鎷呴搹鐟扮缂備浇顕ч悧鍡涱敋閿濆鎯炴い鎰剁悼缁夊爼姊洪棃娑辩叚缂佺姵鍨垮畷婵嬪川鐎涙ǚ鎷洪梺鍦焾鐎涒晠鍩涢幇鐗堢厱闁靛濡囩粻鐐烘煙閾忣偆鐭掓い銏℃瀹曞ジ鎮㈢亸浣镐壕?*/
    private fun setDownloadProgress(percent: Int) {
        if (!::vDownloadFill.isInitialized) return
        val trackW = flDownloadTrack.width
        val lp = vDownloadFill.layoutParams
        lp.width = if (trackW > 0) trackW * percent.coerceIn(0, 100) / 100 else 0
        vDownloadFill.layoutParams = lp
    }
    /** 闂傚倸鍊搁崐椋庣矆娓氣偓楠炴牠顢曢敂钘変罕濠电姴锕ょ€氼噣銆呴崣澶岀瘈濠电姴鍊搁顏堟煕濞嗗繒绠婚柡宀嬬秮楠炲洭顢楅崒娑欏枛濠电姵顔栭崰妤冨垝閹捐钃熼柕鍫濐槸缁犳帡鏌熼悜妯虹仴濠㈢懓妫欑换娑㈠箻閺夋垹鍔伴梺绋款儐閹搁箖鍩€椤掑喚娼愭繛鍙夌墪閿曘垽鏌嗗鍛姦濡炪倖甯掗敃锔剧矓閻㈠憡鐓曢悗锝庝簼閸ゅ洭鎸婂┑鍫熷枑闁哄啫鐗嗛拑鐔兼煛閸ャ儱鐏╂鐐灪娣囧﹪濡堕崨顓т患濡炪倕娴氶崢鐣屾崲濠靛棌鏋旈柛顭戝枟閻忔捇姊虹粙娆惧剳闁稿鐩垾锕傚炊椤掑鏅滈梺鍛婁緱閸犳牕顕ｉ崸妤佲拺缁绢厼鎳庨悞浠嬫煕閺傛寧婀扮紒鍌氱Ч瀹曟粏顦柛瀣崌閻涱噣宕归鐓庮潛婵犵妲呴崑鍛淬€冩繝鍥х畾濞达絽婀辩弧鈧┑顔斤供閸樻悂骞忛搹鍦＝濞达絽婀遍埥澶嬨亜閹存繃鍤囩€殿喗鎮傞幃銏ゆ⒐閹邦喚鐣炬俊鐐€栭悧妤呭礄閻熸嫈锝夋惞閸︻厾锛滃銈嗘閸嬫劖鏅跺☉娆戠缁炬澘褰夐柇顖涱殽閻愯尙绠伴柣锝嗙箞閸┾偓妞ゆ帒濯婚崶顒€绠ユい鏂垮⒔閿涙繈姊虹粙鎸庢拱闁荤噦濡囩划濠冨鐎涙鍘遍柟鑹版彧缁茶法鏁☉姘辩＜闁哄啫鍊搁弸鎴犵磼缂佹绠炵€规洖鐖兼俊鎼佹晜閻ｅ苯寤洪梻鍌欐祰椤曆呮崲閹烘纾婚柣鎰惈绾捐法鈧娲栧ú銈囨閵堝鐓曢柨鏃囶嚙楠炴鐥幆褋鍋㈤柡灞界Х椤т線鏌涢幘璺烘瀻闁伙絿鍏橀、鏇㈡晝閳ь剛绮堢€ｎ偁浜滈柟鎵虫櫅閸旀挳鏌涢悙鍨殌闁宠鍨块幃娆忣啅椤斿吋顔嶅┑鐘愁問閸犳洟宕￠崘鑼殾闁哄洨鍠愮紞鍥煏婵犲繒鐣辩€殿喖娼″铏圭磼濡搫顫岄梺瀹︽澘濡介柟?*/
    private fun updateLoadButtonStyle() {
        if (!::btnLoadModel.isInitialized) return
        runOnUiThread {
            if (hasLoadedModel()) {
                btnLoadModel.text = if (useContext) getString(R.string.ctx_on) else getString(R.string.ctx_off)
                if (useContext) {
                    btnLoadModel.setBackgroundResource(R.drawable.btn_green_bg)
                    btnLoadModel.setTextColor(0xFFFFFFFF.toInt())
                } else {
                    btnLoadModel.setBackgroundResource(R.drawable.btn_rounded_border)
                    btnLoadModel.setTextColor(0xFF1F1F1F.toInt())
                }
            } else {
                btnLoadModel.text = getString(R.string.load_model)
                btnLoadModel.setBackgroundResource(R.drawable.btn_green_bg)
                btnLoadModel.setTextColor(0xFFFFFFFF.toInt())
            }
        }
    }
    private fun hasLoadedModel(): Boolean = isLoadLlmModel || isLoadVlmModel

    /**
     * Send is enabled only when (a) a model is loaded, (b) no inference
     * is in flight, and (c) there is something to send 闂?text or an
     * attached image (VLM only).
     */
    private fun refreshSendButtonState() {
        runOnUiThread {
            val hasText = etInput.text?.isNotBlank() == true
            val hasAttachment = savedImageFiles.isNotEmpty()
            // 闂傚倸鍊搁崐椋庣矆娓氣偓楠炴牠顢曢敂钘変罕濠电姴锕ょ€氼噣銆呴崣澶岀瘈濠电姴鍊搁顏堟煕濞嗗繒绠婚柡宀嬬秮楠炲洭顢楅崒娑欏枛濠电姵顔栭崰妤冨垝閹捐钃熼柕鍫濐槸缁犳帡鏌熼悜妯虹仴濠㈢懓妫欑换娑㈠箻閺夋垹鍔伴梺绋款儐閹搁箖鍩€椤掑喚娼愭繛鍙夌墪閿曘垽鏌嗗鍛姦濡炪倖甯掗敃锔剧矓閻㈠憡鐓曢悗锝庝簼閸ゅ洭鎸婂┑鍫熷枑鐎广儱顦悡鈥愁熆閼搁潧濮囩紒顐㈢Ч閺屾稓浠﹂崜褜鏆￠梺璇查椤嘲顫忓ú顏勫窛濠电偞纰嶉崹鍨暦绾懌浜归柟鐑樺灩椤斿棝鏌ｆ惔顖滅У闁哥姵鐗楃€靛ジ鎮╃紒妯煎帾婵犮垼娉涢悧鍡涘礉濞嗘挻鐓曢柡宥冨妿婢у灚鎱ㄦ繝鍐┿仢鐎规洏鍔嶇换婵嬪礋椤掆偓濞呮姊绘担瑙勫仩闁告柨鑻…鍥晸閻樺弶妲梺閫炲苯澧柕鍥у楠炲鏁愰崱娆嶁偓濠勭磽娴ｅ湱鈽夌紒缁樺笧濡叉劙骞掑Δ鈧惌妤佷繆椤栨碍鎯堢紒渚囧櫍濮婅櫣鎷犻垾铏亶闂佽崵鍟块弲鐘荤嵁閸愵喗鍊婚柦妯侯槺妤犲洭姊洪崷顓х劸婵炴潙鍢插嵄婵﹩鍏橀弨浠嬫煟閹邦剛鎽犵紓宥嗗灴閺屾稑顫濋澶婂壉缂備礁鑻畷顒傚弲濡炪倕绻愮€氼剟骞嗛悙娴嬫斀闁绘顕滃銉╂煟濡も偓濡稓鍒掗崼鐔风窞闁归偊鍘鹃崢顏呯節閻㈤潧浠滈柣蹇旂箞瀹曟繂顫濋婊€绨诲銈嗗姦閸撴稓绮旈閿亾濞堝灝鏋涢柣鏍с偢楠炲啫鈻庨幋鐐茬／闂佺儵鏅滅敮妤呭箲閸ヮ剙绠犳慨妯挎硾閻撴盯鏌涘☉鍗炴灓闁?
            if (isGenerating) {
                btnSend.text = getString(R.string.stop_gen)
                btnSend.isEnabled = true
            } else {
                btnSend.text = getString(R.string.send)
                btnSend.isEnabled = hasText || hasAttachment
            }
        }
    }

    /**
     * Checks the Rust model manager's cache for [modelData]. Uses
     * `getPaths`, which canonicalises the name (so `ai-hub-models/<repo>`
     * and `qualcomm/<repo>` map to the same on-disk entry) and returns
     * null while the pull is still in `.inflight/`.
     */
    private suspend fun isModelDownloaded(modelData: ModelData): Boolean = ModelManagerWrapper.getPaths(modelData.modelName) != null

    private fun loadModel(
        selectModelData: ModelData,
        modelDataPluginId: String,
        nGpuLayers: Int,
        deviceId: String? = null,
    ) {
        modelScope.launch {
            resetLoadState()
            val paths = ModelManagerWrapper.getPaths(selectModelData.modelName)
            if (paths == null) {
                onLoadModelFailed(getString(R.string.model_paths_missing))
                return@launch
            }
            // Manifest-written runtime_id wins when present; fall back to
            // the user's UI selection for GGUF models that skip the manifest.
            val pluginId = paths.runtime_id.ifEmpty { modelDataPluginId }
            val resolvedDeviceId = deviceId
            when (selectModelData.type) {
                "chat", "llm" -> {
                    // QAIRT rejects non-zero n_ctx / n_gpu_layers (both fixed at compile
                    // time in the AI Hub bundle) 闂?and the Kotlin ModelConfig defaults
                    // are non-zero, so zero them explicitly for the qairt path.
                    val isQairt = pluginId == "qairt"
                    val conf =
                        if (isQairt) {
                            ModelConfig(nCtx = 0, nGpuLayers = 0, enable_thinking = enableThinking)
                        } else {
                            ModelConfig(
                                nCtx = 1024,
                                nGpuLayers = nGpuLayers,
                                enable_thinking = enableThinking,
                            )
                        }
                    LlmWrapper
                        .builder()
                        .llmCreateInput(
                            LlmCreateInput(
                                model_name = paths.model_name,
                                model_path = paths.model_path,
                                tokenizer_path = paths.tokenizer_path,
                                config = conf,
                                runtime_id = pluginId,
                                compute_unit = resolvedDeviceId ?: ComputeUnitValue.NPU.value,
                            ),
                        ).build()
                        .onSuccess { wrapper ->
                            isLoadLlmModel = true
                            llmWrapper = wrapper
                            onLoadModelSuccess(getString(R.string.load_success))
                        }.onFailure { error ->
                            onLoadModelFailed(error.message.toString())
                        }
                }

                "multimodal", "vlm" -> {
                    val isNpuVlm = pluginId == "qairt"
                    // Size image preprocessing from the tower this model actually
                    // ships, not from a constant: Qwen3.5-VL is 768/16 (576
                    // tokens) but Qwen2.5-VL is 560/14 (1600), so one hardcoded
                    // number mis-sizes every other model in the catalog.
                    vlmVisionConfig =
                        paths.mmproj_path?.takeIf { it.isNotEmpty() }?.let { GgufVisionReader.read(File(it)) }
                    vlmVisionConfig?.let {
                        Log.d(
                            TAG,
                            "vision tower: ${it.imageSize}px, patch ${it.patchSize}, " +
                                "merge ${it.spatialMergeSize} -> ${it.tokenCount} image tokens",
                        )
                    } ?: Log.w(TAG, "no vision config from mmproj; preprocessing at $FALLBACK_VLM_IMAGE_SIZE")
                    val config =
                        if (isNpuVlm) {
                            // QAIRT rejects non-zero n_ctx / n_gpu_layers for VLM too.
                            ModelConfig(nCtx = 0, nGpuLayers = 0, nThreads = 8, enable_thinking = enableThinking)
                        } else {
                            ModelConfig(
                                // One image costs tokenCount tokens (576 on
                                // Qwen3.5-VL, 1600 on Qwen2.5-VL). nCtx = 1024
                                // left too little room for the prompt plus a
                                // reply, and a second image turn died inside
                                // mtmd_tokenize with "failed to initialize
                                // batch". Leave room for an image, its answer and
                                // a follow-up turn.
                                nCtx = vlmContextSize(vlmVisionConfig),
                                nThreads = 4,
                                nBatch = 1,
                                nUBatch = 1,
                                nGpuLayers = nGpuLayers,
                                enable_thinking = enableThinking,
                            )
                        }
                    VlmWrapper
                        .builder()
                        .vlmCreateInput(
                            VlmCreateInput(
                                model_name = paths.model_name,
                                model_path = paths.model_path,
                                mmproj_path = paths.mmproj_path,
                                config = config,
                                runtime_id = pluginId,
                                compute_unit = resolvedDeviceId ?: "HTP0",
                            ),
                        ).build()
                        .onSuccess {
                            isLoadVlmModel = true
                            vlmWrapper = it
                            onLoadModelSuccess(getString(R.string.load_success))
                        }.onFailure { error ->
                            onLoadModelFailed(error.message.toString())
                        }
                }

                else -> {
                    onLoadModelFailed(getString(R.string.model_type_error))
                }
            }
        }
    }

    private fun downloadModel(selectModelData: ModelData) {
        if (downloadJob?.isActive == true) {
            Toast.makeText(this, "${downloadingModelData?.displayName ?: "\u67D0\u4E2A\u6A21\u578B"} \u6B63\u5728\u4E0B\u8F7D", Toast.LENGTH_SHORT).show()
            return
        }
        if (hasLoadedModel()) {
            modelScope.launch {
                if (isLoadVlmModel) {
                    vlmWrapper.stopStream()
                    vlmWrapper.destroy()
                } else {
                    llmWrapper.stopStream()
                    llmWrapper.destroy()
                }
                resetLoadState()
                runOnUiThread {
                    btnUnloadModel.visibility = View.GONE
                    btnStop.visibility = View.GONE
                    beginDownload(selectModelData)
                }
            }
            return
        }
        beginDownload(selectModelData)
    }

    private fun beginDownload(selectModelData: ModelData) {
        downloadingModelData = selectModelData
        llDownloading.visibility = View.VISIBLE
        binding.btnRetryDownload.visibility = View.GONE
        tvDownloadProgress.text = "0%"
        maxPercent = 0
        lastProgressTs = System.currentTimeMillis()
        lastUiTs = System.currentTimeMillis()
        lastUiDone = 0L
        dlStartTs = 0L
        dlStartDone = 0L
        dlBaselineSet = false
        setDownloadProgress(0)

        val hub =
            runCatching { HubSource.valueOf(selectModelData.hub ?: "AUTO") }
                .getOrDefault(HubSource.AUTO)
        val name = selectModelData.modelName
        val isAiHubName =
            name.startsWith("ai-hub-models/", ignoreCase = true) ||
                name.startsWith("qualcomm/", ignoreCase = true)
        val willUseAiHub = hub == HubSource.AIHUB || (hub == HubSource.AUTO && isAiHubName)
        if (willUseAiHub && selectModelData.chipset.isNullOrBlank()) {
            llDownloading.visibility = View.GONE
            Toast.makeText(this, "AI Hub 模型需要指定芯片（chipset），请更新 model_list.json", Toast.LENGTH_SHORT).show()
            return
        }
        val input =
            ModelPullInput(
                model_name = selectModelData.modelName,
                precision = selectModelData.quant,
                hub = hub,
                chipset = selectModelData.chipset,
                display_name = selectModelData.aiHubDisplayName,
            )

        val wakeLock =
            (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "geniex:model_download")
        wakeLock.acquire()

        var lastDone = 0L
        var lastTs = System.currentTimeMillis()
        var maxTotal = 0L
        maxPercent = 0

        modelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(3000)
                if (downloadJob?.isActive != true) break
                if (System.currentTimeMillis() - lastProgressTs > 12000) {
                    runOnUiThread {
                        if (maxPercent == 0) tvDownloadProgress.text = getString(R.string.dl_stalled)
                    }
                }
                if (System.currentTimeMillis() - lastProgressTs > 25000) {
                    showDownloadFailedDialog()
                    break
                }
            }
        }
        downloadJob =
            modelScope.launch {
                try {
                    if (isModelDownloaded(selectModelData)) {
                        runOnUiThread {
                            llDownloading.visibility = View.GONE
                            Toast.makeText(this@MainActivity, "该模型已下载完成", Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }

                    ModelManagerWrapper.pullFlow(input).collect { event ->
                        when (event) {
                            is ModelManagerWrapper.PullEvent.Progress -> {
                                val totalRaw = event.files.sumOf { if (it.total_bytes > 0) it.total_bytes else 0L }
                                if (totalRaw > maxTotal) maxTotal = totalRaw
                                val total = maxTotal
                                val done = event.files.sumOf { it.downloaded_bytes }
                                val now = System.currentTimeMillis()
                                if (!dlBaselineSet) {
                                    dlBaselineSet = true
                                    dlStartDone = done
                                    dlStartTs = now
                                    lastUiDone = done
                                    lastUiTs = now - 900
                                }
                                lastProgressTs = now
                                lastDone = done
                                if (total > 0) {
                                    val percent = ((done * 100) / total).toInt().coerceIn(0, 100)
                                    if (percent > maxPercent) maxPercent = percent
                                    runOnUiThread { setDownloadProgress(maxPercent) }
                                }
                                if (now - lastUiTs >= 1000) {
                                    val span = (now - lastUiTs).coerceAtLeast(1)
                                    val speed =
                                        ((done - lastUiDone).coerceAtLeast(0L) * 1000.0 / span) / (1024.0 * 1024.0)
                                    lastUiDone = done
                                    lastUiTs = now
                                    runOnUiThread {
                                        val doneMb = done / 1048576.0
                                        val elapsedMs = (now - dlStartTs).coerceAtLeast(1)
                                        val avgBps = (done - dlStartDone).coerceAtLeast(0L) * 1000.0 / elapsedMs
                                        val eta =
                                            if (total > 0 && avgBps > 1024.0 && elapsedMs >= 3000) {
                                                val remainSec = ((total - done).coerceAtLeast(0L) / avgBps).toInt()
                                                if (remainSec >= 3600) {
                                                    String.format(
                                                        Locale.US,
                                                        "剩余 %d:%02d:%02d",
                                                        remainSec / 3600,
                                                        (remainSec % 3600) / 60,
                                                        remainSec % 60,
                                                    )
                                                } else {
                                                    String.format(Locale.US, "剩余 %d:%02d", remainSec / 60, remainSec % 60)
                                                }
                                            } else {
                                                "剩余 --"
                                            }
                                        tvDownloadProgress.text =
                                            if (total > 0) {
                                                String.format(
                                                    Locale.US,
                                                    "%.0f / %.0f MB\n%.1f MB/s · %d%%\n%s",
                                                    doneMb,
                                                    total / 1048576.0,
                                                    speed,
                                                    maxPercent,
                                                    eta,
                                                )
                                            } else {
                                                String.format(Locale.US, "%.0f MB\n%.1f MB/s\n%s", doneMb, speed, eta)
                                            }
                                    }
                                }
                            }

                            is ModelManagerWrapper.PullEvent.Completed -> {
                                runOnUiThread {
                                    llDownloading.visibility = View.GONE
                                    Toast
                                        .makeText(
                                            this@MainActivity,
                                            "${selectModelData.displayName} \u4E0B\u8F7D\u5B8C\u6210",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    updateModelChip()
                                }
                            }

                            is ModelManagerWrapper.PullEvent.Error -> {
                                Log.e(TAG, "pull failed rc=${event.code}: ${event.message}")
                                showDownloadFailedDialog()
                            }
                        }
                    }
                } finally {
                    if (wakeLock.isHeld) wakeLock.release()
                }
            }
    }
    private fun setListeners() {
        btnAddImage.visibility = View.VISIBLE
        btnAddImage.setOnClickListener {
            if (!isLoadVlmModel) {
                Toast.makeText(this@MainActivity, "当前模型不支持图片输入", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            openGallery()
        }


        /*
         * Step 3. download model. Cancelling the coroutine triggers the
         * flow's awaitClose which flips the Rust progress callback to
         * return false 闂?partial files stay on disk for a resumed pull.
         * Use the Retry button to kick off a fresh pull that resumes.
         */
        binding.btnCancelDownload.setOnClickListener {
            downloadJob?.cancel()
            downloadJob = null
            tvDownloadProgress.text = "0%"
            binding.llDownloading.visibility = View.GONE
        }
        binding.btnRetryDownload.setOnClickListener {
            downloadJob?.cancel()
            downloadJob = null
            downloadingModelData?.let { downloadModel(it) }
        }
        binding.btnKb.setOnClickListener { KbManager.showDialog(this@MainActivity) }

        btnDownload.setOnClickListener { showModelPicker(downloadedOnly = false) }

        /*
         * Step 4. load model
         */
        btnLoadModel.setOnClickListener {
            val selectModelData = modelList.first { it.id == selectModelId }
            Log.d(TAG, "current select model data:$selectModelData")
            if (hasLoadedModel()) {
                useContext = !useContext
                getSharedPreferences("geniex_ui", MODE_PRIVATE).edit().putBoolean("use_context", useContext).apply()
                updateLoadButtonStyle()
                Toast
                    .makeText(
                        this@MainActivity,
                        if (useContext) R.string.ctx_enabled else R.string.ctx_disabled,
                        Toast.LENGTH_SHORT,
                    ).show()
                return@setOnClickListener
            }

            // Availability is checked against the manager's cache 闂?a pull
            // that was cancelled mid-flight is not listed until it completes.
            modelScope.launch {
                if (!isModelDownloaded(selectModelData)) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Model not downloaded — tap Download first.", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                runOnUiThread { startLoadModel(selectModelData) }
            }
        }

        /*
         * Step 5. send message
         */
        btnSend.setOnClickListener {
            if (isGenerating) {
                modelScope.launch {
                    runCatching {
                        if (isLoadVlmModel) vlmWrapper.stopStream() else llmWrapper.stopStream()
                    }
                }
                return@setOnClickListener
            }
            if (!hasLoadedModel()) {
                val md = modelList.firstOrNull { it.id == selectModelId }
                if (md == null) {
                    Toast.makeText(this@MainActivity, "请先选择模型", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                modelScope.launch {
                    val downloaded = isModelDownloaded(md)
                    runOnUiThread {
                        if (downloaded) {
                            Toast.makeText(this@MainActivity, "正在加载 ${md.displayName}", Toast.LENGTH_SHORT).show()
                            startLoadModel(md)
                        } else {
                            Toast.makeText(this@MainActivity, "模型未下载，开始下载", Toast.LENGTH_SHORT).show()
                            startDownload(md)
                        }
                    }
                }
                return@setOnClickListener
            }
            // Guard against re-entry: a second click while a previous
            // generate() is still running would race on the native handle
            // and crash the app.
            if (isGenerating) return@setOnClickListener
            isGenerating = true
            refreshSendButtonState()

            if (savedImageFiles.isNotEmpty()) {
                val persistedImgs = savedImageFiles.map { persistImage(it) }
                messages.add(Message("", MessageType.IMAGES, persistedImgs))
                recordTurn("images", persistedImgs.joinToString("|") { it.absolutePath })
                reloadRecycleView()
            }

            val inputString = etInput.text.trim().toString()
            etInput.setText("")
            etInput.clearFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(etInput.windowToken, 0)

            if (inputString.isNotEmpty()) {
                messages.add(Message(inputString, MessageType.USER))
                recordTurn("user", sanitizeForSdk(inputString))
                reloadRecycleView()
            }

            showLoadingIndicator()
            // Sending always re-pins the list to the newest message, for the
            // LLM and the VLM path alike.
            pinBottom = true
            scrollToBottom()
            pendingRefs = emptyList()

            val supportFunctionCall = false
            var tools: String? = null
            if (supportFunctionCall) {
                // if this model support 'function call'
                tools =
                    "[{\"type\":\"function\",\"function\":{\"name\": \"campaign_investigation\",\"description\": \"Check campaign limits and determine appropriate action. If customer has reached limit, return a message (hardcoded or generated by model). If limit not reached, contact support.\",\"parameters\": {\"type\": \"object\", \"properties\":{\"campaign_name\":{\"type\": \"string\",\"description\": \"The name of the campaign to investigate\"}}, \"required\":[\"campaign_name\"]}}}]"
            }

            if (!hasLoadedModel()) {
                Toast.makeText(this@MainActivity, R.string.model_not_loaded, Toast.LENGTH_SHORT).show()
                isGenerating = false
                refreshSendButtonState()
                return@setOnClickListener
            }

            modelScope.launch {
                try {
                    val selectModelData = modelList.first { it.id == selectModelId }
                    val isNpu = ModelManagerWrapper.getPaths(selectModelData.modelName)?.runtime_id == "qairt"
                    Log.d(TAG, "isNpu: $isNpu")

                    val sb = StringBuilder()
                    rawStreamSb.setLength(0)
                    streamingText = ""
                    streamStartTs = System.currentTimeMillis()
                    streamingSaved = false
                    lastStreamUiTs = 0L
                    if (isLoadVlmModel) {
                        // VLM 分支同样要挂知识库：资料塞进这条用户文本里，
                        // 参考资料按钮由公共的 Completed 分支负责渲染。
                        val kbHits = if (kbEnabled) KnowledgeBase.search(inputString, 2) else emptyList()
                        val grounded = groundRefs(kbHits)
                        pendingRefs = grounded
                        val refsBlock = refsBlockOf(grounded)
                        val contents =
                            savedImageFiles
                                .map {
                                    VlmContent("image", it.absolutePath)
                                }.toMutableList()
                        contents.add(VlmContent("text", refsBlock + inputString))
                        clearImages()
                        val sendMsg = VlmChatMessage(role = "user", contents = contents)
                        vlmChatList.add(sendMsg)

                        Log.d(TAG, "before apply chat template:$vlmChatList")
                        vlmWrapper
                            .applyChatTemplate(vlmChatList.toTypedArray(), tools, enableThinking)
                            .onSuccess { result ->
                                Log.d(TAG, "vlm chat template:${result.formattedText}")
                                val baseConfig =
                                    GenerationConfigSample(maxTokens = maxOutputTokens).toGenerationConfig()
                                // Only inject the current turn's media: SDK tokenizes
                                // incrementally, so re-passing history bitmaps breaks
                                // mtmd_tokenize (markers/bitmaps mismatch).
                                val configWithMedia =
                                    vlmWrapper.injectMediaPathsToConfig(
                                        arrayOf(sendMsg),
                                        baseConfig,
                                    )

                                Log.d(TAG, "Config has ${configWithMedia.imageCount} images")

                                vlmWrapper
                                    .generateStreamFlow(
                                        result.formattedText,
                                        configWithMedia,
                                    ).collect { handleResult(sb, it) }
                            }.onFailure {
                                runOnUiThread {
                                    Toast
                                        .makeText(
                                            this@MainActivity,
                                            it.message,
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                }
                            }
                    } else {
                        if (!useContext) {
                            chatList.clear()
                            runCatching {
                                if (isLoadVlmModel) vlmWrapper.reset() else llmWrapper.reset()
                            }
                        }
                        chatList.add(ChatMessage(role = "user", sanitizeForSdk(inputString)))
                        // The user turn is already recorded by the common block above.
                        //
                        // 参考资料必须放进「用户这条消息」里，再整体走 applyChatTemplate。
                        // 早先是拼在已模板化的 prompt 前面，等于在 <|im_start|>system 之前
                        // 多了一段无归属文本，模型会把对话结构当成没结束，回答完还会自己
                        // 虚构一条用户指令继续往下演。
                        val kbHits = if (kbEnabled) KnowledgeBase.search(inputString, 2) else emptyList()
                        val grounded = groundRefs(kbHits)
                        pendingRefs = grounded
                        val refsBlock = refsBlockOf(grounded)
                        Log.d(
                            TAG,
                            "KB refs hits=${kbHits.size} grounded=${grounded.size} " +
                                "block=${refsBlock.length} snips=${kbHits.map { it.second.length }}",
                        )
                        fun withRefs(src: List<ChatMessage>): List<ChatMessage> {
                            if (refsBlock.isEmpty() || src.isEmpty()) return src
                            val out = src.toMutableList()
                            out[out.lastIndex] = ChatMessage("user", sanitizeForSdk(refsBlock + inputString))
                            return out
                        }
                        var trimmed = buildPromptWithTrim(withRefs(chatList.toList()), tools, enableThinking)
                        // NPU 闂傚倸鍊搁崐椋庣矆娓氣偓楠炲鏁撻悩鍐蹭罕闂佸搫娲㈤崹鍦不閻樼鍋撻獮鍨姎婵炲眰鍔庣划濠氬籍閸喓鍘遍柣蹇曞仜婢т粙濡撮幒妤佺厓缂備焦蓱椤ュ牓鏌熼绛嬫疁闁轰焦鍔栭幆鏃堝灳閼碱剙鍤梻鍌欐祰椤曆呪偓鍨浮瀹曟娊鏁愭径濠呮憰濠电偞鍨崹鐟版暜闂備線娼ч¨鈧梻鍕瀹曟洟骞樼紒妯锋嫼?4096闂傚倸鍊搁崐鐑芥倿閿旈敮鍋撶粭娑樻噽閻瑩鏌熼悜姗嗘畷闁搞倕鐭傞弻鐔碱敍閸℃瀚伴柡鍛У缁绘繈鎮介棃娴讹綁鏌よぐ鎺旂暫闁诡垰鑻灃闁告侗鍠氶崢鐢电磽閸屾瑧鍔嶉柨姘辩磼閺冣偓閸庢娊婀佸┑鐘才堥崑鎾绘煕濡も偓閸熷潡锝炶箛鏃傜瘈婵﹩鍓涢崝锕€顪冮妶鍡楃瑨閻庢凹鍓涚划缁樺閺夋垹顔愬┑鐑囩秵閸撴瑩鎮為悜妯圭箚鐎瑰壊鍠栭悘锝囩磼鏉堛劍宕岀€规洘甯掗埢搴ㄥ箣閻樻ɑ宀稿娲川婵犱胶绻侀梺鎼炲姂娴滆泛顕ｉ幎鑺ュ亱闁割偁鍨婚鏇㈡⒑閻熼偊鍤熼柛瀣仧娴滄悂顢旈崼鐔哄幈闂侀潧顭堥崕鑼嫻閳╁啩绻嗛柛娆忣槸婵秶鈧鍣崳锝呯暦閻撳簶鏀介柛鈩冪懃缂嶄線姊婚崒娆戠獢闁逞屽墰閸嬫盯鎳熼娑欐珷閻庣數纭堕崑鎾斥枔閸喗鐝梺闈╃秶缂嶄礁鐣峰ú顏呮櫢闁绘灏欓ˇ銊╂⒑閸愬弶鎯堥柛鐕佸灠閳绘捇骞栨担鍏夋嫼闂佸憡绻傜€氬嘲危濞差亝鐓曢幖娣灩婵秶鈧娲樺妯跨亙闂佸憡绮堥悞锕傚疾濠靛洨绡€闂傚牊绋戦埀顒佹倐楠炲鏁撻悩鑼煣?                        // 闂傚倸鍊搁崐椋庣矆娓氣偓楠炲鏁撻悩鍐蹭簻濡炪倖甯掗崐缁樼▔瀹ュ鐓欓弶鍫濆⒔閻ｉ亶鏌涢妸銉モ偓褰掑Φ閸曨垰鍐€闁靛ě鈧Σ鍫濐渻閵堝棙绀夊瀛樻倐楠炲牓濡搁妷顔藉缓闂佺硶鍓濋〃鍛达綖閳轰讲鏀芥い鏇炴閸掔増銇勯敂鐐毈鐎?"context length exceeded"
                        if (isNpu) {
                            var guard = 0
                            while (trimmed != null && guard < 40 &&
                                trimmed.first.length / 2 + 256 > 4096 - 256 && chatList.size > 2
                            ) {
                                chatList.subList(0, 2).clear()
                                trimmed = buildPromptWithTrim(withRefs(chatList.toList()), tools, enableThinking)
                                guard++
                            }
                        }
                        if (isNpu && (trimmed == null || trimmed.second > 0 || trimmed.first.length > 6000)) {
                            // 闂傚倸鍊搁崐椋庣矆娓氣偓楠炲鏁撻悩铏珨濠电姷顣藉Σ鍛村磻閸涙番鈧啯寰勯幇顑╋箓鏌熼悧鍫熺凡缂佲偓閸緷褰掓晲閸ャ劌娈屾繛瀵稿Т缁绘劙鈥旈崘顔嘉ч柛鈩冾殔椤洭姊虹粙鍧楊€楅柟纰卞亰閿濈偠绠涘☉姗€鏁滃┑掳鍊撻懗鍫曞储闁秵鈷戦柣鐔稿娴犮垽鏌涢悢鍙夋珖闁奸缚椴哥缓浠嬪川婵犲嫬骞堥梻渚€娼ч…顓犲緤閼恒儲鍙忛柛灞句緱濞堜粙鏌ｉ幇顓熺稇婵炴惌鍠栭埞鎴︽晬閸曨偄骞嬫繝纰樺墲閻℃洟藝鐎涙ǜ浜滈柡鍌涘閸ｈ櫣绱掔紒妯兼创妤犵偛顑夐幃娆撳级閹寸媭鏆￠梻鍌欑劍鐎笛兠洪敂鐣岊洸婵犲﹤鐗冮埀顒佺墬缁绘繈宕戦娑欍仢妞ゃ垺妫冨畷銊╊敊閻ｅ苯寤烘繝鐢靛У椤旀牠宕板Δ鍛櫇闁冲搫鎳庨崒銊ノ旈敐鍛灓闁轰礁鍊块弻鐔兼⒒鐎电濡介梺?NPU 闂傚倸鍊搁崐鎼佸磹閻戣姤鍊块柨鏇氶檷娴滃綊鏌涢幇闈涙灍闁稿孩鏌ㄩ埞鎴﹀磼濮橆厼鏆堥梺绋款儛娴滄繈濡甸崟顖氱睄闁稿本鐭竟鏇熺節濞堝灝鐏犻柕鍫熸倐瀵濡搁妷銏☆潔濠碘槅鍨拃锔界妤ｅ啯鈷?KV闂傚倸鍊搁崐鐑芥倿閿旈敮鍋撶粭娑樻噽閻瑩鏌熸潏楣冩闁搞倖鍔栭妵鍕冀椤愵澀绮剁紓浣哄У閼归箖濡撮幒鎴僵闁挎繂鎳嶆竟鏇㈡煟鎼淬値娼愭繛鍙夛耿閹虫繃銈ｉ崘銊у幒闁瑰吋鐣崹娲磿閻斿吋鐓ユ繝闈涙瀹告繄鈧娲栭悥鐓庮潖缂佹ɑ濯撮柛婵勫劤妤旈梻浣告惈閹冲繘鎮￠敓鐘茬疇婵°倕鎳庨柋鍥煛閸モ晛鏋庡ù?context length exceeded
                            runCatching { llmWrapper.reset() }
                        }
                        if (trimmed == null) {
                            runOnUiThread {
                                Toast
                                    .makeText(this@MainActivity, R.string.ctx_too_long, Toast.LENGTH_LONG)
                                    .show()
                            }
                        } else {
                            Log.d(
                                TAG,
                                "PROMPT n=${chatList.size} droppedTurns=${trimmed.second} len=${trimmed.first.length}",
                            )
                            llmWrapper
                                .generateStreamFlow(
                                    sanitizeForSdk(trimmed.first),
                                    GenerationConfigSample(
                                        maxTokens = effectiveMaxTokens(trimmed.first.length + 600, isNpu),
                                    ).toGenerationConfig(),
                                ).collect { streamResult ->
                                    handleResult(sb, streamResult)
                                }
                        }
                    }

                    clearImages()
                } finally {
                    removeLoadingIndicator()
                    isGenerating = false
                    refreshSendButtonState()
                }
            }
        }

        /*
         * Step 6. others
         */
        btnUnloadModel.setOnClickListener {
            if (isGenerating) {
                Toast.makeText(this@MainActivity, R.string.generating_wait, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!hasLoadedModel()) {
                Toast.makeText(this@MainActivity, R.string.model_not_loaded, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Unload model and cleanup
            val handleUnloadResult = fun(result: Int) {
                resetLoadState()
                runOnUiThread {
                    vTip.visibility = View.GONE
                    btnUnloadModel.visibility = View.GONE
                    btnStop.visibility = View.GONE
                    btnAddImage.visibility = View.INVISIBLE
                    clearImages()
                    updateLoadButtonStyle()
        binding.btnResend.setOnClickListener { resendLastQuestion() }
        binding.btnCleanMemory.setOnClickListener { cleanMemory() }
        updateMemoryBar()
        updateDeviceInfo()

        applyProxyEnv(savedProxy())
        useContext = getSharedPreferences("geniex_ui", MODE_PRIVATE).getBoolean("use_context", true)
                    Toast
                        .makeText(
                            this@MainActivity,
                            if (result == 0) {
                                getString(R.string.unload_success)
                            } else {
                                getString(R.string.unload_failed, result)
                            },
                            Toast.LENGTH_SHORT,
                        ).show()
                    refreshSendButtonState()
                }
            }
            modelScope.launch {
                if (isLoadVlmModel) {
                    vlmWrapper.stopStream()
                    vlmWrapper.destroy()
                    vlmChatList.clear()
                    handleUnloadResult(0)
                } else if (isLoadLlmModel) {
                    llmWrapper.stopStream()
                    llmWrapper.destroy()
                    chatList.clear()
                    handleUnloadResult(0)
                } else {
                    handleUnloadResult(0)
                }
            }
        }
        btnStop.setOnClickListener {
            if (!hasLoadedModel()) {
                Toast
                    .makeText(
                        this@MainActivity,
                        "model not loaded",
                        Toast.LENGTH_SHORT,
                    ).show()
                return@setOnClickListener
            }
            // Stop streaming
            modelScope.launch {
                if (isLoadVlmModel) {
                    vlmWrapper.stopStream()
                } else if (isLoadLlmModel) {
                    llmWrapper.stopStream()
                }
            }
        }
    }

    private fun startLoadModel(selectModelData: ModelData) {
        vTip.visibility = View.VISIBLE
        llLoading.visibility = View.VISIBLE
        beginLoadingUi(selectModelData.displayName)

        val supportPluginIds = selectModelData.getSupportPluginIds()
        Log.d(TAG, "support plugin_id:$supportPluginIds")
        var modelDataPluginId = "llama_cpp"
        var nGpuLayers = 0
        if (supportPluginIds.size > 1) {
            val dialogBinding = DialogSelectPluginIdBinding.inflate(layoutInflater)
            val isGgufLlmModel =
                !selectModelData.isNpuModel() &&
                    (selectModelData.type == "chat" || selectModelData.type == "llm")
            supportPluginIds.forEach {
                when (it) {
                    "cpu" -> {
                        dialogBinding.rbCpu.visibility = View.VISIBLE
                        dialogBinding.rbCpu.isChecked = true
                    }

                    "gpu" -> {
                        dialogBinding.rbGpu.visibility = View.VISIBLE
                    }

                    "npu" -> {
                        dialogBinding.rbNpu.visibility = View.VISIBLE
                        dialogBinding.rbNpu.isChecked = true
                    }
                }
            }
            if (isGgufLlmModel) {
                dialogBinding.rbNpu.visibility = View.VISIBLE
                dialogBinding.rbNpu.isChecked = true
            }
            dialogBinding.rgSelectPluginId.setOnCheckedChangeListener { _, checkedId ->
                dialogBinding.llGpuLayers.visibility =
                    if (checkedId == R.id.rb_gpu) View.VISIBLE else View.GONE
            }

            val dialogOnClickListener =
                object : CustomDialogInterface.OnClickListener() {
                    override fun onClick(
                        dialog: DialogInterface?,
                        which: Int,
                    ) {
                        nGpuLayers = 0
                        var ggufLlmDeviceId: String? = null
                        val checkedId = dialogBinding.rgSelectPluginId.checkedRadioButtonId
                        if (checkedId == R.id.rb_gpu) {
                            if (dialogBinding.llGpuLayers.visibility == View.VISIBLE) {
                                nGpuLayers =
                                    dialogBinding.etGpuLayers.text
                                        .toString()
                                        .toInt()
                                if (nGpuLayers == 0) {
                                    Toast
                                        .makeText(
                                            this@MainActivity,
                                            "nGpuLayers min value is 1",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    return
                                }
                            }
                            ggufLlmDeviceId = ComputeUnitValue.GPU.value
                        } else if (checkedId == R.id.rb_npu) {
                            nGpuLayers = 999
                            ggufLlmDeviceId = ComputeUnitValue.NPU.value
                        } else if (checkedId == R.id.rb_cpu) {
                            ggufLlmDeviceId = ComputeUnitValue.CPU.value
                        }
                        when (which) {
                            DialogInterface.BUTTON_POSITIVE -> {
                                dialog?.dismiss()
                                loadModel(selectModelData, modelDataPluginId, nGpuLayers, ggufLlmDeviceId)
                            }

                            DialogInterface.BUTTON_NEGATIVE -> {
                                llLoading.visibility = View.INVISIBLE
                                vTip.visibility = View.GONE
                            }
                        }
                    }
                }
            val alertDialog =
                AlertDialog
                    .Builder(this)
                    .setView(dialogBinding.root)
                    .setNegativeButton("取消", dialogOnClickListener)
                    .setPositiveButton("确定", dialogOnClickListener)
                    .setCancelable(false)
                    .create()
            alertDialog.show()
            dialogOnClickListener.resetPositiveButton(alertDialog)
        } else {
            if ("npu" == supportPluginIds[0]) {
                modelDataPluginId = "npu"
                showCenterHint(getString(R.string.npu_hint))
            }
            loadModel(selectModelData, modelDataPluginId, nGpuLayers)
        }
    }

    fun handleResult(
        sb: StringBuilder,
        streamResult: LlmStreamResult,
    ) {
        when (streamResult) {
            is LlmStreamResult.Token -> {
                removeLoadingIndicator()
                runOnUiThread {
                    rawStreamSb.append(streamResult.text)
                    var fixed = repairByteLevel(rawStreamSb.toString())
                    // 模型偶尔不吐结束符、自己虚构下一轮对话；见到轮次标记就地截断并停流
                    val endMarkers =
                        listOf(
                            "<|im_end|>",
                            "<\uFF5Cend\u2581of\u2581sentence\uFF5C>",
                            "<|endoftext|>",
                            "<|im_start|>",
                        )
                    val endIdx = endMarkers.map { fixed.indexOf(it) }.filter { it >= 0 }.minOrNull()
                    if (endIdx != null) {
                        fixed = fixed.substring(0, endIdx)
                        rawStreamSb.setLength(0)
                        rawStreamSb.append(fixed)
                        modelScope.launch {
                            runCatching {
                                if (isLoadVlmModel) vlmWrapper.stopStream() else llmWrapper.stopStream()
                            }
                        }
                    }
                    sb.setLength(0)
                    sb.append(fixed)
                    streamingText = fixed
                    val nowUi = System.currentTimeMillis()
                    if (nowUi - lastStreamUiTs >= 80) {
                        lastStreamUiTs = nowUi
                        val lm = binding.rvChat.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
                        val lastIdx = if (messages.isEmpty()) 0 else messages.size - 1
                        val oldH = lm?.findViewByPosition(lastIdx)?.height ?: -1
                        if (messages.isEmpty() || messages.last().type != MessageType.ASSISTANT) {
                            messages.add(Message(fixed, MessageType.ASSISTANT, streaming = true))
                        } else {
                            val thinkSec = ((System.currentTimeMillis() - streamStartTs) / 1000).toInt()
                            messages[messages.size - 1] = Message(fixed, MessageType.ASSISTANT, streaming = true, thinkSeconds = thinkSec)
                        }
                        adapter.notifyItemChanged(messages.size - 1)
                        val idleLongEnough = System.currentTimeMillis() - userTouchingTs > 1200
                        if (pinBottom && idleLongEnough) {
                            val idx = messages.size - 1
                            programmaticScroll = true
                            binding.rvChat.post {
                                val v = lm?.findViewByPosition(idx)
                                if (lm != null && v != null) {
                                    val offset = binding.rvChat.height - v.height
                                    lm.scrollToPositionWithOffset(idx, offset)
                                }
                                binding.rvChat.post { programmaticScroll = false }
                            }
                        }
                    }
                }
                Log.d(TAG, "Token: ${streamResult.text}")
            }

            is LlmStreamResult.Completed -> {
                removeLoadingIndicator()
                if (isLoadVlmModel) {
                    vlmChatList.add(
                        VlmChatMessage(
                            "assistant",
                            listOf(VlmContent("text", sb.toString())),
                        ),
                    )
                } else {
                    chatList.add(ChatMessage("assistant", sb.toString()))
                }
                // Persist the reply for both the LLM and the VLM path; the VLM
                // branch used to skip this, so answers vanished after a restart.
                if (!streamingSaved) recordTurn("assistant", sb.toString())
                streamingSaved = false

                runOnUiThread {
                    val content = sb.toString()
                    val size = messages.size
                    messages[size - 1] = Message(content, MessageType.ASSISTANT)
                    if (pendingRefs.isNotEmpty()) {
                        val refsJson =
                            org.json.JSONArray().apply {
                                pendingRefs.forEach { (t, sn) ->
                                    put(org.json.JSONObject().put("t", t).put("s", sn))
                                }
                            }.toString()
                        messages.add(Message(refsJson, MessageType.REFS))
                        recordTurn("refs", refsJson)
                        pendingRefs = emptyList()
                    }

                    val ttft = String.format(Locale.US, "%.2f", streamResult.profile.ttftMs)
                    val promptTokens = streamResult.profile.promptTokens
                    val prefillSpeed =
                        String.format(Locale.US, "%.2f", streamResult.profile.prefillSpeed)

                    val generatedTokens = streamResult.profile.generatedTokens
                    if (promptTokens <= 1 && generatedTokens >= 50) {
                        resetRuntimeContext()
                    }
                    val decodingSpeed =
                        String.format(Locale.US, "%.2f", streamResult.profile.decodingSpeed)

                    val modelLabel =
                        modelList.firstOrNull { it.id == selectModelId }?.displayName ?: selectModelId
                    val profileData =
                        PROFILE_MODEL_PREFIX +
                        "$modelLabel\nTTFT: $ttft ms; Prompt Tokens: $promptTokens; \nPrefilling Speed: $prefillSpeed tok/s\nGenerated Tokens: $generatedTokens; Decoding Speed: $decodingSpeed tok/s"
                    messages.add(
                        Message(
                            profileData,
                            MessageType.PROFILE,
                        ),
                    )
                    recordTurn("profile", profileData)
                    reloadRecycleView()
                }
                Log.d(TAG, "Completed: ${streamResult.profile}")
            }

            is LlmStreamResult.Error -> {
                removeLoadingIndicator()
                runOnUiThread {
                    val raw = streamResult.throwable.message ?: streamResult.throwable.toString()
                    val reason =
                        if (raw.contains("context length", true)) {
                            "上下文超出模型上限（4096 tokens），已自动重置，请重试"
                        } else {
                            raw
                        }
                    if (messages.lastOrNull()?.content != reason) {
                        messages.add(Message(reason, MessageType.PROFILE))
                    }
                    reloadRecycleView()
                }
                Log.d(TAG, "Error: $streamResult")
            }
        }
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK, null)
        intent.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
        startActivityForResult(intent, 1)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 0) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openGallery()
            } else {
                Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_SHORT).show()
            }
        } else if (requestCode == 2001) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openCamera()
            } else {
                Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        var bitmap: Bitmap? = null
        if (requestCode == 1) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                val inputStream = contentResolver.openInputStream(data.data!!)
                bitmap = BitmapFactory.decodeStream(inputStream)
            }
        } else if (requestCode == 1001 && resultCode == Activity.RESULT_OK) {
            photoFile?.let {
                bitmap = BitmapFactory.decodeFile(it.absolutePath)
            }
        }

        bitmap?.let {
            try {
                val file = File(filesDir, "chat_${System.currentTimeMillis()}.jpg")
                val success = saveBitmapToFile(it, file)
                if (success) {
                    Log.d(TAG, "Save success: ${file.absolutePath}")
                    savedImageFiles.add(file)
                    refreshTopScrollContainer()
                } else {
                    Toast.makeText(this, R.string.save_image_failed, Toast.LENGTH_SHORT).show()
                }
            } catch (e: FileNotFoundException) {
                Log.e(TAG, "save image failed", e)
            }
        }
    }

    private fun saveBitmapToFile(
        bitmap: Bitmap,
        file: File,
    ): Boolean =
        try {
            val tempDir = File(this.filesDir, "tmp").apply { if (!exists()) mkdirs() }

            val tempFile =
                File(
                    tempDir,
                    "tmp_${System.currentTimeMillis()}.jpg",
                )
            FileOutputStream(tempFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
            }

            // Crop straight from the full-size temp file. Pre-downscaling on the
            // *longest* edge first would leave the shorter edge under the target
            // (e.g. 448x355), forcing squareCrop to upscale it back 闂?two lossy
            // resamples for a softer result. squareCrop samples down internally.
            ImgUtil.squareCrop(
                imageFile = tempFile,
                outFile = file,
                size = vlmVisionConfig?.imageSize ?: FALLBACK_VLM_IMAGE_SIZE,
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "saveBitmapToFile failed", e)
            false
        }

    private fun clearHistory() {
        if (isLoadLlmModel) {
            modelScope.launch { llmWrapper.reset() }
        }
        if (isLoadVlmModel) {
            modelScope.launch { vlmWrapper.reset() }
        }
        clearImages()
        sessions.removeAll { it.id == currentSessionId }
        startNewSession()
    }

    private var popupWindow: PopupWindow? = null

    private fun showPopupMenu(anchorView: View) {
        if (popupWindow?.isShowing == true) {
            popupWindow?.dismiss()
            return
        }

        val popupView = LayoutInflater.from(this).inflate(R.layout.menu_layout, null)

        popupWindow =
            PopupWindow(
                popupView,
                anchorView.width * 2,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                true,
            )

        popupWindow?.isOutsideTouchable = true
        popupWindow?.elevation = 10f

        val btnCamera = popupView.findViewById<Button>(R.id.btn_camera)
        val btnPhoto = popupView.findViewById<Button>(R.id.btn_photo)

        btnCamera.setOnClickListener {
            popupWindow?.dismiss()
            checkAndOpenCamera()
        }
        btnPhoto.setOnClickListener {
            popupWindow?.dismiss()
            openGallery()
        }

        popupView.measure(
            View.MeasureSpec.UNSPECIFIED,
            View.MeasureSpec.UNSPECIFIED,
        )
        val popupHeight = popupView.measuredHeight
        popupWindow?.showAsDropDown(anchorView, 0, -anchorView.height - popupHeight)
    }

    private var photoUri: Uri? = null
    private var photoFile: File? = null

    private fun checkAndOpenCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                2001,
            )
        } else {
            openCamera()
        }
    }

    private fun openCamera() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        photoFile =
            File(
                getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                "photo_${System.currentTimeMillis()}.jpg",
            )
        photoUri =
            FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                photoFile!!,
            )

        intent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        startActivityForResult(intent, 1001)
    }

    /** 闂傚倸鍊搁崐鐑芥嚄閸洏鈧焦绻濋崒妤佺亙濠电偞鍨崹娲疾濠靛鐓ユ繝闈涙椤ユ粎绱掗崜浣镐户闁逞屽墮缁犲秹宕曢柆宓ュ洭顢涘鍛殫閻庡箍鍎遍ˇ浼村磻閿濆鐓ｉ煫鍥ㄦ礃閸も偓闂佸憡鏌ㄩ鍥╂閹烘纾兼慨妯哄船閳敻姊洪崫鍕効缂佹彃鈧喓浜欓梻渚€鈧偛鑻晶顕€鎮￠妶澶嬬厪闁割偅绻愮槐妤呮煕閵夘喖澧柦鍐枛閺屾洘寰勯崼婵嗗Б濡炪倧瀵岄崑濠囧蓟閿濆鍋愰柛娆忣槹閸庢捇姊洪崷顓涙嫛闁稿锕獮?files/images闂傚倸鍊搁崐鐑芥倿閿旈敮鍋撶粭娑樻噽閻瑩鏌熺€电浠ч梻鍕閺岋繝宕橀妸銉㈠亾閻熸壆鏆﹂柛娆忣槹閸欏繑淇婇姘仴闁告繃妞介幃妤€顫濋鈥愁棟闂傚洤顦甸弻銊モ攽閸℃銈╅梺缁樺浮缁犳牠寮诲☉婊庢Щ闂佹寧娲︽禍顏勵嚕鐠囨祴妲堟俊顖炴敱閻庡姊鸿ぐ鎺戜喊闁哥姵鐗曢锝夊Ω閳哄倵鎷洪梻鍌氱墐閺呮繄绮旈鈧弻锟犲焵椤掑嫬纭€闁绘垵妫涢崝鐑芥煟閻樺弶鎼愮€殿噮鍓熼幃銏ゆ偂鎼达紕鈧厼顪冮妶鍡樷拻闁告鍛笉闁挎繂顦伴埛鎺懨归敐鍛暈闁诡垰鐗撻幃妤€顫濋悡搴☆潽闂佸搫鎳撻崺鏍矉閹烘柡鍋撻敐搴樺亾?*/
    private fun persistImage(src: java.io.File): java.io.File {
        return runCatching {
            val dir = java.io.File(filesDir, "images").apply { mkdirs() }
            val dst = java.io.File(dir, "img_${System.currentTimeMillis()}_${src.name}")
            src.copyTo(dst, overwrite = true)
            dst
        }.getOrDefault(src)
    }
    private fun clearImages() {
        savedImageFiles.clear()
        refreshTopScrollContainer()
    }

    private fun refreshTopScrollContainer() {
        refreshSendButtonState()
        runOnUiThread {
            topScrollContainer.removeAllViews()
            if (savedImageFiles.isEmpty()) {
                scrollImages.visibility = View.GONE
                return@runOnUiThread
            }

            scrollImages.visibility = View.VISIBLE

            for (file in savedImageFiles) {
                val itemView =
                    LayoutInflater
                        .from(this)
                        .inflate(R.layout.item_image_scroll, topScrollContainer, false)
                val ivImage = itemView.findViewById<ImageView>(R.id.iv_image)
                val btnRemove = itemView.findViewById<ImageButton>(R.id.btn_remove)

                ivImage.setImageURI(Uri.fromFile(file))

                btnRemove.setOnClickListener {
                    savedImageFiles.remove(file)
                    refreshTopScrollContainer()
                }
                topScrollContainer.addView(itemView)
            }
        }
    }

    private fun reloadRecycleView() {
        adapter.notifyDataSetChanged()
    }

    /** 濠电姷鏁告慨鐑藉极閸涘﹥鍙忛柟缁㈠枟閺呮繈鏌曟径鍡樻珕闁搞倕鑻灃闁挎繂鎳庨弳娆撴煛閸曗晛鍔ら柍褜鍓欓崢婊堝磻閹剧粯鐓曢柡鍥ュ妼婢ь噣鏌涢幘鏉戔枙婵﹨娅ｇ槐鎺懳熼懡銈呭汲濠电姰鍨婚幊鎾绘晝椤忓嫮鏆︽繝闈涱儏閻掑灚銇勯幒鎴濐仾闁抽攱鍨堕幈銊╂偡閻楀牊鎮欐繝纰樷偓鐐藉仮闁诡喗顨呴～婵嬫偂鎼粹槅娼鹃梻浣稿悑濡炶姤绂嶉崼鏇犲祦閹兼番鍔嶉崵宥夋煏婢诡垰鍟瓏婵犵绱曢崑鎴﹀磹閵堝鍌ㄩ柣锝呮湰閺嗘粓鏌ｉ幇顔芥毄闁绘粎绮穱濠囧Χ閸涱喖娅ら梺缁樻尰閻╊垶寮诲☉銏╂晝闁挎繂鐗嗛ˉ婵囩箾鐎涙鐭掔紒鐘崇墪椤繐煤椤忓嫬绐涙繝鐢靛Т閹冲酣藟濠靛牏纾藉ù锝夋涧婵偓闂佺顑嗛崝妤呭礆閹烘鐓涢柛娑卞幘椤旀帒顪冮妶鍡橆梿闁稿鍔栭〃銉╁炊椤掍讲鎷洪梺缁樺姌濡嫰宕濆杈╂／缂備降鍨瑰顔锯偓瑙勬穿缂嶄線鐛€ｎ喗鏅滈悷娆欑稻鐎氫粙姊绘笟鈧埀顒傚仜閼活垱鏅剁€涙﹩娈介柣鎰綑婵牓鏌熼娑欘棃濠殿喒鍋撻梺鍐叉惈閸婃劕鈹戦崱蹇旀杸闂佺粯鍔栧娆撴倶閿旇姤鍙忓┑鐘插亞閻撹偐鈧娲戦崡鍐差嚕閹绢喖顫呴柣妯活問濡差垱绻濆▓鍨灍闁靛洦鐩畷鎴﹀箻缂佹鍘靛銈嗙墬绾板秴顔忓┑瀣厓鐟滄粓宕滈妸褏绀婇柛鈩冾焽椤╁弶绻濇繝鍌滃闁稿鍊块弻娑樼暆閳ь剟宕戦悙鍝勭９闁汇垹鎲￠悡鐔兼煟閺傛寧鍟炵紒鑸电叀閺岋繝宕卞▎蹇旂€剧紓浣虹帛閻╊垰鐣锋總鍛婂亜濡炲绨肩槐婵嬫煟鎼淬埄鍟忛柛鐘冲哺瀵偅绻濆銉㈠亾娓氣偓瀵挳濮€閻欌偓濞煎﹪姊洪棃娑氱濠殿噣娼ч埢?*/
    private fun scrollToBottom() {
        if (messages.isEmpty()) return
        programmaticScroll = true
        binding.rvChat.post {
            binding.rvChat.scrollToPosition(messages.size - 1)
            binding.rvChat.scrollBy(0, 100000)
            binding.rvChat.post { programmaticScroll = false }
        }
    }

    private fun showLoadingIndicator() {
        runOnUiThread {
            if (loadingMessageIndex >= 0) return@runOnUiThread
            messages.add(Message("", MessageType.LOADING))
            loadingMessageIndex = messages.size - 1
            reloadRecycleView()
        }
    }

    private fun removeLoadingIndicator() {
        runOnUiThread {
            val idx = loadingMessageIndex
            if (idx < 0 || idx >= messages.size) {
                loadingMessageIndex = -1
                return@runOnUiThread
            }
            if (messages[idx].type == MessageType.LOADING) {
                messages.removeAt(idx)
                adapter.notifyItemRemoved(idx)
            }
            loadingMessageIndex = -1
        }
    }

    companion object {
        private const val TAG = "GenieXDemo"

        /** Prefix of the per-turn profile block; parsed back by [parseProfile]. */
        private const val PROFILE_MODEL_PREFIX = "\u6A21\u578B\uFF1A"

        /**
         * Square edge length used for image preprocessing when the mmproj GGUF
         * does not declare one. Only a fallback 闂?the real value is read per
         * model by [GgufVisionReader], since feeding a tower a smaller square
         * than it was trained on silently discards detail.
         */
        private const val FALLBACK_VLM_IMAGE_SIZE = 448

        /** Room for an image, its answer, and a follow-up turn, over the image cost. */
        private const val VLM_CTX_HEADROOM = 2048

        /** nCtx must be at least this regardless of image cost. */
        private const val VLM_MIN_CTX = 4096

        /**
         * Context size that fits one image of [vision]'s token cost plus room to
         * answer and ask again. Rounded up to a power of two, which is what
         * llama.cpp KV-cache allocation prefers.
         */
        private fun vlmContextSize(vision: GgufVisionConfig?): Int {
            val needed = (vision?.tokenCount ?: 0) + VLM_CTX_HEADROOM
            var ctx = VLM_MIN_CTX
            while (ctx < needed) ctx *= 2
            return ctx
        }
    }
}

data class TurnStat(
    val prompt: Int,
    val generated: Int,
    val ttft: String,
    val prefill: String,
    val decode: String,
    val model: String,
)