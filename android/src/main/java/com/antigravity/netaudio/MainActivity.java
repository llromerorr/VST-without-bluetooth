package com.antigravity.netaudio;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQUEST_MEDIA_PROJECTION = 1001;
    private static final int REQUEST_AUDIO_PERMISSION = 2001;

    private Button btnPower;
    private TextView txtStatus;
    private TextView txtVolume;
    private SeekBar seekVolume;
    private MediaProjectionManager projectionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnPower = findViewById(R.id.btnPower);
        txtStatus = findViewById(R.id.txtStatus);
        txtVolume = findViewById(R.id.txtVolume);
        seekVolume = findViewById(R.id.seekVolume);

        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        seekVolume.setMax(100);
        seekVolume.setProgress(AudioCaptureService.currentVolumePercent);
        txtVolume.setText("VOLUMEN: " + AudioCaptureService.currentVolumePercent + "%");

        seekVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                txtVolume.setText("VOLUMEN: " + progress + "%");
                AudioCaptureService.currentVolumePercent = progress;
                if (AudioCaptureService.isRunning) {
                    Intent intent = new Intent(MainActivity.this, AudioCaptureService.class);
                    intent.setAction(AudioCaptureService.ACTION_SET_VOL);
                    intent.putExtra(AudioCaptureService.EXTRA_VOLUME, progress);
                    startService(intent);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        updateUiState();

        btnPower.setOnClickListener(v -> {
            if (AudioCaptureService.isRunning) {
                stopStreaming();
            } else {
                startStreaming();
            }
        });

        // Request audio permission if needed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO_PERMISSION);
            }
        }

        // Prompt to ignore battery optimization so Android never sleeps the app
        requestBatteryOptimizationExemption();
    }

    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                }
            } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        seekVolume.setProgress(AudioCaptureService.currentVolumePercent);
        txtVolume.setText("VOLUMEN: " + AudioCaptureService.currentVolumePercent + "%");
        updateUiState();
    }

    private void updateUiState() {
        if (AudioCaptureService.isRunning) {
            btnPower.setText("TRANSMITIENDO\n\n(TOCA PARA APAGAR)");
            btnPower.setBackgroundColor(0xFF2ED573);
            btnPower.setTextColor(0xFF000000);
            txtStatus.setText("Conectado a REAPER (En vivo)");
            txtStatus.setTextColor(0xFF2ED573);
        } else {
            btnPower.setText("CONECTAR");
            btnPower.setBackgroundColor(0xFF1E2026);
            btnPower.setTextColor(0xFF00D2D2);
            txtStatus.setText("Toca el boton para conectar");
            txtStatus.setTextColor(0xFFA0A0B0);
        }
    }

    private void startStreaming() {
        if (projectionManager != null) {
            startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION);
        }
    }

    private void stopStreaming() {
        Intent intent = new Intent(this, AudioCaptureService.class);
        intent.setAction(AudioCaptureService.ACTION_STOP);
        startService(intent);
        AudioCaptureService.isRunning = false;
        updateUiState();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                Intent serviceIntent = new Intent(this, AudioCaptureService.class);
                serviceIntent.setAction(AudioCaptureService.ACTION_START);
                serviceIntent.putExtra(AudioCaptureService.EXTRA_RESULT_CODE, resultCode);
                serviceIntent.putExtra(AudioCaptureService.EXTRA_RESULT_DATA, data);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }

                AudioCaptureService.isRunning = true;
                updateUiState();
            } else {
                Toast.makeText(this, "Permiso de captura denegado", Toast.LENGTH_SHORT).show();
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }
}