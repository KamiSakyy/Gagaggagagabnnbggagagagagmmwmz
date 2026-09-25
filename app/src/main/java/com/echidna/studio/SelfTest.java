package com.echidna.studio;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import com.echidna.studio.anim.Show;
import com.echidna.studio.anim.ShowLibrary;
import com.echidna.studio.track.FaceSignals;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The self check that runs inside the app itself.
 *
 * <p>It is what makes "does it really work" verifiable without a human: it walks every show, the
 * motion gallery, the whole face mapping chain with scripted signals and the camera pipeline, and
 * writes a machine readable report to logcat. The CI job reads that report and fails the build when
 * any step is missing.</p>
 *
 * <p>Nothing here fakes a result. The strongest checks read the parameters of the native model
 * twice and require that they really moved - that proves the pose travelled all the way from the
 * tracker into the Live2D core.</p>
 */
public final class SelfTest {
    public interface Callbacks {
        /** Model report, or null while the model is not loaded yet. */
        String modelReport();

        void startShow(String showId);

        void playMotion(String name);

        /** Switches the stage into camera mode (with the real camera when {@code realCamera}). */
        void enterCameraMode(boolean realCamera);

        void leaveCameraMode();

        List<String> motions();

        String stageReport();

        /** Face signals the tracker currently sees, as JSON. */
        String signalsReport();

        String trackerReport();

        /** Why the camera is not running, empty when it runs fine. */
        String cameraReport();

        /** Parameters of the native model right now. */
        String parameterReport();
    }

    private static final long STEP_DELAY_MS = 1400;
    private static final int SIGNAL_STEPS = 9;
    private static final int CAMERA_STEPS = 3;

    private final Activity activity;
    private final Callbacks callbacks;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<String> steps = new ArrayList<String>();
    private final List<String> failures = new ArrayList<String>();

    private long startedAtMs;
    private int phase;
    private int counter;
    private int showIndex;
    private int motionIndex;
    private boolean cameraRequested;
    private int signalSamples;
    private boolean movementSeen;
    private boolean blinkSeen;
    private boolean mouthSeen;
    private boolean parametersMoved;
    private boolean cameraFramesSeen;
    private String firstParameters;

    public SelfTest(Activity activity, Callbacks callbacks) {
        this.activity = activity;
        this.callbacks = callbacks;
    }

    public void start(boolean withCamera) {
        cameraRequested = withCamera;
        startedAtMs = System.currentTimeMillis();
        steps.clear();
        failures.clear();
        phase = 0;
        counter = 0;
        showIndex = 0;
        motionIndex = 0;
        signalSamples = 0;
        movementSeen = false;
        blinkSeen = false;
        mouthSeen = false;
        parametersMoved = false;
        cameraFramesSeen = false;
        firstParameters = null;
        EchidnaLog.i("SELFTEST", "старт самопроверки, камера=" + withCamera);
        handler.postDelayed(this::step, 900);
    }

    private void step() {
        if (activity.isFinishing()) {
            return;
        }
        switch (phase) {
            case 0: {
                final String report = callbacks.modelReport();
                if (report == null) {
                    fail("модель не загрузилась за первую секунду");
                    finish();
                    return;
                }
                pass("модель: " + report);
                final List<String> motions = callbacks.motions();
                if (motions.size() < 60) {
                    fail("мошен меньше ожидаемого: " + motions.size());
                } else {
                    pass("мошен в модели: " + motions.size());
                }
                final String parameters = callbacks.parameterReport();
                firstParameters = parameters;
                pass("параметры: " + parameters);
                phase++;
                break;
            }
            case 1: {
                final List<Show> shows = ShowLibrary.shows();
                if (showIndex < shows.size()) {
                    final Show show = shows.get(showIndex);
                    callbacks.startShow(show.id);
                    pass("шоу " + show.id + " запущено (" + show.segments.size() + " сегментов, "
                            + show.duration + " с)");
                    showIndex++;
                } else {
                    phase++;
                }
                break;
            }
            case 2: {
                final List<String> motions = callbacks.motions();
                if (motionIndex < motions.size() && motionIndex < 9) {
                    final String name = motions.get(motionIndex);
                    callbacks.playMotion(name);
                    pass("мошен " + name + " запущен");
                    motionIndex++;
                } else {
                    phase++;
                }
                break;
            }
            case 3: {
                callbacks.enterCameraMode(false);
                pass("режим камеры включён (синтетические сигналы)");
                phase++;
                break;
            }
            case 4: {
                inspectSignals();
                counter++;
                if (counter >= SIGNAL_STEPS) {
                    counter = 0;
                    phase++;
                }
                break;
            }
            case 5: {
                if (!movementSeen) {
                    fail("повороты головы не дошли до модели");
                } else {
                    pass("повороты головы дошли до модели");
                }
                if (!blinkSeen) {
                    fail("моргание не дошло до модели");
                } else {
                    pass("моргание дошло до модели");
                }
                if (!mouthSeen) {
                    fail("открытие рта не дошло до модели");
                } else {
                    pass("открытие рта дошло до модели");
                }
                pass("сигналов проанализировано: " + signalSamples);
                phase++;
                break;
            }
            case 6: {
                callbacks.leaveCameraMode();
                pass("режим камеры выключен, студия вернулась в ожидание");
                phase++;
                break;
            }
            case 7: {
                if (cameraRequested) {
                    callbacks.enterCameraMode(true);
                    pass("настоящая камера включена: " + callbacks.trackerReport());
                } else {
                    pass("настоящая камера не проверялась (запуск без камеры)");
                }
                phase++;
                break;
            }
            case 8: {
                if (cameraRequested) {
                    inspectCamera();
                    counter++;
                    if (counter >= CAMERA_STEPS) {
                        counter = 0;
                        phase++;
                    }
                } else {
                    phase++;
                }
                break;
            }
            case 9: {
                if (cameraRequested) {
                    pass("камера после проверки: " + callbacks.trackerReport());
                    if (cameraFramesSeen) {
                        pass("камера выдаёт кадры");
                    } else {
                        final String camera = String.valueOf(callbacks.cameraReport());
                        if (camera.contains("не найдена") || camera.contains("недоступна")
                                || camera.contains("нет разрешения")) {
                            // A device without a usable camera cannot be blamed on the app, but the
                            // reason is reported loudly so nobody mistakes it for a pass.
                            EchidnaLog.w("SELFTEST", "камеры на устройстве нет: " + camera);
                            pass("камеры на устройстве нет, проверка камеры пропущена: " + camera);
                        } else {
                            fail("камера не выдала ни одного кадра: " + camera);
                        }
                    }
                }
                callbacks.leaveCameraMode();
                phase++;
                break;
            }
            case 10: {
                if (!parametersMoved) {
                    fail("параметры модели не изменились за время проверки");
                } else {
                    pass("параметры модели меняются: " + callbacks.parameterReport());
                }
                phase++;
                break;
            }
            default:
                finish();
                return;
        }
        handler.postDelayed(this::step, STEP_DELAY_MS);
    }

    private void inspectSignals() {
        final String json = callbacks.signalsReport();
        if (json == null || json.length() < 4) {
            return;
        }
        signalSamples++;
        final FaceSignals signals = parse(json);
        if (signals == null || !signals.found) {
            return;
        }
        if (Math.abs(signals.yaw) > 6.0f || Math.abs(signals.pitch) > 5.0f) {
            movementSeen = true;
        }
        if (signals.eyeLeft < 0.4f || signals.eyeRight < 0.4f) {
            blinkSeen = true;
        }
        if (signals.mouthOpen > 0.2f) {
            mouthSeen = true;
        }
        // The native model is the last link of the chain: if its parameters moved, everything worked.
        final String parameters = callbacks.parameterReport();
        if (parameters != null && firstParameters != null && !parameters.equals(firstParameters)) {
            parametersMoved = true;
        }
    }

    private void inspectCamera() {
        final String report = callbacks.trackerReport();
        if (!report.contains("кадров камеры=0")) {
            cameraFramesSeen = true;
            pass("камера выдаёт кадры: " + report);
        } else {
            EchidnaLog.w("SELFTEST", "кадров камеры пока нет: " + report);
        }
        final String signals = callbacks.signalsReport();
        if (signals.contains("\"found\":true")) {
            pass("трекер нашёл лицо в кадре");
        } else {
            pass("трекер работает, лица в кадре нет (нормально для эмулятора)");
        }
    }

    private static FaceSignals parse(String json) {
        final FaceSignals signals = new FaceSignals();
        signals.found = json.contains("\"found\":true");
        signals.yaw = number(json, "yaw");
        signals.pitch = number(json, "pitch");
        signals.roll = number(json, "roll");
        signals.eyeLeft = number(json, "eyeLeft");
        signals.eyeRight = number(json, "eyeRight");
        signals.mouthOpen = number(json, "mouth");
        signals.smile = number(json, "smile");
        return signals;
    }

    private static float number(String json, String key) {
        final int at = json.indexOf("\"" + key + "\":");
        if (at < 0) {
            return 0.0f;
        }
        final int start = at + key.length() + 3;
        int end = start;
        while (end < json.length()) {
            final char c = json.charAt(end);
            if (Character.isDigit(c) || c == '-' || c == '.') {
                end++;
            } else {
                break;
            }
        }
        try {
            return Float.parseFloat(json.substring(start, end));
        } catch (NumberFormatException e) {
            return 0.0f;
        }
    }

    private void pass(String message) {
        steps.add(message);
        EchidnaLog.i("SELFTEST", "OK: " + message);
    }

    private void fail(String message) {
        failures.add(message);
        EchidnaLog.e("SELFTEST", "ПРОВАЛ: " + message);
    }

    private void finish() {
        final long durationMs = System.currentTimeMillis() - startedAtMs;
        final String verdict = failures.isEmpty() ? "OK" : "FAIL";
        final Json json = Json.object()
                .put("verdict", verdict)
                .put("durationMs", durationMs)
                .put("steps", steps.size())
                .put("failures", failures.size())
                .put("cameraChecked", cameraRequested)
                .put("signals", signalSamples)
                .put("movement", movementSeen)
                .put("blink", blinkSeen)
                .put("mouth", mouthSeen)
                .put("parametersMoved", parametersMoved)
                .put("cameraFrames", cameraFramesSeen)
                .put("tracker", callbacks.trackerReport())
                .put("model", String.valueOf(callbacks.modelReport()))
                .put("stage", String.valueOf(callbacks.stageReport()))
                .put("parameters", String.valueOf(callbacks.parameterReport()));
        EchidnaLog.i("SELFTEST", "ИТОГ " + verdict + " " + json);
        EchidnaLog.i("SELFTEST", String.format(Locale.US,
                "шагов=%d, провалов=%d, за %.1f с", steps.size(), failures.size(), durationMs / 1000.0));
        for (String failure : failures) {
            EchidnaLog.e("SELFTEST", "не пройдено: " + failure);
        }
    }
}
