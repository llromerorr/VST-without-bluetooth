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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
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

    // WOB 6.0 Protocol & Frame Constants (exact match with WOB.c)
    private static final short WOB6_MAGIC = 0x3657;
    private static final int WOB_HDR_LEN = 20;
    private static final int TYPE_DATA = 1;
    private static final int TYPE_FEC_XOR = 2;
    private static final int TYPE_PING = 3;

    private static final int CODEC_SILENCE = 0;
    private static final int CODEC_BFP8 = 1;
    private static final int CODEC_BFP6 = 2;

    private static final int SAMPLE_RATE = 48000;
    private static final int FRAME_SAMPLES = 384; // Exact 8.0 ms stereo frame
    private static final int NUM_BLOCKS = 24;
    private static final int FRAME_SIZE_BFP8 = 796;
    private static final int FRAME_SIZE_BFP6 = 604;

    private static final int CAPTURE_RING_SLOTS = 128; // 1.024s decoupling queue
    private static final int RETX_CACHE_SLOTS = 256;

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
    private volatile Socket tcpSocket;
    private volatile OutputStream tcpOut;
    private volatile InputStream tcpIn;
    private volatile DatagramSocket udpSocket;

    private Thread captureThread;
    private Thread senderThread;
    private Thread ackThread;
    private volatile boolean isCapturing = false;
    private volatile boolean isStreamConfirmed = false;
    private volatile boolean useTcpMode = false;
    private volatile long lastAckTime = 0;
    private volatile long writeInFlightStartMs = 0;

    // Auto codec tier (0 = BFP8 HD, 1 = BFP6 Dual-Pack ultra-light 62.5 pps)
    private volatile int desiredTier = 0;
    private volatile long lastStressEventMs = 0;

    // Pending UDP NACKs (used only if firewall forces UDP fallback)
    private static final int MAX_PENDING_NACKS = 32;
    private final Object nackLock = new Object();
    private final int[] pendingNackBase = new int[MAX_PENDING_NACKS];
    private final int[] pendingNackMask = new int[MAX_PENDING_NACKS];
    private int pendingNackHead = 0;
    private int pendingNackTail = 0;

    // Decoupled Capture Ring between CaptureThread and SenderThread
    private final Object captureLock = new Object();
    private final short[][] capRingL = new short[CAPTURE_RING_SLOTS][FRAME_SAMPLES];
    private final short[][] capRingR = new short[CAPTURE_RING_SLOTS][FRAME_SAMPLES];
    private volatile int capWriteIdx = 0;
    private volatile int capReadIdx = 0;

    private volatile InetAddress targetAddr = null;
    private volatile String targetIpStr = null;
    private volatile int serverProtoVersion = 6;
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
            sock.setTrafficClass(0xB8);
        } catch (Exception e) {
            try { sock.setTrafficClass(0x88); } catch (Exception e2) {
                try { sock.setTrafficClass(0x10); } catch (Exception ignored) {}
            }
        }
    }

    private void configureTcpSocketQoS(Socket sock) {
        try { sock.setTcpNoDelay(true); } catch (Exception ignored) {}
        try { sock.setSendBufferSize(64 * 1024); } catch (Exception ignored) {} // Anti-bloat cap (~0.8s max)
        try { sock.setReceiveBufferSize(64 * 1024); } catch (Exception ignored) {}
        try { sock.setSoTimeout(350); } catch (Exception ignored) {}
        try { sock.setKeepAlive(true); } catch (Exception ignored) {}
        try {
            sock.setTrafficClass(0xB8);
        } catch (Exception e) {
            try { sock.setTrafficClass(0x88); } catch (Exception e2) {
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
        int protoVersion; // 6=TCP+UDP, 5=UDP-WOB5, 4=Legacy
        DiscoveryResult(String ip, Network net, int protoVersion) {
            this.ip = ip;
            this.net = net;
            this.protoVersion = protoVersion;
        }
    }

    private DiscoveryResult discoverTargetPC(boolean fastOnly) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String lastKnownIp = prefs.getString(KEY_LAST_IP, "");
        Network bestNet = findBestLocalNetwork();
        List<SubnetInfo> subnets = collectLocalLanSubnets(bestNet);

        byte[] discoverMsg = "NA_DISCOVER".getBytes();
        byte[] recvBuf = new byte[128];

        Network[] trialNets = (bestNet != null) ? new Network[]{bestNet, null} : new Network[]{null};

        for (Network net : trialNets) {
            if (!isCapturing) return null;
            DatagramSocket sock = null;
            try {
                sock = new DatagramSocket();
                sock.setBroadcast(true);
                sock.setSoTimeout(280);
                configureUdpSocketQoS(sock, net);

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
                            int ver = reply.contains("WOB6") ? 6 : (reply.contains("WOB5") ? 5 : 4);
                            sock.close();
                            return new DiscoveryResult(foundIp, net, ver);
                        }
                    } catch (SocketTimeoutException ignored) {
                        break;
                    }
                }

                if (!fastOnly && !subnets.isEmpty() && isCapturing) {
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
                                    int ver = reply.contains("WOB6") ? 6 : (reply.contains("WOB5") ? 5 : 4);
                                    sock.close();
                                    return new DiscoveryResult(foundIp, net, ver);
                                }
                            } catch (SocketTimeoutException ignored) {
                                break;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Discovery error: " + e.getMessage());
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

    private void closeTransportQuietly() {
        writeInFlightStartMs = 0;
        Socket s = tcpSocket;
        tcpSocket = null;
        tcpOut = null;
        tcpIn = null;
        if (s != null) {
            try { s.close(); } catch (Exception ignored) {}
        }
        DatagramSocket u = udpSocket;
        udpSocket = null;
        if (u != null) {
            try { u.close(); } catch (Exception ignored) {}
        }
        Thread a = ackThread;
        ackThread = null;
        if (a != null) {
            try { a.interrupt(); } catch (Exception ignored) {}
        }
    }

    private boolean openTransportConnection() {
        closeTransportQuietly();

        // Attempt TCP first if REAPER supports WOB 6.0 (0% packet loss, zero FEC waste!)
        if (serverProtoVersion >= 6) {
            try {
                Socket s;
                if (boundLocalNetwork != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    s = boundLocalNetwork.getSocketFactory().createSocket();
                } else {
                    s = new Socket();
                }
                s.connect(new InetSocketAddress(targetAddr, TARGET_PORT), 1200);
                configureTcpSocketQoS(s);
                tcpOut = s.getOutputStream();
                tcpIn = s.getInputStream();
                tcpSocket = s;
                useTcpMode = true;
                Log.i(TAG, "Connected via TCP Smart Stream to " + targetIpStr);
                return true;
            } catch (Exception e) {
                Log.w(TAG, "TCP connect failed (" + e.getMessage() + "), falling back to UDP.");
            }
        }

        // Automatic UDP Fallback (if Windows firewall blocks TCP port 58200 or server is v4/v5)
        try {
            DatagramSocket u = new DatagramSocket();
            u.setSoTimeout(300);
            configureUdpSocketQoS(u, boundLocalNetwork);
            udpSocket = u;
            useTcpMode = false;
            Log.i(TAG, "Connected via UDP fallback to " + targetIpStr);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "UDP fallback open failed: " + e.getMessage());
            return false;
        }
    }

    // =========================================================================
    // Main Networking, Self-Healing TCP/UDP Streamer & Watchdog
    // =========================================================================
    private void runNetworkingAndSender() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);

        DiscoveryResult disc = null;
        while (isCapturing && disc == null) {
            updateState(STATE_SEARCHING, "Buscando REAPER en tu red Wi-Fi...");
            disc = discoverTargetPC(false);
            if (disc == null) {
                updateState(STATE_SEARCHING, "REAPER no detectado. Abre REAPER con WOB en la PC.");
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            }
        }

        if (!isCapturing || disc == null) return;

        try {
            targetIpStr = disc.ip;
            targetAddr = InetAddress.getByName(disc.ip);
            serverProtoVersion = disc.protoVersion;
            boundLocalNetwork = disc.net;

            final int channelConfig = AudioFormat.CHANNEL_IN_STEREO;
            final int encoding = AudioFormat.ENCODING_PCM_16BIT;
            int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, encoding);
            int osBufSize = Math.max(minBuf, SAMPLE_RATE * 4 / 2);

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

            captureThread = new Thread(this::runCaptureLoop, "WOB-Capture");
            captureThread.start();

            // Preallocate all zero-GC working buffers once
            final byte[][] cacheBytes = new byte[RETX_CACHE_SLOTS][FRAME_SIZE_BFP8];
            final int[] cacheSeq = new int[RETX_CACHE_SLOTS];
            final int[] cacheCodec = new int[RETX_CACHE_SLOTS];
            final int[] cacheLen = new int[RETX_CACHE_SLOTS];
            final long[] cacheLastResent = new long[RETX_CACHE_SLOTS];

            final byte[] pktBuf = new byte[1400];
            final ByteBuffer pktBB = ByteBuffer.wrap(pktBuf).order(ByteOrder.LITTLE_ENDIAN);
            final DatagramPacket outPkt = new DatagramPacket(pktBuf, pktBuf.length);

            final byte[] xorBuf = new byte[1400];
            final byte[] fecPktBuf = new byte[1400];
            final ByteBuffer fecBB = ByteBuffer.wrap(fecPktBuf).order(ByteOrder.LITTLE_ENDIAN);
            final DatagramPacket fecOutPkt = new DatagramPacket(fecPktBuf, fecPktBuf.length);

            final byte[] pingBuf = new byte[WOB_HDR_LEN];
            final ByteBuffer pingBB = ByteBuffer.wrap(pingBuf).order(ByteOrder.LITTLE_ENDIAN);
            final int[] scratchQ = new int[FRAME_SAMPLES];
            final Random rng = new Random();

            // Outer Auto-Reconnect Loop (heals seamlessly on Wi-Fi dropouts without interrupting AudioRecord)
            while (isCapturing) {
                if (!openTransportConnection()) {
                    updateState(STATE_RECONNECTING, "Sin enlace Wi-Fi. Reintentando...");
                    DiscoveryResult redir = discoverTargetPC(true);
                    if (redir != null) {
                        targetIpStr = redir.ip;
                        targetAddr = InetAddress.getByName(redir.ip);
                        serverProtoVersion = redir.protoVersion;
                        boundLocalNetwork = redir.net;
                    }
                    try { Thread.sleep(600); } catch (InterruptedException ignored) { break; }
                    continue;
                }

                for (int i = 0; i < RETX_CACHE_SLOTS; i++) cacheSeq[i] = -1;
                synchronized (nackLock) {
                    pendingNackHead = 0;
                    pendingNackTail = 0;
                }
                // Drop excess stale buffered audio on fresh socket reconnect (keep freshest ~120ms)
                int wNow = capWriteIdx;
                if (wNow - capReadIdx > 16) {
                    capReadIdx = wNow - 16;
                }

                final int sessionTag = rng.nextInt() | 1;
                lastAckTime = System.currentTimeMillis();

                ackThread = new Thread(this::runAckListener, "WOB-Ack");
                ackThread.setDaemon(true);
                ackThread.start();

                sendControlPing(pingBuf, pingBB, sessionTag);

                int frameSeq = 0;
                int pktSeq = 0;
                int activeTier = desiredTier;
                int fecK = (activeTier == 0) ? 4 : 2;
                int fecIdx = 0;
                int fecBasePkt = 0;
                int xorMaxLen = 0;
                long lastKeepAliveMs = System.currentTimeMillis();

                while (isCapturing) {
                    long now = System.currentTimeMillis();

                    // Watchdog check: if ACK went silent > 4.2s on dead link -> cleanly cycle socket!
                    if (now - lastAckTime > 4200) {
                        isStreamConfirmed = false;
                        updateState(STATE_RECONNECTING, "Reconectando enlace Wi-Fi...");
                        break; // Reconnects cleanly in outer loop!
                    }

                    // Cautious demotion from BFP6 -> BFP8 after 60s clean link
                    if (desiredTier > 0 && (now - lastStressEventMs) > 60000L) {
                        desiredTier = 0;
                        lastStressEventMs = now;
                    }

                    if (!useTcpMode) {
                        processPendingUdpNacks(cacheSeq, cacheCodec, cacheBytes, cacheLen, cacheLastResent, pktBuf, pktBB, outPkt, sessionTag, now);
                    }

                    if (fecIdx == 0 && activeTier != desiredTier) {
                        activeTier = desiredTier;
                        fecK = (activeTier == 0) ? 4 : 2;
                    }

                    // In Tier 0 (HD): 1 frame per write (8ms, BFP8). In Tier 1 (Crowded Church): 2 frames per write (16ms = 62.5 pps, BFP6!)
                    int framesNeeded = (activeTier == 0 || serverProtoVersion < 5) ? 1 : 2;
                    int available = capWriteIdx - capReadIdx;

                    if (available < framesNeeded) {
                        if (now - lastKeepAliveMs > 180) {
                            if (!sendControlPing(pingBuf, pingBB, sessionTag)) break;
                            lastKeepAliveMs = now;
                        }
                        synchronized (captureLock) {
                            try { captureLock.wait(12); } catch (InterruptedException ignored) {}
                        }
                        continue;
                    }

                    // If TCP stalled momentarily and capture ring filled > 750ms, trim oldest frames smoothly
                    if (available > 94) {
                        capReadIdx += (available - 48);
                    }

                    int r0Idx = capReadIdx;
                    int slot0 = r0Idx & (CAPTURE_RING_SLOTS - 1);
                    short[] L0 = capRingL[slot0];
                    short[] R0 = capRingR[slot0];

                    if (serverProtoVersion == 4) {
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

                    if (!useTcpMode && fecIdx == 0) {
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
                        payloadLen = encodeFrame(L0, R0, pktCodec, pktBuf, WOB_HDR_LEN, scratchQ);

                        if (!useTcpMode) {
                            int cs = pktFrameSeq & (RETX_CACHE_SLOTS - 1);
                            cacheSeq[cs] = pktFrameSeq; cacheCodec[cs] = pktCodec; cacheLen[cs] = payloadLen;
                            if (payloadLen > 0) System.arraycopy(pktBuf, WOB_HDR_LEN, cacheBytes[cs], 0, payloadLen);
                        }
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
                            if (!useTcpMode) {
                                int cs0 = pktFrameSeq & (RETX_CACHE_SLOTS - 1);
                                cacheSeq[cs0] = pktFrameSeq; cacheCodec[cs0] = CODEC_SILENCE; cacheLen[cs0] = 0;
                                int cs1 = (pktFrameSeq + 1) & (RETX_CACHE_SLOTS - 1);
                                cacheSeq[cs1] = pktFrameSeq + 1; cacheCodec[cs1] = CODEC_SILENCE; cacheLen[cs1] = 0;
                            }
                        } else {
                            pktCodec = CODEC_BFP6;
                            int len0 = encodeFrame(L0, R0, CODEC_BFP6, pktBuf, WOB_HDR_LEN, scratchQ);
                            int len1 = encodeFrame(L1, R1, CODEC_BFP6, pktBuf, WOB_HDR_LEN + len0, scratchQ);
                            payloadLen = len0 + len1;

                            if (!useTcpMode) {
                                int cs0 = pktFrameSeq & (RETX_CACHE_SLOTS - 1);
                                cacheSeq[cs0] = pktFrameSeq; cacheCodec[cs0] = CODEC_BFP6; cacheLen[cs0] = len0;
                                System.arraycopy(pktBuf, WOB_HDR_LEN, cacheBytes[cs0], 0, len0);
                                int cs1 = (pktFrameSeq + 1) & (RETX_CACHE_SLOTS - 1);
                                cacheSeq[cs1] = pktFrameSeq + 1; cacheCodec[cs1] = CODEC_BFP6; cacheLen[cs1] = len1;
                                System.arraycopy(pktBuf, WOB_HDR_LEN + len0, cacheBytes[cs1], 0, len1);
                            }
                        }
                        frameSeq += 2;
                    }

                    int flagsByte = (pktCodec & 0x07) | ((framesNeeded & 0x03) << 3) | ((activeTier & 0x01) << 6);

                    pktBB.clear();
                    pktBB.putShort(WOB6_MAGIC);
                    pktBB.put((byte) TYPE_DATA);
                    pktBB.put((byte) flagsByte);
                    pktBB.putInt(pktFrameSeq);
                    pktBB.putInt(useTcpMode ? 0 : fecBasePkt);
                    pktBB.put((byte) (useTcpMode ? 0 : fecIdx));
                    pktBB.put((byte) (useTcpMode ? 0 : fecK));
                    pktBB.putShort((short) payloadLen);
                    pktBB.putInt(sessionTag);

                    if (useTcpMode) {
                        OutputStream out = tcpOut;
                        if (out == null) break;
                        try {
                            long t0 = System.currentTimeMillis();
                            writeInFlightStartMs = t0;
                            out.write(pktBuf, 0, WOB_HDR_LEN + payloadLen);
                            writeInFlightStartMs = 0;
                            long elapsed = System.currentTimeMillis() - t0;
                            lastKeepAliveMs = now;
                            // If a TCP write ever takes > 25ms due to Wi-Fi stall, promote cleanly to compact BFP-6 mode!
                            if (elapsed > 25 && desiredTier == 0) {
                                desiredTier = 1;
                                lastStressEventMs = now;
                            }
                        } catch (IOException e) {
                            writeInFlightStartMs = 0;
                            Log.w(TAG, "TCP write broken (" + e.getMessage() + "), reconnecting...");
                            updateState(STATE_RECONNECTING, "Reconectando Wi-Fi...");
                            break;
                        }
                    } else {
                        outPkt.setData(pktBuf, 0, WOB_HDR_LEN + payloadLen);
                        outPkt.setAddress(targetAddr);
                        outPkt.setPort(TARGET_PORT);
                        try {
                            udpSocket.send(outPkt);
                            lastKeepAliveMs = now;
                        } catch (Exception ignored) {}

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
                                xorBuf[7 + k] ^= pktBuf[WOB_HDR_LEN + k];
                            }

                            fecIdx++;
                            if (fecIdx >= fecK) {
                                fecBB.clear();
                                fecBB.putShort(WOB6_MAGIC);
                                fecBB.put((byte) TYPE_FEC_XOR);
                                fecBB.put((byte) ((activeTier & 0x01) << 6));
                                fecBB.putInt(0);
                                fecBB.putInt(fecBasePkt);
                                fecBB.put((byte) fecK);
                                fecBB.put((byte) fecK);
                                fecBB.putShort((short) xorMaxLen);
                                fecBB.putInt(sessionTag);
                                System.arraycopy(xorBuf, 0, fecPktBuf, WOB_HDR_LEN, xorMaxLen);

                                fecOutPkt.setData(fecPktBuf, 0, WOB_HDR_LEN + xorMaxLen);
                                fecOutPkt.setAddress(targetAddr);
                                fecOutPkt.setPort(TARGET_PORT);
                                try { udpSocket.send(fecOutPkt); } catch (Exception ignored) {}
                                fecIdx = 0;
                            }
                        }
                    }
                    pktSeq++;
                }

                closeTransportQuietly();
                if (isCapturing) {
                    try { Thread.sleep(250); } catch (InterruptedException ignored) { break; }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Sender fatal error: " + e.getMessage(), e);
        } finally {
            cleanup();
        }
    }

    private boolean sendControlPing(byte[] pingBuf, ByteBuffer pingBB, int sessionTag) {
        if (serverProtoVersion == 4 && udpSocket != null) {
            try {
                byte[] p4 = new byte[]{0x4E, 0x41, 0, 0};
                udpSocket.send(new DatagramPacket(p4, p4.length, targetAddr, TARGET_PORT));
                return true;
            } catch (Exception ignored) { return false; }
        }

        pingBB.clear();
        pingBB.putShort(WOB6_MAGIC);
        pingBB.put((byte) TYPE_PING);
        pingBB.put((byte) 0);
        pingBB.putInt(0);
        pingBB.putInt(0);
        pingBB.put((byte) 0);
        pingBB.put((byte) 0);
        pingBB.putShort((short) 0);
        pingBB.putInt(sessionTag);

        if (useTcpMode) {
            OutputStream out = tcpOut;
            if (out == null) return false;
            try {
                writeInFlightStartMs = System.currentTimeMillis();
                out.write(pingBuf, 0, WOB_HDR_LEN);
                out.flush();
                writeInFlightStartMs = 0;
                return true;
            } catch (IOException e) {
                writeInFlightStartMs = 0;
                return false;
            }
        } else if (udpSocket != null && !udpSocket.isClosed() && targetAddr != null) {
            try {
                udpSocket.send(new DatagramPacket(pingBuf, WOB_HDR_LEN, targetAddr, TARGET_PORT));
                return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    private void processPendingUdpNacks(int[] cacheSeq, int[] cacheCodec, byte[][] cacheBytes,
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
                if (now - cacheLastResent[cs] < 28) continue;
                cacheLastResent[cs] = now;

                int codec = cacheCodec[cs];
                int len = cacheLen[cs];
                int flagsByte = (codec & 0x07) | (1 << 3) | (1 << 5) | ((desiredTier & 0x01) << 6);

                pktBB.clear();
                pktBB.putShort(WOB6_MAGIC);
                pktBB.put((byte) TYPE_DATA);
                pktBB.put((byte) flagsByte);
                pktBB.putInt(reqSeq);
                pktBB.putInt(0);
                pktBB.put((byte) 0);
                pktBB.put((byte) 0);
                pktBB.putShort((short) len);
                pktBB.putInt(sessionTag);
                if (len > 0) System.arraycopy(cacheBytes[cs], 0, pktBuf, WOB_HDR_LEN, len);

                outPkt.setData(pktBuf, 0, WOB_HDR_LEN + len);
                outPkt.setAddress(targetAddr);
                outPkt.setPort(TARGET_PORT);
                try { udpSocket.send(outPkt); } catch (Exception ignored) {}
            }
        }
    }

    private void handleAckMessage(String msg, long now) {
        if (msg.startsWith("NA_NACK:")) {
            lastAckTime = now;
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
                    synchronized (captureLock) { captureLock.notify(); }
                    lastStressEventMs = now;
                    desiredTier = 1;
                } catch (Exception ignored) {}
            }
        } else if (msg.startsWith("NA_ACK") || msg.startsWith("NA_OFFER")) {
            lastAckTime = now;
            if (msg.startsWith("NA_ACK:")) {
                String[] p = msg.split(":");
                if (p.length >= 5) {
                    try {
                        float targetBufMs = Float.parseFloat(p[2]);
                        float lossPct = Float.parseFloat(p[3]);
                        float jitterMs = Float.parseFloat(p[4]);
                        if (jitterMs >= 18.0f || targetBufMs >= 750.0f || lossPct > 0.5f) {
                            lastStressEventMs = now;
                            desiredTier = 1; // Step smoothly into BFP6 Dual-Pack (62.5 pps)
                        }
                    } catch (Exception ignored) {}
                }
            }
            if (!isStreamConfirmed || connectionState != STATE_CONNECTED) {
                isStreamConfirmed = true;
                String mode = useTcpMode ? "TCP Blindado" : "UDP";
                updateState(STATE_CONNECTED, "Conectado a REAPER (" + targetIpStr + ") - " + mode);
            }
        }
    }

    private void runAckListener() {
        byte[] lineBuf = new byte[256];
        int lineLen = 0;

        if (useTcpMode) {
            while (isCapturing && useTcpMode && tcpSocket != null && !tcpSocket.isClosed()) {
                long now = System.currentTimeMillis();
                // Blocked-write watchdog: if kernel TCP send hangs > 3.5s, kill socket immediately so sender recovers cleanly
                long wStart = writeInFlightStartMs;
                if (wStart > 0 && (now - wStart) > 3500) {
                    Log.w(TAG, "Watchdog closing stalled TCP socket after 3.5s blocked write");
                    try { tcpSocket.close(); } catch (Exception ignored) {}
                    break;
                }

                try {
                    InputStream in = tcpIn;
                    if (in == null) break;
                    int b = in.read();
                    if (b < 0) {
                        // Clean EOF by peer
                        break;
                    }
                    if (b == '\n') {
                        if (lineLen > 0) {
                            String line = new String(lineBuf, 0, lineLen).trim();
                            handleAckMessage(line, System.currentTimeMillis());
                            lineLen = 0;
                        }
                    } else if (b != '\r' && lineLen < lineBuf.length - 1) {
                        lineBuf[lineLen++] = (byte) b;
                    }
                } catch (SocketTimeoutException ignored) {
                    // Normal 350ms read wake-up so watchdog runs cleanly
                } catch (Exception e) {
                    break;
                }
            }
        } else {
            byte[] recvBuf = new byte[256];
            DatagramPacket recvPkt = new DatagramPacket(recvBuf, recvBuf.length);
            while (isCapturing && !useTcpMode && udpSocket != null && !udpSocket.isClosed()) {
                try {
                    udpSocket.receive(recvPkt);
                    String msg = new String(recvPkt.getData(), 0, recvPkt.getLength()).trim();
                    handleAckMessage(msg, System.currentTimeMillis());
                } catch (SocketTimeoutException ignored) {
                } catch (Exception e) {
                    if (!isCapturing || udpSocket == null || udpSocket.isClosed()) break;
                    try { Thread.sleep(80); } catch (InterruptedException ignored) { break; }
                }
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

        closeTransportQuietly();

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
