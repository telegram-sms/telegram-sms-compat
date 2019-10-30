package com.qwe7002.telegram_sms_compat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.gson.Gson;

import java.io.IOException;

import io.paperdb.Paper;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import static android.content.Context.MODE_PRIVATE;

public class sms_receiver extends BroadcastReceiver {
    public void onReceive(final Context context, Intent intent) {
        Paper.init(context);
        final String TAG = "sms_receiver";
        Log.d(TAG, "Receive action: " + intent.getAction());
        Bundle extras = intent.getExtras();
        assert extras != null;
        boolean is_default = false;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            is_default = Telephony.Sms.getDefaultSmsPackage(context).equals(context.getPackageName());
        }
        assert intent.getAction() != null;
        if (intent.getAction().equals("android.provider.Telephony.SMS_RECEIVED") && is_default) {
            //When it is the default application, it will receive two broadcasts.
            Log.i(TAG, "reject: android.provider.Telephony.SMS_RECEIVE.");
            return;
        }
        final SharedPreferences sharedPreferences = context.getSharedPreferences("data", MODE_PRIVATE);
        if (!sharedPreferences.getBoolean("initialized", false)) {
            Log.i(TAG, "Uninitialized, SMS receiver is deactivated.");
            return;
        }
        String bot_token = sharedPreferences.getString("bot_token", "");
        String chat_id = sharedPreferences.getString("chat_id", "");
        String request_uri = public_func.get_url(bot_token, "sendMessage");
        Object[] pdus = (Object[]) extras.get("pdus");
        assert pdus != null;
        final SmsMessage[] messages = new SmsMessage[pdus.length];
        for (int i = 0; i < pdus.length; ++i) {
            messages[i] = SmsMessage.createFromPdu((byte[]) pdus[i]);
        }

        if (messages.length == 0) {
            public_func.write_log(context, "Message length is equal to 0.");
            return;
        }


        StringBuilder message_body_builder = new StringBuilder();
        for (SmsMessage item : messages) {
            message_body_builder.append(item.getMessageBody());
        }
        final String message_body = message_body_builder.toString();
        final String message_address = messages[0].getOriginatingAddress();
        assert message_address != null;
        String trusted_phone_number = sharedPreferences.getString("trusted_phone_number", null);
        boolean is_trusted_phone = false;
        if (trusted_phone_number != null && trusted_phone_number.length() != 0) {
            is_trusted_phone = message_address.contains(trusted_phone_number);
        }
        final message_json request_body = new message_json();
        request_body.chat_id = chat_id;
        String message_body_html = message_body;
        final String message_head = "[" + context.getString(R.string.receive_sms_head) + "]" + "\n" + context.getString(R.string.from) + message_address + "\n" + context.getString(R.string.content);
        String raw_request_body_text = message_head + message_body;
        if (sharedPreferences.getBoolean("verification_code", false) && !is_trusted_phone) {
            String verification = public_func.get_verification_code(message_body);
            if (verification != null) {
                request_body.parse_mode = "html";
                message_body_html = message_body
                        .replace("<", "&lt;")
                        .replace(">", "&gt;")
                        .replace("&", "&amp;")
                        .replace(verification, "<code>" + verification + "</code>");
            }
        }
        request_body.text = message_head + message_body_html;

        if (is_trusted_phone) {
            if (message_body.toLowerCase().equals("restart-service")) {
                new Thread(() -> {
                    public_func.stop_all_service(context.getApplicationContext());
                    public_func.start_service(context.getApplicationContext(), sharedPreferences.getBoolean("battery_monitoring_switch", false), sharedPreferences.getBoolean("chat_command", false));
                }).start();
                raw_request_body_text = context.getString(R.string.system_message_head) + "\n" + context.getString(R.string.restart_service);
                request_body.text = raw_request_body_text;
            }
            String[] msg_send_list = message_body.split("\n");
            String msg_send_to = public_func.get_send_phone_number(msg_send_list[0]);
            if (public_func.is_phone_number(msg_send_to) && msg_send_list.length != 1) {
                StringBuilder msg_send_content = new StringBuilder();
                for (int i = 1; i < msg_send_list.length; ++i) {
                    if (msg_send_list.length != 2 && i != 1) {
                        msg_send_content.append("\n");
                    }
                    msg_send_content.append(msg_send_list[i]);
                }
                new Thread(() -> public_func.send_sms(context, msg_send_to, msg_send_content.toString())).start();
                return;
            }
        }
        String request_body_json = new Gson().toJson(request_body);
        RequestBody body = RequestBody.create(public_func.JSON, request_body_json);
        OkHttpClient okhttp_client = public_func.get_okhttp_obj(sharedPreferences.getBoolean("doh_switch", true));
        Request request = new Request.Builder().url(request_uri).method("POST", body).build();
        Call call = okhttp_client.newCall(request);
        final String error_head = "Send SMS forward failed:";
        final String final_raw_request_body_text = raw_request_body_text;
        call.enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                e.printStackTrace();
                String error_message = error_head + e.getMessage();
                public_func.write_log(context, error_message);
                public_func.send_fallback_sms(context, final_raw_request_body_text);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                assert response.body() != null;
                String result = response.body().string();
                if (response.code() != 200) {
                    String error_message = error_head + response.code() + " " + result;
                    public_func.write_log(context, error_message);
                    public_func.send_fallback_sms(context, final_raw_request_body_text);
                } else {
                    if (!public_func.is_phone_number(message_address)) {
                        public_func.write_log(context, "[" + message_address + "] Not a regular phone number.");
                        return;
                    }
                    public_func.add_message_list(public_func.get_message_id(result), message_address);
                }
            }
        });
    }

}