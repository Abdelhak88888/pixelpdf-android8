package com.pixelpdf.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * PixelPDF native shell.
 * Hosts the bundled HTML app in a WebView and provides native file upload
 * (choose image/PDF) and PDF download/save.
 */
public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private static final int FILE_CHOOSER_REQUEST_CODE = 1001;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        webView = new WebView(this);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);

        webView.setWebViewClient(new WebViewClient());

        // Enable file upload (the "Add Images / Choose" buttons in the HTML)
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView,
                                             ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }
                MainActivity.this.filePathCallback = filePathCallback;
                Intent intent = fileChooserParams.createIntent();
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE);
                } catch (Exception e) {
                    MainActivity.this.filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        // Native bridge for saving/opening a PDF (optional helper)
        webView.addJavascriptInterface(new PixelBridge(this), "Android");

        webView.loadUrl("file:///android_asset/index.html");
        setContentView(root);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (filePathCallback == null) { super.onActivityResult(requestCode, resultCode, data); return; }
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data != null) {
                if (data.getData() != null) {
                    results = new Uri[]{ data.getData() };
                } else if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    // Save a base64 PDF to Downloads and open it (MediaStore, no permission needed on Android 10+)
    @JavascriptInterface
    public void savePdf(final String dataUrl, final String filename) {
        byte[] bytes = null;
        try {
            if (dataUrl == null || !dataUrl.contains(",")) return;
            String base64 = dataUrl.substring(dataUrl.indexOf(',') + 1);
            bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
        } catch (Exception e) { return; }
        final byte[] data = bytes;
        final String outName = (filename == null || filename.isEmpty())
                ? "PixelPDF_" + System.currentTimeMillis() + ".pdf"
                : (filename.endsWith(".pdf") ? filename : filename + ".pdf");
        runOnUiThread(() -> {
            try {
                Uri uri;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.MediaColumns.DISPLAY_NAME, outName);
                    values.put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf");
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    java.io.OutputStream os = getContentResolver().openOutputStream(uri);
                    os.write(data);
                    os.close();
                } else {
                    java.io.File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!downloads.exists()) downloads.mkdirs();
                    java.io.File out = new java.io.File(downloads, outName);
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
                    fos.write(data);
                    fos.close();
                    uri = Uri.fromFile(out);
                }
                Toast.makeText(this, "PDF saved to Downloads", Toast.LENGTH_LONG).show();
                if (uri != null) {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(uri, "application/pdf");
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(intent);
                }
            } catch (Exception e) {
                Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    @JavascriptInterface
    public void toast(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) { webView.destroy(); }
        super.onDestroy();
    }

    private class PixelBridge {
        PixelBridge(Context c) { }
        @JavascriptInterface public void savePdf(String d, String f) { MainActivity.this.savePdf(d, f); }
        @JavascriptInterface public void toast(String m) { MainActivity.this.toast(m); }
    }
}
