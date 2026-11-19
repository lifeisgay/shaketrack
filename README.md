# ShakeTrack — family expense tracker

Shake the phone → type the amount → tap a tag. The entry lands in a shared Google Sheet.
Everyone in the family signs in with their own Google account; the sheet owner is the admin.

## How it works

- **Sign in with Google.** The app gets a Google ID token, the Apps Script checks it with Google and
  checks the email is on the family list (`Lists!D`). The app then gets its own session token.
  There is no shared password.
- **Admin** = the Google account that owns the Apps Script (the account it runs as). The admin can add or
  remove family members in the app (Settings → Family members), and can see, edit and delete everyone's entries.
- **Members** see the family dashboard and can edit or delete only their own entries.
- **Shake to add.** A small foreground service watches the accelerometer while the screen is on.
  Three quick, firm shakes open the add screen. The Quick Settings tile and the notification's "Add now" do the same.
- **Offline.** Entries are queued with WorkManager and uploaded when there's internet. Each entry has an ID, so a retry never duplicates a row.
- **In the app:** Dashboard (month by month, family or only me), Recent (tap to edit/delete), Settings.

## One-time setup

### 1. Sheet + Apps Script
1. Open the "Expense Tracker" Google Sheet → Extensions → Apps Script. Paste `apps_script/Code.gs`.
2. Project Settings (gear) → set the **time zone** to yours (e.g. Asia/Kolkata) so months add up correctly.
3. Run `setup()` once (it keeps existing data; it adds the ID/Email columns and the Family Emails column).

### 2. Google Cloud OAuth clients (needed for "Sign in with Google")
In <https://console.cloud.google.com> with the same Google account:
1. Create a project (e.g. "ShakeTrack").
2. **Google Auth Platform → Branding/Audience:** app name ShakeTrack, user type *External*.
   Either add each family member's Gmail under *Test users*, or press *Publish app*
   (only the basic email/profile scopes are used, so no verification review is needed).
3. **Clients → Create client → Web application** → name "ShakeTrack server". Copy its **Client ID**.
4. **Clients → Create client → Android:** package name `com.example.expensetracker`, SHA-1 fingerprint of
   the signing key (see step 4 below).

### 3. Fill in the two IDs
- `apps_script/Code.gs`: `WEB_CLIENT_ID = "<web client id>"`.
- Apps Script → **Deploy → New deployment → Web app**, Execute as: **Me**, Who has access: **Anyone**.
  Approve the permissions (it needs to call Google to verify sign-ins). Copy the `/exec` URL.
  (After later code changes use Deploy → Manage deployments → Edit → New version, so the URL stays the same.)
- `app/src/main/res/values/config.xml`: `script_url` = the `/exec` URL, `google_web_client_id` = the web client ID.

### 4. Signing key (must stay the same for every build)
Google sign-in only accepts the APK signed with the key whose SHA-1 you registered.
The key lives at `app/shaketrack.keystore` (git-ignored, don't commit it, keep a backup).
- Show its SHA-1 (paste this into the Android OAuth client):
  `keytool -list -v -keystore app/shaketrack.keystore -storepass android -alias shaketrack`
- For GitHub Actions builds, add it as a repository secret named `DEBUG_KEYSTORE_BASE64`
  (Settings → Secrets and variables → Actions). Copy the value with PowerShell:
  `[Convert]::ToBase64String([IO.File]::ReadAllBytes("app\shaketrack.keystore")) | Set-Clipboard`

### 5. Build and install
- Push to `main` (or run the "Build APK" workflow) and download `ShakeTrack-apk` from the run,
  or open the folder in Android Studio and Run.
- Install on each phone, open it, **Sign in with Google**.
- In Settings allow: *Display over other apps* (needed for shake to open the screen from anywhere),
  notifications, and turn off battery optimization.

## Adding family members
Admin: app → Settings → Family members → type their Gmail → Add. (Or type emails into `Lists!D` in the sheet.)
If your OAuth app is still in *Testing*, also add them as test users in Google Cloud.

## Sheet layout
- **Expenses**: Date, Type, Category, Amount, Payment, User, Net Amount, ID, Email (one row per entry).
- **Lists**: expense categories, income categories, payment methods, family emails. Edit categories here; the app picks them up.
- **Dashboard** / **Annual View**: built by `setup()` from the Expenses tab.

## Tuning the shake
`ShakeService.kt`: `THRESHOLD_G` (how hard), `SHAKES_NEEDED`, `WINDOW_MS`, `COOLDOWN_MS`.
