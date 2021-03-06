package com.qwe7002.telegram_sms_compat;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import io.paperdb.Paper;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;


public class chat_command_service extends Service {
    private static long offset = 0;
    private static int magnification = 1;
    private static int error_magnification = 1;
    private String chat_id;
    private String bot_token;
    private Context context;
    private OkHttpClient okhttp_client;
    private broadcast_receiver broadcast_receiver = null;
    private PowerManager.WakeLock wakelock;
    private WifiManager.WifiLock wifiLock;
    private int send_sms_next_status = -1;
    private String send_to_temp;
    private String bot_username = "";
    private final String TAG = "chat_command_service";
    static Thread thread_main;
    private boolean have_bot_username = false;
    private boolean privacy_mode;
    private SharedPreferences sharedPreferences;
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = public_func.get_notification_obj(getApplicationContext(), getString(R.string.chat_command_service_name));
        startForeground(public_func.CHAT_COMMAND_NOTIFY_ID, notification);
        return START_STICKY;
    }

    @SuppressLint({"InvalidWakeLockTag", "WakelockTimeout"})
    @Override
    public void onCreate() {
        super.onCreate();
        context = getApplicationContext();
        Paper.init(context);
        sharedPreferences = context.getSharedPreferences("data", MODE_PRIVATE);
        chat_id = sharedPreferences.getString("chat_id", "");
        bot_token = sharedPreferences.getString("bot_token", "");
        okhttp_client = public_func.get_okhttp_obj(sharedPreferences.getBoolean("doh_switch", true));
        privacy_mode = sharedPreferences.getBoolean("privacy_mode", false);
        wifiLock = ((WifiManager) Objects.requireNonNull(context.getApplicationContext().getSystemService(Context.WIFI_SERVICE))).createWifiLock(WifiManager.WIFI_MODE_FULL, "bot_command_polling_wifi");
        wakelock = ((PowerManager) Objects.requireNonNull(context.getSystemService(Context.POWER_SERVICE))).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "bot_command_polling");
        wifiLock.setReferenceCounted(false);
        wakelock.setReferenceCounted(false);
        if(!wifiLock.isHeld()){
            wifiLock.acquire();
        }
        if (!wakelock.isHeld()) {
            wakelock.acquire();
        }
        thread_main = new Thread(new thread_main_runnable());
        thread_main.start();
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(public_func.BROADCAST_STOP_SERVICE);
        intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        broadcast_receiver = new broadcast_receiver();
        registerReceiver(broadcast_receiver, intentFilter);


    }

    private void receive_handle(JsonObject result_obj) {
        String message_type = "";
        long update_id = result_obj.get("update_id").getAsLong();
        offset = update_id + 1;
        final message_json request_body = new message_json();
        request_body.chat_id = chat_id;
        JsonObject message_obj = null;
        if (result_obj.has("message")) {
            message_obj = result_obj.get("message").getAsJsonObject();
            message_type = message_obj.get("chat").getAsJsonObject().get("type").getAsString();
        }
        if (result_obj.has("channel_post")) {
            message_type = "channel";
            message_obj = result_obj.get("channel_post").getAsJsonObject();
        }
        if (message_obj == null) {
            //Reject group request
            public_func.write_log(context, "Request type is not allowed by security policy.");
            return;
        }
        JsonObject from_obj = null;
        final boolean message_type_is_private = message_type.equals("private");
        if (!message_type_is_private && !have_bot_username) {
            Log.i(TAG, "receive_handle: Did not successfully get bot_username.");
            get_me();
        }
        if (message_obj.has("from")) {
            from_obj = message_obj.get("from").getAsJsonObject();
            if (!message_type_is_private && from_obj.get("is_bot").getAsBoolean()) {
                Log.i(TAG, "receive_handle: receive from bot.");
                return;
            }
        }
        if (message_obj.has("chat")) {
            from_obj = message_obj.get("chat").getAsJsonObject();
        }

        assert from_obj != null;
        String from_id = from_obj.get("id").getAsString();
        if (!chat_id.equals(from_id)) {
            public_func.write_log(context, "Chat ID[" + from_id + "] not allow");
            return;
        }

        String command = "";
        String command_bot_username = "";
        String request_msg = "";
        if (message_obj.has("text")) {
            request_msg = message_obj.get("text").getAsString();
        }
        if (message_obj.has("reply_to_message")) {
            String phone_number = Paper.book().read(message_obj.get("reply_to_message").getAsJsonObject().get("message_id").getAsString(), null);
            if (phone_number != null) {
                public_func.send_sms(context, phone_number, request_msg);
                return;
            }
            if (!message_type_is_private) {
                Log.i(TAG, "receive_handle: The message id could not be found, ignored.");
                return;
            }
        }
        if (message_obj.has("entities")) {
            String temp_command;
            String temp_command_lowercase;
            JsonArray entities_arr = message_obj.get("entities").getAsJsonArray();
            JsonObject entities_obj_command = entities_arr.get(0).getAsJsonObject();
            if (entities_obj_command.get("type").getAsString().equals("bot_command")) {
                int command_offset = entities_obj_command.get("offset").getAsInt();
                int command_end_offset = command_offset + entities_obj_command.get("length").getAsInt();
                temp_command = request_msg.substring(command_offset, command_end_offset).trim();
                temp_command_lowercase = temp_command.toLowerCase();
                command = temp_command_lowercase;
                if (temp_command_lowercase.contains("@")) {
                    int command_at_location = temp_command_lowercase.indexOf("@");
                    command = temp_command_lowercase.substring(0, command_at_location);
                    command_bot_username = temp_command.substring(command_at_location + 1);
                }
            }
        }
        if (!message_type_is_private && privacy_mode && !command_bot_username.equals(bot_username)) {
            Log.i(TAG, "receive_handle: Privacy mode, no username found.");
            return;
        }

        boolean has_command = false;
        switch (command) {
            case "/help":
            case "/start":
            case "/commandlist":
                request_body.text = getString(R.string.system_message_head) + "\n" + getString(R.string.available_command) + "\n" + getString(R.string.sendsms) + "\n" + getString(R.string.get_spam_sms);
                if (!message_type_is_private && privacy_mode && !bot_username.equals("")) {
                    request_body.text = request_body.text.replace(" -", "@" + bot_username + " -");
                }
                if (command.equals("/commandlist")) {
                    request_body.text = request_body.text.replace("/", "");
                }
                has_command = true;
                break;
            case "/ping":
            case "/getinfo":
                String spam_count = "";
                ArrayList<String> spam_list = Paper.book().read("spam_sms_list", new ArrayList<>());
                if (spam_list.size() != 0) {
                    spam_count = "\n" + getString(R.string.spam_count_title) + spam_list.size();
                }
                request_body.text = getString(R.string.system_message_head) + "\n" + context.getString(R.string.current_battery_level) + get_battery_info(context) + "\n" + getString(R.string.current_network_connection_status) + public_func.get_network_type(context) + spam_count + "\nSIM: " + public_func.get_sim_name(context);
                has_command = true;
                break;
            case "/log":
                request_body.text = getString(R.string.system_message_head) + public_func.read_log(context, 10);
                has_command = true;
                break;
            case "/getspamsms":
                ArrayList<String> spam_sms_list = Paper.book().read("spam_sms_list", new ArrayList<>());
                if (spam_sms_list.size() == 0) {
                    request_body.text = context.getString(R.string.system_message_head) + "\n" + getString(R.string.no_spam_history);
                    break;
                }
                new Thread(() -> {
                    if (public_func.check_network_status(context)) {
                        OkHttpClient okhttp_client = public_func.get_okhttp_obj(sharedPreferences.getBoolean("doh_switch", true));
                        for (String item : spam_sms_list) {
                            message_json send_sms_request_body = new message_json();
                            send_sms_request_body.chat_id = chat_id;
                            send_sms_request_body.text = item;
                            String request_uri = public_func.get_url(bot_token, "sendMessage");
                            String request_body_json = new Gson().toJson(send_sms_request_body);
                            RequestBody body = RequestBody.create(public_func.JSON, request_body_json);
                            Request request_obj = new Request.Builder().url(request_uri).method("POST", body).build();
                            Call call = okhttp_client.newCall(request_obj);
                            call.enqueue(new Callback() {
                                @Override
                                public void onFailure(@NotNull Call call, @NotNull IOException e) {
                                    Log.d(TAG, "onFailure: ");
                                }

                                @Override
                                public void onResponse(@NotNull Call call, @NotNull Response response) {

                                }
                            });
                            ArrayList<String> resend_list_local = Paper.book().read("spam_sms_list", new ArrayList<>());
                            resend_list_local.remove(item);
                            Paper.book().write("spam_sms_list", resend_list_local);
                        }
                    }
                    public_func.write_log(context, "Send spam message is complete.");
                }).start();
                return;
            case "/sendsms":
                String[] msg_send_list = request_msg.split("\n");
                if (msg_send_list.length > 2) {
                    String msg_send_to = public_func.get_send_phone_number(msg_send_list[1]);
                    if (public_func.is_phone_number(msg_send_to)) {
                        StringBuilder msg_send_content = new StringBuilder();
                        for (int i = 2; i < msg_send_list.length; ++i) {
                            if (msg_send_list.length != 3 && i != 2) {
                                msg_send_content.append("\n");
                            }
                            msg_send_content.append(msg_send_list[i]);
                        }
                        public_func.send_sms(context, msg_send_to, msg_send_content.toString());
                        return;
                    }
                    has_command = true;
                } else if (message_type_is_private) {
                    send_sms_next_status = SEND_SMS_STATUS.PHONE_INPUT_STATUS;
                    has_command = false;
                }
                request_body.text = "[" + context.getString(R.string.send_sms_head) + "]" + "\n" + getString(R.string.failed_to_get_information);
                break;
            default:
                if (!message_type_is_private) {
                    Log.i(TAG, "receive_handle: The conversation is not Private and does not prompt an error.");
                    return;
                }
                request_body.text = context.getString(R.string.system_message_head) + "\n" + getString(R.string.unknown_command);
                break;
        }
        if (has_command) {
            send_sms_next_status = SEND_SMS_STATUS.STANDBY_STATUS;
            send_to_temp = null;
        }
        if (!has_command && send_sms_next_status != SEND_SMS_STATUS.STANDBY_STATUS) {
            Log.i(TAG, "receive_handle: Enter the interactive SMS sending mode.");
            String result_send = getString(R.string.failed_to_get_information);
            //Log.d(TAG, "Sending mode status: " + send_sms_next_status);
            switch (send_sms_next_status) {
                case SEND_SMS_STATUS.PHONE_INPUT_STATUS:
                    send_sms_next_status = SEND_SMS_STATUS.MESSAGE_INPUT_STATUS;
                    result_send = getString(R.string.enter_number);
                    break;
                case SEND_SMS_STATUS.MESSAGE_INPUT_STATUS:
                    String temp_to = public_func.get_send_phone_number(request_msg);
                    Log.d(TAG, "receive_handle: " + temp_to);
                    if (public_func.is_phone_number(temp_to)) {
                        send_to_temp = temp_to;
                        result_send = getString(R.string.enter_content);
                        send_sms_next_status = SEND_SMS_STATUS.WAITING_TO_SEND_STATUS;
                    } else {
                        send_sms_next_status = SEND_SMS_STATUS.STANDBY_STATUS;
                        send_to_temp = null;
                        result_send = getString(R.string.unable_get_phone_number);
                    }
                    break;
                case SEND_SMS_STATUS.WAITING_TO_SEND_STATUS:
                    public_func.send_sms(context, send_to_temp, request_msg);
                    return;
            }
            request_body.text = "[" + context.getString(R.string.send_sms_head) + "]" + "\n" + result_send;
        }

        String request_uri = public_func.get_url(bot_token, "sendMessage");
        RequestBody body = RequestBody.create(public_func.JSON, new Gson().toJson(request_body));
        Request send_request = new Request.Builder().url(request_uri).method("POST", body).build();
        Call call = okhttp_client.newCall(send_request);
        final String error_head = "Send reply failed:";
        call.enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                e.printStackTrace();
                public_func.write_log(context, error_head + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.code() != 200) {
                    assert response.body() != null;
                    public_func.write_log(context, error_head + response.code() + " " + response.body().string());
                }
            }
        });
    }

    private static class SEND_SMS_STATUS {
        public static final int STANDBY_STATUS = -1;
        public static final int PHONE_INPUT_STATUS = 0;
        public static final int MESSAGE_INPUT_STATUS = 1;
        public static final int WAITING_TO_SEND_STATUS = 2;
    }

    @Override
    public void onDestroy() {
        wifiLock.release();
        wakelock.release();
        unregisterReceiver(broadcast_receiver);
        stopForeground(true);
        super.onDestroy();
    }

    private void get_me() {
        OkHttpClient okhttp_client_new = okhttp_client;
        String request_uri = public_func.get_url(bot_token, "getMe");
        Request request = new Request.Builder().url(request_uri).build();
        Call call = okhttp_client_new.newCall(request);
        Response response;
        try {
            response = call.execute();
        } catch (IOException e) {
            e.printStackTrace();
            public_func.write_log(context, "Get username failed:" + e.getMessage());
            return;
        }
        if (response.code() == 200) {
            String result = null;
            try {
                result = Objects.requireNonNull(response.body()).string();
            } catch (IOException e) {
                e.printStackTrace();
            }
            assert result != null;
            JsonObject result_obj = JsonParser.parseString(result).getAsJsonObject();
            if (result_obj.get("ok").getAsBoolean()) {
                bot_username = result_obj.get("result").getAsJsonObject().get("username").getAsString();
                have_bot_username = true;
                Log.d(TAG, "bot_username: " + bot_username);
            }
        }

    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    class thread_main_runnable implements Runnable {
        @SuppressWarnings("BusyWait")
        @Override
        public void run() {
            Log.d(TAG, "run: thread main start");
            if (public_func.parse_long(chat_id) < 0 && !have_bot_username) {
                new Thread(chat_command_service.this::get_me).start();
            }
            while (true) {
                int timeout = 5 * magnification;
                OkHttpClient okhttp_client_new = okhttp_client.newBuilder()
                        .readTimeout((timeout), TimeUnit.SECONDS)
                        .writeTimeout((timeout), TimeUnit.SECONDS)
                        .build();
                String request_uri = public_func.get_url(bot_token, "getUpdates");
                polling_json request_body = new polling_json();
                request_body.offset = offset;
                request_body.timeout = timeout;
                RequestBody body = RequestBody.create(public_func.JSON, new Gson().toJson(request_body));
                Request request = new Request.Builder().url(request_uri).method("POST", body).build();
                Call call = okhttp_client_new.newCall(request);
                Response response;
                try {
                    response = call.execute();
                    error_magnification = 1;
                } catch (IOException e) {
                    e.printStackTrace();
                    if (!public_func.check_network_status(context)) {
                        public_func.write_log(context, "No network connections available. ");
                        error_magnification = 1;
                        magnification = 1;
                        Log.d(TAG, "run: break while.");
                        break;
                    }
                    int sleep_time = 5 * error_magnification;
                    public_func.write_log(context, "Connection to the Telegram API service failed,try again after " + sleep_time + " seconds.");
                    magnification = 1;
                    if (error_magnification <= 59) {
                        ++error_magnification;
                    }
                    try {
                        Thread.sleep(sleep_time * 1000);
                    } catch (InterruptedException e1) {
                        e1.printStackTrace();
                    }
                    continue;

                }
                if (response.code() == 200) {
                    assert response.body() != null;
                    String result;
                    try {
                        result = Objects.requireNonNull(response.body()).string();
                    } catch (IOException e) {
                        e.printStackTrace();
                        continue;
                    }
                    JsonObject result_obj = JsonParser.parseString(result).getAsJsonObject();
                    if (result_obj.get("ok").getAsBoolean()) {
                        JsonArray result_array = result_obj.get("result").getAsJsonArray();
                        for (JsonElement item : result_array) {
                            receive_handle(item.getAsJsonObject());
                        }
                    }
                    if (magnification <= 11) {
                        ++magnification;
                    }
                } else {
                    public_func.write_log(context, "response code:" + response.code());
                    if (response.code() == 401) {
                        assert response.body() != null;
                        String result;
                        try {
                            result = Objects.requireNonNull(response.body()).string();
                        } catch (IOException e) {
                            e.printStackTrace();
                            continue;
                        }
                        JsonObject result_obj = JsonParser.parseString(result).getAsJsonObject();
                        String result_message = getString(R.string.system_message_head) + "\n" + getString(R.string.error_stop_message) + "\n" + getString(R.string.error_message_head) + result_obj.get("description").getAsString() + "\n" + "Code: " + response.code();
                        public_func.send_fallback_sms(context, result_message);
                        public_func.stop_all_service(context);
                        break;
                    }

                }
            }
        }
    }
    private String get_battery_info(Context context) {
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = context.registerReceiver(null, filter);
        assert batteryStatus != null;
        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int battery_level = -1;
        if (level != -1 && scale != -1) {
            battery_level = (int) ((level / (float) scale) * 100);
        }

        if (battery_level > 100) {
            Log.d(TAG, "The previous battery is over 100%, and the correction is 100%.");
            battery_level = 100;
        }

        StringBuilder battery_string_builder = new StringBuilder().append(battery_level).append("%");
        int charge_status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        switch (charge_status) {
            case BatteryManager.BATTERY_STATUS_CHARGING:
            case BatteryManager.BATTERY_STATUS_FULL:
                battery_string_builder.append(" (").append(context.getString(R.string.charging)).append(")");
                break;
            case BatteryManager.BATTERY_STATUS_DISCHARGING:
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING:
                switch (batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)) {
                    case BatteryManager.BATTERY_PLUGGED_AC:
                    case BatteryManager.BATTERY_PLUGGED_USB:
                    case BatteryManager.BATTERY_PLUGGED_WIRELESS:
                        battery_string_builder.append(" (").append(context.getString(R.string.not_charging)).append(")");
                        break;
                }
                break;
        }
        return battery_string_builder.toString();
    }

    private class broadcast_receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            assert intent.getAction() != null;
            switch (intent.getAction()) {
                case public_func.BROADCAST_STOP_SERVICE:
                    Log.i(TAG, "Received stop signal, quitting now...");
                    stopSelf();
                    android.os.Process.killProcess(android.os.Process.myPid());
                    break;
                case ConnectivityManager.CONNECTIVITY_ACTION:
                    if (public_func.check_network_status(context)) {
                        if (!thread_main.isAlive()) {
                            public_func.write_log(context, "Network connections has been restored.");
                            thread_main = new Thread(new thread_main_runnable());
                            thread_main.start();
                        }
                    }
                    break;
            }
        }
    }

}

