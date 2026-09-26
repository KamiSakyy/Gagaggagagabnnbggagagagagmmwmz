package com.echidna.studio.track;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import com.echidna.studio.EchidnaLog;
import com.echidna.studio.Json;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns the camera, the trackers and the analysis loop.
 *
 * <p>Camera frames arrive on the camera thread; the heavy tracker runs on its own thread so that the
 * camera is never blocked. Results are published as {@link FaceSignals} and picked up by the
 * renderer on the GL thread. Every layer below the hub can be swapped: MediaPipe first, ML Kit if
 * MediaPipe cannot start, and a synthetic source for the automated check.</p>
 */
public final class TrackingHub {
    public interface SignalsListener {
        void onSignals(FaceSignals signals);
    }

    public interface CameraPreviewListener {
        /**
         * @param frame  готовая копия кадра: камера в неё больше не пишет
         * @param serial номер кадра: он растёт, и по нему рендер понимает, что картинка новая
         */
        void onPreview(Bitmap frame, int serial);
    }

    /** Каждый третий кадр уходит в разбор перевёрнутым, пока лицо не найдено. */
    private static final int PROBE_EVERY = 3;
    /** Как часто (мс) пробовать перевёрнутый кадр, пока лицо не найдено. */
    private static final long FLIP_PROBE_INTERVAL_MS = 1500;
    /**
     * Тело считается реже лица: плечи и руки не мигают, а два графа MediaPipe на одном кадре
     * съедают телефон. Между замерами используется последний результат - он всё равно сглаживается.
     */
    private static final long POSE_INTERVAL_MS = 66;
    /**
     * Кисть разбирается реже лица: 15 раз в секунду хватает, чтобы жест читался мгновенно, а
     * телефон при этом не греется.
     */
    private static final long HAND_INTERVAL_MS = 50;
    /** Сколько ждём первых результатов трекера, прежде чем считать его зависшим. */
    private static final long TRACKER_START_GRACE_MS = 4000;
    /** Сколько ждём новых результатов, пока трекер молчит. */
    private static final long TRACKER_SILENCE_MS = 6000;
    /**
     * Кадров предпросмотра в обороте.
     *
     * <p>Камера переиспользует свои буферы, поэтому картинку для окошка надо снять с них: иначе
     * рендер читает буфер, в который камера уже пишет следующий кадр, и окошко дёргается. Три
     * копии - это заведомо больше, чем нужно GL-потоку, чтобы успеть загрузить текстуру.</p>
     */
    private static final int PREVIEW_SLOTS = 3;

    private final Context context;
    private final CameraController camera;
    private final HandlerThread analysisThread;
    private final Handler analysisHandler;
    private final List<String> diagnostics = new ArrayList<String>();

    /**
     * Трекеры поднимаются в фоне, уже после того как камера начала отдавать кадры.
     *
     * <p>Модели лица, тела и кисти весят вместе двадцать мегабайт, и раньше приложение грузило их
     * прямо в обработчике нажатия: человек нажимал «камера» и ждал секунду-две чёрного экрана.
     * Теперь порядок обратный - сначала включается камера и в окошке сразу видно себя, а
     * распознавание поднимается следом. Поля поэтому изменчивые: их читает поток камеры.</p>
     */
    private volatile FaceTracker tracker;
    /** Трекер тела и рук: работает вместе с лицом и подстраховывает его. */
    private volatile FaceTracker poseTracker;
    private long lastPoseMs;
    private FaceSignals lastPoseSignals;
    /**
     * Трекер кисти: 21 точка на руку, счёт пальцев и жест «рука у подбородка».
     *
     * <p>Он третий в цепочке, потому что отвечает на другой вопрос: лицо даёт мимику, поза - тело,
     * а кисть - то, что человек показывает рукой. Работает реже остальных: пальцевый граф тяжелее
     * и не нужен на каждом кадре.</p>
     */
    private volatile FaceTracker handTracker;
    private long lastHandMs;
    private FaceSignals lastHandSignals;
    private final FaceSignals merged = new FaceSignals();
    private SignalsListener signalsListener;
    private CameraPreviewListener previewListener;

    private volatile boolean running;
    private volatile boolean analyzing;
    private volatile boolean syntheticOnly;
    private long lastAnalysisMs;
    private long analyzedFrames;
    private long analyzedEmpty;
    private long startedAtMs;
    /** Когда последний раз проверяли, не приходит ли кадр вверх ногами. */
    private long lastFlipProbeMs;
    private volatile FaceSignals lastSignals;
    /** Последние сигналы лица и тела: кисть и поза обновляются реже, поэтому хранятся отдельно. */
    private FaceSignals lastFaceSignals;
    private volatile int framesPerSecond;
    /** Готовые кадры для окошка предпросмотра: копии, а не буферы камеры. */
    private final Bitmap[] previewSlots = new Bitmap[PREVIEW_SLOTS];
    private int previewCursor;
    /** Номер последнего отданного кадра: рендер по нему понимает, что картинка новая. */
    private volatile int previewSerial;
    private int previewInFlight = -1;
    /**
     * Копии кадров для трекеров.
     *
     * <p>У лица и у пробы по два буфера: граф читает картинку в своём потоке, и если ответ не
     * успел прийти за отведённое время, писать в тот же буфер нельзя - трекер прочитал бы
     * наполовину новый кадр. Второй буфер гарантирует, что читающий всегда видит целую картинку.
     * Тело и кисть делят один буфер, потому что кадр им не отдаётся, пока они заняты.</p>
     */
    private final Bitmap[] faceFrames = new Bitmap[2];
    private final Bitmap[] probeFrames = new Bitmap[2];
    private int faceCursor;
    private int probeCursor;
    private Bitmap bodyFrame;
    /** Номер кадра: по нему видно, когда делать пробу переворота. */
    private long frameCounter;
    /** Что делали с последним кадром: 0 лицо, 1 тело, 2 кисть, 3 проверка переворота. */
    private volatile int cameraPhase;
    /** Наблюдения за положением кадра и решение о перевороте. */
    private final FlipDecision flipDecision = new FlipDecision();
    /**
     * Решение о перевороте по самому лицу.
     *
     * <p>Работает всегда, когда лицо найдено: если глаза на кадре оказываются ниже рта, кадр стоит
     * вверх ногами, и приложение доворачивает его само. Это надёжнее любых формул с углом сенсора,
     * потому что проверяется по человеку, а не по характеристике камеры.</p>
     */
    private final FrameOrientation orientation = new FrameOrientation();
    private long trackerOpenedMs;
    private long lastResultsSeen;
    private long lastResultsChangeMs;
    /** Ждали ли мы ответа лица на последнем такте: видно в отчёте. */
    private volatile boolean waitingForFace;
    /** Готово ли распознавание: пока нет, интерфейс пишет «распознавание загружается». */
    private volatile boolean trackersReady;
    /**
     * Трекеры, поднятые заранее.
     *
     * <p>Модели распознавания можно собрать до того, как человек нажмёт «камера»: пока он выбирает
     * персонажа и смотрит движения, лицо, тело и кисть уже загружены в память. Тогда нажатие
     * включает камеру сразу, без единой задержки на загрузку. Если человек нажимает «камера»
     * раньше, чем закончилась подготовка, работает обычный путь: трекеры поднимаются в фоне, а в
     * окошке уже видно себя.</p>
     */
    private volatile FaceTracker preparedTracker;
    private volatile FaceTracker preparedPose;
    private volatile FaceTracker preparedHand;
    private volatile boolean warmUpStarted;
    /** Когда начали поднимать трекеры: по этому видно, сколько заняла загрузка. */
    private volatile long trackersStartedAt;

    public TrackingHub(Context context) {
        this.context = context.getApplicationContext();
        this.camera = new CameraController(context);
        this.analysisThread = new HandlerThread("echidna-analysis");
        this.analysisThread.start();
        this.analysisHandler = new Handler(analysisThread.getLooper());

        camera.setFrameListener(this::onCameraFrame);
        camera.setStateListener((running, message) -> addDiagnostic("камера: " + message));
    }

    public CameraController camera() {
        return camera;
    }

    public void setSignalsListener(SignalsListener listener) {
        signalsListener = listener;
    }

    public void setPreviewListener(CameraPreviewListener listener) {
        previewListener = listener;
    }

    public boolean isRunning() {
        return running;
    }

    public String trackerName() {
        if (tracker == null) {
            return "нет";
        }
        final StringBuilder builder = new StringBuilder(tracker.name());
        if (poseTracker != null) {
            builder.append(" + ").append(poseTracker.name());
        }
        if (handTracker != null) {
            builder.append(" + ").append(handTracker.name());
        }
        return builder.toString();
    }

    /** Есть ли трекер кисти: от него зависят жесты рукой. */
    public boolean handsAvailable() {
        return handTracker != null;
    }

    /** Номер последнего кадра предпросмотра: растёт, когда картинка обновилась. */
    public int previewSerial() {
        return previewSerial;
    }

    public long analyzedFrames() {
        return analyzedFrames;
    }

    public long emptyFrames() {
        return analyzedEmpty;
    }

    public int framesPerSecond() {
        return framesPerSecond;
    }

    public FaceSignals lastSignals() {
        return lastSignals;
    }

    public List<String> diagnostics() {
        return diagnostics;
    }

    public void addDiagnostic(String line) {
        if (diagnostics.size() > 40) {
            diagnostics.remove(0);
        }
        diagnostics.add(line);
    }

    /** True while a face is in front of the camera (or the synthetic source says so). */
    public boolean faceVisible() {
        final FaceSignals signals = lastSignals;
        return signals != null && signals.found;
    }

    /** Diagnostic report, also used by the self test. */
    public String report() {
        return "трекер=" + trackerName()
                + ", руки=" + (handsAvailable() ? "да" : "нет")
                + ", кадров камеры=" + camera.frameCount()
                + ", проанализировано=" + analyzedFrames
                + ", без лица=" + analyzedEmpty
                + ", камер " + camera.frameWidth() + "x" + camera.frameHeight()
                + ", " + framesPerSecond + " анализ/с"
                + ", ожидание ответа лица: " + (waitingForFace ? "да" : "нет")
                + ", распознавание: " + (trackersReady ? "готово" : "загружается");
    }

    /**
     * Starts the camera and the tracker.
     *
     * @param synthetic run the synthetic source instead of the camera (self test)
     * @return true when tracking is up
     */
    public boolean start(boolean synthetic) {
        if (running) {
            return true;
        }
        syntheticOnly = synthetic;
        startedAtMs = SystemClock.elapsedRealtime();
        analyzedFrames = 0;
        analyzedEmpty = 0;

        if (synthetic) {
            tracker = new SyntheticFaceTracker();
            try {
                tracker.start();
            } catch (Exception e) {
                EchidnaLog.e("TRACK", "синтетический трекер не запустился", e);
                return false;
            }
            running = true;
            addDiagnostic("трекер: синтетический (самопроверка)");
            analysisHandler.post(this::syntheticLoop);
            return true;
        }

        if (!CameraController.hasPermission(context)) {
            addDiagnostic("нет разрешения на камеру");
            return false;
        }
        if (!camera.start()) {
            addDiagnostic("камера не запустилась: " + camera.lastError());
            return false;
        }
        // Камера уже отдаёт кадры: в окошке сразу видно себя. Распознавание поднимается в фоне -
        // загрузка моделей больше не держит нажатие кнопки.
        running = true;
        trackersReady = false;
        orientation.reset();
        trackersStartedAt = SystemClock.elapsedRealtime();
        addDiagnostic("камера включена, распознавание поднимается");
        analysisHandler.post(this::openTrackersInBackground);
        return true;
    }

    /**
     * Поднимает трекеры вне потока интерфейса и сообщает, когда они готовы.
     *
     * <p>Пока идёт загрузка, окошко камеры уже живое, а модель показывает демонстрационную позу -
     * ровно так же, как если бы человека не было в кадре. Это лучше, чем замёрзший чёрный экран.</p>
     */
    private void openTrackersInBackground() {
        final long started = SystemClock.elapsedRealtime();
        final boolean ok = openTracker();
        trackersReady = ok;
        final long spent = SystemClock.elapsedRealtime() - started;
        EchidnaLog.i("TRACK", "распознавание готово за " + spent + " мс: " + trackerName());
        addDiagnostic("распознавание готово за " + spent + " мс: " + trackerName());
        if (signalsListener != null && !ok) {
            addDiagnostic("распознавание не поднялось: смотри журнал");
        }
    }

    /** Сколько раз кадр доворачивался сам, потому что лицо оказывалось вверх ногами. */
    public int cameraFlips() {
        return orientation.flips();
    }

    /** Готово ли распознавание лица: по этому интерфейс пишет «загружается» или «работает». */
    public boolean trackersReady() {
        return trackersReady;
    }

    /**
     * Поднимает трекеры заранее, не дожидаясь нажатия «камера».
     *
     * <p>Вызывается при запуске приложения, когда разрешение на камеру уже выдано. Загрузка идёт в
     * фоновом потоке и ничему не мешает: пока человек листает персонажей, модели уже готовы, и
     * включение камеры становится мгновенным.</p>
     *
     * @return true когда подготовка уже идёт или трекеры готовы
     */
    public boolean warmUp() {
        if (warmUpStarted || running || trackersReady) {
            return true;
        }
        warmUpStarted = true;
        addDiagnostic("готовлю распознавание заранее");
        analysisHandler.post(this::prepareTrackers);
        return true;
    }

    /** Собирает трекеры в фоне и складывает их до первого включения камеры. */
    private void prepareTrackers() {
        final long started = SystemClock.elapsedRealtime();
        try {
            if (preparedTracker == null) {
                preparedTracker = buildFaceTracker();
            }
            if (preparedPose == null) {
                preparedPose = buildPoseTracker();
            }
            if (preparedHand == null) {
                preparedHand = buildHandTracker();
            }
            trackersReady = true;
            final long spent = SystemClock.elapsedRealtime() - started;
            EchidnaLog.i("TRACK", "распознавание подготовлено заранее за " + spent + " мс");
            addDiagnostic("распознавание готово заранее за " + spent + " мс: " + trackerName());
        } catch (Throwable error) {
            EchidnaLog.w("TRACK", "подготовка распознавания не удалась: " + error);
            trackersReady = false;
            warmUpStarted = false;
        }
    }

    /** Забирает заранее поднятые трекеры: если они готовы, включать ничего не нужно. */
    private boolean adoptPreparedTrackers() {
        final FaceTracker face = preparedTracker;
        if (face == null) {
            return false;
        }
        tracker = face;
        poseTracker = preparedPose;
        handTracker = preparedHand;
        preparedTracker = null;
        preparedPose = null;
        preparedHand = null;
        trackersReady = true;
        trackerOpenedMs = SystemClock.elapsedRealtime();
        addDiagnostic("распознавание было готово заранее: " + trackerName());
        EchidnaLog.i("TRACK", "камера включает готовый трекер: " + trackerName());
        return true;
    }

    /** Собирает трекер лица: MediaPipe, а если он не завёлся - ML Kit. */
    private FaceTracker buildFaceTracker() throws Exception {
        if (MediaPipeFaceTracker.assetAvailable(context)) {
            try {
                final MediaPipeFaceTracker mediaPipe = new MediaPipeFaceTracker(context);
                mediaPipe.start();
                return mediaPipe;
            } catch (Throwable noMediaPipe) {
                EchidnaLog.w("TRACK", "MediaPipe не поднялся (" + noMediaPipe + "), беру ML Kit");
            }
        }
        final MlKitFaceTracker mlKit = new MlKitFaceTracker(context);
        mlKit.start();
        return mlKit;
    }

    /** Собирает трекер тела: без него нет плеч, наклона и высоты рук. */
    private FaceTracker buildPoseTracker() {
        if (!MediaPipePoseTracker.assetAvailable(context)) {
            return null;
        }
        try {
            final MediaPipePoseTracker pose = new MediaPipePoseTracker(context);
            pose.start();
            return pose;
        } catch (Throwable error) {
            EchidnaLog.w("TRACK", "трекер тела не поднялся: " + error);
            return null;
        }
    }

    /** Собирает трекер кисти: пальцы, ладонь, рука у подбородка. */
    private FaceTracker buildHandTracker() {
        if (!MediaPipeHandTracker.assetAvailable(context)) {
            return null;
        }
        try {
            final MediaPipeHandTracker hands = new MediaPipeHandTracker(context);
            hands.start();
            return hands;
        } catch (Throwable error) {
            EchidnaLog.w("TRACK", "трекер кисти не поднялся: " + error);
            return null;
        }
    }

    /**
     * Starts the body tracker next to the face tracker.
     *
     * <p>The two see different things: the face model is precise about the eyes and the mouth, the
     * pose model is robust and knows the shoulders, the hips and the hands. Running both is what
     * makes the camera mode feel like a mirror instead of a guessing game.</p>
     */
    private void openPoseTracker() {
        if (poseTracker != null) {
            poseTracker.stop();
            poseTracker = null;
        }
        if (!MediaPipePoseTracker.assetAvailable(context)) {
            addDiagnostic("модель позы отсутствует в APK, тело не отслеживается");
            return;
        }
        try {
            final MediaPipePoseTracker pose = new MediaPipePoseTracker(context);
            pose.start();
            poseTracker = pose;
            addDiagnostic("трекер тела: " + pose.name() + " (плечи, наклон, руки)");
            EchidnaLog.i("TRACK", "поднят трекер тела " + pose.name());
        } catch (Throwable error) {
            EchidnaLog.w("TRACK", "трекер тела не поднялся: " + error);
            addDiagnostic("трекер тела не поднялся: " + error.getClass().getSimpleName());
        }
    }

    /**
     * Starts the hand tracker: the third pair of eyes of the app.
     *
     * <p>Without it the camera still works - the face and the body are enough - but there are no
     * gestures: no finger count, no hand following the user's hand, no "touching the chin".</p>
     */
    private void openHandTracker() {
        if (handTracker != null) {
            handTracker.stop();
            handTracker = null;
        }
        if (!MediaPipeHandTracker.assetAvailable(context)) {
            addDiagnostic("модель кисти отсутствует в APK, жесты рукой недоступны");
            return;
        }
        try {
            final MediaPipeHandTracker hands = new MediaPipeHandTracker(context);
            hands.start();
            handTracker = hands;
            addDiagnostic("трекер кисти: " + hands.name() + " (пальцы, ладонь, подбородок)");
            EchidnaLog.i("TRACK", "поднят трекер кисти " + hands.name());
        } catch (Throwable error) {
            EchidnaLog.w("TRACK", "трекер кисти не поднялся: " + error);
            addDiagnostic("трекер кисти не поднялся: " + error.getClass().getSimpleName());
        }
    }

    /** Picks the best tracker that actually starts on this device. */
    private boolean openTracker() {
        // Готовые трекеры важнее всего: с ними камера включается мгновенно.
        if (adoptPreparedTrackers()) {
            return true;
        }
        if (tracker != null) {
            tracker.stop();
            tracker = null;
        }
        if (MediaPipeFaceTracker.assetAvailable(context)) {
            try {
                final MediaPipeFaceTracker mediaPipe = new MediaPipeFaceTracker(context);
                mediaPipe.start();
                tracker = mediaPipe;
                addDiagnostic("трекер: MediaPipe Face Landmarker (52 blendshape)");
                EchidnaLog.i("TRACK", "выбран MediaPipe Face Landmarker");
                openPoseTracker();
                openHandTracker();
                trackerOpenedMs = SystemClock.elapsedRealtime();
                return true;
            } catch (Throwable t) {
                EchidnaLog.w("TRACK", "MediaPipe не поднялся (" + t + "), пробуем ML Kit");
                addDiagnostic("MediaPipe не поднялся: " + t.getClass().getSimpleName());
            }
        } else {
            addDiagnostic("модель MediaPipe отсутствует в APK, используется ML Kit");
        }
        try {
            final MlKitFaceTracker mlKit = new MlKitFaceTracker(context);
            mlKit.start();
            tracker = mlKit;
            addDiagnostic("трекер: ML Kit Face Detection");
            EchidnaLog.i("TRACK", "выбран ML Kit Face Detection");
            openPoseTracker();
            openHandTracker();
            trackerOpenedMs = SystemClock.elapsedRealtime();
            return true;
        } catch (Throwable t) {
            EchidnaLog.w("TRACK", "ни один трекер не запустился: " + t);
            addDiagnostic("трекеры недоступны: " + t.getClass().getSimpleName());
        }
        // Never leave the streamer with a dead camera mode: the synthetic source keeps the avatar
        // alive and the diagnostics say exactly what is missing.
        try {
            final SyntheticFaceTracker fallback = new SyntheticFaceTracker();
            fallback.start();
            tracker = fallback;
            addDiagnostic("трекер: демонстрационный (живая мимика без лица)");
            EchidnaLog.i("TRACK", "трекеры недоступны, включён демонстрационный источник");
            return true;
        } catch (Throwable t) {
            EchidnaLog.e("TRACK", "демонстрационный источник тоже не поднялся", t);
            return false;
        }
    }

    public void stop() {
        running = false;
        trackersReady = false;
        // Трекеры не выбрасываются, а возвращаются в «заранее поднятые»: второе включение камеры
        // тогда тоже мгновенное. Держать их стоит немного памяти, зато нет повторной загрузки
        // двадцати мегабайт моделей.
        if (tracker != null) {
            preparedTracker = tracker;
            tracker = null;
        }
        if (poseTracker != null) {
            preparedPose = poseTracker;
            poseTracker = null;
        }
        if (handTracker != null) {
            preparedHand = handTracker;
            handTracker = null;
        }
        lastPoseSignals = null;
        lastPoseMs = 0L;
        lastHandSignals = null;
        lastHandMs = 0L;
        camera.stop();
    }

    /** Switches between the front and the back camera while running. */
    public boolean restartCamera() {
        if (!running || syntheticOnly) {
            return false;
        }
        final boolean wasRunning = running;
        camera.stop();
        if (!camera.start()) {
            running = false;
            return false;
        }
        running = wasRunning;
        return true;
    }

    public void release() {
        stop();
        closePrepared();
        analysisThread.quitSafely();
    }

    /** Закрывает заранее поднятые трекеры: приложение выгружается. */
    private void closePrepared() {
        if (preparedTracker != null) {
            preparedTracker.stop();
            preparedTracker = null;
        }
        if (preparedPose != null) {
            preparedPose.stop();
            preparedPose = null;
        }
        if (preparedHand != null) {
            preparedHand.stop();
            preparedHand = null;
        }
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * A frame arrived from the camera.
     *
     * <p>The camera recycles its buffers, so the bitmap it hands over is overwritten by the next
     * frame. It used to be given to the analysis thread as it was: the tracker then read half of the
     * old picture and half of the new one and reported "лицо не найдено" every other frame. The copy
     * below is what makes the analysis see a complete, frozen frame.</p>
     */
    /**
     * A frame arrived from the camera.
     *
     * <p>Три вещи делаются по-разному, потому что у них разная цена. Лицо - самое важное: кадр ему
     * отдаётся на каждом такте, и ответ ждётся, чтобы мимика не отставала. Тело и кисть - вспомога-
     * тельные: они разбираются через свои промежутки и асинхронно, чтобы не задерживать лицо.</p>
     *
     * <p>Копии делаются потому, что камера переиспользует свои буферы: без копии трекер читал бы
     * наполовину старый кадр, и лицо пропадало бы на ровном месте.</p>
     */
    private void onCameraFrame(Bitmap frame, long timestampMs) {
        if (frame == null) {
            return;
        }
        // Сначала окошко: пользователь должен видеть себя даже тогда, когда разбор кадра занят.
        publishPreview(frame);
        final long now = SystemClock.elapsedRealtime();
        if (!running) {
            camera.releaseFrame(frame);
            return;
        }
        if (analyzing) {
            // Разбор не успевает за камерой: кадр пропускаем, но окошко уже обновлено.
            camera.releaseFrame(frame);
            return;
        }
        if (!trackersReady) {
            // Распознавание ещё поднимается: кадры в него не отдаются. Иначе в очереди разбора
            // накопились бы кадры, снятые до загрузки моделей, и первый же ответ трекера показал бы
            // позу полуторасекундной давности.
            camera.releaseFrame(frame);
            return;
        }

        final FaceSignals known = lastSignals;
        final boolean searching = known == null || (!known.found && !known.poseOnly);
        final boolean poseDue = poseTracker != null && !isBusy(poseTracker)
                && now - lastPoseMs >= POSE_INTERVAL_MS;
        // Проба переворота: пока лицо не найдено, каждый третий кадр уходит в разбор перевёрнутым.
        final boolean probeDue = searching && frameCounter % PROBE_EVERY == 0
                && flipDecision.sinceLastFlip(now) > FlipDecision.QUIET_AFTER_FLIP_MS;
        final boolean handsDue = handTracker != null && !isBusy(handTracker)
                && now - lastHandMs >= HAND_INTERVAL_MS;
        final boolean bodyFree = !isBusy(poseTracker) && !isBusy(handTracker);

        frameCounter++;
        lastAnalysisMs = now;
        analyzing = true;

        final Bitmap faceCopy;
        final Bitmap bodyCopy;
        try {
            // Лицо всегда получает свежий кадр: это самая важная и самая чувствительная к задержке
            // часть. Перевёрнутый кадр для пробы делается из той же копии.
            faceCursor = 1 - faceCursor;
            faceCopy = copyInto(faceFrames[faceCursor], frame);
            faceFrames[faceCursor] = faceCopy;
            bodyCopy = (poseDue || handsDue) && bodyFree ? copyInto(bodyFrame, frame) : null;
        } catch (Throwable error) {
            EchidnaLog.w("TRACK", "не удалось скопировать кадр: " + error);
            analyzing = false;
            camera.releaseFrame(frame);
            return;
        }
        // Буфер камеры больше не нужен: у разбора и у окошка свои копии.
        camera.releaseFrame(frame);

        cameraPhase = probeDue ? 3 : (poseDue ? 1 : (handsDue ? 2 : 0));
        analysisHandler.post(() -> {
            try {
                checkTrackerHealth(now);
                stepAnalysis(faceCopy, bodyCopy, now, probeDue, poseDue, handsDue);
            } catch (Throwable t) {
                EchidnaLog.w("TRACK", "анализ кадра: " + t);
            } finally {
                analyzing = false;
            }
        });
    }

    /**
     * Один такт разбора: сначала лицо (и ожидание его ответа), потом тело и кисть.
     *
     * @param probeDue кадр этого такта уходит в разбор перевёрнутым: проверяем положение камеры
     */
    private void stepAnalysis(Bitmap faceCopy, Bitmap bodyCopy, long now, boolean probeDue,
                              boolean poseDue, boolean handsDue) {
        FaceSignals face = null;
        if (tracker != null && faceCopy != null) {
            Bitmap forFace = faceCopy;
            if (probeDue) {
                probeCursor = 1 - probeCursor;
                forFace = rotate180Into(probeFrames[probeCursor], faceCopy);
                probeFrames[probeCursor] = forFace;
            }
            final long submitStart = SystemClock.elapsedRealtime();
            final FaceSignals fresh = tracker.submit(
                    new FaceTracker.Frame(forFace, 0, null), now, probeDue);
            // Ожидание ответа лица видно в отчёте: по нему понятно, успевает ли граф за камерой.
            waitingForFace = SystemClock.elapsedRealtime() - submitStart > 8L;
            if (fresh != null) {
                lastFaceSignals = fresh;
                watchFaceOrientation(fresh, now);
            }
            face = lastFaceSignals;
            if (probeDue) {
                maybeFlipCamera(now);
            }
        }

        if (bodyCopy != null) {
            final FaceTracker.Frame body = new FaceTracker.Frame(bodyCopy, 0, null);
            if (poseDue && poseTracker != null) {
                lastPoseMs = now;
                final FaceSignals fresh = poseTracker.analyze(body, now);
                if (fresh != null) {
                    lastPoseSignals = fresh;
                }
            }
            if (handsDue && handTracker != null) {
                lastHandMs = now;
                final FaceSignals fresh = handTracker.analyze(body, now);
                if (fresh != null) {
                    lastHandSignals = fresh;
                }
            }
        }

        final FaceSignals pose = lastPoseSignals;
        final FaceSignals hands = lastHandSignals;
        if (face == null && pose == null && hands == null) {
            return;
        }
        final FaceSignals signals = merge(face, pose, hands);
        if (signals == null) {
            return;
        }
        analyzedFrames++;
        if (!signals.found) {
            analyzedEmpty++;
        }
        lastSignals = signals;
        if (signalsListener != null) {
            signalsListener.onSignals(signals);
        }
    }

    /**
     * По самому лицу решает, не приходит ли кадр вверх ногами.
     *
     * <p>Этому способу не нужны ни пробы перевёрнутых кадров, ни характеристики камеры: глаза выше
     * рта у человека и ниже рта у перевёрнутого кадра. Как только лицо несколько раз подряд оказалось
     * «наоборот», кадр доворачивается на 180 градусов - и в окошке, и в распознавании сразу.</p>
     */
    private void watchFaceOrientation(FaceSignals fresh, long now) {
        if (!fresh.faceUprightKnown) {
            return;
        }
        orientation.record(fresh.faceUpright);
        final int decision = orientation.decide(now);
        if (decision == FrameOrientation.UNSURE) {
            return;
        }
        if (decision == FrameOrientation.KEEP) {
            // Кадр стоит ровно: наблюдения больше не нужны, решение принято само собой.
            orientation.onConfirmedUpright();
            return;
        }
        final int rotation = (camera.extraRotation() + 180) % 360;
        camera.setExtraRotation(rotation);
        orientation.onFlipped(now);
        resetFlipStats();
        // Проверка перевёрнутых кадров больше не нужна: лицо уже сказало, как оно стоит.
        flipDecision.onFlipped(now);
        addDiagnostic("кадр приходит перевёрнутым (лицо вверх ногами): доворот " + rotation + "°");
        EchidnaLog.i("TRACK", "кадр повёрнут по лицу: доворот " + rotation + "°");
    }

    /**
     * По наблюдениям трекера решает, не приходит ли кадр вверх ногами.
     *
     * <p>Камера поворачивается только тогда, когда на обычных кадрах лицо не находилось ни разу, а
     * на перевёрнутых нашлось несколько раз. Иначе одно ложное срабатывание переворачивало камеру
     * на весь сеанс - именно это и видел пользователь.</p>
     */
    private void maybeFlipCamera(long now) {
        int[] stats = null;
        if (tracker instanceof MediaPipeFaceTracker) {
            stats = ((MediaPipeFaceTracker) tracker).flipStats();
        } else if (tracker instanceof MlKitFaceTracker) {
            stats = ((MlKitFaceTracker) tracker).flipStats();
        }
        if (stats == null) {
            return;
        }
        final int normalFrames = stats[0];
        final int normalHits = stats[1];
        final int flippedFrames = stats[2];
        final int flippedHits = stats[3];
        final boolean enough = normalFrames + flippedFrames
                >= FlipDecision.MIN_NORMAL_FRAMES + FlipDecision.MIN_FLIPPED_FRAMES;
        if (enough && flipDecision.evaluate(normalFrames, normalHits, flippedFrames, flippedHits, now)) {
            final int rotation = (camera.extraRotation() + 180) % 360;
            camera.setExtraRotation(rotation);
            flipDecision.onFlipped(now);
            resetFlipStats();
            addDiagnostic("кадр камеры приходит перевёрнутым: доворот " + rotation + "°");
            EchidnaLog.i("TRACK", "камера перевёрнута: доворот " + rotation + "°");
            return;
        }
        if (enough) {
            // Окно закрыто и ничего не подтвердилось: начинаем новое, но не чаще раза в 10 секунд.
            addDiagnostic("положение кадра проверено: доворот не нужен");
            EchidnaLog.i("TRACK", "проверка положения кадра: переворот не нужен (обычных кадров "
                    + normalFrames + ", с лицом " + normalHits + "; перевёрнутых " + flippedFrames
                    + ", с лицом " + flippedHits + ")");
            resetFlipStats();
            flipDecision.onFlipped(now);
        }
    }

    private void resetFlipStats() {
        if (tracker instanceof MediaPipeFaceTracker) {
            ((MediaPipeFaceTracker) tracker).resetFlipStats();
        } else if (tracker instanceof MlKitFaceTracker) {
            ((MlKitFaceTracker) tracker).resetFlipStats();
        }
    }

    /** Поворот кадра на 180 градусов в переиспользуемый буфер: проверка положения камеры. */
    private static Bitmap rotate180Into(Bitmap target, Bitmap source) {
        Bitmap out = target;
        if (out == null || out.getWidth() != source.getWidth()
                || out.getHeight() != source.getHeight()) {
            out = Bitmap.createBitmap(source.getWidth(), source.getHeight(),
                    Bitmap.Config.ARGB_8888);
        }
        final android.graphics.Matrix matrix = new android.graphics.Matrix();
        matrix.postRotate(180f);
        final android.graphics.Canvas canvas = new android.graphics.Canvas(out);
        canvas.drawBitmap(source, matrix, null);
        return out;
    }

    /** Runs the trackers on one frame and merges what they saw. */
    private FaceSignals merge(FaceSignals face, FaceSignals pose, FaceSignals hands) {
        final boolean anyHands = hands != null && hands.handsSeen;
        if (face == null && pose == null) {
            // Видна только рука: лицо трекер потерял, но жест всё равно должен дойти до модели.
            if (!anyHands) {
                return null;
            }
            merged.set(new FaceSignals());
            merged.found = true;
            merged.poseOnly = true;
            copyHands(hands, merged, null);
            return merged;
        }
        if (face == null) {
            // Лицо потеряно, но поза видит человека: голова едет за телом, руки - за кистью.
            merged.set(pose);
            if (!merged.found && anyHands) {
                merged.found = true;
                merged.poseOnly = true;
            }
            copyHands(hands, merged, pose);
            return merged;
        }
        if (pose == null || !pose.found) {
            merged.set(face);
            copyHands(hands, merged, face);
            return merged;
        }
        merged.set(face);
        merged.body = pose.body;
        merged.bodyYaw = pose.bodyYaw;
        merged.bodyRoll = pose.bodyRoll;
        merged.bodyLift = pose.bodyLift;
        merged.bodyShift = pose.bodyShift;
        merged.handUp = pose.handUp;
        if (!face.found) {
            // The face tracker lost the user: the pose tracker takes over the head as well.
            merged.found = true;
            merged.poseOnly = true;
            merged.yaw = pose.yaw;
            merged.pitch = pose.pitch;
            merged.roll = pose.roll;
            merged.centerX = pose.centerX;
            merged.centerY = pose.centerY;
            if (pose.scale > 0.05f) {
                merged.scale = pose.scale;
            }
            merged.eyeLeft = 1.0f;
            merged.eyeRight = 1.0f;
            merged.smile = 0.0f;
            merged.mouthOpen = 0.0f;
            merged.blendshapes = false;
        } else {
            merged.poseOnly = false;
        }
        copyHands(hands, merged, face);
        return merged;
    }

    /**
     * Adds what the hand model saw to the frame: the fingers, the palm, and how close the fingers are
     * to the chin.
     *
     * <p>The chin is computed here because it needs both trackers: the face supplies the place and
     * the height of the head, the hand supplies the fingertips. Distances are measured in face
     * heights, so the gesture works the same for someone close to the phone and for someone sitting
     * farther away.</p>
     */
    private void copyHands(FaceSignals hands, FaceSignals target, FaceSignals face) {
        if (hands == null || !hands.handsSeen) {
            return;
        }
        target.handsSeen = true;
        target.hands = hands.hands;
        target.fingers = hands.fingers;
        target.fingersLeft = hands.fingersLeft;
        target.fingersRight = hands.fingersRight;
        target.handOpen = hands.handOpen;
        target.handX = hands.handX;
        target.handY = hands.handY;
        target.indexX = hands.indexX;
        target.indexY = hands.indexY;
        target.middleX = hands.middleX;
        target.middleY = hands.middleY;
        target.handSpan = hands.handSpan;
        target.handLeft = hands.handLeft;

        // Высота лица нужна для жестов: "рука дошла до подбородка" измеряется в высотах лица и
        // поэтому работает и вблизи телефона, и вдали от него.
        final float faceHeight;
        if (face != null && face.faceHeight > 0.02f) {
            faceHeight = face.faceHeight;
        } else if (target.faceHeight > 0.02f) {
            faceHeight = target.faceHeight;
        } else {
            faceHeight = 0.5f;
        }
        final float chinX = face != null ? face.centerX : 0.0f;
        final float chinY = (face != null ? face.centerY : 0.0f) + faceHeight * 0.5f;

        // Каждая рука считается своей: поднятая правая не должна тянуть за собой левую.
        target.handSeenLeft = hands.handSeenLeft;
        target.handSeenRight = hands.handSeenRight;
        target.handOpenLeft = hands.handOpenLeft;
        target.handOpenRight = hands.handOpenRight;
        target.handXLeft = hands.handXLeft;
        target.handYLeft = hands.handYLeft;
        target.handXRight = hands.handXRight;
        target.handYRight = hands.handYRight;
        if (hands.handSeenLeft) {
            target.chinTouchLeft = HandPose.reachOf(hands.indexXLeft, hands.indexYLeft,
                    hands.middleXLeft, hands.middleYLeft, hands.palmXLeft, hands.palmYLeft,
                    chinX, chinY, faceHeight);
            target.handUpLeft = clamp01((chinY - hands.handYLeft) / (faceHeight * 1.2f));
        }
        if (hands.handSeenRight) {
            target.chinTouchRight = HandPose.reachOf(hands.indexXRight, hands.indexYRight,
                    hands.middleXRight, hands.middleYRight, hands.palmXRight, hands.palmYRight,
                    chinX, chinY, faceHeight);
            target.handUpRight = clamp01((chinY - hands.handYRight) / (faceHeight * 1.2f));
        }
        // Ведущая рука: на неё смотрит модель, когда показывает жест или тянется к подбородку.
        final boolean leadingLeft = hands.handLeft || !hands.handSeenRight;
        target.chinTouch = leadingLeft ? target.chinTouchLeft : target.chinTouchRight;
        // Высота руки от кисти участвует в подъёме руки модели наравне с плечами от трекера позы.
        target.handUp = Math.max(target.handUpLeft, target.handUpRight);
    }

    private static float clamp01(float v) {
        return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v);
    }

    /** Занят ли трекер кадром: пока занят, новый кадр ему отдавать нельзя. */
    private static boolean isBusy(FaceTracker tracker) {
        return tracker != null && tracker.busy();
    }

    /**
     * Сторож зависшего трекера.
     *
     * <p>Бывает так, что граф поднимается, принимает кадры и молчит: например, драйвер GPU отвечает
     * ошибкой на каждый кадр. Снаружи это выглядит как "лицо не найдено" и демо-режим, хотя камера
     * работает. Поэтому если трекер молчит несколько секунд при живом потоке кадров, он
     * пересобирается заново - с ML Kit или на CPU, что окажется рабочим.</p>
     */
    private void checkTrackerHealth(long now) {
        if (tracker == null || tracker.resultsSeen() < 0L) {
            return;
        }
        final long seen = tracker.resultsSeen();
        if (seen != lastResultsSeen) {
            lastResultsSeen = seen;
            lastResultsChangeMs = now;
            return;
        }
        final long since = now - Math.max(Math.max(lastResultsChangeMs, trackerOpenedMs), startedAtMs);
        // Кадры должны идти: если камера встала, молчание трекера ничего не значит.
        final boolean framesFlowing = now - lastAnalysisMs < 1500;
        if (framesFlowing && since > TRACKER_SILENCE_MS && analyzedFrames > 3) {
            addDiagnostic("трекер молчит " + (since / 1000) + " с, перезапускаю");
            EchidnaLog.w("TRACK", "трекер не отвечает " + since + " мс, перезапуск");
            lastResultsChangeMs = now;
            openTracker();
        }
    }

    /**
     * Кадр для окошка предпросмотра.
     *
     * <p>Копия снимается с буфера камеры, потому что камера пишет в него следующий кадр: если
     * отдать рендеру сам буфер, на телефоне видно, как картинка дёргается и расползается. Копии
     * идут по кругу, и каждая живёт, пока рендер не загрузит её в текстуру.</p>
     */
    private void publishPreview(Bitmap frame) {
        final CameraPreviewListener listener = previewListener;
        if (listener == null) {
            return;
        }
        try {
            final Bitmap slot = previewSlotFor(frame);
            if (slot == null) {
                return;
            }
            final android.graphics.Canvas canvas = new android.graphics.Canvas(slot);
            canvas.drawBitmap(frame, 0f, 0f, null);
            final int serial = ++previewSerial;
            listener.onPreview(slot, serial);
        } catch (Throwable error) {
            EchidnaLog.w("TRACK", "кадр окошка не подготовлен: " + error);
        }
    }

    private Bitmap previewSlotFor(Bitmap frame) {
        for (int i = 0; i < PREVIEW_SLOTS; i++) {
            final int index = (previewCursor + i) % PREVIEW_SLOTS;
            final Bitmap candidate = previewSlots[index];
            if (candidate == null || candidate.getWidth() != frame.getWidth()
                    || candidate.getHeight() != frame.getHeight()) {
                continue;
            }
            previewCursor = (index + 1) % PREVIEW_SLOTS;
            return candidate;
        }
        final Bitmap created = Bitmap.createBitmap(frame.getWidth(), frame.getHeight(),
                Bitmap.Config.ARGB_8888);
        final int index = previewCursor;
        previewSlots[index] = created;
        previewCursor = (index + 1) % PREVIEW_SLOTS;
        return created;
    }

    /** Копия кадра в переиспользуемый буфер: без выделения памяти на каждый кадр. */
    private static Bitmap copyInto(Bitmap target, Bitmap source) {
        Bitmap out = target;
        if (out == null || out.getWidth() != source.getWidth()
                || out.getHeight() != source.getHeight()) {
            out = Bitmap.createBitmap(source.getWidth(), source.getHeight(),
                    Bitmap.Config.ARGB_8888);
        }
        final android.graphics.Canvas canvas = new android.graphics.Canvas(out);
        canvas.drawBitmap(source, 0f, 0f, null);
        return out;
    }

    private void syntheticLoop() {
        while (running && syntheticOnly) {
            try {
                final FaceSignals signals = tracker == null
                        ? null
                        : tracker.analyze(new FaceTracker.Frame(null, 0, null),
                                SystemClock.elapsedRealtime());
                if (signals != null) {
                    analyzedFrames++;
                    if (!signals.found) {
                        analyzedEmpty++;
                    }
                    lastSignals = signals;
                    if (signalsListener != null) {
                        signalsListener.onSignals(signals);
                    }
                }
                final long seconds = Math.max(1, (SystemClock.elapsedRealtime() - startedAtMs) / 1000);
                framesPerSecond = (int) (analyzedFrames / seconds);
                Thread.sleep(33);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                EchidnaLog.w("TRACK", "синтетический цикл: " + t);
            }
        }
    }

    /** Called once per second from the renderer to keep the rate display honest. */
    public void tickRate() {
        final long seconds = Math.max(1, (SystemClock.elapsedRealtime() - startedAtMs) / 1000);
        framesPerSecond = (int) (analyzedFrames / seconds);
    }

    /** Snapshot of the current signals, JSON encoded, for the self test report. */
    public String signalsJson() {
        final FaceSignals s = lastSignals;
        if (s == null) {
            return "{}";
        }
        return Json.object()
                .put("found", s.found)
                .put("yaw", round(s.yaw))
                .put("pitch", round(s.pitch))
                .put("roll", round(s.roll))
                .put("eyeLeft", round(s.eyeLeft))
                .put("eyeRight", round(s.eyeRight))
                .put("mouth", round(s.mouthOpen))
                .put("smile", round(s.smile))
                .put("blendshapes", s.blendshapes)
                .put("body", s.body)
                .put("bodyYaw", round(s.bodyYaw))
                .put("bodyRoll", round(s.bodyRoll))
                .put("bodyLift", round(s.bodyLift))
                .put("handUp", round(s.handUp))
                .put("poseOnly", s.poseOnly)
                .toString();
    }

    private static float round(float value) {
        return Math.round(value * 100.0f) / 100.0f;
    }

    @Override
    public String toString() {
        return "TrackingHub{" + report() + "}";
    }
}
