# Sanjeewa Distribution ERP — Android app

Package `lk.com.harshapex.sanjeewaerp` · Capacitor 8 · direct APK install (no Play Store)

## How it works

```
Android app (bundled Next.js UI, web/out)  ──native HTTPS──▶  existing ERP Worker (Erp-system)  ──▶  D1
```

- The UI from `Erp-system` is bundled inside the APK (fast start, works on weak networks). Nothing is loaded from a website.
- API calls use Capacitor's **native HTTP**. The HttpOnly `erp_session` cookie is stored in Android's private cookie store, not readable by JavaScript.
- **No backend changes.** The app talks to the same `/api/*` endpoints with the same login, roles, permission checks and business rules as the web ERP. Admin-only features stay hidden and server-blocked for Sales Representatives.
- This repository **cannot deploy**: `wrangler.jsonc` has no production bindings, and `deploy` / `db:migrate:remote` are disabled. Production stays in `raveenn10-bit/Erp-system`.

## One-time setup (GitHub)

1. **Settings → Secrets and variables → Actions → Variables → New repository variable**
   - Name: `ERP_API_URL`
   - Value: the HTTPS address you open the ERP at today, e.g. `https://sanjeewa-distribution-erp.<your-account>.workers.dev` (no trailing slash)
   This is a public address, not a secret. The APK build stops with a clear error if it's missing.
2. Push to `feature/capacitor-android-app` (or run the workflow manually: **Actions → Android APK → Run workflow**).

## Download and install the APK

**Direct link (opens the download straight away on the phone, no GitHub login):**
https://github.com/raveenn10-bit/Erp-app/releases/latest/download/Nugathalawa-ERP.apk

This always points to the newest build. Every push publishes a release `android-v1.0.<run>`; only the 5 newest are kept.

Alternative (GitHub login needed):
1. GitHub → **Actions** → latest green **Android APK** run → **Artifacts** → `Sanjeewa-ERP-Android` (downloads a .zip).
2. Unzip; copy the `.apk` to the phone (WhatsApp to yourself, Google Drive or USB).
3. On the phone, tap the APK. When Android asks, allow **Install unknown apps** for the app you opened it with (Files / Chrome / Drive). Then tap **Install**.
4. If Play Protect warns about an unknown developer, choose **More details → Install anyway**. That's expected for apps not from the Play Store.

## Updating later

- Every CI build has a higher version code (`github.run_number`), so a newer APK **installs over the old one** without uninstalling, and you stay signed in.
- Updates only work if the APK is signed with the **same key**. Debug APKs are signed with the CI runner's temporary debug key, so switching from a debug APK to a release APK (or between debug APKs built on different machines) needs a one-time uninstall.

## Release signing (for client distribution)

Create the key once, keep it safe forever (losing it means users must uninstall to update):

```bash
keytool -genkeypair -v -keystore sanjeewa-release.jks -alias sanjeewa \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 sanjeewa-release.jks > keystore.b64
```

Add repository **Secrets**: `ANDROID_KEYSTORE_BASE64` (contents of keystore.b64), `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` (`sanjeewa`), `ANDROID_KEY_PASSWORD`. Then **Run workflow** with *release* ticked → artifact `Sanjeewa-ERP-Android-Release`. Never commit `.jks`, `keystore.properties` or passwords (they're in `.gitignore`).

## Invoices

One data model (`web/lib/invoice/model.ts`) drives the preview, A4 PDF, A4 print, thermal receipt and WhatsApp text, using the server's amounts only (integer cents, LKR, Asia/Colombo dates).

| Action | What happens |
|---|---|
| **Send PDF via WhatsApp** | Builds a real PDF, opens the Android share sheet. Pick WhatsApp, then the customer (Android can't pre-select a WhatsApp contact for files; the app shows the saved number). Nothing is marked "delivered". |
| **Open chat (text + link)** | Opens the customer's WhatsApp chat with the number pre-filled, summary text and online invoice link (no attachment). |
| **Download PDF** | Saves to `Documents/SanjeewaERP/`. If the phone refuses, the share sheet opens so you can "Save to Files"/Drive. |
| **Print A4** | Android print dialog (Wi-Fi/Mopria printers, Save as PDF) at true A4 size. |
| **Receipt 58/80 mm** | Bluetooth ESC/POS printer (paired in Android Settings first) or the Android print service. 58 mm, 80 mm or custom width. **Test print** in the printer settings (⚙). |

PDFs and receipts are drawn by the phone's own text engine, so Sinhala prints correctly. Text in the PDF is an image (not selectable).

## Backend items for approval (not done — production untouched)

1. **CSRF origin for the app.** The Worker requires an `Origin` header on writes. Native HTTP doesn't send one, so the app sends the ERP's own origin plus `X-ERP-Client: android`. Login, cookies, roles and permissions are still fully checked by the server. Recommended later, with your approval: let the Worker explicitly accept the app (e.g. allow `X-ERP-Client: android` requests without a browser Origin, or move the app to bearer tokens), then remove the Origin header from `web/lib/api.ts`.
2. **Public invoice link** (`/view/invoice/:token`) is unchanged and still served by the production Worker.

## Verified vs not verified

Verified in this environment (local Worker + throwaway local D1, headless Chromium emulating Capacitor's routing): typecheck, 27 worker unit tests, production web build, cold start on a deep link, login → dashboard → reload, invoice sheet on a 390 px screen, valid 2-page A4 PDF (21 lines) and 1-page PDF (3 lines), valid 58 mm receipt PDF.

**Not verified** (no Android SDK or device available here): the Gradle/APK build itself (runs in GitHub Actions), native HTTP + cookie behaviour on a phone, share sheet → WhatsApp, Documents saving, Android print dialog, Bluetooth printing on any printer model, back button, status bar, splash. Bluetooth printing targets Bluetooth Classic (SPP) ESC/POS printers that support raster `GS v 0`; BLE-only printers are not supported. Test on the actual phones and printers before rollout.

## Local development

```bash
npm ci
cp .env.example .dev.vars   # then set SETUP_TOKEN and FRONTEND_ORIGIN=http://localhost:3000
npx wrangler d1 migrations apply sanjeewa-erp-localdev --local   # local throwaway DB only
npx wrangler dev --port 8787
cd web && NEXT_PUBLIC_API_URL=http://localhost:8787 npx next build && npx cap sync android
npx cap open android   # Android Studio, run on a device
```
