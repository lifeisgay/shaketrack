// Paste into Extensions -> Apps Script of the "Expense Tracker" sheet.
// 1) Set your SECRET below. Matches the secret in your mobile app.
// 2) Run setup() once. It creates/resets the Dashboard, Lists, and Expenses tabs with clean charts.
// 3) Deploy -> Manage deployments -> Edit -> Version: New version -> Deploy.

const SECRET = "whatrasudeep";
const N = 5000; // Maximum expense rows evaluated by formulas
const CAT_SLOTS = 12, PAY_SLOTS = 8, USER_SLOTS = 8;

function json(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}

// Mobile app calls this to sync categories, payments, and registered users.
function doGet(e) {
  if (!e || !e.parameter || e.parameter.secret !== SECRET) return json({ error: "unauthorized" });
  const ss = SpreadsheetApp.getActive();
  const l = ss.getSheetByName("Lists") || setupLists();
  const col = c => l.getRange(c + "2:" + c).getValues().map(r => String(r[0]).trim()).filter(String);
  return json({ categories: col("A"), payments: col("B"), users: col("C") });
}

// Mobile app posts each expense entry here.
function doPost(e) {
  try {
    const d = JSON.parse(e.postData.contents);
    if (d.secret !== SECRET) return ContentService.createTextOutput("unauthorized");

    const ss = SpreadsheetApp.getActive();
    let sh = ss.getSheetByName("Expenses");
    if (!sh) {
      sh = ss.insertSheet("Expenses");
      sh.getRange(1, 1, 1, 5).setValues([["Date", "Amount", "Category", "Payment", "User"]]);
      sh.getRange(1, 1, 1, 5).setFontWeight("bold").setBackground("#F1F5F9");
    }

    const user = (d.user && String(d.user).trim()) || "User 1";

    // Auto-register new users into Lists column C if not already present
    const lists = ss.getSheetByName("Lists");
    if (lists) {
      const users = lists.getRange("C2:C").getValues().map(r => String(r[0]).trim()).filter(String);
      if (!users.includes(user)) {
        lists.getRange(users.length + 2, 3).setValue(user);
      }
    }

    // Find next empty row
    const colA = sh.getRange("A:A").getValues();
    let n = colA.length;
    while (n > 0 && (colA[n - 1][0] === "" || colA[n - 1][0] === null)) n--;
    const row = Math.max(n + 1, 2);

    const dateVal = d.ts ? new Date(Number(d.ts)) : new Date();
    sh.getRange(row, 1, 1, 5).setValues([[dateVal, Number(d.amount), d.category, d.payment, user]]);
    sh.getRange(row, 1).setNumberFormat("dd-mmm-yyyy hh:mm");
    sh.getRange(row, 2).setNumberFormat('"₹"#,##0.00');

    return ContentService.createTextOutput("ok");
  } catch (err) {
    return ContentService.createTextOutput("error: " + err.message);
  }
}

function setup() {
  setupLists();
  buildDashboard();
}

function setupLists() {
  const ss = SpreadsheetApp.getActive();
  let lists = ss.getSheetByName("Lists");
  if (!lists) {
    lists = ss.insertSheet("Lists");
    lists.getRange("A1:C1").setValues([["Categories", "Payment Types", "Users"]]).setFontWeight("bold").setBackground("#E2E8F0");
    lists.getRange("A2:A7").setValues([["Fuel"], ["Food"], ["Miscellaneous"], ["Transport"], ["ticket"], ["chok"]]);
    lists.getRange("B2:B4").setValues([["Card"], ["GPay"], ["Cash"]]);
    lists.getRange("C2:C4").setValues([["Aneez"], ["User 1"], ["User 2"]]);
  }

  let log = ss.getSheetByName("Expenses");
  if (!log) {
    log = ss.insertSheet("Expenses");
    log.getRange(1, 1, 1, 5).setValues([["Date", "Amount", "Category", "Payment", "User"]]);
    log.getRange(1, 1, 1, 5).setFontWeight("bold").setBackground("#F1F5F9");
    log.setColumnWidth(1, 160); log.setColumnWidth(2, 120); log.setColumnWidth(3, 140);
    log.setColumnWidth(4, 120); log.setColumnWidth(5, 120);
  }

  // Set soft data validation (allow invalid so new entries aren't rejected)
  const rule = c => SpreadsheetApp.newDataValidation()
    .requireValueInRange(lists.getRange(c + "2:" + c), true).setAllowInvalid(true).build();
  log.getRange("C2:C" + N).setDataValidation(rule("A"));
  log.getRange("D2:D" + N).setDataValidation(rule("B"));
  log.getRange("E2:E" + N).setDataValidation(rule("C"));

  return lists;
}

// Builds a modern, clean financial dashboard with KPI cards, breakdown tables, and sparklines.
function buildDashboard() {
  const ss = SpreadsheetApp.getActive();
  let d = ss.getSheetByName("Dashboard");
  if (!d) d = ss.insertSheet("Dashboard");

  d.getCharts().forEach(c => d.removeChart(c));
  d.clear();
  d.setHiddenGridlines(true);

  // Set clean column widths
  d.setColumnWidth(1, 170); // A: Category / Metric
  d.setColumnWidth(2, 120); // B: Amount
  d.setColumnWidth(3, 85);  // C: Share %
  d.setColumnWidth(4, 120); // D: Sparkline Bar
  d.setColumnWidth(5, 30);  // E: Spacer
  d.setColumnWidth(6, 170); // F: Payment / User
  d.setColumnWidth(7, 120); // G: Amount
  d.setColumnWidth(8, 85);  // H: Share %
  d.setColumnWidth(9, 120); // I: Sparkline Bar
  d.setColumnWidth(10, 30); // J: Spacer
  d.setColumnWidth(11, 460);// K: Charts area

  const INR = '"₹"#,##0.00', PCT = '0.0%';
  const INDIGO = "#4F46E5", SLATE_DARK = "#0F172A", LIGHT_BG = "#F8FAFC", BORDER_COL = "#E2E8F0";

  // --- HEADER SECTION ---
  d.getRange("A1").setValue("⚡ ShakeTrack Financial Dashboard").setFontSize(18).setFontWeight("bold").setFontColor(SLATE_DARK);
  d.getRange("A2").setValue("Automated mobile expense tracker · Real-time summary")
    .setFontStyle("italic").setFontColor("#64748B").setFontSize(10);

  // Month & Year Filter Controls
  d.getRange("A4").setValue("Selected Month:").setFontWeight("bold").setFontColor("#334155");
  d.getRange("B4").setValue(new Date(new Date().getFullYear(), new Date().getMonth(), 1))
    .setNumberFormat("mmmm yyyy").setFontWeight("bold").setBackground("#FEF3C7").setFontColor("#92400E")
    .setHorizontalAlignment("center");
  
  d.getRange("C4").setValue("Year:").setFontWeight("bold").setFontColor("#334155").setHorizontalAlignment("right");
  d.getRange("D4").setFormula("=YEAR(B4)").setFontWeight("bold").setBackground("#F1F5F9").setFontColor(SLATE_DARK)
    .setHorizontalAlignment("center");

  // Helper date limits
  const inMonth = `Expenses!$A$2:$A$${N},">="&$B$4,Expenses!$A$2:$A$${N},"<"&EDATE($B$4,1)`;
  const B = `Expenses!$B$2:$B$${N}`;

  // --- KPI CARDS (Row 6 to 8) ---
  function makeKpiCard(colStart, title, formula, isCurrency) {
    const r1 = d.getRange(6, colStart, 1, 2);
    r1.merge().setValue(title).setFontSize(9).setFontWeight("bold").setFontColor("#64748B").setBackground("#F1F5F9");
    const r2 = d.getRange(7, colStart, 2, 2);
    r2.merge().setFormula(formula).setFontSize(16).setFontWeight("bold").setFontColor(INDIGO)
      .setBackground(LIGHT_BG).setHorizontalAlignment("center").setVerticalAlignment("middle");
    if (isCurrency) r2.setNumberFormat(INR); else r2.setNumberFormat("#,##0");
    d.getRange(6, colStart, 3, 2).setBorder(true, true, true, true, false, false, BORDER_COL, SpreadsheetApp.BorderStyle.SOLID);
  }

  makeKpiCard(1, "THIS MONTH'S TOTAL", `=IFERROR(SUMIFS(${B},${inMonth}),0)`, true);
  makeKpiCard(3, "TRANSACTIONS", `=IFERROR(COUNTIFS(${inMonth}),0)`, false);
  makeKpiCard(6, "DAILY AVERAGE", `=IFERROR(A7/DAY(EOMONTH($B$4,0)),0)`, true);
  makeKpiCard(8, "ALL-TIME SPEND", `=IFERROR(SUM(${B}),0)`, true);

  // Helper Table Formatter
  function formatTableHeader(r, c, w, title) {
    const range = d.getRange(r, c, 1, w);
    range.setBackground(INDIGO).setFontColor("#FFFFFF").setFontWeight("bold").getCell(1, 1).setValue(title);
  }

  function formatTableSubHeader(r, c, headers) {
    const range = d.getRange(r, c, 1, headers.length);
    range.setValues([headers]).setBackground("#EEF2FF").setFontWeight("bold").setFontColor("#3730A3");
  }

  // --- TABLE 1: BY CATEGORY (Row 11) ---
  formatTableHeader(11, 1, 4, "Spend by Category");
  formatTableSubHeader(12, 1, ["Category", "Amount", "Share", "Distribution"]);
  const catFormulas = [];
  for (let i = 0; i < CAT_SLOTS; i++) {
    const row = 13 + i, src = `Lists!A${i + 2}`;
    catFormulas.push([
      `=IF(${src}="","",${src})`,
      `=IF(A${row}="","",SUMIFS(${B},Expenses!$C$2:$C$${N},A${row},${inMonth}))`,
      `=IF(OR(A${row}="",$A$7=0),"",B${row}/$A$7)`,
      `=IF(C${row}="","",SPARKLINE(C${row},{"charttype","bar";"max",1;"color1","#4F46E5"}))`
    ]);
  }
  d.getRange(13, 1, CAT_SLOTS, 4).setFormulas(catFormulas);
  d.getRange(13, 2, CAT_SLOTS, 1).setNumberFormat(INR);
  d.getRange(13, 3, CAT_SLOTS, 1).setNumberFormat(PCT);

  const catTotalRow = 13 + CAT_SLOTS;
  d.getRange(catTotalRow, 1, 1, 4).setFormulas([["Total", `=SUM(B13:B${catTotalRow - 1})`, `=SUM(C13:C${catTotalRow - 1})`, ""]])
    .setFontWeight("bold").setBackground("#F1F5F9");
  d.getRange(catTotalRow, 2).setNumberFormat(INR);
  d.getRange(catTotalRow, 3).setNumberFormat(PCT);

  // --- TABLE 2: BY PAYMENT METHOD (Row 11, Cols F-I) ---
  formatTableHeader(11, 6, 4, "Spend by Payment Type");
  formatTableSubHeader(12, 6, ["Payment Type", "Amount", "Share", "Distribution"]);
  const payFormulas = [];
  for (let i = 0; i < PAY_SLOTS; i++) {
    const row = 13 + i, src = `Lists!B${i + 2}`;
    payFormulas.push([
      `=IF(${src}="","",${src})`,
      `=IF(F${row}="","",SUMIFS(${B},Expenses!$D$2:$D$${N},F${row},${inMonth}))`,
      `=IF(OR(F${row}="",$A$7=0),"",G${row}/$A$7)`,
      `=IF(H${row}="","",SPARKLINE(H${row},{"charttype","bar";"max",1;"color1","#0D9488"}))`
    ]);
  }
  d.getRange(13, 6, PAY_SLOTS, 4).setFormulas(payFormulas);
  d.getRange(13, 7, PAY_SLOTS, 1).setNumberFormat(INR);
  d.getRange(13, 8, PAY_SLOTS, 1).setNumberFormat(PCT);

  const payTotalRow = 13 + PAY_SLOTS;
  d.getRange(payTotalRow, 6, 1, 4).setFormulas([["Total", `=SUM(G13:G${payTotalRow - 1})`, `=SUM(H13:H${payTotalRow - 1})`, ""]])
    .setFontWeight("bold").setBackground("#F1F5F9");
  d.getRange(payTotalRow, 7).setNumberFormat(INR);
  d.getRange(payTotalRow, 8).setNumberFormat(PCT);

  // --- TABLE 3: BY USER (Row payTotalRow + 2, Cols F-I) ---
  const userStartRow = payTotalRow + 2;
  formatTableHeader(userStartRow, 6, 4, "Spend by User");
  formatTableSubHeader(userStartRow + 1, 6, ["User Name", "Amount", "Share", "Distribution"]);
  const userFormulas = [];
  for (let i = 0; i < USER_SLOTS; i++) {
    const row = userStartRow + 2 + i, src = `Lists!C${i + 2}`;
    userFormulas.push([
      `=IF(${src}="","",${src})`,
      `=IF(F${row}="","",SUMIFS(${B},Expenses!$E$2:$E$${N},F${row},${inMonth}))`,
      `=IF(OR(F${row}="",$A$7=0),"",G${row}/$A$7)`,
      `=IF(H${row}="","",SPARKLINE(H${row},{"charttype","bar";"max",1;"color1","#EC4899"}))`
    ]);
  }
  d.getRange(userStartRow + 2, 6, USER_SLOTS, 4).setFormulas(userFormulas);
  d.getRange(userStartRow + 2, 7, USER_SLOTS, 1).setNumberFormat(INR);
  d.getRange(userStartRow + 2, 8, USER_SLOTS, 1).setNumberFormat(PCT);

  const userTotalRow = userStartRow + 2 + USER_SLOTS;
  d.getRange(userTotalRow, 6, 1, 4).setFormulas([["Total", `=SUM(G${userStartRow + 2}:G${userTotalRow - 1})`, `=SUM(H${userStartRow + 2}:H${userTotalRow - 1})`, ""]])
    .setFontWeight("bold").setBackground("#F1F5F9");
  d.getRange(userTotalRow, 7).setNumberFormat(INR);
  d.getRange(userTotalRow, 8).setNumberFormat(PCT);

  // --- TABLE 4: 12-MONTH TREND (Row catTotalRow + 2, Cols A-D) ---
  const trendStartRow = catTotalRow + 2;
  formatTableHeader(trendStartRow, 1, 4, "Annual Monthly Trend");
  formatTableSubHeader(trendStartRow + 1, 1, ["Month", "Spend", "Share of Year", "Trend Bar"]);
  const trendFormulas = [];
  for (let i = 0; i < 12; i++) {
    const row = trendStartRow + 2 + i;
    trendFormulas.push([
      i === 0 ? "=DATE($D$4,1,1)" : `=EDATE(A${row - 1},1)`,
      `=SUMIFS(${B},Expenses!$A$2:$A$${N},">="&A${row},Expenses!$A$2:$A$${N},"<"&EDATE(A${row},1))`,
      `=IF(OR(A${row}="",$B$${trendStartRow + 14}=0),"",B${row}/$B$${trendStartRow + 14})`,
      `=IF(C${row}="","",SPARKLINE(C${row},{"charttype","bar";"max",1;"color1","#6366F1"}))`
    ]);
  }
  d.getRange(trendStartRow + 2, 1, 12, 4).setFormulas(trendFormulas);
  d.getRange(trendStartRow + 2, 1, 12, 1).setNumberFormat("mmm yyyy");
  d.getRange(trendStartRow + 2, 2, 12, 1).setNumberFormat(INR);
  d.getRange(trendStartRow + 2, 3, 12, 1).setNumberFormat(PCT);

  const trendTotalRow = trendStartRow + 14;
  d.getRange(trendTotalRow, 1, 1, 4).setFormulas([["Full Year Total", `=SUM(B${trendStartRow + 2}:B${trendTotalRow - 1})`, `=SUM(C${trendStartRow + 2}:C${trendTotalRow - 1})`, ""]])
    .setFontWeight("bold").setBackground("#F1F5F9");
  d.getRange(trendTotalRow, 2).setNumberFormat(INR);
  d.getRange(trendTotalRow, 3).setNumberFormat(PCT);

  // Font styling
  d.getDataRange().setFontFamily("Arial");

  // --- CHARTS (Column K) ---
  try {
    // 1. Category Pie Chart
    const pieChart = d.newChart()
      .setChartType(Charts.ChartType.PIE)
      .addRange(d.getRange(`A12:B${catTotalRow - 1}`))
      .setNumHeaders(1)
      .setPosition(4, 11, 0, 0)
      .setOption("title", "Category Spend Share")
      .setOption("pieHole", 0.4)
      .setOption("width", 480)
      .setOption("height", 290)
      .build();
    d.insertChart(pieChart);

    // 2. Monthly Trend Bar Chart
    const trendChart = d.newChart()
      .setChartType(Charts.ChartType.COLUMN)
      .addRange(d.getRange(`A${trendStartRow + 1}:B${trendTotalRow - 1}`))
      .setNumHeaders(1)
      .setPosition(18, 11, 0, 0)
      .setOption("title", "Monthly Spend Trend")
      .setOption("legend", { position: "none" })
      .setOption("width", 480)
      .setOption("height", 290)
      .build();
    d.insertChart(trendChart);
  } catch (_e) {}
}
