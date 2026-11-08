package xiaojw.BydRoot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * 刷机 / 分区备份期间的前台保活服务。
 * 备份可能持续数十分钟，退到后台若被系统杀掉，USB 连接中断会直接毁掉正在写入的镜像。
 */
public final class TaskService extends Service {

    private static final String CHANNEL_ID = "BydRoot-task";
    private static final int NOTIFICATION_ID = 1001;

    @Override
    public void onCreate() {
        super.onCreate();
        // 服务一创建就进入前台状态：startForegroundService 之后若任务很快结束并调用
        // stopService，系统会判定前台服务从未提升，约 5 秒后异步抛 RemoteServiceException
        // 直接把 app 干掉（该异常不在调用方的 try-catch 范围内）。把 startForeground 放在
        // onCreate 而非 onStartCommand，可彻底消除这个竞态窗口。
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "刷机 / 备份任务", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("长时间 USB 任务保活");
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null) nm.createNotificationChannel(ch);
            }
            Notification n = buildNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
        } catch (Throwable ignored) {
            // 通知/前台提升失败绝不能让 app 闪退，退化为普通服务即可。
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 前台提升已在 onCreate 完成，这里不再处理。
        return START_NOT_STICKY;
    }

    private Notification buildNotification() {
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("BydRoot 正在执行")
                .setContentText("USB 刷机 / 分区备份进行中，请勿断开数据线")
                .setOngoing(true)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
