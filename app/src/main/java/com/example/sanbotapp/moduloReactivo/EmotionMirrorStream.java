package com.example.sanbotapp.moduloReactivo;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.TextureView;

import com.example.sanbotapp.RobotEmotionManager;
import com.qihancloud.opensdk.function.beans.StreamOption;
import com.qihancloud.opensdk.function.unit.MediaManager;
import com.qihancloud.opensdk.function.unit.interfaces.media.MediaStreamListener;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * EmotionMirrorStream — Modo STREAM CONTINUO
 *
 * Mantiene la cámara activa y, cada {@link #INTERVALO_MS} ms, captura un fotograma,
 * lo envía al endpoint POST /api/emotion/quick y hace que el robot imite
 * la emoción detectada en tiempo real.
 *
 * Solo se lanza una nueva detección cuando el robot ha terminado de ejecutar
 * la emoción anterior, para evitar solapamientos.
 *
 * Uso:
 *   EmotionMirrorStream mirror = new EmotionMirrorStream(
 *       mediaManager, tvMedia, emotionManager
 *   );
 *   mirror.iniciar();    // arranca el stream y la detección periódica
 *   mirror.detener();    // para todo y libera la cámara
 */
public class EmotionMirrorStream implements TextureView.SurfaceTextureListener {

    private static final String TAG = "EmotionMirrorStream";

    // TODO: Reemplaza con la IP
    private static final String BASE_URL    = "http://192.168.50.14:8000";
    private static final String ENDPOINT    = BASE_URL + "/api/emotion/quick";
    private static final String SESSION_ID  = "robot_stream";
    private static final int    MAX_FACES   = 1;


    /** Umbral mínimo de confianza para reaccionar (0–1). Evita reaccionar a detecciones dudosas. */
    private static final double CONFIANZA_MINIMA = 0.55;

    /** Intervalo entre capturas (ms). El servidor tarda ~1 s, así que 3 s da margen. */
    private static final long INTERVALO_MS = 3000;

    private final MediaManager        mediaManager;
    private final RobotEmotionManager emotionManager;
    private final TextureView         tvMedia;


    private final Handler uiHandler    = new Handler(Looper.getMainLooper());
    private boolean activo             = false;
    private boolean peticionEnCurso    = false;

    private boolean streamAbierto = false;

    public EmotionMirrorStream(MediaManager mediaManager,
                               TextureView tvMedia,
                               RobotEmotionManager emotionManager) {
        this.mediaManager   = mediaManager;
        this.tvMedia        = tvMedia;
        this.emotionManager = emotionManager;
        this.tvMedia.setSurfaceTextureListener(this);
        iniciarListenerVideo();
    }

    /** Arranca la detección continua de emociones. */
    public void iniciar() {
        activo = true;

        // Si el TextureView ya tiene superficie y el stream no está abierto aún,
        // onSurfaceTextureAvailable no volverá a dispararse — lo abrimos aquí.
        if (tvMedia.isAvailable() && !streamAbierto) {
            Log.i(TAG, "Surface ya disponible al iniciar — abriendo stream manualmente");
            abrirStream();
        }

        uiHandler.postDelayed(bucleDeteccion, INTERVALO_MS);
        Log.i(TAG, "Stream de emociones iniciado (INTERVALO=" + INTERVALO_MS + "ms)");
    }

    /** Para la detección periódica (la cámara sigue visible hasta que se destruya la vista). */
    public void detener() {
        activo = false;
        uiHandler.removeCallbacks(bucleDeteccion);
        Log.i(TAG, "Stream de emociones detenido");
    }

    public boolean isActivo() { return activo; }

    //  Bucle de detección

    private final Runnable bucleDeteccion = new Runnable() {
        @Override
        public void run() {
            if (!activo) return;

            if (!peticionEnCurso && !emotionManager.isProcessing()) {
                detectarYImitar();
            } else {
                Log.d(TAG, "Ciclo saltado | peticion=" + peticionEnCurso
                        + " | procesando=" + emotionManager.isProcessing());
            }
            uiHandler.postDelayed(this, INTERVALO_MS);
        }
    };

    private void detectarYImitar() {
        Bitmap frame = capturarFrame();
        if (frame == null) {
            Log.w(TAG, "Frame nulo, saltando ciclo");
            return;
        }

        byte[] imageBytes = bitmapToJpegBytes(frame);
        if (imageBytes == null) return;

        peticionEnCurso = true;
        new Thread(() -> {
            ResultadoEmocion resultado = enviarAlServidor(imageBytes);
            uiHandler.post(() -> {
                peticionEnCurso = false;
                if (resultado != null && resultado.esValida()) {
                    Log.i(TAG, "Emoción detectada: " + resultado.emocion
                            + " (" + (int)(resultado.confianza * 100) + "%)");
                    imitarEmocion(resultado.emocion);
                } else {
                    Log.d(TAG, "Sin cara detectada o confianza insuficiente");
                }
            });
        }).start();
    }

    //  Mapeo emoción → robot

    /**
     * Mapea las etiquetas del backend a las acciones del robot.
     * Etiquetas posibles según la API: happy, sad, anger, fear, disgust, surprise, neutral, unknown
     */
    private void imitarEmocion(String emocion) {
        switch (emocion.toLowerCase()) {
            case "happy":
                emotionManager.mostrarFeliz();
                break;
            case "sad":
                emotionManager.mostrarTriste();
                break;
            case "anger":
                emotionManager.mostrarEnfadado();
                break;
            case "fear":
                // TODO: Annadir emocion miedo
            case "disgust":
                emotionManager.mostrarPreocupado();
                break;
            case "surprise":
                emotionManager.mostrarEntusiasmado();
                break;
            case "neutral":
            case "unknown":
            default:
                // En neutral/unknown el robot no hace nada
                break;
        }
    }

    // Captura y conversión de imagen

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

    //  Comunicación con el servidor

    /**
     * POST /api/emotion/quick?session_id=robot_stream&max_faces=1
     * Content-Type: image/jpeg
     * Body: bytes JPEG directos
     *
     * Devuelve ResultadoEmocion o null si hay error de red.
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
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

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
                return parsearRespuesta(sb.toString());
            } else {
                Log.e(TAG, "Error HTTP " + code + ": " + conn.getResponseMessage());
                conn.disconnect();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error de red", e);
        }
        return null;
    }

    /**
     * Parsea el JSON de respuesta del backend.
     *
     * Formato esperado:
     * {
     *   "emotion": "happy",
     *   "confidence": 0.82,
     *   "faces": [ { "face_id": 0, "emotions": [...] } ]
     * }
     *
     * Si emotion == "unknown" o faces está vacío, devuelve resultado inválido.
     */
    private ResultadoEmocion parsearRespuesta(String json) {
        try {
            JSONObject obj = new JSONObject(json);

            String emocion    = obj.optString("emotion", "unknown");
            double confianza  = obj.optDouble("confidence", 0.0);
            JSONArray faces   = obj.optJSONArray("faces");

            // Sin cara detectada
            if ("unknown".equals(emocion) || faces == null || faces.length() == 0) {
                return new ResultadoEmocion("unknown", 0.0);
            }

            return new ResultadoEmocion(emocion, confianza);

        } catch (Exception e) {
            Log.e(TAG, "Error parseando JSON: " + json, e);
            return null;
        }
    }

    // Clase auxiliar de resultado

    private class ResultadoEmocion {
        final String emocion;
        final double confianza;

        ResultadoEmocion(String emocion, double confianza) {
            this.emocion   = emocion;
            this.confianza = confianza;
        }

        /** Devuelve true si la detección es fiable y procesable. */
        boolean esValida() {
            return !"unknown".equals(emocion) && confianza >= CONFIANZA_MINIMA;
        }
    }

    //  Lifecycle de la cámara

    private void iniciarListenerVideo() {
        // El SDK renderiza el video directamente en el TextureView.
        // Registramos el listener igualmente por si el SDK lo requiere,
        // pero no necesitamos procesar los bytes aquí.
        mediaManager.setMediaListener(new MediaStreamListener() {
            @Override
            public void getVideoStream(byte[] bytes) {}
            @Override
            public void getAudioStream(byte[] bytes) {}
        });
    }

    private void abrirStream() {
        StreamOption opt = new StreamOption();
        opt.setChannel(StreamOption.MAIN_STREAM);
        opt.setDecodType(StreamOption.HARDWARE_DECODE);
        opt.setJustIframe(false);
        mediaManager.openStream(opt);
        streamAbierto = true;
        hacerWarmup();
        Log.i(TAG, "Stream de camara abierto");
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        Log.i(TAG, "onSurfaceTextureAvailable " + w + "x" + h);
        abrirStream();
    }

    /**
     * Envia un JPEG minimo al servidor para que cargue el modelo en memoria.
     * La primera llamada real tarda ~2 s; las siguientes ~0.8-1 s.
     * Con el warmup ese coste se paga al arrancar, no cuando el usuario interactua.
     */
    private void hacerWarmup() {
        new Thread(() -> {
            try {
                String urlStr = ENDPOINT + "?session_id=warmup&max_faces=1";
                java.net.HttpURLConnection conn =
                        (java.net.HttpURLConnection) new java.net.URL(urlStr).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "image/jpeg");
                conn.setDoOutput(true);
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                // JPEG minimo de 1x1 px — solo para que el servidor cargue el modelo
                byte[] jpegMinimo = new byte[]{
                        (byte)0xFF,(byte)0xD8,(byte)0xFF,(byte)0xE0,0x00,0x10,
                        0x4A,0x46,0x49,0x46,0x00,0x01,0x01,0x00,0x00,0x01,
                        0x00,0x01,0x00,0x00,(byte)0xFF,(byte)0xD9
                };
                conn.getOutputStream().write(jpegMinimo);
                conn.getOutputStream().flush();
                int code = conn.getResponseCode();
                Log.i(TAG, "Warmup completado (HTTP " + code + ")");
                conn.disconnect();
            } catch (Exception e) {
                Log.w(TAG, "Warmup fallido (no critico): " + e.getMessage());
            }
        }, "warmup-thread").start();
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {}

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        detener();
        mediaManager.closeStream();
        streamAbierto = false;
        return true;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
}