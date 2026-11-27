package vn.tienlen.game

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice

/**
 * Đọc tay cầm USB trực tiếp (khi Android không tự nhận thành tay cầm), dùng lại UsbPad
 * của phần mềm Mini Game Box. Mọi sự kiện được chuyển về luồng giao diện.
 */
class UsbController(
    private val ctx: Context,
    private val onButton: (index: Int, down: Boolean) -> Unit,
    private val onDirection: (retroMask: Int) -> Unit
) {
    companion object {
        private const val ACTION_USB_PERMISSION = "vn.tienlen.game.USB_PERMISSION"
    }

    private val ui = Handler(Looper.getMainLooper())
    private val manager = ctx.getSystemService(Context.USB_SERVICE) as UsbManager?
    private val pads = HashMap<String, UsbPad>()
    private val asked = HashSet<String>()
    private var receiver: BroadcastReceiver? = null

    @Volatile
    var status: String = "chưa có tay cầm USB nào cần đọc trực tiếp"
        private set

    private val ticker = object : Runnable {
        override fun run() {
            scan()
            ui.postDelayed(this, 2000)
        }
    }

    private val listener = object : UsbPad.Listener {
        override fun onButton(pad: UsbPad, index: Int, down: Boolean) {
            ui.post { this@UsbController.onButton(index, down) }
        }

        override fun onDirection(pad: UsbPad, retroMask: Int) {
            ui.post { this@UsbController.onDirection(retroMask) }
        }

        override fun onRaw(pad: UsbPad, hex: String) {}

        override fun onStatus(pad: UsbPad, text: String) {
            status = text
        }

        override fun onClosed(pad: UsbPad) {
            ui.post {
                if (pads[pad.deviceName()] === pad) pads.remove(pad.deviceName())
            }
        }
    }

    fun start() {
        if (manager == null) return
        if (receiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    val a = i.action
                    @Suppress("DEPRECATION")
                    val d = i.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (ACTION_USB_PERMISSION == a) {
                        if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) scan()
                        else status = "bạn đã từ chối quyền truy cập USB"
                    } else if (UsbManager.ACTION_USB_DEVICE_DETACHED == a && d != null) {
                        pads[d.deviceName]?.shutdown()
                        asked.remove(d.deviceName)
                    }
                }
            }
            val f = IntentFilter(ACTION_USB_PERMISSION)
            f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            ctx.registerReceiver(r, f)
            receiver = r
        }
        ui.removeCallbacks(ticker)
        ui.post(ticker)
    }

    fun stop() {
        ui.removeCallbacks(ticker)
        try {
            receiver?.let { ctx.unregisterReceiver(it) }
        } catch (ignored: Exception) {
        }
        receiver = null
        for (p in ArrayList(pads.values)) p.shutdown()
        pads.clear()
        asked.clear()
    }

    /** Android đã tự nhận thiết bị này thành thiết bị nhập thì không cần đọc trực tiếp. */
    private fun androidSees(d: UsbDevice): Boolean {
        for (id in InputDevice.getDeviceIds()) {
            val dev = InputDevice.getDevice(id)
            if (dev != null && dev.vendorId == d.vendorId && dev.productId == d.productId) return true
        }
        return false
    }

    private fun scan() {
        val m = manager ?: return
        val list = m.deviceList ?: return
        for (d in list.values) {
            val key = d.deviceName
            if (pads.containsKey(key)) continue
            var hid: UsbInterface? = null
            for (i in 0 until d.interfaceCount) {
                val it = d.getInterface(i)
                if (it.interfaceClass == UsbConstants.USB_CLASS_HID && it.interfaceProtocol == 0) {
                    hid = it
                    break
                }
            }
            if (hid == null || androidSees(d)) continue
            if (m.hasPermission(d)) {
                val pad = UsbPad(m, d, hid, listener)
                pads[key] = pad
                status = "đang khởi động..."
                pad.start()
            } else if (asked.add(key)) {
                status = "đang chờ bạn cấp quyền USB (bấm OK ở hộp thoại hiện ra)"
                val intent = Intent(ACTION_USB_PERMISSION)
                intent.setPackage(ctx.packageName)
                val pi = PendingIntent.getBroadcast(ctx, 0, intent, 0)
                m.requestPermission(d, pi)
            }
        }
    }
}
