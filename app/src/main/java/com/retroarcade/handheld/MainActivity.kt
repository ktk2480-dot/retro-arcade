package com.retroarcade.handheld

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/** The game list: checks every zip in the ROM folder against both cores and starts games. */
class MainActivity : AppCompatActivity() {

    private companion object {
        const val PREFS = "ui_prefs"
        const val PREF_FOLDER = "rom_folder"
        const val PREF_SHOW_UNKNOWN = "show_unknown"

        const val COLOR_OK = 0xFF43A047.toInt()
        const val COLOR_WARN = 0xFFFFB300.toInt()
        const val COLOR_BAD = 0xFFE53935.toInt()
        const val COLOR_UNKNOWN = 0xFF757575.toInt()
        const val COLOR_ACCENT = 0xFFFFC107.toInt()
        const val COLOR_DIM = 0xFF9E9EA8.toInt()
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var folderLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var emptyLabel: TextView
    private lateinit var searchBox: EditText
    private lateinit var listView: ListView
    private lateinit var showUnknown: CheckBox
    private lateinit var permissionPanel: LinearLayout
    private lateinit var contentPanel: LinearLayout

    private val adapter = RomAdapter()
    private val worker = Executors.newSingleThreadExecutor()
    private var allItems: List<RomItem> = emptyList()
    private var biosSets: List<String> = emptyList()
    private var needsScan = true
    private var scanning = false
    private var scanToken = 0

    private val gameLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            onGameResult(result.resultCode, result.data)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        setContentView(buildUi())
        refreshFolderLabel()
    }

    override fun onResume() {
        super.onResume()
        val granted = hasStorageAccess()
        permissionPanel.visibility = if (granted) View.GONE else View.VISIBLE
        contentPanel.visibility = if (granted) View.VISIBLE else View.GONE
        if (granted && needsScan) startScan()
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        needsScan = true
    }

    // ---- screen -----------------------------------------------------------------------------

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun label(text: String, sizeSp: Float, color: Int? = null): TextView {
        val view = TextView(this)
        view.text = text
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (color != null) view.setTextColor(color)
        return view
    }

    private fun buildUi(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.fitsSystemWindows = true
        root.setPadding(dp(16), dp(12), dp(16), dp(8))

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val title = label("RETRO ARCADE", 22f, COLOR_ACCENT)
        title.typeface = Typeface.DEFAULT_BOLD
        title.letterSpacing = 0.08f
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val help = Button(this)
        help.text = "도움말"
        help.setOnClickListener { showHelp() }
        header.addView(help, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(header)

        // Shown until the app may read the ROM folder.
        permissionPanel = LinearLayout(this)
        permissionPanel.orientation = LinearLayout.VERTICAL
        permissionPanel.setPadding(0, dp(24), 0, 0)
        permissionPanel.addView(
            label(
                "ROM 파일을 읽으려면 저장소 접근 권한이 필요합니다.\n\n" +
                    "아케이드 에뮬레이터는 ROM zip을 파일 경로로 직접 열기 때문에 " +
                    "'모든 파일에 대한 접근'을 허용해야 합니다. 이 앱은 인터넷에 접속하지 않습니다.",
                15f,
            )
        )
        val grant = Button(this)
        grant.text = "저장소 접근 허용"
        grant.setOnClickListener { requestStorageAccess() }
        val grantParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        grantParams.topMargin = dp(16)
        permissionPanel.addView(grant, grantParams)
        root.addView(permissionPanel)

        contentPanel = LinearLayout(this)
        contentPanel.orientation = LinearLayout.VERTICAL

        folderLabel = label("", 13f, COLOR_DIM)
        folderLabel.setPadding(0, dp(4), 0, dp(4))
        folderLabel.setSingleLine(true)
        folderLabel.ellipsize = TextUtils.TruncateAt.START
        contentPanel.addView(folderLabel)

        val tools = LinearLayout(this)
        tools.orientation = LinearLayout.HORIZONTAL
        tools.gravity = Gravity.CENTER_VERTICAL
        searchBox = EditText(this)
        searchBox.hint = "게임 검색"
        searchBox.setSingleLine(true)
        searchBox.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyFilter()
            }
        })
        tools.addView(searchBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val folderButton = Button(this)
        folderButton.text = "폴더"
        folderButton.setOnClickListener { pickFolder() }
        tools.addView(folderButton)
        val refreshButton = Button(this)
        refreshButton.text = "새로고침"
        refreshButton.setOnClickListener { startScan() }
        tools.addView(refreshButton)
        contentPanel.addView(tools)

        statusLabel = label("", 13f, COLOR_DIM)
        statusLabel.setPadding(0, dp(4), 0, dp(6))
        contentPanel.addView(statusLabel)

        listView = ListView(this)
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            adapter.itemAt(position)?.let { onItemTapped(it) }
        }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            adapter.itemAt(position)?.let { chooseCore(it) }
            true
        }
        contentPanel.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        emptyLabel = label("", 15f)
        emptyLabel.setPadding(0, dp(24), 0, 0)
        emptyLabel.visibility = View.GONE
        contentPanel.addView(emptyLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        showUnknown = CheckBox(this)
        showUnknown.text = "인식되지 않는 압축 파일도 표시"
        showUnknown.isChecked = prefs.getBoolean(PREF_SHOW_UNKNOWN, false)
        showUnknown.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(PREF_SHOW_UNKNOWN, checked).apply()
            applyFilter()
        }
        contentPanel.addView(showUnknown)

        root.addView(contentPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun showHelp() {
        val missingCores = Core.values().filter { !it.isBundled(this) }.joinToString(", ") { it.label }
        val coreNote = if (missingCores.isEmpty()) "" else "\n\n※ 이 빌드에는 다음 코어가 빠져 있습니다: $missingCores"
        AlertDialog.Builder(this)
            .setTitle("사용 방법")
            .setMessage(
                "1. 직접 보유한 아케이드 ROM(zip)을 한 폴더에 넣습니다. 압축은 풀지 않습니다.\n" +
                    "2. '폴더'에서 그 폴더를 고르면 목록에 나타납니다.\n" +
                    "3. 게임을 누르면 맞는 코어로 실행합니다. 길게 누르면 코어를 직접 고를 수 있습니다.\n\n" +
                    "● 초록: 실행 가능  ● 노랑: 시도 가능  ● 빨강: 롬셋 불일치\n\n" +
                    "· ROM은 코어 버전과 세트가 맞아야 합니다. FBNeo는 최신 세트, " +
                    "MAME 2003-Plus는 0.78 계열 세트를 사용합니다.\n" +
                    "· 네오지오 등은 BIOS zip(neogeo.zip)이 같은 폴더에 있어야 합니다.\n" +
                    "· 게임 중 MENU(또는 뒤로가기): 상태 저장/불러오기, CRT 필터, 회전 고정.\n" +
                    "· 버튼: '버튼 배열'로 3·4·6버튼을 고르고, '버튼 지정'으로 화면 버튼이 누를 " +
                    "게임 버튼을 바꾸며, '버튼 위치 편집'으로 끌어서 옮깁니다.\n" +
                    "· 조이스틱: 상하·좌우 2방향, 4방향, 8방향, 16방향(아날로그 게임용) 중에서 고릅니다.\n" +
                    "· 상태 저장은 FBNeo에서 안정적이며, MAME 2003-Plus는 게임에 따라 지원하지 않습니다." +
                    coreNote
            )
            .setPositiveButton("확인", null)
            .show()
    }

    // ---- storage access and folder ----------------------------------------------------------

    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    private fun requestStorageAccess() {
        needsScan = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                )
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (_: Exception) {
                    toast("설정 > 앱 > 레트로 아케이드 > 권한에서 '모든 파일 접근'을 허용해 주세요.")
                }
            }
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1)
        }
    }

    /** Defaults to the Download folder, where a browser puts downloaded files. */
    @Suppress("DEPRECATION")
    private fun romFolder(): File {
        val saved = prefs.getString(PREF_FOLDER, null)
        if (saved != null) return File(saved)
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    }

    private fun refreshFolderLabel() {
        folderLabel.text = "ROM 폴더: " + romFolder().absolutePath
    }

    private fun pickFolder() {
        FolderPicker.show(this, romFolder()) { picked ->
            prefs.edit().putString(PREF_FOLDER, picked.absolutePath).apply()
            refreshFolderLabel()
            startScan()
        }
    }

    // ---- scanning and list ------------------------------------------------------------------

    private fun startScan() {
        if (!hasStorageAccess()) return
        needsScan = false
        scanning = true
        val token = ++scanToken
        val dir = romFolder()
        statusLabel.text = "ROM을 확인하는 중…"
        val appContext = applicationContext
        worker.execute {
            val result = try {
                RomScanner.scan(dir, GameDb.get(appContext))
            } catch (_: Exception) {
                ScanResult(emptyList(), emptyList())
            }
            runOnUiThread {
                if (token == scanToken && !isDestroyed) {
                    scanning = false
                    allItems = result.items
                    biosSets = result.biosSets
                    applyFilter()
                }
            }
        }
    }

    private fun applyFilter() {
        if (scanning || !::showUnknown.isInitialized) return
        val query = searchBox.text.toString().trim().lowercase(Locale.ROOT)
        val withUnknown = showUnknown.isChecked
        val shown = allItems.filter { item ->
            (withUnknown || item.known) &&
                (query.isEmpty() ||
                    item.title.lowercase(Locale.ROOT).contains(query) ||
                    item.name.contains(query))
        }
        adapter.setItems(shown)

        val known = allItems.count { it.known }
        val ready = allItems.count { it.rank == 0 }
        val status = StringBuilder("게임 ${known}개 · 실행 가능 ${ready}개")
        if (biosSets.isNotEmpty()) status.append(" · BIOS: ").append(biosSets.joinToString(", "))
        statusLabel.text = status

        if (shown.isEmpty()) {
            emptyLabel.text = if (allItems.isEmpty()) {
                "이 폴더에서 ROM(zip)을 찾지 못했습니다.\n\n" +
                    "· ROM zip 파일을 이 폴더에 넣거나\n" +
                    "· '폴더'를 눌러 ROM이 있는 폴더를 선택하세요.\n\n" +
                    "zip은 압축을 풀지 않고 그대로 둡니다."
            } else {
                "조건에 맞는 게임이 없습니다."
            }
            emptyLabel.visibility = View.VISIBLE
            listView.visibility = View.GONE
        } else {
            emptyLabel.visibility = View.GONE
            listView.visibility = View.VISIBLE
        }
    }

    /** One-line verdict for a ROM plus the colour of its status dot. */
    private fun describe(item: RomItem): Pair<String, Int> {
        val file = item.file.name
        val best = item.best ?: return Pair("$file · 인식되지 않는 파일", COLOR_UNKNOWN)
        val core = best.core.label
        return when {
            !item.verifiable -> Pair("$file · $core · 7z는 내용 확인 생략", COLOR_WARN)
            best.playable && best.game.isPreliminary -> Pair("$file · $core · 에뮬레이션 미완성 게임", COLOR_WARN)
            best.playable -> Pair("$file · $core", COLOR_OK)
            best.missing == 0 -> Pair("$file · $core · BIOS 필요: ${best.game.bios}.zip", COLOR_WARN)
            best.parentMissing -> Pair("$file · 부모 ROM 필요: ${best.game.parent}.zip", COLOR_BAD)
            else -> Pair("$file · 롬셋 불일치 ($core 기준 ${best.percent}% 일치, ${best.missing}개 누락)", COLOR_BAD)
        }
    }

    private fun coreVerdict(item: RomItem, core: Core): String {
        if (!core.isBundled(this)) return "이 빌드에 없음"
        val match = item.matchFor(core) ?: return if (item.known) "이 코어의 목록에 없음" else "확인 불가"
        return when {
            !item.verifiable -> "내용 확인 생략"
            match.playable -> "실행 가능"
            match.missing == 0 -> "BIOS 필요 (${match.game.bios}.zip)"
            else -> "${match.percent}% 일치, ${match.missing}개 누락"
        }
    }

    private fun onItemTapped(item: RomItem) {
        val best = item.best
        if (best == null) {
            chooseCore(item)
            return
        }
        if (item.rank <= 1 && best.core.isBundled(this)) {
            launch(item, best.core)
            return
        }
        val (verdict, _) = describe(item)
        AlertDialog.Builder(this)
            .setTitle(item.title)
            .setMessage("$verdict\n\n이 상태로는 실행되지 않을 가능성이 높습니다.")
            .setPositiveButton("그래도 실행") { _, _ -> launch(item, best.core) }
            .setNeutralButton("코어 선택") { _, _ -> chooseCore(item) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun chooseCore(item: RomItem) {
        val cores = Core.values()
        val labels = Array(cores.size) { i -> cores[i].label + "  ·  " + coreVerdict(item, cores[i]) }
        AlertDialog.Builder(this)
            .setTitle(item.title)
            .setItems(labels) { _, which -> launch(item, cores[which]) }
            .setNegativeButton("취소", null)
            .show()
    }

    // ---- starting games ---------------------------------------------------------------------

    private fun launch(item: RomItem, core: Core) {
        if (!core.isBundled(this)) {
            toast("이 빌드에는 ${core.label} 코어가 들어 있지 않습니다.")
            return
        }
        val game = item.matchFor(core)?.game
        val other = Core.values().firstOrNull { it != core && it.isBundled(this) && item.matchFor(it) != null }
        val intent = Intent(this, GameActivity::class.java)
            .putExtra(GameActivity.EXTRA_ROM_PATH, item.file.absolutePath)
            .putExtra(GameActivity.EXTRA_CORE, core.id)
            .putExtra(GameActivity.EXTRA_ALT_CORE, other?.id ?: "")
            .putExtra(GameActivity.EXTRA_TITLE, game?.title ?: item.title)
            .putExtra(GameActivity.EXTRA_VERTICAL, game?.isVertical ?: false)
            .putExtra(GameActivity.EXTRA_BUTTONS, game?.buttons ?: GameDb.BUTTONS_UNKNOWN)
            .putExtra(GameActivity.EXTRA_STICK, game?.stick ?: GameDb.STICK_UNKNOWN)
        gameLauncher.launch(intent)
    }

    private fun onGameResult(resultCode: Int, data: Intent?) {
        when (resultCode) {
            Activity.RESULT_OK, GameActivity.RESULT_LOAD_FAILED -> RunMarker.clear(this)
            GameActivity.RESULT_RETRY -> {
                val extras = data?.extras
                if (extras != null) {
                    gameLauncher.launch(Intent(this, GameActivity::class.java).putExtras(extras))
                }
            }
            else -> {
                // The game process went away without saying goodbye.
                val state = RunMarker.read(this)
                RunMarker.clear(this)
                if (state == RunMarker.LOADING) {
                    AlertDialog.Builder(this)
                        .setTitle("게임이 시작 중에 종료되었습니다")
                        .setMessage(
                            "ROM 세트가 선택한 코어와 맞지 않을 때 주로 발생합니다.\n\n" +
                                "게임을 길게 눌러 다른 코어로 실행해 보거나, 해당 코어 버전에 맞는 ROM 세트를 사용하세요."
                        )
                        .setPositiveButton("확인", null)
                        .show()
                }
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // ---- list rows --------------------------------------------------------------------------

    private class RowViews(val dot: View, val title: TextView, val subtitle: TextView)

    private inner class RomAdapter : BaseAdapter() {
        private var items: List<RomItem> = emptyList()

        fun setItems(list: List<RomItem>) {
            items = list
            notifyDataSetChanged()
        }

        fun itemAt(position: Int): RomItem? = items.getOrNull(position)

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = convertView ?: buildRow()
            val views = row.tag as RowViews
            val item = items[position]
            val (verdict, color) = describe(item)
            views.title.text = item.title
            views.subtitle.text = verdict
            (views.dot.background as GradientDrawable).setColor(color)
            return row
        }

        private fun buildRow(): View {
            val row = LinearLayout(this@MainActivity)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(4), dp(10), dp(4), dp(10))

            val dot = View(this@MainActivity)
            val circle = GradientDrawable()
            circle.shape = GradientDrawable.OVAL
            circle.setColor(COLOR_UNKNOWN)
            dot.background = circle
            val dotParams = LinearLayout.LayoutParams(dp(12), dp(12))
            dotParams.rightMargin = dp(12)
            row.addView(dot, dotParams)

            val texts = LinearLayout(this@MainActivity)
            texts.orientation = LinearLayout.VERTICAL
            val title = label("", 16f)
            title.setSingleLine(true)
            title.ellipsize = TextUtils.TruncateAt.END
            val subtitle = label("", 12.5f, COLOR_DIM)
            subtitle.setSingleLine(true)
            subtitle.ellipsize = TextUtils.TruncateAt.END
            texts.addView(title)
            texts.addView(subtitle)
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            row.tag = RowViews(dot, title, subtitle)
            return row
        }
    }
}
