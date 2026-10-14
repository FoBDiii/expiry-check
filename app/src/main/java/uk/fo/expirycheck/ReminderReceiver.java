package uk.fo.expirycheck;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/** Daily "what's expiring" notification, plus re-arming it after a reboot or app update. */
public class ReminderReceiver extends BroadcastReceiver {
    static final String KEY_ON = "rem.on";
    static final String KEY_HOUR = "rem.hour";
    static final String KEY_MINUTE = "rem.minute";
    static final String DATA_KEY = "ec.data";
    private static final String ACTION_FIRE = "uk.fo.expirycheck.REMINDER";
    private static final String CHANNEL_ID = "reminders";
    private static final int NOTIFICATION_ID = 1;

    @Override
    public void onReceive(Context ctx, Intent intent) {
        createChannel(ctx);
        if (ACTION_FIRE.equals(intent.getAction())) postSummary(ctx, false);
        schedule(ctx);
    }

    static void createChannel(Context ctx) {
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Expiry reminders", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("Daily list of items that have expired or expire soon");
        ctx.getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    static void schedule(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        Intent i = new Intent(ctx, ReminderReceiver.class).setAction(ACTION_FIRE);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        am.cancel(pi);
        if (!p.getBoolean(KEY_ON, false)) return;

        Calendar next = Calendar.getInstance();
        next.set(Calendar.HOUR_OF_DAY, p.getInt(KEY_HOUR, 7));
        next.set(Calendar.MINUTE, p.getInt(KEY_MINUTE, 0));
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        if (next.getTimeInMillis() <= System.currentTimeMillis() + 30_000) next.add(Calendar.DAY_OF_MONTH, 1);
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), pi);
    }

    /** Counts expired / today / soon items from the saved data and posts one notification. */
    static void postSummary(Context ctx, boolean evenIfNothing) {
        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return;
        int expired = 0, today = 0, soon = 0;
        List<String> names = new ArrayList<>();
        try {
            String raw = ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).getString(DATA_KEY, null);
            if (raw != null) {
                JSONObject data = new JSONObject(raw);
                JSONObject products = data.optJSONObject("products");
                JSONObject settings = data.optJSONObject("settings");
                int warn = settings == null ? 3 : settings.optInt("warnDays", 3);
                JSONArray items = data.optJSONArray("items");
                LocalDate now = LocalDate.now();
                for (int i = 0; items != null && i < items.length(); i++) {
                    JSONObject it = items.getJSONObject(i);
                    if (!"active".equals(it.optString("status", "active"))) continue;
                    String exp = it.optString("expiry", "");
                    if (exp.length() != 10) continue;
                    long days = ChronoUnit.DAYS.between(now, LocalDate.parse(exp));
                    if (days > warn) continue;
                    if (days < 0) expired++;
                    else if (days == 0) today++;
                    else soon++;
                    if (days <= 0 && names.size() < 6) {
                        JSONObject prod = products == null ? null : products.optJSONObject(it.optString("code"));
                        names.add(prod == null ? it.optString("code") : prod.optString("name", it.optString("code")));
                    }
                }
            }
        } catch (Exception ignored) {
        }

        String title;
        if (expired + today + soon == 0) {
            if (!evenIfNothing) return;
            title = "Nothing expiring soon";
        } else {
            List<String> parts = new ArrayList<>();
            if (expired > 0) parts.add(expired + " expired");
            if (today > 0) parts.add(today + " expire today");
            if (soon > 0) parts.add(soon + " expire soon");
            title = String.join(" · ", parts);
        }
        String text = names.isEmpty() ? "Open Expiry Check to see the list." : "Check: " + String.join(", ", names);

        Intent open = new Intent(ctx, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_expiry)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(pi)
                .build();
        ctx.getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, n);
    }
}
