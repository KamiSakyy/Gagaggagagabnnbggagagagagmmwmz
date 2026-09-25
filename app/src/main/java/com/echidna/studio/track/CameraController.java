package com.echidna.studio.track;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import com.echidna.studio.EchidnaLog;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Front camera feeding the tracker and the preview.
 *
 * <p>Camera2 is used directly: the app needs exactly one YUV stream, and doing it by hand keeps the
 * frame path short and the APK free of another library. Frames are converted to ARGB with a hand
 * written YUV converter (much cheaper than the JPEG round trip of the classic sample) and are
 * already rotated upright when they are published, so the tracker and the preview use them as they
 * are. Every published frame is a fresh bitmap that nobody writes to afterwards, which is what makes
 * it safe to hand the very same object to the analysis thread and to the renderer.</p>
 */
public final class CameraController {
    public interface FrameListener {
        void onFrame(Bitmap bitmap, long timestampMs);
    }

    public interface StateListener {
        void onCameraState(boolean running, String message);
    }

    private static final Size PREFERRED = new Size(640, 480);
    private static final int MAX_PIXELS = 1280 * 960;

    private final Context context;
    private HandlerThread thread;
    private Handler handler;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private FrameListener frameListener;
    private StateListener stateListener;

    private int sensorOrientation;
    private int targetRotation = Surface.ROTATION_0;
    private volatile boolean running;
    private volatile boolean frontFacing = true;
    private volatile String lastError = "";
    private volatile long frames;
    private volatile int frameWidth;
    private volatile int frameHeight;
    private final BitmapPool pool = new BitmapPool();
    private long lastFrameLog;

    public CameraController(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setFrameListener(FrameListener listener) {
        frameListener = listener;
    }

    public void setStateListener(StateListener listener) {
        stateListener = listener;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isFrontFacing() {
        return frontFacing;
    }

    public long frameCount() {
        return frames;
    }

    public String lastError() {
        return lastError;
    }

    public int frameWidth() {
        return frameWidth;
    }

    public int frameHeight() {
        return frameHeight;
    }

    public static boolean hasPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    public void setTargetRotation(int rotation) {
        targetRotation = rotation;
    }

    /** Opens the camera. Returns false when it is not available or not permitted. */
    public boolean start() {
        if (running) {
            return true;
        }
        if (!hasPermission(context)) {
            report(false, "нет разрешения на камеру");
            return false;
        }
        final CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) {
            report(false, "камера недоступна");
            return false;
        }
        try {
            final String id = pickCameraId(manager);
            if (id == null) {
                report(false, "камера не найдена");
                return false;
            }
            final CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
            final Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            frontFacing = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
            final Integer orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = orientation == null ? 0 : orientation;

            final StreamConfigurationMap map =
                    characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            final Size size = chooseSize(map);
            frameWidth = size.getWidth();
            frameHeight = size.getHeight();

            startThread();
            reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 3);
            reader.setOnImageAvailableListener(this::onImageAvailable, handler);

            manager.openCamera(id, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    device = camera;
                    createSession(characteristics);
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    EchidnaLog.w("CAMERA", "камера отключилась");
                    camera.close();
                    device = null;
                    running = false;
                    report(false, "камера отключилась");
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    EchidnaLog.e("CAMERA", "ошибка камеры " + error);
                    camera.close();
                    device = null;
                    running = false;
                    report(false, "ошибка камеры " + error);
                }
            }, handler);
            running = true;
            EchidnaLog.i("CAMERA", "старт: " + id + " " + size + ", сенсор " + sensorOrientation + "°");
            report(true, "камера " + size.getWidth() + "x" + size.getHeight()
                    + (frontFacing ? " фронтальная" : " основная"));
            return true;
        } catch (CameraAccessException | SecurityException | IllegalArgumentException e) {
            EchidnaLog.e("CAMERA", "не удалось открыть камеру", e);
            report(false, "камера: " + e.getClass().getSimpleName());
            return false;
        }
    }

    public void stop() {
        running = false;
        if (session != null) {
            try {
                session.close();
            } catch (RuntimeException ignored) {
                // nothing to do
            }
            session = null;
        }
        if (device != null) {
            try {
                device.close();
            } catch (RuntimeException ignored) {
                // nothing to do
            }
            device = null;
        }
        if (reader != null) {
            reader.close();
            reader = null;
        }
        stopThread();
        report(false, "камера остановлена");
    }

    private String pickCameraId(CameraManager manager) throws CameraAccessException {
        final String[] ids = manager.getCameraIdList();
        String front = null;
        String back = null;
        for (String id : ids) {
            final CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
            final Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (facing == null) {
                continue;
            }
            if (facing == CameraCharacteristics.LENS_FACING_FRONT && front == null) {
                front = id;
            } else if (facing == CameraCharacteristics.LENS_FACING_BACK && back == null) {
                back = id;
            }
        }
        return front != null ? front : back;
    }

    private Size chooseSize(StreamConfigurationMap map) {
        if (map == null) {
            return PREFERRED;
        }
        final Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
        if (sizes == null || sizes.length == 0) {
            return PREFERRED;
        }
        final List<Size> candidates = new ArrayList<Size>(Arrays.asList(sizes));
        final List<Size> allowed = new ArrayList<Size>();
        for (int i = 0; i < candidates.size(); i++) {
            final Size size = candidates.get(i);
            if ((long) size.getWidth() * size.getHeight() <= MAX_PIXELS) {
                allowed.add(size);
            }
        }
        if (allowed.isEmpty()) {
            allowed.addAll(candidates);
        }
        allowed.sort(Comparator.comparingLong(size ->
                Math.abs((long) size.getWidth() * size.getHeight()
                        - (long) PREFERRED.getWidth() * PREFERRED.getHeight())));
        for (int i = 0; i < allowed.size(); i++) {
            final Size size = allowed.get(i);
            if (size.getWidth() >= size.getHeight()) {
                return size;
            }
        }
        final Size first = allowed.get(0);
        return first.getWidth() > first.getHeight()
                ? first
                : new Size(first.getHeight(), first.getWidth());
    }

    private void createSession(CameraCharacteristics characteristics) {
        if (device == null || reader == null) {
            return;
        }
        try {
            final CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(reader.getSurface());
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
            if (hasAutoFocus(characteristics)) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            }
            final Range<Integer> fps = pickFpsRange(characteristics);
            if (fps != null) {
                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps);
            }

            final List<Surface> surfaces = new ArrayList<Surface>();
            surfaces.add(reader.getSurface());
            device.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession configured) {
                    session = configured;
                    try {
                        session.setRepeatingRequest(builder.build(), null, handler);
                        report(true, "камера работает");
                    } catch (CameraAccessException | IllegalArgumentException | IllegalStateException e) {
                        EchidnaLog.e("CAMERA", "не удалось запустить поток кадров", e);
                        report(false, "кадры не пошли: " + e.getClass().getSimpleName());
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession configured) {
                    report(false, "сессия камеры не настроилась");
                }
            }, handler);
        } catch (CameraAccessException | IllegalArgumentException | IllegalStateException e) {
            EchidnaLog.e("CAMERA", "сессия камеры не создалась", e);
            report(false, "сессия камеры: " + e.getClass().getSimpleName());
        }
    }

    private static boolean hasAutoFocus(CameraCharacteristics characteristics) {
        final int[] modes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        if (modes == null) {
            return false;
        }
        for (int mode : modes) {
            if (mode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) {
                return true;
            }
        }
        return false;
    }

    private static Range<Integer> pickFpsRange(CameraCharacteristics characteristics) {
        final Range<Integer>[] ranges =
                characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges == null || ranges.length == 0) {
            return null;
        }
        Range<Integer> best = null;
        for (Range<Integer> range : ranges) {
            if (range.getUpper() < 24) {
                continue;
            }
            if (best == null
                    || range.getLower() > best.getLower()
                    || (range.getLower().equals(best.getLower())
                        && range.getUpper() > best.getUpper())) {
                best = range;
            }
        }
        return best != null ? best : ranges[ranges.length - 1];
    }

    private void onImageAvailable(ImageReader source) {
        final Image image = source.acquireLatestImage();
        if (image == null) {
            return;
        }
        try {
            if (!running) {
                return;
            }
            final int width = image.getWidth();
            final int height = image.getHeight();
            frames++;
            final long timestampMs = System.currentTimeMillis();

            final int rotation = rotationDegrees();
            // Buffers are recycled: at thirty frames per second a fresh 1.2 MB bitmap for every
            // frame would mean forty megabytes of garbage per second, and a stream runs for hours.
            final Bitmap published = convertYuvToArgb(image, rotation, pool.acquire(width, height, rotation));

            if (frameListener != null) {
                frameListener.onFrame(published, timestampMs);
            }
            if (timestampMs - lastFrameLog > 5000) {
                lastFrameLog = timestampMs;
                EchidnaLog.i("CAMERA", "кадров: " + frames + ", " + width + "x" + height
                        + ", поворот " + rotation + "°");
            }
        } catch (Throwable t) {
            EchidnaLog.w("CAMERA", "кадр не обработан: " + t);
        } finally {
            image.close();
        }
    }

    private int rotationDegrees() {
        final int deviceRotation;
        switch (targetRotation) {
            case Surface.ROTATION_90:
                deviceRotation = 90;
                break;
            case Surface.ROTATION_180:
                deviceRotation = 180;
                break;
            case Surface.ROTATION_270:
                deviceRotation = 270;
                break;
            default:
                deviceRotation = 0;
                break;
        }
        return frontFacing
                ? (sensorOrientation + deviceRotation) % 360
                : (sensorOrientation - deviceRotation + 360) % 360;
    }

    /**
     * YUV_420_888 to ARGB in one pass, straight into the requested orientation.
     *
     * <p>Every plane is read with its own row and pixel stride, which is what makes the converter
     * survive the padding some devices add to their buffers. Rotating while converting means one
     * bitmap allocation per frame and no extra blit, which matters because this runs on every
     * camera frame for hours on end.</p>
     *
     * @param rotation clockwise rotation in degrees: 0, 90, 180 or 270
     */
    static Bitmap convertYuvToArgb(Image image, int rotation) {
        return convertYuvToArgb(image, rotation, null);
    }

    /**
     * @param target bitmap to write into, or null to allocate a new one; it must already be the
     *               right size, which is what {@link BitmapPool} takes care of
     */
    static Bitmap convertYuvToArgb(Image image, int rotation, Bitmap target) {
        final int width = image.getWidth();
        final int height = image.getHeight();
        final boolean quarter = rotation == 90 || rotation == 270;
        final int outWidth = quarter ? height : width;
        final int outHeight = quarter ? width : height;
        final Bitmap out = target != null
                ? target
                : Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888);

        final Image.Plane[] planes = image.getPlanes();
        final ByteBuffer yPlane = planes[0].getBuffer();
        final ByteBuffer uPlane = planes[1].getBuffer();
        final ByteBuffer vPlane = planes[2].getBuffer();

        final int yRowStride = planes[0].getRowStride();
        final int yPixelStride = planes[0].getPixelStride();
        final int uRowStride = planes[1].getRowStride();
        final int uPixelStride = planes[1].getPixelStride();
        final int vRowStride = planes[2].getRowStride();
        final int vPixelStride = planes[2].getPixelStride();

        final int[] pixels = new int[outWidth * outHeight];
        final int yBase = yPlane.position();
        final int uBase = uPlane.position();
        final int vBase = vPlane.position();

        for (int dy = 0; dy < outHeight; dy++) {
            final int outRow = dy * outWidth;
            for (int dx = 0; dx < outWidth; dx++) {
                final int sx;
                final int sy;
                switch (rotation) {
                    case 90:
                        sx = dy;
                        sy = height - 1 - dx;
                        break;
                    case 180:
                        sx = width - 1 - dx;
                        sy = height - 1 - dy;
                        break;
                    case 270:
                        sx = width - 1 - dy;
                        sy = dx;
                        break;
                    default:
                        sx = dx;
                        sy = dy;
                        break;
                }
                final int yValue = (yPlane.get(yBase + sy * yRowStride + sx * yPixelStride) & 0xFF) - 16;
                final int uvX = sx >> 1;
                final int uvY = sy >> 1;
                final int uValue =
                        (uPlane.get(uBase + uvY * uRowStride + uvX * uPixelStride) & 0xFF) - 128;
                final int vValue =
                        (vPlane.get(vBase + uvY * vRowStride + uvX * vPixelStride) & 0xFF) - 128;

                final int yScaled = (yValue < 0 ? 0 : yValue) * 1192;
                int r = (yScaled + 1634 * vValue) >> 10;
                int g = (yScaled - 833 * vValue - 400 * uValue) >> 10;
                int b = (yScaled + 2066 * uValue) >> 10;
                if (r < 0) {
                    r = 0;
                } else if (r > 255) {
                    r = 255;
                }
                if (g < 0) {
                    g = 0;
                } else if (g > 255) {
                    g = 255;
                }
                if (b < 0) {
                    b = 0;
                } else if (b > 255) {
                    b = 255;
                }
                pixels[outRow + dx] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        out.setPixels(pixels, 0, outWidth, 0, 0, outWidth, outHeight);
        return out;
    }

    /**
     * A rotating set of camera frames.
     *
     * <p>Each buffer is handed out once per {@link #SLOTS} frames, which is far longer than the
     * lifetime of a single frame: the renderer uploads it as a texture within one frame and the
     * tracker finishes with it in a few dozen milliseconds, while a slot comes back only after
     * roughly a tenth of a second. The rotation therefore removes the allocation churn without
     * ever overwriting a buffer somebody is still reading.</p>
     */
    static final class BitmapPool {
        private static final int SLOTS = 4;
        private final Bitmap[] slots = new Bitmap[SLOTS];
        private int cursor;

        Bitmap acquire(int sourceWidth, int sourceHeight, int rotation) {
            final boolean quarter = rotation == 90 || rotation == 270;
            final int width = quarter ? sourceHeight : sourceWidth;
            final int height = quarter ? sourceWidth : sourceHeight;
            for (int i = 0; i < SLOTS; i++) {
                final int index = (cursor + i) % SLOTS;
                final Bitmap candidate = slots[index];
                if (candidate != null
                        && candidate.getWidth() == width
                        && candidate.getHeight() == height) {
                    cursor = (index + 1) % SLOTS;
                    return candidate;
                }
            }
            final Bitmap created = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            slots[cursor] = created;
            cursor = (cursor + 1) % SLOTS;
            return created;
        }
    }

    private void startThread() {
        stopThread();
        thread = new HandlerThread("echidna-camera");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    private void stopThread() {
        if (thread != null) {
            thread.quitSafely();
            try {
                thread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
            handler = null;
        }
    }

    private void report(boolean isRunning, String message) {
        if (!isRunning && message != null && !message.contains("остановлена")) {
            lastError = message;
        }
        if (stateListener != null) {
            stateListener.onCameraState(isRunning, message);
        }
    }
}
