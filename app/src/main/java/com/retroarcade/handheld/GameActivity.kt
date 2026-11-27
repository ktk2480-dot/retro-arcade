package com.retroarcade.handheld

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.ShaderConfig
import com.swordfish.libretrodroid.Variable
import com.swordfish.libretrodroid.ViewportAlignment
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.system.exitProcess

/** Runs one game. Lives in the ":game" process (see the manifest). */
class GameActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ROM_PATH = "rom_path"
        const val EXTRA_CORE = "core"
        const val EXTRA_ALT_CORE = "alt_core"
        const val EXTRA_TITLE = "title"
        const val EXTRA_VERTICAL = "vertical"
        const val EXTRA_BUTTONS = "buttons"

        /** The game could not be loaded; the reason was already shown here. */
        const val RESULT_LOAD_FAILED = Activity.RESULT_FIRST_USER

        /** The player asked to run the same ROM with the core named in the result extras. */
        const val RESULT_RETRY = Activity.RESULT_FIRST_USER + 1

        private const val PREFS = "game_prefs"
        private const val PREF_CRT = "crt_filter"
        private const val PREF_BUTTON_MODE_PREFIX = "buttons_"
        private const val PREF_ROTATION = "rotation"

        private const val BUTTONS_AUTO = 0
        private const val ROTATION_AUTO = 0
        private const val ROTATION_PORTRAIT = 1
        private const val ROTATION_LANDSCAPE = 2

        private const val SLOT_COUNT = 3

        // MAME 2003-Plus controller types: RETRO_DEVICE_JOYPAD and its "6-Button" subclass.
        private const val MAME_PAD_CLASSIC = 1
        private const val MAME_PAD_6BUTTON = (3 shl 8) or 1

        // The game's buttons 1-6 in the order both cores hand them out, as RetroPad keys.
        private val GAME_BUTTON_KEYS = intArrayOf(
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_BUTTON_R1,
        )

        // Which game button (index above) each on-screen slot presses by default.
        // One row and two-by-two simply count up: A B C (D) = buttons 1 2 3 (4).
        private val DEFAULT_SLOTS_3 = intArrayOf(0, 1, 2)
        private val DEFAULT_SLOTS_4 = intArrayOf(0, 1, 2, 3)

        // Six buttons keep the fighting-game panel both cores expect:
        // bottom row A B C = B, A, R and top row X Y Z = Y, X, L.
        private val DEFAULT_SLOTS_6 = intArrayOf(0, 1, 5, 2, 3, 4)
    }

    private lateinit var retroView: GLRetroView
    private lateinit var console: ConsoleLayout
    private lateinit var core: Core
    private lateinit var romFile: File
    private lateinit var prefs: SharedPreferences
    private lateinit var padStore: PadStore

    /** The player's own button assignment for this game (slot -> game button, -1 = default). */
    private var keyMap = IntArray(PadStore.SLOTS) { -1 }

    private var gameTitle = ""
    private var gameButtons = GameDb.BUTTONS_UNKNOWN
    private var crtEnabled = true
    private var loaded = false
    private var closing = false
    private var failureShown = false
    private var openDialogs = 0

    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val path = intent.getStringExtra(EXTRA_ROM_PATH)
        val requestedCore = Core.byId(intent.getStringExtra(EXTRA_CORE))
        if (path == null || requestedCore == null) {
            finish()
            return
        }
        core = requestedCore
        romFile = File(path)
        gameTitle = intent.getStringExtra(EXTRA_TITLE) ?: romFile.name
        gameButtons = intent.getIntExtra(EXTRA_BUTTONS, GameDb.BUTTONS_UNKNOWN)
        val verticalGame = intent.getBooleanExtra(EXTRA_VERTICAL, false)

        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        padStore = PadStore(prefs)
        crtEnabled = prefs.getBoolean(PREF_CRT, true)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val attributes = window.attributes
            attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = attributes
        }
        applyRotationPreference()

        RunMarker.set(this, RunMarker.LOADING)

        val systemDir = File(filesDir, "system")
        val savesDir = File(filesDir, "saves")
        systemDir.mkdirs()
        savesDir.mkdirs()
        if (core == Core.MAME2003PLUS) {
            File(systemDir, "mame2003-plus").mkdirs()
            for (sub in arrayOf("cfg", "nvram", "hi", "memcard", "diff", "ctrlr", "inp", "snap")) {
                File(savesDir, "mame2003-plus/$sub").mkdirs()
            }
        } else {
            File(systemDir, "fbneo").mkdirs()
            File(savesDir, "fbneo").mkdirs()
        }

        val portraitNow = resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        val data = GLRetroViewData(this).apply {
            coreFilePath = core.libraryPath(this@GameActivity)
            gameFilePath = romFile.absolutePath
            systemDirectory = systemDir.absolutePath
            savesDirectory = savesDir.absolutePath
            variables = coreVariables()
            shader = if (crtEnabled) ShaderConfig.CRT else ShaderConfig.Default
            viewportAlignment = if (portraitNow) ViewportAlignment.TOP else ViewportAlignment.CENTER
            rumbleEventsEnabled = false
            preferLowLatencyAudio = true
        }
        retroView = GLRetroView(this, data)
        lifecycle.addObserver(retroView)

        console = ConsoleLayout(this, retroView, verticalGame, padStore)
        console.buttonCount = effectiveButtonCount()
        keyMap = padStore.loadKeyMap(gameKey(), console.buttonCount)
        console.onArrangementChanged = { landscape ->
            // Upright, the picture hugs the top of its area; sideways it is centred.
            val alignment = if (landscape) ViewportAlignment.CENTER else ViewportAlignment.TOP
            retroView.queueEvent {
                try {
                    retroView.viewportAlignment = alignment
                } catch (_: Throwable) {
                }
            }
        }
        console.gamepad.listener = object : GamepadView.Listener {
            override fun onStick(x: Int, y: Int) {
                retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, x.toFloat(), y.toFloat(), 0)
            }

            override fun onButton(id: Int, pressed: Boolean) {
                val keyCode = keyCodeFor(id)
                if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                    val action = if (pressed) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
                    retroView.sendKeyEvent(action, keyCode, 0)
                }
            }

            override fun onMenu() {
                openMenu()
            }

            override fun onEditFinished() {
                setEmulationPaused(false)
                hideSystemBars()
            }
        }
        setContentView(console)
        hideSystemBars()

        lifecycleScope.launch {
            retroView.getGLRetroErrors().collect { code -> onCoreError(code) }
        }
        lifecycleScope.launch {
            retroView.getGLRetroEvents().first { it is GLRetroView.GLRetroEvents.FrameRendered }
            onFirstFrame()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (console.gamepad.editing) {
                    console.gamepad.stopEditing()
                    setEmulationPaused(false)
                } else {
                    openMenu()
                }
            }
        })
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::console.isInitialized) hideSystemBars()
    }

    override fun onPause() {
        if (::console.isInitialized) console.gamepad.releaseAll()
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
        // Cores keep global state, so the next game must start in a fresh process.
        if (isFinishing) exitProcess(0)
    }

    // ---- core setup -------------------------------------------------------------------------

    private fun coreVariables(): Array<Variable> = when (core) {
        Core.MAME2003PLUS -> arrayOf(
            Variable("mame2003-plus_skip_disclaimer", "enabled"),
            Variable("mame2003-plus_skip_warnings", "enabled"),
        )
        // By default FBNeo opens the cabinet's service menu when START is held down,
        // which is far too easy to do by accident on a touch screen.
        Core.FBNEO -> arrayOf(
            Variable("fbneo-diagnostic-input", "None"),
        )
    }

    private fun gameKey(): String = core.id + "_" + romFile.nameWithoutExtension

    private fun defaultSlots(): IntArray = when (console.buttonCount) {
        3 -> DEFAULT_SLOTS_3
        4 -> DEFAULT_SLOTS_4
        else -> DEFAULT_SLOTS_6
    }

    /** Index of the game button an on-screen slot presses: the player's choice, else the default. */
    private fun gameButtonFor(slot: Int): Int {
        val chosen = keyMap.getOrElse(slot) { -1 }
        if (chosen >= 0 && chosen < GAME_BUTTON_KEYS.size) return chosen
        return defaultSlots().getOrElse(slot) { -1 }
    }

    private fun keyCodeFor(id: Int): Int = when (id) {
        ConsoleGeometry.ID_COIN -> KeyEvent.KEYCODE_BUTTON_SELECT
        ConsoleGeometry.ID_START -> KeyEvent.KEYCODE_BUTTON_START
        else -> {
            val index = gameButtonFor(id)
            if (index >= 0) GAME_BUTTON_KEYS[index] else KeyEvent.KEYCODE_UNKNOWN
        }
    }

    private fun onFirstFrame() {
        loaded = true
        RunMarker.set(this, RunMarker.RUNNING)
        if (core == Core.MAME2003PLUS) {
            // Six-button games get MAME's 6-button pad so punches sit on the top row
            // and kicks on the bottom row, like the arcade panel.
            val type = if (gameButtons in 5..20) MAME_PAD_6BUTTON else MAME_PAD_CLASSIC
            retroView.queueEvent {
                try {
                    retroView.setControllerType(0, type)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun onCoreError(code: Int) {
        if (closing || failureShown) return
        if (loaded && code == GLRetroView.ERROR_GENERIC) return
        failureShown = true
        RunMarker.clear(this)

        val message = when (code) {
            GLRetroView.ERROR_LOAD_GAME ->
                "게임을 불러오지 못했습니다.\n\n" +
                    "· ROM 세트가 ${core.label} 버전과 맞지 않거나\n" +
                    "· 부모 ROM / BIOS zip(예: neogeo.zip)이 같은 폴더에 없을 때 생깁니다."
            GLRetroView.ERROR_LOAD_LIBRARY ->
                "에뮬레이터 코어(${core.label})를 불러오지 못했습니다."
            GLRetroView.ERROR_GL_NOT_COMPATIBLE ->
                "이 기기의 그래픽(OpenGL ES) 버전에서는 실행할 수 없습니다."
            else ->
                "에뮬레이터 오류가 발생했습니다. (코드 $code)"
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(gameTitle)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("닫기") { _, _ -> closeGame(RESULT_LOAD_FAILED, null) }

        val alt = Core.byId(intent.getStringExtra(EXTRA_ALT_CORE))
        if (code == GLRetroView.ERROR_LOAD_GAME && alt != null && alt != core) {
            builder.setNeutralButton("${alt.label}(으)로 실행") { _, _ ->
                val retry = Intent()
                intent.extras?.let { retry.putExtras(it) }
                retry.putExtra(EXTRA_CORE, alt.id)
                retry.putExtra(EXTRA_ALT_CORE, "")
                closeGame(RESULT_RETRY, retry)
            }
        }
        if (!isFinishing) builder.show()
    }

    // ---- menu -------------------------------------------------------------------------------

    private fun openMenu() {
        if (closing || failureShown || openDialogs > 0 || console.gamepad.editing) return
        console.gamepad.releaseAll()
        val items = arrayOf(
            "계속하기",
            "상태 저장",
            "상태 불러오기",
            "CRT 스캔라인 필터: " + (if (crtEnabled) "켜짐" else "꺼짐"),
            "버튼 배열: " + buttonModeLabel(),
            "버튼 지정 (이 게임)",
            "버튼 위치 편집",
            "화면 회전: " + rotationLabel(),
            "게임 리셋",
            "게임 종료",
        )
        val builder = AlertDialog.Builder(this)
            .setTitle(gameTitle)
            .setItems(items) { _, which ->
                when (which) {
                    1 -> chooseSlot(true)
                    2 -> chooseSlot(false)
                    3 -> toggleCrt()
                    4 -> chooseButtonMode()
                    5 -> chooseRemapSlot()
                    6 -> console.gamepad.startEditing()
                    7 -> cycleRotation()
                    8 -> resetGame()
                    9 -> closeGame(Activity.RESULT_OK, null)
                    else -> Unit
                }
            }
        showPaused(builder)
    }

    /** Shows a dialog and holds the game still for as long as any dialog is open. */
    private fun showPaused(builder: AlertDialog.Builder) {
        if (isFinishing) return
        val dialog = builder.create()
        dialog.setOnDismissListener {
            openDialogs--
            if (openDialogs <= 0) {
                openDialogs = 0
                if (!closing) {
                    // The layout editor keeps the game held until "done".
                    if (!console.gamepad.editing) setEmulationPaused(false)
                    hideSystemBars()
                }
            }
        }
        openDialogs++
        if (openDialogs == 1) setEmulationPaused(true)
        dialog.show()
    }

    private fun setEmulationPaused(paused: Boolean) {
        try {
            if (paused) {
                // The core advances one step per drawn frame, so drawing on demand stops it.
                retroView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                retroView.audioEnabled = false
            } else {
                retroView.audioEnabled = true
                retroView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }
        } catch (_: Throwable) {
        }
    }

    private fun resetGame() {
        retroView.queueEvent {
            try {
                retroView.reset(false)
            } catch (_: Throwable) {
            }
        }
    }

    private fun toggleCrt() {
        crtEnabled = !crtEnabled
        prefs.edit().putBoolean(PREF_CRT, crtEnabled).apply()
        val config: ShaderConfig = if (crtEnabled) ShaderConfig.CRT else ShaderConfig.Default
        retroView.queueEvent {
            try {
                retroView.shader = config
            } catch (_: Throwable) {
            }
        }
    }

    // ---- button layout and rotation ---------------------------------------------------------

    /** The arrangement is remembered per game: 0 = automatic, else 3, 4 or 6. */
    private fun buttonMode(): Int = prefs.getInt(PREF_BUTTON_MODE_PREFIX + gameKey(), BUTTONS_AUTO)

    private fun autoButtonCount(): Int = when {
        gameButtons == GameDb.BUTTONS_UNKNOWN || gameButtons >= 5 -> 6
        gameButtons == 4 -> 4
        else -> 3
    }

    private fun effectiveButtonCount(): Int = when (buttonMode()) {
        3 -> 3
        4 -> 4
        6 -> 6
        else -> autoButtonCount()
    }

    private fun buttonModeLabel(): String = when (buttonMode()) {
        3 -> "3버튼"
        4 -> "4버튼"
        6 -> "6버튼"
        else -> "자동 (${autoButtonCount()}버튼)"
    }

    private fun chooseButtonMode() {
        val modes = intArrayOf(BUTTONS_AUTO, 3, 4, 6)
        val labels = arrayOf(
            "자동 (이 게임: ${autoButtonCount()}버튼)",
            "3버튼 · 한 줄  A B C",
            "4버튼 · 두 줄  C D / A B",
            "6버튼 · 두 줄  X Y Z / A B C",
        )
        val builder = AlertDialog.Builder(this)
            .setTitle("버튼 배열 (이 게임)")
            .setItems(labels) { _, which ->
                prefs.edit().putInt(PREF_BUTTON_MODE_PREFIX + gameKey(), modes[which]).apply()
                console.gamepad.releaseAll()
                console.buttonCount = effectiveButtonCount()
                keyMap = padStore.loadKeyMap(gameKey(), console.buttonCount)
            }
            .setNegativeButton("취소", null)
        showPaused(builder)
    }

    private fun slotNames(count: Int): Array<String> = when (count) {
        3 -> arrayOf("A", "B", "C")
        4 -> arrayOf("A", "B", "C", "D")
        else -> arrayOf("A", "B", "C", "X", "Y", "Z")
    }

    /** Lets the player choose which game button each on-screen button presses, for this game only. */
    private fun chooseRemapSlot() {
        val count = console.buttonCount
        val names = slotNames(count)
        val labels = Array(names.size) { slot ->
            "화면 ${names[slot]}  →  게임 버튼 ${gameButtonFor(slot) + 1}"
        }
        val builder = AlertDialog.Builder(this)
            .setTitle("버튼 지정 (이 게임)")
            .setItems(labels) { _, slot -> chooseGameButton(slot, names[slot]) }
            .setNeutralButton("기본값으로") { _, _ ->
                padStore.clearKeyMap(gameKey(), count)
                keyMap = padStore.loadKeyMap(gameKey(), count)
                toast("버튼 지정을 기본값으로 되돌렸습니다.")
            }
            .setPositiveButton("닫기", null)
        showPaused(builder)
    }

    private fun chooseGameButton(slot: Int, name: String) {
        val count = console.buttonCount
        val labels = Array(GAME_BUTTON_KEYS.size) { index -> "게임 버튼 ${index + 1}" }
        val builder = AlertDialog.Builder(this)
            .setTitle("화면 $name 버튼이 누를 게임 버튼")
            .setSingleChoiceItems(labels, gameButtonFor(slot)) { dialog, which ->
                if (slot >= 0 && slot < keyMap.size) {
                    keyMap[slot] = which
                    padStore.saveKeyMap(gameKey(), count, keyMap)
                }
                dialog.dismiss()
                chooseRemapSlot()
            }
            .setNegativeButton("취소") { _, _ -> chooseRemapSlot() }
        showPaused(builder)
    }

    private fun rotationLabel(): String = when (prefs.getInt(PREF_ROTATION, ROTATION_AUTO)) {
        ROTATION_PORTRAIT -> "세로 고정"
        ROTATION_LANDSCAPE -> "가로 고정"
        else -> "자동"
    }

    private fun cycleRotation() {
        val next = when (prefs.getInt(PREF_ROTATION, ROTATION_AUTO)) {
            ROTATION_AUTO -> ROTATION_PORTRAIT
            ROTATION_PORTRAIT -> ROTATION_LANDSCAPE
            else -> ROTATION_AUTO
        }
        prefs.edit().putInt(PREF_ROTATION, next).apply()
        applyRotationPreference()
        toast("화면 회전: " + rotationLabel())
    }

    private fun applyRotationPreference() {
        requestedOrientation = when (prefs.getInt(PREF_ROTATION, ROTATION_AUTO)) {
            ROTATION_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            ROTATION_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
        }
    }

    // ---- save states ------------------------------------------------------------------------

    private fun stateFile(slot: Int): File =
        File(filesDir, "states/${core.id}/${romFile.nameWithoutExtension}.slot$slot")

    private fun chooseSlot(save: Boolean) {
        val format = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val labels = Array(SLOT_COUNT) { index ->
            val file = stateFile(index + 1)
            val detail = if (file.exists()) format.format(Date(file.lastModified())) else "비어 있음"
            "슬롯 ${index + 1}  ·  $detail"
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(if (save) "상태 저장" else "상태 불러오기")
            .setItems(labels) { _, which ->
                if (save) saveState(which + 1) else loadState(which + 1)
            }
            .setNegativeButton("취소", null)
        showPaused(builder)
    }

    private fun saveState(slot: Int) {
        val target = stateFile(slot)
        io.execute {
            // The library takes its core lock for this call, so it is safe off the render thread.
            val ok = try {
                val bytes = retroView.serializeState(false)
                if (bytes.isEmpty()) {
                    false
                } else {
                    target.parentFile?.mkdirs()
                    target.writeBytes(bytes)
                    true
                }
            } catch (_: Throwable) {
                false
            }
            runOnUiThread {
                toast(if (ok) "슬롯 $slot 에 저장했습니다." else "이 게임은 상태 저장을 지원하지 않습니다.")
            }
        }
    }

    private fun loadState(slot: Int) {
        val source = stateFile(slot)
        if (!source.exists()) {
            toast("슬롯 $slot 은 비어 있습니다.")
            return
        }
        io.execute {
            val ok = try {
                retroView.unserializeState(source.readBytes(), false)
            } catch (_: Throwable) {
                false
            }
            runOnUiThread {
                toast(if (ok) "슬롯 $slot 을 불러왔습니다." else "상태를 불러오지 못했습니다.")
            }
        }
    }

    // ---- leaving ----------------------------------------------------------------------------

    private fun closeGame(resultCode: Int, data: Intent?) {
        if (closing) return
        closing = true
        setResult(resultCode, data)
        RunMarker.clear(this)
        try {
            // Stop the render thread first, then let the core write its NVRAM and high scores.
            retroView.onPause()
            if (loaded) retroView.onDestroy()
        } catch (_: Throwable) {
        }
        finish()
        exitProcess(0)
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun toast(message: String) {
        if (!isFinishing) Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
