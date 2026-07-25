package com.arslan.guard;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.Collections;

/**
 * Kilit ekranında art arda yanlış PIN girildiğinde ön kamerayı kullanarak
 * sessizce (önizleme göstermeden) tek kare fotoğraf çeken yardımcı sınıf.
 *
 * Camera2 API, Android 5.0 (API 21) itibarıyla tüm cihazlarda mevcuttur,
 * bu nedenle eski cihazlarla da uyumludur. Xiaomi/MIUI gibi özel
 * arayüzlerde kamera erişimi için ekstra bir MIUI izni gerekmez; sadece
 * standart CAMERA çalışma zamanı izni yeterlidir.
 */
public class IntruderCapture {

    private static final String TAG = "IntruderCapture";

    private final Context appContext;
    private CameraDevice cameraDevice;
    private ImageReader imageReader;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    public IntruderCapture(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /**
     * Ön kamerayı açar, tek kare JPEG yakalar ve uygulamanın özel
     * depo alanına kaydeder. İzin verilmemişse veya ön kamera
     * bulunamazsa sessizce hiçbir şey yapmaz (uygulamayı çökertmez).
     */
    public void captureIntruderPhoto() {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "CAMERA izni verilmedi, intruder selfie atlanıyor.");
            return;
        }

        CameraManager cameraManager = (CameraManager) appContext.getSystemService(Context.CAMERA_SERVICE);
        if (cameraManager == null) return;

        try {
            String frontCameraId = findFrontCameraId(cameraManager);
            if (frontCameraId == null) {
                Log.w(TAG, "Ön kamera bulunamadı.");
                return;
            }

            startBackgroundThread();

            imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 1);
            imageReader.setOnImageAvailableListener(onImageAvailableListener, backgroundHandler);

            cameraManager.openCamera(frontCameraId, cameraStateCallback, backgroundHandler);
        } catch (CameraAccessException | SecurityException e) {
            Log.e(TAG, "Kamera açılamadı: " + e.getMessage());
            cleanup();
        }
    }

    private String findFrontCameraId(CameraManager manager) throws CameraAccessException {
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                return id;
            }
        }
        // Ön kamera yoksa (bazı özel cihazlar), ilk kamerayı kullan
        String[] ids = manager.getCameraIdList();
        return ids.length > 0 ? ids[0] : null;
    }

    private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            cameraDevice = camera;
            try {
                final CaptureRequest.Builder captureBuilder =
                        camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                captureBuilder.addTarget(imageReader.getSurface());
                captureBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);

                camera.createCaptureSession(
                        Collections.singletonList(imageReader.getSurface()),
                        new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(CameraCaptureSession session) {
                                try {
                                    session.capture(captureBuilder.build(), null, backgroundHandler);
                                } catch (CameraAccessException e) {
                                    Log.e(TAG, "Fotoğraf çekilemedi: " + e.getMessage());
                                    cleanup();
                                }
                            }

                            @Override
                            public void onConfigureFailed(CameraCaptureSession session) {
                                Log.e(TAG, "Kamera oturumu yapılandırılamadı.");
                                cleanup();
                            }
                        }, backgroundHandler);
            } catch (CameraAccessException e) {
                Log.e(TAG, "Capture request oluşturulamadı: " + e.getMessage());
                cleanup();
            }
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            cleanup();
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            Log.e(TAG, "Kamera hatası: " + error);
            cleanup();
        }
    };

    private final ImageReader.OnImageAvailableListener onImageAvailableListener =
            new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    Image image = null;
                    try {
                        image = reader.acquireLatestImage();
                        if (image != null) {
                            saveImageToDisk(image);
                        }
                    } finally {
                        if (image != null) image.close();
                        cleanup();
                    }
                }
            };

    private void saveImageToDisk(Image image) {
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);

        File dir = PrefsHelper.getIntruderSelfieDir(appContext);
        String fileName = "intruder_" + System.currentTimeMillis() + ".jpg";
        File outFile = new File(dir, fileName);

        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            fos.write(bytes);
            fos.flush();
            Log.i(TAG, "Intruder selfie kaydedildi: " + outFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Fotoğraf kaydedilemedi: " + e.getMessage());
        }
    }

    private void cleanup() {
        if (cameraDevice != null) {
            try {
                cameraDevice.close();
            } catch (Exception ignored) {
            }
            cameraDevice = null;
        }
        if (imageReader != null) {
            try {
                imageReader.close();
            } catch (Exception ignored) {
            }
            imageReader = null;
        }
        stopBackgroundThread();
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("ArslanGuardCameraThread");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
            } catch (InterruptedException ignored) {
            }
            backgroundThread = null;
            backgroundHandler = null;
        }
    }
}
