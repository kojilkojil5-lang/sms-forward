package kr.smsforward.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.telephony.SmsManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/** 설정 저장, 필터 판단, 실제 전송, 기록을 담당. */
public final class Forwarder {

    static final String PREFS = "settings";
    static final String KEY_ENABLED = "enabled";
    static final String KEY_TARGET = "target";
    static final String KEY_SENDERS = "senders";
    static final String KEY_INCLUDE = "include";
    static final String KEY_EXCLUDE = "exclude";
    static final String KEY_LOG = "log";

    /** 처음 설치 시 기본 제외어: 인증번호 류는 전달하지 않음 */
    static final String DEFAULT_EXCLUDE = "인증번호,인증 번호,OTP,비밀번호,승인번호,보안코드";

    private static final int MAX_LOG_LINES = 50;

    private Forwarder() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 문자 한 건을 받아 조건에 맞으면 전달 */
    static void handle(Context c, String from, String body) {
        SharedPreferences p = prefs(c);
        if (!p.getBoolean(KEY_ENABLED, false)) return;

        String target = p.getString(KEY_TARGET, "").trim();
        if (digits(target).isEmpty()) {
            log(c, "건너뜀(받을 번호 미설정) - " + from);
            return;
        }

        String fromDigits = digits(from);
        String targetDigits = digits(target);

        // 받는 사람이 보낸 문자를 다시 그 사람에게 돌려보내는 무한반복 방지
        if (!fromDigits.isEmpty() && sameNumber(fromDigits, targetDigits)) {
            log(c, "건너뜀(받는 사람 본인 문자) - " + from);
            return;
        }

        // 1) 발신번호 필터: 비어 있으면 모든 번호 허용
        String[] senders = split(p.getString(KEY_SENDERS, ""));
        if (senders.length > 0) {
            boolean ok = false;
            for (String s : senders) {
                String sd = digits(s);
                if (!sd.isEmpty() && sameNumber(fromDigits, sd)) { ok = true; break; }
            }
            if (!ok) return; // 대상 아닌 번호는 기록도 하지 않음
        }

        String lowerBody = body.toLowerCase(Locale.ROOT);

        // 2) 제외어: 하나라도 들어 있으면 전달 안 함
        for (String ex : split(p.getString(KEY_EXCLUDE, DEFAULT_EXCLUDE))) {
            if (lowerBody.contains(ex.toLowerCase(Locale.ROOT))) {
                log(c, "제외어(" + ex + ") 포함으로 미전달 - " + from);
                return;
            }
        }

        // 3) 포함어: 비어 있으면 모두 허용, 있으면 하나 이상 들어 있어야 전달
        String[] includes = split(p.getString(KEY_INCLUDE, ""));
        if (includes.length > 0) {
            boolean ok = false;
            for (String in : includes) {
                if (lowerBody.contains(in.toLowerCase(Locale.ROOT))) { ok = true; break; }
            }
            if (!ok) return;
        }

        String text = "[문자전달] 보낸사람: " + from + "\n" + body;
        try {
            send(c, target, text);
            log(c, "전달함 " + from + " → " + target);
        } catch (Exception e) {
            log(c, "전송 실패 " + from + " : " + e.getMessage());
        }
    }

    /** 실제 SMS 발송. 긴 내용은 여러 통으로 나눠 보냄 */
    static void send(Context c, String to, String text) {
        SmsManager sm;
        if (Build.VERSION.SDK_INT >= 31) {
            sm = c.getSystemService(SmsManager.class);
        } else {
            sm = SmsManager.getDefault();
        }
        ArrayList<String> parts = sm.divideMessage(text);
        if (parts.size() > 1) {
            sm.sendMultipartTextMessage(to, null, parts, null, null);
        } else {
            sm.sendTextMessage(to, null, text, null, null);
        }
    }

    static synchronized void log(Context c, String line) {
        String time = new SimpleDateFormat("MM-dd HH:mm", Locale.KOREA).format(new Date());
        SharedPreferences p = prefs(c);
        String old = p.getString(KEY_LOG, "");
        String all = time + "  " + line + (old.isEmpty() ? "" : "\n" + old);
        String[] lines = all.split("\n");
        if (lines.length > MAX_LOG_LINES) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < MAX_LOG_LINES; i++) {
                if (i > 0) sb.append('\n');
                sb.append(lines[i]);
            }
            all = sb.toString();
        }
        p.edit().putString(KEY_LOG, all).apply();
    }

    static String digits(String s) {
        return s == null ? "" : s.replaceAll("[^0-9]", "");
    }

    /** +82-10-1234-5678 과 010-1234-5678 을 같은 번호로 취급 */
    static boolean sameNumber(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return false;
        return normalizeKr(a).equals(normalizeKr(b));
    }

    private static String normalizeKr(String d) {
        if (d.startsWith("82") && d.length() >= 11) return "0" + d.substring(2);
        return d;
    }

    static String[] split(String s) {
        if (s == null) return new String[0];
        ArrayList<String> out = new ArrayList<>();
        for (String t : s.split("[,\\n]")) {
            String v = t.trim();
            if (!v.isEmpty()) out.add(v);
        }
        return out.toArray(new String[0]);
    }
}
