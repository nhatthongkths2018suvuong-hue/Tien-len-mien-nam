package vn.tienlen.game

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var view: GameView
    private lateinit var usb: UsbController
    private var lastBackAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = GameView(this)
        usb = UsbController(
            this,
            { index, down -> view.onUsbButton(index, down) },
            { mask -> view.onUsbDirection(mask) }
        )
        view.usbStatus = { usb.status }
        setContentView(view)
        hideSystemUi()
    }

    override fun onResume() {
        super.onResume()
        view.resume()
        usb.start()
    }

    override fun onPause() {
        usb.stop()
        view.pause()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (view.onKey(keyCode, event.repeatCount)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        return view.onMotion(event) || super.onGenericMotionEvent(event)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (view.onBack()) return
        val now = SystemClock.uptimeMillis()
        if (now - lastBackAt < 2000) {
            super.onBackPressed()
        } else {
            lastBackAt = now
            Toast.makeText(this, "Nhấn Back lần nữa để thoát", Toast.LENGTH_SHORT).show()
        }
    }
}
