// ============================================================================
// ShakeTrack: Family Expense Tracker — Google Sheets backend
// ----------------------------------------------------------------------------
// Auth: family members sign in with Google in the app. The app sends the Google
// ID token once ("login"); we verify it with Google, check the email is on the
// family list (Lists!D), and hand back a session token used for every later call.
// The admin is whoever owns this Apps Script project (the account it runs as).
// ============================================================================

// OAuth "Web application" client ID from Google Cloud (same project as the
// Android client). Not a secret. Must match google_web_client_id in the app.
const WEB_CLIENT_ID = "658489307438-q4madllvqdr3qria2rp5hm0gb1ve1259.apps.googleusercontent.com";

// The spreadsheet this backend writes to (works whether or not the script is bound to it).
const SHEET_ID = "1xPl_2t9DiJsruaWz-eyDnZmF8Xvqwm0Ot8eJjImuBg4";
function book() {
  return SpreadsheetApp.getActive() || SpreadsheetApp.openById(SHEET_ID);
}

const N = 5000; // Maximum data rows evaluated by the sheet dashboards
const RECENT_LIMIT = 30;
const LISTS_VERSION = "3"; // bump to rewrite the Lists tab with the defaults below

// ----------------------------------------------------------------------------
// Categories (defaults written to the Lists tab; edit them there afterwards)
// ----------------------------------------------------------------------------
const EXPENSE_CATEGORIES = ["Food", "Fuel", "Transport", "Tea & snacks", "Miscellaneous", "Things bought"];
const INCOME_CATEGORIES = ["Salary", "Passive income", "Borrowed"];
const SAVINGS_CATEGORIES = ["Gold", "Mutual funds / Investments", "Bank / Cash savings"];
const FIXED_CATEGORIES = [
  "Rent – Bangalore", "Rent – Home (native)", "Electricity (EB) bill", "Gas",
  "Medicine", "Maid salary", "Mobile recharge", "Loan repaid"
];
// Credit cards: payment options under "Card", and the card-bill tags under Fixed expenses
const CARDS = ["ICICI card", "Jupiter card", "OneCard", "Amazon Pay Later"];
const PAYMENT_METHODS = ["GPay", "Cash", "Card"];

// Tags with special meaning
const BORROW_TAG = "Borrowed";   // money in, but it's debt, not income
const REPAY_TAG = "Loan repaid"; // money out, but it pays back debt, not spending

// Lists tab columns
const L = { EXP: "A", INC: "B", PAY: "C", EMAILS: "D", CARDS: "E", SAV: "F", FIXED: "G" };

/*
 * Entry types (Expenses!B):
 *   Income     salary, passive income          -> counts as income
 *   Borrowed   the "Borrowed" tag               -> debt (adds to "you owe"), not income
 *   Expense    everyday spending                -> spending
 *   Fixed      rent, bills, maid, recharge...   -> spending
 *   Savings    gold, mutual funds, bank         -> saved, not spending
 *   Transfer   card bill paid (tag = card name) -> not spending: the card purchases already were
 *   Repayment  the "Loan repaid" tag            -> reduces "you owe", not spending
 */
const TYPES = ["Income", "Borrowed", "Expense", "Fixed", "Savings", "Transfer", "Repayment"];

// Expenses sheet columns (1-based)
const COL = { DATE: 1, TYPE: 2, CAT: 3, AMT: 4, PAY: 5, USER: 6, NET: 7, ID: 8, EMAIL: 9, NOTE: 10 };
const HEADERS = ["Date", "Type", "Category", "Amount", "Payment", "User", "Net Amount", "ID", "Email", "Note"];

function json(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}

class ApiError extends Error {}

// ----------------------------------------------------------------------------
// HTTP entry points
// ----------------------------------------------------------------------------
function doGet() {
  return json({ ok: true, app: "ShakeTrack" });
}

function doPost(e) {
  try {
    const d = JSON.parse((e && e.postData && e.postData.contents) || "{}");
    if (d.action === "login") return json(login(d));

    const me = sessionUser(d.token);
    switch (d.action) {
      case "lists":         return json(Object.assign({ ok: true, me: me }, readLists()));
      case "add":           return json(withLock(() => addEntry(me, d)));
      case "recent":        return json(recent(me));
      case "update":        return json(withLock(() => updateEntry(me, d)));
      case "delete":        return json(withLock(() => deleteEntry(me, d)));
      case "summary":       return json(summary(me, d));
      case "members":       return json(listMembers(me));
      case "member_add":    return json(withLock(() => addMember(me, d)));
      case "member_remove": return json(withLock(() => removeMember(me, d)));
      case "logout":        PropertiesService.getScriptProperties().deleteProperty("s:" + d.token); return json({ ok: true });
      default: throw new ApiError("Unknown action");
    }
  } catch (err) {
    if (err instanceof ApiError) return json({ ok: false, error: err.message, auth: !!err.auth });
    return json({ ok: false, error: "Server error: " + err.message });
  }
}

function withLock(fn) {
  const lock = LockService.getScriptLock();
  lock.waitLock(20000);
  try { return fn(); } finally { lock.releaseLock(); }
}

// ----------------------------------------------------------------------------
// Auth & family membership
// ----------------------------------------------------------------------------
function adminEmail() {
  return String(Session.getEffectiveUser().getEmail() || "").toLowerCase();
}

function memberEmails() {
  const l = listsSheet();
  const emails = l.getRange("D2:D").getValues()
    .map(r => String(r[0]).trim().toLowerCase())
    .filter(v => v.indexOf("@") > 0);
  const admin = adminEmail();
  if (admin && emails.indexOf(admin) < 0) emails.unshift(admin);
  return emails;
}

function authError(msg) {
  const e = new ApiError(msg);
  e.auth = true;
  return e;
}

function login(d) {
  if (!d.idToken) throw new ApiError("Missing Google token");
  const res = UrlFetchApp.fetch(
    "https://oauth2.googleapis.com/tokeninfo?id_token=" + encodeURIComponent(d.idToken),
    { muteHttpExceptions: true }
  );
  if (res.getResponseCode() !== 200) throw authError("Google sign-in could not be verified");
  const t = JSON.parse(res.getContentText());
  const issOk = t.iss === "accounts.google.com" || t.iss === "https://accounts.google.com";
  const verified = t.email_verified === true || t.email_verified === "true";
  if (t.aud !== WEB_CLIENT_ID || !issOk || !verified) throw authError("Google sign-in could not be verified");

  const email = String(t.email).toLowerCase();
  if (memberEmails().indexOf(email) < 0) {
    throw authError(email + " is not on the family list. Ask the admin to add you.");
  }
  const name = String(t.name || t.given_name || email.split("@")[0]);
  const token = Utilities.getUuid().replace(/-/g, "") + Utilities.getUuid().replace(/-/g, "");
  PropertiesService.getScriptProperties().setProperty("s:" + token, JSON.stringify({ e: email, n: name, t: Date.now() }));

  return Object.assign({ ok: true, token: token, me: userInfo(email, name) }, readLists());
}

function userInfo(email, name) {
  return { email: email, name: name, admin: email === adminEmail() };
}

function sessionUser(token) {
  if (!token) throw authError("Please sign in again");
  const raw = PropertiesService.getScriptProperties().getProperty("s:" + token);
  if (!raw) throw authError("Please sign in again");
  const s = JSON.parse(raw);
  if (memberEmails().indexOf(s.e) < 0) {
    PropertiesService.getScriptProperties().deleteProperty("s:" + token);
    throw authError("You were removed from the family list");
  }
  return userInfo(s.e, s.n);
}

function requireAdmin(me) {
  if (!me.admin) throw new ApiError("Only the admin can do that");
}

function listMembers(me) {
  requireAdmin(me);
  return { ok: true, admin: adminEmail(), members: memberEmails() };
}

function addMember(me, d) {
  requireAdmin(me);
  const email = String(d.email || "").trim().toLowerCase();
  if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) throw new ApiError("That doesn't look like an email address");
  const l = listsSheet();
  const existing = l.getRange("D2:D").getValues().map(r => String(r[0]).trim().toLowerCase());
  if (existing.indexOf(email) < 0 && email !== adminEmail()) {
    let row = existing.findIndex(v => v === "") ;
    row = row < 0 ? existing.length + 2 : row + 2;
    l.getRange(row, 4).setValue(email);
  }
  return listMembers(me);
}

function removeMember(me, d) {
  requireAdmin(me);
  const email = String(d.email || "").trim().toLowerCase();
  if (email === adminEmail()) throw new ApiError("The admin can't be removed");
  const l = listsSheet();
  const vals = l.getRange("D2:D").getValues();
  for (let i = vals.length - 1; i >= 0; i--) {
    if (String(vals[i][0]).trim().toLowerCase() === email) l.getRange(i + 2, 4).setValue("");
  }
  // Compact the column so there are no gaps
  const left = memberEmailsRaw(l);
  l.getRange("D2:D").clearContent();
  if (left.length) l.getRange(2, 4, left.length, 1).setValues(left.map(v => [v]));
  return listMembers(me);
}

function memberEmailsRaw(l) {
  return l.getRange("D2:D").getValues().map(r => String(r[0]).trim()).filter(String);
}

// ----------------------------------------------------------------------------
// Lists (categories / payment methods)
// ----------------------------------------------------------------------------
function listsSheet() {
  const ss = book();
  return ss.getSheetByName("Lists") || setupLists();
}

function readLists() {
  const l = listsSheet();
  const col = c => l.getRange(c + "2:" + c).getValues().map(r => String(r[0]).trim()).filter(String);
  const or = (v, d) => v.length ? v : d;
  const cards = or(col(L.CARDS), CARDS);
  return {
    expense_categories: or(col(L.EXP), EXPENSE_CATEGORIES),
    income_categories: or(col(L.INC), INCOME_CATEGORIES),
    savings_categories: or(col(L.SAV), SAVINGS_CATEGORIES),
    fixed_categories: or(col(L.FIXED), FIXED_CATEGORIES),
    payments: or(col(L.PAY), PAYMENT_METHODS),
    cards: cards,
    transfer_categories: cards // card-bill tags (older app versions read this name)
  };
}

// ----------------------------------------------------------------------------
// Entries
// ----------------------------------------------------------------------------
function expensesSheet() {
  const ss = book();
  let sh = ss.getSheetByName("Expenses");
  if (!sh) {
    sh = ss.insertSheet("Expenses");
  }
  if (sh.getRange(1, COL.NOTE).getValue() !== "Note") {
    sh.getRange(1, 1, 1, HEADERS.length).setValues([HEADERS]).setFontWeight("bold").setBackground("#F1F5F9");
  }
  return sh;
}

function lastDataRow(sh) {
  const colA = sh.getRange("A:A").getValues();
  let n = colA.length;
  while (n > 1 && (colA[n - 1][0] === "" || colA[n - 1][0] === null)) n--;
  return n; // 1 when only the header exists
}

/** Reads all rows, assigning IDs to legacy rows that don't have one yet. */
function readRows(sh) {
  const last = lastDataRow(sh);
  if (last < 2) return [];
  const vals = sh.getRange(2, 1, last - 1, COL.NOTE).getValues();
  let fixed = false;
  vals.forEach(v => { if (!v[COL.ID - 1]) { v[COL.ID - 1] = Utilities.getUuid(); fixed = true; } });
  if (fixed) sh.getRange(2, COL.ID, vals.length, 1).setValues(vals.map(v => [v[COL.ID - 1]]));
  return vals.map((v, i) => ({
    row: i + 2,
    date: v[COL.DATE - 1] instanceof Date ? v[COL.DATE - 1] : new Date(v[COL.DATE - 1]),
    type: TYPES.indexOf(String(v[COL.TYPE - 1])) >= 0 ? String(v[COL.TYPE - 1]) : "Expense",
    category: String(v[COL.CAT - 1]),
    amount: Math.abs(Number(v[COL.AMT - 1]) || 0),
    payment: String(v[COL.PAY - 1]),
    user: String(v[COL.USER - 1]),
    id: String(v[COL.ID - 1]),
    email: String(v[COL.EMAIL - 1]).toLowerCase(),
    note: String(v[COL.NOTE - 1] || "")
  }));
}

function toApi(r) {
  return {
    id: r.id, ts: r.date.getTime(), type: r.type, category: r.category,
    amount: r.amount, payment: r.payment, user: r.user, email: r.email, note: r.note
  };
}

/** The tag decides Borrowed / Repayment; otherwise the section the app saved it from. */
function entryType(type, category) {
  const c = String(category).trim().toLowerCase();
  if (c === BORROW_TAG.toLowerCase()) return "Borrowed";
  if (c === REPAY_TAG.toLowerCase()) return "Repayment";
  return TYPES.indexOf(type) >= 0 ? type : "Expense";
}

function netAmount(type, amount) {
  if (type === "Income" || type === "Borrowed") return amount;
  if (type === "Transfer") return 0;
  return -amount;
}

function cleanEntry(d) {
  const amount = Math.abs(Number(d.amount) || 0);
  if (!(amount > 0)) throw new ApiError("Amount must be more than 0");
  const category = String(d.category || "").trim() || "Miscellaneous";
  const type = entryType(String(d.type || ""), category);
  const payment = String(d.payment || "").trim() || "GPay";
  const note = String(d.note || "").trim().slice(0, 200);
  return { type: type, amount: amount, category: category, payment: payment, note: note };
}

function writeRow(sh, row, date, e, me, id) {
  sh.getRange(row, 1, 1, COL.NOTE).setValues([[
    date, e.type, e.category, e.amount, e.payment, me.name,
    netAmount(e.type, e.amount), id, me.email, e.note
  ]]);
  sh.getRange(row, COL.DATE).setNumberFormat("dd-mmm-yyyy hh:mm");
  sh.getRange(row, COL.AMT).setNumberFormat('"₹"#,##0.00');
  sh.getRange(row, COL.NET).setNumberFormat('"₹"#,##0.00');
}

function addEntry(me, d) {
  const sh = expensesSheet();
  const id = String(d.id || Utilities.getUuid());
  // Idempotent: a retried upload with the same id is not added twice
  const ids = readRows(sh).map(r => r.id);
  if (ids.indexOf(id) >= 0) return { ok: true, id: id, duplicate: true };
  const e = cleanEntry(d);
  const date = d.ts ? new Date(Number(d.ts)) : new Date();
  writeRow(sh, Math.max(lastDataRow(sh) + 1, 2), date, e, me, id);
  return { ok: true, id: id };
}

function findOwned(me, id) {
  const sh = expensesSheet();
  const r = readRows(sh).filter(x => x.id === String(id))[0];
  if (!r) throw new ApiError("Entry not found (it may have been deleted)");
  if (!me.admin && r.email !== me.email) throw new ApiError("You can only change your own entries");
  return { sh: sh, r: r };
}

function updateEntry(me, d) {
  const f = findOwned(me, d.id);
  const e = cleanEntry(d);
  // Keep the original author on the row even when the admin edits it
  const owner = { name: f.r.user, email: f.r.email };
  writeRow(f.sh, f.r.row, f.r.date, e, owner, f.r.id);
  return { ok: true };
}

function deleteEntry(me, d) {
  const f = findOwned(me, d.id);
  f.sh.deleteRow(f.r.row);
  return { ok: true };
}

function recent(me) {
  const rows = readRows(expensesSheet())
    .filter(r => me.admin || r.email === me.email)
    .sort((a, b) => b.date - a.date)
    .slice(0, RECENT_LIMIT);
  return { ok: true, entries: rows.map(toApi) };
}

/** Month totals for the app's Home tab, plus running debt and per-card balances. */
function summary(me, d) {
  const now = new Date();
  const year = Number(d.year) || now.getFullYear();
  const month = d.month !== undefined ? Number(d.month) : now.getMonth(); // 0-11
  const start = new Date(year, month, 1), end = new Date(year, month + 1, 1);
  const mine = d.scope === "me";
  const lists = readLists();

  const all = readRows(expensesSheet()).filter(r => !mine || r.email === me.email);
  const inMonth = r => r.date >= start && r.date < end;

  const sum = {}; TYPES.forEach(t => sum[t] = 0);
  const cats = { Income: {}, Expense: {}, Fixed: {}, Savings: {} };
  const bucket = { Income: "Income", Borrowed: "Income", Expense: "Expense", Fixed: "Fixed", Repayment: "Fixed", Savings: "Savings" };
  const people = {};
  const cards = {};
  lists.cards.forEach(c => cards[c] = { name: c, spent: 0, paid: 0, due: 0 });
  let owe = 0, count = 0;

  all.forEach(r => {
    // Running balances (all months up to the end of the selected one)
    if (r.date < end) {
      if (r.type === "Borrowed") owe += r.amount;
      if (r.type === "Repayment") owe -= r.amount;
      if ((r.type === "Expense" || r.type === "Fixed") && cards[r.payment]) cards[r.payment].due += r.amount;
      if (r.type === "Transfer" && cards[r.category]) cards[r.category].due -= r.amount;
    }
    if (!inMonth(r)) return;
    count++;
    sum[r.type] += r.amount;
    const b = bucket[r.type];
    if (b) cats[b][r.category] = (cats[b][r.category] || 0) + r.amount;
    if ((r.type === "Expense" || r.type === "Fixed") && cards[r.payment]) cards[r.payment].spent += r.amount;
    if (r.type === "Transfer" && cards[r.category]) cards[r.category].paid += r.amount;
    const who = r.user || r.email || "Unknown";
    people[who] = people[who] || { name: who, income: 0, expense: 0 };
    if (r.type === "Income") people[who].income += r.amount;
    if (r.type === "Expense" || r.type === "Fixed") people[who].expense += r.amount;
  });

  const r2 = v => Math.round(v * 100) / 100;
  const sorted = o => Object.keys(o).map(k => ({ name: k, amount: r2(o[k]) })).sort((a, b) => b.amount - a.amount);
  return {
    ok: true, year: year, month: month, scope: mine ? "me" : "family", count: count,
    income: r2(sum.Income),
    expense: r2(sum.Expense + sum.Fixed), // total spending
    everyday: r2(sum.Expense), fixed: r2(sum.Fixed),
    savings: r2(sum.Savings),
    borrowed: r2(sum.Borrowed), repaid: r2(sum.Repayment), owe: r2(owe),
    card_paid: r2(sum.Transfer),
    cards: Object.keys(cards).map(k => cards[k])
      .filter(c => c.spent || c.paid || Math.abs(c.due) > 0.005)
      .map(c => ({ name: c.name, spent: r2(c.spent), paid: r2(c.paid), due: r2(c.due) })),
    expense_categories: sorted(cats.Expense),
    fixed_categories: sorted(cats.Fixed),
    savings_categories: sorted(cats.Savings),
    income_categories: sorted(cats.Income),
    people: Object.keys(people).map(k => people[k]).sort((a, b) => b.expense - a.expense)
  };
}

// ----------------------------------------------------------------------------
// SETUP: run once from the Apps Script editor (safe to re-run; keeps your data)
// ----------------------------------------------------------------------------
function setup() {
  setupLists();
  buildDashboard();
  buildAnnualDashboard();
  // Remove the blank default tab of a brand-new spreadsheet
  const ss = book();
  const blank = ss.getSheetByName("Sheet1");
  if (blank && blank.getLastRow() === 0 && ss.getSheets().length > 1) ss.deleteSheet(blank);
  const dash = ss.getSheetByName("Dashboard");
  if (dash) { ss.setActiveSheet(dash); ss.moveActiveSheet(1); }
}

function setupLists() {
  const ss = book();
  let lists = ss.getSheetByName("Lists");
  if (!lists) lists = ss.insertSheet("Lists");

  const props = PropertiesService.getScriptProperties();
  const fresh = props.getProperty("listsVersion") !== LISTS_VERSION;
  const write = (c, values) => {
    const range = lists.getRange(c + "2:" + c);
    const existing = range.getValues().map(r => String(r[0]).trim()).filter(String);
    if (fresh || !existing.length) {
      range.clearContent();
      lists.getRange(2, range.getColumn(), values.length, 1).setValues(values.map(v => [v]));
    }
  };
  lists.getRange("A1:G1").setValues([["Expense", "Income", "Payment methods", "Family emails", "Cards", "Savings", "Fixed expenses"]])
    .setFontWeight("bold").setBackground("#E2E8F0");
  write(L.EXP, EXPENSE_CATEGORIES);
  write(L.INC, INCOME_CATEGORIES);
  write(L.PAY, PAYMENT_METHODS);
  write(L.CARDS, CARDS);
  write(L.SAV, SAVINGS_CATEGORIES);
  write(L.FIXED, FIXED_CATEGORIES);
  props.setProperty("listsVersion", LISTS_VERSION);

  // Column D keeps only family email addresses
  const emails = memberEmailsRaw(lists).filter(v => v.indexOf("@") > 0);
  lists.getRange("D2:D").clearContent();
  if (emails.length) lists.getRange(2, 4, emails.length, 1).setValues(emails.map(v => [v]));
  lists.getRange("I1").setValue("Admin (script owner): " + adminEmail()).setFontColor("#64748B");
  lists.getRange("I2").setValue("Tags with special meaning: \"" + BORROW_TAG + "\" (Income) is debt; \"" + REPAY_TAG +
    "\" (Fixed) pays it back. Cards are payment options and also the card-bill tags.").setFontColor("#64748B");
  for (let c = 1; c <= 7; c++) lists.setColumnWidth(c, 190);

  const log = expensesSheet();
  log.setColumnWidth(1, 150); log.setColumnWidth(2, 100); log.setColumnWidth(3, 180);
  log.setColumnWidth(4, 110); log.setColumnWidth(5, 140); log.setColumnWidth(6, 110); log.setColumnWidth(7, 120);
  log.setColumnWidth(10, 220);
  log.getRange("C2:C" + N).clearDataValidations();
  log.getRange("E2:E" + N).clearDataValidations();
  log.getRange("F2:F" + N).clearDataValidations();
  return lists;
}

// ----------------------------------------------------------------------------
// Formula helpers shared by both dashboards
// ----------------------------------------------------------------------------
const XR = {
  date: `Expenses!$A$2:$A$${N}`, type: `Expenses!$B$2:$B$${N}`, cat: `Expenses!$C$2:$C$${N}`,
  amt: `Expenses!$D$2:$D$${N}`, pay: `Expenses!$E$2:$E$${N}`
};
/** SUMIFS over one or more entry types, with extra "criteria range, criterion" pairs. */
function sumTypes(types, extra) {
  return types.map(t => `IFERROR(SUMIFS(${XR.amt},${XR.type},"${t}"${extra ? "," + extra : ""}),0)`).join("+");
}

function colLetter(n) {
  let s = "";
  while (n > 0) { const m = (n - 1) % 26; s = String.fromCharCode(65 + m) + s; n = Math.floor((n - 1) / 26); }
  return s;
}

// ----------------------------------------------------------------------------
// MONTHLY DASHBOARD
// ----------------------------------------------------------------------------
function buildDashboard() {
  const ss = book();
  let d = ss.getSheetByName("Dashboard");
  if (!d) d = ss.insertSheet("Dashboard");

  d.getCharts().forEach(c => d.removeChart(c));
  d.clear();
  d.getRange(1, 1, d.getMaxRows(), d.getMaxColumns()).breakApart();
  d.setHiddenGridlines(true);

  const widths = [40, 190, 70, 110, 100, 30, 340, 30, 190, 70, 110, 100, 30, 340];
  widths.forEach((w, i) => d.setColumnWidth(i + 1, w));

  const INR = '"₹"#,##0.00';
  const NAVY = "#1E293B", BLUE = "#2563EB", RED = "#DC2626", GREEN = "#16A34A", VIOLET = "#7C3AED", AMBER = "#B45309";
  const BORDER_COL = "#E2E8F0";
  const month = `${XR.date},">="&$C$4,${XR.date},"<"&EDATE($C$4,1)`;

  // Header & month picker
  d.getRange("B2:N2").merge().setBackground("#0F172A");
  d.getRange("B2").setValue("SHAKETRACK FINANCIAL DASHBOARD").setFontSize(16).setFontWeight("bold").setFontColor("#FFFFFF")
    .setVerticalAlignment("middle");
  d.getRange("B4").setValue("Selected month:").setFontWeight("bold").setFontColor("#334155");
  d.getRange("C4:D4").merge();
  d.getRange("C4").setValue(new Date(new Date().getFullYear(), new Date().getMonth(), 1))
    .setNumberFormat("mmmm yyyy").setFontWeight("bold").setBackground("#FEF3C7").setFontColor("#92400E")
    .setHorizontalAlignment("center");
  d.getRange("E4").setValue("Year:").setFontWeight("bold").setFontColor("#334155").setHorizontalAlignment("right");
  d.getRange("F4").setFormula("=YEAR(C4)").setFontWeight("bold").setFontColor(NAVY).setHorizontalAlignment("center");

  // KPI cards: Income | Spent | Saved | Left
  const kpi = (range, title, color, light, formula, sub) => {
    const top = d.getRange(range);
    const c0 = top.getColumn(), w = top.getNumColumns();
    d.getRange(6, c0, 1, w).merge().setValue(title).setFontWeight("bold").setFontSize(10).setFontColor(color).setBackground(light);
    d.getRange(7, c0, 2, w).merge().setFormula(formula).setFontSize(20).setFontWeight("bold").setFontColor(color)
      .setNumberFormat(INR).setHorizontalAlignment("center").setVerticalAlignment("middle");
    d.getRange(9, c0, 1, w).merge().setFormula(sub).setFontSize(9).setFontColor("#64748B").setHorizontalAlignment("center")
      .setBackground("#F8FAFC");
    d.getRange(6, c0, 4, w).setBorder(true, true, true, true, false, false, BORDER_COL, SpreadsheetApp.BorderStyle.SOLID);
  };
  const owe = `(${sumTypes(["Borrowed"], `${XR.date},"<"&EDATE($C$4,1)`)})-(${sumTypes(["Repayment"], `${XR.date},"<"&EDATE($C$4,1)`)})`;
  kpi("B6:D6", "INCOME", BLUE, "#EFF6FF", `=${sumTypes(["Income"], month)}`,
    `="Borrowed this month: "&TEXT(${sumTypes(["Borrowed"], month)},"#,##0")`);
  kpi("E6:G6", "SPENT (EVERYDAY + FIXED)", RED, "#FEF2F2", `=${sumTypes(["Expense", "Fixed"], month)}`,
    `=IF(B7=0,"",TEXT(E7/B7,"0%")&" of income")`);
  kpi("I6:K6", "SAVED", VIOLET, "#F5F3FF", `=${sumTypes(["Savings"], month)}`,
    `=IF(B7=0,"",TEXT(I7/B7,"0%")&" of income")`);
  kpi("L6:N6", "LEFT", GREEN, "#F0FDF4", `=B7-E7-I7`, `="You owe (all time): "&TEXT(${owe},"#,##0")`);
  d.getRange("L7").setNumberFormat('"₹"#,##0.00;[Red]-"₹"#,##0.00'); // red when overspent

  d.getRange("B10:N10").merge().setFormula(
    `="Card bills paid this month: "&TEXT(${sumTypes(["Transfer"], month)},"#,##0")&"   ·   Loan repaid: "&TEXT(${sumTypes(["Repayment"], month)},"#,##0")&"   (neither is counted as spending)"`
  ).setFontColor("#475569").setFontSize(9).setFontStyle("italic");

  // Category tables follow the Lists tab live: edit tags there and these update.
  const t1 = breakdownTable(d, { row: 12, col: 2, list: L.EXP, types: ["Expense"], slots: 15,
    title: "EVERYDAY EXPENSES", dark: "#991B1B", light: "#FEE2E2", bar: RED, month: month });
  breakdownTable(d, { row: 12, col: 9, list: L.INC, types: ["Income", "Borrowed"], slots: 8,
    title: "INCOME (BORROWED SHOWN SEPARATELY)", dark: "#1E40AF", light: "#DBEAFE", bar: BLUE, month: month });
  const r2 = t1 + 3;
  const t2 = breakdownTable(d, { row: r2, col: 2, list: L.FIXED, types: ["Fixed", "Repayment"], slots: 15,
    title: "FIXED EXPENSES", dark: "#9A3412", light: "#FFEDD5", bar: AMBER, month: month });
  breakdownTable(d, { row: r2, col: 9, list: L.SAV, types: ["Savings"], slots: 8,
    title: "SAVINGS", dark: "#5B21B6", light: "#EDE9FE", bar: VIOLET, month: month });
  const r3 = t2 + 3;
  breakdownTable(d, { row: r3, col: 2, list: L.CARDS, types: ["Transfer"], slots: 8,
    title: "CARD BILLS PAID (NOT SPENDING)", dark: "#1E3A8A", light: "#DBEAFE", bar: BLUE, month: month });
  breakdownTable(d, { row: r3, col: 9, list: L.CARDS, types: ["Expense", "Fixed"], slots: 8, matchPayment: true,
    title: "SPENT WITH EACH CARD", dark: "#334155", light: "#E2E8F0", bar: NAVY, month: month });
  d.getDataRange().setFontFamily("Arial");

  try {
    const pie = (row, col, labelCol, amtCol, n, title) => d.insertChart(d.newChart()
      .setChartType(Charts.ChartType.PIE)
      .addRange(d.getRange(row + 1, labelCol, n + 1, 1))
      .addRange(d.getRange(row + 1, amtCol, n + 1, 1))
      .setNumHeaders(1).setPosition(row, col, 0, 0)
      .setOption("title", title).setOption("pieHole", 0.5)
      .setOption("width", 340).setOption("height", 280)
      .setOption("legend", { position: "right", textStyle: { fontSize: 10 } })
      .build());
    pie(12, 7, 2, 4, 15, "Everyday expenses");
    pie(r2, 7, 2, 4, 15, "Fixed expenses");
    pie(12, 14, 9, 11, 8, "Income");
    pie(r2, 14, 9, 11, 8, "Savings");
  } catch (_e) {}
}

/**
 * One table: Category | % | Amount | Share. Rows come from Lists!<list> (FILTER, live).
 * Amount = this month's entries of the given types with that category (or paid with that
 * card, when matchPayment). Returns the row of its TOTAL line.
 */
function breakdownTable(d, o) {
  const INR = '"₹"#,##0.00', PCT = '0%';
  const c = o.col, head = o.row, first = o.row + 2, last = first + o.slots - 1;
  const Lc = n => colLetter(c + n); // 0 name, 1 %, 2 amount, 3 bar
  const names = `${Lc(0)}${first}:${Lc(0)}${last}`;
  const amts = `${Lc(2)}${first}:${Lc(2)}${last}`;
  const matchCol = o.matchPayment ? XR.pay : XR.cat;
  const total = sumTypes(o.types, o.month);

  d.getRange(head, c, 1, 4).merge().setValue(o.title).setBackground(o.dark).setFontColor("#FFFFFF").setFontWeight("bold");
  d.getRange(head + 1, c, 1, 4).setValues([[o.matchPayment ? "Card" : "Category", "%", "Amount", "Share"]])
    .setBackground(o.light).setFontColor(o.dark).setFontWeight("bold");
  d.getRange(first, c).setFormula(
    `=IFERROR(ARRAY_CONSTRAIN(FILTER(Lists!$${o.list}$2:$${o.list}, Lists!$${o.list}$2:$${o.list}<>""), ${o.slots}, 1), "")`);
  // One formula per row: SUMIFS does not evaluate row by row inside ARRAYFORMULA
  const amtF = [], pctF = [];
  for (let r = first; r <= last; r++) {
    const name = `${Lc(0)}${r}`;
    amtF.push([`=IF(${name}="", "", ${sumTypes(o.types, `${matchCol},${name},${o.month}`)})`]);
    pctF.push([`=IF(${name}="", "", IF(SUM(${amts})=0, 0, ${Lc(2)}${r}/SUM(${amts})))`]);
  }
  d.getRange(first, c + 2, o.slots, 1).setFormulas(amtF);
  d.getRange(first, c + 1, o.slots, 1).setFormulas(pctF);
  const bars = [];
  for (let r = first; r <= last; r++) {
    bars.push([`=IF(N(${Lc(2)}${r})=0,"",SPARKLINE(N(${Lc(1)}${r}),{"charttype","bar";"max",1;"color1","${o.bar}"}))`]);
  }
  d.getRange(first, c + 3, o.slots, 1).setFormulas(bars);
  d.getRange(first, c, o.slots, 1).setFontColor("#1E293B");
  d.getRange(first, c + 1, o.slots, 1).setNumberFormat(PCT);
  d.getRange(first, c + 2, o.slots, 1).setNumberFormat(INR);

  const other = last + 1, tot = last + 2;
  if (!o.matchPayment) {
    // Money under tags that aren't (or are no longer) in the Lists tab
    d.getRange(other, c + 2).setFormula(`=IF(ABS((${total})-SUM(${amts}))<0.005, "", (${total})-SUM(${amts}))`).setNumberFormat(INR);
    d.getRange(other, c).setFormula(`=IF(${Lc(2)}${other}="", "", "Other (not in Lists)")`).setFontColor("#64748B").setFontStyle("italic");
  }
  d.getRange(tot, c, 1, 4).setBackground(o.light).setFontColor(o.dark).setFontWeight("bold");
  d.getRange(tot, c).setValue("TOTAL");
  d.getRange(tot, c + 2).setFormula(o.matchPayment ? `=SUM(${amts})` : `=${total}`).setNumberFormat(INR);
  d.getRange(head, c, 2, 4).setBorder(true, true, true, true, true, true, "#E2E8F0", SpreadsheetApp.BorderStyle.SOLID);
  d.getRange(tot, c, 1, 4).setBorder(true, true, true, true, false, false, "#E2E8F0", SpreadsheetApp.BorderStyle.SOLID);
  return tot;
}

// ----------------------------------------------------------------------------
// ANNUAL VIEW (month-by-month; category rows follow the Lists tab live)
// ----------------------------------------------------------------------------
function buildAnnualDashboard() {
  const ss = book();
  let a = ss.getSheetByName("Annual View");
  if (!a) a = ss.insertSheet("Annual View");

  a.getCharts().forEach(c => a.removeChart(c));
  a.clear();
  a.getRange(1, 1, a.getMaxRows(), a.getMaxColumns()).breakApart();
  a.setHiddenGridlines(true);
  a.setFrozenRows(0);

  a.setColumnWidth(1, 210);
  for (let c = 2; c <= 13; c++) a.setColumnWidth(c, 90);
  a.setColumnWidth(14, 110);

  const INR = '"₹"#,##0.00';
  const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
  const mStart = m => `DATE($B$3,${m + 1},1)`;
  const inMonth = m => `${XR.date},">="&${mStart(m)},${XR.date},"<"&EDATE(${mStart(m)},1)`;

  a.getRange("A1:N1").merge().setBackground("#0F172A");
  a.getRange("A1").setValue("ANNUAL VIEW (MONTH BY MONTH)").setFontColor("#FFFFFF").setFontSize(14).setFontWeight("bold");
  a.getRange("A3").setValue("Year:").setFontWeight("bold");
  a.getRange("B3").setFormula("=YEAR(Dashboard!C4)").setFontWeight("bold").setBackground("#FEF3C7")
    .setFontColor("#92400E").setHorizontalAlignment("center");
  a.getRange(5, 1, 1, 14).setValues([["Category / Metric"].concat(MONTHS, ["Year total"])])
    .setFontWeight("bold").setBackground("#334155").setFontColor("#FFFFFF");
  a.getRange(5, 2, 1, 13).setHorizontalAlignment("center");

  // Section: title row, one row per tag (from Lists), then a total of `totalTypes`.
  const section = (startRow, title, list, types, totalTypes, slots, dark, light, totalLabel) => {
    a.getRange(startRow, 1, 1, 14).merge().setValue(title).setBackground(dark).setFontColor("#FFFFFF").setFontWeight("bold");
    const first = startRow + 1, last = first + slots - 1, names = `$A${first}:$A${last}`;
    a.getRange(first, 1).setFormula(
      `=IFERROR(ARRAY_CONSTRAIN(FILTER(Lists!$${list}$2:$${list}, Lists!$${list}$2:$${list}<>""), ${slots}, 1), "")`);
    // One formula per cell: SUMIFS does not evaluate row by row inside ARRAYFORMULA
    const grid = [];
    for (let r = first; r <= last; r++) {
      const row = [];
      for (let m = 0; m < 12; m++) row.push(`=IF($A${r}="", "", ${sumTypes(types, `${XR.cat},$A${r},${inMonth(m)}`)})`);
      grid.push(row);
    }
    a.getRange(first, 2, slots, 12).setFormulas(grid);
    const rowTotals = [];
    for (let r = first; r <= last; r++) rowTotals.push([`=IF($A${r}="", "", SUM(B${r}:M${r}))`]);
    a.getRange(first, 14, slots, 1).setFormulas(rowTotals).setFontWeight("bold");
    const tot = last + 1, totals = [];
    for (let m = 0; m < 12; m++) totals.push(`=${sumTypes(totalTypes, inMonth(m))}`);
    totals.push(`=SUM(B${tot}:M${tot})`);
    a.getRange(tot, 1).setValue(totalLabel);
    a.getRange(tot, 2, 1, 13).setFormulas([totals]);
    a.getRange(tot, 1, 1, 14).setFontWeight("bold").setBackground(light).setFontColor(dark);
    return tot;
  };

  const incTot = section(6, "INCOME", L.INC, ["Income", "Borrowed"], ["Income"], 8, "#1E40AF", "#DBEAFE", "TOTAL INCOME (excl. borrowed)");
  const expTot = section(incTot + 2, "EVERYDAY EXPENSES", L.EXP, ["Expense"], ["Expense"], 15, "#991B1B", "#FEE2E2", "TOTAL EVERYDAY");
  const fixTot = section(expTot + 2, "FIXED EXPENSES", L.FIXED, ["Fixed", "Repayment"], ["Fixed"], 15, "#9A3412", "#FFEDD5", "TOTAL FIXED (excl. loan repaid)");
  const savTot = section(fixTot + 2, "SAVINGS", L.SAV, ["Savings"], ["Savings"], 8, "#5B21B6", "#EDE9FE", "TOTAL SAVED");

  // Summary
  const s = savTot + 2;
  a.getRange(s, 1, 1, 14).merge().setValue("SUMMARY").setBackground("#14532D").setFontColor("#FFFFFF").setFontWeight("bold");
  const rows = [
    ["TOTAL SPENDING", m => `=${colLetter(m + 2)}${expTot}+${colLetter(m + 2)}${fixTot}`],
    ["LEFT (income − spending − savings)", m => `=${colLetter(m + 2)}${incTot}-${colLetter(m + 2)}${s + 1}-${colLetter(m + 2)}${savTot}`],
    ["SAVINGS RATE", m => `=IF(${colLetter(m + 2)}${incTot}=0,"",${colLetter(m + 2)}${savTot}/${colLetter(m + 2)}${incTot})`],
    ["BORROWED", m => `=${sumTypes(["Borrowed"], inMonth(m))}`],
    ["LOAN REPAID", m => `=${sumTypes(["Repayment"], inMonth(m))}`],
    ["CARD BILLS PAID (not spending)", m => `=${sumTypes(["Transfer"], inMonth(m))}`]
  ];
  rows.forEach((def, i) => {
    const r = s + 1 + i, f = [];
    for (let m = 0; m < 12; m++) f.push(def[1](m));
    // Year column: sum, except the rate which is recomputed from the year totals
    f.push(def[0] === "SAVINGS RATE" ? `=IF(N${incTot}=0,"",N${savTot}/N${incTot})` : `=SUM(B${r}:M${r})`);
    a.getRange(r, 1).setValue(def[0]);
    const fmt = def[0] === "SAVINGS RATE" ? "0%" : def[0].indexOf("LEFT") === 0 ? '"₹"#,##0.00;[Red]-"₹"#,##0.00' : INR;
    a.getRange(r, 2, 1, 13).setFormulas([f]).setNumberFormat(fmt);
    a.getRange(r, 1, 1, 14).setFontWeight(i < 3 ? "bold" : "normal")
      .setBackground(i < 3 ? "#DCFCE7" : "#F8FAFC").setFontColor(i < 3 ? "#15803D" : "#475569");
  });
  const lastRow = s + rows.length;

  a.getRange(6, 2, savTot - 5, 13).setNumberFormat(INR);
  a.getRange(5, 1, lastRow - 4, 14).setBorder(true, true, true, true, true, true, "#E2E8F0", SpreadsheetApp.BorderStyle.SOLID);
  a.getDataRange().setFontFamily("Arial");
  a.setFrozenRows(5);
}
