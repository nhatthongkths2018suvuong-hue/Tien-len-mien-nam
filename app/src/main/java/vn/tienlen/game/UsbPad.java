package vn.tienlen.game;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

/**
 * Đọc tay cầm USB (HID) trực tiếp qua USB Host, dùng khi Android không tự nhận tay cầm.
 * Tự đọc "mô tả báo cáo HID" của tay cầm để biết nút nào nằm ở đâu, nên không phụ thuộc từng mẫu.
 * Mọi callback của Listener được gọi từ thread đọc (không phải thread giao diện).
 */
public final class UsbPad extends Thread {

    public interface Listener {
        /** index bắt đầu từ 0 (nút 1 = index 0). */
        void onButton(UsbPad pad, int index, boolean down);

        /** Mặt nạ hướng theo bit RetroPad (UP=1<<4, DOWN=1<<5, LEFT=1<<6, RIGHT=1<<7). */
        void onDirection(UsbPad pad, int retroMask);

        void onRaw(UsbPad pad, String hex);

        void onStatus(UsbPad pad, String text);

        void onClosed(UsbPad pad);
    }

    private static final int DIR_UP = 1 << 4;
    private static final int DIR_DOWN = 1 << 5;
    private static final int DIR_LEFT = 1 << 6;
    private static final int DIR_RIGHT = 1 << 7;

    private static final class Field {
        final int reportId, bitOffset, bitSize, page, usage, min, max;

        Field(int reportId, int bitOffset, int bitSize, int page, int usage, int min, int max) {
            this.reportId = reportId;
            this.bitOffset = bitOffset;
            this.bitSize = bitSize;
            this.page = page;
            this.usage = usage;
            this.min = min;
            this.max = max;
        }
    }

    private final UsbManager manager;
    private final UsbDevice device;
    private final UsbInterface iface;
    private final Listener listener;
    private volatile boolean running = true;

    private final ArrayList<Field> fields = new ArrayList<>();
    private boolean hasReportIds = false;
    private int topPage = -1;
    private int topUsage = -1;
    private int buttonCount = 0;

    public UsbPad(UsbManager manager, UsbDevice device, UsbInterface iface, Listener listener) {
        super("usb-pad");
        this.manager = manager;
        this.device = device;
        this.iface = iface;
        this.listener = listener;
    }

    public int padId() {
        return device.getDeviceId();
    }

    public String deviceName() {
        return device.getDeviceName();
    }

    public void shutdown() {
        running = false;
    }

    // -----------------------------------------------------------------------
    // Vòng đọc
    // -----------------------------------------------------------------------
    @Override
    public void run() {
        UsbDeviceConnection conn = null;
        boolean claimed = false;
        try {
            conn = manager.openDevice(device);
            if (conn == null) {
                listener.onStatus(this, "không mở được thiết bị (chưa được cấp quyền USB?)");
                return;
            }
            if (!conn.claimInterface(iface, true)) {
                listener.onStatus(this, "không chiếm được giao diện USB");
                return;
            }
            claimed = true;

            // Lấy mô tả báo cáo HID: GET_DESCRIPTOR(Report) gửi tới giao diện
            byte[] desc = new byte[4096];
            int n = conn.controlTransfer(0x81, 0x06, 0x2200, iface.getId(), desc, desc.length, 1500);
            if (n <= 0) {
                listener.onStatus(this, "không đọc được mô tả HID của tay cầm");
                return;
            }
            parseDescriptor(desc, n);
            boolean isPad = topPage == 0x01 && (topUsage == 0x04 || topUsage == 0x05);
            if (!isPad) {
                listener.onStatus(this, "thiết bị HID này không phải tay cầm");
                return;
            }
            if (fields.isEmpty()) {
                listener.onStatus(this, "tay cầm không có trường nút/trục nào đọc được");
                return;
            }

            UsbEndpoint in = null;
            for (int i = 0; i < iface.getEndpointCount(); i++) {
                UsbEndpoint ep = iface.getEndpoint(i);
                if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_INT
                        && ep.getDirection() == UsbConstants.USB_DIR_IN) {
                    in = ep;
                    break;
                }
            }
            if (in == null) {
                listener.onStatus(this, "không thấy endpoint đọc dữ liệu của tay cầm");
                return;
            }

            listener.onStatus(this, "đang đọc trực tiếp (" + buttonCount + " nút)");

            byte[] buf = new byte[Math.max(in.getMaxPacketSize(), 16)];
            byte[] lastRaw = null;
            while (running) {
                int r = conn.bulkTransfer(in, buf, buf.length, 200);
                if (r > 0) {
                    if (lastRaw == null || lastRaw.length != r || !sameBytes(lastRaw, buf, r)) {
                        lastRaw = new byte[r];
                        System.arraycopy(buf, 0, lastRaw, 0, r);
                        listener.onRaw(this, hex(buf, r));
                    }
                    handleReport(buf, r);
                } else if (r < 0) {
                    // timeout cũng trả về -1; chỉ thoát khi tay cầm đã bị rút
                    HashMap<String, UsbDevice> now = manager.getDeviceList();
                    if (now == null || !now.containsKey(device.getDeviceName())) break;
                    SystemClock.sleep(10);
                }
            }
        } catch (Exception e) {
            listener.onStatus(this, "lỗi: " + e.getClass().getSimpleName());
        } finally {
            // trả hết nút/hướng đang giữ
            listener.onDirection(this, 0);
            for (int b = 0; b < 32; b++) {
                if ((lastButtons >> b & 1) != 0) listener.onButton(this, b, false);
            }
            lastButtons = 0;
            try {
                if (conn != null) {
                    if (claimed) conn.releaseInterface(iface);
                    conn.close();
                }
            } catch (Exception ignored) {
            }
            listener.onClosed(this);
        }
    }

    // -----------------------------------------------------------------------
    // Xử lý báo cáo
    // -----------------------------------------------------------------------
    private int lastButtons = 0;
    private int lastDir = 0;

    private void handleReport(byte[] buf, int n) {
        int id = hasReportIds ? (buf[0] & 0xFF) : 0;
        int buttons = 0;
        int dir = 0;

        for (Field f : fields) {
            if (f.reportId != id) continue;
            int v = getBits(buf, n, f.bitOffset, f.bitSize);
            if (f.min < 0 && f.bitSize < 32 && ((v >> (f.bitSize - 1)) & 1) != 0) {
                v -= (1 << f.bitSize);
            }
            if (f.page == 0x09) {
                if (f.usage >= 1 && f.usage <= 32 && v != 0) buttons |= 1 << (f.usage - 1);
            } else if (f.page == 0x01) {
                if (f.usage == 0x39) { // Hat switch
                    if (v >= f.min && v <= f.max) {
                        int pos = v - f.min;
                        if (f.max - f.min == 3) pos *= 2; // hat 4 hướng
                        switch (pos) {
                            case 0: dir |= DIR_UP; break;
                            case 1: dir |= DIR_UP | DIR_RIGHT; break;
                            case 2: dir |= DIR_RIGHT; break;
                            case 3: dir |= DIR_DOWN | DIR_RIGHT; break;
                            case 4: dir |= DIR_DOWN; break;
                            case 5: dir |= DIR_DOWN | DIR_LEFT; break;
                            case 6: dir |= DIR_LEFT; break;
                            case 7: dir |= DIR_UP | DIR_LEFT; break;
                            default: break;
                        }
                    }
                } else if (f.usage == 0x30 || f.usage == 0x31) { // trục X, Y
                    int span = f.max - f.min;
                    int lo = f.min + span / 4;
                    int hi = f.max - span / 4;
                    if (f.usage == 0x30) {
                        if (v < lo) dir |= DIR_LEFT;
                        else if (v > hi) dir |= DIR_RIGHT;
                    } else {
                        if (v < lo) dir |= DIR_UP;
                        else if (v > hi) dir |= DIR_DOWN;
                    }
                }
            }
        }

        int changed = buttons ^ lastButtons;
        if (changed != 0) {
            for (int b = 0; b < 32; b++) {
                if ((changed >> b & 1) != 0) listener.onButton(this, b, (buttons >> b & 1) != 0);
            }
            lastButtons = buttons;
        }
        if (dir != lastDir) {
            lastDir = dir;
            listener.onDirection(this, dir);
        }
    }

    private static int getBits(byte[] d, int n, int bitOff, int size) {
        long v = 0;
        for (int b = 0; b < size && b < 32; b++) {
            int bit = bitOff + b;
            int idx = bit >> 3;
            if (idx >= n) break;
            if (((d[idx] >> (bit & 7)) & 1) != 0) v |= 1L << b;
        }
        return (int) v;
    }

    private static boolean sameBytes(byte[] a, byte[] b, int n) {
        for (int i = 0; i < n; i++) if (a[i] != b[i]) return false;
        return true;
    }

    private static String hex(byte[] b, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n && i < 16; i++) sb.append(String.format(Locale.US, "%02x ", b[i] & 0xFF));
        return sb.toString().trim();
    }

    // -----------------------------------------------------------------------
    // Phân tích mô tả báo cáo HID
    // -----------------------------------------------------------------------
    private void parseDescriptor(byte[] d, int len) {
        int usagePage = 0, logMin = 0, logMax = 0, repSize = 0, repCount = 0, repId = 0;
        ArrayList<int[]> usages = new ArrayList<>(); // {trang, usage}
        int usageMin = -1, usageMax = -1;
        HashMap<Integer, Integer> offsets = new HashMap<>();
        boolean topSet = false;

        int i = 0;
        while (i < len) {
            int b = d[i] & 0xFF;
            if (b == 0xFE) { // mục dài, bỏ qua
                int sz = (i + 1 < len) ? (d[i + 1] & 0xFF) : 0;
                i += 3 + sz;
                continue;
            }
            int size = b & 3;
            if (size == 3) size = 4;
            int type = (b >> 2) & 3;
            int tag = (b >> 4) & 0xF;
            int data = 0;
            for (int k = 0; k < size && i + 1 + k < len; k++) {
                data |= (d[i + 1 + k] & 0xFF) << (8 * k);
            }
            int sdata = data;
            if (size == 1) sdata = (byte) data;
            else if (size == 2) sdata = (short) data;
            i += 1 + size;

            if (type == 1) { // Global
                switch (tag) {
                    case 0: usagePage = data; break;
                    case 1: logMin = sdata; break;
                    case 2: logMax = (logMin >= 0) ? data : sdata; break;
                    case 7: repSize = data; break;
                    case 8: repId = data; hasReportIds = true; break;
                    case 9: repCount = data; break;
                    default: break;
                }
            } else if (type == 2) { // Local
                switch (tag) {
                    case 0:
                        if (size == 4) usages.add(new int[]{(data >>> 16) & 0xFFFF, data & 0xFFFF});
                        else usages.add(new int[]{-1, data});
                        break;
                    case 1: usageMin = data; break;
                    case 2: usageMax = data; break;
                    default: break;
                }
            } else if (type == 0) { // Main
                if (tag == 0x8) { // Input
                    boolean constant = (data & 1) != 0;
                    boolean variable = (data & 2) != 0;
                    int off = offsets.containsKey(repId) ? offsets.get(repId) : (repId != 0 ? 8 : 0);
                    if (!constant && variable) {
                        for (int n = 0; n < repCount; n++) {
                            int page = usagePage;
                            int usage = -1;
                            if (!usages.isEmpty()) {
                                int[] u = usages.get(Math.min(n, usages.size() - 1));
                                if (u[0] != -1) page = u[0];
                                usage = u[1];
                            } else if (usageMin >= 0) {
                                usage = Math.min(usageMin + n, usageMax >= 0 ? usageMax : usageMin + n);
                            }
                            fields.add(new Field(repId, off + n * repSize, repSize, page, usage, logMin, logMax));
                            if (page == 0x09 && usage > buttonCount && usage <= 32) buttonCount = usage;
                        }
                    }
                    offsets.put(repId, off + repCount * repSize);
                } else if (tag == 0xA && !topSet && data == 1) { // Collection (Application)
                    topPage = usagePage;
                    topUsage = usages.isEmpty() ? -1 : usages.get(0)[1];
                    topSet = true;
                }
                // sau mỗi mục Main, xóa các giá trị Local
                usages.clear();
                usageMin = -1;
                usageMax = -1;
            }
        }
    }
}
