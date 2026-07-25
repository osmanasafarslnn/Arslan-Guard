package com.arslan.guard;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * Cihazın ivmeölçer (accelerometer) sensörünü dinleyerek "sallama"
 * hareketini algılayan hafif, bağımlılıksız yardımcı sınıf.
 *
 * Ekstra bir izin GEREKMEZ (BODY_SENSORS izni sadece kalp atışı gibi
 * biyometrik sensörler içindir, ivmeölçer normal bir sensördür).
 */
public class ShakeDetector implements SensorEventListener {

    public interface OnShakeListener {
        void onShake();
    }

    // Hassasiyet seviyesine göre eşik değerleri (düşük eşik = daha hassas)
    private static final float THRESHOLD_LOW = 22f;
    private static final float THRESHOLD_MEDIUM = 15f;
    private static final float THRESHOLD_HIGH = 10f;

    private static final long MIN_SHAKE_INTERVAL_MS = 1000L; // art arda tetiklenmeyi önler

    private final SensorManager sensorManager;
    private final Sensor accelerometer;
    private OnShakeListener listener;

    private float shakeThreshold = THRESHOLD_MEDIUM;
    private long lastShakeTime = 0L;

    private float lastX, lastY, lastZ;
    private boolean firstReading = true;

    public ShakeDetector(Context context) {
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        accelerometer = sensorManager != null
                ? sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                : null;
    }

    public void setOnShakeListener(OnShakeListener listener) {
        this.listener = listener;
    }

    /** 1 = Düşük, 2 = Orta, 3 = Yüksek hassasiyet */
    public void setSensitivity(int level) {
        switch (level) {
            case 1:
                shakeThreshold = THRESHOLD_LOW;
                break;
            case 3:
                shakeThreshold = THRESHOLD_HIGH;
                break;
            case 2:
            default:
                shakeThreshold = THRESHOLD_MEDIUM;
                break;
        }
    }

    public boolean isAvailable() {
        return accelerometer != null;
    }

    public void start() {
        if (sensorManager != null && accelerometer != null) {
            // SENSOR_DELAY_NORMAL: batarya dostu, düşük CPU kullanımı
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    public void stop() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;

        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];

        if (firstReading) {
            lastX = x;
            lastY = y;
            lastZ = z;
            firstReading = false;
            return;
        }

        float deltaX = Math.abs(lastX - x);
        float deltaY = Math.abs(lastY - y);
        float deltaZ = Math.abs(lastZ - z);

        // Basit ama etkili "toplam ivme değişimi" hesabı (FFT/matris hesabı gerektirmez)
        float totalDelta = deltaX + deltaY + deltaZ;

        lastX = x;
        lastY = y;
        lastZ = z;

        if (totalDelta > shakeThreshold) {
            long now = System.currentTimeMillis();
            if (now - lastShakeTime > MIN_SHAKE_INTERVAL_MS) {
                lastShakeTime = now;
                if (listener != null) {
                    listener.onShake();
                }
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Kullanılmıyor
    }
}
