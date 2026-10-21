package com.anc.lite;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.*;
import android.os.*;
import android.view.WindowManager;
import android.widget.*;

public class MainActivity extends Activity {
    static final int SR = 48000;
    volatile boolean running = false;
    volatile float gain = 0.8f;      // anti-noise amplitude
    volatile float cutoff = 800f;    // Hz, ANC works best at low freq
    Thread worker;
    Button btn;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL); l.setPadding(48, 48, 48, 48);
        TextView t = new TextView(this);
        t.setText("Plug in earphones, keep phone mic uncovered near the noise.\nGain (anti-noise level):");
        SeekBar g = new SeekBar(this); g.setMax(100); g.setProgress(80);
        g.setOnSeekBarChangeListener(new SimpleSeek() { public void onProgressChanged(SeekBar s, int p, boolean u) { gain = p / 100f * 1.5f; }});
        TextView t2 = new TextView(this); t2.setText("Max frequency (200-2000 Hz):");
        SeekBar c = new SeekBar(this); c.setMax(1800); c.setProgress(600);
        c.setOnSeekBarChangeListener(new SimpleSeek() { public void onProgressChanged(SeekBar s, int p, boolean u) { cutoff = 200 + p; }});
        btn = new Button(this); btn.setText("START ANC");
        btn.setOnClickListener(v -> toggle());
        l.addView(t); l.addView(g); l.addView(t2); l.addView(c); l.addView(btn);
        setContentView(l);
    }

    void toggle() {
        if (running) { running = false; btn.setText("START ANC"); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1); return;
        }
        running = true; btn.setText("STOP ANC");
        worker = new Thread(this::loop); worker.start();
    }

    @Override public void onRequestPermissionsResult(int r, String[] p, int[] g) {
        if (g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) toggle();
    }

    void loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        int minIn = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int minOut = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord rec = new AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.UNPROCESSED) // no AGC/NS/echo cancel
            .setAudioFormat(new AudioFormat.Builder().setSampleRate(SR)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(minIn).build();
        AudioTrack trk = new AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(new AudioFormat.Builder().setSampleRate(SR)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(minOut).build();
        int frames = 64; // ~1.3 ms blocks
        short[] buf = new short[frames];
        float lp = 0, dcX = 0, dcY = 0;
        rec.startRecording(); trk.play();
        while (running) {
            int n = rec.read(buf, 0, frames);
            float a = (float) (1 - Math.exp(-2 * Math.PI * cutoff / SR));
            for (int i = 0; i < n; i++) {
                float x = buf[i] / 32768f;
                float y = x - dcX + 0.995f * dcY; dcX = x; dcY = y;   // DC blocker
                lp += a * (y - lp);                                   // low-pass
                float o = -gain * lp;                                 // 180° anti-phase
                o = Math.max(-0.9f, Math.min(0.9f, o));
                buf[i] = (short) (o * 32767);
            }
            trk.write(buf, 0, n);
        }
        rec.stop(); rec.release(); trk.stop(); trk.release();
    }

    @Override protected void onDestroy() { running = false; super.onDestroy(); }

    abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        public void onStartTrackingTouch(SeekBar s) {}
        public void onStopTrackingTouch(SeekBar s) {}
    }
}
