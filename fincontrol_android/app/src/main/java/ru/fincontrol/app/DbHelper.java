package ru.fincontrol.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DbHelper extends SQLiteOpenHelper {
    public static final String DB_NAME = "fincontrol.db";
    public static final int DB_VERSION = 1;

    public DbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE accounts (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL," +
                "initial_balance REAL NOT NULL DEFAULT 0," +
                "created_at TEXT NOT NULL)");

        db.execSQL("CREATE TABLE categories (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL," +
                "type TEXT NOT NULL CHECK(type IN ('income','expense')))");

        db.execSQL("CREATE TABLE transactions (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "type TEXT NOT NULL CHECK(type IN ('income','expense'))," +
                "account_id INTEGER NOT NULL," +
                "category TEXT NOT NULL," +
                "note TEXT," +
                "amount REAL NOT NULL CHECK(amount >= 0)," +
                "occurred_at TEXT NOT NULL," +
                "FOREIGN KEY(account_id) REFERENCES accounts(id) ON DELETE RESTRICT)");

        db.execSQL("CREATE TABLE goals (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "title TEXT NOT NULL," +
                "target_amount REAL NOT NULL," +
                "saved_amount REAL NOT NULL DEFAULT 0," +
                "target_date TEXT)");

        db.execSQL("CREATE TABLE recurring (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "title TEXT NOT NULL," +
                "amount REAL NOT NULL," +
                "day_of_month INTEGER NOT NULL," +
                "category TEXT NOT NULL," +
                "active INTEGER NOT NULL DEFAULT 1)");

        db.execSQL("CREATE TABLE settings (" +
                "key TEXT PRIMARY KEY," +
                "value TEXT NOT NULL)");

        seedDefaults(db);
    }

    private void seedDefaults(SQLiteDatabase db) {
        String[] expense = {"Продукты", "Транспорт", "Жильё", "Здоровье", "Подписки", "Развлечения", "Покупки", "Образование", "Другое"};
        String[] income = {"Зарплата", "Подработка", "Возврат", "Подарок", "Другое"};
        for (String c : expense) insertCategory(db, c, "expense");
        for (String c : income) insertCategory(db, c, "income");
        setSetting(db, "monthly_budget", "0");
    }

    private void insertCategory(SQLiteDatabase db, String name, String type) {
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        cv.put("type", type);
        db.insert("categories", null, cv);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Version 1 is the first public schema.
    }

    public long addAccount(String name, double initialBalance) {
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        cv.put("initial_balance", initialBalance);
        cv.put("created_at", LocalDate.now().toString());
        return getWritableDatabase().insert("accounts", null, cv);
    }

    public List<Account> getAccounts() {
        List<Account> list = new ArrayList<>();
        String sql = "SELECT a.id, a.name, a.initial_balance + COALESCE(SUM(CASE WHEN t.type='income' THEN t.amount ELSE -t.amount END),0) balance " +
                "FROM accounts a LEFT JOIN transactions t ON t.account_id=a.id GROUP BY a.id ORDER BY a.id";
        try (Cursor c = getReadableDatabase().rawQuery(sql, null)) {
            while (c.moveToNext()) {
                list.add(new Account(c.getLong(0), c.getString(1), c.getDouble(2)));
            }
        }
        return list;
    }

    public boolean hasTransactionsForAccount(long accountId) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM transactions WHERE account_id=?", new String[]{String.valueOf(accountId)})) {
            return c.moveToFirst() && c.getInt(0) > 0;
        }
    }

    public void deleteAccount(long accountId) {
        getWritableDatabase().delete("accounts", "id=?", new String[]{String.valueOf(accountId)});
    }

    public long addTransaction(String type, long accountId, String category, String note, double amount, String date) {
        ContentValues cv = new ContentValues();
        cv.put("type", type);
        cv.put("account_id", accountId);
        cv.put("category", category);
        cv.put("note", note == null ? "" : note.trim());
        cv.put("amount", amount);
        cv.put("occurred_at", date);
        return getWritableDatabase().insert("transactions", null, cv);
    }

    public void deleteTransaction(long id) {
        getWritableDatabase().delete("transactions", "id=?", new String[]{String.valueOf(id)});
    }

    public List<TransactionRow> getTransactions(int limit) {
        List<TransactionRow> list = new ArrayList<>();
        String sql = "SELECT t.id,t.type,t.account_id,a.name,t.category,t.note,t.amount,t.occurred_at " +
                "FROM transactions t JOIN accounts a ON a.id=t.account_id " +
                "ORDER BY t.occurred_at DESC,t.id DESC LIMIT ?";
        try (Cursor c = getReadableDatabase().rawQuery(sql, new String[]{String.valueOf(limit)})) {
            while (c.moveToNext()) {
                list.add(new TransactionRow(
                        c.getLong(0), c.getString(1), c.getLong(2), c.getString(3),
                        c.getString(4), c.getString(5), c.getDouble(6), c.getString(7)));
            }
        }
        return list;
    }

    public List<String> getCategories(String type) {
        List<String> list = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT name FROM categories WHERE type=? ORDER BY id", new String[]{type})) {
            while (c.moveToNext()) list.add(c.getString(0));
        }
        return list;
    }

    public double totalBalance() {
        String sql = "SELECT COALESCE(SUM(initial_balance),0) + COALESCE((SELECT SUM(CASE WHEN type='income' THEN amount ELSE -amount END) FROM transactions),0) FROM accounts";
        try (Cursor c = getReadableDatabase().rawQuery(sql, null)) {
            return c.moveToFirst() ? c.getDouble(0) : 0;
        }
    }

    public double sumForMonth(String type, YearMonth month) {
        String from = month.atDay(1).toString();
        String to = month.atEndOfMonth().toString();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COALESCE(SUM(amount),0) FROM transactions WHERE type=? AND occurred_at BETWEEN ? AND ?",
                new String[]{type, from, to})) {
            return c.moveToFirst() ? c.getDouble(0) : 0;
        }
    }

    public Map<String, Double> expensesByCategory(YearMonth month) {
        LinkedHashMap<String, Double> map = new LinkedHashMap<>();
        String from = month.atDay(1).toString();
        String to = month.atEndOfMonth().toString();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT category,SUM(amount) s FROM transactions WHERE type='expense' AND occurred_at BETWEEN ? AND ? GROUP BY category ORDER BY s DESC",
                new String[]{from, to})) {
            while (c.moveToNext()) map.put(c.getString(0), c.getDouble(1));
        }
        return map;
    }

    public List<MonthSummary> getSixMonthSummary() {
        List<MonthSummary> out = new ArrayList<>();
        YearMonth now = YearMonth.now();
        for (int i = 5; i >= 0; i--) {
            YearMonth m = now.minusMonths(i);
            out.add(new MonthSummary(m, sumForMonth("income", m), sumForMonth("expense", m)));
        }
        return out;
    }

    public long addGoal(String title, double target, double saved, String targetDate) {
        ContentValues cv = new ContentValues();
        cv.put("title", title);
        cv.put("target_amount", target);
        cv.put("saved_amount", saved);
        cv.put("target_date", targetDate == null ? "" : targetDate);
        return getWritableDatabase().insert("goals", null, cv);
    }

    public void addGoalContribution(long goalId, double amount) {
        getWritableDatabase().execSQL("UPDATE goals SET saved_amount = MIN(target_amount, saved_amount + ?) WHERE id=?", new Object[]{amount, goalId});
    }

    public void deleteGoal(long id) {
        getWritableDatabase().delete("goals", "id=?", new String[]{String.valueOf(id)});
    }

    public List<Goal> getGoals() {
        List<Goal> list = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,title,target_amount,saved_amount,target_date FROM goals ORDER BY id DESC", null)) {
            while (c.moveToNext()) {
                list.add(new Goal(c.getLong(0), c.getString(1), c.getDouble(2), c.getDouble(3), c.getString(4)));
            }
        }
        return list;
    }

    public double totalReservedGoals() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COALESCE(SUM(saved_amount),0) FROM goals", null)) {
            return c.moveToFirst() ? c.getDouble(0) : 0;
        }
    }

    public long addRecurring(String title, double amount, int dayOfMonth, String category) {
        ContentValues cv = new ContentValues();
        cv.put("title", title);
        cv.put("amount", amount);
        cv.put("day_of_month", dayOfMonth);
        cv.put("category", category);
        cv.put("active", 1);
        return getWritableDatabase().insert("recurring", null, cv);
    }

    public void deleteRecurring(long id) {
        getWritableDatabase().delete("recurring", "id=?", new String[]{String.valueOf(id)});
    }

    public List<Recurring> getRecurring() {
        List<Recurring> list = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,title,amount,day_of_month,category,active FROM recurring ORDER BY day_of_month,id", null)) {
            while (c.moveToNext()) {
                list.add(new Recurring(c.getLong(0), c.getString(1), c.getDouble(2), c.getInt(3), c.getString(4), c.getInt(5) == 1));
            }
        }
        return list;
    }

    public double plannedRecurringRemainingThisMonth() {
        LocalDate today = LocalDate.now();
        double sum = 0;
        for (Recurring r : getRecurring()) {
            if (!r.active) continue;
            int actualDay = Math.min(r.dayOfMonth, today.lengthOfMonth());
            if (actualDay >= today.getDayOfMonth()) sum += r.amount;
        }
        return sum;
    }

    public void setSetting(String key, String value) {
        setSetting(getWritableDatabase(), key, value);
    }

    private void setSetting(SQLiteDatabase db, String key, String value) {
        ContentValues cv = new ContentValues();
        cv.put("key", key);
        cv.put("value", value);
        db.insertWithOnConflict("settings", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public String getSetting(String key, String defaultValue) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT value FROM settings WHERE key=?", new String[]{key})) {
            return c.moveToFirst() ? c.getString(0) : defaultValue;
        }
    }

    public double getMonthlyBudget() {
        try {
            return Double.parseDouble(getSetting("monthly_budget", "0"));
        } catch (Exception e) {
            return 0;
        }
    }

    public double safeDailyLimit() {
        LocalDate today = LocalDate.now();
        double balance = totalBalance();
        double reserved = totalReservedGoals();
        double planned = plannedRecurringRemainingThisMonth();
        double freeCash = Math.max(0, balance - reserved - planned);
        double monthlyBudget = getMonthlyBudget();
        if (monthlyBudget > 0) {
            double spent = sumForMonth("expense", YearMonth.now());
            freeCash = Math.min(freeCash, Math.max(0, monthlyBudget - spent));
        }
        int daysLeft = today.lengthOfMonth() - today.getDayOfMonth() + 1;
        return daysLeft <= 0 ? 0 : freeCash / daysLeft;
    }

    public String exportJson() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("format", "fincontrol-backup");
        root.put("version", 1);
        root.put("created_at", java.time.LocalDateTime.now().toString());
        root.put("accounts", tableToJson("accounts", new String[]{"id","name","initial_balance","created_at"}));
        root.put("categories", tableToJson("categories", new String[]{"id","name","type"}));
        root.put("transactions", tableToJson("transactions", new String[]{"id","type","account_id","category","note","amount","occurred_at"}));
        root.put("goals", tableToJson("goals", new String[]{"id","title","target_amount","saved_amount","target_date"}));
        root.put("recurring", tableToJson("recurring", new String[]{"id","title","amount","day_of_month","category","active"}));
        root.put("settings", tableToJson("settings", new String[]{"key","value"}));
        return root.toString(2);
    }

    private JSONArray tableToJson(String table, String[] columns) throws JSONException {
        JSONArray arr = new JSONArray();
        try (Cursor c = getReadableDatabase().query(table, columns, null, null, null, null, null)) {
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                for (int i = 0; i < columns.length; i++) {
                    int type = c.getType(i);
                    if (type == Cursor.FIELD_TYPE_INTEGER) o.put(columns[i], c.getLong(i));
                    else if (type == Cursor.FIELD_TYPE_FLOAT) o.put(columns[i], c.getDouble(i));
                    else if (type == Cursor.FIELD_TYPE_NULL) o.put(columns[i], JSONObject.NULL);
                    else o.put(columns[i], c.getString(i));
                }
                arr.put(o);
            }
        }
        return arr;
    }

    public void importJson(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        if (!"fincontrol-backup".equals(root.optString("format"))) {
            throw new JSONException("Неизвестный формат резервной копии");
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("transactions", null, null);
            db.delete("accounts", null, null);
            db.delete("categories", null, null);
            db.delete("goals", null, null);
            db.delete("recurring", null, null);
            db.delete("settings", null, null);

            importArray(db, "accounts", root.getJSONArray("accounts"), new String[]{"id","name","initial_balance","created_at"});
            importArray(db, "categories", root.getJSONArray("categories"), new String[]{"id","name","type"});
            importArray(db, "transactions", root.getJSONArray("transactions"), new String[]{"id","type","account_id","category","note","amount","occurred_at"});
            importArray(db, "goals", root.getJSONArray("goals"), new String[]{"id","title","target_amount","saved_amount","target_date"});
            importArray(db, "recurring", root.getJSONArray("recurring"), new String[]{"id","title","amount","day_of_month","category","active"});
            importArray(db, "settings", root.getJSONArray("settings"), new String[]{"key","value"});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private void importArray(SQLiteDatabase db, String table, JSONArray arr, String[] columns) throws JSONException {
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            ContentValues cv = new ContentValues();
            for (String col : columns) {
                if (!o.has(col) || o.isNull(col)) {
                    cv.putNull(col);
                } else {
                    Object v = o.get(col);
                    if (v instanceof Integer || v instanceof Long) cv.put(col, ((Number) v).longValue());
                    else if (v instanceof Double || v instanceof Float) cv.put(col, ((Number) v).doubleValue());
                    else cv.put(col, String.valueOf(v));
                }
            }
            db.insertOrThrow(table, null, cv);
        }
    }

    public String exportTransactionsCsv() {
        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF');
        sb.append("Дата;Тип;Счёт;Категория;Комментарий;Сумма\n");
        String sql = "SELECT t.occurred_at,t.type,a.name,t.category,t.note,t.amount FROM transactions t JOIN accounts a ON a.id=t.account_id ORDER BY t.occurred_at,t.id";
        try (Cursor c = getReadableDatabase().rawQuery(sql, null)) {
            while (c.moveToNext()) {
                sb.append(csv(c.getString(0))).append(';')
                        .append(csv("income".equals(c.getString(1)) ? "Доход" : "Расход")).append(';')
                        .append(csv(c.getString(2))).append(';')
                        .append(csv(c.getString(3))).append(';')
                        .append(csv(c.getString(4))).append(';')
                        .append(String.format(java.util.Locale.US, "%.2f", c.getDouble(5)))
                        .append('\n');
            }
        }
        return sb.toString();
    }

    private String csv(String s) {
        if (s == null) s = "";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    public static class Account {
        public final long id;
        public final String name;
        public final double balance;
        public Account(long id, String name, double balance) {
            this.id = id; this.name = name; this.balance = balance;
        }
        @Override public String toString() { return name; }
    }

    public static class TransactionRow {
        public final long id;
        public final String type;
        public final long accountId;
        public final String accountName;
        public final String category;
        public final String note;
        public final double amount;
        public final String date;
        public TransactionRow(long id, String type, long accountId, String accountName, String category, String note, double amount, String date) {
            this.id=id; this.type=type; this.accountId=accountId; this.accountName=accountName; this.category=category; this.note=note; this.amount=amount; this.date=date;
        }
    }

    public static class Goal {
        public final long id;
        public final String title;
        public final double target;
        public final double saved;
        public final String targetDate;
        public Goal(long id, String title, double target, double saved, String targetDate) {
            this.id=id; this.title=title; this.target=target; this.saved=saved; this.targetDate=targetDate == null ? "" : targetDate;
        }
    }

    public static class Recurring {
        public final long id;
        public final String title;
        public final double amount;
        public final int dayOfMonth;
        public final String category;
        public final boolean active;
        public Recurring(long id, String title, double amount, int dayOfMonth, String category, boolean active) {
            this.id=id; this.title=title; this.amount=amount; this.dayOfMonth=dayOfMonth; this.category=category; this.active=active;
        }
    }

    public static class MonthSummary {
        public final YearMonth month;
        public final double income;
        public final double expense;
        public MonthSummary(YearMonth month, double income, double expense) {
            this.month=month; this.income=income; this.expense=expense;
        }
    }
}
