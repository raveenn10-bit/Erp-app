package lk.com.harshapex.sanjeewaerp;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.webkit.CookieManager;
import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Biometric unlock for the ERP session.
 *
 * - The ERP server session token (HttpOnly cookie "erp_session") is encrypted
 *   with an AES-256-GCM key that lives in the Android Keystore, cannot be
 *   exported, and can only be used right after a successful system
 *   BiometricPrompt (Class 3 biometric, or device PIN/pattern on Android 11+).
 * - No password or PIN is ever stored. No biometric data is read by the app;
 *   Android only reports success/failure.
 * - Adding a new fingerprint/face invalidates the key (enrollment change).
 * - The plaintext cookie is removed on every cold start and after 5 minutes in
 *   the background, so the app must be unlocked again.
 * - The server still validates the session (/api/me) and enforces roles.
 */
@CapacitorPlugin(name = "BiometricAuth")
public class BiometricAuthPlugin extends Plugin {
    private static final String KEY_ALIAS = "erp_biometric_session_v1";
    private static final String PREFS = "erp_biometric";
    private static final String COOKIE = "erp_session";
    private static final long LOCK_AFTER_MS = 5 * 60 * 1000;
    private long pausedAt = 0;

    private SharedPreferences prefs() { return getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    private boolean enrolled() { return prefs().contains("ct") && prefs().contains("iv"); }

    @Override
    public void load() {
        // Cold start: never leave a usable session cookie lying around when biometric lock is on.
        if (enrolled()) removeCookie(prefs().getString("url", null));
    }

    @Override
    protected void handleOnPause() { pausedAt = SystemClock.elapsedRealtime(); }

    @Override
    protected void handleOnResume() {
        if (pausedAt > 0 && enrolled() && SystemClock.elapsedRealtime() - pausedAt > LOCK_AFTER_MS) {
            removeCookie(prefs().getString("url", null));
            notifyListeners("locked", new JSObject());
        }
        pausedAt = 0;
    }

    // ---------------- availability ----------------
    private int authenticators() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            ? BiometricManager.Authenticators.BIOMETRIC_STRONG | BiometricManager.Authenticators.DEVICE_CREDENTIAL
            : BiometricManager.Authenticators.BIOMETRIC_STRONG;
    }

    @PluginMethod
    public void status(PluginCall call) {
        BiometricManager bm = BiometricManager.from(getContext());
        int bio = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);
        JSObject r = new JSObject();
        r.put("biometric", bio == BiometricManager.BIOMETRIC_SUCCESS);
        r.put("deviceCredentialFallback", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R);
        r.put("reason", reason(bio));
        r.put("enabled", enrolled());
        r.put("userId", prefs().getString("userId", null));
        r.put("userName", prefs().getString("userName", null));
        call.resolve(r);
    }

    private String reason(int code) {
        switch (code) {
            case BiometricManager.BIOMETRIC_SUCCESS: return "ok";
            case BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED: return "none_enrolled";
            case BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE: return "no_hardware";
            case BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE: return "unavailable";
            case BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED: return "security_update";
            default: return "unsupported";
        }
    }

    // ---------------- enable ----------------
    @PluginMethod
    public void enable(PluginCall call) {
        String url = call.getString("url"), userId = call.getString("userId"), userName = call.getString("userName", "");
        if (url == null || userId == null) { call.reject("url and userId are required"); return; }
        if (BiometricManager.from(getContext()).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) != BiometricManager.BIOMETRIC_SUCCESS) {
            call.reject("No fingerprint or face is set up on this phone", "NOT_AVAILABLE"); return;
        }
        String token = readCookie(url);
        if (token == null) { call.reject("No active session. Sign in first.", "NO_SESSION"); return; }
        final Cipher cipher;
        try {
            deleteKey();
            cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, createKey());
        } catch (Exception e) { call.reject("Secure key could not be created: " + e.getMessage(), "KEYSTORE"); return; }

        prompt(call, cipher, "Enable biometric login", "Confirm it's you · ඔබ බව තහවුරු කරන්න", (c) -> {
            try {
                byte[] ct = c.doFinal(token.getBytes(StandardCharsets.UTF_8));
                prefs().edit()
                    .putString("ct", Base64.encodeToString(ct, Base64.NO_WRAP))
                    .putString("iv", Base64.encodeToString(c.getIV(), Base64.NO_WRAP))
                    .putString("url", url).putString("userId", userId).putString("userName", userName)
                    .apply();
                JSObject r = new JSObject(); r.put("enabled", true); call.resolve(r);
            } catch (Exception e) { wipe(); call.reject("Could not save secure credential", "KEYSTORE"); }
        });
    }

    // ---------------- unlock ----------------
    @PluginMethod
    public void unlock(PluginCall call) {
        if (!enrolled()) { call.reject("Biometric login is not set up", "NOT_ENROLLED"); return; }
        final Cipher cipher;
        try {
            SecretKey key = loadKey();
            if (key == null) { wipe(); call.reject("Biometric login was reset. Sign in with PIN or password.", "KEY_MISSING"); return; }
            byte[] iv = Base64.decode(prefs().getString("iv", ""), Base64.NO_WRAP);
            cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        } catch (KeyPermanentlyInvalidatedException e) {
            wipe(); call.reject("Fingerprints or face changed on this phone. Sign in with PIN or password, then enable biometrics again.", "ENROLLMENT_CHANGED"); return;
        } catch (Exception e) { wipe(); call.reject("Biometric login was reset. Sign in with PIN or password.", "KEYSTORE"); return; }

        prompt(call, cipher, "Sign in to Nugathalawa ERP", "Fingerprint or face · ඇඟිලි සලකුණ හෝ මුහුණ", (c) -> {
            try {
                String token = new String(c.doFinal(Base64.decode(prefs().getString("ct", ""), Base64.NO_WRAP)), StandardCharsets.UTF_8);
                String url = prefs().getString("url", null);
                CookieManager cm = CookieManager.getInstance();
                cm.setCookie(url, COOKIE + "=" + token + "; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=604800");
                cm.flush();
                JSObject r = new JSObject();
                r.put("userId", prefs().getString("userId", null));
                r.put("userName", prefs().getString("userName", null));
                call.resolve(r);
            } catch (Exception e) { wipe(); call.reject("Saved credential could not be read. Sign in with PIN or password.", "KEYSTORE"); }
        });
    }

    // ---------------- disable / lock ----------------
    @PluginMethod
    public void disable(PluginCall call) { wipe(); call.resolve(); }

    @PluginMethod
    public void lock(PluginCall call) {
        removeCookie(call.getString("url", prefs().getString("url", null)));
        call.resolve();
    }

    // ---------------- helpers ----------------
    private interface Success { void run(Cipher c); }

    private void prompt(PluginCall call, Cipher cipher, String title, String subtitle, Success onSuccess) {
        getActivity().runOnUiThread(() -> {
            BiometricPrompt.PromptInfo.Builder info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(title).setSubtitle(subtitle).setConfirmationRequired(false)
                .setAllowedAuthenticators(authenticators());
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) info.setNegativeButtonText("Use PIN or Password");
            BiometricPrompt bp = new BiometricPrompt((FragmentActivity) getActivity(), ContextCompat.getMainExecutor(getContext()),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                        BiometricPrompt.CryptoObject co = result.getCryptoObject();
                        if (co == null || co.getCipher() == null) { call.reject("Authentication failed", "FAILED"); return; }
                        onSuccess.run(co.getCipher());
                    }
                    @Override public void onAuthenticationError(int code, @NonNull CharSequence msg) {
                        String c;
                        switch (code) {
                            case BiometricPrompt.ERROR_USER_CANCELED: case BiometricPrompt.ERROR_NEGATIVE_BUTTON: case BiometricPrompt.ERROR_CANCELED: c = "CANCELLED"; break;
                            case BiometricPrompt.ERROR_LOCKOUT: c = "LOCKOUT"; break;
                            case BiometricPrompt.ERROR_LOCKOUT_PERMANENT: c = "LOCKOUT_PERMANENT"; break;
                            case BiometricPrompt.ERROR_NO_BIOMETRICS: c = "NOT_AVAILABLE"; break;
                            default: c = "ERROR";
                        }
                        call.reject(msg.toString(), c);
                    }
                    // onAuthenticationFailed: a single non-matching finger/face; the system prompt stays open.
                });
            bp.authenticate(info.build(), new BiometricPrompt.CryptoObject(cipher));
        });
    }

    private SecretKey createKey() throws Exception {
        KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG | KeyProperties.AUTH_DEVICE_CREDENTIAL);
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(b.build());
        return kg.generateKey();
    }

    private SecretKey loadKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        return (SecretKey) ks.getKey(KEY_ALIAS, null);
    }

    private void deleteKey() {
        try { KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null); ks.deleteEntry(KEY_ALIAS); } catch (Exception ignored) {}
    }

    private void wipe() { prefs().edit().clear().apply(); deleteKey(); }

    private String readCookie(String url) {
        String all = CookieManager.getInstance().getCookie(url);
        if (all == null) return null;
        for (String part : all.split(";")) {
            String p = part.trim();
            if (p.startsWith(COOKIE + "=")) { String v = p.substring(COOKIE.length() + 1); return v.isEmpty() ? null : v; }
        }
        return null;
    }

    private void removeCookie(String url) {
        if (url == null) return;
        CookieManager cm = CookieManager.getInstance();
        cm.setCookie(url, COOKIE + "=; Path=/; Max-Age=0");
        cm.flush();
    }
}
