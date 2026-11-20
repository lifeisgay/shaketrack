package com.example.expensetracker

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
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
 * The app: Google sign-in, then three tabs on a bottom bar (thumb-reachable):
 *  - Home: this month's money for the family (or just me)
 *  - Recent: latest entries; tap to edit, long-press to delete
 *  - Settings: shake trigger, permissions, account, and (admin) family members
 * The round + button opens the quick-add sheet from any tab.
 */
class MainActivity : Activity() {

    private val scope = MainScope()
    private fun dp(v: Int) = Ui.dp(this, v)

    private var tab = 0
    private val month: Calendar = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
    private var mineOnly = false
    private var signInMessage: String? = null
    private var firstResume = true

    private lateinit var body: LinearLayout
    private lateinit var scroller: ScrollView
    private var fab: View? = null
    private val navItems = mutableListOf<LinearLayout>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.isSignedIn(this)) {
            ShakeService.start(this)
            Api.refreshLists(this)
            // Coming back (after adding an entry or granting a permission): reload the tab
            if (!firstResume && ::body.isInitialized && navItems.isNotEmpty()) showTab(tab)
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
    private fun launch(onFail: ((String) -> Unit)? = null, block: suspend () -> Unit) {
        scope.launch {
            val msg = try {
                block()
                return@launch
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.auth) {
                    signInMessage = e.message
                    render()
                    return@launch
                }
                e.message ?: "Something went wrong"
            } catch (_: IOException) {
                "Couldn't reach the sheet. Check your internet."
            } catch (e: Exception) {
                e.message ?: "Something went wrong"
            }
            if (onFail != null) onFail(msg) else toast(msg)
        }
    }

    /** Shown in place of "Loading…" when a tab couldn't load. */
    private fun showError(holder: LinearLayout, msg: String) {
        if (!holder.isAttachedToWindow) return
        holder.removeAllViews()
        holder.addView(Ui.card(this).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(Ui.text(this@MainActivity, "📡", 32f).apply { gravity = Gravity.CENTER })
            addView(Ui.text(this@MainActivity, msg, 16f, Ui.MUTED).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(14))
            })
            addView(Ui.button(this@MainActivity, "Retry", primary = false) { showTab(tab) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        })
    }

    // =========================================================================
    // Sign-in / not configured
    // =========================================================================

    private fun page() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(32), dp(24), dp(32))
    }

    private fun notConfiguredScreen() {
        val root = page()
        root.addView(Ui.text(this, "⚡ ShakeTrack", 28f, bold = true))
        root.addView(Ui.card(this).apply {
            addView(Ui.text(this@MainActivity,
                "This build isn't connected to a sheet yet.\n\nFill in script_url and google_web_client_id in app/src/main/res/values/config.xml, then rebuild.",
                16f, Ui.MUTED))
        })
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(root) })
    }

    private fun signInScreen() {
        navItems.clear()
        val root = page().apply { gravity = Gravity.CENTER_HORIZONTAL }
        root.addView(Ui.space(this, 56))
        root.addView(Ui.text(this, "⚡", 56f).apply { gravity = Gravity.CENTER })
        root.addView(Ui.text(this, "ShakeTrack", 32f, bold = true).apply { gravity = Gravity.CENTER })
        root.addView(Ui.text(this, "Family expenses in one Google Sheet.\nShake your phone, type the amount, tap a tag.", 16f, Ui.MUTED).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
            setPadding(0, dp(8), 0, dp(40))
        })

        val status = Ui.text(this, signInMessage ?: "", 15f, Ui.RED).apply { gravity = Gravity.CENTER }
        lateinit var btn: Button
        btn = Ui.button(this, "Sign in with Google") {
            btn.isEnabled = false
            status.setTextColor(Ui.MUTED)
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
                    status.setTextColor(Ui.RED); status.text = "No connection to the sheet. Try again."
                } catch (e: Exception) {
                    status.setTextColor(Ui.RED); status.text = e.message ?: "Sign-in failed"
                }
                btn.isEnabled = true
            }
        }
        root.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        root.addView(status.apply { setPadding(0, dp(18), 0, 0) })
        root.addView(Ui.text(this, "Only family members the admin has added can sign in.", 14f, Ui.FAINT).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(28), 0, 0)
        })
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(root) })
    }

    // =========================================================================
    // Main shell: header, content, bottom bar, + button
    // =========================================================================

    private fun mainScreen() {
        val frame = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(6))
        }
        header.addView(Ui.text(this, "⚡ ShakeTrack", 24f, bold = true))
        val who = Prefs.name(this).ifEmpty { Prefs.email(this) } + if (Prefs.isAdmin(this)) "  ·  Admin" else ""
        header.addView(Ui.text(this, who, 14f, Ui.MUTED).apply { setPadding(0, dp(2), 0, 0) })
        column.addView(header)

        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(110)) // room for the + button
        }
        scroller = ScrollView(this).apply { addView(body); isVerticalScrollBarEnabled = false }
        column.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Bottom navigation
        navItems.clear()
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Ui.SURFACE)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        listOf("📊" to "Home", "🧾" to "Recent", "⚙️" to "Settings").forEachIndexed { i, (icon, label) ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, dp(6))
                isClickable = true
                setOnClickListener { showTab(i) }
                addView(Ui.text(this@MainActivity, icon, 20f).apply { gravity = Gravity.CENTER })
                addView(Ui.text(this@MainActivity, label, 13f, Ui.MUTED, bold = true).apply { gravity = Gravity.CENTER })
            }
            navItems.add(item)
            nav.addView(item, LinearLayout.LayoutParams(0, dp(62), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
        column.addView(View(this).apply { setBackgroundColor(Ui.LINE) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        column.addView(nav)
        frame.addView(column)

        // Floating + button, bottom-right above the bar
        val fab = Ui.text(this, "+", 32f, 0xFFFFFFFF.toInt(), bold = true).apply {
            this@MainActivity.fab = this
            gravity = Gravity.CENTER
            background = Ui.pressable(this@MainActivity, Ui.ACCENT_STRONG, 32)
            elevation = dp(8).toFloat()
            contentDescription = "Add entry"
            setOnClickListener { startActivity(Intent(this@MainActivity, ExpenseActivity::class.java)) }
        }
        frame.addView(fab, FrameLayout.LayoutParams(dp(64), dp(64)).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            rightMargin = dp(20)
            bottomMargin = dp(92)
        })

        setContentView(frame)
        showTab(tab)
    }

    private fun showTab(i: Int) {
        tab = i
        navItems.forEachIndexed { idx, item ->
            val on = idx == i
            item.background = if (on) Ui.round(this, Ui.ACCENT_SOFT, 16) else null
            (item.getChildAt(1) as TextView).setTextColor(if (on) Ui.ACCENT else Ui.MUTED)
        }
        fab?.visibility = if (i == 2) View.GONE else View.VISIBLE // not needed on Settings
        body.removeAllViews()
        scroller.scrollTo(0, 0)
        when (i) {
            0 -> dashboardTab()
            1 -> recentTab()
            else -> settingsTab()
        }
    }

    private fun loading() = Ui.text(this, "Loading…", 15f, Ui.MUTED).apply {
        gravity = Gravity.CENTER; setPadding(0, dp(40), 0, 0)
    }

    // =========================================================================
    // Home (dashboard)
    // =========================================================================

    private fun dashboardTab() {
        // Month switcher
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(10))
        }
        val monthLabel = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(month.time)
        nav.addView(arrow("‹") { month.add(Calendar.MONTH, -1); showTab(0) })
        nav.addView(Ui.text(this, monthLabel, 19f, bold = true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        nav.addView(arrow("›") { month.add(Calendar.MONTH, 1); showTab(0) })
        body.addView(nav)

        // Family / Only me
        val toggle = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        toggle.addView(Ui.chip(this, "Family", !mineOnly) { mineOnly = false; showTab(0) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
        toggle.addView(Ui.chip(this, "Only me", mineOnly) { mineOnly = true; showTab(0) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        body.addView(toggle)

        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        holder.addView(loading())
        body.addView(holder)

        val reqYear = month.get(Calendar.YEAR)
        val reqMonth = month.get(Calendar.MONTH)
        launch({ showError(holder, it) }) {
            val j = Api.call(this@MainActivity, "summary", JSONObject()
                .put("year", reqYear).put("month", reqMonth).put("scope", if (mineOnly) "me" else "family"))
            if (tab != 0 || !holder.isAttachedToWindow) return@launch
            holder.removeAllViews()
            fillDashboard(holder, j)
        }
    }

    private fun arrow(t: String, onClick: () -> Unit) = Ui.text(this, t, 26f, Ui.ACCENT, bold = true).apply {
        gravity = Gravity.CENTER
        background = Ui.pressable(this@MainActivity, Ui.SURFACE, 14, Ui.LINE)
        layoutParams = LinearLayout.LayoutParams(dp(52), dp(48))
        setOnClickListener { onClick() }
    }

    private fun fillDashboard(holder: LinearLayout, j: JSONObject) {
        val income = j.optDouble("income", 0.0)
        val expense = j.optDouble("expense", 0.0)
        val left = income - expense
        val count = j.optInt("count")

        // Hero: what's left this month
        val hero = Ui.card(this).apply { background = Ui.round(this@MainActivity, Ui.ACCENT_SOFT, 22, Ui.LINE) }
        hero.addView(Ui.label(this, "Left this month", Ui.ACCENT).apply { setPadding(0, 0, 0, dp(4)) })
        hero.addView(Ui.text(this, Ui.money(left), 38f, if (left >= 0) Ui.TEXT else Ui.RED, bold = true))
        val rate = if (income > 0) "${((left / income) * 100).toInt()}% of income saved" else "No income logged yet"
        hero.addView(Ui.text(this, "$rate  ·  $count entries", 14f, Ui.MUTED).apply { setPadding(0, dp(4), 0, 0) })
        holder.addView(hero)

        // Income / Spent
        val kpis = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        kpis.addView(kpi("Money in", income, Ui.GREEN), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(10) })
        kpis.addView(kpi("Spent", expense, Ui.RED), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        holder.addView(kpis)

        if (count == 0) {
            holder.addView(Ui.card(this).apply {
                addView(Ui.text(this@MainActivity, "Nothing logged this month yet.\nShake your phone or tap + to add the first one.", 16f, Ui.MUTED).apply {
                    setLineSpacing(0f, 1.2f)
                })
            })
            return
        }

        holder.addView(breakdown("Where the money went", j.optJSONArray("expense_categories"), expense, Ui.RED))
        val inc = j.optJSONArray("income_categories")
        if (inc != null && inc.length() > 0) holder.addView(breakdown("Money in", inc, income, Ui.GREEN))

        val people = j.optJSONArray("people")
        if (!mineOnly && people != null && people.length() > 1) {
            val c = Ui.card(this)
            c.addView(Ui.label(this, "Spent by person"))
            var max = 1.0
            for (k in 0 until people.length()) max = maxOf(max, people.getJSONObject(k).optDouble("expense"))
            for (k in 0 until people.length()) {
                val p = people.getJSONObject(k)
                c.addView(barRow(p.optString("name"), p.optDouble("expense"), p.optDouble("expense") / max, Ui.RED,
                    "in ${Ui.money(p.optDouble("income"))}"))
            }
            holder.addView(c)
        }
    }

    private fun kpi(label: String, value: Double, color: Int) = Ui.card(this).apply {
        addView(Ui.label(this@MainActivity, label, color).apply { setPadding(0, 0, 0, dp(4)) })
        addView(Ui.text(this@MainActivity, Ui.money(value), 22f, bold = true).apply { maxLines = 1 })
    }

    private fun breakdown(heading: String, arr: JSONArray?, total: Double, color: Int): View {
        val c = Ui.card(this)
        c.addView(Ui.label(this, heading))
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
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(Ui.text(this, label, 16f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(Ui.text(this, note, 13f, Ui.MUTED).apply { setPadding(0, 0, dp(12), 0) })
        top.addView(Ui.text(this, Ui.money(amount), 16f, bold = true))
        row.addView(top)
        val f = frac.coerceIn(0.0, 1.0).toFloat()
        val track = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.round(this@MainActivity, Ui.SURFACE_2, 5)
        }
        track.addView(View(this).apply { background = Ui.round(this@MainActivity, color, 5) },
            LinearLayout.LayoutParams(0, dp(8), maxOf(f, 0.01f)))
        track.addView(View(this), LinearLayout.LayoutParams(0, dp(8), maxOf(1f - f, 0f)))
        row.addView(track, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(8) })
        return row
    }

    // =========================================================================
    // Recent entries: tap = edit, long-press = delete
    // =========================================================================

    private fun recentTab() {
        val pendingNote = Ui.text(this, "", 15f, Ui.AMBER).apply {
            visibility = View.GONE
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = Ui.round(this@MainActivity, Ui.AMBER_SOFT, 14)
        }
        body.addView(pendingNote, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6); bottomMargin = dp(6)
        })
        body.addView(Ui.text(this,
            (if (Prefs.isAdmin(this)) "Latest family entries." else "Your latest entries.") + "  Tap to edit · hold to delete",
            14f, Ui.MUTED).apply { setPadding(dp(4), dp(6), 0, dp(10)) })

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(loading())
        body.addView(list)

        launch({ showError(list, it) }) {
            val pending = withContext(Dispatchers.IO) { UploadWorker.pendingCount(this@MainActivity) }
            if (pending > 0) {
                pendingNote.text = "⏳  $pending saved on this phone, waiting for internet to upload"
                pendingNote.visibility = View.VISIBLE
            }
            val j = Api.call(this@MainActivity, "recent")
            if (tab != 1 || !list.isAttachedToWindow) return@launch
            list.removeAllViews()
            val arr = j.optJSONArray("entries") ?: JSONArray()
            if (arr.length() == 0) {
                list.addView(Ui.card(this@MainActivity, 0).apply { addView(Ui.text(this@MainActivity, "No entries yet.", 16f, Ui.MUTED)) })
                return@launch
            }
            val fmt = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault())
            var lastDay = ""
            val dayFmt = SimpleDateFormat("EEEE, d MMM", Locale.getDefault())
            for (k in 0 until arr.length()) {
                val e = arr.getJSONObject(k)
                val day = dayFmt.format(Date(e.optLong("ts")))
                if (day != lastDay) {
                    list.addView(Ui.label(this@MainActivity, day).apply { setPadding(dp(4), dp(14), 0, dp(8)) })
                    lastDay = day
                }
                list.addView(entryRow(e, fmt))
            }
        }
    }

    private fun entryRow(e: JSONObject, fmt: SimpleDateFormat): View {
        val income = e.optString("type") == "Income"
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(68)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = Ui.pressable(this@MainActivity, Ui.SURFACE, 16, Ui.LINE)
            isClickable = true
            setOnClickListener { editDialog(e) }
            setOnLongClickListener { Ui.vibrate(this@MainActivity, 30); confirmDelete(e, null); true }
        }
        // Colored dot: green = money in, red = spent
        row.addView(View(this).apply { background = Ui.round(this@MainActivity, if (income) Ui.GREEN else Ui.RED, 6) },
            LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(14) })
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(Ui.text(this, e.optString("category"), 17f, bold = true))
        val meta = mutableListOf(fmt.format(Date(e.optLong("ts"))).substringAfter(", "), e.optString("payment"))
        if (Prefs.isAdmin(this) && e.optString("email") != Prefs.email(this)) meta.add(e.optString("user"))
        left.addView(Ui.text(this, meta.filter { it.isNotEmpty() }.joinToString("  ·  "), 14f, Ui.MUTED).apply { setPadding(0, dp(2), 0, 0) })
        row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val amt = e.optDouble("amount")
        row.addView(Ui.text(this, if (income) Ui.money(amt, sign = true) else Ui.money(-amt), 18f, if (income) Ui.GREEN else Ui.RED, bold = true))
        return row.also {
            it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        }
    }

    private fun confirmDelete(e: JSONObject, parent: AlertDialog?) {
        AlertDialog.Builder(this)
            .setTitle("Delete this entry?")
            .setMessage("${e.optString("category")}  ·  ${Ui.money(e.optDouble("amount"))}\nIt will be removed from the sheet. This can't be undone.")
            .setPositiveButton("Delete") { _, _ ->
                launch {
                    Api.call(this@MainActivity, "delete", JSONObject().put("id", e.optString("id")))
                    parent?.dismiss()
                    toast("Deleted")
                    showTab(1)
                }
            }
            .setNegativeButton("Keep", null)
            .show()
    }

    private fun editDialog(e: JSONObject) {
        val expCats = Prefs.expenseCategories(this)
        val incCats = Prefs.incomeCategories(this)
        val curCat = e.optString("category")
        val curIncome = e.optString("type") == "Income"
        val exp = if (!curIncome && curCat !in expCats) expCats + curCat else expCats
        val inc = if (curIncome && curCat !in incCats) incCats + curCat else incCats
        val labels = exp + inc.map { "+ $it" }
        val pays = Prefs.payments(this).let { if (e.optString("payment") in it) it else it + e.optString("payment") }

        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0) }
        form.addView(Ui.label(this, "Amount (₹)").apply { setPadding(0, dp(8), 0, dp(6)) })
        val amount = Ui.input(this, "0", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, 20f).apply {
            val a = e.optDouble("amount")
            setText(if (a % 1.0 == 0.0) a.toLong().toString() else a.toString())
        }
        form.addView(amount)
        form.addView(Ui.label(this, "Category").apply { setPadding(0, dp(16), 0, dp(6)) })
        val cat = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, labels)
            setSelection(if (curIncome) exp.size + inc.indexOf(curCat) else exp.indexOf(curCat))
            minimumHeight = dp(50)
        }
        form.addView(cat)
        form.addView(Ui.label(this, "Paid with").apply { setPadding(0, dp(16), 0, dp(6)) })
        val pay = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, pays)
            setSelection(pays.indexOf(e.optString("payment")).coerceAtLeast(0))
            minimumHeight = dp(50)
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
                if (value == null || value <= 0) { amount.error = "Enter an amount"; return@setOnClickListener }
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
                setTextColor(Ui.RED)
                setOnClickListener { confirmDelete(e, dlg) }
            }
        }
        dlg.show()
    }

    // =========================================================================
    // Settings
    // =========================================================================

    private fun settingsTab() {
        // Shake trigger
        val shake = Ui.card(this, 6)
        shake.addView(Ui.label(this, "Shake to add"))
        val sw = Switch(this).apply {
            text = "Shake the phone to open the add screen"
            textSize = 16f
            setTextColor(Ui.TEXT)
            minHeight = dp(48)
            isChecked = Prefs.shakeEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                Prefs.setShakeEnabled(this@MainActivity, on)
                if (on) ShakeService.start(this@MainActivity) else ShakeService.stop(this@MainActivity)
            }
        }
        shake.addView(sw)
        shake.addView(Ui.text(this, "Give the phone 3 quick, firm shakes while the screen is on.", 14f, Ui.MUTED).apply {
            setPadding(0, dp(4), 0, 0)
        })
        body.addView(shake)

        // Permissions
        val perms = Ui.card(this)
        perms.addView(Ui.label(this, "Permissions"))
        val overlay = Settings.canDrawOverlays(this)
        perms.addView(permRow(if (overlay) "Display over other apps" else "Allow display over other apps", "Needed for shake to open the add screen", overlay) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        if (Build.VERSION.SDK_INT >= 33) {
            val notif = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            perms.addView(permRow(if (notif) "Notifications" else "Allow notifications", "Shows the 'Add now' shortcut", notif) {
                askNotificationPermission()
            })
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val exempt = pm.isIgnoringBatteryOptimizations(packageName)
        perms.addView(permRow(if (exempt) "Battery optimization off" else "Turn off battery optimization", "Keeps shake working in the background", exempt) {
            if (!exempt) startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:$packageName")))
        })
        body.addView(perms)

        if (Prefs.isAdmin(this)) body.addView(familyCard())

        // Account
        val acct = Ui.card(this)
        acct.addView(Ui.label(this, "Account"))
        acct.addView(Ui.text(this, Prefs.name(this), 18f, bold = true))
        acct.addView(Ui.text(this, Prefs.email(this) + if (Prefs.isAdmin(this)) "  ·  Admin" else "  ·  Family member", 14f, Ui.MUTED).apply {
            setPadding(0, dp(2), 0, dp(14))
        })
        acct.addView(Ui.button(this, "Sign out", primary = false) {
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

    /** One permission line: status dot, title, hint; tappable when not yet granted. */
    private fun permRow(title: String, hint: String, done: Boolean, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(60)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = if (done) Ui.round(this@MainActivity, Ui.GREEN_SOFT, 14, Ui.GREEN_LINE)
            else Ui.pressable(this@MainActivity, Ui.SURFACE_2, 14, Ui.LINE)
            if (!done) setOnClickListener { onClick() }
        }
        row.addView(Ui.text(this, if (done) "✓" else "!", 18f, if (done) Ui.GREEN else Ui.AMBER, bold = true).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, 0, 0) }
        texts.addView(Ui.text(this, title, 16f, bold = true))
        texts.addView(Ui.text(this, hint, 13f, Ui.MUTED))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (!done) row.addView(Ui.text(this, "›", 22f, Ui.MUTED, bold = true))
        return row.also {
            it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        }
    }

    private fun familyCard(): View {
        val c = Ui.card(this)
        c.addView(Ui.label(this, "Family members"))
        c.addView(Ui.text(this, "Only these Google accounts can sign in. Everyone sees the family totals; members can edit only their own entries.", 14f, Ui.MUTED).apply {
            setPadding(0, 0, 0, dp(10))
        })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(Ui.text(this, "Loading…", 15f, Ui.MUTED))
        c.addView(list)

        val addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        val email = Ui.input(this, "name@gmail.com", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        addRow.addView(email, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
        addRow.addView(Ui.button(this, "Add") {
            val v = email.text.toString().trim()
            if (v.isEmpty()) return@button
            launch {
                val j = Api.call(this@MainActivity, "member_add", JSONObject().put("email", v))
                email.setText("")
                fillMembers(list, j)
                toast("✓ $v can now sign in")
            }
        }.apply { setPadding(dp(22), 0, dp(22), 0) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(50)))
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
                minimumHeight = dp(48)
            }
            row.addView(Ui.text(this, m, 15f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (m == admin) {
                row.addView(Ui.text(this, "Admin", 13f, Ui.ACCENT, bold = true))
            } else {
                row.addView(Ui.text(this, "Remove", 14f, Ui.RED, bold = true).apply {
                    setPadding(dp(12), dp(10), dp(4), dp(10))
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

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
