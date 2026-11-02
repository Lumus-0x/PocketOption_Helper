/**
 * Pocket Option Helper → Google Sheets
 * Лист: Plan_30_days
 * Приложение присылает: { date: "DD.MM.YYYY", amount: 1234.56, accountType: "REAL" }
 *
 * Как подключить — см. README.md, раздел «Шаг 2. Добавь Apps Script».
 */
function doPost(e) {
  try {
    const data = JSON.parse(e.postData.contents);
    const sheet = SpreadsheetApp.getActiveSpreadsheet().getSheetByName('Plan_30_days');

    if (!sheet) {
      return ContentService.createTextOutput('Error: Sheet "Plan_30_days" not found');
    }

    const amount = Number(data.amount);

    // Проверка соединения из приложения присылает amount = 0.
    // Ничего не записываем, но отвечаем OK, чтобы тест прошёл.
    if (!isFinite(amount) || amount <= 0) {
      return ContentService.createTextOutput('OK: test');
    }

    const firstRow = 6;
    const lastRow = 35;
    const timeZone = SpreadsheetApp.getActiveSpreadsheet().getSpreadsheetTimeZone();
    const dates = sheet.getRange(`B${firstRow}:B${lastRow}`).getValues().flat();
    const target = String(data.date).trim();

    const index = dates.findIndex(value => {
      if (!value) return false;
      const d = value instanceof Date ? value : new Date(value);
      if (isNaN(d.getTime())) return false;
      const formatted = Utilities.formatDate(d, timeZone, 'dd.MM.yyyy');
      return formatted === target;
    });

    if (index === -1) {
      return ContentService.createTextOutput('Date not found: ' + target);
    }

    const row = firstRow + index;
    sheet.getRange(`E${row}`).setValue(amount);
    SpreadsheetApp.flush();

    return ContentService.createTextOutput(`OK: E${row} = ${amount}`);
  } catch (err) {
    return ContentService.createTextOutput('Error: ' + err.message);
  }
}

// Необязательно: быстрая проверка, что Web App жив.
function doGet() {
  return ContentService.createTextOutput('Pocket Option Helper: OK');
}
