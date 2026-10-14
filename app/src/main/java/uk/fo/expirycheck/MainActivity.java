package uk.fo.expirycheck;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.content.FileProvider;

import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class MainActivity extends Activity {
    static final String PREFS = "expirycheck";
    private static final String IMG_HOST = "img.local";
    private static final int REQ_FILE = 10;
    private static final int REQ_NOTIFY = 11;

    private WebView webView;
    private SharedPreferences prefs;
    private ValueCallback<Uri[]> fileCallback;
    private Uri cameraUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        ReminderReceiver.createChannel(this);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setSupportZoom(false);
        s.setMediaPlaybackRequiresUserGesture(true);

        webView.addJavascriptInterface(new Bridge(), "AndroidBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("file".equals(uri.getScheme())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception ignored) {
                }
                return true;
            }

            // Product photos live in the app's private storage and are shown as https://img.local/<name>.
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (!IMG_HOST.equals(uri.getHost())) return null;
                Map<String, String> headers = new HashMap<>();
                headers.put("Access-Control-Allow-Origin", "*");
                headers.put("Cache-Control", "no-store");
                File f = imageFile(uri.getLastPathSegment());
                try {
                    if (f != null && f.exists()) {
                        return new WebResourceResponse("image/jpeg", null, 200, "OK", headers, new FileInputStream(f));
                    }
                } catch (Exception ignored) {
                }
                return new WebResourceResponse("image/jpeg", null, 404, "Not Found", headers,
                        new ByteArrayInputStream(new byte[0]));
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                cameraUri = null;
                boolean image = false;
                for (String t : params.getAcceptTypes()) if (t != null && t.startsWith("image")) image = true;
                try {
                    Intent intent;
                    if (image && params.isCaptureEnabled()) {
                        intent = cameraIntent();
                    } else {
                        Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                        pick.addCategory(Intent.CATEGORY_OPENABLE);
                        pick.setType(image ? "image/*" : "*/*");
                        intent = Intent.createChooser(pick, image ? "Choose a photo" : "Choose backup file");
                    }
                    startActivityForResult(intent, REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl("file:///android_asset/index.html");
        }
    }

    private Intent cameraIntent() {
        File dir = new File(getCacheDir(), "camera");
        dir.mkdirs();
        File[] old = dir.listFiles();
        if (old != null) for (File o : old) o.delete();
        File out = new File(dir, "photo_" + System.currentTimeMillis() + ".jpg");
        cameraUri = FileProvider.getUriForFile(this, getPackageName() + ".files", out);
        Intent cam = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        cam.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
        cam.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return cam;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Dates roll over at midnight; let the page refresh its "today" view.
        if (webView != null) webView.evaluateJavascript("window.onAppResume && window.onAppResume()", null);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE) {
            if (fileCallback != null) {
                Uri[] result = null;
                if (resultCode == RESULT_OK) {
                    if (data != null && data.getData() != null) result = new Uri[]{data.getData()};
                    else if (cameraUri != null) result = new Uri[]{cameraUri};
                }
                fileCallback.onReceiveValue(result);
                fileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        webView.evaluateJavascript("window.onBackButton ? window.onBackButton() : false", value -> {
            if (!"true".equals(value)) {
                if (webView.canGoBack()) webView.goBack();
                else finish();
            }
        });
    }

    private void js(String script) {
        runOnUiThread(() -> webView.evaluateJavascript(script, null));
    }

    /** Only plain file names, so nothing outside the image folder can be read or written. */
    private File imageFile(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_.-]{1,80}") || name.startsWith(".")) return null;
        File dir = new File(getFilesDir(), "img");
        dir.mkdirs();
        return new File(dir, name);
    }

    static String newImageName() {
        return "p_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16) + ".jpg";
    }

    /** Scales a photo down to at most 700px on the long side and stores it as JPEG. */
    static boolean writeScaledJpeg(byte[] bytes, File out) {
        try {
            Bitmap bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bm == null) return false;
            int w = bm.getWidth(), h = bm.getHeight(), max = Math.max(w, h);
            if (max > 700) {
                float k = 700f / max;
                Bitmap scaled = Bitmap.createScaledBitmap(bm, Math.round(w * k), Math.round(h * k), true);
                if (scaled != bm) bm.recycle();
                bm = scaled;
            }
            try (FileOutputStream fos = new FileOutputStream(out)) {
                bm.compress(Bitmap.CompressFormat.JPEG, 85, fos);
            }
            bm.recycle();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean canNotify() {
        return Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private void share(Intent send, String title) {
        Intent chooser = Intent.createChooser(send, title);
        runOnUiThread(() -> {
            try {
                startActivity(chooser);
            } catch (Exception ignored) {
            }
        });
    }

    /** Methods the page calls as window.AndroidBridge.* */
    private class Bridge {
        @JavascriptInterface
        public String getItem(String key) {
            return prefs.getString(key, null);
        }

        @JavascriptInterface
        public void setItem(String key, String value) {
            prefs.edit().putString(key, value).apply();
        }

        @JavascriptInterface
        public void removeItem(String key) {
            prefs.edit().remove(key).apply();
        }

        @JavascriptInterface
        public String appVersion() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception e) {
                return "";
            }
        }

        /** Opens Google's barcode scanner. Replies with window.onScanResult(code | null, error | null). */
        @JavascriptInterface
        public void scan() {
            runOnUiThread(() -> {
                GmsBarcodeScannerOptions options = new GmsBarcodeScannerOptions.Builder()
                        .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A,
                                Barcode.FORMAT_UPC_E, Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39,
                                Barcode.FORMAT_ITF, Barcode.FORMAT_QR_CODE, Barcode.FORMAT_DATA_MATRIX)
                        .enableAutoZoom()
                        .allowManualInput()
                        .build();
                GmsBarcodeScanner scanner = GmsBarcodeScanning.getClient(MainActivity.this, options);
                scanner.startScan()
                        .addOnSuccessListener(b -> js("window.onScanResult(" + JSONObject.quote(b.getRawValue()) + ", null)"))
                        .addOnCanceledListener(() -> js("window.onScanResult(null, null)"))
                        .addOnFailureListener(e -> js("window.onScanResult(null, " + JSONObject.quote(
                                e.getMessage() == null ? "Scanner unavailable" : e.getMessage()) + ")"));
            });
        }

        /** Looks the barcode up online on a background thread. Replies with window.onLookup(reqId, result). */
        @JavascriptInterface
        public void lookup(String code, String reqId) {
            new Thread(() -> {
                JSONObject r = ProductLookup.lookup(MainActivity.this, code);
                js("window.onLookup(" + JSONObject.quote(reqId) + ", " + r.toString() + ")");
            }).start();
        }

        /** Stores a photo (base64) as a new image file; returns its name, or null. */
        @JavascriptInterface
        public String saveImage(String base64) {
            String name = newImageName();
            return saveImageAs(name, base64) ? name : null;
        }

        @JavascriptInterface
        public boolean saveImageAs(String name, String base64) {
            File f = imageFile(name);
            if (f == null) return false;
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                return writeScaledJpeg(bytes, f);
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public String readImage(String name) {
            File f = imageFile(name);
            if (f == null || !f.exists()) return null;
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] b = new byte[(int) f.length()];
                int off = 0, n;
                while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
                return Base64.encodeToString(b, Base64.NO_WRAP);
            } catch (Exception e) {
                return null;
            }
        }

        @JavascriptInterface
        public void deleteImage(String name) {
            File f = imageFile(name);
            if (f != null) f.delete();
        }

        /** Deletes stored photos no product uses any more (e.g. from cancelled scans). */
        @JavascriptInterface
        public void pruneImages(String keepJson) {
            try {
                org.json.JSONArray keep = new org.json.JSONArray(keepJson);
                java.util.Set<String> names = new java.util.HashSet<>();
                for (int i = 0; i < keep.length(); i++) names.add(keep.optString(i));
                File[] files = new File(getFilesDir(), "img").listFiles();
                if (files == null) return;
                long cutoff = System.currentTimeMillis() - 10 * 60_000L; // leave anything from the last 10 minutes alone
                for (File f : files) if (!names.contains(f.getName()) && f.lastModified() < cutoff) f.delete();
            } catch (Exception ignored) {
            }
        }

        @JavascriptInterface
        public void shareText(String text, String subject) {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, text);
            send.putExtra(Intent.EXTRA_SUBJECT, subject);
            share(send, "Send list");
        }

        /** Saves a base64 file to Downloads/Expiry Check and, if asked, opens the share sheet. */
        @JavascriptInterface
        public boolean saveFile(String name, String base64, String mime, boolean thenShare) {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                ContentResolver cr = getContentResolver();
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Expiry Check");
                v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) return false;
                try (OutputStream out = cr.openOutputStream(uri)) {
                    if (out == null) return false;
                    out.write(bytes);
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                cr.update(uri, done, null, null);
                if (thenShare) {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType(mime);
                    send.putExtra(Intent.EXTRA_STREAM, uri);
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    share(send, "Send " + name);
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        /** Turns the daily reminder on or off at the given time. */
        @JavascriptInterface
        public void setReminder(boolean on, int hour, int minute) {
            prefs.edit().putBoolean(ReminderReceiver.KEY_ON, on)
                    .putInt(ReminderReceiver.KEY_HOUR, hour)
                    .putInt(ReminderReceiver.KEY_MINUTE, minute).apply();
            ReminderReceiver.schedule(MainActivity.this);
            if (on && !canNotify() && Build.VERSION.SDK_INT >= 33) {
                runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY));
            }
        }

        @JavascriptInterface
        public boolean notificationsAllowed() {
            return canNotify();
        }

        /** Posts the reminder now, so the user can see what it looks like. */
        @JavascriptInterface
        public void testReminder() {
            if (!canNotify() && Build.VERSION.SDK_INT >= 33) {
                runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY));
                return;
            }
            ReminderReceiver.postSummary(MainActivity.this, true);
        }
    }
}
