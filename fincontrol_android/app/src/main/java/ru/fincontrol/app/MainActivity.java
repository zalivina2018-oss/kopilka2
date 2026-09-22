package ru.fincontrol.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int C_BG = Color.rgb(245, 247, 251);
    private static final int C_CARD = Color.WHITE;
    private static final int C_TEXT = Color.rgb(24, 32, 51);
    private static final int C_MUTED = Color.rgb(112, 122, 145);
    private static final int C_PRIMARY = Color.rgb(47, 107, 255);
    private static final int C_GREEN = Color.rgb(25, 154, 105);
    private static final int C_RED = Color.rgb(223, 73, 83);
    private static final int C_LINE = Color.rgb(228, 233, 241);

    private static final int REQ_EXPORT_CSV = 1001;
    private static final int REQ_EXPORT_BACKUP = 1002;
    private static final int REQ_IMPORT_BACKUP = 1003;

    private DbHelper db;
    private FrameLayout content;
    private LinearLayout nav;
    private int activeTab = 0;
    private String pendingWriteContent = null;

    private final Locale ru = new Locale("ru", "RU");
    private final NumberFormat money = NumberFormat.getCurrencyInstance(ru);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        db = new DbHelper(this);

        Window w = getWindow();
        w.setStatusBarColor(C_BG);
        w.setNavigationBarColor(Color.WHITE);
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setPadding(0, dp(6), 0, 0);
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                v.setPadding(0, insets.getSystemWindowInsetTop() + dp(6), 0, insets.getSystemWindowInsetBottom());
                return insets;
            });
        }

        root.addView(buildTopBar(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        nav = buildBottomNav();
        root.addView(nav, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)));

        setContentView(root);
        showTab(0);
    }

    private View buildTopBar() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(12));

        TextView title = text("Копилка", 26, C_TEXT, true);
        box.addView(title);
        TextView sub = text("Личный бюджет без банковских подключений", 13, C_MUTED, false);
        sub.setPadding(0, dp(2), 0, 0);
        box.addView(sub);
        return box;
    }

    private LinearLayout buildBottomNav() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(dp(8), dp(6), dp(8), dp(6));
        bar.setBackgroundColor(Color.WHITE);
        String[] labels = {"Главная", "Операции", "Счета", "Цели", "Ещё"};
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView item = text(labels[i], 12, C_MUTED, false);
            item.setGravity(Gravity.CENTER);
            item.setTag("nav_" + i);
            item.setPadding(dp(4), dp(12), dp(4), dp(12));
            item.setOnClickListener(v -> showTab(idx));
            bar.addView(item, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
        return bar;
    }

    private void showTab(int tab) {
        activeTab = tab;
        for (int i = 0; i < nav.getChildCount(); i++) {
            TextView v = (TextView) nav.getChildAt(i);
            boolean active = i == tab;
            v.setTextColor(active ? C_PRIMARY : C_MUTED);
            v.setTypeface(Typeface.DEFAULT, active ? Typeface.BOLD : Typeface.NORMAL);
            v.setBackground(active ? roundRect(Color.rgb(237, 243, 255), 14, 0, 0) : null);
        }
        switch (tab) {
            case 0: renderHome(); break;
            case 1: renderTransactions(); break;
            case 2: renderAccounts(); break;
            case 3: renderGoals(); break;
            default: renderMore(); break;
        }
    }

    private void setPage(View view) {
        content.removeAllViews();
        content.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private ScrollView scrollPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        return scroll;
    }

    private LinearLayout pageColumn() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(8), dp(16), dp(28));
        return col;
    }

    private void renderHome() {
        ScrollView scroll = scrollPage();
        LinearLayout col = pageColumn();
        scroll.addView(col);

        double daily = db.safeDailyLimit();
        double balance = db.totalBalance();
        double income = db.sumForMonth("income", YearMonth.now());
        double expense = db.sumForMonth("expense", YearMonth.now());
        double reserved = db.totalReservedGoals();
        double planned = db.plannedRecurringRemainingThisMonth();

        LinearLayout hero = card(C_PRIMARY);
        TextView heroLabel = text("МОЖНО ПОТРАТИТЬ СЕГОДНЯ", 12, Color.rgb(221, 231, 255), true);
        hero.addView(heroLabel);
        TextView heroValue = text(fmt(daily), 34, Color.WHITE, true);
        heroValue.setPadding(0, dp(8), 0, dp(8));
        hero.addView(heroValue);
        TextView heroHint = text("С учётом накоплений, оставшихся регулярных платежей и лимита месяца", 13, Color.rgb(225, 234, 255), false);
        hero.addView(heroHint);
        col.addView(hero, lpCard());

        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        stats.setPadding(0, dp(12), 0, 0);
        stats.addView(statCard("Баланс", fmt(balance), C_TEXT), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        stats.addView(spaceH(8));
        stats.addView(statCard("Расходы", fmt(expense), C_RED), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        stats.addView(spaceH(8));
        stats.addView(statCard("Доходы", fmt(income), C_GREEN), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(stats);

        TextView section = sectionTitle("Быстрое действие");
        col.addView(section);
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button expenseBtn = actionButton("− Расход", C_RED);
        expenseBtn.setOnClickListener(v -> showTransactionDialog("expense"));
        Button incomeBtn = actionButton("＋ Доход", C_GREEN);
        incomeBtn.setOnClickListener(v -> showTransactionDialog("income"));
        actions.addView(expenseBtn, new LinearLayout.LayoutParams(0, dp(48), 1f));
        actions.addView(spaceH(10));
        actions.addView(incomeBtn, new LinearLayout.LayoutParams(0, dp(48), 1f));
        col.addView(actions);

        if (reserved > 0 || planned > 0) {
            col.addView(sectionTitle("Резервы"));
            LinearLayout reserveCard = card(C_CARD);
            addKeyValue(reserveCard, "Отложено на цели", fmt(reserved), C_TEXT);
            addDivider(reserveCard);
            addKeyValue(reserveCard, "Платежи до конца месяца", fmt(planned), C_TEXT);
            col.addView(reserveCard, lpCard());
        }

        col.addView(sectionTitle("Счета"));
        List<DbHelper.Account> accounts = db.getAccounts();
        if (accounts.isEmpty()) {
            col.addView(emptyCard("Счетов пока нет", "Добавь первый счёт — карту, наличные или накопительный.", "Добавить счёт", v -> showAccountDialog()));
        } else {
            LinearLayout accountCard = card(C_CARD);
            int n = Math.min(accounts.size(), 3);
            for (int i = 0; i < n; i++) {
                DbHelper.Account a = accounts.get(i);
                addKeyValue(accountCard, a.name, fmt(a.balance), a.balance >= 0 ? C_TEXT : C_RED);
                if (i < n - 1) addDivider(accountCard);
            }
            if (accounts.size() > 3) {
                addDivider(accountCard);
                TextView more = text("Ещё счетов: " + (accounts.size() - 3), 13, C_PRIMARY, true);
                more.setPadding(0, dp(10), 0, 0);
                more.setOnClickListener(v -> showTab(2));
                accountCard.addView(more);
            }
            col.addView(accountCard, lpCard());
        }

        List<DbHelper.TransactionRow> tx = db.getTransactions(5);
        col.addView(sectionTitle("Последние операции"));
        if (tx.isEmpty()) {
            col.addView(emptyCard("Операций пока нет", "Добавь первый расход или доход — дневной лимит пересчитается автоматически.", "Добавить расход", v -> showTransactionDialog("expense")));
        } else {
            LinearLayout txCard = card(C_CARD);
            for (int i = 0; i < tx.size(); i++) {
                txCard.addView(transactionRow(tx.get(i), false));
                if (i < tx.size() - 1) addDivider(txCard);
            }
            col.addView(txCard, lpCard());
        }

        setPage(scroll);
    }

    private void renderTransactions() {
        ScrollView scroll = scrollPage();
        LinearLayout col = pageColumn();
        scroll.addView(col);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = text("Операции", 24, C_TEXT, true);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button add = miniButton("＋ Добавить");
        add.setOnClickListener(v -> showTransactionDialog("expense"));
        top.addView(add);
        col.addView(top);

        List<DbHelper.TransactionRow> list = db.getTransactions(500);
        if (list.isEmpty()) {
            col.addView(emptyCard("История пуста", "Здесь появятся все доходы и расходы.", "Добавить операцию", v -> showTransactionDialog("expense")), lpTop(18));
        } else {
            String lastDate = "";
            for (DbHelper.TransactionRow t : list) {
                if (!t.date.equals(lastDate)) {
                    TextView date = text(prettyDate(t.date), 13, C_MUTED, true);
                    date.setPadding(dp(4), dp(18), dp(4), dp(8));
                    col.addView(date);
                    lastDate = t.date;
                }
                LinearLayout c = card(C_CARD);
                c.setPadding(dp(14), dp(2), dp(14), dp(2));
                c.addView(transactionRow(t, true));
                col.addView(c, lpCardTight());
            }
        }
        setPage(scroll);
    }

    private View transactionRow(DbHelper.TransactionRow t, boolean enableDelete) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));

        TextView badge = text("income".equals(t.type) ? "+" : "−", 22, "income".equals(t.type) ? C_GREEN : C_RED, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(roundRect("income".equals(t.type) ? Color.rgb(232, 247, 240) : Color.rgb(255, 238, 240), 18, 0, 0));
        row.addView(badge, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout middle = new LinearLayout(this);
        middle.setOrientation(LinearLayout.VERTICAL);
        middle.setPadding(dp(12), 0, dp(8), 0);
        TextView name = text(t.category + (t.note == null || t.note.trim().isEmpty() ? "" : " · " + t.note), 15, C_TEXT, true);
        middle.addView(name);
        middle.addView(text(t.accountName + " · " + prettyDate(t.date), 12, C_MUTED, false));
        row.addView(middle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        String sign = "income".equals(t.type) ? "+" : "−";
        TextView amount = text(sign + fmt(t.amount), 15, "income".equals(t.type) ? C_GREEN : C_RED, true);
        amount.setGravity(Gravity.END);
        row.addView(amount);

        if (enableDelete) {
            row.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this)
                        .setTitle("Удалить операцию?")
                        .setMessage(t.category + " · " + fmt(t.amount))
                        .setNegativeButton("Отмена", null)
                        .setPositiveButton("Удалить", (d, w) -> {
                            db.deleteTransaction(t.id);
                            renderTransactions();
                        }).show();
                return true;
            });
        }
        return row;
    }

    private void renderAccounts() {
        ScrollView scroll = scrollPage();
        LinearLayout col = pageColumn();
        scroll.addView(col);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(text("Счета", 24, C_TEXT, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button add = miniButton("＋ Счёт");
        add.setOnClickListener(v -> showAccountDialog());
        top.addView(add);
        col.addView(top);

        LinearLayout total = card(C_PRIMARY);
        total.addView(text("Общий баланс", 13, Color.rgb(221, 231, 255), false));
        TextView value = text(fmt(db.totalBalance()), 30, Color.WHITE, true);
        value.setPadding(0, dp(7), 0, 0);
        total.addView(value);
        col.addView(total, lpTop(18));

        List<DbHelper.Account> list = db.getAccounts();
        if (list.isEmpty()) {
            col.addView(emptyCard("Добавь первый счёт", "Например: Основная карта, Наличные, Накопительный.", "Добавить", v -> showAccountDialog()), lpTop(14));
        } else {
            for (DbHelper.Account a : list) {
                LinearLayout c = card(C_CARD);
                LinearLayout line = new LinearLayout(this);
                line.setOrientation(LinearLayout.HORIZONTAL);
                line.setGravity(Gravity.CENTER_VERTICAL);
                line.addView(text(a.name, 16, C_TEXT, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                line.addView(text(fmt(a.balance), 18, a.balance >= 0 ? C_TEXT : C_RED, true));
                c.addView(line);
                TextView hint = text("Удерживай карточку, чтобы удалить пустой счёт", 11, C_MUTED, false);
                hint.setPadding(0, dp(8), 0, 0);
                c.addView(hint);
                c.setOnLongClickListener(v -> {
                    if (db.hasTransactionsForAccount(a.id)) {
                        toast("Сначала удали операции этого счёта");
                    } else {
                        new AlertDialog.Builder(this)
                                .setTitle("Удалить счёт?")
                                .setMessage(a.name)
                                .setNegativeButton("Отмена", null)
                                .setPositiveButton("Удалить", (d,w) -> { db.deleteAccount(a.id); renderAccounts(); })
                                .show();
                    }
                    return true;
                });
                col.addView(c, lpCard());
            }
        }
        setPage(scroll);
    }

    private void renderGoals() {
        ScrollView scroll = scrollPage();
        LinearLayout col = pageColumn();
        scroll.addView(col);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(text("Накопления", 24, C_TEXT, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button add = miniButton("＋ Цель");
        add.setOnClickListener(v -> showGoalDialog());
        top.addView(add);
        col.addView(top);

        List<DbHelper.Goal> goals = db.getGoals();
        if (goals.isEmpty()) {
            col.addView(emptyCard("Создай финансовую цель", "Отложенные деньги исключаются из суммы, доступной для ежедневных трат.", "Новая цель", v -> showGoalDialog()), lpTop(18));
        } else {
            TextView reserved = text("Уже отложено: " + fmt(db.totalReservedGoals()), 14, C_MUTED, false);
            reserved.setPadding(dp(4), dp(14), 0, dp(4));
            col.addView(reserved);
            for (DbHelper.Goal g : goals) {
                LinearLayout c = card(C_CARD);
                LinearLayout head = new LinearLayout(this);
                head.setOrientation(LinearLayout.HORIZONTAL);
                head.setGravity(Gravity.CENTER_VERTICAL);
                head.addView(text(g.title, 17, C_TEXT, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                double pct = g.target <= 0 ? 0 : Math.min(100, g.saved / g.target * 100.0);
                head.addView(text(Math.round(pct) + "%", 14, C_PRIMARY, true));
                c.addView(head);
                TextView amount = text(fmt(g.saved) + " из " + fmt(g.target), 14, C_MUTED, false);
                amount.setPadding(0, dp(8), 0, dp(8));
                c.addView(amount);
                c.addView(progressBar((float) (pct / 100.0), C_PRIMARY));
                if (!g.targetDate.isEmpty()) {
                    TextView date = text("Цель к " + prettyDate(g.targetDate), 12, C_MUTED, false);
                    date.setPadding(0, dp(8), 0, 0);
                    c.addView(date);
                }
                Button contribute = secondaryButton(g.saved >= g.target ? "Цель достигнута" : "Пополнить цель");
                contribute.setEnabled(g.saved < g.target);
                contribute.setOnClickListener(v -> showGoalContributionDialog(g));
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
                bp.topMargin = dp(12);
                c.addView(contribute, bp);
                c.setOnLongClickListener(v -> {
                    new AlertDialog.Builder(this)
                            .setTitle("Удалить цель?")
                            .setMessage("Отложенная сумма снова станет доступна в дневном лимите.")
                            .setNegativeButton("Отмена", null)
                            .setPositiveButton("Удалить", (d,w) -> { db.deleteGoal(g.id); renderGoals(); })
                            .show();
                    return true;
                });
                col.addView(c, lpCard());
            }
        }
        setPage(scroll);
    }

    private void renderMore() {
        ScrollView scroll = scrollPage();
        LinearLayout col = pageColumn();
        scroll.addView(col);
        col.addView(text("Ещё", 24, C_TEXT, true));
        TextView sub = text("Планирование, аналитика и данные", 13, C_MUTED, false);
        sub.setPadding(0, dp(4), 0, dp(16));
        col.addView(sub);

        col.addView(menuCard("Регулярные платежи", "Аренда, подписки, связь и другие обязательные траты", v -> renderRecurring()), lpCard());
        col.addView(menuCard("Отчёты", "Расходы по категориям и динамика за 6 месяцев", v -> renderReports()), lpCard());
        col.addView(menuCard("Бюджет и резервная копия", "Лимит месяца, CSV, экспорт и восстановление данных", v -> renderSettings()), lpCard());

        LinearLayout formula = card(Color.rgb(237, 243, 255));
        formula.addView(text("Как считается дневной лимит", 15, C_TEXT, true));
        TextView f = text("Свободный баланс = деньги на счетах − накопления − оставшиеся регулярные платежи. Если задан месячный бюджет, дополнительно учитывается его остаток. Свободная сумма делится на количество дней до конца месяца, включая сегодня.", 13, C_MUTED, false);
        f.setPadding(0, dp(8), 0, 0);
        formula.addView(f);
        col.addView(formula, lpTop(10));
        setPage(scroll);
    }

    private void renderRecurring() {
        ScrollView scroll = scrollPage();
        LinearLayout col = pageColumn();
        scroll.addView(col);
        addSubHeader(col, "Регулярные платежи", this::renderMore, "＋ Платёж", v -> showRecurringDialog());

        double remaining = db.plannedRecurringRemainingThisMonth();
        LinearLayout summary = card(C_PRIMARY);
        summary.addView(text("Запланировано до конца месяца", 13, Color.rgb(221,231,255), false));
        TextView sum = text(fmt(remaining), 28, Color.WHITE, true);
        sum.setPadding(0, dp(6), 0, 0);
        summary.addView(sum);
        col.addView(summary, lpTop(16));

        List<DbHelper.Recurring> list = db.getRecurring();
        if (list.isEmpty()) {
            col.addView(emptyCard("Нет регулярных платежей", "Добавь обязательные расходы, чтобы дневной лимит заранее их резервировал.", "Добавить платёж", v -> showRecurringDialog()), lpTop(14));
        } else {
            for (DbHelper.Recurring r : list) {
                LinearLayout c = card(C_CARD);
                LinearLayout line = new LinearLayout(this);
                line.setOrientation(LinearLayout.HORIZONTAL);
                line.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout txt = new LinearLayout(this);
                txt.setOrientation(LinearLayout.VERTICAL);
                txt.addView(text(r.title, 16, C_TEXT, true));
                txt.addView(text(r.category + " · " + r.dayOfMonth + " числа", 12, C_MUTED, false));
                line.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                line.addView(text(fmt(r.amount), 16, C_RED, true));
                c.addView(line);
                c.setOnLongClickListener(v -> {
                    new AlertDialog.Builder(this)
                            .setTitle("Удалить регулярный платёж?")
                            .setMessage(r.title)
                            .setNegativeButton("Отмена", null)
                            .setPositiveButton("Удалить", (d,w) -> { db.deleteRecurring(r.id); renderRecurring(); })
                            .show();
                    return true;
                });
                col.addView(c, lpCard());
            }
        }
        setPage(scroll);
    }

    private void renderReports() {
        ScrollView scroll = scrollPage();
        LinearLayout col = pageColumn();
        scroll.addView(col);
        addSubHeader(col, "Отчёты", this::renderMore, null, null);

        YearMonth month = YearMonth.now();
        double income = db.sumForMonth("income", month);
        double expense = db.sumForMonth("expense", month);
        double net = income - expense;
        double saveRate = income > 0 ? (net / income * 100.0) : 0;

        LinearLayout summary = card(C_CARD);
        TextView m = text(month.getMonth().getDisplayName(TextStyle.FULL_STANDALONE, ru) + " " + month.getYear(), 16, C_TEXT, true);
        summary.addView(m);
        addDivider(summary);
        addKeyValue(summary, "Доходы", fmt(income), C_GREEN);
        addDivider(summary);
        addKeyValue(summary, "Расходы", fmt(expense), C_RED);
        addDivider(summary);
        addKeyValue(summary, "Разница", (net >= 0 ? "+" : "") + fmt(net), net >= 0 ? C_GREEN : C_RED);
        addDivider(summary);
        addKeyValue(summary, "Доля сбережений", String.format(ru, "%.0f%%", saveRate), saveRate >= 0 ? C_PRIMARY : C_RED);
        col.addView(summary, lpTop(16));

        col.addView(sectionTitle("Расходы по категориям"));
        Map<String, Double> byCat = db.expensesByCategory(month);
        if (byCat.isEmpty()) {
            col.addView(emptyCard("Пока нечего анализировать", "Добавь расходы — здесь появится структура трат.", "Добавить расход", v -> showTransactionDialog("expense")));
        } else {
            double max = 0;
            for (double v : byCat.values()) max = Math.max(max, v);
            LinearLayout catCard = card(C_CARD);
            int idx = 0;
            for (Map.Entry<String, Double> e : byCat.entrySet()) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.VERTICAL);
                LinearLayout head = new LinearLayout(this);
                head.setOrientation(LinearLayout.HORIZONTAL);
                head.addView(text(e.getKey(), 14, C_TEXT, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                head.addView(text(fmt(e.getValue()), 14, C_TEXT, true));
                row.addView(head);
                LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8));
                pp.topMargin = dp(8);
                row.addView(progressBar((float) (max <= 0 ? 0 : e.getValue() / max), C_PRIMARY), pp);
                catCard.addView(row);
                if (idx++ < byCat.size() - 1) addDivider(catCard);
            }
            col.addView(catCard, lpCard());
        }

        col.addView(sectionTitle("Последние 6 месяцев"));
        LinearLayout trend = card(C_CARD);
        List<DbHelper.MonthSummary> months = db.getSixMonthSummary();
        for (int i = 0; i < months.size(); i++) {
            DbHelper.MonthSummary s = months.get(i);
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            String label = s.month.getMonth().getDisplayName(TextStyle.SHORT, ru) + " " + String.valueOf(s.month.getYear()).substring(2);
            line.addView(text(label, 13, C_MUTED, true), new LinearLayout.LayoutParams(dp(58), ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout vals = new LinearLayout(this);
            vals.setOrientation(LinearLayout.VERTICAL);
            vals.addView(text("+ " + fmt(s.income), 12, C_GREEN, false));
            vals.addView(text("− " + fmt(s.expense), 12, C_RED, false));
            line.addView(vals, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            double netMonth = s.income - s.expense;
            line.addView(text((netMonth >= 0 ? "+" : "") + fmt(netMonth), 13, netMonth >= 0 ? C_TEXT : C_RED, true));
            trend.addView(line);
            if (i < months.size() - 1) addDivider(trend);
        }
        col.addView(trend, lpCard());
        setPage(scroll);
    }

    private void renderSettings() {
        ScrollView scroll = scrollPage();
        LinearLayout col = pageColumn();
        scroll.addView(col);
        addSubHeader(col, "Бюджет и данные", this::renderMore, null, null);

        LinearLayout budget = card(C_CARD);
        budget.addView(text("Месячный лимит расходов", 16, C_TEXT, true));
        TextView bh = text("Если оставить 0, дневной лимит считается только по фактическому свободному балансу.", 12, C_MUTED, false);
        bh.setPadding(0, dp(6), 0, dp(10));
        budget.addView(bh);
        EditText budgetInput = input("Например, 80000");
        budgetInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        double current = db.getMonthlyBudget();
        if (current > 0) budgetInput.setText(String.valueOf((long) current));
        budget.addView(budgetInput);
        Button save = primaryButton("Сохранить лимит");
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        sp.topMargin = dp(10);
        budget.addView(save, sp);
        save.setOnClickListener(v -> {
            double val = parseAmount(budgetInput.getText().toString());
            db.setSetting("monthly_budget", String.valueOf(Math.max(0, val)));
            toast("Лимит сохранён");
        });
        col.addView(budget, lpTop(16));

        col.addView(sectionTitle("Экспорт и резервная копия"));
        LinearLayout data = card(C_CARD);
        Button csv = secondaryButton("Экспорт операций в CSV");
        csv.setOnClickListener(v -> exportCsv());
        data.addView(csv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
        Button backup = secondaryButton("Создать резервную копию JSON");
        backup.setOnClickListener(v -> exportBackup());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        bp.topMargin = dp(10);
        data.addView(backup, bp);
        Button restore = secondaryButton("Восстановить из JSON");
        restore.setOnClickListener(v -> importBackup());
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        rp.topMargin = dp(10);
        data.addView(restore, rp);
        TextView note = text("Все данные хранятся локально на устройстве. Приложение не требует доступа к банковским счетам и не отправляет финансовые данные на сервер.", 12, C_MUTED, false);
        note.setPadding(0, dp(14), 0, 0);
        data.addView(note);
        col.addView(data, lpCard());

        LinearLayout about = card(Color.rgb(237, 243, 255));
        about.addView(text("Копилка · версия 1.0.0", 15, C_TEXT, true));
        TextView ah = text("Доходы, расходы, счета, цели, регулярные платежи, дневной лимит, отчёты, CSV и резервное копирование — без регистрации.", 12, C_MUTED, false);
        ah.setPadding(0, dp(6), 0, 0);
        about.addView(ah);
        col.addView(about, lpTop(10));
        setPage(scroll);
    }

    private void showTransactionDialog(String defaultType) {
        List<DbHelper.Account> accounts = db.getAccounts();
        if (accounts.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Сначала нужен счёт")
                    .setMessage("Добавь карту, наличные или другой счёт, чтобы записывать операции.")
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Добавить счёт", (d,w) -> showAccountDialog())
                    .show();
            return;
        }

        LinearLayout form = dialogForm();
        Spinner type = spinner(new String[]{"Расход", "Доход"});
        type.setSelection("income".equals(defaultType) ? 1 : 0);
        form.addView(label("Тип операции"));
        form.addView(type);

        EditText amount = input("Сумма");
        amount.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        form.addView(label("Сумма"));
        form.addView(amount);

        Spinner account = new Spinner(this);
        account.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, accounts));
        form.addView(label("Счёт"));
        form.addView(account);

        Spinner category = new Spinner(this);
        form.addView(label("Категория"));
        form.addView(category);

        EditText note = input("Например, супермаркет");
        form.addView(label("Комментарий"));
        form.addView(note);

        EditText date = input("ГГГГ-ММ-ДД");
        date.setText(LocalDate.now().toString());
        date.setFocusable(false);
        date.setOnClickListener(v -> pickDate(date));
        form.addView(label("Дата"));
        form.addView(date);

        Runnable refreshCategories = () -> {
            String dbType = type.getSelectedItemPosition() == 1 ? "income" : "expense";
            List<String> cats = db.getCategories(dbType);
            category.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, cats));
        };
        type.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { refreshCategories.run(); }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        refreshCategories.run();

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Новая операция")
                .setView(wrapDialog(form))
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", null)
                .create();
        dlg.setOnShowListener(x -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            double val = parseAmount(amount.getText().toString());
            if (val <= 0) { amount.setError("Укажи сумму больше нуля"); return; }
            DbHelper.Account a = (DbHelper.Account) account.getSelectedItem();
            if (a == null || category.getSelectedItem() == null) { toast("Заполни счёт и категорию"); return; }
            String dbType = type.getSelectedItemPosition() == 1 ? "income" : "expense";
            db.addTransaction(dbType, a.id, String.valueOf(category.getSelectedItem()), note.getText().toString(), val, date.getText().toString());
            dlg.dismiss();
            refreshCurrent();
        }));
        dlg.show();
    }

    private void showAccountDialog() {
        LinearLayout form = dialogForm();
        EditText name = input("Например, Основная карта");
        form.addView(label("Название"));
        form.addView(name);
        EditText balance = input("0");
        balance.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        form.addView(label("Текущий баланс"));
        form.addView(balance);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Новый счёт")
                .setView(wrapDialog(form))
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Добавить", null)
                .create();
        dlg.setOnShowListener(x -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String n = name.getText().toString().trim();
            if (n.isEmpty()) { name.setError("Укажи название"); return; }
            db.addAccount(n, parseAmount(balance.getText().toString()));
            dlg.dismiss();
            refreshCurrent();
        }));
        dlg.show();
    }

    private void showGoalDialog() {
        LinearLayout form = dialogForm();
        EditText title = input("Например, Отпуск");
        form.addView(label("Цель")); form.addView(title);
        EditText target = input("100000");
        target.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        form.addView(label("Нужно накопить")); form.addView(target);
        EditText saved = input("0");
        saved.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        form.addView(label("Уже отложено")); form.addView(saved);
        EditText date = input("Необязательно");
        date.setFocusable(false);
        date.setOnClickListener(v -> pickDate(date));
        form.addView(label("Срок")); form.addView(date);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Новая цель")
                .setView(wrapDialog(form))
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Создать", null)
                .create();
        dlg.setOnShowListener(x -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String t = title.getText().toString().trim();
            double goal = parseAmount(target.getText().toString());
            double have = Math.max(0, parseAmount(saved.getText().toString()));
            if (t.isEmpty()) { title.setError("Укажи название"); return; }
            if (goal <= 0) { target.setError("Укажи сумму цели"); return; }
            have = Math.min(goal, have);
            db.addGoal(t, goal, have, date.getText().toString().trim());
            dlg.dismiss();
            refreshCurrent();
        }));
        dlg.show();
    }

    private void showGoalContributionDialog(DbHelper.Goal g) {
        EditText amount = input("Сумма пополнения");
        amount.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        LinearLayout form = dialogForm();
        form.addView(text("Осталось: " + fmt(Math.max(0, g.target - g.saved)), 13, C_MUTED, false));
        form.addView(label("Пополнить на")); form.addView(amount);
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(g.title)
                .setView(wrapDialog(form))
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Пополнить", null)
                .create();
        dlg.setOnShowListener(x -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            double val = parseAmount(amount.getText().toString());
            if (val <= 0) { amount.setError("Укажи сумму"); return; }
            db.addGoalContribution(g.id, val);
            dlg.dismiss();
            renderGoals();
        }));
        dlg.show();
    }

    private void showRecurringDialog() {
        LinearLayout form = dialogForm();
        EditText title = input("Например, Аренда");
        form.addView(label("Название")); form.addView(title);
        EditText amount = input("Сумма");
        amount.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        form.addView(label("Сумма")); form.addView(amount);
        EditText day = input("Например, 5");
        day.setInputType(InputType.TYPE_CLASS_NUMBER);
        form.addView(label("День месяца (1–31)")); form.addView(day);
        Spinner category = new Spinner(this);
        category.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, db.getCategories("expense")));
        form.addView(label("Категория")); form.addView(category);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Регулярный платёж")
                .setView(wrapDialog(form))
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Добавить", null)
                .create();
        dlg.setOnShowListener(x -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String t = title.getText().toString().trim();
            double val = parseAmount(amount.getText().toString());
            int d;
            try { d = Integer.parseInt(day.getText().toString().trim()); } catch (Exception e) { d = 0; }
            if (t.isEmpty()) { title.setError("Укажи название"); return; }
            if (val <= 0) { amount.setError("Укажи сумму"); return; }
            if (d < 1 || d > 31) { day.setError("От 1 до 31"); return; }
            db.addRecurring(t, val, d, String.valueOf(category.getSelectedItem()));
            dlg.dismiss();
            renderRecurring();
        }));
        dlg.show();
    }

    private void exportCsv() {
        pendingWriteContent = db.exportTransactionsCsv();
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/csv");
        i.putExtra(Intent.EXTRA_TITLE, "fincontrol-transactions-" + LocalDate.now() + ".csv");
        startActivityForResult(i, REQ_EXPORT_CSV);
    }

    private void exportBackup() {
        try {
            pendingWriteContent = db.exportJson();
        } catch (JSONException e) {
            toast("Не удалось подготовить резервную копию");
            return;
        }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, "fincontrol-backup-" + LocalDate.now() + ".json");
        startActivityForResult(i, REQ_EXPORT_BACKUP);
    }

    private void importBackup() {
        new AlertDialog.Builder(this)
                .setTitle("Восстановить данные?")
                .setMessage("Текущие данные приложения будут заменены содержимым резервной копии.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Выбрать файл", (d,w) -> {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("application/json");
                    startActivityForResult(i, REQ_IMPORT_BACKUP);
                }).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_EXPORT_CSV || requestCode == REQ_EXPORT_BACKUP) {
            if (pendingWriteContent == null) return;
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new Exception("No output stream");
                out.write(pendingWriteContent.getBytes(StandardCharsets.UTF_8));
                out.flush();
                toast("Файл сохранён");
            } catch (Exception e) {
                toast("Не удалось сохранить файл");
            } finally {
                pendingWriteContent = null;
            }
        } else if (requestCode == REQ_IMPORT_BACKUP) {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new Exception("No input stream");
                BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
                db.importJson(sb.toString());
                toast("Данные восстановлены");
                showTab(0);
            } catch (Exception e) {
                toast("Не удалось восстановить: " + e.getMessage());
            }
        }
    }

    private void refreshCurrent() {
        showTab(activeTab);
    }

    private LinearLayout card(int color) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        c.setBackground(roundRect(color, 18, color == C_CARD ? C_LINE : 0, color == C_CARD ? 1 : 0));
        return c;
    }

    private LinearLayout statCard(String label, String value, int valueColor) {
        LinearLayout c = card(C_CARD);
        c.setPadding(dp(12), dp(12), dp(12), dp(12));
        c.addView(text(label, 11, C_MUTED, false));
        TextView v = text(value, 14, valueColor, true);
        v.setPadding(0, dp(5), 0, 0);
        c.addView(v);
        return c;
    }

    private TextView sectionTitle(String s) {
        TextView t = text(s, 18, C_TEXT, true);
        t.setPadding(dp(2), dp(24), dp(2), dp(10));
        return t;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        t.setLineSpacing(0, 1.12f);
        return t;
    }

    private TextView label(String s) {
        TextView t = text(s, 12, C_MUTED, true);
        t.setPadding(dp(2), dp(12), 0, dp(6));
        return t;
    }

    private EditText input(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextSize(15);
        e.setTextColor(C_TEXT);
        e.setHintTextColor(Color.rgb(158, 166, 183));
        e.setSingleLine(true);
        e.setPadding(dp(12), 0, dp(12), 0);
        e.setBackground(roundRect(Color.WHITE, 12, C_LINE, 1));
        e.setMinHeight(dp(48));
        return e;
    }

    private Spinner spinner(String[] items) {
        Spinner s = new Spinner(this);
        s.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, items));
        return s;
    }

    private Button actionButton(String s, int color) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(14);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setAllCaps(false);
        b.setBackground(roundRect(color, 14, 0, 0));
        return b;
    }

    private Button primaryButton(String s) {
        return actionButton(s, C_PRIMARY);
    }

    private Button secondaryButton(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(14);
        b.setTextColor(C_PRIMARY);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setAllCaps(false);
        b.setBackground(roundRect(Color.rgb(241, 245, 255), 14, Color.rgb(217, 226, 249), 1));
        return b;
    }

    private Button miniButton(String s) {
        Button b = secondaryButton(s);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(12), dp(7), dp(12), dp(7));
        return b;
    }

    private LinearLayout emptyCard(String title, String body, String action, View.OnClickListener listener) {
        LinearLayout c = card(C_CARD);
        c.addView(text(title, 16, C_TEXT, true));
        TextView b = text(body, 13, C_MUTED, false);
        b.setPadding(0, dp(6), 0, dp(12));
        c.addView(b);
        Button btn = secondaryButton(action);
        btn.setOnClickListener(listener);
        c.addView(btn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        return c;
    }

    private LinearLayout menuCard(String title, String body, View.OnClickListener click) {
        LinearLayout c = card(C_CARD);
        c.setOnClickListener(click);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.addView(text(title, 16, C_TEXT, true));
        TextView b = text(body, 12, C_MUTED, false);
        b.setPadding(0, dp(5), dp(8), 0);
        txt.addView(b);
        row.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(text("›", 30, C_PRIMARY, false));
        c.addView(row);
        return c;
    }

    private void addKeyValue(LinearLayout parent, String key, String value, int color) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView k = text(key, 14, C_MUTED, false);
        r.addView(k, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(text(value, 15, color, true));
        parent.addView(r);
    }

    private void addDivider(LinearLayout parent) {
        View v = new View(this);
        v.setBackgroundColor(C_LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(12);
        lp.bottomMargin = dp(12);
        parent.addView(v, lp);
    }

    private View progressBar(float value, int color) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackground(roundRect(Color.rgb(235, 238, 244), 8, 0, 0));
        value = Math.max(0f, Math.min(1f, value));
        View fill = new View(this);
        fill.setBackground(roundRect(color, 8, 0, 0));
        View rest = new View(this);
        if (value > 0) bar.addView(fill, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, value));
        if (value < 1) bar.addView(rest, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f - value));
        bar.setMinimumHeight(dp(8));
        return bar;
    }

    private void addSubHeader(LinearLayout col, String title, Runnable back, String action, View.OnClickListener actionClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView backBtn = text("‹", 34, C_PRIMARY, false);
        backBtn.setGravity(Gravity.CENTER);
        backBtn.setOnClickListener(v -> back.run());
        row.addView(backBtn, new LinearLayout.LayoutParams(dp(36), dp(44)));
        TextView t = text(title, 22, C_TEXT, true);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (action != null) {
            Button a = miniButton(action);
            a.setOnClickListener(actionClick);
            row.addView(a);
        }
        col.addView(row);
    }

    private LinearLayout dialogForm() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(4), dp(20), dp(12));
        return form;
    }

    private View wrapDialog(LinearLayout form) {
        ScrollView s = new ScrollView(this);
        s.addView(form);
        s.setPadding(0, dp(4), 0, 0);
        return s;
    }

    private void pickDate(EditText target) {
        LocalDate current;
        try { current = LocalDate.parse(target.getText().toString()); }
        catch (Exception e) { current = LocalDate.now(); }
        DatePickerDialog dlg = new DatePickerDialog(this, (view, year, month, day) ->
                target.setText(LocalDate.of(year, month + 1, day).toString()),
                current.getYear(), current.getMonthValue() - 1, current.getDayOfMonth());
        dlg.show();
    }

    private String prettyDate(String iso) {
        try {
            LocalDate d = LocalDate.parse(iso);
            if (d.equals(LocalDate.now())) return "Сегодня";
            if (d.equals(LocalDate.now().minusDays(1))) return "Вчера";
            return d.format(DateTimeFormatter.ofPattern("d MMM yyyy", ru));
        } catch (Exception e) {
            return iso;
        }
    }

    private String fmt(double value) {
        return money.format(value).replace(" ", " ");
    }

    private double parseAmount(String s) {
        if (s == null || s.trim().isEmpty()) return 0;
        try {
            return Double.parseDouble(s.trim().replace(" ", "").replace(',', '.'));
        } catch (Exception e) {
            return 0;
        }
    }

    private GradientDrawable roundRect(int fill, int radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fill);
        gd.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) gd.setStroke(dp(strokeDp), strokeColor);
        return gd;
    }

    private LinearLayout.LayoutParams lpCard() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        return lp;
    }

    private LinearLayout.LayoutParams lpCardTight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(7);
        return lp;
    }

    private LinearLayout.LayoutParams lpTop(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(top);
        return lp;
    }

    private View spaceH(int dp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(dp), 1));
        return v;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
