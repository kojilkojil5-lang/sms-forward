package kr.smsforward.app;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
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

    private static final int MAX_LOG_LINES = 100;

    private Forwarder() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 문자 한 건을 받아 조건에 맞으면 전달 */
    static void handle(Context c, String from, String body) {
        SharedPreferences p = prefs(c);
        if (!p.getBoolean(KEY_ENABLED, false)) return;

        String[] targets = split(p.getString(KEY_TARGET, ""));
        if (targets.length == 0) {
            log(c, "건너뜀(받을 번호 미설정) - " + from);
            return;
        }

        String fromDigits = digits(from);

        // 받는 사람이 보낸 문자를 다시 돌려보내는 무한반복 방지
        for (String t : targets) {
            if (!fromDigits.isEmpty() && sameNumber(fromDigits, digits(t))) {
                log(c, "건너뜀(받는 사람 본인 문자) - " + from);
                return;
            }
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
            if (matches(lowerBody, ex)) {
                log(c, "제외어(" + ex + ") 포함으로 미전달 - " + from);
                return;
            }
        }

        // 3) 포함어: 비어 있으면 모두 허용, 있으면 하나 이상 들어 있어야 전달
        String[] includes = split(p.getString(KEY_INCLUDE, ""));
        if (includes.length > 0) {
            boolean ok = false;
            for (String in : includes) {
                if (matches(lowerBody, in)) { ok = true; break; }
            }
            if (!ok) return;
        }

        String text = "[문자전달] 보낸사람: " + from + "\n" + body;
        sendToAll(c, targets, text, "전달 " + from);
    }

    /** 여러 명에게 각각 따로 발송하고 결과를 번호별로 기록 */
    static void sendToAll(Context c, String[] targets, String text, String what) {
        for (String t : targets) {
            String num = digits(t);
            if (num.length() < 9) {
                log(c, what + " → " + t + " 실패(번호 형식 오류)");
                continue;
            }
            try {
                send(c, num, text, what);
                log(c, "⏳ 발송 요청 [" + what + "] → " + num);
            } catch (Exception e) {
                log(c, "❌ 발송 실패 [" + what + "] → " + num + " : " + e.getMessage());
            }
        }
    }

    /**
     * 키워드 판단. 띄어쓰기로 나뉜 단어가 모두 들어 있으면 일치로 봄.
     * 예) "신세계 OTP" → 문자에 '신세계'와 'OTP'가 둘 다 있으면 일치 (붙어 있지 않아도 됨)
     */
    static boolean matches(String lowerBody, String keyword) {
        String[] words = keyword.trim().toLowerCase(Locale.ROOT).split("\\s+");
        boolean any = false;
        for (String w : words) {
            if (w.isEmpty()) continue;
            any = true;
            if (!lowerBody.contains(w)) return false;
        }
        return any;
    }

    private static int reqCode = (int) (System.currentTimeMillis() & 0xFFFFF);

    /**
     * 실제 SMS 발송. 긴 내용은 여러 통으로 나눠 보냄.
     * 발송 결과(통신사로 나갔는지)와 수신 확인(상대 폰 도착)을 SendResultReceiver 가 기록함.
     * 여러 통으로 나뉜 경우 마지막 조각 기준으로 한 번만 기록.
     */
    static void send(Context c, String to, String text, String what) {
        SmsManager sm;
        if (Build.VERSION.SDK_INT >= 31) {
            sm = c.getSystemService(SmsManager.class);
        } else {
            sm = SmsManager.getDefault();
        }
        // divideMessage()는 삼성 등에서 READ_PHONE_STATE 권한을 요구하며 실패하므로 직접 나눔
        ArrayList<String> parts = splitMessage(text);
        int n = parts.size();
        PendingIntent sentLast = resultIntent(c, SendResultReceiver.ACTION_SENT, to, what, n);
        PendingIntent deliveredLast = resultIntent(c, SendResultReceiver.ACTION_DELIVERED, to, what, n);

        if (n > 1) {
            ArrayList<PendingIntent> sent = new ArrayList<>();
            ArrayList<PendingIntent> delivered = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                sent.add(i == n - 1 ? sentLast : null);
                delivered.add(i == n - 1 ? deliveredLast : null);
            }
            try {
                sm.sendMultipartTextMessage(to, null, parts, sent, delivered);
            } catch (SecurityException e) {
                // 그래도 막히면 조각을 한 통씩 따로 보냄 (결과 기록은 마지막 조각 기준)
                for (int i = 0; i < n; i++) {
                    sm.sendTextMessage(to, null, parts.get(i),
                            i == n - 1 ? sentLast : null, i == n - 1 ? deliveredLast : null);
                }
            }
        } else {
            sm.sendTextMessage(to, null, text, sentLast, deliveredLast);
        }
    }

    /**
     * 문자 나누기. 한글 등 포함 시 한 통 70자(분할 시 67자), 영문/숫자만이면 160자(분할 시 153자).
     * 이모지 같은 두 칸짜리 글자가 중간에 잘리지 않게 처리.
     */
    static ArrayList<String> splitMessage(String text) {
        boolean ascii = true;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch >= 128 || ch == '[' || ch == ']' || ch == '{' || ch == '}'
                    || ch == '\\' || ch == '^' || ch == '~' || ch == '|' || ch == '`') {
                ascii = false;
                break;
            }
        }
        int single = ascii ? 160 : 70;
        int chunk = ascii ? 153 : 67;
        ArrayList<String> out = new ArrayList<>();
        if (text.length() <= single) {
            out.add(text);
            return out;
        }
        int i = 0;
        while (i < text.length()) {
            int end = Math.min(i + chunk, text.length());
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            out.add(text.substring(i, end));
            i = end;
        }
        return out;
    }

    private static synchronized PendingIntent resultIntent(Context c, String action, String to, String what, int parts) {
        Intent i = new Intent(c, SendResultReceiver.class);
        i.setAction(action);
        i.putExtra(SendResultReceiver.EXTRA_TO, to);
        i.putExtra(SendResultReceiver.EXTRA_WHAT, what);
        i.putExtra(SendResultReceiver.EXTRA_PARTS, parts);
        reqCode++;
        // 결과 코드를 시스템이 채워줘야 하므로 MUTABLE 필요 (명시적 대상이라 안전)
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        return PendingIntent.getBroadcast(c, reqCode, i, flags);
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
