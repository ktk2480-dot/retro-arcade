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
        private const val PREF_BUTTON_MODE = "button_mode"
        private const val PREF_ROTATION = "rotation"

        private const val BUTTONS_AUTO = 0
        private const val ROTATION_AUTO = 0
        private const val ROTATION_PORTRAIT = 1
        private const val ROTATION_LANDSCAPE = 2

        private const val SLOT_COUNT = 3

        // MAME 2003-Plus controller types: RETRO_DEVICE_JOYPAD and its "6-Button" subclass.
        private const val MAME_PAD_CLASSIC = 1
        private const val MAME_PAD_6BUTTON = (3 shl 8) or 1
    }

    private lateinit var retroView: GLRetroView
    private lateinit var console: ConsoleLayout
    private lateinit var core: Core
    private lateinit var romFile: File
    private lateinit var prefs: SharedPreferences

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

        console = ConsoleLayout(this, retroView, verticalGame)
        console.sixButtons = effectiveSixButtons()
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
                openMenu()
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

    /** Positions on the panel map to the same RetroPad keys for both cores. */
    private fun keyCodeFor(id: Int): Int = when (id) {
        ConsoleGeometry.ID_B1 -> KeyEvent.KEYCODE_BUTTON_B
        ConsoleGeometry.ID_B2 -> KeyEvent.KEYCODE_BUTTON_A
        ConsoleGeometry.ID_B3 -> KeyEvent.KEYCODE_BUTTON_R1
        ConsoleGeometry.ID_T1 -> KeyEvent.KEYCODE_BUTTON_Y
        ConsoleGeometry.ID_T2 -> KeyEvent.KEYCODE_BUTTON_X
        ConsoleGeometry.ID_T3 -> KeyEvent.KEYCODE_BUTTON_L1
        ConsoleGeometry.ID_COIN -> KeyEvent.KEYCODE_BUTTON_SELECT
        ConsoleGeometry.ID_START -> KeyEvent.KEYCODE_BUTTON_START
        else -> KeyEvent.KEYCODE_UNKNOWN
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
        if (closing || failureShown || openDialogs > 0) return
        console.gamepad.releaseAll()
        val items = arrayOf(
            "계속하기",
            "상태 저장",
            "상태 불러오기",
            "CRT 스캔라인 필터: " + (if (crtEnabled) "켜짐" else "꺼짐"),
            "버튼 배열: " + buttonModeLabel(),
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
                    4 -> cycleButtonMode()
                    5 -> cycleRotation()
                    6 -> resetGame()
                    7 -> closeGame(Activity.RESULT_OK, null)
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
                    setEmulationPaused(false)
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

    private fun effectiveSixButtons(): Boolean = when (prefs.getInt(PREF_BUTTON_MODE, BUTTONS_AUTO)) {
        4 -> false
        6 -> true
        else -> gameButtons == GameDb.BUTTONS_UNKNOWN || gameButtons >= 5
    }

    private fun buttonModeLabel(): String = when (prefs.getInt(PREF_BUTTON_MODE, BUTTONS_AUTO)) {
        4 -> "4버튼"
        6 -> "6버튼"
        else -> "자동 (" + (if (effectiveSixButtons()) "6버튼" else "4버튼") + ")"
    }

    private fun cycleButtonMode() {
        val next = when (prefs.getInt(PREF_BUTTON_MODE, BUTTONS_AUTO)) {
            BUTTONS_AUTO -> 4
            4 -> 6
            else -> BUTTONS_AUTO
        }
        prefs.edit().putInt(PREF_BUTTON_MODE, next).apply()
        console.sixButtons = effectiveSixButtons()
        toast("버튼 배열: " + buttonModeLabel())
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
