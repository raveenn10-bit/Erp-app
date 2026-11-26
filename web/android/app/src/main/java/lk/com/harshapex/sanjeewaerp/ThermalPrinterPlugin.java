package lk.com.harshapex.sanjeewaerp;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Build;
import android.util.Base64;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.io.OutputStream;
import java.util.Set;
import java.util.UUID;

/**
 * Minimal ESC/POS sender for Bluetooth Classic (SPP) thermal printers that are
 * already paired in Android Settings. The JavaScript side builds the ESC/POS
 * byte stream (raster image, so Sinhala text prints correctly); this class only
 * lists bonded devices and writes bytes. No scanning, so no location permission.
 */
@CapacitorPlugin(
    name = "ThermalPrinter",
    permissions = { @Permission(alias = "bluetooth", strings = { Manifest.permission.BLUETOOTH_CONNECT }) }
)
public class ThermalPrinterPlugin extends Plugin {
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private boolean needsRuntimePermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && getPermissionState("bluetooth") != PermissionState.GRANTED;
    }

    private BluetoothAdapter adapter() {
        BluetoothManager m = (BluetoothManager) getContext().getSystemService(Context.BLUETOOTH_SERVICE);
        return m == null ? null : m.getAdapter();
    }

    @PluginMethod
    public void listPaired(PluginCall call) {
        if (needsRuntimePermission()) { requestPermissionForAlias("bluetooth", call, "listAfterPermission"); return; }
        doList(call);
    }

    @PermissionCallback
    private void listAfterPermission(PluginCall call) {
        if (needsRuntimePermission()) { call.reject("Bluetooth permission denied", "PERMISSION_DENIED"); return; }
        doList(call);
    }

    @SuppressLint("MissingPermission")
    private void doList(PluginCall call) {
        BluetoothAdapter a = adapter();
        if (a == null) { call.reject("This device has no Bluetooth", "NO_BLUETOOTH"); return; }
        if (!a.isEnabled()) { call.reject("Bluetooth is turned off", "BLUETOOTH_OFF"); return; }
        JSArray list = new JSArray();
        Set<BluetoothDevice> bonded = a.getBondedDevices();
        if (bonded != null) for (BluetoothDevice d : bonded) {
            JSObject o = new JSObject();
            o.put("name", d.getName() == null ? "Unknown device" : d.getName());
            o.put("address", d.getAddress());
            list.put(o);
        }
        JSObject ret = new JSObject();
        ret.put("devices", list);
        call.resolve(ret);
    }

    @PluginMethod
    public void print(PluginCall call) {
        if (needsRuntimePermission()) { requestPermissionForAlias("bluetooth", call, "printAfterPermission"); return; }
        doPrint(call);
    }

    @PermissionCallback
    private void printAfterPermission(PluginCall call) {
        if (needsRuntimePermission()) { call.reject("Bluetooth permission denied", "PERMISSION_DENIED"); return; }
        doPrint(call);
    }

    @SuppressLint("MissingPermission")
    private void doPrint(PluginCall call) {
        final String address = call.getString("address");
        final String data = call.getString("data");
        if (address == null || data == null) { call.reject("address and data are required"); return; }
        final BluetoothAdapter a = adapter();
        if (a == null || !a.isEnabled()) { call.reject("Bluetooth is turned off", "BLUETOOTH_OFF"); return; }
        final byte[] bytes;
        try { bytes = Base64.decode(data, Base64.DEFAULT); } catch (Exception e) { call.reject("Invalid print data"); return; }
        if (bytes.length > 2_000_000) { call.reject("Receipt too large"); return; }

        new Thread(() -> {
            BluetoothSocket socket = null;
            try {
                BluetoothDevice device = a.getRemoteDevice(address);
                a.cancelDiscovery();
                socket = device.createRfcommSocketToServiceRecord(SPP);
                socket.connect();
                OutputStream out = socket.getOutputStream();
                int chunk = 512;
                for (int i = 0; i < bytes.length; i += chunk) {
                    out.write(bytes, i, Math.min(chunk, bytes.length - i));
                    out.flush();
                    Thread.sleep(12); // small pause: cheap printers have tiny buffers
                }
                Thread.sleep(400);
                JSObject ret = new JSObject();
                ret.put("bytes", bytes.length);
                call.resolve(ret);
            } catch (Exception e) {
                call.reject("Printer connection failed: " + e.getMessage(), "CONNECT_FAILED");
            } finally {
                if (socket != null) try { socket.close(); } catch (Exception ignored) {}
            }
        }).start();
    }
}
