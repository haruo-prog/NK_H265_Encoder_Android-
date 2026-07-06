package jp.co.nkts.scoremanager;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String PREF_NAME = "nk_score_manager_beta";
    private static final String KEY_DRAFT = "draft_text_v11";
    private static final String KEY_ROUNDS = "rounds_text_v11";
    private static final long AUTO_SAVE_INTERVAL_MS = 5 * 60 * 1000L;

    private final Handler autoSaveHandler = new Handler(Looper.getMainLooper());
    private Runnable autoSaveRunnable;
    private boolean restoring = false;

    private EditText dateEdit;
    private EditText courseEdit;
    private Spinner startSideSpinner;
    private EditText startTimeEdit;
    private EditText[] memberEdits = new EditText[4];
    private EditText[] parEdits = new EditText[18];
    private EditText[][] scoreEdits = new EditText[4][18];
    private EditText[] puttEdits = new EditText[18];
    private Spinner[] fwSpinners = new Spinner[18];
    private EditText[] obEdits = new EditText[18];
    private EditText[] penaltyEdits = new EditText[18];
    private TextView summaryText;
    private TextView saveStatusText;
    private TextView exportText;
    private TextView pastRoundsText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createContentView());
        restoreDraft();
        updateSummary();
        loadPastRounds();
        startAutoSaveTimer();
    }

    @Override
    protected void onPause() {
        saveDraft("バックグラウンド保存");
        super.onPause();
    }

    @Override
    protected void onStop() {
        saveDraft("画面終了前保存");
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (autoSaveRunnable != null) autoSaveHandler.removeCallbacks(autoSaveRunnable);
        saveDraft("終了時保存");
        super.onDestroy();
    }

    private ScrollView createContentView() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFFF8FAFC);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(16), dp(12), dp(18));
        scroll.addView(root);

        TextView title = label("NKスコア管理");
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title);

        TextView sub = info("V1.1 BETA / スコアカード風UI・PAR・FW・OB・ペナルティ・平均パット・過去ラウンド");
        sub.setGravity(Gravity.CENTER_HORIZONTAL);
        sub.setPadding(0, dp(4), 0, dp(12));
        root.addView(sub);

        dateEdit = input("日付 例：2026/07/06");
        dateEdit.setText(new SimpleDateFormat("yyyy/MM/dd", Locale.US).format(new Date()));
        root.addView(row("日付", dateEdit));

        courseEdit = input("ゴルフ場名");
        root.addView(row("ゴルフ場", courseEdit));

        startSideSpinner = new Spinner(this);
        ArrayAdapter<String> startAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new String[]{"OUTスタート", "INスタート"});
        startAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        startSideSpinner.setAdapter(startAdapter);
        root.addView(row("IN / OUT", startSideSpinner));

        startTimeEdit = input("例：08:35");
        root.addView(row("スタート時間", startTimeEdit));

        root.addView(section("メンバー登録 1名〜4名 / 1番目が自分"));
        for (int i = 0; i < 4; i++) {
            memberEdits[i] = input(i == 0 ? "自分の名前" : "メンバー" + (i + 1) + " 任意");
            root.addView(row((i + 1) + "人目", memberEdits[i]));
        }

        root.addView(section("スコアカード"));
        TextView note = info("各ホール：PAR、4名スコア、自分のパット、FW、OB、ペナルティを入力できます。FWは主にティーショット対象ホールで使います。");
        note.setPadding(0, 0, 0, dp(8));
        root.addView(note);

        for (int hole = 0; hole < 18; hole++) {
            LinearLayout block = new LinearLayout(this);
            block.setOrientation(LinearLayout.VERTICAL);
            block.setPadding(dp(8), dp(8), dp(8), dp(8));
            block.setBackgroundColor(hole < 9 ? 0xFFEFF6FF : 0xFFF0FDF4);
            LinearLayout.LayoutParams blockParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            blockParams.setMargins(0, 0, 0, dp(8));
            root.addView(block, blockParams);

            TextView holeTitle = label(String.format(Locale.US, "%02dH  %s", hole + 1, hole < 9 ? "OUT" : "IN"));
            holeTitle.setTextSize(18);
            block.addView(holeTitle);

            LinearLayout parRow = new LinearLayout(this);
            parRow.setOrientation(LinearLayout.HORIZONTAL);
            parRow.setGravity(Gravity.CENTER_VERTICAL);
            parRow.setPadding(0, dp(6), 0, 0);
            block.addView(parRow);
            parRow.addView(info("PAR"), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            parEdits[hole] = numberInput("4");
            parEdits[hole].setText("4");
            parRow.addView(parEdits[hole], new LinearLayout.LayoutParams(dp(80), LinearLayout.LayoutParams.WRAP_CONTENT));

            GridLayout grid = new GridLayout(this);
            grid.setColumnCount(4);
            grid.setPadding(0, dp(6), 0, 0);
            block.addView(grid);

            for (int player = 0; player < 4; player++) {
                LinearLayout cell = new LinearLayout(this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setPadding(dp(3), 0, dp(3), 0);
                GridLayout.LayoutParams gp = new GridLayout.LayoutParams();
                gp.width = 0;
                gp.height = GridLayout.LayoutParams.WRAP_CONTENT;
                gp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
                cell.setLayoutParams(gp);
                cell.addView(smallLabel(player == 0 ? "自分" : "M" + (player + 1)));
                scoreEdits[player][hole] = numberInput("S");
                cell.addView(scoreEdits[player][hole]);
                grid.addView(cell);
            }

            GridLayout ownGrid = new GridLayout(this);
            ownGrid.setColumnCount(4);
            ownGrid.setPadding(0, dp(8), 0, 0);
            block.addView(ownGrid);

            LinearLayout puttCell = scoreOptionCell("Putt");
            puttEdits[hole] = numberInput("P");
            puttCell.addView(puttEdits[hole]);
            ownGrid.addView(puttCell, gridParam());

            LinearLayout fwCell = scoreOptionCell("FW");
            fwSpinners[hole] = new Spinner(this);
            ArrayAdapter<String> fwAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new String[]{"-", "○", "×", "対象外"});
            fwAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            fwSpinners[hole].setAdapter(fwAdapter);
            fwCell.addView(fwSpinners[hole]);
            ownGrid.addView(fwCell, gridParam());

            LinearLayout obCell = scoreOptionCell("OB");
            obEdits[hole] = numberInput("0");
            obCell.addView(obEdits[hole]);
            ownGrid.addView(obCell, gridParam());

            LinearLayout penCell = scoreOptionCell("Pen");
            penaltyEdits[hole] = numberInput("0");
            penCell.addView(penaltyEdits[hole]);
            ownGrid.addView(penCell, gridParam());
        }

        summaryText = panel("集計中...");
        root.addView(summaryText);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.VERTICAL);
        buttons.setPadding(0, dp(12), 0, dp(8));
        root.addView(buttons);

        Button saveButton = button("手動保存");
        saveButton.setOnClickListener(v -> saveDraft("手動保存"));
        buttons.addView(saveButton);

        Button roundSaveButton = button("このラウンドを過去ラウンドへ保存");
        roundSaveButton.setOnClickListener(v -> saveRound());
        buttons.addView(roundSaveButton);

        Button exportButton = button("テキストでエクスポート");
        exportButton.setOnClickListener(v -> exportText());
        buttons.addView(exportButton);

        Button copyButton = button("エクスポート内容をコピー");
        copyButton.setOnClickListener(v -> copyExportText());
        buttons.addView(copyButton);

        saveStatusText = info("自動保存：5分おき / バックグラウンド移行時にも保存");
        saveStatusText.setGravity(Gravity.CENTER_HORIZONTAL);
        buttons.addView(saveStatusText);

        exportText = panel("エクスポート結果はここに表示されます。");
        root.addView(exportText);

        root.addView(section("過去ラウンド一覧"));
        pastRoundsText = panel("過去ラウンドはまだありません。");
        root.addView(pastRoundsText);

        TextView credit = info("© 株式会社NKテクニカルサポート");
        credit.setGravity(Gravity.CENTER_HORIZONTAL);
        credit.setPadding(0, dp(18), 0, 0);
        root.addView(credit);

        attachWatchers();
        return scroll;
    }

    private void attachWatchers() {
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { if (!restoring) updateSummary(); }
            @Override public void afterTextChanged(Editable s) {}
        };
        dateEdit.addTextChangedListener(watcher);
        courseEdit.addTextChangedListener(watcher);
        startTimeEdit.addTextChangedListener(watcher);
        for (EditText e : memberEdits) e.addTextChangedListener(watcher);
        for (EditText e : parEdits) e.addTextChangedListener(watcher);
        for (int p = 0; p < 4; p++) for (int h = 0; h < 18; h++) scoreEdits[p][h].addTextChangedListener(watcher);
        for (EditText e : puttEdits) e.addTextChangedListener(watcher);
        for (EditText e : obEdits) e.addTextChangedListener(watcher);
        for (EditText e : penaltyEdits) e.addTextChangedListener(watcher);
        AdapterView.OnItemSelectedListener listener = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, android.view.View view, int position, long id) { if (!restoring) updateSummary(); }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        };
        startSideSpinner.setOnItemSelectedListener(listener);
        for (Spinner s : fwSpinners) s.setOnItemSelectedListener(listener);
    }

    private LinearLayout scoreOptionCell(String label) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setPadding(dp(3), 0, dp(3), 0);
        cell.addView(smallLabel(label));
        return cell;
    }

    private GridLayout.LayoutParams gridParam() {
        GridLayout.LayoutParams gp = new GridLayout.LayoutParams();
        gp.width = 0;
        gp.height = GridLayout.LayoutParams.WRAP_CONTENT;
        gp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        return gp;
    }

    private LinearLayout row(String label, android.view.View input) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, 0, 0, dp(10));
        TextView l = section(label);
        l.setPadding(0, 0, 0, dp(4));
        row.addView(l);
        row.addView(input);
        return row;
    }

    private EditText input(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setTextSize(15);
        e.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        return e;
    }

    private EditText numberInput(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setTextSize(14);
        e.setGravity(Gravity.CENTER);
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        return e;
    }

    private TextView label(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(0xFF111827);
        v.setTypeface(null, 1);
        return v;
    }

    private TextView section(String text) {
        TextView v = label(text);
        v.setTextSize(16);
        v.setPadding(0, dp(12), 0, dp(6));
        return v;
    }

    private TextView smallLabel(String text) {
        TextView v = info(text);
        v.setGravity(Gravity.CENTER);
        v.setTextSize(12);
        return v;
    }

    private TextView info(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(14);
        v.setTextColor(0xFF334155);
        return v;
    }

    private TextView panel(String text) {
        TextView v = info(text);
        v.setBackgroundColor(0xFFE2E8F0);
        v.setPadding(dp(12), dp(12), dp(12), dp(12));
        return v;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15);
        return b;
    }

    private void updateSummary() {
        if (summaryText == null) return;
        StringBuilder sb = new StringBuilder();
        int totalPar = totalPar();
        sb.append("集計\n");
        sb.append("PAR合計：").append(totalPar).append("\n");
        for (int p = 0; p < 4; p++) {
            String name = text(memberEdits[p]);
            if (p > 0 && name.isEmpty()) continue;
            if (p == 0 && name.isEmpty()) name = "自分";
            int out = 0, in = 0, total = 0, holes = 0;
            for (int h = 0; h < 18; h++) {
                int s = intValue(scoreEdits[p][h]);
                if (s > 0) {
                    total += s;
                    if (h < 9) out += s; else in += s;
                    holes++;
                }
            }
            String diff = holes == 18 ? scoreDiff(total, totalPar) : "未完了";
            sb.append(name).append("：OUT ").append(out).append(" / IN ").append(in).append(" / TOTAL ").append(total).append(" / ").append(diff).append(" / ").append(holes).append("H\n");
        }
        int putts = 0, puttHoles = 0, ob = 0, pen = 0, fwOk = 0, fwNg = 0;
        for (int h = 0; h < 18; h++) {
            int p = intValue(puttEdits[h]);
            if (p > 0) { putts += p; puttHoles++; }
            ob += intValue(obEdits[h]);
            pen += intValue(penaltyEdits[h]);
            String fw = fwText(h);
            if ("○".equals(fw)) fwOk++;
            if ("×".equals(fw)) fwNg++;
        }
        double avgPutt = puttHoles > 0 ? (double) putts / puttHoles : 0.0;
        int fwTargets = fwOk + fwNg;
        double fwRate = fwTargets > 0 ? (double) fwOk * 100.0 / fwTargets : 0.0;
        sb.append("自分パット合計：").append(putts).append(" / 平均パット：").append(one(avgPutt)).append(" / 入力 ").append(puttHoles).append("H\n");
        sb.append("FWキープ：").append(fwOk).append("/").append(fwTargets).append(" / ").append(one(fwRate)).append("%\n");
        sb.append("OB：").append(ob).append(" / ペナルティ：").append(pen);
        summaryText.setText(sb.toString());
    }

    private int totalPar() {
        int total = 0;
        for (int h = 0; h < 18; h++) {
            int par = intValue(parEdits[h]);
            total += par > 0 ? par : 0;
        }
        return total;
    }

    private String scoreDiff(int total, int par) {
        int diff = total - par;
        if (diff == 0) return "E";
        return diff > 0 ? "+" + diff : String.valueOf(diff);
    }

    private void startAutoSaveTimer() {
        autoSaveRunnable = new Runnable() {
            @Override public void run() {
                saveDraft("5分おき自動保存");
                autoSaveHandler.postDelayed(this, AUTO_SAVE_INTERVAL_MS);
            }
        };
        autoSaveHandler.postDelayed(autoSaveRunnable, AUTO_SAVE_INTERVAL_MS);
    }

    private void saveDraft(String reason) {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        prefs.edit().putString(KEY_DRAFT, serialize()).apply();
        if (saveStatusText != null) saveStatusText.setText(reason + "：" + nowFull());
    }

    private void restoreDraft() {
        restoring = true;
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String data = prefs.getString(KEY_DRAFT, "");
        if (data.isEmpty()) {
            restoring = false;
            return;
        }
        String[] parts = data.split("\\|", -1);
        int i = 0;
        if (parts.length > i) dateEdit.setText(parts[i++]);
        if (parts.length > i) courseEdit.setText(parts[i++]);
        if (parts.length > i) startSideSpinner.setSelection(bound(intFromString(parts[i++]), 0, 1));
        if (parts.length > i) startTimeEdit.setText(parts[i++]);
        for (int m = 0; m < 4 && parts.length > i; m++) memberEdits[m].setText(parts[i++]);
        for (int h = 0; h < 18 && parts.length > i; h++) parEdits[h].setText(parts[i++]);
        for (int p = 0; p < 4; p++) for (int h = 0; h < 18 && parts.length > i; h++) scoreEdits[p][h].setText(parts[i++]);
        for (int h = 0; h < 18 && parts.length > i; h++) puttEdits[h].setText(parts[i++]);
        for (int h = 0; h < 18 && parts.length > i; h++) fwSpinners[h].setSelection(bound(intFromString(parts[i++]), 0, 3));
        for (int h = 0; h < 18 && parts.length > i; h++) obEdits[h].setText(parts[i++]);
        for (int h = 0; h < 18 && parts.length > i; h++) penaltyEdits[h].setText(parts[i++]);
        restoring = false;
    }

    private String serialize() {
        ArrayList<String> parts = new ArrayList<>();
        parts.add(escape(text(dateEdit)));
        parts.add(escape(text(courseEdit)));
        parts.add(String.valueOf(startSideSpinner.getSelectedItemPosition()));
        parts.add(escape(text(startTimeEdit)));
        for (EditText e : memberEdits) parts.add(escape(text(e)));
        for (EditText e : parEdits) parts.add(escape(text(e)));
        for (int p = 0; p < 4; p++) for (int h = 0; h < 18; h++) parts.add(escape(text(scoreEdits[p][h])));
        for (EditText e : puttEdits) parts.add(escape(text(e)));
        for (Spinner s : fwSpinners) parts.add(String.valueOf(s.getSelectedItemPosition()));
        for (EditText e : obEdits) parts.add(escape(text(e)));
        for (EditText e : penaltyEdits) parts.add(escape(text(e)));
        return String.join("|", parts);
    }

    private String buildExportText() {
        updateSummary();
        StringBuilder sb = new StringBuilder();
        sb.append("NKスコア管理 V1.1 BETA\n");
        sb.append("出力日時：").append(nowFull()).append("\n");
        sb.append("日付：").append(text(dateEdit)).append("\n");
        sb.append("ゴルフ場：").append(text(courseEdit)).append("\n");
        sb.append("スタート：").append(startSideSpinner.getSelectedItem()).append("\n");
        sb.append("スタート時間：").append(text(startTimeEdit)).append("\n\n");
        sb.append("メンバー\n");
        for (int i = 0; i < 4; i++) {
            String name = text(memberEdits[i]);
            if (i == 0 && name.isEmpty()) name = "自分";
            if (i == 0 || !name.isEmpty()) sb.append(i + 1).append(". ").append(name).append(i == 0 ? "（自分・パット/FW/OB/ペナルティ入力対象）" : "").append("\n");
        }
        sb.append("\nスコアカード\n");
        for (int h = 0; h < 18; h++) {
            sb.append(String.format(Locale.US, "%02dH", h + 1)).append(" PAR:").append(text(parEdits[h]));
            for (int p = 0; p < 4; p++) {
                String name = text(memberEdits[p]);
                if (p > 0 && name.isEmpty()) continue;
                if (p == 0 && name.isEmpty()) name = "自分";
                sb.append(" / ").append(name).append(":").append(text(scoreEdits[p][h]));
            }
            sb.append(" / Putt:").append(text(puttEdits[h]));
            sb.append(" / FW:").append(fwText(h));
            sb.append(" / OB:").append(text(obEdits[h]));
            sb.append(" / Pen:").append(text(penaltyEdits[h])).append("\n");
        }
        sb.append("\n").append(summaryText.getText().toString()).append("\n");
        return sb.toString();
    }

    private void exportText() {
        saveDraft("エクスポート前保存");
        exportText.setText(buildExportText());
    }

    private void copyExportText() {
        String out = buildExportText();
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("NK Score Export", out));
        exportText.setText(out);
        Toast.makeText(this, "エクスポート内容をコピーしました。", Toast.LENGTH_SHORT).show();
    }

    private void saveRound() {
        String round = buildRoundListItem() + "\n" + buildExportText();
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String old = prefs.getString(KEY_ROUNDS, "");
        String next = round + "\n====================\n" + old;
        if (next.length() > 30000) next = next.substring(0, 30000);
        prefs.edit().putString(KEY_ROUNDS, next).apply();
        loadPastRounds();
        saveDraft("ラウンド保存時バックアップ");
        Toast.makeText(this, "過去ラウンドへ保存しました。", Toast.LENGTH_SHORT).show();
    }

    private String buildRoundListItem() {
        int total = 0;
        int holes = 0;
        for (int h = 0; h < 18; h++) {
            int s = intValue(scoreEdits[0][h]);
            if (s > 0) { total += s; holes++; }
        }
        String score = holes == 18 ? String.valueOf(total) : total + " / " + holes + "H";
        return "【" + text(dateEdit) + "】" + text(courseEdit) + " / 自分TOTAL " + score + " / 保存 " + nowFull();
    }

    private void loadPastRounds() {
        if (pastRoundsText == null) return;
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String rounds = prefs.getString(KEY_ROUNDS, "");
        pastRoundsText.setText(rounds.isEmpty() ? "過去ラウンドはまだありません。" : rounds);
    }

    private String fwText(int hole) {
        Object item = fwSpinners[hole].getSelectedItem();
        return item == null ? "-" : item.toString();
    }

    private String text(EditText e) { return e == null ? "" : e.getText().toString().trim(); }
    private int intValue(EditText e) { return intFromString(text(e)); }
    private int intFromString(String s) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; } }
    private int bound(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    private String escape(String s) { return s.replace("|", "／").replace("\n", " "); }
    private String nowFull() { return new SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US).format(new Date()); }
    private String one(double value) { return String.format(Locale.US, "%.1f", value); }
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
}
