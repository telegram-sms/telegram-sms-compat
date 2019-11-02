package com.qwe7002.telegram_sms_compat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;

import io.paperdb.Paper;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import static android.content.Context.MODE_PRIVATE;

public class call_receiver extends BroadcastReceiver {
    private static String incoming_number;

    @Override
    public void onReceive(Context context, Intent intent) {
        Paper.init(context);
        Log.d("call_receiver", "onReceive: " + intent.getAction());
        if (intent.getStringExtra("incoming_number") != null) {
            incoming_number = intent.getStringExtra("incoming_number");
        }
        TelephonyManager telephony = (TelephonyManager) context
                .getSystemService(Context.TELEPHONY_SERVICE);
        call_status_listener custom_phone_listener = new call_status_listener(context, incoming_number);
        assert telephony != null;
        telephony.listen(custom_phone_listener, PhoneStateListener.LISTEN_CALL_STATE);
    }

    static class call_status_listener extends PhoneStateListener {
        private static int lastState = TelephonyManager.CALL_STATE_IDLE;
        private static String incoming_number;
        private final Context context;

        call_status_listener(Context context, String incoming_number) {
            super();
            this.context = context;
            call_status_listener.incoming_number = incoming_number;
        }

        public void onCallStateChanged(int state, String incomingNumber) {
            if (lastState == TelephonyManager.CALL_STATE_RINGING
                    && state == TelephonyManager.CALL_STATE_IDLE) {
                final SharedPreferences sharedPreferences = context.getSharedPreferences("data", MODE_PRIVATE);
                if (!sharedPreferences.getBoolean("initialized", false)) {
                    Log.i("call_status_listener", "Uninitialized, Phone receiver is deactivated.");
                    return;
                }
                String bot_token = sharedPreferences.getString("bot_token", "");
                String chat_id = sharedPreferences.getString("chat_id", "");
                String request_uri = public_func.get_url(bot_token, "sendMessage");
                final message_json request_body = new message_json();
                request_body.chat_id = chat_id;
                request_body.text = "[" + context.getString(R.string.missed_call_head) + "]" + "\n" + context.getString(R.string.Incoming_number) + incoming_number;
                String request_body_raw = new Gson().toJson(request_body);
                RequestBody body = RequestBody.create(public_func.JSON, request_body_raw);
                OkHttpClient okhttp_client = public_func.get_okhttp_obj(sharedPreferences.getBoolean("doh_switch", true));
                Request request = new Request.Builder().url(request_uri).method("POST", body).build();
                Call call = okhttp_client.newCall(request);
                final String error_head = "Send missed call failed:";
                call.enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        e.printStackTrace();
                        String error_message = error_head + e.getMessage();
                        public_func.write_log(context, error_message);
                        public_func.send_fallback_sms(context, request_body.text);
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                        assert response.body() != null;
                        if (response.code() != 200) {

                            String error_message = error_head + response.code() + " " + response.body().string();
                            public_func.write_log(context, error_message);
                            public_func.send_fallback_sms(context, request_body.text);
                        } else {
                            String result = response.body().string();
                            JsonObject result_obj = JsonParser.parseString(result).getAsJsonObject().get("result").getAsJsonObject();
                            String message_id = result_obj.get("message_id").getAsString();
                            if (!public_func.is_phone_number(incoming_number)) {
                                public_func.write_log(context, "[" + incoming_number + "] Not a regular phone number.");
                                return;
                            }
                            public_func.add_message_list(message_id, incoming_number);
                        }
                    }
                });
            }

            lastState = state;
        }

    }

}

