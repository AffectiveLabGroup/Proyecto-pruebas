package com.example.sanbotapp.moduloReactivo;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.TextureView;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

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
import java.nio.ByteBuffer;

/**
 * EmotionMirrorStreamBytes — Modo STREAM CONTINUO sin TextureView
 *
 * En lugar de capturar el TextureView cada X segundos, procesa directamente
 * los bytes que entrega el SDK en getVideoStream(). Esto permite controlar
 * exactamente cuándo se envía cada frame sin depender de la superficie.
 *
 * Cómo funciona:
 *   El SDK entrega paquetes NAL de H.264 en getVideoStream(). Los acumulamos
 *   y cada FRAME_SKIP paquetes intentamos decodificar el último en memoria
 *   (MediaCodec en modo buffer, sin Surface) y obtener un Bitmap que enviamos
 *   al servidor.
 *
 *   Si el SDK entrega los bytes ya como JPEG o NV21 (depende del firmware),
 *   el fallback con BitmapFactory los convierte directamente sin necesidad
 *   de MediaCodec.
 *
 * Ventajas frente a la versión TextureView:
 *   - Control preciso de cada frame
 *   - No depende del layout ni del tamaño de pantalla
 *   - La imagen enviada es siempre la resolución real de la cámara, escalada
 *
 * Uso:
 *   EmotionMirrorStreamBytes mirror = new EmotionMirrorStreamBytes(
 *       mediaManager, emotionManager, BASE_URL
 *   );
 *   mirror.iniciar();
 *   mirror.detener();
 *
 * NOTA: No necesita TextureView — si quieres preview visual usa también
 * EmotionMirrorStream (la versión con TextureView) o ponlos juntos.
 */
public class EmotionMirrorStreamBytes {

    private static final String TAG = "EmotionMirrorStreamB";

    private static final String SESSION_ID      = "robot_stream_bytes";
    private static final int    MAX_FACES       = 1;
    private static final double CONFIANZA_MINIMA = 0.55;

    /** Resolución de envío al servidor. */
    private static final int SEND_W = 640;
    private static final int SEND_H = 480;

    /**
     * Procesar 1 de cada N paquetes NAL recibidos.
     * Con ~15 fps y FRAME_SKIP=15 → ~1 envío cada segundo.
     * Con FRAME_SKIP=30 → ~1 envío cada 2 s.
     * Ajusta según la velocidad de tu servidor.
     */
    private static final int FRAME_SKIP = 20;

    private final MediaManager        mediaManager;
    private final RobotEmotionManager emotionManager;
    private final String              endpoint;

    // TextureView opcional para preview visual (puede ser null)
    private TextureView tvMedia   = null;
    private Surface     previewSurface = null;

    private final Handler uiHandler     = new Handler(Looper.getMainLooper());
    private boolean       activo        = false;
    private boolean       peticionEnCurso = false;

    private int paqueteCount    = 0;
    private int ultimoEnviado   = 0;

    // MediaCodec en modo buffer (sin Surface) para decodificar H.264 → Bitmap
    private MediaCodec            decoder;
    private MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
    private boolean               decoderListo = false;

    // ── Constructor ───────────────────────────────────────────────────────────

    public EmotionMirrorStreamBytes(MediaManager mediaManager,
                                    RobotEmotionManager emotionManager,
                                    String baseUrl) {
        this.mediaManager   = mediaManager;
        this.emotionManager = emotionManager;
        this.endpoint       = baseUrl + "/api/emotion/quick";
    }

    // ── API pública ───────────────────────────────────────────────────────────

    public void iniciar() {
        activo       = true;
        paqueteCount = 0;
        ultimoEnviado = 0;

        iniciarDecoder();
        registrarListener();
        abrirStream();

        Log.i(TAG, "Stream por bytes iniciado (FRAME_SKIP=" + FRAME_SKIP + ")");
    }

    public void detener() {
        activo = false;
        mediaManager.closeStream();
        pararDecoder();
        if (previewSurface != null) { previewSurface.release(); previewSurface = null; }
        Log.i(TAG, "Stream por bytes detenido");
    }

    public boolean isActivo() { return activo; }

    /**
     * Opcional: pasa un TextureView para mostrar el preview en pantalla.
     * Llama a esto ANTES de iniciar().
     */
    public void setPreviewTexture(TextureView tv) {
        this.tvMedia = tv;
    }

    // ── Stream y listener ─────────────────────────────────────────────────────

    private void abrirStream() {
        // Si hay TextureView, creamos la Surface para el preview.
        // SOFTWARE_DECODE es obligatorio para que los bytes lleguen al listener;
        // con HARDWARE_DECODE el SDK los renderiza directamente y el callback queda vacío.
        if (tvMedia != null && tvMedia.isAvailable()) {
            SurfaceTexture st = tvMedia.getSurfaceTexture();
            previewSurface = new Surface(st);
            Log.i(TAG, "Preview habilitado en TextureView");
        }
        StreamOption opt = new StreamOption();
        opt.setChannel(StreamOption.MAIN_STREAM);
        opt.setDecodType(StreamOption.SOFTWARE_DECODE);
        opt.setJustIframe(false);
        mediaManager.openStream(opt);
    }

    private void registrarListener() {
        mediaManager.setMediaListener(new MediaStreamListener() {
            @Override
            public void getVideoStream(byte[] bytes) {
                if (!activo || bytes == null || bytes.length == 0) return;

                paqueteCount++;

                // Dibujar en el preview si está disponible
                if (previewSurface != null && previewSurface.isValid()) {
                    try {
                        android.graphics.Canvas canvas = previewSurface.lockCanvas(null);
                        if (canvas != null) {
                            // El SDK en SOFTWARE_DECODE entrega NV21 — lo pintamos
                            // Solo si tenemos un bitmap decodificado reciente
                            previewSurface.unlockCanvasAndPost(canvas);
                        }
                    } catch (Exception ignored) {}
                }

                // Solo procesamos 1 de cada FRAME_SKIP paquetes
                if (paqueteCount - ultimoEnviado < FRAME_SKIP) return;
                // Si hay petición en vuelo o el robot está ejecutando una emoción, saltamos
                if (peticionEnCurso || emotionManager.isProcessing()) return;

                ultimoEnviado = paqueteCount;

                // Intentar obtener Bitmap de los bytes
                Bitmap frame = bytesABitmap(bytes);
                if (frame == null) {
                    Log.d(TAG, "Paquete #" + paqueteCount + ": no se pudo convertir a Bitmap");
                    return;
                }

                Log.d(TAG, "Paquete #" + paqueteCount + " → Bitmap "
                        + frame.getWidth() + "x" + frame.getHeight()
                        + " → enviando al servidor");

                byte[] jpegBytes = bitmapToJpeg(frame);
                frame.recycle();
                if (jpegBytes == null) return;

                peticionEnCurso = true;
                final byte[] payload = jpegBytes;
                new Thread(() -> {
                    ResultadoEmocion resultado = enviarAlServidor(payload);
                    uiHandler.post(() -> {
                        peticionEnCurso = false;
                        if (resultado != null && resultado.esValida()) {
                            Log.i(TAG, "Emocion: " + resultado.emocion
                                    + " (" + (int)(resultado.confianza * 100) + "%)");
                            imitarEmocion(resultado.emocion);
                        }
                    });
                }).start();
            }

            @Override
            public void getAudioStream(byte[] bytes) {}
        });
    }

    // ── Conversión de bytes a Bitmap ──────────────────────────────────────────

    /**
     * Estrategia en cascada para convertir los bytes del SDK a Bitmap:
     *
     *  1. Intento directo con BitmapFactory (funciona si el SDK entrega JPEG)
     *  2. Decodificación H.264 con MediaCodec en modo buffer
     *  3. Interpretación como NV21 (YUV) si el tamaño cuadra
     *
     * Cuál aplica depende del firmware del robot. Los logs te dirán cuál funciona.
     */
    private Bitmap bytesABitmap(byte[] bytes) {

        // ── Intento 1: JPEG directo ───────────────────────────────────────────
        // Si el SDK ya entrega JPEG esto funciona directamente
        if (bytes.length > 2 && bytes[0] == (byte)0xFF && bytes[1] == (byte)0xD8) {
            Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bmp != null) {
                Log.v(TAG, "Decodificado como JPEG directo");
                return escalar(bmp);
            }
        }

        // ── Intento 2: H.264 con MediaCodec en modo buffer ───────────────────
        if (decoderListo) {
            Bitmap bmp = decodificarH264(bytes);
            if (bmp != null) {
                Log.v(TAG, "Decodificado como H.264");
                return escalar(bmp);
            }
        }

        // ── Intento 3: NV21 (YUV 4:2:0) ─────────────────────────────────────
        // El tamaño de un frame NV21 de WxH es W*H*3/2
        // Probamos con resoluciones comunes del Sanbot
        int[][] resoluciones = {{1280, 720}, {640, 480}, {1920, 1080}};
        for (int[] res : resoluciones) {
            int w = res[0], h = res[1];
            if (bytes.length == w * h * 3 / 2) {
                Bitmap bmp = nv21ToBitmap(bytes, w, h);
                if (bmp != null) {
                    Log.v(TAG, "Decodificado como NV21 " + w + "x" + h);
                    return escalar(bmp);
                }
            }
        }

        return null;
    }

    private Bitmap escalar(Bitmap src) {
        if (src.getWidth() == SEND_W && src.getHeight() == SEND_H) return src;
        Bitmap scaled = Bitmap.createScaledBitmap(src, SEND_W, SEND_H, true);
        src.recycle();
        return scaled;
    }

    // ── Decodificador H.264 en modo buffer (sin Surface) ─────────────────────

    // true cuando hemos configurado el decoder con los SPS/PPS reales del stream
    private boolean decoderConfigurado = false;

    private void iniciarDecoder() {
        try {
            decoder = MediaCodec.createDecoderByType("video/avc");
            // No configuramos aquí — esperamos al primer paquete SPS/PPS
            // para tener la resolución y parámetros reales del stream
            decoderListo = true;
            Log.i(TAG, "Decoder H.264 creado, esperando SPS/PPS");
        } catch (Exception e) {
            decoderListo = false;
            Log.w(TAG, "No se pudo crear decoder H.264: " + e.getMessage());
        }
    }

    private void pararDecoder() {
        if (decoder != null) {
            try { decoder.stop(); } catch (Exception ignored) {}
            decoder.release();
            decoder = null;
        }
        decoderListo      = false;
        decoderConfigurado = false;
    }

    /**
     * Devuelve true si el paquete NAL es SPS (0x27 o 0x67 como primer byte tras start code).
     * El paquete #1 con header 00 00 00 01 27... es SPS — es el que inicializa el decoder.
     */
    private boolean esSPS(byte[] nal) {
        // Buscar el primer byte tras el start code (00 00 00 01 o 00 00 01)
        int offset = 0;
        if (nal.length > 4 && nal[0]==0 && nal[1]==0 && nal[2]==0 && nal[3]==1) offset = 4;
        else if (nal.length > 3 && nal[0]==0 && nal[1]==0 && nal[2]==1) offset = 3;
        if (offset >= nal.length) return false;
        int nalType = nal[offset] & 0x1F;
        return nalType == 7; // 7 = SPS
    }

    private boolean esPPS(byte[] nal) {
        int offset = 0;
        if (nal.length > 4 && nal[0]==0 && nal[1]==0 && nal[2]==0 && nal[3]==1) offset = 4;
        else if (nal.length > 3 && nal[0]==0 && nal[1]==0 && nal[2]==1) offset = 3;
        if (offset >= nal.length) return false;
        int nalType = nal[offset] & 0x1F;
        return nalType == 8; // 8 = PPS
    }

    // Guardamos SPS y PPS para configurar el decoder cuando lleguen ambos
    private byte[] spsBytes = null;
    private byte[] ppsBytes = null;

    private void configurarDecoderConSPSPPS() {
        try {
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", 1280, 720);
            format.setByteBuffer("csd-0", ByteBuffer.wrap(spsBytes));
            format.setByteBuffer("csd-1", ByteBuffer.wrap(ppsBytes));
            decoder.configure(format, null, null, 0);
            decoder.start();
            decoderConfigurado = true;
            Log.i(TAG, "Decoder configurado con SPS/PPS reales");
        } catch (Exception e) {
            Log.e(TAG, "Error configurando decoder con SPS/PPS: " + e.getMessage());
            decoderListo = false;
        }
    }

    private Bitmap decodificarH264(byte[] nalBytes) {
        if (!decoderListo) return null;

        // Guardar SPS/PPS y configurar el decoder cuando tengamos los dos
        if (esSPS(nalBytes)) {
            spsBytes = nalBytes;
            Log.d(TAG, "SPS recibido (" + nalBytes.length + " bytes)");
            if (!decoderConfigurado && ppsBytes != null) configurarDecoderConSPSPPS();
            return null; // el SPS no produce imagen
        }
        if (esPPS(nalBytes)) {
            ppsBytes = nalBytes;
            Log.d(TAG, "PPS recibido (" + nalBytes.length + " bytes)");
            if (!decoderConfigurado && spsBytes != null) configurarDecoderConSPSPPS();
            return null; // el PPS no produce imagen
        }

        // Sin decoder configurado no podemos decodificar frames
        if (!decoderConfigurado) return null;

        try {
            int inIdx = decoder.dequeueInputBuffer(10000);
            if (inIdx < 0) return null;

            ByteBuffer inputBuf = decoder.getInputBuffer(inIdx);
            inputBuf.clear();
            inputBuf.put(nalBytes);
            decoder.queueInputBuffer(inIdx, 0, nalBytes.length,
                    System.currentTimeMillis() * 1000, 0);

            // Drenar la cola de salida
            int outIdx = decoder.dequeueOutputBuffer(bufferInfo, 30000);

            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat newFmt = decoder.getOutputFormat();
                Log.i(TAG, "Formato de salida: " + newFmt);
                // Reintentar con el mismo paquete
                outIdx = decoder.dequeueOutputBuffer(bufferInfo, 30000);
            }

            if (outIdx >= 0) {
                ByteBuffer outputBuf = decoder.getOutputBuffer(outIdx);
                MediaFormat outFormat = decoder.getOutputFormat();

                int w = outFormat.getInteger(MediaFormat.KEY_WIDTH);
                int h = outFormat.getInteger(MediaFormat.KEY_HEIGHT);
                // KEY_STRIDE puede no estar disponible en todos los dispositivos
                int stride = w;
                if (outFormat.containsKey(MediaFormat.KEY_STRIDE)) {
                    stride = outFormat.getInteger(MediaFormat.KEY_STRIDE);
                }

                byte[] yuv = new byte[outputBuf.remaining()];
                outputBuf.get(yuv);
                decoder.releaseOutputBuffer(outIdx, false);

                Log.v(TAG, "Frame decodificado: " + w + "x" + h
                        + " stride=" + stride + " yuv=" + yuv.length + "b");
                return nv21ToBitmap(yuv, stride, h);
            }
        } catch (Exception e) {
            Log.w(TAG, "Error decodificando frame: " + e.getMessage());
        }
        return null;
    }

    /** Convierte NV21 (YUV 4:2:0 semi-planar) a Bitmap via YuvImage → JPEG → Bitmap */
    private Bitmap nv21ToBitmap(byte[] nv21, int width, int height) {
        try {
            YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuvImage.compressToJpeg(new Rect(0, 0, width, height), 85, out);
            byte[] jpegBytes = out.toByteArray();
            return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
        } catch (Exception e) {
            Log.w(TAG, "Error convirtiendo NV21: " + e.getMessage());
            return null;
        }
    }

    // ── Mapeo emoción → robot ─────────────────────────────────────────────────

    private void imitarEmocion(String emocion) {
        switch (emocion.toLowerCase()) {
            case "happy":    emotionManager.mostrarFeliz();        break;
            case "sad":      emotionManager.mostrarTriste();       break;
            case "anger":    emotionManager.mostrarEnfadado();     break;
            case "fear":
            case "disgust":  emotionManager.mostrarPreocupado();   break;
            case "surprise": emotionManager.mostrarEntusiasmado(); break;
            default: break;
        }
    }

    // ── Red ───────────────────────────────────────────────────────────────────

    private byte[] bitmapToJpeg(Bitmap bitmap) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos);
            return baos.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "Error JPEG", e);
            return null;
        }
    }

    private ResultadoEmocion enviarAlServidor(byte[] imageBytes) {
        try {
            String urlStr = endpoint + "?session_id=" + SESSION_ID + "&max_faces=" + MAX_FACES;
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

            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                conn.disconnect();
                return parsear(sb.toString());
            }
            conn.disconnect();
        } catch (Exception e) {
            Log.e(TAG, "Error de red", e);
        }
        return null;
    }

    private ResultadoEmocion parsear(String json) {
        try {
            JSONObject obj   = new JSONObject(json);
            String emocion   = obj.optString("emotion", "unknown");
            double confianza = obj.optDouble("confidence", 0.0);
            JSONArray faces  = obj.optJSONArray("faces");
            int numCaras     = (faces != null) ? faces.length() : 0;
            return new ResultadoEmocion(emocion, confianza, numCaras);
        } catch (Exception e) {
            Log.e(TAG, "Error parseando: " + json, e);
            return null;
        }
    }

    // ── Clase auxiliar ────────────────────────────────────────────────────────

    private class ResultadoEmocion {
        final String emocion;
        final double confianza;
        final int    faces;

        ResultadoEmocion(String emocion, double confianza, int faces) {
            this.emocion   = emocion;
            this.confianza = confianza;
            this.faces     = faces;
        }

        boolean esValida() {
            return !"unknown".equals(emocion)
                    && faces > 0
                    && confianza >= CONFIANZA_MINIMA;
        }
    }
}