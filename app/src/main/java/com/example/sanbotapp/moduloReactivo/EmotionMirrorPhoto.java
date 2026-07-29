package com.example.sanbotapp.moduloReactivo;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

import com.example.sanbotapp.RobotEmotionManager;
import com.example.sanbotapp.robotControl.SpeechControl;
import com.qihancloud.opensdk.function.beans.StreamOption;
import com.qihancloud.opensdk.function.unit.MediaManager;
import com.qihancloud.opensdk.function.unit.SpeechManager;
import com.qihancloud.opensdk.function.unit.interfaces.media.MediaStreamListener;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;

/**
 * EmotionMirrorPhoto — Modo FOTO ÚNICA
 *
 * La cámara permanece encendida para mostrar la vista previa, pero NO envía
 * imágenes de forma continua. Cuando el usuario llama a {@link #tomarFotoYReaccionar()},
 * el robot:
 *   1. Avisa verbalmente con una cuenta atrás ("3, 2, 1...")
 *   2. Captura el fotograma
 *   3. Lo envía al endpoint POST /api/emotion/quick
 *   4. Dice una frase personalizada según la emoción detectada
 *   5. Cambia su propia expresión para imitarla
 *
 * Si la confianza es baja o no se detecta cara, el robot lo comunica
 * en lugar de reaccionar con una emoción incorrecta.
 *
 * Uso:
 *   EmotionMirrorPhoto photo = new EmotionMirrorPhoto(
 *       mediaManager, speechManager, tvMedia, emotionManager
 *   );
 *   // Cuando el usuario pulse el botón:
 *   photo.tomarFotoYReaccionar();
 */
public class EmotionMirrorPhoto implements TextureView.SurfaceTextureListener {

    private static final String TAG = "EmotionMirrorPhoto";

    // ── TODO: Reemplaza con la IP del ordenador de Miguel ────────────────────
    private static final String BASE_URL   = "http://192.168.50.177:8000";
    private static final String ENDPOINT   = BASE_URL + "/api/emotion/quick";
    private static final String SESSION_ID = "robot_photo";
    private static final int    MAX_FACES  = 1;
    // ─────────────────────────────────────────────────────────────────────────

    /** Umbral mínimo de confianza para reaccionar (0–1). */
    private static final double CONFIANZA_MINIMA = 0.50;

    /** Tiempo de espera tras la cuenta atrás antes de capturar (ms). */
    private static final long DELAY_FOTO_MS = 3500;

    private final MediaManager        mediaManager;
    private final SpeechControl       speechControl;
    private final RobotEmotionManager emotionManager;
    private final TextureView         tvMedia;

    private MediaCodec            mediaCodec;
    private MediaCodec.BufferInfo videoBufferInfo   = new MediaCodec.BufferInfo();
    private ByteBuffer[]          videoInputBuffers;
    private Surface               surface;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private boolean       ocupado   = false;

    /** Listener opcional para que la Activity reciba el resultado. */
    public interface OnResultadoListener {
        void onEmocionDetectada(String emocion, double confianza);
        void onSinCara();
        void onError(String motivo);
    }
    private OnResultadoListener resultadoListener;

    // ── Constructor ───────────────────────────────────────────────────────────

    public EmotionMirrorPhoto(MediaManager mediaManager,
                              SpeechManager speechManager,
                              TextureView tvMedia,
                              RobotEmotionManager emotionManager) {
        this.mediaManager   = mediaManager;
        this.speechControl  = new SpeechControl(speechManager);
        this.tvMedia        = tvMedia;
        this.emotionManager = emotionManager;
        this.tvMedia.setSurfaceTextureListener(this);
        iniciarListenerVideo();
    }

    // ── API pública ───────────────────────────────────────────────────────────

    public void setOnResultadoListener(OnResultadoListener listener) {
        this.resultadoListener = listener;
    }

    public boolean isOcupado() { return ocupado; }

    /**
     * Punto de entrada principal. Llama a esto cuando el usuario pulse el botón.
     * El robot avisa, captura y reacciona automáticamente.
     */
    public void tomarFotoYReaccionar() {
        if (ocupado) {
            Log.w(TAG, "Ya hay una captura en proceso, ignorando.");
            return;
        }
        ocupado = true;

        // Cuenta atrás hablada
        speechControl.hablar("Prepárate, voy a hacerte una foto en tres, dos, uno");

        // Esperamos a que el usuario se coloque y la frase termine
        uiHandler.postDelayed(() -> {
            Bitmap frame = capturarFrame();
            if (frame == null) {
                speechControl.hablar("No veo nada, asegúrate de estar frente a la cámara");
                ocupado = false;
                if (resultadoListener != null) resultadoListener.onError("frame nulo");
                return;
            }

            byte[] imageBytes = bitmapToJpegBytes(frame);
            if (imageBytes == null) {
                ocupado = false;
                if (resultadoListener != null) resultadoListener.onError("error convirtiendo imagen");
                return;
            }

            // Enviamos en hilo secundario
            new Thread(() -> {
                ResultadoEmocion resultado = enviarAlServidor(imageBytes);
                uiHandler.post(() -> {
                    ocupado = false;
                    procesarResultado(resultado);
                });
            }).start();

        }, DELAY_FOTO_MS);
    }

    // ── Procesado del resultado ───────────────────────────────────────────────

    private void procesarResultado(ResultadoEmocion resultado) {
        if (resultado == null) {
            speechControl.hablar("No he podido conectar con el servidor, inténtalo de nuevo");
            if (resultadoListener != null) resultadoListener.onError("error de red");
            return;
        }

        // Sin cara detectada
        if ("unknown".equals(resultado.emocion) || resultado.faces == 0) {
            speechControl.hablar("No he detectado ninguna cara. ¿Estás ahí? Mira a la cámara");
            if (resultadoListener != null) resultadoListener.onSinCara();
            return;
        }

        // Confianza insuficiente
        if (resultado.confianza < CONFIANZA_MINIMA) {
            speechControl.hablar("No estoy segura de tu expresión, ¿puedes repetirlo con más luz?");
            if (resultadoListener != null) resultadoListener.onError("confianza baja: " + resultado.confianza);
            return;
        }

        Log.i(TAG, "Emoción: " + resultado.emocion + " | Confianza: " + (int)(resultado.confianza * 100) + "%");

        // Reacción
        reaccionarAEmocion(resultado.emocion, resultado.confianza);
        if (resultadoListener != null) {
            resultadoListener.onEmocionDetectada(resultado.emocion, resultado.confianza);
        }
    }

    /**
     * El robot dice una frase contextual y luego imita la emoción.
     * Las etiquetas vienen del backend: happy, sad, anger, fear, disgust, surprise, neutral, unknown
     */
    private void reaccionarAEmocion(String emocion, double confianza) {
        int porcentaje = (int)(confianza * 100);

        switch (emocion.toLowerCase()) {

            case "happy":
                speechControl.hablar("¡Qué alegría! Hoy pareces muy feliz, eso me alegra mucho");
                uiHandler.postDelayed(() -> emotionManager.mostrarFeliz(), 3500);
                break;

            case "sad":
                speechControl.hablar("Vaya, hoy pareces algo triste. ¿Quieres contarme qué te pasa?");
                uiHandler.postDelayed(() -> emotionManager.mostrarTriste(), 4000);
                break;

            case "anger":
                speechControl.hablar("Noto que estás enfadado. Intenta respirar, todo se arregla");
                uiHandler.postDelayed(() -> emotionManager.mostrarEnfadado(), 4000);
                break;

            case "fear":
                speechControl.hablar("Parece que algo te asusta. Tranquilo, estoy aquí contigo");
                uiHandler.postDelayed(() -> emotionManager.mostrarPreocupado(), 4000);
                break;

            case "disgust":
                speechControl.hablar("Esa cara no me gusta nada. ¿Algo no va bien?");
                uiHandler.postDelayed(() -> emotionManager.mostrarPreocupado(), 3500);
                break;

            case "surprise":
                speechControl.hablar("¡Vaya sorpresa! Me contagias tu asombro");
                uiHandler.postDelayed(() -> emotionManager.mostrarEntusiasmado(), 3000);
                break;

            case "neutral":
                speechControl.hablar("Pareces tranquilo hoy. Me alegra verte bien");
                // En neutral no cambiamos la emoción del robot
                break;

            default:
                speechControl.hablar("No he podido reconocer bien tu expresión, inténtalo de nuevo");
                Log.w(TAG, "Emoción no mapeada: " + emocion);
        }
    }

    // ── Captura y conversión ──────────────────────────────────────────────────

    private static final int SEND_W = 640;
    private static final int SEND_H = 480;

    private Bitmap capturarFrame() {
        if (!tvMedia.isAvailable()) return null;
        Bitmap raw = tvMedia.getBitmap();
        if (raw == null) return null;
        Log.d(TAG, "Textura capturada: " + raw.getWidth() + "x" + raw.getHeight()
                + " -> redimensionando a " + SEND_W + "x" + SEND_H);
        Bitmap scaled = Bitmap.createScaledBitmap(raw, SEND_W, SEND_H, true);
        raw.recycle();
        return scaled;
    }

    /**
     * Convierte el Bitmap a bytes JPEG al 80%.
     * El backend espera Content-Type: image/jpeg con bytes directos (sin base64).
     */
    private byte[] bitmapToJpegBytes(Bitmap bitmap) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos);
            return baos.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "Error convirtiendo bitmap a JPEG", e);
            return null;
        }
    }

    // ── Comunicación con el servidor ──────────────────────────────────────────

    /**
     * POST /api/emotion/quick?session_id=robot_photo&max_faces=1
     * Content-Type: image/jpeg
     * Body: bytes JPEG directos (sin base64, sin JSON wrapper)
     *
     * Respuesta exitosa:
     * {
     *   "emotion": "happy",
     *   "confidence": 0.82,
     *   "face": [x, y, w, h],
     *   "faces": [ { "face_id": 0, "emotions": [...] } ]
     * }
     *
     * Sin cara:
     * { "emotion": "unknown", "confidence": 0.0, "face": null, "faces": [] }
     */
    private ResultadoEmocion enviarAlServidor(byte[] imageBytes) {
        try {
            String urlStr = ENDPOINT
                    + "?session_id=" + SESSION_ID
                    + "&max_faces=" + MAX_FACES;

            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "image/jpeg");
            conn.setDoOutput(true);
            conn.setConnectTimeout(7000);
            conn.setReadTimeout(7000);

            DataOutputStream os = new DataOutputStream(conn.getOutputStream());
            os.write(imageBytes);
            os.flush();
            os.close();

            int code = conn.getResponseCode();

            if (code == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                conn.disconnect();

                Log.d(TAG, "Respuesta servidor: " + sb);
                return parsearRespuesta(sb.toString());

            } else {
                // Leer el cuerpo del error para loguear el detalle
                try {
                    BufferedReader errReader = new BufferedReader(
                            new InputStreamReader(conn.getErrorStream()));
                    StringBuilder errSb = new StringBuilder();
                    String l;
                    while ((l = errReader.readLine()) != null) errSb.append(l);
                    Log.e(TAG, "Error HTTP " + code + ": " + errSb);
                } catch (Exception ignored) {}
                conn.disconnect();
            }

        } catch (Exception e) {
            Log.e(TAG, "Error de red", e);
        }
        return null;
    }

    /**
     * Parsea el JSON de respuesta.
     * Extrae "emotion", "confidence" y el número de caras detectadas.
     */
    private ResultadoEmocion parsearRespuesta(String json) {
        try {
            JSONObject obj      = new JSONObject(json);
            String emocion      = obj.optString("emotion", "unknown");
            double confianza    = obj.optDouble("confidence", 0.0);
            JSONArray faces     = obj.optJSONArray("faces");
            int numCaras        = (faces != null) ? faces.length() : 0;

            return new ResultadoEmocion(emocion, confianza, numCaras);

        } catch (Exception e) {
            Log.e(TAG, "Error parseando JSON: " + json, e);
            return null;
        }
    }

    // ── Clase auxiliar de resultado ───────────────────────────────────────────

    private static class ResultadoEmocion {
        final String emocion;
        final double confianza;
        final int    faces;

        ResultadoEmocion(String emocion, double confianza, int faces) {
            this.emocion   = emocion;
            this.confianza = confianza;
            this.faces     = faces;
        }
    }

    // ── Lifecycle de la cámara ────────────────────────────────────────────────

    private void iniciarListenerVideo() {
        mediaManager.setMediaListener(new MediaStreamListener() {
            @Override
            public void getVideoStream(byte[] bytes) {
                mostrarVideo(ByteBuffer.wrap(bytes));
            }
            @Override
            public void getAudioStream(byte[] bytes) {}
        });
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        this.surface = new Surface(st);
        StreamOption opt = new StreamOption();
        opt.setChannel(StreamOption.MAIN_STREAM);
        opt.setDecodType(StreamOption.HARDWARE_DECODE);
        opt.setJustIframe(false);
        mediaManager.openStream(opt);
        iniciarDecodificador(this.surface);
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {}

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        mediaManager.closeStream();
        pararDecodificador();
        if (surface != null) surface.release();
        return true;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}

    // ── Decodificador H.264 ───────────────────────────────────────────────────

    private void iniciarDecodificador(Surface surface) {
        if (mediaCodec != null) return;
        try {
            mediaCodec = MediaCodec.createDecoderByType("video/avc");
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", 1280, 720);
            mediaCodec.configure(format, surface, null, 0);
            mediaCodec.start();
            videoInputBuffers = mediaCodec.getInputBuffers();
        } catch (IOException e) {
            Log.e(TAG, "Error iniciando decodificador", e);
        }
    }

    private void pararDecodificador() {
        if (mediaCodec != null) {
            mediaCodec.stop();
            mediaCodec.release();
            mediaCodec = null;
        }
        videoInputBuffers = null;
    }

    private void mostrarVideo(ByteBuffer sampleData) {
        try {
            int inIndex = mediaCodec.dequeueInputBuffer(16000);
            if (inIndex >= 0) {
                ByteBuffer buffer = videoInputBuffers[inIndex];
                buffer.clear();
                buffer.put(sampleData);
                buffer.flip();
                mediaCodec.queueInputBuffer(inIndex, 0, sampleData.limit(), 0, 0);
            }
            int outId = mediaCodec.dequeueOutputBuffer(videoBufferInfo, 16000);
            if (outId >= 0) mediaCodec.releaseOutputBuffer(outId, true);
        } catch (Exception e) {
            Log.e(TAG, "Error mostrando video", e);
        }
    }
}
