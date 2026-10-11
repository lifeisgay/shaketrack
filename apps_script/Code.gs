// Paste into Extensions -> Apps Script of the "Expense Tracker" sheet.
// 1) Set SECRET below. The same value goes in the app.
// 2) Run setup() once. It sets the dropdowns and rebuilds the Dashboard with charts.
// 3) Deploy -> New deployment -> Web app -> Execute as: Me, Who has access: Anyone. Copy the URL into the app.
// After editing this script later, use Deploy -> Manage deployments -> Edit -> New version.

const SECRET = "change-me";
const N = 5000;                       // Expenses rows read by the Dashboard
const CAT_SLOTS = 12, PAY_SLOTS = 8, USER_SLOTS = 8;  // names shown per Dashboard table

function json(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}

// The app calls this to get the current category and payment lists.
function doGet(e) {
  if (!e || !e.parameter || e.parameter.secret !== SECRET) return json({ error: "unauthorized" });
  const l = SpreadsheetApp.getActive().getSheetByName("Lists");
  const col = c => l.getRange(c + "2:" + c).getValues().map(r => String(r[0]).trim()).filter(String);
  return json({ categories: col("A"), payments: col("B"), users: col("C") });
}

// The app posts one expense here. It goes under the last filled row of column A in "Expenses".
function doPost(e) {
  const d = JSON.parse(e.postData.contents);
  if (d.secret !== SECRET) return ContentService.createTextOutput("unauthorized");
  const sh = SpreadsheetApp.getActive().getSheetByName("Expenses");
  const colA = sh.getRange("A:A").getValues();
  let n = colA.length;
  while (n > 0 && colA[n - 1][0] === "") n--;
  const row = n + 1;
  sh.getRange(row, 1, 1, 5).setValues([[new Date(d.ts), Number(d.amount), d.category, d.payment, d.user]]);
  sh.getRange(row, 1).setNumberFormat("dd-mmm-yyyy");
  sh.getRange(row, 2).setNumberFormat('"₹"#,##0.00');
  return ContentService.createTextOutput("ok");
}

function setup() {
  setupLists();
  buildDashboard();
}

// Dropdowns read the whole Lists column, so any name added under the last one is accepted.
function setupLists() {
  const ss = SpreadsheetApp.getActive();
  const lists = ss.getSheetByName("Lists"), log = ss.getSheetByName("Expenses");
  lists.getRange("E1").setValue("Add a new name under the last one in a column. The dropdowns, Dashboard and app pick it up.");
  const rule = c => SpreadsheetApp.newDataValidation()
    .requireValueInRange(lists.getRange(c + "2:" + c), true).setAllowInvalid(false).build();
  log.getRange("C2:C" + N).setDataValidation(rule("A"));
  log.getRange("D2:D" + N).setDataValidation(rule("B"));
  log.getRange("E2:E" + N).setDataValidation(rule("C"));
}

// Rebuilds the Dashboard with room for more categories, payment types and users, then draws the charts.
function buildDashboard() {
  const ss = SpreadsheetApp.getActive();
  const d = ss.getSheetByName("Dashboard");
  const today = new Date();
  const month = d.getRange("B4").getValue() || new Date(today.getFullYear(), today.getMonth(), 1);
  d.getCharts().forEach(c => d.removeChart(c));
  d.clear();
  d.setHiddenGridlines(true);
  d.setColumnWidth(1, 230); d.setColumnWidth(2, 130); d.setColumnWidth(3, 100); d.setColumnWidth(4, 30);

  const A = `Expenses!$A$2:$A$${N}`, B = `Expenses!$B$2:$B$${N}`;
  const C = `Expenses!$C$2:$C$${N}`, D = `Expenses!$D$2:$D$${N}`, E = `Expenses!$E$2:$E$${N}`;
  const inMonth = `${A},">="&$B$4,${A},"<"&$B$5`;
  const INR = '"₹"#,##0.00', PCT = '0.0%';
  const NAVY = "#1F3864", BLUE = "#2E75B6", LIGHT = "#DDEBF7", GREY = "#F2F2F2";

  d.getRange("A1").setValue("Expense Tracker").setFontSize(20).setFontWeight("bold").setFontColor(NAVY);
  d.getRange("A2").setValue("Yellow cell is the only input. Everything else is calculated from the Expenses tab.")
    .setFontStyle("italic").setFontColor("#595959");
  d.getRange("A4:A6").setValues([["Month to report (first day)"], ["Next month start"], ["Year for trend"]]);
  d.getRange("A4").setFontWeight("bold");
  d.getRange("B4").setValue(month).setNumberFormat("mmm yyyy").setBackground("#FFF2CC")
    .setFontColor("#0000FF").setFontWeight("bold").setHorizontalAlignment("right");
  d.getRange("B5").setFormula("=EDATE(B4,1)").setNumberFormat("dd-mmm-yyyy").setHorizontalAlignment("right");
  d.getRange("B6").setFormula("=YEAR(B4)").setNumberFormat("0").setHorizontalAlignment("right");

  const section = (r, t) => d.getRange(r, 1, 1, 3).setBackground(BLUE).setFontColor("#FFFFFF").setFontWeight("bold")
    .getCell(1, 1).setValue(t);
  const heads = (r, a, b, c) => d.getRange(r, 1, 1, 3).setValues([[a, b, c]]).setBackground(LIGHT)
    .setFontWeight("bold").setFontColor(NAVY);

  // A table of names from the Lists tab with this month's amount and share.
  function block(top, title, head, listCol, crit, slots) {
    const first = top + 2, last = top + 1 + slots, total = last + 1;
    section(top, title); heads(top + 1, head, "Amount", "Share");
    const f = [];
    for (let i = 0; i < slots; i++) {
      const r = first + i, src = `Lists!${listCol}${i + 2}`;
      f.push([`=IF(${src}="","",${src})`,
              `=IF(A${r}="","",SUMIFS(${B},${crit},A${r},${inMonth}))`,
              `=IF(OR(A${r}="",$B$${total}=0),"",B${r}/$B$${total})`]);
    }
    d.getRange(first, 1, slots, 3).setFormulas(f);
    d.getRange(first, 2, slots, 1).setNumberFormat(INR);
    d.getRange(first, 3, slots, 1).setNumberFormat(PCT);
    d.getRange(total, 1, 1, 3).setFormulas([["Total", `=SUM(B${first}:B${last})`, `=SUM(C${first}:C${last})`]])
      .setFontWeight("bold").setBackground(GREY);
    d.getRange(total, 2).setNumberFormat(INR); d.getRange(total, 3).setNumberFormat(PCT);
    return { top, first, last, total };
  }

  const cat = block(15, "By category", "Category", "A", C, CAT_SLOTS);
  const pay = block(cat.total + 2, "By payment type", "Payment type", "B", D, PAY_SLOTS);
  const usr = block(pay.total + 2, "By user", "User", "C", E, USER_SLOTS);

  // This month summary
  section(8, "This month");
  d.getRange(9, 1, 5, 2).setFormulas([
    ["Total spent", `=SUMIFS(${B},${inMonth})`],
    ["Number of entries", `=COUNTIFS(${A},">="&$B$4,${A},"<"&$B$5)`],
    ["Average per entry", "=IF(B10=0,0,B9/B10)"],
    ["Top category", `=IF(B9=0,"-",INDEX(A${cat.first}:A${cat.last},MATCH(MAX(B${cat.first}:B${cat.last}),B${cat.first}:B${cat.last},0)))`],
    ["All-time total", `=SUM(${B})`]]);
  d.getRange("B9").setNumberFormat(INR); d.getRange("B11").setNumberFormat(INR);
  d.getRange("B13").setNumberFormat(INR); d.getRange("B10").setNumberFormat("0");
  d.getRange("B9:B13").setFontWeight("bold").setHorizontalAlignment("right");

  // Monthly trend for the year in B6
  const tt = usr.total + 2, tf = tt + 2, tl = tf + 11, tTotal = tl + 1;
  section(tt, "Monthly trend"); heads(tt + 1, "Month", "Amount", "Share of year");
  const tr = [];
  for (let i = 0; i < 12; i++) {
    const r = tf + i;
    tr.push([i === 0 ? "=DATE($B$6,1,1)" : `=EDATE(A${r - 1},1)`,
             `=SUMIFS(${B},${A},">="&A${r},${A},"<"&EDATE(A${r},1))`,
             `=IF($B$${tTotal}=0,"",B${r}/$B$${tTotal})`]);
  }
  d.getRange(tf, 1, 12, 3).setFormulas(tr);
  d.getRange(tf, 1, 12, 1).setNumberFormat("mmm yyyy").setHorizontalAlignment("left");
  d.getRange(tf, 2, 12, 1).setNumberFormat(INR); d.getRange(tf, 3, 12, 1).setNumberFormat(PCT);
  d.getRange(tTotal, 1, 1, 3).setFormulas([["Year total", `=SUM(B${tf}:B${tl})`, `=SUM(C${tf}:C${tl})`]])
    .setFontWeight("bold").setBackground(GREY);
  d.getRange(tTotal, 2).setNumberFormat(INR); d.getRange(tTotal, 3).setNumberFormat(PCT);

  d.getDataRange().setFontFamily("Arial");

  // Charts
  const pie = (title, a1, row, col) => d.insertChart(d.newChart()
    .setChartType(Charts.ChartType.PIE).addRange(d.getRange(a1)).setNumHeaders(1)
    .setPosition(row, col, 0, 0).setOption("title", title)
    .setOption("pieSliceText", "percentage").setOption("width", 430).setOption("height", 270).build());
  const bars = (title, a1, row, col, type) => d.insertChart(d.newChart()
    .setChartType(type).addRange(d.getRange(a1)).setNumHeaders(1)
    .setPosition(row, col, 0, 0).setOption("title", title).setOption("legend", { position: "none" })
    .setOption("width", 430).setOption("height", 270).build());
  pie("Spend by category", `A${cat.top + 1}:B${cat.last}`, 4, 5);
  pie("Spend by payment type", `A${pay.top + 1}:B${pay.last}`, 4, 10);
  bars("Spend by user", `A${usr.top + 1}:B${usr.last}`, 20, 5, Charts.ChartType.BAR);
  bars("Monthly spend", `A${tt + 1}:B${tl}`, 20, 10, Charts.ChartType.COLUMN);
}
