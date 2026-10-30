package kr.smsforward.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int REQ_SMS = 100;

    private Switch swEnabled;
    private EditText etTarget, etSenders, etInclude, etExclude;
    private TextView tvStatus, tvLog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        loadSettings();
        if (!hasSmsPermission()) requestSmsPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    // ---------------- 화면 구성 ----------------

    private ScrollView buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad * 2);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("문자 자동전달");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        tvStatus = new TextView(this);
        tvStatus.setPadding(0, dp(6), 0, dp(12));
        root.addView(tvStatus);

        swEnabled = new Switch(this);
        swEnabled.setText("자동전달 켜기");
        swEnabled.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        root.addView(swEnabled);

        etTarget = addField(root, "받을 사람 번호 (필수, 여러 명은 쉼표로 구분)", "예: 010-1234-5678, 010-2222-3333", InputType.TYPE_CLASS_PHONE);
        etSenders = addField(root, "이 번호에서 온 문자만 전달 (비우면 전체)",
                "쉼표로 구분. 예: 1588-1234, 010-9876-5432", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        etInclude = addField(root, "이 단어가 들어간 문자만 전달 (비우면 전체)",
                "쉼표로 구분. 띄어 쓴 단어는 모두 포함돼야 함. 예: 신세계 OTP, 입금", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        etExclude = addField(root, "이 단어가 들어가면 전달 안 함",
                "쉼표로 구분", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);

        addButton(root, "설정 저장", v -> saveSettings());
        addButton(root, "문자 권한 허용하기", v -> requestSmsPermission());
        addButton(root, "배터리 최적화 예외 등록 (권장)", v -> requestBatteryException());
        addButton(root, "받을 번호로 테스트 문자 보내기", v -> sendTest());

        TextView logTitle = new TextView(this);
        logTitle.setText("최근 기록");
        logTitle.setTypeface(Typeface.DEFAULT_BOLD);
        logTitle.setPadding(0, dp(20), 0, dp(4));
        root.addView(logTitle);

        tvLog = new TextView(this);
        tvLog.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvLog.setTextIsSelectable(true);
        root.addView(tvLog);

        addButton(root, "기록 지우기", v -> {
            Forwarder.prefs(this).edit().remove(Forwarder.KEY_LOG).apply();
            refreshStatus();
        });

        return scroll;
    }

    private EditText addField(LinearLayout root, String label, String hint, int inputType) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setPadding(0, dp(14), 0, dp(2));
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(tv);

        EditText et = new EditText(this);
        et.setHint(hint);
        et.setInputType(inputType);
        root.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return et;
    }

    private void addButton(LinearLayout root, String text, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        root.addView(b, lp);
    }

    // ---------------- 설정 ----------------

    private void loadSettings() {
        SharedPreferences p = Forwarder.prefs(this);
        swEnabled.setChecked(p.getBoolean(Forwarder.KEY_ENABLED, false));
        etTarget.setText(p.getString(Forwarder.KEY_TARGET, ""));
        etSenders.setText(p.getString(Forwarder.KEY_SENDERS, ""));
        etInclude.setText(p.getString(Forwarder.KEY_INCLUDE, ""));
        etExclude.setText(p.getString(Forwarder.KEY_EXCLUDE, Forwarder.DEFAULT_EXCLUDE));
    }

    private void saveSettings() {
        String target = etTarget.getText().toString().trim();
        boolean enabled = swEnabled.isChecked();

        if (enabled && !validTargets(target)) {
            toast("받을 사람 번호를 정확히 입력하세요 (여러 명은 쉼표로 구분)");
            return;
        }
        if (enabled && !hasSmsPermission()) {
            toast("문자 권한이 없어 켤 수 없습니다. 권한을 먼저 허용하세요");
            swEnabled.setChecked(false);
            enabled = false;
        }

        Forwarder.prefs(this).edit()
                .putBoolean(Forwarder.KEY_ENABLED, enabled)
                .putString(Forwarder.KEY_TARGET, target)
                .putString(Forwarder.KEY_SENDERS, etSenders.getText().toString())
                .putString(Forwarder.KEY_INCLUDE, etInclude.getText().toString())
                .putString(Forwarder.KEY_EXCLUDE, etExclude.getText().toString())
                .apply();
        toast(enabled ? "저장됨 - 자동전달 작동 중" : "저장됨 - 자동전달 꺼짐");
        refreshStatus();
    }

    private void refreshStatus() {
        SharedPreferences p = Forwarder.prefs(this);
        boolean on = p.getBoolean(Forwarder.KEY_ENABLED, false);
        StringBuilder sb = new StringBuilder();
        sb.append(on ? "● 작동 중" : "○ 꺼져 있음");
        if (!hasSmsPermission()) sb.append("\n⚠ 문자 권한 없음");
        if (!isIgnoringBattery()) sb.append("\n⚠ 배터리 최적화 대상 (오래 두면 멈출 수 있음)");
        tvStatus.setText(sb.toString());

        String log = p.getString(Forwarder.KEY_LOG, "");
        tvLog.setText(log.isEmpty() ? "아직 기록 없음" : log);
    }

    // ---------------- 권한 / 배터리 ----------------

    private boolean hasSmsPermission() {
        return checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestSmsPermission() {
        if (hasSmsPermission()) {
            toast("이미 허용되어 있습니다");
            return;
        }
        requestPermissions(new String[]{
                Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS}, REQ_SMS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_SMS && !hasSmsPermission()) {
            new AlertDialog.Builder(this)
                    .setTitle("권한 필요")
                    .setMessage("문자를 받고 보내려면 SMS 권한이 필요합니다.\n"
                            + "거절했다면 설정 > 애플리케이션 > 문자 자동전달 > 권한에서 '문자'를 허용하세요.")
                    .setPositiveButton("설정 열기", (d, w) -> {
                        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName()));
                        startActivity(i);
                    })
                    .setNegativeButton("닫기", null)
                    .show();
        }
        refreshStatus();
    }

    private boolean isIgnoringBattery() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void requestBatteryException() {
        if (isIgnoringBattery()) {
            toast("이미 예외로 등록되어 있습니다");
            return;
        }
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    // ---------------- 테스트 ----------------

    private void sendTest() {
        String target = etTarget.getText().toString().trim();
        if (!validTargets(target)) {
            toast("받을 사람 번호를 정확히 입력하세요 (여러 명은 쉼표로 구분)");
            return;
        }
        if (!hasSmsPermission()) {
            toast("문자 권한을 먼저 허용하세요");
            return;
        }
        new AlertDialog.Builder(this)
                .setMessage(Forwarder.split(target).length + "명에게 테스트 문자를 1통씩 실제로 보냅니다.\n" + target)
                .setPositiveButton("보내기", (d, w) -> {
                    Forwarder.sendToAll(this, Forwarder.split(target),
                            "[문자전달 테스트] 자동전달 앱 연결 확인용 문자입니다.", "테스트");
                    toast("보냈습니다. 받는 분들께 도착했는지 확인하세요");
                    refreshStatus();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    /** 쉼표로 구분된 번호가 1개 이상이고 모두 9자리 이상인지 */
    private boolean validTargets(String raw) {
        String[] arr = Forwarder.split(raw);
        if (arr.length == 0) return false;
        for (String t : arr) if (Forwarder.digits(t).length() < 9) return false;
        return true;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
