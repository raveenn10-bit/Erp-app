package lk.com.harshapex.sanjeewaerp;

import android.content.Context;
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
