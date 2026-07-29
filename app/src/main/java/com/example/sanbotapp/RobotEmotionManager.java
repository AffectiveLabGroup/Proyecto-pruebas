package com.example.sanbotapp;

import android.app.Activity;


import com.qihancloud.opensdk.function.beans.EmotionsType;
import com.qihancloud.opensdk.function.beans.LED;
import com.qihancloud.opensdk.function.beans.SpeakOption;
import com.qihancloud.opensdk.function.beans.handmotion.AbsoluteAngleHandMotion;
import com.qihancloud.opensdk.function.beans.headmotion.AbsoluteAngleHeadMotion;
import com.qihancloud.opensdk.function.beans.headmotion.RelativeAngleHeadMotion;
import com.qihancloud.opensdk.function.beans.wheelmotion.RelativeAngleWheelMotion;
import com.qihancloud.opensdk.function.unit.HandMotionManager;
import com.qihancloud.opensdk.function.unit.HeadMotionManager;
import com.qihancloud.opensdk.function.unit.SpeechManager;
import com.qihancloud.opensdk.function.unit.SystemManager;
import com.qihancloud.opensdk.function.unit.WheelMotionManager;
import com.qihancloud.opensdk.function.unit.HardWareManager;

import java.util.Random;

/**
 * RobotEmotionManager
 *
 * Clase auxiliar para lanzar emociones del robot de forma sencilla.
 * Encapsula toda la lógica de LEDs, movimientos y frases.
 *
 * Uso básico:
 *   RobotEmotionManager emotions = new RobotEmotionManager(
 *       this, systemManager, hardwareManager,
 *       speechManager, headMotionManager,
 *       handMotionManager, wheelMotionManager
 *   );
 *   emotions.mostrarFeliz();
 *   emotions.mostrarTriste();
 *   emotions.mostrarFeliz(() -> Log.d("Robot", "Emoción terminada"));
 */
public class RobotEmotionManager {

    // ── Interfaz de callback ──────────────────────────────────────────────────

    /** Se invoca en el hilo UI cuando la emoción ha terminado completamente. */
    public interface OnEmotionFinishedListener {
        void onFinished();
    }

    // ── Dependencias ──────────────────────────────────────────────────────────

    private final Activity           activity;
    private final SystemManager systemManager;
    private final HardWareManager  hardwareManager;
    private final SpeechManager speechManager;
    private final HeadMotionManager headMotionManager;
    private final HandMotionManager handMotionManager;
    private final WheelMotionManager wheelMotionManager;

    private final SpeakOption speakOption;
    private final Random      random = new Random();

    /** Evita lanzar dos emociones a la vez. */
    private volatile boolean isProcessing = false;


    public RobotEmotionManager(Activity activity,
                               SystemManager systemManager,
                               HardWareManager hardwareManager,
                               SpeechManager speechManager,
                               HeadMotionManager headMotionManager,
                               HandMotionManager handMotionManager,
                               WheelMotionManager wheelMotionManager) {
        this.activity           = activity;
        this.systemManager      = systemManager;
        this.hardwareManager    = hardwareManager;
        this.speechManager      = speechManager;
        this.headMotionManager  = headMotionManager;
        this.handMotionManager  = handMotionManager;
        this.wheelMotionManager = wheelMotionManager;

        speakOption = new SpeakOption();
        speakOption.setSpeed(50);
        speakOption.setIntonation(50);
    }

    public void mostrarFeliz()                               { mostrarFeliz(null); }
    public void mostrarTriste()                              { mostrarTriste(null); }
    public void mostrarEnfadado()                            { mostrarEnfadado(null); }
    public void mostrarSonrojado()                           { mostrarSonrojado(null); }
    public void mostrarPreocupado()                          { mostrarPreocupado(null); }
    public void mostrarEnamorado()                           { mostrarEnamorado(null); }
    public void mostrarCurioso()                             { mostrarCurioso(null); }
    public void mostrarEntusiasmado()                        { mostrarEntusiasmado(null); }

    // Versiones con callback ──────────────────────────────────────────────────

    /** 😊 Feliz — LEDs amarillos, expresión SMILE */
    public void mostrarFeliz(OnEmotionFinishedListener callback) {
        String[] frases = {
                "Hoy estoy muy feliz, ¡Gracias por jugar conmigo!",
                "Estoy contenta de que estés aquí",
                "Estoy muy feliz de verte, espero que tú también lo estés"
        };
        lanzarEmocion(callback, () -> {
            systemManager.showEmotion(EmotionsType.SMILE);
            hardwareManager.setLED(new LED(LED.PART_ALL, LED.MODE_YELLOW));
            hablar(frases);
            dormir(5000);
            apagarLuces();
        });
    }

    /** 😢 Triste — LEDs azules, expresión CRY, cabeza hacia abajo */
    public void mostrarTriste(OnEmotionFinishedListener callback) {
        String[] frases = {
                "A veces me pongo muy triste, y no puedo parar de llorar",
                "Cuando me pongo triste, no puedo ocultarlo",
                "Me siento muy triste, no sé cómo parar de llorar"
        };
        lanzarEmocion(callback, () -> {
            systemManager.showEmotion(EmotionsType.CRY);
            hardwareManager.setLED(new LED(LED.PART_ALL, LED.MODE_BLUE));
            hablar(frases);
            headMotionManager.doAbsoluteAngleMotion(
                    new AbsoluteAngleHeadMotion(AbsoluteAngleHeadMotion.ACTION_VERTICAL, 7));
            dormir(5000);
            apagarLuces();
            headMotionManager.doAbsoluteAngleMotion(
                    new AbsoluteAngleHeadMotion(AbsoluteAngleHeadMotion.ACTION_VERTICAL, 30));
        });
    }

    /** 😠 Enfadado — LEDs rojos, expresión ANGRY, brazos agitados */
    public void mostrarEnfadado(OnEmotionFinishedListener callback) {
        String[] frases = {
                "Cuando estoy enfadada me pongo muy nerviosa",
                "No me gusta estar enfadada, pero a veces no puedo evitarlo",
                "No puedo evitar enfadarme cuando sucede una injusticia"
        };
        lanzarEmocion(callback, () -> {
            systemManager.showEmotion(EmotionsType.ANGRY);
            hardwareManager.setLED(new LED(LED.PART_ALL, LED.MODE_RED));
            hablar(frases);
            handMotionManager.doAbsoluteAngleMotion(
                    new AbsoluteAngleHandMotion(AbsoluteAngleHandMotion.PART_BOTH, 20, 0));
            dormir(2000);
            handMotionManager.doAbsoluteAngleMotion(
                    new AbsoluteAngleHandMotion(AbsoluteAngleHandMotion.PART_BOTH, 20, 180));
            dormir(2000);
            apagarLuces();
        });
    }

    /** 😳 Sonrojado / Tímido — LEDs morados, expresión SHY, cabeza hacia abajo-izquierda */
    public void mostrarSonrojado(OnEmotionFinishedListener callback) {
        String[] frases = {
                "Soy un poco tímida, disculpa si no siempre te respondo",
                "Me da vergüenza estar rodeada de tanta gente",
                "No puedo evitar sonrojarme cuando me miras fijamente"
        };
        lanzarEmocion(callback, () -> {
            systemManager.showEmotion(EmotionsType.SHY);
            hardwareManager.setLED(new LED(LED.PART_ALL, LED.MODE_PURPLE));
            hablar(frases);
            headMotionManager.doRelativeAngleMotion(
                    new RelativeAngleHeadMotion(RelativeAngleHeadMotion.ACTION_LEFTDOWN, 30));
            dormir(5000);
            headMotionManager.doRelativeAngleMotion(
                    new RelativeAngleHeadMotion(RelativeAngleHeadMotion.ACTION_RIGHTUP, 30));
            dormir(2000);
            apagarLuces();
        });
    }

    /** 😟 Preocupado — LEDs verdes, expresión GRIEVANCE */
    public void mostrarPreocupado(OnEmotionFinishedListener callback) {
        String[] frases = {
                "Estoy preocupada por ti, ¿Va todo bien?",
                "No puedo evitar preocuparme cuando algo no va bien",
                "Siempre me preocupa que algo malo pueda pasar"
        };
        lanzarEmocion(callback, () -> {
            systemManager.showEmotion(EmotionsType.GRIEVANCE);
            hardwareManager.setLED(new LED(LED.PART_ALL, LED.MODE_GREEN));
            hablar(frases);
            dormir(7000);
            apagarLuces();
        });
    }

    /** 😍 Enamorado — LEDs rosas, expresión KISS, brazos extendidos */
    public void mostrarEnamorado(OnEmotionFinishedListener callback) {
        String[] frases = {
                "Cuando estoy enamorada no puedo ocultarlo",
                "Me encanta estar enamorada, me siento muy feliz",
                "Cuando estoy enamorada se me nota mucho en los ojos"
        };
        lanzarEmocion(callback, () -> {
            systemManager.showEmotion(EmotionsType.KISS);
            hardwareManager.setLED(new LED(LED.PART_ALL, LED.MODE_PINK));
            hablar(frases);
            handMotionManager.doAbsoluteAngleMotion(
                    new AbsoluteAngleHandMotion(AbsoluteAngleHandMotion.PART_BOTH, 5, 20));
            dormir(5000);
            handMotionManager.doAbsoluteAngleMotion(
                    new AbsoluteAngleHandMotion(AbsoluteAngleHandMotion.PART_BOTH, 5, 180));
            dormir(2000);
            apagarLuces();
        });
    }

    /** 🤔 Curioso — LEDs blancos, expresión QUESTION, cabeza mirando a los lados */
    public void mostrarCurioso(OnEmotionFinishedListener callback) {
        String[] frases = {
                "Soy una robot muy curiosa, me pregunto qué pasará dentro de 50 años, ¿Seré más parecida a vosotros?",
                "No puedo evitar preguntarme qué pasará en el futuro, me intriga mucho",
                "Me encanta aprender cosas nuevas, soy muy curiosa"
        };
        lanzarEmocion(callback, () -> {
            systemManager.showEmotion(EmotionsType.QUESTION);
            hardwareManager.setLED(new LED(LED.PART_ALL, LED.MODE_WHITE));
            hablar(frases);
            headMotionManager.doRelativeAngleMotion(
                    new RelativeAngleHeadMotion(RelativeAngleHeadMotion.ACTION_LEFT, 30));
            dormir(3000);
            headMotionManager.doRelativeAngleMotion(
                    new RelativeAngleHeadMotion(RelativeAngleHeadMotion.ACTION_RIGHT, 60));
            dormir(3000);
            headMotionManager.doRelativeAngleMotion(
                    new RelativeAngleHeadMotion(RelativeAngleHeadMotion.ACTION_LEFT, 30));
            dormir(7000);
            apagarLuces();
        });
    }

    /** 🎉 Entusiasmado — LEDs aleatorios, expresión PRISE, brazos y giro */
    public void mostrarEntusiasmado(OnEmotionFinishedListener callback) {
        String[] frases = {
                "Estoy muy emocionada, me encanta estar aquí",
                "No puedo evitar emocionarme cuando algo me gusta mucho",
                "Me encanta estar aquí con vosotros, me siento muy feliz"
        };
        lanzarEmocion(callback, () -> {
            systemManager.showEmotion(EmotionsType.PRISE);
            hardwareManager.setLED(new LED(LED.PART_ALL, LED.MODE_FLICKER_RANDOM));
            hablar(frases);

            handMotionManager.doAbsoluteAngleMotion(
                    new AbsoluteAngleHandMotion(AbsoluteAngleHandMotion.PART_LEFT, 20, 180));
            handMotionManager.doAbsoluteAngleMotion(
                    new AbsoluteAngleHandMotion(AbsoluteAngleHandMotion.PART_RIGHT, 20, 20));
            dormir(2000);

            wheelMotionManager.doRelativeAngleMotion(
                    new RelativeAngleWheelMotion(RelativeAngleWheelMotion.TURN_LEFT, 5, 360));
            dormir(3000);

            handMotionManager.doAbsoluteAngleMotion(
                    new AbsoluteAngleHandMotion(AbsoluteAngleHandMotion.PART_LEFT, 20, 20));
            handMotionManager.doAbsoluteAngleMotion(
                    new AbsoluteAngleHandMotion(AbsoluteAngleHandMotion.PART_RIGHT, 20, 180));
            dormir(2000);

            handMotionManager.doAbsoluteAngleMotion(
                    new AbsoluteAngleHandMotion(AbsoluteAngleHandMotion.PART_LEFT, 20, 180));
            handMotionManager.doAbsoluteAngleMotion(
                    new AbsoluteAngleHandMotion(AbsoluteAngleHandMotion.PART_RIGHT, 20, 20));
            dormir(2000);

            // Resetear brazos
            handMotionManager.doAbsoluteAngleMotion(
                    new AbsoluteAngleHandMotion(AbsoluteAngleHandMotion.PART_BOTH, 20, 180));
            apagarLuces();
        });
    }

    // ── Utilidades públicas ───────────────────────────────────────────────────

    /** Devuelve true si hay una emoción en curso. */
    public boolean isProcessing() {
        return isProcessing;
    }

    // ── Métodos privados ──────────────────────────────────────────────────────

    /**
     * Ejecuta el bloque de emoción en un hilo secundario.
     * Protege contra ejecuciones simultáneas y llama al callback al terminar.
     */
    private void lanzarEmocion(OnEmotionFinishedListener callback, EmotionRunnable emocion) {
        if (isProcessing) return;
        isProcessing = true;

        new Thread(() -> {
            try {
                Thread.sleep(100); // pequeño retraso inicial
                emocion.run();
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                activity.runOnUiThread(() -> {
                    isProcessing = false;
                    if (callback != null) callback.onFinished();
                });
            }
        }).start();
    }

    /** Selecciona y habla una frase aleatoria del array. */
    private void hablar(String[] frases) {
        speechManager.startSpeak(frases[random.nextInt(frases.length)], speakOption);
    }

    /** Duerme el hilo ignorando InterruptedException internamente. */
    private void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /** Apaga todos los LEDs. */
    private void apagarLuces() {
        hardwareManager.setLED(new LED(LED.PART_ALL, LED.MODE_CLOSE));
    }

    /** Interfaz funcional interna para el bloque de emoción. */
    @FunctionalInterface
    private interface EmotionRunnable {
        void run() throws InterruptedException;
    }
}
