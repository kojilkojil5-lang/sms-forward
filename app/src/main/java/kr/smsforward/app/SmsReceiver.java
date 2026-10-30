package kr.smsforward.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;

/** 문자(SMS) 수신 시 시스템이 호출. 여러 조각으로 나뉜 문자는 하나로 합쳐서 전달함. */
public class SmsReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) return;

        SmsMessage[] messages = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (messages == null || messages.length == 0) return;

        String from = messages[0].getOriginatingAddress();
        StringBuilder body = new StringBuilder();
        for (SmsMessage m : messages) {
            if (m != null && m.getMessageBody() != null) body.append(m.getMessageBody());
        }

        Forwarder.handle(context.getApplicationContext(), from, body.toString());
    }
}
