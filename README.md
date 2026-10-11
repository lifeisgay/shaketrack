# Expense Tracker (Android)

Trigger -> popup (amount + tag) -> Google Sheet.

## 1. Sheet backend
1. Use the "Expense Tracker" Google Sheet (tabs: Dashboard, Expenses, Lists).
2. Extensions -> Apps Script, paste `apps_script/Code.gs`, set your own SECRET, run `addDashboardCharts` once.
3. Deploy -> New deployment -> Web app -> Execute as: Me, Access: Anyone. Copy the URL.

## 2. Build
Open this folder in Android Studio (it will sync Gradle and create the wrapper), then Run on your phone.

## 3. Configure on the phone
1. Open the app, paste the URL and secret, set your name, categories and payment types, Save.
2. "Test entry screen" to check a row appears in the sheet.
3. Enable the volume trigger: Settings -> Accessibility -> Expense Tracker -> On.
   (If sideloaded on Android 13+: App info -> ⋮ -> Allow restricted settings first.)
4. Disable battery optimization (button in the app).
5. Optional: add the "Add expense" tile in Quick Settings.

## Changing the trigger
Every trigger just starts `ExpenseActivity`. Edit `VolumeTriggerService.kt` (key, press count, timing)
or add a new trigger that launches the same activity.


## Build the APK without Android Studio
1. Create a free GitHub account and a new repository.
2. Upload the contents of this folder (including `.github/workflows/build-apk.yml`).
3. Open the Actions tab, run "Build APK", then download `ExpenseTracker-apk` from the finished run.
4. Unzip it, copy `app-debug.apk` to the phone and install it (allow "Install unknown apps").
