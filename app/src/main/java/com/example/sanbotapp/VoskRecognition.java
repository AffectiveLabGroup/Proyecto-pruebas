package com.example.sanbotapp;

import android.content.Context;
import android.content.res.AssetManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.*;

public class VoskRecognition {

    private static final String TAG = "VoskRecognition";
    private static final int SAMPLE_RATE = 16000;

    private AudioRecord recorder;
    private boolean isRecording = false;

    // Inicia el reconocimiento en tiempo real
    public void startRecognition(Context context, VoskListener listener) {
        new Thread(() -> {
            try {
                // Copiar modelo si hace falta
                File modelPath = new File(context.getFilesDir(), "vosk-model-es");
                if (!modelPath.exists()) {
                    copyAssets(context, "model-es", modelPath);
                }

                // Inicializar modelo y reconocedor
                Model model = new Model(modelPath.getAbsolutePath());
                Recognizer recognizer = new Recognizer(model, SAMPLE_RATE);

                int bufferSize = AudioRecord.getMinBufferSize(
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                );

                recorder = new AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize
                );

                byte[] buffer = new byte[bufferSize];
                recorder.startRecording();
                isRecording = true;

                Log.d(TAG, "🎙️ Iniciando reconocimiento...");

                while (isRecording) {
                    int bytesRead = recorder.read(buffer, 0, buffer.length);
                    //Log.d(TAG, "Bytes leídos del micrófono: " + bytesRead);

                    if (bytesRead > 0) {
                        if (recognizer.acceptWaveForm(buffer, bytesRead)) {
                            String result = recognizer.getResult();
                            listener.onResult(result);
                        } else {
                            String partial = recognizer.getPartialResult();
                            listener.onPartialResult(partial);
                        }
                    }
                }

                // Detener y liberar recursos
                recorder.stop();
                recorder.release();
                recognizer.close();
                model.close();

                Log.d(TAG, "🛑 Reconocimiento detenido.");

            } catch (IOException e) {
                listener.onError(e.getMessage());
            }
        }).start();
    }

    // Detiene el reconocimiento
    public void stopRecognition() {
        isRecording = false;
    }

    public void reconocer(Context context, VoskListener listener, byte[] audioData) {
        new Thread(() -> {
            try {
                // Copiar modelo si hace falta
                File modelPath = new File(context.getFilesDir(), "vosk-model-es");
                if (!modelPath.exists()) {
                    copyAssets(context, "model-es", modelPath);
                }

                // Inicializar modelo y reconocedor
                Model model = new Model(modelPath.getAbsolutePath());
                Recognizer recognizer = new Recognizer(model, SAMPLE_RATE);

                /*int bufferSize = AudioRecord.getMinBufferSize(
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                );*/

                recorder = new AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        audioData.length
                );

                //byte[] buffer = new byte[bufferSize];
                recorder.startRecording();
                isRecording = true;

                Log.d(TAG, "🎙️ Iniciando reconocimiento...");

                while (isRecording) {
                    int bytesRead = recorder.read(audioData, 0, audioData.length);
                    //Log.d(TAG, "Bytes leídos del micrófono: " + bytesRead);

                    if (bytesRead > 0) {
                        if (recognizer.acceptWaveForm(audioData, bytesRead)) {
                            String result = recognizer.getResult();
                            listener.onResult(result);
                        } else {
                            String partial = recognizer.getPartialResult();
                            listener.onPartialResult(partial);
                        }
                    }
                }

                // Detener y liberar recursos
                recorder.stop();
                recorder.release();
                recognizer.close();
                model.close();

                Log.d(TAG, "🛑 Reconocimiento detenido.");

            } catch (IOException e) {
                listener.onError(e.getMessage());
            }
        }).start();
    }

    // Copia recursiva del modelo
    private static void copyAssets(Context context, String assetDir, File outDir) throws IOException {
        String[] files = context.getAssets().list(assetDir);

        if (files == null || files.length == 0) {
            throw new IOException("No se encontraron archivos en assets/" + assetDir);
        }

        if (!outDir.exists()) {
            outDir.mkdirs();
        }

        for (String fileName : files) {
            String assetPath = assetDir + "/" + fileName;
            File outFile = new File(outDir, fileName);

            String[] subFiles = context.getAssets().list(assetPath);
            if (subFiles != null && subFiles.length > 0) {
                copyAssets(context, assetPath, outFile);
            } else {
                try (
                        InputStream in = context.getAssets().open(assetPath);
                        OutputStream out = new java.io.FileOutputStream(outFile)
                ) {
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }
            }
        }
    }

    // Interfaz de callback para recibir resultados
    public interface VoskListener {
        void onResult(String result);
        void onPartialResult(String partial);
        void onError(String error);
    }
}
