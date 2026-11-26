package lk.com.harshapex.sanjeewaerp;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import androidx.core.content.FileProvider;
import java.util.ArrayList;
import java.util.List;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintManager;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Prints an already generated PDF through the Android print framework
 * (Wi-Fi printers, Mopria, "Save as PDF", vendor print services).
 * Android WebView does not implement window.print(), so this is required.
 */
@CapacitorPlugin(name = "NativePrint")
public class NativePrintPlugin extends Plugin {

    @PluginMethod
    public void printPdf(PluginCall call) {
        String path = call.getString("path");
        String jobName = call.getString("jobName", "Invoice");
        boolean a4 = call.getBoolean("a4", true);
        if (path == null || path.isEmpty()) { call.reject("path is required"); return; }
        final File file = new File(Uri.parse(path).getPath() != null ? Uri.parse(path).getPath() : path);
        if (!file.exists()) { call.reject("PDF file not found"); return; }
        // Only print files that live inside this app's private storage.
        try {
            String canonical = file.getCanonicalPath();
            String cache = getContext().getCacheDir().getCanonicalPath();
            String files = getContext().getFilesDir().getCanonicalPath();
            if (!canonical.startsWith(cache) && !canonical.startsWith(files)) { call.reject("File outside app storage"); return; }
        } catch (Exception e) { call.reject("Invalid path"); return; }

        getActivity().runOnUiThread(() -> {
            try {
                PrintManager pm = (PrintManager) getActivity().getSystemService(Context.PRINT_SERVICE);
                PrintAttributes.Builder attrs = new PrintAttributes.Builder();
                if (a4) attrs.setMediaSize(PrintAttributes.MediaSize.ISO_A4);
                pm.print(jobName, new PdfAdapter(file, jobName), attrs.build());
                JSObject ret = new JSObject();
                ret.put("started", true);
                call.resolve(ret);
            } catch (Exception e) {
                call.reject("Unable to open Android print: " + e.getMessage());
            }
        });
    }

    /**
     * Opens WhatsApp (or WhatsApp Business) with the PDF attached and, where
     * WhatsApp honours it, the customer's chat pre-selected via the "jid"
     * extra (unofficial; WhatsApp may still ask the user to pick the chat).
     * The user always presses Send — nothing is sent automatically.
     */
    @PluginMethod
    public void shareToWhatsApp(PluginCall call) {
        String path = call.getString("path");
        String phone = call.getString("phone", "").replaceAll("[^0-9]", "");
        String text = call.getString("text", "");
        String mime = call.getString("mime", "application/pdf");
        if (path == null) { call.reject("path is required"); return; }
        File file = new File(Uri.parse(path).getPath() != null ? Uri.parse(path).getPath() : path);
        try {
            if (!file.getCanonicalPath().startsWith(getContext().getCacheDir().getCanonicalPath())) { call.reject("File outside app cache"); return; }
        } catch (Exception e) { call.reject("Invalid path"); return; }
        Uri uri = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", file);
        List<Intent> targets = new ArrayList<>();
        for (String pkg : new String[]{"com.whatsapp.w4b", "com.whatsapp"}) {
            try {
                getContext().getPackageManager().getPackageInfo(pkg, 0);
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType(mime);
                i.setPackage(pkg);
                i.putExtra(Intent.EXTRA_STREAM, uri);
                if (!text.isEmpty()) i.putExtra(Intent.EXTRA_TEXT, text);
                if (!phone.isEmpty()) i.putExtra("jid", phone + "@s.whatsapp.net");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                targets.add(i);
            } catch (PackageManager.NameNotFoundException ignored) {}
        }
        if (targets.isEmpty()) { call.reject("WhatsApp is not installed", "NO_WHATSAPP"); return; }
        try {
            Intent launch = targets.size() == 1 ? targets.get(0)
                : Intent.createChooser(targets.get(0), "Send with").putExtra(Intent.EXTRA_INITIAL_INTENTS, targets.subList(1, targets.size()).toArray(new Intent[0]));
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getActivity().startActivity(launch);
            JSObject ret = new JSObject(); ret.put("apps", targets.size()); call.resolve(ret);
        } catch (Exception e) { call.reject("Could not open WhatsApp: " + e.getMessage(), "NO_WHATSAPP"); }
    }

    /**
     * Sends a ready ESC/POS byte stream to the RawBT app ("rawbt:base64,…"),
     * which forwards it unchanged to the selected Bluetooth/USB printer:
     * exact paper width, portrait, cut at the end — no page scaling/rotation.
     */
    @PluginMethod
    public void printRawBt(PluginCall call) {
        String data = call.getString("data");
        if (data == null || data.isEmpty()) { call.reject("data is required"); return; }
        try { getContext().getPackageManager().getPackageInfo("ru.a402d.rawbtprinter", 0); }
        catch (PackageManager.NameNotFoundException e) { call.reject("RawBT is not installed", "NO_RAWBT"); return; }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("rawbt:base64," + data));
            i.setPackage("ru.a402d.rawbtprinter");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getActivity().startActivity(i);
            call.resolve();
        } catch (Exception e) { call.reject("Could not open RawBT: " + e.getMessage(), "NO_RAWBT"); }
    }

    private static class PdfAdapter extends PrintDocumentAdapter {
        private final File file; private final String name;
        PdfAdapter(File file, String name) { this.file = file; this.name = name; }

        @Override
        public void onLayout(PrintAttributes oldA, PrintAttributes newA, CancellationSignal cancel, LayoutResultCallback cb, Bundle extras) {
            if (cancel.isCanceled()) { cb.onLayoutCancelled(); return; }
            PrintDocumentInfo info = new PrintDocumentInfo.Builder(name.replaceAll("[^A-Za-z0-9._-]", "_") + ".pdf")
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN).build();
            cb.onLayoutFinished(info, !newA.equals(oldA));
        }

        @Override
        public void onWrite(PageRange[] pages, ParcelFileDescriptor dest, CancellationSignal cancel, WriteResultCallback cb) {
            try (InputStream in = new FileInputStream(file); OutputStream out = new FileOutputStream(dest.getFileDescriptor())) {
                byte[] buf = new byte[16384]; int n;
                while ((n = in.read(buf)) > 0) { if (cancel.isCanceled()) { cb.onWriteCancelled(); return; } out.write(buf, 0, n); }
                cb.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});
            } catch (Exception e) {
                cb.onWriteFailed(e.getMessage());
            }
        }
    }
}
