package com.qwe7002.telegram_sms_compat;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.core.app.NotificationManagerCompat;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.conscrypt.Conscrypt;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.security.Security;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import io.paperdb.Paper;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.dnsoverhttps.DnsOverHttps;


class public_func {
    static final String BROADCAST_STOP_SERVICE = "com.qwe7002.telegram_sms_compat.stop_all";
    static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    static long parse_long(String long_str) {
        long result = 0;
        try {
            result = Long.parseLong(long_str);
        } catch (NumberFormatException e) {
            e.printStackTrace();
        }
        return result;
    }

    static String get_send_phone_number(String phone_number) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < phone_number.length(); ++i) {
            char c = phone_number.charAt(i);
            if (c == '+' || Character.isDigit(c)) {
                result.append(c);
            }
        }
        return result.toString();
    }

    static boolean check_network_status(Context context) {
        ConnectivityManager manager = (ConnectivityManager) context
                .getApplicationContext().getSystemService(
                        Context.CONNECTIVITY_SERVICE);
        assert manager != null;
        NetworkInfo networkinfo = manager.getActiveNetworkInfo();
        return networkinfo != null && networkinfo.isConnected();
    }

    static String get_url(String token, String func) {
        return "https://api.telegram.org/bot" + token + "/" + func;
    }

    static OkHttpClient get_okhttp_obj(boolean doh_switch) {
        Security.insertProviderAt(Conscrypt.newProvider(), 1);
        OkHttpClient.Builder okhttp = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true);
        if (doh_switch) {
            okhttp.dns(new DnsOverHttps.Builder().client(okhttp.build())
                    .url(HttpUrl.get("https://cloudflare-dns.com/dns-query"))
                    .bootstrapDnsHosts(get_by_ip("2606:4700:4700::1001"), get_by_ip("2606:4700:4700::1111"), get_by_ip("1.0.0.1"), get_by_ip("1.1.1.1"))
                    .includeIPv6(true)
                    .build());
        }
        return okhttp.build();
    }

    private static InetAddress get_by_ip(String host) {
        try {
            return InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    static boolean is_phone_number(String str) {
        for (int i = str.length(); --i >= 0; ) {
            char c = str.charAt(i);
            if (c == '+') {
                continue;
            }
            if (!Character.isDigit(c)) {
                return false;
            }
        }
        return true;
    }

    static String get_network_type(Context context) {
        String net_type = "Unknown";
        ConnectivityManager connect_manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        assert connect_manager != null;
        NetworkInfo network_info = connect_manager.getActiveNetworkInfo();
        if (network_info == null) {
            return net_type;
        }
        switch (network_info.getType()) {
            case ConnectivityManager.TYPE_WIFI:
                net_type = "WIFI";
                break;
            case ConnectivityManager.TYPE_MOBILE:
                TelephonyManager telephonyManager = (TelephonyManager) context
                        .getSystemService(Context.TELEPHONY_SERVICE);
                assert telephonyManager != null;
                switch (network_info.getSubtype()) {
                    case TelephonyManager.NETWORK_TYPE_LTE:
                        net_type = "LTE";
                        break;
                    case TelephonyManager.NETWORK_TYPE_HSPAP:
                    case TelephonyManager.NETWORK_TYPE_EVDO_0:
                    case TelephonyManager.NETWORK_TYPE_EVDO_A:
                    case TelephonyManager.NETWORK_TYPE_EVDO_B:
                    case TelephonyManager.NETWORK_TYPE_EHRPD:
                    case TelephonyManager.NETWORK_TYPE_HSDPA:
                    case TelephonyManager.NETWORK_TYPE_HSUPA:
                    case TelephonyManager.NETWORK_TYPE_HSPA:
                    case TelephonyManager.NETWORK_TYPE_TD_SCDMA:
                    case TelephonyManager.NETWORK_TYPE_UMTS:
                        net_type = "3G";
                        break;
                    case TelephonyManager.NETWORK_TYPE_GPRS:
                    case TelephonyManager.NETWORK_TYPE_EDGE:
                    case TelephonyManager.NETWORK_TYPE_CDMA:
                    case TelephonyManager.NETWORK_TYPE_1xRTT:
                    case TelephonyManager.NETWORK_TYPE_IDEN:
                        net_type = "2G";
                        break;
                }
                break;
        }
        return net_type;
    }

    static void send_sms(Context context, String send_to, String content) {
        if (!is_phone_number(send_to)) {
            write_log(context, "[" + send_to + "] is an illegal phone number.");
            return;
        }
        SharedPreferences sharedPreferences = context.getSharedPreferences("data", android.content.Context.MODE_PRIVATE);
        String bot_token = sharedPreferences.getString("bot_token", "");
        String chat_id = sharedPreferences.getString("chat_id", "");
        String request_uri = public_func.get_url(bot_token, "sendMessage");
        message_json request_body = new message_json();
        request_body.chat_id = chat_id;
        android.telephony.SmsManager sms_manager;
        sms_manager = android.telephony.SmsManager.getDefault();
        String send_content = "[" + context.getString(R.string.send_sms_head) + "]" + "\n" + context.getString(R.string.to) + send_to + "\n" + context.getString(R.string.content) + content;
        String message_id = "-1";
        request_body.text = send_content + "\n" + context.getString(R.string.status) + context.getString(R.string.sending);
        Gson gson = new Gson();
        String request_body_raw = gson.toJson(request_body);
        RequestBody body = RequestBody.create(public_func.JSON, request_body_raw);
        OkHttpClient okhttp_client = public_func.get_okhttp_obj(sharedPreferences.getBoolean("doh_switch", true));
        Request request = new Request.Builder().url(request_uri).method("POST", body).build();
        Call call = okhttp_client.newCall(request);
        try {
            Response response = call.execute();
            if (response.code() != 200 || response.body() == null) {
                throw new IOException(String.valueOf(response.code()));
            }
            message_id = get_message_id(response.body().string());
        } catch (IOException e) {
            e.printStackTrace();
            public_func.write_log(context, "failed to send message:" + e.getMessage());
        }
        ArrayList<String> divideContents = sms_manager.divideMessage(content);
        ArrayList<PendingIntent> send_receiver_list = new ArrayList<>();
        IntentFilter filter = new IntentFilter("send_sms");
        BroadcastReceiver receiver = new sms_send_receiver();
        context.getApplicationContext().registerReceiver(receiver, filter);
        Intent sent_intent = new Intent("send_sms");
        sent_intent.putExtra("message_id", message_id);
        sent_intent.putExtra("message_text", send_content);
        PendingIntent sentIntent = PendingIntent.getBroadcast(context, 0, sent_intent, PendingIntent.FLAG_CANCEL_CURRENT);
        send_receiver_list.add(sentIntent);
        sms_manager.sendMultipartTextMessage(send_to, null, divideContents, send_receiver_list, null);
    }

    static void send_fallback_sms(Context context, String content) {
        String TAG = "send_fallback_sms";
        SharedPreferences sharedPreferences = context.getSharedPreferences("data", android.content.Context.MODE_PRIVATE);
        if (!sharedPreferences.getBoolean("fallback_sms", false)) {
            Log.i(TAG, "Did not open the SMS to fall back.");
            return;
        }
        String trust_number = sharedPreferences.getString("trusted_phone_number", null);
        if (trust_number == null) {
            Log.i(TAG, "The trusted number is empty.");
            return;
        }
        android.telephony.SmsManager sms_manager = android.telephony.SmsManager.getDefault();
        ArrayList<String> divideContents = sms_manager.divideMessage(content);
        sms_manager.sendMultipartTextMessage(trust_number, null, divideContents, null, null);
    }

    static void add_resend_loop(Context context, String message) {
        ArrayList<String> resend_list;
        Paper.init(context);
        resend_list = Paper.book().read("resend_list", new ArrayList<>());
        resend_list.add(message);
        Paper.book().write("resend_list", resend_list);
        start_resend(context);
    }

    static void start_resend(Context context) {
        Intent intent = new Intent(context, resend_service.class);
        context.startService(intent);
    }

    static String get_message_id(String result) {
        JsonObject result_obj = JsonParser.parseString(result).getAsJsonObject().get("result").getAsJsonObject();
        return result_obj.get("message_id").getAsString();
    }

    static Notification get_notification_obj(Context context, String notification_name) {
        Notification.Builder result_builder = new Notification.Builder(context)
                .setAutoCancel(false)
                .setSmallIcon(R.drawable.ic_stat)
                .setOngoing(true)
                .setTicker(context.getString(R.string.app_name))
                .setWhen(System.currentTimeMillis())
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(notification_name + context.getString(R.string.service_is_running))
                .setPriority(Notification.PRIORITY_MIN);
        return result_builder.build();
    }

    static void stop_all_service(Context context) {
        Intent intent = new Intent(BROADCAST_STOP_SERVICE);
        context.sendBroadcast(intent);
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    static void start_service(Context context, Boolean battery_switch, Boolean chat_command_switch) {
        Intent battery_service = new Intent(context, com.qwe7002.telegram_sms_compat.battery_service.class);
        Intent chat_long_polling_service = new Intent(context, chat_command_service.class);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            if (is_notify_listener(context)) {
                if (is_notify_listener(context)) {
                    ComponentName thisComponent = new ComponentName(context, notification_listener_service.class);
                    PackageManager pm = context.getPackageManager();
                    pm.setComponentEnabledSetting(thisComponent, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
                    pm.setComponentEnabledSetting(thisComponent, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
                }
            }
        }
        if (battery_switch) {
            context.startService(battery_service);
        }
        if (chat_command_switch) {
            context.startService(chat_long_polling_service);
        }

    }

    static String get_sim_name(Context context) {
        String result = "Unknown";
        TelephonyManager telephonyManager = ((TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE));
        assert telephonyManager != null;
        if (!telephonyManager.getSimOperatorName().equals("")) {
            result = telephonyManager.getSimOperatorName();
        }
        if (!telephonyManager.getNetworkOperatorName().equals("")) {
            result = telephonyManager.getNetworkOperatorName();
        }
        return result;
    }


    static void write_log(Context context, String log) {
        Log.i("write_log", log);
        int new_file_mode = Context.MODE_APPEND;
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.UK);
        Date ts = new Date(System.currentTimeMillis());
        String write_string = "\n" + simpleDateFormat.format(ts) + " " + log;
        write_file(context, write_string, new_file_mode);
    }

    static String read_log(Context context, int line) {
        String result = "\n" + context.getString(R.string.no_logs);
        String log_content = public_func.read_log_last_line(context, line);
        if (!log_content.isEmpty()) {
            result = log_content;
        }
        return result;
    }

    static String read_log_last_line(Context context, int line) {
        StringBuilder builder = new StringBuilder();
        FileInputStream file_stream = null;
        FileChannel channel = null;
        try {
            file_stream = context.openFileInput("error.log");
            channel = file_stream.getChannel();
            ByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
            buffer.position((int) channel.size());
            int count = 0;
            for (long i = channel.size() - 1; i >= 0; i--) {
                char c = (char) buffer.get((int) i);
                builder.insert(0, c);
                if (c == '\n') {
                    if (count == (line - 1)) {
                        break;
                    }
                    ++count;
                }
            }
            return builder.toString();
        } catch (IOException e) {
            e.printStackTrace();
            return "";
        } finally {
            try {
                if (file_stream != null) {
                    file_stream.close();
                }
                if (channel != null) {
                    channel.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    static void write_file(Context context, String write_string, int mode) {
        FileOutputStream file_stream = null;
        try {
            file_stream = context.openFileOutput("error.log", mode);
            byte[] bytes = write_string.getBytes();
            file_stream.write(bytes);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (file_stream != null) {
                try {
                    file_stream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }


    static void add_message_list(String message_id, String phone) {
        Paper.book().write(message_id, phone);
        Log.d("add_message_list", "add_message_list: " + message_id);
    }

    static boolean is_notify_listener(Context context) {
        Set<String> packageNames = NotificationManagerCompat.getEnabledListenerPackages(context);
        return packageNames.contains(context.getPackageName());
    }

}
