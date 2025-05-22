package com.example.sanbotapp.robotControl;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.sanbotapp.R;
import com.qihancloud.opensdk.beans.OperationResult;
import com.qihancloud.opensdk.function.beans.SpeakOption;
import com.qihancloud.opensdk.function.beans.speech.Grammar;
import com.qihancloud.opensdk.function.unit.SpeechManager;
import com.qihancloud.opensdk.function.unit.interfaces.speech.RecognizeListener;
import com.qihancloud.opensdk.function.unit.interfaces.speech.SpeakListener;
import com.qihancloud.opensdk.function.unit.interfaces.speech.WakenListener;

import java.util.concurrent.CountDownLatch;

public class SpeechControl {

    private SpeechManager speechManager;
    private static SpeakOption speakOption = new SpeakOption();
    private String cadenaReconocida;
    private boolean finHabla = false;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean escuchando = false;
    private long ultimoVolumenTimestamp = 0;

    private static final int SILENCIO_MAX_MS = 5000;

    // Constructor
    public SpeechControl(SpeechManager speechManager){
        this.speechManager = speechManager;
    }

    // Función que indica si el robot está hablando o no
    public boolean isRobotHablando(){
        OperationResult or = speechManager.isSpeaking();
        if (or.getResult().equals("1")) {
            return true;
        }
        else{
            return false;
        }
    }

    // Función que utiliza la síntesis de voz para pronunciar la
    // frase que se passa como parámetro con la entonación
    // y velocidad pasadas en el constructor.
    public void hablar(String respuesta){
        Log.d("hablar", "voy a hablar");
        Log.d("speakoption velocidad", String.valueOf(speakOption.getSpeed()));
        Log.d("speakoption entonacion", String.valueOf(speakOption.getIntonation()));
        speechManager.startSpeak(respuesta, speakOption);
    }

    // Función que detiene el habla del robot.
    public void pararHabla(){
        speechManager.stopSpeak();
    }

    // Función que obtiene el diálogo que el usuario ha dicho
    // desde que el robot se pone en modo WakeUp hasta que el
    // usuario deja de hablar
    public String modoEscucha(){
        cadenaReconocida=null;
        speechManager.doWakeUp();
        speechManager.setOnSpeechListener(new RecognizeListener() {
            @Override
            public boolean onRecognizeResult(Grammar grammar) {
                cadenaReconocida = grammar.getText();
                Log.d("pruebaRecognizeResult", cadenaReconocida);
                return true;
            }

            @Override
            public void onRecognizeVolume(int i) {

            }
        });
        while(cadenaReconocida==null || cadenaReconocida.isEmpty()){
        }
        return cadenaReconocida;
    }

    // Función que espera a que el robot termina de hablar
    public boolean heAcabado(){
        finHabla = false;
        speechManager.setOnSpeechListener(new SpeakListener(){
            // Acción que se ejecuta cuando el robot termina de hablar
            @Override
            public void onSpeakFinish() {
                // Si está en modo conversación automática
                Log.d("fin", "termine de hablar");
                finHabla = true;
            }

            @Override
            public void onSpeakProgress(int i) {
                // ...
            }
        });
        while(!finHabla){
        }
        return finHabla;
    }

    public boolean heAcabado2(){
        CountDownLatch latch = new CountDownLatch(1);

        speechManager.setOnSpeechListener(new SpeakListener() {
            @Override
            public void onSpeakFinish() {
                Log.d("fin", "Terminé de hablar");
                latch.countDown(); // Decrementar el latch cuando el habla ha terminado
            }

            @Override
            public void onSpeakProgress(int i) {
                // Implementar si es necesario
            }
        });

        try {
            latch.await(); // Esperar hasta que countDown() sea llamado
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // Restaurar el estado de interrupción
            Log.e("SpeechHandler", "El hilo fue interrumpido", e);
        }

        return true; // Después de que latch.countDown() sea llamado, sabemos que ha terminado
    }

    public void setVelocidadHabla(int velocidad){
        Log.d("speakoption velocidad", "cambiando velocidad a " + velocidad);
        speakOption.setSpeed(velocidad);
    }

    public void setEntonacionHabla(int entonacion){
        Log.d("speakoption entonacion", "cambiando entonacion a " + entonacion);
        speakOption.setIntonation(entonacion);
    }

    public int getVelocidadHabla(){
        return speakOption.getSpeed();
    }

    public int getEntonacionHabla(){
        return speakOption.getIntonation();
    }

    public void initListener() {
        //设置唤醒，休眠回调
        speechManager.setOnSpeechListener(new WakenListener() {
            @Override
            public void onWakeUp() {
                System.out.println("onWakeUp ----------------------------------------------");
            }

            @Override
            public void onSleep() {
                System.out.println("onSleep ----------------------------------------------");
            }
        });
        //语音识别回调

        speechManager.setOnSpeechListener(new RecognizeListener() {
            @Override
            public boolean onRecognizeResult(Grammar grammar) {
                //Log.i("reconocimiento：", "onRecognizeResult: "+grammar.getText());
                //只有在配置了RECOGNIZE_MODE为1，且返回为true的情况下，才会拦截
                // Si reconoce "hola" sanbot responde "hola"
                System.out.println(grammar.getText());
                return true;
            }

            @Override
            public void onRecognizeVolume(int i) {
                System.out.println("onRecognizeVolume ----------------------------------------------");
            }

        });

    }



    public void iniciar() {
        escuchando = true;
        iniciarEscucha();
    }

    public void detener() {
        escuchando = false;
        handler.removeCallbacksAndMessages(null);
    }

    private void iniciarEscucha() {
        if (!escuchando) return;

        Log.i("EscuchaPersistente", "Activando escucha...");
        speechManager.doWakeUp();

        final boolean[] recibido = {false};

        // Marca de silencio inicial
        ultimoVolumenTimestamp = System.currentTimeMillis();

        speechManager.setOnSpeechListener(new RecognizeListener() {
            @Override
            public boolean onRecognizeResult(Grammar grammar) {
                recibido[0] = true;
                String texto = grammar.getText();
                Log.i("EscuchaPersistente", "Texto reconocido: " + texto);

                procesarTexto(texto);

                // Reiniciar escucha tras pequeña pausa
                handler.postDelayed(() -> iniciarEscucha(), 1000);
                return true;
            }

            @Override
            public void onRecognizeVolume(int volume) {
                if (volume > 3) { // sensibilidad ajustable
                    ultimoVolumenTimestamp = System.currentTimeMillis();
                }
            }
        });

        // Verifica silencio prolongado para reactivar escucha
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!recibido[0]) {
                    long ahora = System.currentTimeMillis();
                    long sinVozMs = ahora - ultimoVolumenTimestamp;

                    if (sinVozMs >= SILENCIO_MAX_MS) {
                        Log.w("EscuchaPersistente", "Silencio prolongado detectado. Reactivando escucha.");
                        iniciarEscucha(); // solo reinicia si hubo silencio
                    } else {
                        // Aún hay voz, reintenta este chequeo más tarde
                        handler.postDelayed(this, 1000);
                    }
                }
            }
        }, SILENCIO_MAX_MS);
    }

    private void procesarTexto(String texto) {
        if (texto.contains("hola")) {
            speechManager.startSpeak("Hola, ¿cómo estás?");
        } else if (texto.contains("adiós")) {
            detener();
            speechManager.startSpeak("Hasta luego.");
            speechManager.doSleep();
        }
    }


}
