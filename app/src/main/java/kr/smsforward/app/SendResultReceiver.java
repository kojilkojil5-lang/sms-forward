package kr.smsforward.app;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;

/**
 * 발송한 문자의 결과를 기록.
 * - ACTION_SENT: 폰에서 통신사로 실제로 나갔는지
 * - ACTION_DELIVERED: 받는 사람 폰에 도착했다는 통신사 수신 확인
 */
public class SendResultReceiver extends BroadcastReceiver {

    static final String ACTION_SENT = "kr.smsforward.app.SMS_SENT";
    static final String ACTION_DELIVERED = "kr.smsforward.app.SMS_DELIVERED";
    static final String EXTRA_TO = "to";
    static final String EXTRA_WHAT = "what";
    static final String EXTRA_PARTS = "parts";

    @Override
    public void onReceive(Context context, Intent intent) {
        Context c = context.getApplicationContext();
        String to = intent.getStringExtra(EXTRA_TO);
        String what = intent.getStringExtra(EXTRA_WHAT);
        int parts = intent.getIntExtra(EXTRA_PARTS, 1);
        String tag = "[" + what + "] → " + to + (parts > 1 ? " (" + parts + "통 분할)" : "");

        if (ACTION_SENT.equals(intent.getAction())) {
            int code = getResultCode();
            if (code == Activity.RESULT_OK) {
                Forwarder.log(c, "✅ 발송 완료 " + tag);
            } else {
                Forwarder.log(c, "❌ 발송 실패(" + sentError(code) + ") " + tag);
            }
        } else if (ACTION_DELIVERED.equals(intent.getAction())) {
            Forwarder.log(c, deliveryText(intent) + " " + tag);
        }
    }

    private static String sentError(int code) {
        switch (code) {
            case SmsManager.RESULT_ERROR_NO_SERVICE: return "신호 없음";
            case SmsManager.RESULT_ERROR_RADIO_OFF: return "비행기모드/무선 꺼짐";
            case SmsManager.RESULT_ERROR_NULL_PDU: return "문자 생성 오류";
            case SmsManager.RESULT_ERROR_GENERIC_FAILURE: return "통신사 거부 또는 번호 오류";
            default: return "오류코드 " + code;
        }
    }

    /** 통신사 수신 확인 상태 해석 (0 = 도착, 32~63 = 일시 실패/대기, 64 이상 = 영구 실패) */
    private static String deliveryText(Intent intent) {
        try {
            byte[] pdu = intent.getByteArrayExtra("pdu");
            String format = intent.getStringExtra("format");
            if (pdu != null) {
                SmsMessage m = SmsMessage.createFromPdu(pdu, format);
                int st = m.getStatus();
                if (st == 0) return "📩 수신 확인(상대 폰 도착)";
                if (st >= 64) return "❌ 수신 실패(상태 " + st + ")";
                if (st >= 32) return "⏳ 수신 대기(상대 폰 꺼짐 등, 상태 " + st + ")";
                return "📩 수신 확인(상태 " + st + ")";
            }
        } catch (Exception ignored) {
        }
        return "📩 수신 확인";
    }
}
