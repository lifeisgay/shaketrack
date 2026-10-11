// ============================================================================
// ShakeTrack: Personal Financial Tracker & Dashboard Backend
// ============================================================================
// 1) Secret key for secure mobile sync:
const SECRET = "whatrasudeep";
const N = 5000; // Maximum data rows evaluated

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

function json(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}

// ----------------------------------------------------------------------------
// GET: Sync categories, payments, and users with the mobile app
// ----------------------------------------------------------------------------
function doGet(e) {
  if (!e || !e.parameter || e.parameter.secret !== SECRET) return json({ error: "unauthorized" });
  const ss = SpreadsheetApp.getActive();
  let l = ss.getSheetByName("Lists");
  if (!l) l = setupLists();

  const col = c => l.getRange(c + "2:" + c).getValues().map(r => String(r[0]).trim()).filter(String);
  return json({
    expense_categories: col("A").length ? col("A") : EXPENSE_CATEGORIES,
    income_categories: col("B").length ? col("B") : INCOME_CATEGORIES,
    categories: col("A").length ? col("A") : EXPENSE_CATEGORIES,
    payments: col("C").length ? col("C") : PAYMENT_METHODS,
    users: col("D").length ? col("D") : ["Aneez", "User 1"]
  });
}

// ----------------------------------------------------------------------------
// POST: Receive new expense or income from the mobile app
// ----------------------------------------------------------------------------
function doPost(e) {
  try {
    const d = JSON.parse(e.postData.contents);
    if (d.secret !== SECRET) return ContentService.createTextOutput("unauthorized");

    const ss = SpreadsheetApp.getActive();
    let sh = ss.getSheetByName("Expenses");
    if (!sh) {
      sh = ss.insertSheet("Expenses");
      sh.getRange(1, 1, 1, 7).setValues([["Date", "Type", "Category", "Amount", "Payment", "User", "Net Amount"]]);
      sh.getRange(1, 1, 1, 7).setFontWeight("bold").setBackground("#F1F5F9");
    }

    const isIncome = d.type === "Income" || INCOME_CATEGORIES.includes(d.category);
    const type = isIncome ? "Income" : "Expense";
    const category = d.category || (isIncome ? "Income" : "Miscellaneous");
    const amount = Math.abs(Number(d.amount) || 0);
    const payment = d.payment || "GPay";
    const user = (d.user && String(d.user).trim()) || "Aneez";
    const dateVal = d.ts ? new Date(Number(d.ts)) : new Date();

    // Auto-register new users into Lists column D if not already present
    const lists = ss.getSheetByName("Lists");
    if (lists) {
      const users = lists.getRange("D2:D").getValues().map(r => String(r[0]).trim()).filter(String);
      if (!users.includes(user)) {
        lists.getRange(users.length + 2, 4).setValue(user);
      }
    }

    // Find next empty row in Expenses
    const colA = sh.getRange("A:A").getValues();
    let n = colA.length;
    while (n > 0 && (colA[n - 1][0] === "" || colA[n - 1][0] === null)) n--;
    const row = Math.max(n + 1, 2);

    sh.getRange(row, 1, 1, 7).setValues([[
      dateVal,
      type,
      category,
      amount,
      payment,
      user,
      isIncome ? amount : -amount
    ]]);

    sh.getRange(row, 1).setNumberFormat("dd-mmm-yyyy hh:mm");
    sh.getRange(row, 4).setNumberFormat('"₹"#,##0.00');
    sh.getRange(row, 7).setNumberFormat('"₹"#,##0.00');

    return ContentService.createTextOutput("ok");
  } catch (err) {
    return ContentService.createTextOutput("error: " + err.message);
  }
}

// ----------------------------------------------------------------------------
// SETUP: Initializer for Lists & Dashboard
// ----------------------------------------------------------------------------
function setup() {
  setupLists();
  buildDashboard();
  buildAnnualDashboard();
}

function setupLists() {
  const ss = SpreadsheetApp.getActive();
  let lists = ss.getSheetByName("Lists");
  if (!lists) lists = ss.insertSheet("Lists");
  lists.clear();

  lists.getRange("A1:D1").setValues([["Expense Categories", "Income Categories", "Payment Methods", "Users"]])
    .setFontWeight("bold").setBackground("#E2E8F0");

  const expRows = EXPENSE_CATEGORIES.map(c => [c]);
  const incRows = INCOME_CATEGORIES.map(c => [c]);
  const payRows = PAYMENT_METHODS.map(p => [p]);
  const usrRows = [["Aneez"], ["User 1"]];

  lists.getRange(2, 1, expRows.length, 1).setValues(expRows);
  lists.getRange(2, 2, incRows.length, 1).setValues(incRows);
  lists.getRange(2, 3, payRows.length, 1).setValues(payRows);
  lists.getRange(2, 4, usrRows.length, 1).setValues(usrRows);

  let log = ss.getSheetByName("Expenses");
  if (!log) {
    log = ss.insertSheet("Expenses");
    log.getRange(1, 1, 1, 7).setValues([["Date", "Type", "Category", "Amount", "Payment", "User", "Net Amount"]]);
    log.getRange(1, 1, 1, 7).setFontWeight("bold").setBackground("#F1F5F9");
    log.setColumnWidth(1, 150); log.setColumnWidth(2, 100); log.setColumnWidth(3, 140);
    log.setColumnWidth(4, 110); log.setColumnWidth(5, 110); log.setColumnWidth(6, 110); log.setColumnWidth(7, 120);
  }

  // Soft validation
  const rule = c => SpreadsheetApp.newDataValidation()
    .requireValueInRange(lists.getRange(c + "2:" + c), true).setAllowInvalid(true).build();
  log.getRange("C2:C" + N).setDataValidation(rule("A"));
  log.getRange("E2:E" + N).setDataValidation(rule("C"));
  log.getRange("F2:F" + N).setDataValidation(rule("D"));

  return lists;
}

// ----------------------------------------------------------------------------
// MONTHLY DASHBOARD (Matching the visual card template in the screenshot)
// ----------------------------------------------------------------------------
function buildDashboard() {
  const ss = SpreadsheetApp.getActive();
  let d = ss.getSheetByName("Dashboard");
  if (!d) d = ss.insertSheet("Dashboard");

  d.getCharts().forEach(c => d.removeChart(c));
  d.clear();
  d.setHiddenGridlines(true);

  // Column widths
  d.setColumnWidth(1, 40);  // A: Margin
  d.setColumnWidth(2, 150); // B: Cat name (Expenses)
  d.setColumnWidth(3, 70);  // C: %
  d.setColumnWidth(4, 110); // D: Amount
  d.setColumnWidth(5, 100); // E: Bar
  d.setColumnWidth(6, 30);  // F: Spacer
  d.setColumnWidth(7, 340); // G: Donut Chart Area (Expenses)
  d.setColumnWidth(8, 30);  // H: Spacer
  d.setColumnWidth(9, 140); // I: Cat name (Income)
  d.setColumnWidth(10, 70); // J: %
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
  d.getRange("M7:N7").merge().setFormula(`=SPARKLINE(B7,{"charttype","bar";"max",MAX(B7,E7,1);"color1","${BLUE}"})`);
  d.getRange("L8").setValue("Expenses").setFontSize(9).setFontWeight("bold").setFontColor(RED);
  d.getRange("M8:N8").merge().setFormula(`=SPARKLINE(E7,{"charttype","bar";"max",MAX(B7,E7,1);"color1","${RED}"})`);
  d.getRange("L9").setValue("Savings").setFontSize(9).setFontWeight("bold").setFontColor(GREEN);
  d.getRange("M9:N9").merge().setFormula(`=SPARKLINE(MAX(0,I7),{"charttype","bar";"max",MAX(B7,E7,1);"color1","${GREEN}"})`);
  d.getRange("L6:N9").setBorder(true, true, true, true, false, false, BORDER_COL, SpreadsheetApp.BorderStyle.SOLID);

  // --- SECTION: EXPENSES TABLE & DONUT CHART (Row 12) ---
  d.getRange("B12:E12").merge().setValue("EXPENSES BREAKDOWN").setBackground("#991B1B").setFontColor("#FFFFFF").setFontWeight("bold");
  d.getRange("B13:E13").setValues([["Category", "%", "Amount", "Share"]]).setBackground("#FEE2E2").setFontColor("#991B1B").setFontWeight("bold");

  const expFormulas = [];
  for (let i = 0; i < EXPENSE_CATEGORIES.length; i++) {
    const r = 14 + i;
    const cat = EXPENSE_CATEGORIES[i];
    expFormulas.push([
      cat,
      `=IF($E$7=0,0,D${r}/$E$7)`,
      `=IFERROR(SUMIFS(${amtCol},${typeCol},"Expense",${catCol},B${r},${inMonth}),0)`,
      `=IF(D${r}=0,"",SPARKLINE(C${r},{"charttype","bar";"max",1;"color1","${RED}"}))`
    ]);
  }
  d.getRange(14, 2, EXPENSE_CATEGORIES.length, 4).setFormulas(expFormulas);
  d.getRange(14, 3, EXPENSE_CATEGORIES.length, 1).setNumberFormat(PCT);
  d.getRange(14, 4, EXPENSE_CATEGORIES.length, 1).setNumberFormat(INR);

  const expTotalRow = 14 + EXPENSE_CATEGORIES.length;
  d.getRange(expTotalRow, 2, 1, 4).setFormulas([["TOTAL EXPENSES", `=SUM(C14:C${expTotalRow - 1})`, `=SUM(D14:D${expTotalRow - 1})`, ""]])
    .setFontWeight("bold").setBackground("#FEE2E2").setFontColor("#991B1B");
  d.getRange(expTotalRow, 3).setNumberFormat(PCT);
  d.getRange(expTotalRow, 4).setNumberFormat(INR);

  // --- SECTION: INCOME TABLE & DONUT CHART (Row 12, Cols I-L) ---
  d.getRange("I12:L12").merge().setValue("INCOME BREAKDOWN").setBackground("#1E40AF").setFontColor("#FFFFFF").setFontWeight("bold");
  d.getRange("I13:L13").setValues([["Category", "%", "Amount", "Share"]]).setBackground("#DBEAFE").setFontColor("#1E40AF").setFontWeight("bold");

  const incFormulas = [];
  for (let i = 0; i < INCOME_CATEGORIES.length; i++) {
    const r = 14 + i;
    const cat = INCOME_CATEGORIES[i];
    incFormulas.push([
      cat,
      `=IF($B$7=0,0,K${r}/$B$7)`,
      `=IFERROR(SUMIFS(${amtCol},${typeCol},"Income",${catCol},I${r},${inMonth}),0)`,
      `=IF(K${r}=0,"",SPARKLINE(J${r},{"charttype","bar";"max",1;"color1","${BLUE}"}))`
    ]);
  }
  d.getRange(14, 9, INCOME_CATEGORIES.length, 4).setFormulas(incFormulas);
  d.getRange(14, 10, INCOME_CATEGORIES.length, 1).setNumberFormat(PCT);
  d.getRange(14, 11, INCOME_CATEGORIES.length, 1).setNumberFormat(INR);

  const incTotalRow = 14 + INCOME_CATEGORIES.length;
  d.getRange(incTotalRow, 9, 1, 4).setFormulas([["TOTAL INCOME", `=SUM(J14:J${incTotalRow - 1})`, `=SUM(K14:K${incTotalRow - 1})`, ""]])
    .setFontWeight("bold").setBackground("#DBEAFE").setFontColor("#1E40AF");
  d.getRange(incTotalRow, 10).setNumberFormat(PCT);
  d.getRange(incTotalRow, 11).setNumberFormat(INR);

  // Borders for tables
  d.getRange(12, 2, EXPENSE_CATEGORIES.length + 3, 4).setBorder(true, true, true, true, true, true, BORDER_COL, SpreadsheetApp.BorderStyle.SOLID);
  d.getRange(12, 9, INCOME_CATEGORIES.length + 3, 4).setBorder(true, true, true, true, true, true, BORDER_COL, SpreadsheetApp.BorderStyle.SOLID);

  d.getDataRange().setFontFamily("Arial");

  // --- EMBEDDED DONUT CHARTS ---
  try {
    // 1. Expenses Donut Chart (Column G)
    const expChart = d.newChart()
      .setChartType(Charts.ChartType.PIE)
      .addRange(d.getRange(`B13:B${expTotalRow - 1}`))
      .addRange(d.getRange(`D13:D${expTotalRow - 1}`))
      .setNumHeaders(1)
      .setPosition(12, 7, 0, 0)
      .setOption("title", "Expense Distribution")
      .setOption("pieHole", 0.5)
      .setOption("width", 350)
      .setOption("height", 300)
      .setOption("legend", { position: "right", textStyle: { fontSize: 10 } })
      .build();
    d.insertChart(expChart);

    // 2. Income Donut Chart (Column N)
    const incChart = d.newChart()
      .setChartType(Charts.ChartType.PIE)
      .addRange(d.getRange(`I13:I${incTotalRow - 1}`))
      .addRange(d.getRange(`K13:K${incTotalRow - 1}`))
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

// ----------------------------------------------------------------------------
// ANNUAL HORIZONTAL DASHBOARD (Month-by-month full-year matrix)
// ----------------------------------------------------------------------------
function buildAnnualDashboard() {
  const ss = SpreadsheetApp.getActive();
  let a = ss.getSheetByName("Annual View");
  if (!a) a = ss.insertSheet("Annual View");

  a.getCharts().forEach(c => a.removeChart(c));
  a.clear();
  a.setHiddenGridlines(true);

  // Column widths: Col A is Category/Tag, Cols B-M are Jan-Dec, Col N is Total
  a.setColumnWidth(1, 160);
  for (let c = 2; c <= 13; c++) a.setColumnWidth(c, 90);
  a.setColumnWidth(14, 110);

  const INR = '"₹"#,##0.00';
  const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

  // Header Title
  a.getRange("A1:N1").merge().setBackground("#0F172A");
  a.getRange("A1").setValue("📅 ANNUAL FINANCIAL MATRIX (MONTH-BY-MONTH HORIZONTAL VIEW)").setFontColor("#FFFFFF")
    .setFontSize(14).setFontWeight("bold").setVerticalAlignment("middle");

  a.getRange("A3").setValue("Year:").setFontWeight("bold");
  a.getRange("B3").setFormula("=YEAR(Dashboard!C4)").setFontWeight("bold").setBackground("#FEF3C7")
    .setFontColor("#92400E").setHorizontalAlignment("center");

  // Table Column Headers (Row 5)
  a.getRange(5, 1).setValue("Category / Metric").setFontWeight("bold").setBackground("#334155").setFontColor("#FFFFFF");
  for (let i = 0; i < 12; i++) {
    a.getRange(5, i + 2).setValue(MONTHS[i]).setFontWeight("bold").setBackground("#334155").setFontColor("#FFFFFF")
      .setHorizontalAlignment("center");
  }
  a.getRange(5, 14).setValue("Year Total").setFontWeight("bold").setBackground("#1E293B").setFontColor("#FFFFFF")
    .setHorizontalAlignment("center");

  const amtCol = `Expenses!$D$2:$D$${N}`;
  const typeCol = `Expenses!$B$2:$B$${N}`;
  const catCol = `Expenses!$C$2:$C$${N}`;
  const dateCol = `Expenses!$A$2:$A$${N}`;

  let currentRow = 6;

  // --- 1. INCOME SECTION ---
  a.getRange(currentRow, 1, 1, 14).merge().setValue("➕ INCOME (INCOMING)").setBackground("#1E40AF").setFontColor("#FFFFFF")
    .setFontWeight("bold");
  currentRow++;

  const incStartRow = currentRow;
  for (let cat of INCOME_CATEGORIES) {
    a.getRange(currentRow, 1).setValue(cat).setFontWeight("bold").setFontColor("#1E3A8A");
    for (let m = 0; m < 12; m++) {
      const colLetter = String.fromCharCode(66 + m);
      const mStart = `DATE($B$3,${m + 1},1)`;
      const mEnd = `EDATE(${mStart},1)`;
      a.getRange(currentRow, m + 2).setFormula(
        `=IFERROR(SUMIFS(${amtCol},${typeCol},"Income",${catCol},$A${currentRow},${dateCol},">="&${mStart},${dateCol},"<"&${mEnd}),0)`
      );
    }
    a.getRange(currentRow, 14).setFormula(`=SUM(B${currentRow}:M${currentRow})`).setFontWeight("bold");
    currentRow++;
  }
  const incEndRow = currentRow - 1;

  // Total Income Row
  const totalIncRow = currentRow;
  a.getRange(totalIncRow, 1).setValue("TOTAL INCOME").setFontWeight("bold").setBackground("#DBEAFE").setFontColor("#1E40AF");
  for (let m = 0; m < 12; m++) {
    const colLetter = String.fromCharCode(66 + m);
    a.getRange(totalIncRow, m + 2).setFormula(`=SUM(${colLetter}${incStartRow}:${colLetter}${incEndRow})`)
      .setFontWeight("bold").setBackground("#DBEAFE").setFontColor("#1E40AF");
  }
  a.getRange(totalIncRow, 14).setFormula(`=SUM(B${totalIncRow}:M${totalIncRow})`).setFontWeight("bold")
    .setBackground("#BFDBFE").setFontColor("#1E40AF");
  currentRow += 2;

  // --- 2. EXPENDITURES SECTION ---
  a.getRange(currentRow, 1, 1, 14).merge().setValue("➖ EXPENDITURES (OUTGOING)").setBackground("#991B1B").setFontColor("#FFFFFF")
    .setFontWeight("bold");
  currentRow++;

  const expStartRow = currentRow;
  for (let cat of EXPENSE_CATEGORIES) {
    a.getRange(currentRow, 1).setValue(cat).setFontColor("#7F1D1D");
    for (let m = 0; m < 12; m++) {
      const mStart = `DATE($B$3,${m + 1},1)`;
      const mEnd = `EDATE(${mStart},1)`;
      a.getRange(currentRow, m + 2).setFormula(
        `=IFERROR(SUMIFS(${amtCol},${typeCol},"Expense",${catCol},$A${currentRow},${dateCol},">="&${mStart},${dateCol},"<"&${mEnd}),0)`
      );
    }
    a.getRange(currentRow, 14).setFormula(`=SUM(B${currentRow}:M${currentRow})`).setFontWeight("bold");
    currentRow++;
  }
  const expEndRow = currentRow - 1;

  // Total Expenses Row
  const totalExpRow = currentRow;
  a.getRange(totalExpRow, 1).setValue("TOTAL EXPENDITURE").setFontWeight("bold").setBackground("#FEE2E2").setFontColor("#991B1B");
  for (let m = 0; m < 12; m++) {
    const colLetter = String.fromCharCode(66 + m);
    a.getRange(totalExpRow, m + 2).setFormula(`=SUM(${colLetter}${expStartRow}:${colLetter}${expEndRow})`)
      .setFontWeight("bold").setBackground("#FEE2E2").setFontColor("#991B1B");
  }
  a.getRange(totalExpRow, 14).setFormula(`=SUM(B${totalExpRow}:M${totalExpRow})`).setFontWeight("bold")
    .setBackground("#FECACA").setFontColor("#991B1B");
  currentRow += 2;

  // --- 3. SUMMARY: NET SAVINGS & REMAINING ---
  a.getRange(currentRow, 1, 1, 14).merge().setValue("📊 NET SUMMARY (SAVINGS & REMAINING)").setBackground("#14532D").setFontColor("#FFFFFF")
    .setFontWeight("bold");
  currentRow++;

  const netRow = currentRow;
  a.getRange(netRow, 1).setValue("NET REMAINING").setFontWeight("bold").setBackground("#DCFCE7").setFontColor("#15803D");
  for (let m = 0; m < 12; m++) {
    const colLetter = String.fromCharCode(66 + m);
    a.getRange(netRow, m + 2).setFormula(`=${colLetter}${totalIncRow}-${colLetter}${totalExpRow}`)
      .setFontWeight("bold").setBackground("#DCFCE7").setFontColor("#15803D");
  }
  a.getRange(netRow, 14).setFormula(`=B${netRow}+C${netRow}+D${netRow}+E${netRow}+F${netRow}+G${netRow}+H${netRow}+I${netRow}+J${netRow}+K${netRow}+L${netRow}+M${netRow}`)
    .setFontWeight("bold").setBackground("#BBF7D0").setFontColor("#15803D");
  currentRow++;

  const pctRow = currentRow;
  a.getRange(pctRow, 1).setValue("SAVINGS RATE %").setFontWeight("bold").setBackground("#F0FDF4").setFontColor("#15803D");
  for (let m = 0; m < 12; m++) {
    const colLetter = String.fromCharCode(66 + m);
    a.getRange(pctRow, m + 2).setFormula(`=IF(${colLetter}${totalIncRow}=0,"0.0%",${colLetter}${netRow}/${colLetter}${totalIncRow})`)
      .setFontWeight("bold").setBackground("#F0FDF4").setFontColor("#15803D").setNumberFormat("0.0%");
  }
  a.getRange(pctRow, 14).setFormula(`=IF(N${totalIncRow}=0,"0.0%",N${netRow}/N${totalIncRow})`)
    .setFontWeight("bold").setBackground("#DCFCE7").setFontColor("#15803D").setNumberFormat("0.0%");

  // Format currency across all data rows
  a.getRange(6, 2, currentRow - 6, 13).setNumberFormat(INR);
  a.getRange(5, 1, currentRow - 4, 14).setBorder(true, true, true, true, true, true, "#E2E8F0", SpreadsheetApp.BorderStyle.SOLID);
  a.getDataRange().setFontFamily("Arial");
}
