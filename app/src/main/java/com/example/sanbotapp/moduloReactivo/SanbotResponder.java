package com.example.sanbotapp.moduloReactivo;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.sanbotapp.robotControl.HeadControl;
import com.example.sanbotapp.robotControl.SpeechControl;
import com.example.sanbotapp.robotControl.WheelControl;
import com.qihancloud.opensdk.function.unit.HardWareManager;
import com.qihancloud.opensdk.function.unit.interfaces.hardware.PIRListener;
import com.qihancloud.opensdk.function.unit.interfaces.hardware.VoiceLocateListener;

public class SanbotResponder {
    private HardWareManager hardWareManager;
    private SpeechControl speechControl;
    private HeadControl headControl;
    private WheelControl wheelControl;

    private final Handler handler = new Handler(Looper.getMainLooper());

    public SanbotResponder(HardWareManager hardWareManager, HeadControl headControl, SpeechControl speechControl, WheelControl wheelControl) {
        this.hardWareManager = hardWareManager;
        this.speechControl = speechControl;
        this.headControl = headControl;
        this.wheelControl = wheelControl;
    }

    private static final long TIEMPO_ENTRE_RESPUESTAS_MS = 60_000; // 10 segundos
    private long ultimoTiempoRespuesta = 0;

    /**
     *  Localización de sonido: Girar hacia donde viene el ruido
     */
    public void detectarFuenteDeSonido() {
        hardWareManager.setOnHareWareListener(new VoiceLocateListener() {
            @Override
            public void voiceLocateResult(int angle) {
                long tiempoActual = System.currentTimeMillis();
                if (tiempoActual - ultimoTiempoRespuesta < TIEMPO_ENTRE_RESPUESTAS_MS) {
                    return; // No responder si no ha pasado suficiente tiempo
                }

                // Ejecutar la respuesta en un nuevo hilo
                new Thread(() -> {
                    // Ángulo detectado según sentido de las agujas del reloj
                    Log.d("SanbotResponder", "Sonido detectado en ángulo: " + angle + "°");
                    int headAngle = convertirAnguloCabeza(angle);

                    if (headAngle != -1) {
                        headControl.girarCabeza(headAngle);
                        // Solo decir la frase si no está hablando
                        if (!speechControl.isRobotHablando()) {
                            String[] frases = {
                                    "¿Qué fue eso?",
                                    "¡Creo que oí algo!",
                                    "Interesante sonido por aquí.",
                                    "¡Hey! ¿Hay alguien ahí?",
                                    "¡Escuché algo por esta zona!"
                            };
                            speechControl.hablar(frases[(int)(Math.random() * frases.length)]);
                        }

                        // Tras hablar recuperar posicion de la cabeza
                        handler.postDelayed(() -> {
                            headControl.girarCabeza(90);
                        }, 5000);

                    } else {
                        if (!speechControl.isRobotHablando()) {
                            String[] frasesDetras = {
                                    "¡Algo sonó detrás de mí!",
                                    "¿Eh? Eso vino de atrás...",
                                    "¡Oh! Hay algo detrás.",
                                    "¡No me asustes así!",
                                    "¡Eso no sonó nada bien!"
                            };
                            speechControl.hablar(frasesDetras[(int)(Math.random() * frasesDetras.length)]);
                        }

                    }

                    // Actualizar el tiempo de la última respuesta
                    ultimoTiempoRespuesta = System.currentTimeMillis();
                }).start();
            }
        });
    }


    private static final long TIEMPO_ENTRE_RESPUESTAS_PIR_MS = 30_000; // 15 segundos
    private long ultimoTiempoPIR = 0;
    /**
     *  Detección PIR: Reaccionar si alguien se acerca
     */
    public void detectarMovimientoPIR() {
        hardWareManager.setOnHareWareListener(new PIRListener() {
            @Override
            public void onPIRCheckResult(boolean isChecked, int part) {
                long tiempoActual = System.currentTimeMillis();
                if (!isChecked || (tiempoActual - ultimoTiempoPIR < TIEMPO_ENTRE_RESPUESTAS_PIR_MS)) {
                    return; // No responder si no ha pasado suficiente tiempo o si no hay movimiento
                }

                ultimoTiempoPIR = tiempoActual; // Actualizar antes de la respuesta

                handler.post(() -> {
                    if (part == 1) {
                        if(!speechControl.isRobotHablando()){
                            // Parte frontal
                            String[] respuestasFrente = {
                                    "¡Hola! Bienvenido.",
                                    "¡Hola! Toma asiento y disfruta de la presentación.",
                                    "Qué bueno verte por aquí. Adelante, ponte cómodo."
                            };
                            int randomIndex = (int) (Math.random() * respuestasFrente.length);
                            speechControl.hablar(respuestasFrente[randomIndex]);
                        }

                    } else {
                        if(!speechControl.isRobotHablando()){
                            // Parte trasera
                            wheelControl.controlBasicoRuedas(WheelControl.AccionesRuedas.DERECHA, 180);

                            String[] frasesDetras = {
                                    "Me asustaste, no te había visto.",
                                    "¿Vienes por detrás? ¡Qué sigiloso!",
                                    "¡Ups! Estabas justo detrás de mí.",
                                    "¡Ah! Debería tener ojos en la espalda.",
                                    "Hola, ¿me estabas espiando?",
                            };
                            int randomIndex = (int) (Math.random() * frasesDetras.length);

                            // Espera un momento antes de hablar y luego girar de nuevo
                            handler.postDelayed(() -> {
                                speechControl.hablar(frasesDetras[randomIndex]);

                                // Espera otro momento antes de volver a girar
                                handler.postDelayed(() -> {
                                    wheelControl.controlBasicoRuedas(WheelControl.AccionesRuedas.IZQUIERDA, 180);
                                }, 3000); // 3 segundos después de hablar

                            }, 1000); // 1 segundo después de girar
                        }
                    }
                });
            }
        });
    }


    /**
     * Desactivar detección de sonido y movimiento PIR - UTILIZAR DESDE LA PRESETACIÓN SI MOLESTA SU USO
     */
    public void desactivarDeteccion() {
        hardWareManager.setOnHareWareListener(null);
    }


    private int convertirAnguloCabeza(int angle) {
        // Angulo cabeza de 0 (izquierda) a 180 (derecha)
        // Movimiento agujas del reloj por lo que 90º seria 180º en absoluto (derecha), y 270º seria 0º en absoluto (izquierda)

        if (angle >= 270) { // 270 = 0º, 90 = 180º/ 270 -270 = 0 / 270 - 90 = 180 / 360 -270 = 90
            return angle - 270;
        } else if (angle <= 90){
            return 90 + angle;
        }else {
            // Si angulo > 90 && angulo < 270
            return -1;
        }

    }

}

