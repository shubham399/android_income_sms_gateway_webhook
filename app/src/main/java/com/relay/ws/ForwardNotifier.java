package com.relay.ws;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

// Posts a visible notification for every SMS that is handed to the webhook so
// forwarding never happens silently behind the user's back. The notification is
// how a phone owner can see, in the status shade, exactly when a message is sent
// and from whom. Replaces rather than stacks: each forward overwrites the
// previous one, so a sensitive message body (e.g. an OTP) is not left sitting
// in a pile of notifications on the lock screen.
public class ForwardNotifier {

    private static final String CHANNEL_ID = "SmsForwarded";
    private static final int NOTIFICATION_ID = 2;
    private static final int MAX_BODY_LENGTH = 120;

    private ForwardNotifier() {
    }

    public static void notify(@NonNull Context context, @NonNull String sender, @NonNull String content) {
        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);

        createChannel(context);

        String body = content.length() > MAX_BODY_LENGTH
                ? content.substring(0, MAX_BODY_LENGTH) + "…"
                : content;

        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_f)
                .setColor(context.getColor(R.color.colorPrimary))
                .setContentTitle(context.getString(R.string.notification_forwarded_title))
                .setContentText(context.getString(R.string.notification_forwarded_text, sender, body))
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(context.getString(R.string.notification_forwarded_text, sender, body)))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build();

        // Without POST_NOTIFICATIONS (Android 13+) NotificationManagerCompat
        // drops the notification silently, which is the right behaviour here:
        // forwarding still works, the disclosure just stays hidden.
        notificationManager.notify(NOTIFICATION_ID, notification);
    }

    private static void createChannel(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager notificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_forwarded),
                NotificationManager.IMPORTANCE_DEFAULT);
        notificationManager.createNotificationChannel(channel);
    }
}