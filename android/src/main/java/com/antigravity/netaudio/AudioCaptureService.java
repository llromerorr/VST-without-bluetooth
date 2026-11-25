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
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
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
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Random;

public class AudioCaptureService extends Service {
    private static final String TAG = "WOB";
    private static final String CHANNEL_ID = "WOB_Channel";
    private static final int NOTIF_ID = 101;
    private static final int TARGET_PORT = 58200;
    private static final String PREFS_NAME = "WOBPrefs";
    private static final String KEY_LAST_IP = "last_known_ip";

    // WOB 5.0 Protocol & Frame Constants (exact match with WOB.c)
    private static final short WOB5_MAGIC = 0x3557;
    private static final int WOB5_HDR_LEN = 20;
    private static final int TYPE_DATA = 1;
    private static final int TYPE_FEC_XOR = 2;
    private static final int TYPE_PING = 3;

    private static final int CODEC_SILENCE = 0;
    private static final int CODEC_BFP8 = 1;
    private static final int CODEC_BFP6 = 2;

    private static final int SAMPLE_RATE = 48000;
    private static final int FRAME_SAMPLES = 384; // 8.0 ms stereo frame
    private static final int NUM_BLOCKS = 24;     // 24 * 16 = 384
    private static final int FRAME_SIZE_BFP8 = 796;
    private static final int FRAME_SIZE_BFP6 = 604;

    private static final int CAPTURE_RING_SLOTS = 64; // 512 ms decoupling queue
    private static final int RETX_CACHE_SLOTS = 256;  // 2.048 s retransmit history

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

    public static final int DEFAULT_SAFE_VOLUME = 65;

    public interface ConnectionListener {
        void onConnectionStateChanged(int state, String message);
        void onVolumeChanged(int volumePercent);
    }

    public static volatile boolean isRunning = false;
    public static volatile int connectionState = STATE_IDLE;
    public static volatile String connectionInfo = "Toca el boton para conectar";
    public static volatile int currentVolumePercent = DEFAULT_SAFE_VOLUME;

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
    private Thread captureThread;
    private Thread senderThread;
    private Thread ackThread;
    private volatile boolean isCapturing = false;
    private volatile boolean isStreamConfirmed = false;
    private volatile long lastAckTime = 0;

    // Adaptive Protection Tier (0=HD BFP8+K4, 1=Church Armored BFP6x2+K3, 2=Survival BFP6x2+K2)
    private volatile int desiredTier = 0;
    private volatile long lastStressEventMs = 0;
    private volatile long lastTierPromoteMs = 0;

    // Pending selective NACK retransmit requests (from ACK thread to Sender thread)
    private static final int MAX_PENDING_NACKS = 32;
    private final Object nackLock = new Object();
    private final int[] pendingNackBase = new int[MAX_PENDING_NACKS];
    private final int[] pendingNackMask = new int[MAX_PENDING_NACKS];
    private int pendingNackHead = 0;
    private int pendingNackTail = 0;

    // Decoupled Lock-Free-ish Capture Ring between CaptureThread and NetworkSenderThread
    private final Object captureLock = new Object();
    private final short[][] capRingL = new short[CAPTURE_RING_SLOTS][FRAME_SAMPLES];
    private final short[][] capRingR = new short[CAPTURE_RING_SLOTS][FRAME_SAMPLES];
    private volatile int capWriteIdx = 0;
    private volatile int capReadIdx = 0;

    // Current confirmed destination (updated safely if PC moves or reconnects)
    private volatile InetAddress targetAddr = null;
    private volatile String targetIpStr = null;
    private volatile boolean targetIsWob5 = true;
    private volatile Network boundLocalNetwork = null;

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
            currentVolumePercent = DEFAULT_SAFE_VOLUME;
            notifyVolumeChanged();
            startForegroundNotification();
            startStreamingFlow(resultCode, resultData);
        }

        return START_NOT_STICKY;
    }

    private Notification buildNotification() {
        Intent volUpIntent = new Intent(this, AudioCaptureService.class);
        volUpIntent.setAction(ACTION_VOL_UP);
        int flags = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) ? PendingIntent.FLAG_IMMUTABLE : 0;
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
                    wifiMode = 4; // WIFI_MODE_FULL_LOW_LATENCY
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
        isStreamConfirmed = false;
        desiredTier = 0;
        lastStressEventMs = System.currentTimeMillis();
        capWriteIdx = 0;
        capReadIdx = 0;
        synchronized (nackLock) {
            pendingNackHead = 0;
            pendingNackTail = 0;
        }

        updateState(STATE_SEARCHING, "Buscando REAPER en tu red Wi-Fi...");
        senderThread = new Thread(this::runNetworkingAndSender, "WOB-Net");
        senderThread.start();
    }

    // =========================================================================
    // Wi-Fi / LAN Interface Detection & Socket QoS Helpers
    // =========================================================================
    private Network findBestLocalNetwork() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;
            Network[] nets = cm.getAllNetworks();
            Network wifiNet = null;
            Network ethNet = null;
            for (Network n : nets) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc == null) continue;
                if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    ethNet = n;
                } else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    wifiNet = n;
                }
            }
            return (ethNet != null) ? ethNet : wifiNet;
        } catch (Exception ignored) {}
        return null;
    }

    private void configureUdpSocketQoS(DatagramSocket sock, Network net) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && net != null) {
            try { net.bindSocket(sock); } catch (Exception ignored) {}
        }
        try { sock.setSendBufferSize(512 * 1024); } catch (Exception ignored) {}
        try { sock.setReceiveBufferSize(128 * 1024); } catch (Exception ignored) {}
        try {
            sock.setTrafficClass(0xB8); // DSCP EF (Voice / WMM AC_VO priority)
        } catch (Exception e) {
            try { sock.setTrafficClass(0x88); } catch (Exception e2) { // DSCP AF41 (Video / AC_VI)
                try { sock.setTrafficClass(0x10); } catch (Exception ignored) {}
            }
        }
    }

    private static class SubnetInfo {
        String ip;
        String broadcast;
        int prefixLen;
        SubnetInfo(String ip, String bcast, int prefixLen) {
            this.ip = ip;
            this.broadcast = bcast;
            this.prefixLen = prefixLen;
        }
    }

    private List<SubnetInfo> collectLocalLanSubnets(Network localNet) {
        List<SubnetInfo> result = new ArrayList<>();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && localNet != null) {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    LinkProperties lp = cm.getLinkProperties(localNet);
                    if (lp != null) {
                        for (LinkAddress la : lp.getLinkAddresses()) {
                            InetAddress addr = la.getAddress();
                            if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                                int plen = la.getPrefixLength();
                                byte[] raw = addr.getAddress();
                                int ipInt = ((raw[0] & 0xFF) << 24) | ((raw[1] & 0xFF) << 16)
                                          | ((raw[2] & 0xFF) << 8) | (raw[3] & 0xFF);
                                int mask = (plen == 0) ? 0 : (0xFFFFFFFF << (32 - plen));
                                int bcastInt = ipInt | (~mask);
                                String bcast = ((bcastInt >> 24) & 0xFF) + "." + ((bcastInt >> 16) & 0xFF) + "."
                                             + ((bcastInt >> 8) & 0xFF) + "." + (bcastInt & 0xFF);
                                result.add(new SubnetInfo(addr.getHostAddress(), bcast, plen));
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        // Also include non-cellular NetworkInterfaces (supports USB tethering rndis0 / Wi-Fi Hotspot ap0)
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                NetworkInterface intf = en.nextElement();
                String name = intf.getName().toLowerCase();
                if (name.contains("rmnet") || name.contains("ccmni") || name.contains("pdp")
                        || name.contains("tun") || name.contains("dummy") || !intf.isUp()) {
                    continue;
                }
                for (InterfaceAddress ia : intf.getInterfaceAddresses()) {
                    InetAddress addr = ia.getAddress();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        String bcast = (ia.getBroadcast() != null) ? ia.getBroadcast().getHostAddress() : null;
                        result.add(new SubnetInfo(addr.getHostAddress(), bcast, ia.getNetworkPrefixLength()));
                    }
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    private static class DiscoveryResult {
        String ip;
        Network net;
        boolean isWob5;
        DiscoveryResult(String ip, Network net, boolean isWob5) {
            this.ip = ip;
            this.net = net;
            this.isWob5 = isWob5;
        }
    }

    private DiscoveryResult discoverTargetPC() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String lastKnownIp = prefs.getString(KEY_LAST_IP, "");
        Network bestNet = findBestLocalNetwork();
        List<SubnetInfo> subnets = collectLocalLanSubnets(bestNet);

        byte[] discoverMsg = "NA_DISCOVER".getBytes();
        byte[] recvBuf = new byte[128];

        // Try on Wi-Fi/Eth bound socket first, and unbound if Hotspot/USB might differ
        Network[] trialNets = (bestNet != null) ? new Network[]{bestNet, null} : new Network[]{null};

        for (Network net : trialNets) {
            if (!isCapturing) return null;
            DatagramSocket sock = null;
            try {
                sock = new DatagramSocket();
                sock.setBroadcast(true);
                sock.setSoTimeout(280);
                configureUdpSocketQoS(sock, net);

                // Phase 1: Zero-flood probe (lastKnownIp + exact subnet broadcasts + global broadcast)
                if (lastKnownIp != null && !lastKnownIp.isEmpty()) {
                    try {
                        InetAddress cached = InetAddress.getByName(lastKnownIp);
                        sock.send(new DatagramPacket(discoverMsg, discoverMsg.length, cached, TARGET_PORT));
                        sock.send(new DatagramPacket(discoverMsg, discoverMsg.length, cached, TARGET_PORT));
                    } catch (Exception ignored) {}
                }

                try {
                    sock.send(new DatagramPacket(discoverMsg, discoverMsg.length, InetAddress.getByName("255.255.255.255"), TARGET_PORT));
                } catch (Exception ignored) {}

                for (SubnetInfo s : subnets) {
                    if (s.broadcast != null) {
                        try {
                            sock.send(new DatagramPacket(discoverMsg, discoverMsg.length, InetAddress.getByName(s.broadcast), TARGET_PORT));
                        } catch (Exception ignored) {}
                    }
                }

                DatagramPacket recvPkt = new DatagramPacket(recvBuf, recvBuf.length);
                long deadline = System.currentTimeMillis() + 300;
                while (System.currentTimeMillis() < deadline && isCapturing) {
                    try {
                        sock.receive(recvPkt);
                        String reply = new String(recvPkt.getData(), 0, recvPkt.getLength());
                        if (reply.contains("NA_OFFER")) {
                            String foundIp = recvPkt.getAddress().getHostAddress();
                            prefs.edit().putString(KEY_LAST_IP, foundIp).apply();
                            boolean wob5 = reply.contains("WOB5");
                            sock.close();
                            Log.i(TAG, "Confirmed REAPER (" + reply + ") at " + foundIp);
                            return new DiscoveryResult(foundIp, net, wob5);
                        }
                    } catch (SocketTimeoutException ignored) {
                        break;
                    }
                }

                // Phase 2: Gentle paced sweep of /24 (and nearby /24 blocks if /22 or /23 subnet)
                if (!subnets.isEmpty() && isCapturing) {
                    SubnetInfo primary = subnets.get(0);
                    String[] parts = primary.ip.split("\\.");
                    if (parts.length == 4) {
                        int b0 = Integer.parseInt(parts[0]);
                        int b1 = Integer.parseInt(parts[1]);
                        int b2 = Integer.parseInt(parts[2]);

                        int span = (primary.prefixLen < 24 && primary.prefixLen >= 21) ? (1 << (24 - primary.prefixLen)) : 1;
                        int baseThird = b2 & ~(span - 1);

                        sock.setSoTimeout(250);
                        for (int thirdOff = 0; thirdOff < span && isCapturing; thirdOff++) {
                            int curThird = baseThird + thirdOff;
                            String prefix = b0 + "." + b1 + "." + curThird + ".";
                            for (int i = 1; i <= 254 && isCapturing; i++) {
                                try {
                                    sock.send(new DatagramPacket(discoverMsg, discoverMsg.length, InetAddress.getByName(prefix + i), TARGET_PORT));
                                } catch (Exception ignored) {}
                                if ((i & 15) == 0) {
                                    // Yield 2ms every 16 probes so AP ARP table stays completely healthy
                                    try { Thread.sleep(2); } catch (InterruptedException ignored) {}
                                }
                            }
                        }

                        long scanDeadline = System.currentTimeMillis() + 450;
                        while (System.currentTimeMillis() < scanDeadline && isCapturing) {
                            try {
                                sock.receive(recvPkt);
                                String reply = new String(recvPkt.getData(), 0, recvPkt.getLength());
                                if (reply.contains("NA_OFFER")) {
                                    String foundIp = recvPkt.getAddress().getHostAddress();
                                    prefs.edit().putString(KEY_LAST_IP, foundIp).apply();
                                    boolean wob5 = reply.contains("WOB5");
                                    sock.close();
                                    return new DiscoveryResult(foundIp, net, wob5);
                                }
                            } catch (SocketTimeoutException ignored) {
                                break;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Discovery pass error: " + e.getMessage());
            } finally {
                if (sock != null && !sock.isClosed()) {
                    try { sock.close(); } catch (Exception ignored) {}
                }
            }
        }

        return null;
    }

    // =========================================================================
    // Closed-Loop Noise-Shaped BFP Encoder (Bit-Exact with WOB.c)
    // =========================================================================
    private static int s31Div32(int x) {
        return (x >= 0) ? ((x * 31) >> 5) : -(((-x) * 31) >> 5);
    }

    private static boolean isDigitalZero(short[] L, short[] R) {
        for (int i = 0; i < FRAME_SAMPLES; i++) {
            if (L[i] != 0 || R[i] != 0) return false;
        }
        return true;
    }

    private static void encodeBfpChannel(short[] ch, int bits, byte[] out, int shiftOffset, int dataOffset, int[] scratchQ) {
        final int qmax = (1 << (bits - 1)) - 1;
        int prev = ch[0];
        int errFb = 0;

        for (int b = 0; b < NUM_BLOCKS; b++) {
            int start = b * 16;
            int end = start + 16;
            int first = (b == 0) ? 1 : start;

            int errHeadroom = Math.abs(errFb >> 1) + 4;
            int pTmp = prev;
            int maxD = 0;
            for (int i = first; i < end; i++) {
                int d = Math.abs(ch[i] - s31Div32(pTmp));
                if (d > maxD) maxD = d;
                pTmp = ch[i];
            }
            maxD += (maxD >> 4) + errHeadroom;

            int shift = 0;
            while ((maxD >> shift) > qmax && shift < 12) {
                shift++;
            }

            if ((b & 1) == 0) {
                out[shiftOffset + (b >> 1)] = (byte) (shift & 0x0F);
            } else {
                out[shiftOffset + (b >> 1)] |= (byte) ((shift & 0x0F) << 4);
            }

            int step = 1 << shift;
            int half = step >> 1;

            for (int i = first; i < end; i++) {
                int pred = s31Div32(prev);
                int target = ch[i] - pred + (errFb >> 1);
                int q;
                if (shift == 0) {
                    q = target;
                } else {
                    q = (target >= 0) ? ((target + half) >> shift) : -((-target + half) >> shift);
                }
                if (q > qmax) q = qmax;
                if (q < -qmax) q = -qmax;

                int rec = pred + (q << shift);
                if (rec > 32767) rec = 32767;
                if (rec < -32768) rec = -32768;

                errFb = ch[i] - rec;
                prev = rec;
                scratchQ[i] = q;
            }
        }
        scratchQ[0] = 0;

        if (bits == 8) {
            for (int i = 0; i < FRAME_SAMPLES; i++) {
                out[dataOffset + i] = (byte) scratchQ[i];
            }
        } else {
            int j = dataOffset;
            for (int i = 0; i < FRAME_SAMPLES; i += 4) {
                int u0 = (scratchQ[i] + 32) & 0x3F;
                int u1 = (scratchQ[i + 1] + 32) & 0x3F;
                int u2 = (scratchQ[i + 2] + 32) & 0x3F;
                int u3 = (scratchQ[i + 3] + 32) & 0x3F;
                out[j]     = (byte) (u0 | ((u1 & 0x03) << 6));
                out[j + 1] = (byte) (((u1 >> 2) & 0x0F) | ((u2 & 0x0F) << 4));
                out[j + 2] = (byte) (((u2 >> 4) & 0x03) | (u3 << 2));
                j += 3;
            }
        }
    }

    private static int encodeFrame(short[] L, short[] R, int codec, byte[] outBuf, int outOff, int[] scratchQ) {
        if (codec == CODEC_SILENCE) return 0;

        short sL0 = L[0];
        short sR0 = R[0];
        outBuf[outOff]     = (byte) (sL0 & 0xFF);
        outBuf[outOff + 1] = (byte) ((sL0 >> 8) & 0xFF);
        outBuf[outOff + 2] = (byte) (sR0 & 0xFF);
        outBuf[outOff + 3] = (byte) ((sR0 >> 8) & 0xFF);

        if (codec == CODEC_BFP8) {
            encodeBfpChannel(L, 8, outBuf, outOff + 4, outOff + 28, scratchQ);
            encodeBfpChannel(R, 8, outBuf, outOff + 16, outOff + 412, scratchQ);
            return FRAME_SIZE_BFP8;
        } else {
            encodeBfpChannel(L, 6, outBuf, outOff + 4, outOff + 28, scratchQ);
            encodeBfpChannel(R, 6, outBuf, outOff + 16, outOff + 316, scratchQ);
            return FRAME_SIZE_BFP6;
        }
    }

    // =========================================================================
    // Decoupled Audio Capture Thread (NEVER blocks on network)
    // =========================================================================
    private void runCaptureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        final int interleavedLen = FRAME_SAMPLES * 2;
        short[] pcmTmp = new short[interleavedLen];

        try {
            audioRecord.startRecording();
            while (isCapturing && audioRecord != null) {
                int read = audioRecord.read(pcmTmp, 0, interleavedLen, AudioRecord.READ_BLOCKING);
                if (read == interleavedLen) {
                    int w = capWriteIdx;
                    int r = capReadIdx;
                    // If network thread stalls far behind > 500ms, drop oldest queued frame cleanly
                    if (w - r >= CAPTURE_RING_SLOTS - 1) {
                        capReadIdx = w - (CAPTURE_RING_SLOTS / 2);
                    }

                    int slot = w & (CAPTURE_RING_SLOTS - 1);
                    short[] dstL = capRingL[slot];
                    short[] dstR = capRingR[slot];

                    int volPct = currentVolumePercent;
                    if (volPct <= 0) {
                        for (int i = 0; i < FRAME_SAMPLES; i++) {
                            dstL[i] = 0;
                            dstR[i] = 0;
                        }
                    } else if (volPct >= 100) {
                        for (int i = 0; i < FRAME_SAMPLES; i++) {
                            dstL[i] = pcmTmp[i * 2];
                            dstR[i] = pcmTmp[i * 2 + 1];
                        }
                    } else {
                        float gain = (float) Math.pow(volPct / 100.0, 1.5);
                        for (int i = 0; i < FRAME_SAMPLES; i++) {
                            dstL[i] = (short) Math.max(-32767, Math.min(32767, (int) (pcmTmp[i * 2] * gain)));
                            dstR[i] = (short) Math.max(-32767, Math.min(32767, (int) (pcmTmp[i * 2 + 1] * gain)));
                        }
                    }

                    capWriteIdx = w + 1;
                    synchronized (captureLock) {
                        captureLock.notify();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Capture thread error: " + e.getMessage());
        }
    }

    // =========================================================================
    // Main Networking, Self-Healing ACK/NACK & FEC Sender Thread
    // =========================================================================
    private void runNetworkingAndSender() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);

        DiscoveryResult disc = null;
        while (isCapturing && disc == null) {
            updateState(STATE_SEARCHING, "Buscando REAPER en tu red Wi-Fi...");
            disc = discoverTargetPC();
            if (disc == null) {
                updateState(STATE_SEARCHING, "REAPER no detectado. Abre REAPER con WOB en la PC.");
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            }
        }

        if (!isCapturing || disc == null) return;

        try {
            targetIpStr = disc.ip;
            targetAddr = InetAddress.getByName(disc.ip);
            targetIsWob5 = disc.isWob5;
            boundLocalNetwork = disc.net;

            final int channelConfig = AudioFormat.CHANNEL_IN_STEREO;
            final int encoding = AudioFormat.ENCODING_PCM_16BIT;
            int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, encoding);
            int osBufSize = Math.max(minBuf, SAMPLE_RATE * 4 / 2); // Huge 500ms OS capture buffer

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaProjection != null) {
                audioRecord = PlaybackCaptureCompat.createRecord(mediaProjection, SAMPLE_RATE, channelConfig, encoding, osBufSize);
            } else {
                audioRecord = new AudioRecord(android.media.MediaRecorder.AudioSource.MIC, SAMPLE_RATE, channelConfig, encoding, osBufSize);
            }

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize");
                stopSelf();
                return;
            }

            udpSocket = new DatagramSocket();
            udpSocket.setSoTimeout(300);
            configureUdpSocketQoS(udpSocket, boundLocalNetwork);

            final int sessionTag = new Random().nextInt() | 1; // guaranteed non-zero

            // Start bidirectional ACK + NACK receiver thread
            ackThread = new Thread(() -> runAckAndNackListener(), "WOB-Ack");
            ackThread.setDaemon(true);
            ackThread.start();

            // Send immediate handshake ping and wait briefly for genuine NA_ACK before marking UI Green
            byte[] pingBuf = new byte[WOB5_HDR_LEN];
            ByteBuffer pingBB = ByteBuffer.wrap(pingBuf).order(ByteOrder.LITTLE_ENDIAN);
            sendWob5Ping(pingBuf, pingBB, sessionTag);
            lastAckTime = System.currentTimeMillis();

            // Start dedicated capture thread
            captureThread = new Thread(this::runCaptureLoop, "WOB-Capture");
            captureThread.start();

            // Preallocate all reusable buffers (ZERO GC pauses during live church audio!)
            final byte[][] cacheBytes = new byte[RETX_CACHE_SLOTS][FRAME_SIZE_BFP8];
            final int[] cacheSeq = new int[RETX_CACHE_SLOTS];
            final int[] cacheCodec = new int[RETX_CACHE_SLOTS];
            final int[] cacheLen = new int[RETX_CACHE_SLOTS];
            final long[] cacheLastResent = new long[RETX_CACHE_SLOTS];
            for (int i = 0; i < RETX_CACHE_SLOTS; i++) cacheSeq[i] = -1;

            final byte[] pktBuf = new byte[1400];
            final ByteBuffer pktBB = ByteBuffer.wrap(pktBuf).order(ByteOrder.LITTLE_ENDIAN);
            final DatagramPacket outPkt = new DatagramPacket(pktBuf, pktBuf.length);

            final byte[] xorBuf = new byte[1400];
            final byte[] fecPktBuf = new byte[1400];
            final ByteBuffer fecBB = ByteBuffer.wrap(fecPktBuf).order(ByteOrder.LITTLE_ENDIAN);
            final DatagramPacket fecOutPkt = new DatagramPacket(fecPktBuf, fecPktBuf.length);

            final int[] scratchQ = new int[FRAME_SAMPLES];

            int frameSeq = 0;
            int pktSeq = 0;
            int activeTier = 0; // Starts HD; auto-promotes cleanly on group boundaries
            int fecK = 4;
            int fecIdx = 0;
            int fecBasePkt = 0;
            int xorMaxLen = 0;

            long lastKeepAliveMs = System.currentTimeMillis();
            long lastPingReprobeMs = 0;

            while (isCapturing) {
                long now = System.currentTimeMillis();

                // 1. Process any immediate NACK retransmission requests!
                processPendingNacks(cacheSeq, cacheCodec, cacheBytes, cacheLen, cacheLastResent, pktBuf, pktBB, outPkt, sessionTag, now);

                // 2. Check genuine bidirectional heartbeat health
                if (now - lastAckTime > 3800) {
                    if (connectionState == STATE_CONNECTED) {
                        isStreamConfirmed = false;
                        updateState(STATE_RECONNECTING, "Senal debil en Wi-Fi. Auto-reparando...");
                    }
                    // Lightweight single-packet probe every 2.5s (NO 254-IP scan storm!)
                    if (now - lastPingReprobeMs > 2500) {
                        lastPingReprobeMs = now;
                        sendWob5Ping(pingBuf, pingBB, sessionTag);
                        try {
                            byte[] discMsg = "NA_DISCOVER".getBytes();
                            udpSocket.send(new DatagramPacket(discMsg, discMsg.length, targetAddr, TARGET_PORT));
                            udpSocket.send(new DatagramPacket(discMsg, discMsg.length, InetAddress.getByName("255.255.255.255"), TARGET_PORT));
                        } catch (Exception ignored) {}
                    }
                } else {
                    // Check cautious tier downgrade only after 45s clean operation
                    if (desiredTier > 0 && (now - lastStressEventMs) > 45000L && (now - lastTierPromoteMs) > 45000L) {
                        desiredTier = desiredTier - 1;
                        lastStressEventMs = now;
                    }
                }

                // Update Tier cleanly only at FEC group boundaries (fecIdx == 0)
                if (fecIdx == 0 && activeTier != desiredTier) {
                    activeTier = desiredTier;
                    fecK = (activeTier == 0) ? 4 : ((activeTier == 1) ? 3 : 2);
                    Log.i(TAG, "Auto-adapted protection Tier=" + activeTier + " fecK=" + fecK);
                }

                // 3. Determine how many frames needed per packet (1 in Tier 0; 2 in Tier 1/2 = 62.5 pps!)
                int framesNeeded = (activeTier == 0 || !targetIsWob5) ? 1 : 2;
                int available = capWriteIdx - capReadIdx;

                if (available < framesNeeded) {
                    if (now - lastKeepAliveMs > 180) {
                        sendWob5Ping(pingBuf, pingBB, sessionTag);
                        lastKeepAliveMs = now;
                    }
                    synchronized (captureLock) {
                        try { captureLock.wait(12); } catch (InterruptedException ignored) {}
                    }
                    continue;
                }

                // 4. Read frames from capture ring & encode
                int r0Idx = capReadIdx;
                int slot0 = r0Idx & (CAPTURE_RING_SLOTS - 1);
                short[] L0 = capRingL[slot0];
                short[] R0 = capRingR[slot0];

                if (!targetIsWob5) {
                    // Legacy WOB 4.0 fallback packet (split 384 into two 192-sample UDP packets < 1500 MTU)
                    capReadIdx = r0Idx + 1;
                    for (int half = 0; half < 2; half++) {
                        int off = half * 192;
                        pktBB.clear();
                        pktBB.putShort((short) 0x4E41);
                        pktBB.putShort((short) (frameSeq++ & 0xFFFF));
                        for (int i = off; i < off + 192; i++) {
                            pktBB.putShort(L0[i]);
                            pktBB.putShort(R0[i]);
                        }
                        outPkt.setData(pktBuf, 0, 4 + 192 * 4);
                        outPkt.setAddress(targetAddr);
                        outPkt.setPort(TARGET_PORT);
                        try { udpSocket.send(outPkt); lastKeepAliveMs = now; } catch (Exception ignored) {}
                    }
                    continue;
                }

                if (fecIdx == 0) {
                    fecBasePkt = pktSeq;
                    xorMaxLen = 0;
                    java.util.Arrays.fill(xorBuf, (byte) 0);
                }

                int pktCodec;
                int payloadLen;
                int pktFrameSeq = frameSeq;

                if (framesNeeded == 1) {
                    capReadIdx = r0Idx + 1;
                    boolean sil = isDigitalZero(L0, R0);
                    pktCodec = sil ? CODEC_SILENCE : CODEC_BFP8;
                    payloadLen = encodeFrame(L0, R0, pktCodec, pktBuf, WOB5_HDR_LEN, scratchQ);

                    int cs = pktFrameSeq & (RETX_CACHE_SLOTS - 1);
                    cacheSeq[cs] = pktFrameSeq;
                    cacheCodec[cs] = pktCodec;
                    cacheLen[cs] = payloadLen;
                    if (payloadLen > 0) System.arraycopy(pktBuf, WOB5_HDR_LEN, cacheBytes[cs], 0, payloadLen);
                    frameSeq++;
                } else {
                    int r1Idx = r0Idx + 1;
                    int slot1 = r1Idx & (CAPTURE_RING_SLOTS - 1);
                    short[] L1 = capRingL[slot1];
                    short[] R1 = capRingR[slot1];
                    capReadIdx = r0Idx + 2;

                    boolean sil0 = isDigitalZero(L0, R0);
                    boolean sil1 = isDigitalZero(L1, R1);

                    if (sil0 && sil1) {
                        pktCodec = CODEC_SILENCE;
                        payloadLen = 0;
                        int cs0 = pktFrameSeq & (RETX_CACHE_SLOTS - 1);
                        cacheSeq[cs0] = pktFrameSeq; cacheCodec[cs0] = CODEC_SILENCE; cacheLen[cs0] = 0;
                        int cs1 = (pktFrameSeq + 1) & (RETX_CACHE_SLOTS - 1);
                        cacheSeq[cs1] = pktFrameSeq + 1; cacheCodec[cs1] = CODEC_SILENCE; cacheLen[cs1] = 0;
                    } else {
                        pktCodec = CODEC_BFP6;
                        int len0 = encodeFrame(L0, R0, CODEC_BFP6, pktBuf, WOB5_HDR_LEN, scratchQ);
                        int len1 = encodeFrame(L1, R1, CODEC_BFP6, pktBuf, WOB5_HDR_LEN + len0, scratchQ);
                        payloadLen = len0 + len1;

                        int cs0 = pktFrameSeq & (RETX_CACHE_SLOTS - 1);
                        cacheSeq[cs0] = pktFrameSeq; cacheCodec[cs0] = CODEC_BFP6; cacheLen[cs0] = len0;
                        System.arraycopy(pktBuf, WOB5_HDR_LEN, cacheBytes[cs0], 0, len0);

                        int cs1 = (pktFrameSeq + 1) & (RETX_CACHE_SLOTS - 1);
                        cacheSeq[cs1] = pktFrameSeq + 1; cacheCodec[cs1] = CODEC_BFP6; cacheLen[cs1] = len1;
                        System.arraycopy(pktBuf, WOB5_HDR_LEN + len0, cacheBytes[cs1], 0, len1);
                    }
                    frameSeq += 2;
                }

                int flagsByte = (pktCodec & 0x07) | ((framesNeeded & 0x03) << 3) | ((activeTier & 0x03) << 6);

                pktBB.clear();
                pktBB.putShort(WOB5_MAGIC);
                pktBB.put((byte) TYPE_DATA);
                pktBB.put((byte) flagsByte);
                pktBB.putInt(pktFrameSeq);
                pktBB.putInt(fecBasePkt);
                pktBB.put((byte) fecIdx);
                pktBB.put((byte) fecK);
                pktBB.putShort((short) payloadLen);
                pktBB.putInt(sessionTag);

                outPkt.setData(pktBuf, 0, WOB5_HDR_LEN + payloadLen);
                outPkt.setAddress(targetAddr);
                outPkt.setPort(TARGET_PORT);
                try {
                    udpSocket.send(outPkt);
                    lastKeepAliveMs = now;
                } catch (Exception ignored) {}

                // Accumulate XOR Parity: [flags(1), frameSeq(4), payloadLen(2), payload(...)]
                if (fecK >= 2) {
                    int totalXorSpan = 7 + payloadLen;
                    if (totalXorSpan > xorMaxLen) xorMaxLen = totalXorSpan;

                    xorBuf[0] ^= (byte) flagsByte;
                    xorBuf[1] ^= (byte) (pktFrameSeq & 0xFF);
                    xorBuf[2] ^= (byte) ((pktFrameSeq >> 8) & 0xFF);
                    xorBuf[3] ^= (byte) ((pktFrameSeq >> 16) & 0xFF);
                    xorBuf[4] ^= (byte) ((pktFrameSeq >> 24) & 0xFF);
                    xorBuf[5] ^= (byte) (payloadLen & 0xFF);
                    xorBuf[6] ^= (byte) ((payloadLen >> 8) & 0xFF);
                    for (int k = 0; k < payloadLen; k++) {
                        xorBuf[7 + k] ^= pktBuf[WOB5_HDR_LEN + k];
                    }

                    fecIdx++;
                    if (fecIdx >= fecK) {
                        // Send XOR Parity packet cleanly protecting this group!
                        fecBB.clear();
                        fecBB.putShort(WOB5_MAGIC);
                        fecBB.put((byte) TYPE_FEC_XOR);
                        fecBB.put((byte) ((activeTier & 0x03) << 6));
                        fecBB.putInt(0);
                        fecBB.putInt(fecBasePkt);
                        fecBB.put((byte) fecK);
                        fecBB.put((byte) fecK);
                        fecBB.putShort((short) xorMaxLen);
                        fecBB.putInt(sessionTag);
                        System.arraycopy(xorBuf, 0, fecPktBuf, WOB5_HDR_LEN, xorMaxLen);

                        fecOutPkt.setData(fecPktBuf, 0, WOB5_HDR_LEN + xorMaxLen);
                        fecOutPkt.setAddress(targetAddr);
                        fecOutPkt.setPort(TARGET_PORT);
                        try { udpSocket.send(fecOutPkt); } catch (Exception ignored) {}

                        fecIdx = 0;
                    }
                }
                pktSeq++;
            }
        } catch (Exception e) {
            Log.e(TAG, "Sender thread error: " + e.getMessage(), e);
        } finally {
            cleanup();
        }
    }

    private void sendWob5Ping(byte[] pingBuf, ByteBuffer pingBB, int sessionTag) {
        if (udpSocket == null || udpSocket.isClosed() || targetAddr == null) return;
        try {
            if (targetIsWob5) {
                pingBB.clear();
                pingBB.putShort(WOB5_MAGIC);
                pingBB.put((byte) TYPE_PING);
                pingBB.put((byte) 0);
                pingBB.putInt(0);
                pingBB.putInt(0);
                pingBB.put((byte) 0);
                pingBB.put((byte) 0);
                pingBB.putShort((short) 0);
                pingBB.putInt(sessionTag);
                udpSocket.send(new DatagramPacket(pingBuf, WOB5_HDR_LEN, targetAddr, TARGET_PORT));
            } else {
                byte[] p4 = new byte[]{0x4E, 0x41, 0, 0};
                udpSocket.send(new DatagramPacket(p4, p4.length, targetAddr, TARGET_PORT));
            }
        } catch (Exception ignored) {}
    }

    private void processPendingNacks(int[] cacheSeq, int[] cacheCodec, byte[][] cacheBytes,
                                     int[] cacheLen, long[] cacheLastResent,
                                     byte[] pktBuf, ByteBuffer pktBB, DatagramPacket outPkt,
                                     int sessionTag, long now) {
        while (true) {
            int base, mask;
            synchronized (nackLock) {
                if (pendingNackHead == pendingNackTail) return;
                base = pendingNackBase[pendingNackHead];
                mask = pendingNackMask[pendingNackHead];
                pendingNackHead = (pendingNackHead + 1) & (MAX_PENDING_NACKS - 1);
            }

            for (int k = 0; k < 32; k++) {
                if ((mask & (1 << k)) == 0) continue;
                int reqSeq = base + k;
                int cs = reqSeq & (RETX_CACHE_SLOTS - 1);
                if (cacheSeq[cs] != reqSeq) continue;
                if (now - cacheLastResent[cs] < 24) continue; // Cooldown guard
                cacheLastResent[cs] = now;

                int codec = cacheCodec[cs];
                int len = cacheLen[cs];
                int flagsByte = (codec & 0x07) | (1 << 3) | (1 << 5) | ((desiredTier & 0x03) << 6); // isRetransmit = 1

                pktBB.clear();
                pktBB.putShort(WOB5_MAGIC);
                pktBB.put((byte) TYPE_DATA);
                pktBB.put((byte) flagsByte);
                pktBB.putInt(reqSeq);
                pktBB.putInt(0);
                pktBB.put((byte) 0);
                pktBB.put((byte) 0);
                pktBB.putShort((short) len);
                pktBB.putInt(sessionTag);
                if (len > 0) {
                    System.arraycopy(cacheBytes[cs], 0, pktBuf, WOB5_HDR_LEN, len);
                }

                outPkt.setData(pktBuf, 0, WOB5_HDR_LEN + len);
                outPkt.setAddress(targetAddr);
                outPkt.setPort(TARGET_PORT);
                try { udpSocket.send(outPkt); } catch (Exception ignored) {}
            }
        }
    }

    private void runAckAndNackListener() {
        byte[] recvBuf = new byte[256];
        DatagramPacket recvPkt = new DatagramPacket(recvBuf, recvBuf.length);

        while (isCapturing && udpSocket != null && !udpSocket.isClosed()) {
            try {
                udpSocket.receive(recvPkt);
                String msg = new String(recvPkt.getData(), 0, recvPkt.getLength());
                long now = System.currentTimeMillis();

                if (msg.startsWith("NA_NACK:")) {
                    lastAckTime = now;
                    // Format: NA_NACK:<baseSeq>:<hexMask>
                    String[] parts = msg.split(":");
                    if (parts.length >= 3) {
                        try {
                            int baseSeq = (int) Long.parseLong(parts[1].trim());
                            int mask = (int) Long.parseLong(parts[2].trim(), 16);
                            synchronized (nackLock) {
                                int nextTail = (pendingNackTail + 1) & (MAX_PENDING_NACKS - 1);
                                if (nextTail != pendingNackHead) {
                                    pendingNackBase[pendingNackTail] = baseSeq;
                                    pendingNackMask[pendingNackTail] = mask;
                                    pendingNackTail = nextTail;
                                }
                            }
                            synchronized (captureLock) {
                                captureLock.notify();
                            }
                            lastStressEventMs = now;
                            if (desiredTier < 1) {
                                desiredTier = 1;
                                lastTierPromoteMs = now;
                            } else if (desiredTier == 1 && Integer.bitCount(mask) >= 2) {
                                desiredTier = 2;
                                lastTierPromoteMs = now;
                            }
                        } catch (Exception ignored) {}
                    }
                } else if (msg.startsWith("NA_ACK") || msg.startsWith("NA_OFFER")) {
                    lastAckTime = now;

                    // Update target address cleanly if REAPER answered from updated IP
                    String fromIp = recvPkt.getAddress().getHostAddress();
                    if (targetIpStr == null || !fromIp.equals(targetIpStr)) {
                        targetIpStr = fromIp;
                        targetAddr = recvPkt.getAddress();
                        if (msg.contains("WOB5")) targetIsWob5 = true;
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(KEY_LAST_IP, fromIp).apply();
                    }

                    // Parse telemetry: NA_ACK:<recv>:<bufMs>:<lossPermille>:<jitterMs>:<fec>:<nack>
                    if (msg.startsWith("NA_ACK:")) {
                        String[] p = msg.split(":");
                        if (p.length >= 5) {
                            try {
                                float bufMs = Float.parseFloat(p[2]);
                                float lossPerm = Float.parseFloat(p[3]);
                                float jitterMs = Float.parseFloat(p[4]);

                                if (lossPerm >= 4.0f || jitterMs >= 13.0f || bufMs >= 210.0f) {
                                    lastStressEventMs = now;
                                    if (desiredTier == 0) {
                                        desiredTier = 1;
                                        lastTierPromoteMs = now;
                                    }
                                }
                                if (lossPerm >= 18.0f || jitterMs >= 28.0f || bufMs >= 360.0f) {
                                    lastStressEventMs = now;
                                    if (desiredTier < 2) {
                                        desiredTier = 2;
                                        lastTierPromoteMs = now;
                                    }
                                }
                            } catch (Exception ignored) {}
                        }
                    }

                    if (!isStreamConfirmed || connectionState != STATE_CONNECTED) {
                        isStreamConfirmed = true;
                        updateState(STATE_CONNECTED, "Conectado a REAPER (" + targetIpStr + ") - Blindado");
                    }
                }
            } catch (SocketTimeoutException ignored) {
            } catch (Exception e) {
                // Transient socket warning on congested network: sleep briefly and retry, NEVER kill ACK thread prematurely!
                if (!isCapturing || udpSocket == null || udpSocket.isClosed()) break;
                try { Thread.sleep(80); } catch (InterruptedException ignored) { break; }
            }
        }
    }

    private void stopCapturing() {
        isCapturing = false;
        isRunning = false;
        synchronized (captureLock) { captureLock.notifyAll(); }
        updateState(STATE_IDLE, "Toca el boton para conectar");
        cleanup();
    }

    private void cleanup() {
        isCapturing = false;
        synchronized (captureLock) { captureLock.notifyAll(); }

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

        if (captureThread != null) {
            try { captureThread.interrupt(); } catch (Exception ignored) {}
            captureThread = null;
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
