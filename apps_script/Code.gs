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
const EXP_SLOTS = 30; // rows reserved for expense categories on the dashboards
const INC_SLOTS = 15; // rows reserved for income categories

const EXPENSE_CATEGORIES = [
  "Food",
  "Fuel",
  "Transport",
  "Groceries",
  "Rent",
  "Snacks and tea",
  "Medicine",
  "F99 expense",
  "Card payment",
  "Miscellaneous"
];

const INCOME_CATEGORIES = [
  "Salary",
  "F99 payment",
  "Borrowed",
  "Income"
];

const PAYMENT_METHODS = [
  "GPay",
  "Card",
  "Cash"
];

// Expenses sheet columns (1-based)
const COL = { DATE: 1, TYPE: 2, CAT: 3, AMT: 4, PAY: 5, USER: 6, NET: 7, ID: 8, EMAIL: 9 };
const HEADERS = ["Date", "Type", "Category", "Amount", "Payment", "User", "Net Amount", "ID", "Email"];

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
  const exp = col("A"), inc = col("B"), pay = col("C");
  return {
    expense_categories: exp.length ? exp : EXPENSE_CATEGORIES,
    income_categories: inc.length ? inc : INCOME_CATEGORIES,
    payments: pay.length ? pay : PAYMENT_METHODS
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
  if (sh.getRange(1, COL.ID).getValue() !== "ID") {
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
  const vals = sh.getRange(2, 1, last - 1, COL.EMAIL).getValues();
  let fixed = false;
  vals.forEach(v => { if (!v[COL.ID - 1]) { v[COL.ID - 1] = Utilities.getUuid(); fixed = true; } });
  if (fixed) sh.getRange(2, COL.ID, vals.length, 1).setValues(vals.map(v => [v[COL.ID - 1]]));
  return vals.map((v, i) => ({
    row: i + 2,
    date: v[COL.DATE - 1] instanceof Date ? v[COL.DATE - 1] : new Date(v[COL.DATE - 1]),
    type: String(v[COL.TYPE - 1]) === "Income" ? "Income" : "Expense",
    category: String(v[COL.CAT - 1]),
    amount: Math.abs(Number(v[COL.AMT - 1]) || 0),
    payment: String(v[COL.PAY - 1]),
    user: String(v[COL.USER - 1]),
    id: String(v[COL.ID - 1]),
    email: String(v[COL.EMAIL - 1]).toLowerCase()
  }));
}

function toApi(r) {
  return {
    id: r.id, ts: r.date.getTime(), type: r.type, category: r.category,
    amount: r.amount, payment: r.payment, user: r.user, email: r.email
  };
}

function cleanEntry(d) {
  const type = d.type === "Income" ? "Income" : "Expense";
  const amount = Math.abs(Number(d.amount) || 0);
  if (!(amount > 0)) throw new ApiError("Amount must be more than 0");
  const category = String(d.category || "").trim() || (type === "Income" ? "Income" : "Miscellaneous");
  const payment = String(d.payment || "").trim() || "GPay";
  return { type: type, amount: amount, category: category, payment: payment };
}

function writeRow(sh, row, date, e, me, id) {
  sh.getRange(row, 1, 1, COL.EMAIL).setValues([[
    date, e.type, e.category, e.amount, e.payment, me.name,
    e.type === "Income" ? e.amount : -e.amount, id, me.email
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

function summary(me, d) {
  const now = new Date();
  const year = Number(d.year) || now.getFullYear();
  const month = d.month !== undefined ? Number(d.month) : now.getMonth(); // 0-11
  const start = new Date(year, month, 1), end = new Date(year, month + 1, 1);
  const mine = d.scope === "me";

  const rows = readRows(expensesSheet())
    .filter(r => r.date >= start && r.date < end)
    .filter(r => !mine || r.email === me.email);

  let income = 0, expense = 0;
  const expCats = {}, incCats = {}, people = {};
  rows.forEach(r => {
    const who = r.user || r.email || "Unknown";
    people[who] = people[who] || { name: who, income: 0, expense: 0 };
    if (r.type === "Income") {
      income += r.amount;
      incCats[r.category] = (incCats[r.category] || 0) + r.amount;
      people[who].income += r.amount;
    } else {
      expense += r.amount;
      expCats[r.category] = (expCats[r.category] || 0) + r.amount;
      people[who].expense += r.amount;
    }
  });
  const sorted = o => Object.keys(o).map(k => ({ name: k, amount: o[k] })).sort((a, b) => b.amount - a.amount);
  return {
    ok: true, year: year, month: month, scope: mine ? "me" : "family",
    income: income, expense: expense, count: rows.length,
    expense_categories: sorted(expCats),
    income_categories: sorted(incCats),
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

  lists.getRange("A1:D1").setValues([["Expense Categories", "Income Categories", "Payment Methods", "Family Emails"]])
    .setFontWeight("bold").setBackground("#E2E8F0");

  const fillIfEmpty = (colIdx, values) => {
    const existing = lists.getRange(2, colIdx, Math.max(lists.getMaxRows() - 1, 1), 1).getValues()
      .map(r => String(r[0]).trim()).filter(String);
    if (!existing.length) lists.getRange(2, colIdx, values.length, 1).setValues(values.map(v => [v]));
  };
  fillIfEmpty(1, EXPENSE_CATEGORIES);
  fillIfEmpty(2, INCOME_CATEGORIES);
  fillIfEmpty(3, PAYMENT_METHODS);

  // Old versions kept display names in column D; keep only email addresses now.
  const emails = memberEmailsRaw(lists).filter(v => v.indexOf("@") > 0);
  lists.getRange("D2:D").clearContent();
  if (emails.length) lists.getRange(2, 4, emails.length, 1).setValues(emails.map(v => [v]));
  lists.getRange("F1").setValue("Admin (script owner): " + adminEmail()).setFontColor("#64748B");

  const log = expensesSheet();
  log.setColumnWidth(1, 150); log.setColumnWidth(2, 100); log.setColumnWidth(3, 140);
  log.setColumnWidth(4, 110); log.setColumnWidth(5, 110); log.setColumnWidth(6, 110); log.setColumnWidth(7, 120);

  // Soft validation
  const rule = c => SpreadsheetApp.newDataValidation()
    .requireValueInRange(lists.getRange(c + "2:" + c), true).setAllowInvalid(true).build();
  log.getRange("C2:C" + N).setDataValidation(rule("A"));
  log.getRange("E2:E" + N).setDataValidation(rule("C"));
  log.getRange("F2:F" + N).clearDataValidations();

  return lists;
}

// ----------------------------------------------------------------------------
// MONTHLY DASHBOARD (Matching the visual card template in the screenshot)
// ----------------------------------------------------------------------------
function buildDashboard() {
  const ss = book();
  let d = ss.getSheetByName("Dashboard");
  if (!d) d = ss.insertSheet("Dashboard");

  d.getCharts().forEach(c => d.removeChart(c));
  d.clear();
  d.setHiddenGridlines(true);

  // Column widths
  d.setColumnWidth(1, 40);  // A: Margin
  d.setColumnWidth(2, 150); // B: Cat name (Expenses)
  d.setColumnWidth(3, 75);  // C: %
  d.setColumnWidth(4, 110); // D: Amount
  d.setColumnWidth(5, 100); // E: Bar
  d.setColumnWidth(6, 30);  // F: Spacer
  d.setColumnWidth(7, 340); // G: Donut Chart Area (Expenses)
  d.setColumnWidth(8, 30);  // H: Spacer
  d.setColumnWidth(9, 140); // I: Cat name (Income)
  d.setColumnWidth(10, 75); // J: %
  d.setColumnWidth(11, 110);// K: Amount
  d.setColumnWidth(12, 100);// L: Bar
  d.setColumnWidth(13, 30); // M: Spacer
  d.setColumnWidth(14, 340);// N: Donut Chart Area (Income)

  const INR = '"₹"#,##0.00', PCT = '0.0%';
  const NAVY = "#1E293B", BLUE = "#2563EB", RED = "#DC2626", GREEN = "#16A34A";
  const CARD_BG = "#FFFFFF", BORDER_COL = "#E2E8F0";

  // --- HEADER & FILTER ---
  d.getRange("B2:N2").setBackground("#0F172A").merge();
  d.getRange("B2").setValue("⚡ SHAKETRACK FINANCIAL DASHBOARD").setFontSize(16).setFontWeight("bold").setFontColor("#FFFFFF")
    .setVerticalAlignment("middle").setHorizontalAlignment("left");

  d.getRange("B4").setValue("Selected Month:").setFontWeight("bold").setFontColor("#334155");
  d.getRange("C4:D4").merge();
  d.getRange("C4").setValue(new Date(new Date().getFullYear(), new Date().getMonth(), 1))
    .setNumberFormat("mmmm yyyy").setFontWeight("bold").setBackground("#FEF3C7").setFontColor("#92400E")
    .setHorizontalAlignment("center");

  d.getRange("E4").setValue("Year:").setFontWeight("bold").setFontColor("#334155").setHorizontalAlignment("right");
  d.getRange("F4").setFormula("=YEAR(C4)").setFontWeight("bold").setBackground("#F1F5F9").setFontColor(NAVY)
    .setHorizontalAlignment("center");

  // Date ranges for SUMIFS
  const inMonth = `Expenses!$A$2:$A$${N},">="&$C$4,Expenses!$A$2:$A$${N},"<"&EDATE($C$4,1)`;
  const typeCol = `Expenses!$B$2:$B$${N}`;
  const amtCol = `Expenses!$D$2:$D$${N}`;
  const catCol = `Expenses!$C$2:$C$${N}`;

  // --- TOP KPI CARDS (Row 6 to 9) ---
  // Card 1: Income (Blue)
  d.getRange("B6:D6").merge().setValue("INCOME").setFontWeight("bold").setFontSize(10).setFontColor(BLUE).setBackground("#EFF6FF");
  d.getRange("B7:D8").merge().setFormula(`=IFERROR(SUMIFS(${amtCol},${typeCol},"Income",${inMonth}),0)`)
    .setFontSize(20).setFontWeight("bold").setFontColor(BLUE).setNumberFormat(INR)
    .setHorizontalAlignment("center").setVerticalAlignment("middle").setBackground(CARD_BG);
  d.getRange("B9:D9").merge().setValue("100% (Base)").setFontSize(9).setFontColor("#64748B").setHorizontalAlignment("center").setBackground("#F8FAFC");
  d.getRange("B6:D9").setBorder(true, true, true, true, false, false, BORDER_COL, SpreadsheetApp.BorderStyle.SOLID);

  // Card 2: Expenses (Red)
  d.getRange("E6:G6").merge().setValue("EXPENSES").setFontWeight("bold").setFontSize(10).setFontColor(RED).setBackground("#FEF2F2");
  d.getRange("E7:G8").merge().setFormula(`=IFERROR(SUMIFS(${amtCol},${typeCol},"Expense",${inMonth}),0)`)
    .setFontSize(20).setFontWeight("bold").setFontColor(RED).setNumberFormat(INR)
    .setHorizontalAlignment("center").setVerticalAlignment("middle").setBackground(CARD_BG);
  d.getRange("E9:G9").merge().setFormula(`=IF(B7=0,"0.0% spent",TEXT(E7/B7,"0.0%")&" of income")`)
    .setFontSize(9).setFontColor(RED).setHorizontalAlignment("center").setBackground("#F8FAFC");
  d.getRange("E6:G9").setBorder(true, true, true, true, false, false, BORDER_COL, SpreadsheetApp.BorderStyle.SOLID);

  // Card 3: Savings / Net Remaining (Green)
  d.getRange("I6:K6").merge().setValue("SAVINGS / REMAINING").setFontWeight("bold").setFontSize(10).setFontColor(GREEN).setBackground("#F0FDF4");
  d.getRange("I7:K8").merge().setFormula(`=B7-E7`)
    .setFontSize(20).setFontWeight("bold").setFontColor(GREEN).setNumberFormat(INR)
    .setHorizontalAlignment("center").setVerticalAlignment("middle").setBackground(CARD_BG);
  d.getRange("I9:K9").merge().setFormula(`=IF(B7=0,"0.0% saved",TEXT(I7/B7,"0.0%")&" savings rate")`)
    .setFontSize(9).setFontColor(GREEN).setHorizontalAlignment("center").setBackground("#F8FAFC");
  d.getRange("I6:K9").setBorder(true, true, true, true, false, false, BORDER_COL, SpreadsheetApp.BorderStyle.SOLID);

  // Card 4: Horizontal Comparison Bars (Top Right, Cols L to N)
  d.getRange("L6:N6").merge().setValue("INCOME VS EXPENSE COMPARISON").setFontWeight("bold").setFontSize(9).setFontColor("#475569").setBackground("#F1F5F9");
  d.getRange("L7").setValue("Income").setFontSize(9).setFontWeight("bold").setFontColor(BLUE);
  d.getRange("M7:N7").merge().setFormula(`=IFERROR(SPARKLINE(B7,{"charttype","bar";"max",MAX(B7,E7,1);"color1","${BLUE}"}),"")`);
  d.getRange("L8").setValue("Expenses").setFontSize(9).setFontWeight("bold").setFontColor(RED);
  d.getRange("M8:N8").merge().setFormula(`=IFERROR(SPARKLINE(E7,{"charttype","bar";"max",MAX(B7,E7,1);"color1","${RED}"}),"")`);
  d.getRange("L9").setValue("Savings").setFontSize(9).setFontWeight("bold").setFontColor(GREEN);
  d.getRange("M9:N9").merge().setFormula(`=IFERROR(SPARKLINE(MAX(0,I7),{"charttype","bar";"max",MAX(B7,E7,1);"color1","${GREEN}"}),"")`);
  d.getRange("L6:N9").setBorder(true, true, true, true, false, false, BORDER_COL, SpreadsheetApp.BorderStyle.SOLID);

  // ==========================================================================
  // BREAKDOWN TABLES — category rows come live from the Lists tab, so adding,
  // renaming or removing a tag there updates the dashboard with no re-run.
  // ==========================================================================
  breakdownTable(d, {
    col: 2, listCol: "A", type: "Expense", slots: EXP_SLOTS, totalCell: "$E$7",
    title: "EXPENSES BREAKDOWN", dark: "#991B1B", light: "#FEE2E2", bar: RED
  });
  breakdownTable(d, {
    col: 9, listCol: "B", type: "Income", slots: INC_SLOTS, totalCell: "$B$7",
    title: "INCOME BREAKDOWN", dark: "#1E40AF", light: "#DBEAFE", bar: BLUE
  });
  d.getDataRange().setFontFamily("Arial");

  // DONUT CHARTS (ranges cover every slot; empty rows are ignored by the chart)
  try {
    const expChart = d.newChart()
      .setChartType(Charts.ChartType.PIE)
      .addRange(d.getRange(13, 2, EXP_SLOTS + 1, 1))
      .addRange(d.getRange(13, 4, EXP_SLOTS + 1, 1))
      .setNumHeaders(1)
      .setPosition(12, 7, 0, 0)
      .setOption("title", "Expense Distribution")
      .setOption("pieHole", 0.5)
      .setOption("width", 350)
      .setOption("height", 300)
      .setOption("legend", { position: "right", textStyle: { fontSize: 10 } })
      .build();
    d.insertChart(expChart);

    const incChart = d.newChart()
      .setChartType(Charts.ChartType.PIE)
      .addRange(d.getRange(13, 9, INC_SLOTS + 1, 1))
      .addRange(d.getRange(13, 11, INC_SLOTS + 1, 1))
      .setNumHeaders(1)
      .setPosition(12, 14, 0, 0)
      .setOption("title", "Income Distribution")
      .setOption("pieHole", 0.5)
      .setOption("width", 350)
      .setOption("height", 300)
      .setOption("legend", { position: "right", textStyle: { fontSize: 10 } })
      .build();
    d.insertChart(incChart);
  } catch (_e) {}
}

/** One category table on the Dashboard: Category | % | Amount | Share, driven by Lists!<listCol>. */
function breakdownTable(d, o) {
  const INR = '"₹"#,##0.00', PCT = '0.0%';
  const c = o.col, first = 14, last = first + o.slots - 1;
  const L = n => colLetter(c + n); // L(0)=Category, L(1)=%, L(2)=Amount, L(3)=Share
  const cats = `${L(0)}${first}:${L(0)}${last}`;
  const amts = `${L(2)}${first}:${L(2)}${last}`;
  const inMonth = `Expenses!$A$2:$A$${N},">="&$C$4,Expenses!$A$2:$A$${N},"<"&EDATE($C$4,1)`;
  const typeCol = `Expenses!$B$2:$B$${N}`, amtCol = `Expenses!$D$2:$D$${N}`, catCol = `Expenses!$C$2:$C$${N}`;

  d.getRange(12, c, 1, 4).merge().setValue(o.title).setBackground(o.dark).setFontColor("#FFFFFF").setFontWeight("bold");
  d.getRange(13, c, 1, 4).setValues([["Category", "%", "Amount", "Share"]])
    .setBackground(o.light).setFontColor(o.dark).setFontWeight("bold");

  // Category names: every non-empty cell of Lists!<listCol>, up to `slots` rows
  d.getRange(first, c).setFormula(
    `=IFERROR(ARRAY_CONSTRAIN(FILTER(Lists!$${o.listCol}$2:$${o.listCol}, Lists!$${o.listCol}$2:$${o.listCol}<>""), ${o.slots}, 1), "")`
  );
  d.getRange(first, c + 2).setFormula(
    `=ARRAYFORMULA(IF(${cats}="", "", IFERROR(SUMIFS(${amtCol},${typeCol},"${o.type}",${catCol},${cats},${inMonth}), 0)))`
  );
  d.getRange(first, c + 1).setFormula(
    `=ARRAYFORMULA(IF(${cats}="", "", IF(${o.totalCell}=0, 0, ${amts}/${o.totalCell})))`
  );
  const bars = [];
  for (let r = first; r <= last; r++) {
    bars.push([`=IF(N(${L(2)}${r})=0,"",SPARKLINE(N(${L(1)}${r}),{"charttype","bar";"max",1;"color1","${o.bar}"}))`]);
  }
  d.getRange(first, c + 3, o.slots, 1).setFormulas(bars);
  d.getRange(first, c, o.slots, 1).setFontColor("#1E293B");
  d.getRange(first, c + 1, o.slots, 1).setNumberFormat(PCT);
  d.getRange(first, c + 2, o.slots, 1).setNumberFormat(INR);

  // Money in categories that aren't (or are no longer) in Lists
  const other = last + 1, total = last + 2;
  d.getRange(other, c + 2).setFormula(`=IF(ABS(${o.totalCell}-SUM(${amts}))<0.005, "", ${o.totalCell}-SUM(${amts}))`).setNumberFormat(INR);
  d.getRange(other, c).setFormula(`=IF(${L(2)}${other}="", "", "Other (not in Lists)")`).setFontColor("#64748B").setFontStyle("italic");
  d.getRange(total, c, 1, 4).setBackground(o.light).setFontColor(o.dark).setFontWeight("bold");
  d.getRange(total, c).setValue("TOTAL " + o.type.toUpperCase());
  d.getRange(total, c + 2).setFormula(`=${o.totalCell}`).setNumberFormat(INR);
  d.getRange(total, c + 1).setFormula(`=IF(${o.totalCell}=0, "", 1)`).setNumberFormat(PCT);

  d.getRange(12, c, 2, 4).setBorder(true, true, true, true, true, true, "#E2E8F0", SpreadsheetApp.BorderStyle.SOLID);
  d.getRange(total, c, 1, 4).setBorder(true, true, true, true, false, false, "#E2E8F0", SpreadsheetApp.BorderStyle.SOLID);
}

function colLetter(n) {
  let s = "";
  while (n > 0) { const m = (n - 1) % 26; s = String.fromCharCode(65 + m) + s; n = Math.floor((n - 1) / 26); }
  return s;
}

// ----------------------------------------------------------------------------
// ANNUAL HORIZONTAL DASHBOARD (Month-by-month full-year matrix)
// Category rows come live from the Lists tab, like the monthly dashboard.
// ----------------------------------------------------------------------------
function buildAnnualDashboard() {
  const ss = book();
  let a = ss.getSheetByName("Annual View");
  if (!a) a = ss.insertSheet("Annual View");

  a.getCharts().forEach(c => a.removeChart(c));
  a.clear();
  a.setHiddenGridlines(true);

  a.setColumnWidth(1, 170);
  for (let c = 2; c <= 13; c++) a.setColumnWidth(c, 90);
  a.setColumnWidth(14, 110);

  const INR = '"₹"#,##0.00';
  const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
  const amtCol = `Expenses!$D$2:$D$${N}`, typeCol = `Expenses!$B$2:$B$${N}`;
  const catCol = `Expenses!$C$2:$C$${N}`, dateCol = `Expenses!$A$2:$A$${N}`;
  const mStart = m => `DATE($B$3,${m + 1},1)`;
  const inMonth = m => `${dateCol},">="&${mStart(m)},${dateCol},"<"&EDATE(${mStart(m)},1)`;

  a.getRange("A1:N1").merge().setBackground("#0F172A");
  a.getRange("A1").setValue("📅 ANNUAL FINANCIAL MATRIX (MONTH-BY-MONTH HORIZONTAL VIEW)").setFontColor("#FFFFFF")
    .setFontSize(14).setFontWeight("bold").setVerticalAlignment("middle");
  a.getRange("A3").setValue("Year:").setFontWeight("bold");
  a.getRange("B3").setFormula("=YEAR(Dashboard!C4)").setFontWeight("bold").setBackground("#FEF3C7")
    .setFontColor("#92400E").setHorizontalAlignment("center");

  const head = [["Category / Metric"].concat(MONTHS, ["Year Total"])];
  a.getRange(5, 1, 1, 14).setValues(head).setFontWeight("bold").setBackground("#334155").setFontColor("#FFFFFF");
  a.getRange(5, 2, 1, 13).setHorizontalAlignment("center");

  // One section: header row, `slots` category rows from Lists!<listCol>, then a TOTAL row
  // (the total is every entry of that type, so money in unlisted categories still counts).
  const section = (startRow, title, type, listCol, slots, dark, light, nameColor) => {
    a.getRange(startRow, 1, 1, 14).merge().setValue(title).setBackground(dark).setFontColor("#FFFFFF").setFontWeight("bold");
    const first = startRow + 1, last = first + slots - 1, cats = `$A${first}:$A${last}`;
    a.getRange(first, 1).setFormula(
      `=IFERROR(ARRAY_CONSTRAIN(FILTER(Lists!$${listCol}$2:$${listCol}, Lists!$${listCol}$2:$${listCol}<>""), ${slots}, 1), "")`
    );
    a.getRange(first, 1, slots, 1).setFontColor(nameColor).setFontWeight("bold");
    const monthFormulas = [];
    for (let m = 0; m < 12; m++) {
      monthFormulas.push(
        `=ARRAYFORMULA(IF(${cats}="", "", IFERROR(SUMIFS(${amtCol},${typeCol},"${type}",${catCol},${cats},${inMonth(m)}), 0)))`
      );
    }
    a.getRange(first, 2, 1, 12).setFormulas([monthFormulas]);
    const rowTotals = [];
    for (let r = first; r <= last; r++) rowTotals.push([`=IF($A${r}="", "", SUM(B${r}:M${r}))`]);
    a.getRange(first, 14, slots, 1).setFormulas(rowTotals).setFontWeight("bold");

    const totalRow = last + 1, totals = [];
    for (let m = 0; m < 12; m++) totals.push(`=IFERROR(SUMIFS(${amtCol},${typeCol},"${type}",${inMonth(m)}), 0)`);
    totals.push(`=SUM(B${totalRow}:M${totalRow})`);
    a.getRange(totalRow, 1).setValue("TOTAL " + (type === "Income" ? "INCOME" : "EXPENDITURE"));
    a.getRange(totalRow, 2, 1, 13).setFormulas([totals]);
    a.getRange(totalRow, 1, 1, 14).setFontWeight("bold").setBackground(light).setFontColor(dark);
    return totalRow;
  };

  const totalIncRow = section(6, "➕ INCOME (INCOMING)", "Income", "B", INC_SLOTS, "#1E40AF", "#DBEAFE", "#1E3A8A");
  const totalExpRow = section(totalIncRow + 2, "➖ EXPENDITURES (OUTGOING)", "Expense", "A", EXP_SLOTS, "#991B1B", "#FEE2E2", "#7F1D1D");

  // NET SUMMARY
  let r = totalExpRow + 2;
  a.getRange(r, 1, 1, 14).merge().setValue("📊 NET SUMMARY (SAVINGS & REMAINING)").setBackground("#14532D")
    .setFontColor("#FFFFFF").setFontWeight("bold");
  const netRow = r + 1, pctRow = r + 2, net = [], pct = [];
  for (let m = 0; m < 13; m++) {
    const L = colLetter(m + 2);
    net.push(`=${L}${totalIncRow}-${L}${totalExpRow}`);
    pct.push(`=IF(${L}${totalIncRow}=0, "", ${L}${netRow}/${L}${totalIncRow})`);
  }
  a.getRange(netRow, 1).setValue("NET REMAINING");
  a.getRange(netRow, 2, 1, 13).setFormulas([net]);
  a.getRange(netRow, 1, 1, 14).setFontWeight("bold").setBackground("#DCFCE7").setFontColor("#15803D");
  a.getRange(pctRow, 1).setValue("SAVINGS RATE %");
  a.getRange(pctRow, 2, 1, 13).setFormulas([pct]).setNumberFormat("0.0%");
  a.getRange(pctRow, 1, 1, 14).setFontWeight("bold").setBackground("#F0FDF4").setFontColor("#15803D");

  a.getRange(6, 2, netRow - 5, 13).setNumberFormat(INR);
  a.getRange(pctRow, 2, 1, 13).setNumberFormat("0.0%");
  a.getRange(5, 1, pctRow - 4, 14).setBorder(true, true, true, true, true, true, "#E2E8F0", SpreadsheetApp.BorderStyle.SOLID);
  a.getDataRange().setFontFamily("Arial");
  a.setFrozenRows(5);
}
