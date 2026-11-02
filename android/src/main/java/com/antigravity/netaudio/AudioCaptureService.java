package com.antigravity.netaudio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class AudioCaptureService extends Service {
    private static final String TAG = "WOB";
    private static final String CHANNEL_ID = "WOB_Channel";
    private static final int NOTIF_ID = 101;
    private static final int TARGET_PORT = 58200;
    private static final String PREFS_NAME = "WOBPrefs";
    private static final String KEY_LAST_IP = "last_known_ip";

    public static final String ACTION_START = "ACTION_START";
    public static final String ACTION_STOP = "ACTION_STOP";
    public static final String ACTION_VOL_UP = "ACTION_VOL_UP";
    public static final String ACTION_VOL_DOWN = "ACTION_VOL_DOWN";
    public static final String ACTION_SET_VOL = "ACTION_SET_VOL";
    public static final String EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE";
    public static final String EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA";
    public static final String EXTRA_VOLUME = "EXTRA_VOLUME";

    public static boolean isRunning = false;
    public static volatile int currentVolumePercent = 100; // 0..100%

    private MediaProjection mediaProjection;
    private AudioRecord audioRecord;
    private DatagramSocket udpSocket;
    private Thread workerThread;
    private volatile boolean isCapturing = false;

    private AudioManager audioManager;
    private int originalMediaVolume = -1;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private NotificationManager notificationManager;

    @Override
    public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "WOB Streaming",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Audio en vivo a REAPER (WOB)");
            if (notificationManager != null) notificationManager.createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopCapturing();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_VOL_UP.equals(action)) {
            currentVolumePercent = Math.min(100, currentVolumePercent + 10);
            updateNotification();
            return START_NOT_STICKY;
        }

        if (ACTION_VOL_DOWN.equals(action)) {
            currentVolumePercent = Math.max(0, currentVolumePercent - 10);
            updateNotification();
            return START_NOT_STICKY;
        }

        if (ACTION_SET_VOL.equals(action)) {
            currentVolumePercent = Math.max(0, Math.min(100, intent.getIntExtra(EXTRA_VOLUME, 100)));
            updateNotification();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action)) {
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);

            startForegroundNotification();
            startStreamingFlow(resultCode, resultData);
        }

        return START_NOT_STICKY;
    }

    private Notification buildNotification() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        // Action Volume Down
        Intent downIntent = new Intent(this, AudioCaptureService.class);
        downIntent.setAction(ACTION_VOL_DOWN);
        PendingIntent piDown = PendingIntent.getService(this, 1, downIntent, flags);

        // Action Volume Up
        Intent upIntent = new Intent(this, AudioCaptureService.class);
        upIntent.setAction(ACTION_VOL_UP);
        PendingIntent piUp = PendingIntent.getService(this, 2, upIntent, flags);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
                .setContentTitle("WOB: En vivo a REAPER")
                .setContentText("Volumen: " + currentVolumePercent + "% (Toca - / +)")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .addAction(android.R.drawable.ic_media_previous, "[-] Bajar", piDown)
                .addAction(android.R.drawable.ic_media_next, "[+] Subir", piUp)
                .setOngoing(true)
                .build();
    }

    private void startForegroundNotification() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIF_ID, notification);
        }
        isRunning = true;
    }

    private void updateNotification() {
        if (notificationManager != null && isCapturing) {
            notificationManager.notify(NOTIF_ID, buildNotification());
        }
    }

    private void startStreamingFlow(int resultCode, Intent resultData) {
        if (isCapturing) return;

        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm == null || resultData == null) {
            stopSelf();
            return;
        }

        mediaProjection = mpm.getMediaProjection(resultCode, resultData);
        if (mediaProjection == null) {
            stopSelf();
            return;
        }

        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WOB:WakeLock");
                wakeLock.acquire(12 * 60 * 60 * 1000L);
            }

            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                int wifiMode = WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    wifiMode = 4; // LOW_LATENCY
                }
                wifiLock = wm.createWifiLock(wifiMode, "WOB:WifiLock");
                wifiLock.acquire();
            }
        } catch (Exception ignored) {}

        if (audioManager != null) {
            originalMediaVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
        }

        isCapturing = true;
        workerThread = new Thread(() -> runStreaming());
        workerThread.start();
    }

    private String discoverTargetPC() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String lastKnownIp = prefs.getString(KEY_LAST_IP, "192.168.0.116");

        try {
            DatagramSocket discoverySock = new DatagramSocket();
            discoverySock.setBroadcast(true);
            discoverySock.setSoTimeout(400);

            byte[] discoverMsg = "NA_DISCOVER".getBytes();

            try {
                discoverySock.send(new DatagramPacket(discoverMsg, discoverMsg.length, InetAddress.getByName("255.255.255.255"), TARGET_PORT));
                discoverySock.send(new DatagramPacket(discoverMsg, discoverMsg.length, InetAddress.getByName("192.168.0.255"), TARGET_PORT));
            } catch (Exception ignored) {}

            if (lastKnownIp != null && !lastKnownIp.isEmpty()) {
                try {
                    discoverySock.send(new DatagramPacket(discoverMsg, discoverMsg.length, InetAddress.getByName(lastKnownIp), TARGET_PORT));
                } catch (Exception ignored) {}
            }

            byte[] recvBuf = new byte[64];
            DatagramPacket recvPacket = new DatagramPacket(recvBuf, recvBuf.length);

            long deadline = System.currentTimeMillis() + 600;
            while (System.currentTimeMillis() < deadline) {
                try {
                    discoverySock.receive(recvPacket);
                    String reply = new String(recvPacket.getData(), 0, recvPacket.getLength());
                    if (reply.contains("NA_OFFER")) {
                        String foundIp = recvPacket.getAddress().getHostAddress();
                        prefs.edit().putString(KEY_LAST_IP, foundIp).apply();
                        discoverySock.close();
                        Log.i(TAG, "REAPER WOB discovered at: " + foundIp);
                        return foundIp;
                    }
                } catch (Exception e) {
                    break;
                }
            }

            final AtomicBoolean found = new AtomicBoolean(false);
            final String[] resultIp = new String[]{lastKnownIp};
            ExecutorService pool = Executors.newFixedThreadPool(16);

            for (int i = 1; i <= 254; i++) {
                final String probeIp = "192.168.0." + i;
                pool.execute(() -> {
                    if (found.get()) return;
                    try {
                        DatagramSocket s = new DatagramSocket();
                        s.setSoTimeout(250);
                        s.send(new DatagramPacket(discoverMsg, discoverMsg.length, InetAddress.getByName(probeIp), TARGET_PORT));
                        byte[] b = new byte[32];
                        DatagramPacket p = new DatagramPacket(b, b.length);
                        s.receive(p);
                        String str = new String(p.getData(), 0, p.getLength());
                        if (str.contains("NA_OFFER")) {
                            found.set(true);
                            resultIp[0] = probeIp;
                        }
                        s.close();
                    } catch (Exception ignored) {}
                });
            }
            pool.shutdown();
            try { Thread.sleep(250); } catch (InterruptedException ignored) {}

            discoverySock.close();
            if (found.get()) {
                prefs.edit().putString(KEY_LAST_IP, resultIp[0]).apply();
                return resultIp[0];
            }

        } catch (Exception e) {
            Log.e(TAG, "Discovery error: " + e.getMessage());
        }

        return lastKnownIp;
    }

    private void runStreaming() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);

        String targetIp = discoverTargetPC();
        Log.i(TAG, "Streaming to target: " + targetIp);

        final int sampleRate = 48000;
        final int channelConfig = AudioFormat.CHANNEL_IN_STEREO;
        final int encoding = AudioFormat.ENCODING_PCM_16BIT;

        try {
            AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();

            int minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding);
            int bufSize = Math.max(minBuf, 48000 * 2 * 2 / 10);

            audioRecord = new AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(encoding)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfig)
                            .build())
                    .setBufferSizeInBytes(bufSize)
                    .build();

            udpSocket = new DatagramSocket();
            udpSocket.setSendBufferSize(256 * 1024);
            InetAddress targetAddr = InetAddress.getByName(targetIp);

            byte[] initPing = new byte[]{0x4E, 0x41, 0, 0};
            udpSocket.send(new DatagramPacket(initPing, initPing.length, targetAddr, TARGET_PORT));

            final int samplesPerPacket = 480; // 5ms
            short[] audioBuffer = new short[samplesPerPacket];
            byte[] packetBytes = new byte[4 + samplesPerPacket * 2]; // 964 bytes
            ByteBuffer bb = ByteBuffer.wrap(packetBytes).order(ByteOrder.LITTLE_ENDIAN);
            short seq = 1;

            audioRecord.startRecording();
            Log.i(TAG, "Audio recording started");

            long lastSendTime = System.currentTimeMillis();

            while (isCapturing) {
                int read = audioRecord.read(audioBuffer, 0, samplesPerPacket, AudioRecord.READ_BLOCKING);
                if (read > 0) {
                    float gain = (float) currentVolumePercent / 100.0f;

                    bb.clear();
                    bb.putShort((short) 0x4E41);
                    bb.putShort(seq++);
                    for (int i = 0; i < read; i++) {
                        short val = (short) Math.max(-32767, Math.min(32767, (int) (audioBuffer[i] * gain)));
                        bb.putShort(val);
                    }

                    DatagramPacket packet = new DatagramPacket(packetBytes, 4 + read * 2, targetAddr, TARGET_PORT);
                    udpSocket.send(packet);
                    lastSendTime = System.currentTimeMillis();
                } else {
                    long now = System.currentTimeMillis();
                    if (now - lastSendTime > 150) {
                        udpSocket.send(new DatagramPacket(initPing, initPing.length, targetAddr, TARGET_PORT));
                        lastSendTime = now;
                    }
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Streaming error: " + e.getMessage(), e);
        } finally {
            cleanup();
        }
    }

    private void cleanup() {
        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
                audioRecord.release();
            } catch (Exception ignored) {}
            audioRecord = null;
        }

        if (udpSocket != null && !udpSocket.isClosed()) {
            udpSocket.close();
            udpSocket = null;
        }

        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }

        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Exception ignored) {}
            wakeLock = null;
        }

        if (wifiLock != null && wifiLock.isHeld()) {
            try { wifiLock.release(); } catch (Exception ignored) {}
            wifiLock = null;
        }

        if (originalMediaVolume >= 0 && audioManager != null) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalMediaVolume, 0);
            originalMediaVolume = -1;
        }
    }

    private void stopCapturing() {
        isCapturing = false;
        if (workerThread != null) {
            workerThread.interrupt();
            workerThread = null;
        }
        cleanup();
        isRunning = false;
    }

    @Override
    public void onDestroy() {
        stopCapturing();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
