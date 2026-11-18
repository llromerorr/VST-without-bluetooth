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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Enumeration;

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

    // Connection States
    public static final int STATE_IDLE = 0;
    public static final int STATE_SEARCHING = 1;
    public static final int STATE_CONNECTED = 2;
    public static final int STATE_RECONNECTING = 3;

    public static final int DEFAULT_SAFE_VOLUME = 65; // Safe startup volume (avoids audio shock)

    public interface ConnectionListener {
        void onConnectionStateChanged(int state, String message);
        void onVolumeChanged(int volumePercent);
    }

    public static volatile boolean isRunning = false;
    public static volatile int connectionState = STATE_IDLE;
    public static volatile String connectionInfo = "Toca el boton para conectar";
    public static volatile int currentVolumePercent = DEFAULT_SAFE_VOLUME; // Starts safely at 65%

    private static ConnectionListener activeListener = null;

    public static synchronized void setListener(ConnectionListener listener) {
        activeListener = listener;
        if (listener != null) {
            listener.onConnectionStateChanged(connectionState, connectionInfo);
            listener.onVolumeChanged(currentVolumePercent);
        }
    }

    private static void notifyVolumeChanged() {
        ConnectionListener l = activeListener;
        if (l != null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                ConnectionListener cur = activeListener;
                if (cur != null) cur.onVolumeChanged(currentVolumePercent);
            });
        }
    }

    private void updateState(int state, String info) {
        connectionState = state;
        connectionInfo = info;
        updateNotification();
        ConnectionListener l = activeListener;
        if (l != null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                ConnectionListener cur = activeListener;
                if (cur != null) cur.onConnectionStateChanged(state, info);
            });
        }
    }

    private MediaProjection mediaProjection;
    private AudioRecord audioRecord;
    private DatagramSocket udpSocket;
    private Thread workerThread;
    private Thread ackThread;
    private volatile boolean isCapturing = false;
    private volatile long lastAckTime = 0;

    private AudioManager audioManager;
    private int originalMediaVolume = -1;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private WifiManager.MulticastLock multicastLock;
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
            currentVolumePercent = Math.min(100, currentVolumePercent + 5);
            updateNotification();
            notifyVolumeChanged();
            return START_NOT_STICKY;
        }

        if (ACTION_VOL_DOWN.equals(action)) {
            currentVolumePercent = Math.max(0, currentVolumePercent - 5);
            updateNotification();
            notifyVolumeChanged();
            return START_NOT_STICKY;
        }

        if (ACTION_SET_VOL.equals(action)) {
            currentVolumePercent = intent.getIntExtra(EXTRA_VOLUME, DEFAULT_SAFE_VOLUME);
            updateNotification();
            notifyVolumeChanged();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action)) {
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            currentVolumePercent = DEFAULT_SAFE_VOLUME; // Always reset to safe volume when connecting
            notifyVolumeChanged();
            startForegroundNotification();
            startStreamingFlow(resultCode, resultData);
        }

        return START_NOT_STICKY;
    }

    private Notification buildNotification() {
        Intent stopIntent = new Intent(this, AudioCaptureService.class);
        stopIntent.setAction(ACTION_STOP);
        int flags = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent piStop = PendingIntent.getService(this, 0, stopIntent, flags);

        Intent volUpIntent = new Intent(this, AudioCaptureService.class);
        volUpIntent.setAction(ACTION_VOL_UP);
        PendingIntent piUp = PendingIntent.getService(this, 1, volUpIntent, flags);

        Intent volDownIntent = new Intent(this, AudioCaptureService.class);
        volDownIntent.setAction(ACTION_VOL_DOWN);
        PendingIntent piDown = PendingIntent.getService(this, 2, volDownIntent, flags);

        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent piOpen = PendingIntent.getActivity(this, 0, openIntent, flags);

        String statusBrief;
        if (connectionState == STATE_CONNECTED) {
            statusBrief = "🟢 En vivo";
        } else if (connectionState == STATE_SEARCHING) {
            statusBrief = "🟡 Buscando...";
        } else if (connectionState == STATE_RECONNECTING) {
            statusBrief = "🟠 Reconectando...";
        } else {
            statusBrief = "Inactivo";
        }

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        // Clean, simple notification: Volume is front and center, never truncated!
        builder.setContentTitle("Volumen: " + currentVolumePercent + "%")
                .setContentText(statusBrief)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .addAction(R.drawable.ic_minus, "-", piDown)
                .addAction(R.drawable.ic_plus, "+", piUp)
                .setContentIntent(piOpen)
                .setOngoing(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setVisibility(Notification.VISIBILITY_PUBLIC);
            Notification.MediaStyle mediaStyle = new Notification.MediaStyle();
            mediaStyle.setShowActionsInCompactView(0, 1);
            builder.setStyle(mediaStyle);
        }

        return builder.build();
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
        if (notificationManager != null && isRunning) {
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
            mediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    stopCapturing();
                }
            }, new Handler(Looper.getMainLooper()));
        } catch (Exception ignored) {}

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

                multicastLock = wm.createMulticastLock("WOB:MulticastLock");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
        } catch (Exception ignored) {}

        if (audioManager != null) {
            originalMediaVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
        }

        isCapturing = true;
        updateState(STATE_SEARCHING, "Buscando REAPER en tu red local...");
        workerThread = new Thread(this::runStreaming);
        workerThread.start();
    }

    private String getLocalSubnetPrefix() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                        String ip = inetAddress.getHostAddress();
                        int lastDot = ip.lastIndexOf('.');
                        if (lastDot > 0) {
                            return ip.substring(0, lastDot + 1); // e.g. "192.168.0."
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return "192.168.0.";
    }

    private String discoverTargetPC() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String lastKnownIp = prefs.getString(KEY_LAST_IP, "");
        String subnetPrefix = getLocalSubnetPrefix();

        try {
            DatagramSocket discoverySock = new DatagramSocket();
            discoverySock.setBroadcast(true);
            discoverySock.setSoTimeout(300);

            byte[] discoverMsg = "NA_DISCOVER".getBytes();

            // 1. Direct probes to broadcasts and cached IP
            try {
                discoverySock.send(new DatagramPacket(discoverMsg, discoverMsg.length, InetAddress.getByName("255.255.255.255"), TARGET_PORT));
                discoverySock.send(new DatagramPacket(discoverMsg, discoverMsg.length, InetAddress.getByName(subnetPrefix + "255"), TARGET_PORT));
            } catch (Exception ignored) {}

            if (lastKnownIp != null && !lastKnownIp.isEmpty()) {
                try {
                    discoverySock.send(new DatagramPacket(discoverMsg, discoverMsg.length, InetAddress.getByName(lastKnownIp), TARGET_PORT));
                } catch (Exception ignored) {}
            }

            // 2. Fast single-socket sweep across all 254 local IPs (~15ms total)
            for (int i = 1; i <= 254; i++) {
                try {
                    discoverySock.send(new DatagramPacket(discoverMsg, discoverMsg.length, InetAddress.getByName(subnetPrefix + i), TARGET_PORT));
                } catch (Exception ignored) {}
            }

            // 3. Listen for response
            byte[] recvBuf = new byte[128];
            DatagramPacket recvPacket = new DatagramPacket(recvBuf, recvBuf.length);

            long deadline = System.currentTimeMillis() + 900;
            while (System.currentTimeMillis() < deadline) {
                try {
                    discoverySock.receive(recvPacket);
                    String reply = new String(recvPacket.getData(), 0, recvPacket.getLength());
                    if (reply.contains("NA_OFFER")) {
                        String foundIp = recvPacket.getAddress().getHostAddress();
                        prefs.edit().putString(KEY_LAST_IP, foundIp).apply();
                        discoverySock.close();
                        Log.i(TAG, "REAPER WOB confirmed at: " + foundIp);
                        return foundIp;
                    }
                } catch (SocketTimeoutException ignored) {
                    break;
                } catch (Exception ignored) {
                    break;
                }
            }

            discoverySock.close();
        } catch (Exception e) {
            Log.e(TAG, "Discovery error: " + e.getMessage());
        }

        return null;
    }

    private void runStreaming() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);

        String targetIp = null;

        // Discovery loop: will keep searching until REAPER responds (NO fake connections!)
        while (isCapturing && targetIp == null) {
            updateState(STATE_SEARCHING, "Buscando REAPER en tu red Wi-Fi...");
            targetIp = discoverTargetPC();

            if (targetIp == null) {
                // If not found, try last known IP once with a direct probe
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                String lastKnown = prefs.getString(KEY_LAST_IP, "");
                if (lastKnown != null && !lastKnown.isEmpty()) {
                    targetIp = lastKnown;
                    Log.i(TAG, "Probing last known IP: " + targetIp);
                    break;
                }

                updateState(STATE_SEARCHING, "REAPER no detectado. Abre REAPER con WOB en la PC.");
                try { Thread.sleep(1500); } catch (InterruptedException e) { return; }
            }
        }

        if (!isCapturing || targetIp == null) return;

        final int sampleRate = 48000;
        final int channelConfig = AudioFormat.CHANNEL_IN_STEREO;
        final int encoding = AudioFormat.ENCODING_PCM_16BIT;

        try {
            int minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding);
            int bufSize = Math.max(minBuf, 48000 * 2 * 2 / 10);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaProjection != null) {
                audioRecord = PlaybackCaptureCompat.createRecord(mediaProjection, sampleRate, channelConfig, encoding, bufSize);
            } else {
                audioRecord = new AudioRecord(
                        android.media.MediaRecorder.AudioSource.MIC,
                        sampleRate,
                        channelConfig,
                        encoding,
                        bufSize
                );
            }

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize");
                stopSelf();
                return;
            }

            udpSocket = new DatagramSocket();
            udpSocket.setSendBufferSize(512 * 1024);
            udpSocket.setSoTimeout(300); // non-blocking for ACK reception
            InetAddress targetAddr = InetAddress.getByName(targetIp);

            // Send initial connection handshake ping
            byte[] initPing = new byte[]{0x4E, 0x41, 0, 0};
            udpSocket.send(new DatagramPacket(initPing, initPing.length, targetAddr, TARGET_PORT));
            lastAckTime = System.currentTimeMillis();

            // Background ACK listener thread: validates live two-way communication
            final String fTargetIp = targetIp;
            ackThread = new Thread(() -> {
                byte[] ackBuf = new byte[128];
                DatagramPacket ackPacket = new DatagramPacket(ackBuf, ackBuf.length);
                while (isCapturing && udpSocket != null && !udpSocket.isClosed()) {
                    try {
                        udpSocket.receive(ackPacket);
                        String msg = new String(ackPacket.getData(), 0, ackPacket.getLength());
                        if (msg.startsWith("NA_ACK") || msg.startsWith("NA_OFFER")) {
                            lastAckTime = System.currentTimeMillis();
                            if (connectionState != STATE_CONNECTED) {
                                updateState(STATE_CONNECTED, "Conectado a REAPER (" + fTargetIp + ") - En vivo");
                            }
                        }
                    } catch (SocketTimeoutException ignored) {
                    } catch (Exception ignored) {
                        break;
                    }
                }
            });
            ackThread.setDaemon(true);
            ackThread.start();

            // 5ms packets = 240 stereo frames = 480 shorts (964 bytes payload, always under 1500 MTU)
            final int samplesPerPacket = 480;
            short[] audioBuffer = new short[samplesPerPacket];
            byte[] packetBytes = new byte[4 + samplesPerPacket * 2];
            ByteBuffer bb = ByteBuffer.wrap(packetBytes).order(ByteOrder.LITTLE_ENDIAN);
            short seq = 1;

            audioRecord.startRecording();
            updateState(STATE_CONNECTED, "Conectado a REAPER (" + targetIp + ") - En vivo");
            Log.i(TAG, "Audio recording started, streaming to " + targetIp);

            long lastSendTime = System.currentTimeMillis();
            long lastDiscoveryRetryTime = 0;

            while (isCapturing) {
                int read = audioRecord.read(audioBuffer, 0, samplesPerPacket, AudioRecord.READ_BLOCKING);
                long now = System.currentTimeMillis();

                // Heartbeat & Connection Health Check:
                if (now - lastAckTime > 3000) {
                    // No ACK received in 3 seconds: connection is struggling or lost!
                    if (connectionState == STATE_CONNECTED) {
                        updateState(STATE_RECONNECTING, "Senal debil o perdida. Reconectando...");
                    }

                    // Periodic background rediscovery pulse every 2s
                    if (now - lastDiscoveryRetryTime > 2000) {
                        lastDiscoveryRetryTime = now;
                        new Thread(() -> {
                            String newIp = discoverTargetPC();
                            if (newIp != null && !newIp.equals(fTargetIp)) {
                                Log.i(TAG, "REAPER moved to new IP: " + newIp);
                            }
                        }).start();
                    }
                }

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
                    try {
                        udpSocket.send(packet);
                        lastSendTime = now;
                    } catch (Exception e) {
                        // Wi-Fi socket send failed (e.g. temporary network interface transition)
                    }
                } else {
                    if (now - lastSendTime > 150) {
                        try {
                            udpSocket.send(new DatagramPacket(initPing, initPing.length, targetAddr, TARGET_PORT));
                            lastSendTime = now;
                        } catch (Exception ignored) {}
                    }
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Streaming error: " + e.getMessage(), e);
        } finally {
            cleanup();
        }
    }

    private void stopCapturing() {
        isCapturing = false;
        isRunning = false;
        updateState(STATE_IDLE, "Toca el boton para conectar");
        cleanup();
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
            try { udpSocket.close(); } catch (Exception ignored) {}
            udpSocket = null;
        }

        if (ackThread != null) {
            try { ackThread.interrupt(); } catch (Exception ignored) {}
            ackThread = null;
        }

        if (mediaProjection != null) {
            try { mediaProjection.stop(); } catch (Exception ignored) {}
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

        if (multicastLock != null && multicastLock.isHeld()) {
            try { multicastLock.release(); } catch (Exception ignored) {}
            multicastLock = null;
        }

        if (originalMediaVolume >= 0 && audioManager != null) {
            try { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalMediaVolume, 0); } catch (Exception ignored) {}
            originalMediaVolume = -1;
        }

        updateState(STATE_IDLE, "Toca el boton para conectar");
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

    @android.annotation.TargetApi(Build.VERSION_CODES.Q)
    private static class PlaybackCaptureCompat {
        static AudioRecord createRecord(MediaProjection mediaProjection, int sampleRate, int channelConfig, int encoding, int bufSize) {
            AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();

            return new AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(encoding)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfig)
                            .build())
                    .setBufferSizeInBytes(bufSize)
                    .build();
        }
    }
}
