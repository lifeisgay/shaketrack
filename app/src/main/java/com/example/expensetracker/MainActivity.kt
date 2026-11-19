package com.example.expensetracker

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The app itself: Google sign-in, then three tabs:
 *  - Dashboard: this month's income / spending for the family (or just me)
 *  - Recent: my last entries (admin: everyone's) — tap one to edit or delete
 *  - Settings: shake trigger, permissions, account, and (admin) family members
 */
class MainActivity : Activity() {

    private val scope = MainScope()
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private var tab = 0
    private val month: Calendar = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
    private var mineOnly = false
    private var signInMessage: String? = null
    private var firstResume = true

    private lateinit var body: LinearLayout
    private val tabButtons = mutableListOf<TextView>()

    // Palette
    private val INK = Color.parseColor("#0F172A")
    private val MUTED = Color.parseColor("#64748B")
    private val LINE = Color.parseColor("#E2E8F0")
    private val BG = Color.parseColor("#F8FAFC")
    private val INDIGO = Color.parseColor("#4F46E5")
    private val RED = Color.parseColor("#DC2626")
    private val GREEN = Color.parseColor("#16A34A")
    private val BLUE = Color.parseColor("#2563EB")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.isSignedIn(this)) {
            ShakeService.start(this)
            Api.refreshLists(this)
            // Coming back (e.g. after adding an entry or granting a permission): reload the tab
            if (!firstResume && ::body.isInitialized && tabButtons.isNotEmpty()) showTab(tab)
        }
        firstResume = false
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun render() {
        when {
            !Prefs.isConfigured(this) -> notConfiguredScreen()
            !Prefs.isSignedIn(this) -> signInScreen()
            else -> mainScreen()
        }
    }

    /** Runs a server call; shows errors, and drops back to sign-in if the session ended. */
    private fun launch(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.auth) {
                    signInMessage = e.message
                    render()
                } else toast(e.message ?: "Something went wrong")
            } catch (_: IOException) {
                toast("No connection to the sheet. Try again.")
            } catch (e: Exception) {
                toast(e.message ?: "Something went wrong")
            }
        }
    }

    // =========================================================================
    // Screens
    // =========================================================================

    private fun notConfiguredScreen() {
        val root = page()
        root.addView(title("⚡ ShakeTrack"))
        root.addView(card().apply {
            addView(text("This build isn't connected to a sheet yet.\n\nFill in script_url and google_web_client_id in app/src/main/res/values/config.xml, then rebuild. See the README.", 14f, INK))
        })
        setContentView(ScrollView(this).apply { setBackgroundColor(BG); addView(root) })
    }

    private fun signInScreen() {
        tabButtons.clear()
        val root = page().apply { gravity = Gravity.CENTER_HORIZONTAL }
        root.addView(space(48))
        root.addView(text("⚡", 48f, INK).apply { gravity = Gravity.CENTER })
        root.addView(title("ShakeTrack").apply { gravity = Gravity.CENTER })
        root.addView(text("Family expenses in one Google Sheet.\nShake your phone, type the amount, tap a tag. Done.", 14f, MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(32))
        })

        val status = text(signInMessage ?: "", 13f, RED).apply { gravity = Gravity.CENTER }
        val btn = primaryButton("Sign in with Google") {}
        btn.setOnClickListener {
            btn.isEnabled = false
            status.setTextColor(MUTED)
            status.text = "Signing in…"
            scope.launch {
                try {
                    val j = GoogleAuth.signIn(this@MainActivity)
                    if (j == null) {
                        status.text = ""
                    } else {
                        signInMessage = null
                        tab = 0
                        askNotificationPermission()
                        render()
                        ShakeService.start(this@MainActivity)
                        return@launch
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    status.setTextColor(RED); status.text = "No connection to the sheet. Try again."
                } catch (e: Exception) {
                    status.setTextColor(RED); status.text = e.message ?: "Sign-in failed"
                }
                btn.isEnabled = true
            }
        }
        root.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        root.addView(status.apply { setPadding(0, dp(16), 0, 0) })
        root.addView(text("Only family members the admin has added can sign in.", 12f, MUTED).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(24), 0, 0)
        })
        setContentView(ScrollView(this).apply { setBackgroundColor(BG); addView(root) })
    }

    private fun mainScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(8))
        }
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        names.addView(text("⚡ ShakeTrack", 20f, INK, bold = true))
        val who = Prefs.name(this).ifEmpty { Prefs.email(this) } + if (Prefs.isAdmin(this)) " · Admin" else ""
        names.addView(text(who, 12f, MUTED))
        header.addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(primaryButton("+ Add") {
            startActivity(Intent(this, ExpenseActivity::class.java))
        }.apply { setPadding(dp(18), 0, dp(18), 0) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        root.addView(header)

        // Tabs
        tabButtons.clear()
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(4), dp(16), dp(8))
        }
        listOf("Dashboard", "Recent", "Settings").forEachIndexed { i, label ->
            val t = TextView(this).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, dp(9), 0, dp(9))
                setOnClickListener { showTab(i) }
            }
            tabButtons.add(t)
            tabs.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(4), 0, dp(4), 0)
            })
        }
        root.addView(tabs)

        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(32))
        }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        showTab(tab)
    }

    private fun showTab(i: Int) {
        tab = i
        tabButtons.forEachIndexed { idx, t ->
            val on = idx == i
            t.background = round(if (on) INDIGO else Color.WHITE, 12, if (on) 0 else LINE)
            t.setTextColor(if (on) Color.WHITE else Color.parseColor("#334155"))
            t.typeface = Typeface.create("sans-serif-medium", if (on) Typeface.BOLD else Typeface.NORMAL)
        }
        body.removeAllViews()
        when (i) {
            0 -> dashboardTab()
            1 -> recentTab()
            else -> settingsTab()
        }
    }

    // =========================================================================
    // Dashboard
    // =========================================================================

    private fun dashboardTab() {
        // Month switcher
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(8))
        }
        val monthLabel = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(month.time)
        nav.addView(chip("‹") { month.add(Calendar.MONTH, -1); showTab(0) })
        nav.addView(text(monthLabel, 16f, INK, bold = true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        nav.addView(chip("›") { month.add(Calendar.MONTH, 1); showTab(0) })
        body.addView(nav)

        // Family / Mine
        val toggle = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(12)) }
        toggle.addView(segment("Family", !mineOnly) { mineOnly = false; showTab(0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
        toggle.addView(segment("Only me", mineOnly) { mineOnly = true; showTab(0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        body.addView(toggle)

        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        holder.addView(text("Loading…", 13f, MUTED).apply { setPadding(0, dp(24), 0, 0); gravity = Gravity.CENTER })
        body.addView(holder)

        val reqYear = month.get(Calendar.YEAR)
        val reqMonth = month.get(Calendar.MONTH)
        launch {
            val j = Api.call(this@MainActivity, "summary", JSONObject()
                .put("year", reqYear).put("month", reqMonth).put("scope", if (mineOnly) "me" else "family"))
            if (tab != 0 || !holder.isAttachedToWindow) return@launch
            holder.removeAllViews()
            fillDashboard(holder, j)
        }
    }

    private fun fillDashboard(holder: LinearLayout, j: JSONObject) {
        val income = j.optDouble("income", 0.0)
        val expense = j.optDouble("expense", 0.0)
        val saved = income - expense

        // KPI cards
        val kpis = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        kpis.addView(kpi("Income", income, BLUE), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
        kpis.addView(kpi("Spent", expense, RED), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
        kpis.addView(kpi("Left", saved, if (saved >= 0) GREEN else RED), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        holder.addView(kpis)

        val rate = if (income > 0) "${((saved / income) * 100).toInt()}% of income saved" else ""
        val count = j.optInt("count")
        holder.addView(text(listOf("$count entries", rate).filter { it.isNotEmpty() }.joinToString(" · "), 12f, MUTED).apply {
            setPadding(dp(4), dp(8), 0, dp(12))
        })

        if (count == 0) {
            holder.addView(card().apply { addView(text("Nothing logged this month yet. Shake your phone to add the first one.", 14f, MUTED)) })
            return
        }

        holder.addView(breakdown("Where the money went", j.optJSONArray("expense_categories"), expense, RED))
        val inc = j.optJSONArray("income_categories")
        if (inc != null && inc.length() > 0) holder.addView(breakdown("Money in", inc, income, BLUE))

        val people = j.optJSONArray("people")
        if (!mineOnly && people != null && people.length() > 1) {
            val c = card()
            c.addView(sectionTitle("By person"))
            var max = 1.0
            for (k in 0 until people.length()) max = maxOf(max, people.getJSONObject(k).optDouble("expense"))
            for (k in 0 until people.length()) {
                val p = people.getJSONObject(k)
                c.addView(barRow(p.optString("name"), p.optDouble("expense"), p.optDouble("expense") / max, RED,
                    "in ${money(p.optDouble("income"))}"))
            }
            holder.addView(c)
        }
    }

    private fun breakdown(heading: String, arr: JSONArray?, total: Double, color: Int): View {
        val c = card()
        c.addView(sectionTitle(heading))
        if (arr == null) return c
        for (k in 0 until arr.length()) {
            val o = arr.getJSONObject(k)
            val amt = o.optDouble("amount")
            val frac = if (total > 0) amt / total else 0.0
            c.addView(barRow(o.optString("name"), amt, frac, color, "${(frac * 100).toInt()}%"))
        }
        return c
    }

    private fun barRow(label: String, amount: Double, frac: Double, color: Int, note: String): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(text(label, 13f, INK), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(text(note, 11f, MUTED).apply { setPadding(0, 0, dp(10), 0) })
        top.addView(text(money(amount), 13f, INK, bold = true))
        row.addView(top)
        val f = frac.coerceIn(0.0, 1.0).toFloat()
        val track = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = round(Color.parseColor("#F1F5F9"), 4)
        }
        track.addView(View(this).apply { background = round(color, 4) },
            LinearLayout.LayoutParams(0, dp(6), maxOf(f, 0.01f)))
        track.addView(View(this), LinearLayout.LayoutParams(0, dp(6), maxOf(1f - f, 0f)))
        row.addView(track, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(5) })
        return row
    }

    private fun kpi(label: String, value: Double, color: Int): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = round(Color.WHITE, 14, LINE)
            addView(text(label.uppercase(), 10f, color, bold = true))
            addView(text(money(value), 16f, INK, bold = true).apply { setPadding(0, dp(4), 0, 0); maxLines = 1 })
        }
    }

    // =========================================================================
    // Recent entries (edit / delete)
    // =========================================================================

    private fun recentTab() {
        val pendingNote = text("", 12f, Color.parseColor("#92400E")).apply {
            visibility = View.GONE
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = round(Color.parseColor("#FEF3C7"), 10)
        }
        body.addView(pendingNote, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        body.addView(text(
            if (Prefs.isAdmin(this)) "Latest family entries. Tap one to edit or delete." else "Your latest entries. Tap one to edit or delete.",
            12f, MUTED
        ).apply { setPadding(dp(4), 0, 0, dp(10)) })

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(text("Loading…", 13f, MUTED).apply { setPadding(0, dp(24), 0, 0); gravity = Gravity.CENTER })
        body.addView(list)

        launch {
            val pending = withContext(Dispatchers.IO) { UploadWorker.pendingCount(this@MainActivity) }
            if (pending > 0) {
                pendingNote.text = "⏳ $pending saved on this phone, waiting to upload (needs internet)."
                pendingNote.visibility = View.VISIBLE
            }
            val j = Api.call(this@MainActivity, "recent")
            if (tab != 1 || !list.isAttachedToWindow) return@launch
            list.removeAllViews()
            val arr = j.optJSONArray("entries") ?: JSONArray()
            if (arr.length() == 0) {
                list.addView(card().apply { addView(text("No entries yet.", 14f, MUTED)) })
                return@launch
            }
            val fmt = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault())
            for (k in 0 until arr.length()) {
                val e = arr.getJSONObject(k)
                list.addView(entryRow(e, fmt))
            }
        }
    }

    private fun entryRow(e: JSONObject, fmt: SimpleDateFormat): View {
        val income = e.optString("type") == "Income"
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = round(Color.WHITE, 12, LINE)
            isClickable = true
            setOnClickListener { editDialog(e) }
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(text(e.optString("category"), 14f, INK, bold = true))
        val meta = mutableListOf(fmt.format(Date(e.optLong("ts"))), e.optString("payment"))
        if (Prefs.isAdmin(this) && e.optString("email") != Prefs.email(this)) meta.add(e.optString("user"))
        left.addView(text(meta.filter { it.isNotEmpty() }.joinToString(" · "), 11f, MUTED))
        row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(text((if (income) "+" else "−") + money(e.optDouble("amount")), 15f, if (income) GREEN else RED, bold = true))
        return row.also {
            it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        }
    }

    private fun editDialog(e: JSONObject) {
        val expCats = Prefs.expenseCategories(this)
        val incCats = Prefs.incomeCategories(this)
        // One list: expense tags, then income tags shown with "+"
        val curCat = e.optString("category")
        val curIncome = e.optString("type") == "Income"
        val exp = if (!curIncome && curCat !in expCats) expCats + curCat else expCats
        val inc = if (curIncome && curCat !in incCats) incCats + curCat else incCats
        val labels = exp + inc.map { "+ $it" }

        val pays = Prefs.payments(this).let { if (e.optString("payment") in it) it else it + e.optString("payment") }

        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        form.addView(fieldLabel("Amount (₹)"))
        val amount = input("0", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL).apply {
            val a = e.optDouble("amount")
            setText(if (a % 1.0 == 0.0) a.toLong().toString() else a.toString())
        }
        form.addView(amount)
        form.addView(fieldLabel("Category"))
        val cat = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, labels)
            setSelection(if (curIncome) exp.size + inc.indexOf(curCat) else exp.indexOf(curCat))
        }
        form.addView(cat)
        form.addView(fieldLabel("Paid with"))
        val pay = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, pays)
            setSelection(pays.indexOf(e.optString("payment")).coerceAtLeast(0))
        }
        form.addView(pay)

        val dlg = AlertDialog.Builder(this)
            .setTitle("Edit entry")
            .setView(form)
            .setPositiveButton("Save", null)
            .setNeutralButton("Delete", null)
            .setNegativeButton("Cancel", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = amount.text.toString().trim().toDoubleOrNull()
                if (value == null || value <= 0) { toast("Enter an amount"); return@setOnClickListener }
                val idx = cat.selectedItemPosition
                val isIncome = idx >= exp.size
                val category = if (isIncome) inc[idx - exp.size] else exp[idx]
                launch {
                    Api.call(this@MainActivity, "update", JSONObject()
                        .put("id", e.optString("id"))
                        .put("amount", value)
                        .put("type", if (isIncome) "Income" else "Expense")
                        .put("category", category)
                        .put("payment", pay.selectedItem as String))
                    dlg.dismiss()
                    toast("✓ Updated")
                    showTab(1)
                }
            }
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).apply {
                setTextColor(RED)
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Delete this entry?")
                        .setMessage("${e.optString("category")} · ${money(e.optDouble("amount"))}\nIt will be removed from the sheet. This can't be undone.")
                        .setPositiveButton("Delete") { _, _ ->
                            launch {
                                Api.call(this@MainActivity, "delete", JSONObject().put("id", e.optString("id")))
                                dlg.dismiss()
                                toast("Deleted")
                                showTab(1)
                            }
                        }
                        .setNegativeButton("Keep", null)
                        .show()
                }
            }
        }
        dlg.show()
    }

    // =========================================================================
    // Settings
    // =========================================================================

    private fun settingsTab() {
        // Shake trigger
        val shake = card()
        shake.addView(sectionTitle("SHAKE TO ADD"))
        val sw = Switch(this).apply {
            text = "Shake the phone to open the add screen"
            textSize = 14f
            setTextColor(INK)
            isChecked = Prefs.shakeEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                Prefs.setShakeEnabled(this@MainActivity, on)
                if (on) ShakeService.start(this@MainActivity) else ShakeService.stop(this@MainActivity)
            }
        }
        shake.addView(sw)
        shake.addView(text("Give the phone 3 quick, firm shakes while the screen is on. A small notification stays while it's listening.", 12f, MUTED).apply {
            setPadding(0, dp(6), 0, dp(12))
        })
        shake.addView(secondaryButton("Open the add screen now") {
            startActivity(Intent(this, ExpenseActivity::class.java))
        })
        body.addView(shake)

        // Permissions
        val perms = card()
        perms.addView(sectionTitle("PERMISSIONS"))
        val overlay = Settings.canDrawOverlays(this)
        perms.addView(actionButton(if (overlay) "✓ Display over other apps" else "Allow display over other apps (needed for shake)", overlay) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        if (Build.VERSION.SDK_INT >= 33) {
            val notif = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            perms.addView(actionButton(if (notif) "✓ Notifications" else "Allow notifications", notif) {
                askNotificationPermission()
            })
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val exempt = pm.isIgnoringBatteryOptimizations(packageName)
        perms.addView(actionButton(if (exempt) "✓ Battery optimization off" else "Turn off battery optimization (keeps shake working)", exempt) {
            if (!exempt) startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:$packageName")))
        })
        body.addView(perms)

        // Family (admin only)
        if (Prefs.isAdmin(this)) body.addView(familyCard())

        // Account
        val acct = card()
        acct.addView(sectionTitle("ACCOUNT"))
        acct.addView(text(Prefs.name(this), 15f, INK, bold = true))
        acct.addView(text(Prefs.email(this) + if (Prefs.isAdmin(this)) " · Admin" else " · Family member", 12f, MUTED).apply {
            setPadding(0, 0, 0, dp(12))
        })
        acct.addView(secondaryButton("Sign out") {
            AlertDialog.Builder(this)
                .setTitle("Sign out?")
                .setMessage("Entries not uploaded yet stay on this phone and upload after you sign in again.")
                .setPositiveButton("Sign out") { _, _ ->
                    scope.launch {
                        GoogleAuth.signOut(this@MainActivity)
                        ShakeService.stop(this@MainActivity)
                        render()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        })
        body.addView(acct)
    }

    private fun familyCard(): View {
        val c = card()
        c.addView(sectionTitle("FAMILY MEMBERS"))
        c.addView(text("Only these Google accounts can sign in. Everyone sees the family dashboard; members can edit only their own entries.", 12f, MUTED).apply {
            setPadding(0, 0, 0, dp(10))
        })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(text("Loading…", 13f, MUTED))
        c.addView(list)

        val addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        val email = input("name@gmail.com", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        addRow.addView(email, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
        addRow.addView(primaryButton("Add") {
            val v = email.text.toString().trim()
            if (v.isEmpty()) return@primaryButton
            launch {
                val j = Api.call(this@MainActivity, "member_add", JSONObject().put("email", v))
                email.setText("")
                fillMembers(list, j)
                toast("✓ $v can now sign in")
            }
        }.apply { setPadding(dp(18), 0, dp(18), 0) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46)))
        c.addView(addRow)

        launch { fillMembers(list, Api.call(this@MainActivity, "members")) }
        return c
    }

    private fun fillMembers(list: LinearLayout, j: JSONObject) {
        list.removeAllViews()
        val admin = j.optString("admin")
        val arr = j.optJSONArray("members") ?: JSONArray()
        for (k in 0 until arr.length()) {
            val m = arr.getString(k)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            row.addView(text(m, 13f, INK), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (m == admin) {
                row.addView(text("Admin", 11f, INDIGO, bold = true))
            } else {
                row.addView(text("Remove", 12f, RED, bold = true).apply {
                    setPadding(dp(8), dp(4), 0, dp(4))
                    setOnClickListener {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Remove $m?")
                            .setMessage("They will be signed out and can't add entries. Their past entries stay in the sheet.")
                            .setPositiveButton("Remove") { _, _ ->
                                launch { fillMembers(list, Api.call(this@MainActivity, "member_remove", JSONObject().put("email", m))) }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                })
            }
            list.addView(row)
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    // =========================================================================
    // Small UI helpers
    // =========================================================================

    private fun money(v: Double): String {
        val f = android.icu.text.NumberFormat.getNumberInstance(Locale("en", "IN")).apply { maximumFractionDigits = 0 }
        return (if (v < 0) "-₹" else "₹") + f.format(kotlin.math.abs(v))
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun round(color: Int, radiusDp: Int, stroke: Int = 0) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun page() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(32), dp(24), dp(32))
    }

    private fun space(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        typeface = Typeface.create("sans-serif-medium", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun title(t: String) = text(t, 26f, INK, bold = true)

    private fun sectionTitle(t: String) = text(t, 11f, INDIGO, bold = true).apply {
        letterSpacing = 0.06f
        setPadding(0, 0, 0, dp(8))
    }

    private fun fieldLabel(t: String) = text(t, 12f, Color.parseColor("#334155")).apply { setPadding(0, dp(10), 0, dp(4)) }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = round(Color.WHITE, 16, LINE)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        }
    }

    private fun input(h: String, type: Int) = EditText(this).apply {
        hint = h
        setHintTextColor(Color.parseColor("#94A3B8"))
        textSize = 15f
        inputType = type
        setSingleLine()
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = round(BG, 10, Color.parseColor("#CBD5E1"))
    }

    private fun chip(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t
        textSize = 20f
        setTextColor(INDIGO)
        gravity = Gravity.CENTER
        background = round(Color.WHITE, 12, LINE)
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(40))
        setOnClickListener { onClick() }
    }

    private fun segment(t: String, on: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = t
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(0, dp(8), 0, dp(8))
        background = round(if (on) Color.parseColor("#EEF2FF") else Color.WHITE, 10, if (on) Color.parseColor("#C7D2FE") else LINE)
        setTextColor(if (on) Color.parseColor("#4338CA") else MUTED)
        typeface = Typeface.create("sans-serif-medium", if (on) Typeface.BOLD else Typeface.NORMAL)
        setOnClickListener { onClick() }
    }

    private fun primaryButton(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextColor(Color.WHITE)
        background = round(INDIGO, 12)
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    private fun secondaryButton(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextColor(Color.parseColor("#4338CA"))
        background = round(Color.parseColor("#EEF2FF"), 12, Color.parseColor("#C7D2FE"))
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    private fun actionButton(t: String, done: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        textSize = 13f
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(dp(14), 0, dp(14), 0)
        setTextColor(if (done) Color.parseColor("#15803D") else INK)
        background = round(if (done) Color.parseColor("#F0FDF4") else Color.parseColor("#F1F5F9"), 10,
            if (done) Color.parseColor("#BBF7D0") else LINE)
        stateListAnimator = null
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        }
        setOnClickListener { onClick() }
    }
}
