package com.example.sanbotapp.moduloReactivo;

import static android.content.ContentValues.TAG;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

import com.example.sanbotapp.robotControl.SpeechControl;
import com.qihancloud.opensdk.function.beans.StreamOption;
import com.qihancloud.opensdk.function.unit.MediaManager;
import com.qihancloud.opensdk.function.unit.SpeechManager;
import com.qihancloud.opensdk.function.unit.interfaces.media.MediaStreamListener;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.Objects;
import org.json.JSONArray;
import org.json.JSONObject;


public class RecognitionControl implements TextureView.SurfaceTextureListener{

    private MediaManager mediaManager;
    private SpeechControl speechControl;
    private SurfaceTexture surfaceTexture;
    private Surface surface;
    private Bitmap frame;
    MediaCodec mediaCodec;
    TextureView tvMedia;
    long decodeTimeout = 16000;

    private Context context;
    MediaCodec.BufferInfo videoBufferInfo = new MediaCodec.BufferInfo();
    ByteBuffer[] videoInputBuffers;
    Runnable runnable;
    Handler handler = new Handler();

    private final Handler backgroundHandler = new Handler(Looper.getMainLooper());

    private long inicioRuidoAlto = 0;
    private boolean ruidoActivo = false;
    private static final int TIEMPO_UMBRAL_MS = 1000;
    private ResultadoReconocimiento resultadoReconocimiento = new ResultadoReconocimiento();

    private final ByteArrayOutputStream audioBuffer = new ByteArrayOutputStream();
    private final Object lock = new Object();
    private long lastSaveTime = System.currentTimeMillis();
    private int audioFileCounter = 0;
    private final int SAMPLE_RATE = 16000; // o el que uses
    private final int CHANNELS = 1;
    private final int BITS_PER_SAMPLE = 16;
    private final int SAVE_INTERVAL_MS = 5000; // 5 segundos



    public RecognitionControl(SpeechManager speechManager, MediaManager mediaManager, TextureView tvMedia, Context context){
        this.mediaManager = mediaManager;
        this.speechControl = new SpeechControl(speechManager);
        this.context = context;
        this.tvMedia = tvMedia;
        this.tvMedia.setSurfaceTextureListener(this);
        initListener();
    }

    /***********************************************************************************************
     * Inicio de la cámara, activa el video y audio stream
     */

    private void initListener() {
        mediaManager.setMediaListener(new MediaStreamListener() {
            @Override
            public void getVideoStream(byte[] bytes) {
                showViewData(ByteBuffer.wrap(bytes));
            }

            @Override
            public void getAudioStream(byte[] bytes) {

            }
        });
    }

    public void audiowav() {
        mediaManager.setMediaListener(new MediaStreamListener() {
            @Override
            public void getVideoStream(byte[] bytes) {
                showViewData(ByteBuffer.wrap(bytes));
            }

            @Override
            public void getAudioStream(byte[] bytes) {
                if (bytes == null || bytes.length == 0) {
                    Log.e("AudioDebug", "❌ audioData está vacío o es nulo.");
                    return;
                }

                new Thread(() -> {
                    synchronized (lock) {
                        try {
                            audioBuffer.write(bytes);
                        } catch (IOException e) {
                            Log.e("AudioDebug", "Error escribiendo en el buffer: " + e.getMessage());
                        }

                        long now = System.currentTimeMillis();
                        if (now - lastSaveTime >= SAVE_INTERVAL_MS) {
                            byte[] audioChunk = audioBuffer.toByteArray(); // copia actual del buffer
                            audioBuffer.reset(); // vacía para siguiente chunk
                            lastSaveTime = now;

                            // Guardar a archivo WAV
                            File wavFile = new File(context.getExternalFilesDir(null),
                                    "audio_" + (audioFileCounter++) + ".wav");



                            try {
                                uploadWavToAzure(audioChunk, "audio_" + System.currentTimeMillis() + ".wav");
                                saveAsWavFile(audioChunk, wavFile, SAMPLE_RATE, CHANNELS, BITS_PER_SAMPLE);
                                Log.d("AudioDebug", "✅ Archivo guardado: " + wavFile.getAbsolutePath());


                            } catch (IOException e) {
                                Log.e("AudioDebug", "❌ Error guardando WAV: " + e.getMessage());
                            }
                        }
                    }
                }).start();
            }
        });
    }
    public void startDeteccionRuido() {
        mediaManager.setMediaListener(new MediaStreamListener() {
            @Override
            public void getVideoStream(byte[] bytes) {
                backgroundHandler.post(() -> showViewData(ByteBuffer.wrap(bytes))); // Mueve a otro hilo
            }

            @Override
            public void getAudioStream(byte[] bytes) {

                if (bytes == null || bytes.length == 0) {
                    Log.e("AudioDebug", "❌ audioData está vacío o es nulo.");
                    return;
                }

                try {
                    File wavFile = new File(context.getExternalFilesDir(null), "audio_detectado.wav");
                    saveAsWavFile(bytes, wavFile, 16000, 1, 16);  // Ajusta sampleRate/canales según tu fuente
                    Log.d("AudioDebug", "✅ Archivo WAV guardado en: " + wavFile.getAbsolutePath());
                } catch (IOException e) {
                    Log.e("AudioDebug", "❌ Error guardando WAV: " + e.getMessage());
                }

                // Procesar el audio en un hilo en segundo plano
                new Thread(() -> {
                    float[] floatSamples = convertBytesToFloat(bytes);
                    float rms = calculateRMS(floatSamples);
                    float decibels = calculateDecibels(rms);
                    float calibratedDecibels = decibels + 90;

                    Log.d("Audio", "🔊 Decibeles detectados: " + calibratedDecibels + " dB");

                    if (calibratedDecibels > 75) {
                        if (!ruidoActivo) {
                            inicioRuidoAlto = System.currentTimeMillis();
                            ruidoActivo = true;
                        }

                        if (System.currentTimeMillis() - inicioRuidoAlto >= TIEMPO_UMBRAL_MS) {
                            backgroundHandler.post(() -> quejarse()); // Ejecutar quejarse() en la UI
                            ruidoActivo = false;
                        }
                    } else {
                        ruidoActivo = false;
                    }
                }).start();
            }
        });
    }

    // Pruebaaaaaa----------------------
    private void saveWavToMusicFolder(byte[] wavData, String fileName, Context context) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Audio.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav");
        values.put(MediaStore.Audio.Media.IS_MUSIC, 1);
        values.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/MiApp");

        Uri uri = context.getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
        if (uri != null) {
            try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                out.write(wavData);
                Log.d("AudioDebug", "✅ Guardado en Música/MiApp: " + uri.toString());
            } catch (IOException e) {
                Log.e("AudioDebug", "❌ Error escribiendo WAV: " + e.getMessage());
            }
        } else {
            Log.e("AudioDebug", "❌ No se pudo crear URI para guardar audio.");
        }
    }

    public void uploadWavToAzure(byte[] wavData, String fileName) {
        new Thread(() -> {
            try {
                HttpURLConnection connection = getHttpURLConnection(wavData, fileName);

                try (OutputStream os = connection.getOutputStream()) {
                    os.write(wavData);
                }

                int responseCode = connection.getResponseCode();
                if (responseCode == 201) {
                    Log.d("AzureUpload", "✅ Archivo subido correctamente a Azure.");
                } else {
                    Log.e("AzureUpload", "❌ Fallo al subir. Código: " + responseCode);
                }

                connection.disconnect();
            } catch (Exception e) {
                Log.e("AzureUpload", "❌ Error: " + e.getMessage(), e);
            }
        }).start();
    }

    private static HttpURLConnection getHttpURLConnection(byte[] wavData, String nombre) throws IOException {
        URL url = new URL("https://guardarcanciones.blob.core.windows.net/musica/"+ nombre +"?sp=racwdli&st=2025-05-22T11:32:08Z&se=2025-05-22T19:32:08Z&sv=2024-11-04&sr=c&sig=hTW8%2FYziIwZ%2FMhFDzXmpuOAe5OuSgWk%2B%2BBCfM%2Fv5FIY%3D"); // debe ser completo, incluyendo ?sig=...
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();

        connection.setDoOutput(true);
        connection.setRequestMethod("PUT");
        connection.setRequestProperty("x-ms-blob-type", "BlockBlob");
        connection.setRequestProperty("Content-Type", "audio/wav");
        connection.setFixedLengthStreamingMode(wavData.length);
        return connection;
    }


    /***********************************************************************************************
     * Recogida de audio a través del array de bytes[]
     *
     */
    public void saveAsWavFile(byte[] audioData, File outputFile, int sampleRate, int channels, int bitsPerSample) throws IOException {
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int dataSize = audioData.length;

        FileOutputStream out = new FileOutputStream(outputFile);

        // Encabezado WAV
        out.write(new byte[] {
                'R', 'I', 'F', 'F', // ChunkID
                (byte)(dataSize + 36), (byte)((dataSize + 36) >> 8), (byte)((dataSize + 36) >> 16), (byte)((dataSize + 36) >> 24), // ChunkSize
                'W', 'A', 'V', 'E', // Format
                'f', 'm', 't', ' ', // Subchunk1ID
                16, 0, 0, 0, // Subchunk1Size (16 para PCM)
                1, 0, // AudioFormat (1 = PCM)
                (byte)channels, 0, // NumChannels
                (byte)(sampleRate), (byte)(sampleRate >> 8), (byte)(sampleRate >> 16), (byte)(sampleRate >> 24), // SampleRate
                (byte)(byteRate), (byte)(byteRate >> 8), (byte)(byteRate >> 16), (byte)(byteRate >> 24), // ByteRate
                (byte)(channels * bitsPerSample / 8), 0, // BlockAlign
                (byte)bitsPerSample, 0, // BitsPerSample
                'd', 'a', 't', 'a', // Subchunk2ID
                (byte)(dataSize), (byte)(dataSize >> 8), (byte)(dataSize >> 16), (byte)(dataSize >> 24) // Subchunk2Size
        });

        // Escribir los datos de audio
        out.write(audioData);
        out.close();
    }


    /***********************************************************************************************
     * Las siguientes fuciones pertenecen al reconocimiento de ruido. Esta programa una respuesta
     * si el ruido es alto
     */
    private void quejarse(){
        // Diversos tipos de respuestas
        int randomResponse = (int) (Math.random() * 3) + 1;

        if (randomResponse == 1) {
            speechControl.hablar("¡Qué escándalo! Bajen el volumen");
        } else if(randomResponse == 2){
            speechControl.hablar("Podéis hablar más bajito, por favor");
        } else if(randomResponse == 3){
            speechControl.hablar("Por favor, bajar el volumen");
        } else {
            speechControl.hablar("Podéis hablar más bajito, por favor");
        }

        // Esperar a volver a escuchar algo alto
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        startDeteccionRuido();
    }
    private float[] convertBytesToFloat(byte[] audioData) {
        short[] audioSamples = new short[audioData.length / 2];
        for (int i = 0; i < audioSamples.length; i++) {
            audioSamples[i] = (short) ((audioData[2 * i] & 0xFF) | (audioData[2 * i + 1] << 8));
        }

        float[] floatSamples = new float[audioSamples.length];
        for (int i = 0; i < audioSamples.length; i++) {
            floatSamples[i] = audioSamples[i] / 32768.0f; // Normalize to [-1.0, 1.0]
        }
        return floatSamples;
    }
    private float calculateRMS(float[] samples) {
        double sum = 0;
        for (float sample : samples) {
            sum += sample * sample;
        }
        return (float) Math.sqrt(sum / samples.length);
    }
    private float calculateDecibels(float rms) {
        if (rms == 0) {
            return -96; // Minimum dB value for 16-bit audio
        }
        return (float) (20 * Math.log10(rms));
    }

    /***********************************************************************************************
    * Pre: Tarea y métodos que se va a ejecutar
    * Post: Realiza la fotografía en el momento y después la envía al servidor para procesarla y
    * obtener la respuesta según la tarea y el method que se esté usando
    */
    public void startRecognition(String task, String method){

        System.out.println("START RECOGNITION");

        Bitmap screenshot = takeScreenshotFromTextureView(tvMedia);

        System.out.println("screenshot: " + screenshot);
        // TODO: PASAR FRAMES DEL VIDEO A LA API
        String base64Image = encodeImageToBase64Bitmap(screenshot);


        if (base64Image != null) {
            // Enviar la imagen al servidor
            new Thread(new Runnable() {
                @Override
                public void run() {
                    sendImageToServerModels(base64Image, task, method);
                }
            }).start();

        }
    }

    /***********************************************************************************************
     * Programa el reconocimiento para que se ejecute cada 15 segundos
     */
    // Activación del reconocimiento
    private boolean reconocimientoActivo = false;
    // Programación del reconocimiento cada 60 segundos
    private long intervaloReconocimiento = 60000;
    private Handler recognitionHandler = new Handler(Looper.getMainLooper());
    private Runnable recognitionTimeoutRunnable;
    private boolean recognitionInProgress = false;


    private final Runnable reconocimientoRunnable = new Runnable() {
        @Override
        public void run() {
            if (reconocimientoActivo) {
                startAllRecognitions();
            }
            recognitionHandler.postDelayed(this, intervaloReconocimiento);
        }
    };

    /**
     * Realiza la fotografía en el momento y después la envía al servidor para procesarla
     * Envía la fotografía al servidor y hace una petición por cada tipo de reconocimiento posible,
     * para así ofrecer una respuesta completa al usuario
     */
    public void startAllRecognitions() {
        System.out.println("START RECOGNITION");

        Bitmap screenshot = takeScreenshotFromTextureView(tvMedia);

        String base64Image = encodeImageToBase64Bitmap(screenshot);

        if (base64Image != null) {
            recognitionInProgress = true;

            // Lanzamos todas las peticiones
            new Thread(() -> {
                sendImageToServerModels(base64Image, "expression", "VGG19");
                sendImageToServerModels(base64Image, "age_gender", "MiVOLO");
                sendImageToServerModels(base64Image, "person_detection", "YOLOv8");
                sendImageToServerModels(base64Image, "face_recognition", "InsightFace");
                sendImageToServerModels(base64Image, "face_detection", "YOLOv8"); // Se usa para disparar en cuanto llega
            }).start();

            // Empezamos un timeout por si tarda mucho
            recognitionTimeoutRunnable = () -> {
                if (recognitionInProgress) {
                    construirYHablarRespuesta();
                }
            };
            recognitionHandler.postDelayed(recognitionTimeoutRunnable, 3000); // 3 segundos
        }
    }

    /**
     * Inicio del reconocimiento periódico
     */
    public void iniciarReconocimientosPeriodicos() {
        reconocimientoActivo = true;
        recognitionHandler.post(reconocimientoRunnable);
    }

    /**
     * Detención del reconocimiento periódico
     */
    public void detenerReconocimientosPeriodicos() {
        reconocimientoActivo = false;
        recognitionHandler.removeCallbacks(reconocimientoRunnable);
    }



    /***********************************************************************************************
     * FUNCIONES CONCRETAS SOBRE RECONOCIMIENTO
     * Realiza fotografía, hace peticion al servidor y reconoce la expresion
     */
    public void recognitionExpresion(){

        System.out.println("START RECOGNITION");

        Bitmap screenshot = takeScreenshotFromTextureView(tvMedia);

        System.out.println("screenshot: " + screenshot);
        // TODO: PASAR FRAMES DEL VIDEO A LA API
        String base64Image = encodeImageToBase64Bitmap(screenshot);

        if (base64Image != null) {
            // Enviar la imagen al servidor
            new Thread(new Runnable() {
                @Override
                public void run() {
                    sendImageToServerModels(base64Image, "expression", "VGG19");
                }
            }).start();

        }
    }

    /**
     * Realiza fotografía, hace peticion al servidor y reconoce la edad y el género
     */
    public void recognitionAgeGender() {

        System.out.println("START RECOGNITION");

        Bitmap screenshot = takeScreenshotFromTextureView(tvMedia);

        System.out.println("screenshot: " + screenshot);
        // TODO: PASAR FRAMES DEL VIDEO A LA API
        String base64Image = encodeImageToBase64Bitmap(screenshot);


        if (base64Image != null) {
            // Enviar la imagen al servidor
            new Thread(new Runnable() {
                @Override
                public void run() {
                    sendImageToServerModels(base64Image, "age_gender", "MiVOLO");
                }
            }).start();

        }
    }

    /**
     * Realiza fotografía, hace peticion al servidor y detecta si hay alguien
     */
    public void recognitionPersonDetection(){

        System.out.println("START RECOGNITION");

        Bitmap screenshot = takeScreenshotFromTextureView(tvMedia);

        System.out.println("screenshot: " + screenshot);
        // TODO: PASAR FRAMES DEL VIDEO A LA API
        String base64Image = encodeImageToBase64Bitmap(screenshot);


        if (base64Image != null) {
            // Enviar la imagen al servidor
            new Thread(new Runnable() {
                @Override
                public void run() {
                    sendImageToServerModels(base64Image, "person_detection", "YOLOv8");
                }
            }).start();

        }
    }

    /**
     * Realiza fotografía, hace peticion al servidor y reconoce a la persona según las fotos almacenadas en el servidor
     */
    public void recognitionPerson(){

        System.out.println("START RECOGNITION");

        Bitmap screenshot = takeScreenshotFromTextureView(tvMedia);

        System.out.println("screenshot: " + screenshot);
        // TODO: PASAR FRAMES DEL VIDEO A LA API
        String base64Image = encodeImageToBase64Bitmap(screenshot);


        if (base64Image != null) {
            // Enviar la imagen al servidor
            new Thread(new Runnable() {
                @Override
                public void run() {
                    sendImageToServerModels(base64Image, "face_recognition", "InsightFace");
                }
            }).start();

        }
    }

    /**
     * Realiza fotografía, hace peticion al servidor y detecta si hay una cara en frente
     */
    public void recognitionFaceDetection(){

        System.out.println("START RECOGNITION");

        Bitmap screenshot = takeScreenshotFromTextureView(tvMedia);

        System.out.println("screenshot: " + screenshot);
        // TODO: PASAR FRAMES DEL VIDEO A LA API
        String base64Image = encodeImageToBase64Bitmap(screenshot);


        if (base64Image != null) {
            // Enviar la imagen al servidor
            new Thread(new Runnable() {
                @Override
                public void run() {
                    sendImageToServerModels(base64Image, "face_detection", "YOLOv8");
                }
            }).start();
        }
    }

    /***********************************************************************************************
     * FUNCIONES PROGRAMAR RECONOCIMIENTOS
     * Pre: Tarea y métodos que se va a ejecutar
     * Post: Cada x tiempo realiza la fotografía en el momento y después la envía al servidor
     * para procesarla.
     */
    public void programarRecognition(String task, String method){

        handler = new Handler();
        runnable = new Runnable() {
            @Override
            public void run() {
                Bitmap screenshot = takeScreenshotFromTextureView(tvMedia);

                String base64Image = encodeImageToBase64Bitmap(screenshot);

                if (base64Image != null) {
                    // Enviar la imagen al servidor
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            sendImageToServerModels(base64Image, task, method);
                        }
                    }).start();

                }
                // Configura el handler para que vuelva a ejecutar este bloque en 30 segundos
                handler.postDelayed(this, 60000); // 60,000 milisegundos = 60 segundos = 1 mins
            }
        };
        // Iniciar el ciclo de preguntas después de una pequeña demora inicial (opcional)
        handler.postDelayed(runnable, 60000);
    }

    /**
     * Pre:
     * Post: Pausa cualquier reconocimiento programado
     */
    public void stopRecognition(){
        handler.removeCallbacks(runnable);
    }

    /**
     * Cada 60 segundos realiza la fotografía y la envía al servidor
     * para procesarla y reconocer la expresion del usuario
     */
    public void programarRecognitionExpression(){

        handler = new Handler();
        runnable = new Runnable() {
            @Override
            public void run() {
                recognitionExpresion();
                // Configura el handler para que vuelva a ejecutar este bloque en 30 segundos
                handler.postDelayed(this, 60000); // 60,000 milisegundos = 60 segundos = 1 mins
            }
        };
        // Iniciar el ciclo de preguntas después de una pequeña demora inicial (opcional)
        handler.postDelayed(runnable, 60000);
    }

    /**
     * Cada 60 segundos realiza la fotografía y la envía al servidor
     * para procesarla y detectar la edad y el genero del usuario
     */
    public void programarRecognitionAgeGender(){

        handler = new Handler();
        runnable = new Runnable() {
            @Override
            public void run() {
                recognitionAgeGender();
                // Configura el handler para que vuelva a ejecutar este bloque en 30 segundos
                handler.postDelayed(this, 60000); // 60,000 milisegundos = 60 segundos = 1 mins
            }
        };
        // Iniciar el ciclo de preguntas después de una pequeña demora inicial (opcional)
        handler.postDelayed(runnable, 60000);
    }

    /**
     * Cada 60 segundos realiza la fotografía y la envía al servidor
     * para procesarla y detectar a la persona
     */
    public void programarRecognitionPerson(){

        handler = new Handler();
        runnable = new Runnable() {
            @Override
            public void run() {
                recognitionPerson();
                // Configura el handler para que vuelva a ejecutar este bloque en 30 segundos
                handler.postDelayed(this, 60000); // 60,000 milisegundos = 60 segundos = 1 mins
            }
        };
        // Iniciar el ciclo de preguntas después de una pequeña demora inicial (opcional)
        handler.postDelayed(runnable, 60000);
    }

    /**
     * Cada 60 segundos realiza la fotografía y la envía al servidor
     * para procesarla y detectar si hay alguna persona frente a la cámara
     */
    public void programarRecognitionPersonDetection(){

        handler = new Handler();
        runnable = new Runnable() {
            @Override
            public void run() {
                recognitionPersonDetection();
                // Configura el handler para que vuelva a ejecutar este bloque en 30 segundos
                handler.postDelayed(this, 60000); // 60,000 milisegundos = 60 segundos = 1 mins
            }
        };
        // Iniciar el ciclo de preguntas después de una pequeña demora inicial (opcional)
        handler.postDelayed(runnable, 60000);
    }

    /**
     * Cada 30 segundos realiza la fotografía y la envía al servidor
     * para procesarla y detectar si hay un rostro frente a la cámara
     */
    public void programarRecognitionFaceDetection(){

        handler = new Handler();
        runnable = new Runnable() {
            @Override
            public void run() {
                recognitionFaceDetection();
                // Configura el handler para que vuelva a ejecutar este bloque en 30 segundos
                handler.postDelayed(this, 30000); // 60,000 milisegundos = 60 segundos = 1 mins
            }
        };
        // Iniciar el ciclo de preguntas después de una pequeña demora inicial (opcional)
        handler.postDelayed(runnable, 30000);
    }


    /***********************************************************************************************
     * FUNCIONES SOBRE TOMAR FOTOGRAFÍA Y ENVIAR A SERVIDOR
     * Pre:
     * Post: Realiza una fotografía y la devuelve en formato base64
     */
    public String takePhoto(){

        System.out.println("TAKE PHOTO");

        Bitmap screenshot = takeScreenshotFromTextureView(tvMedia);

        System.out.println("screenshot: " + screenshot);

        return encodeImageToBase64Bitmap(screenshot);

    }
    public Bitmap takeScreenshotFromTextureView(TextureView textureView) {
        // Verificar si el TextureView está disponible
        if (textureView.isAvailable()) {
            System.out.println("screen avaiables");
            return textureView.getBitmap();
        }
        return null;
    }
    public String encodeImageToBase64Bitmap(Bitmap resizedBitmap) {
        try {

            // Redimensiona la imagen antes de codificarla
            //TODO: PROBAR A HACER UN RESIZE
            //Bitmap resizedBitmap = resizeImage(imageUri, 800, 600); // Ejemplo: máximo 800x600 píxeles

            if (resizedBitmap == null) {
                System.out.println("Error al redimensionar la imagen.");
                return null;
            }

            // Convierte el Bitmap a un array de bytes
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            resizedBitmap.compress(Bitmap.CompressFormat.PNG, 80, byteArrayOutputStream); // Compresión a calidad 80%
            byte[] imageBytes = byteArrayOutputStream.toByteArray();

            // Codifica los bytes de la imagen en Base64
            String base64String = Base64.encodeToString(imageBytes, Base64.NO_WRAP); // Evita saltos de línea

            System.out.println("Base64: " + base64String);

            base64String = base64String.trim(); // Elimina espacios en blanco alrededor
            base64String = base64String.replace("\n", "").replace("\r", ""); // Elimina saltos de línea

            // Validar que la longitud sea múltiplo de 4
            int remainder = base64String.length() % 4;
            if (remainder != 0) {
                int padding = 4 - remainder; // Calcula el relleno necesario
                for (int i = 0; i < padding; i++) {
                    base64String += "="; // Añade '=' al final de la cadena
                }
            }

            System.out.println("Base64 ESTAAA: " + base64String);

            return base64String;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Pre: Imagen en formato base64, tarea y metodo que se va a ejecutar
     * Post: Envia la imagen al servidor para procesarla
     */
    public void sendImageToServerModels(String base64Image, String task, String method) {
        try {

            // URL de destino
            URL url = new URL("http://155.210.155.206:8080/sendImage?task="+task+"&method="+method+"&mode=text");

            // Abrir la conexión HTTP
            HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.setRequestMethod("POST");
            urlConnection.setRequestProperty("Content-Type", "application/json");
            urlConnection.setDoOutput(true);

            // Crear el cuerpo de la solicitud
            DataOutputStream os = new DataOutputStream(urlConnection.getOutputStream());
            os.write(base64Image.getBytes()); // Escribir los bytes decodificados
            os.flush();
            os.close();

            System.out.println("dataoutputstring: " + os);
            System.out.println("url: " + url);

            // Leer la respuesta
            int code = urlConnection.getResponseCode();
            if (code == HttpURLConnection.HTTP_OK) {
                BufferedReader in = new BufferedReader(new InputStreamReader(urlConnection.getInputStream()));
                String inputLine;
                StringBuilder response = new StringBuilder();

                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                in.close();

                System.out.println("Respuesta del servidor: " + response);

                //procesarRespuesta(response.toString(), task);
                procesarRespuestaAll(response.toString(), task);

            } else if( code == HttpURLConnection.HTTP_NOT_FOUND){
                System.out.println("Error: " + urlConnection.getResponseMessage());

            } else if (code == HttpURLConnection.HTTP_INTERNAL_ERROR){
                System.out.println("Error: " + urlConnection.getResponseMessage());

            }else {
                // Manejar errores
                Log.e("MyTag", "Error: " + urlConnection.getResponseMessage());
            }

            urlConnection.disconnect();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void procesarRespuesta(String respuesta, String task) {
        // TODO: CAMBIAR EMOCIONES
        if (Objects.equals(task, "expression")) {
            if (respuesta.equals("happiness")) {
                speechControl.hablar("Hola, pareces feliz");
            } else if (respuesta.equals("sadness")) {
                speechControl.hablar("Hoy parecer un poco triste ¿necesitas ayuda en algo?");
            } else if (respuesta.equals("anger")) {
                speechControl.hablar("Eh! No te enfades conmigo, yo no tengo la culpa");
            } else if (respuesta.equals("fear") ) {
                speechControl.hablar("¿Tienes miedo de mi?");
            } else if (respuesta.equals("disgust")) {
                speechControl.hablar("Parece discustado ¿ocurre algo?");
            } else if (respuesta.equals("surprise")) {
                speechControl.hablar("¿Te sorprendi con mis habilidades?");
            }
        } else if (Objects.equals(task, "age_gender")) {
            // La respuesta contiene la edad y el género (por ejemplo, (22, 'female')
            String[] partes = respuesta.split(",");
            String edad = partes[0].trim();
            String genero = partes[1].trim();

            if (genero.equals("'female')")) {
                genero = "mujer";
            } else if (genero.equals("'male')")) {
                genero = "hombre";
            }

            System.out.println("Edad: " + edad + ", Género: " + genero);

            speechControl.hablar("Tu edad es " + edad + " y tu genero es " + genero);
        } else if (Objects.equals(task, "face_recognition")) {
            // Respuesta recibida Respuesta del servidor: ../user_faces\loreto.jpg
            // Reemplaza caracter \ por /
            respuesta = respuesta.replace("\\", "/");
            System.out.println("Respuesta reemplazada: " + respuesta);

            String[] partes = respuesta.split("/");
            String parte2 = partes[2].trim();
            String[] partes2 = parte2.split("\\.");
            String nombre = partes2[0].trim();

            speechControl.hablar("Hola, parece que eres " + nombre);
            System.out.println("Nombre: " + nombre);

        } else if( Objects.equals(task, "person_detection")) {
            speechControl.hablar("Hola ¿Hay alguien ahí?");
        } else if( Objects.equals(task, "face_detection")) {
            speechControl.hablar("Hola, espera un momento, voy a adivinar tu edad y género, mira a la cámara fijamente");
            recognitionAgeGender();
        }
    }

    public void procesarRespuestaAll(String respuesta, String task) {
        try {
            String jsonCompatible = respuesta
                    .replace("array(", "")
                    .replace("dtype=float32", "")
                    .replace("'", "\"")
                    .replace(")", "")
                    .replace("nan", "0") // por si hay valores no numéricos
                    .replace(", ,", ",")              // elimina dobles comas
                    .replace(",]", "]")              // elimina coma antes de cierre
                    .replace("[,", "[")              // elimina coma después de apertura
                    .replace("'", "\"")
                    .replaceAll("\\s+", " ");

            JSONArray jsonArray = new JSONArray(jsonCompatible);

            if (task.equals("expression")) {
                for (int i = 0; i < jsonArray.length(); i++) {
                    JSONObject obj = jsonArray.getJSONObject(i);
                    System.out.println("EXPRESSION obj: " + obj);
                    if (obj.has("expression")) {
                        System.out.println("EXPRESSION HA ENTRADO y la emocion es: " + obj.getString("expression"));
                        String emotion = obj.getString("expression");
                        resultadoReconocimiento.emocion = emotion;
                    }
                }

            } else if (task.equals("age_gender")) {
                for (int i = 0; i < jsonArray.length(); i++) {
                    JSONObject obj = jsonArray.getJSONObject(i);
                    if (obj.has("age") && obj.has("gender")) {
                        resultadoReconocimiento.edad = String.valueOf(obj.getInt("age"));
                        resultadoReconocimiento.genero = obj.getString("gender");
                        break; // Solo tomamos el primero
                    }
                }

            } else if (task.equals("face_recognition")) {
                for (int i = 0; i < jsonArray.length(); i++) {
                    JSONObject obj = jsonArray.getJSONObject(i);
                    if (obj.has("face_recognition")) {
                        String ruta = obj.getString("face_recognition").replace("\\", "/");
                        String[] partes = ruta.split("/");
                        if (partes.length > 2) {
                            String nombre = partes[2].split("\\.")[0];
                            resultadoReconocimiento.nombreReconocido = nombre;
                        }
                    }
                }

            } else if (task.equals("face_detection")) {
                if (recognitionInProgress) {
                    recognitionHandler.removeCallbacks(recognitionTimeoutRunnable);
                    construirYHablarRespuesta();
                }

            } else if (task.equals("person_detection")) {
                if (jsonArray.length() > 0) {
                    resultadoReconocimiento.personaDetectada = true;
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }



    private void construirYHablarRespuesta() {
        //Ver el objeto reconocido
        System.out.println("RECONOCIMIENTO: " + resultadoReconocimiento.emocion + " " + resultadoReconocimiento.edad + " " + resultadoReconocimiento.genero);

        recognitionInProgress = false;

        StringBuilder respuesta = new StringBuilder();

        if (resultadoReconocimiento.personaDetectada) {
            respuesta.append("He detectado una persona. ");
        }

        if (resultadoReconocimiento.nombreReconocido != null) {
            respuesta.append("Parece que eres ").append(resultadoReconocimiento.nombreReconocido).append(". ");
        }

        if (resultadoReconocimiento.edad != null && resultadoReconocimiento.genero != null) {
            String genero = resultadoReconocimiento.genero.equals("female") ? "mujer" : "hombre";
            respuesta.append("Tu edad parece ser ").append(resultadoReconocimiento.edad)
                    .append(" años y tu género es ").append(genero).append(". ");
        }

        if (resultadoReconocimiento.emocion != null) {
            switch (resultadoReconocimiento.emocion) {
                case "happiness":
                    respuesta.append("Te ves feliz. ");
                    break;
                case "sadness":
                    respuesta.append("Pareces algo triste. ¿Necesitas ayuda? ");
                    break;
                case "anger":
                    respuesta.append("Te noto un poco enfadado. ");
                    break;
                case "fear":
                    respuesta.append("¿Estás asustado? ");
                    break;
                case "disgust":
                    respuesta.append("Detecto cierto disgusto. ");
                    break;
                case "surprise":
                    respuesta.append("Te ves sorprendido. ");
                    break;
            }
        }

        if (respuesta.length() > 0) {
            speechControl.hablar(respuesta.toString());
        }

        // Reiniciar para la próxima detección
        resultadoReconocimiento = new ResultadoReconocimiento();
    }










    /**
     * 显示视频流
     *
     * @param sampleData
     */
    private void showViewData(ByteBuffer sampleData) {
        try {
            int inIndex = mediaCodec.dequeueInputBuffer(decodeTimeout);
            if (inIndex >= 0) {
                ByteBuffer buffer = videoInputBuffers[inIndex];
                int sampleSize = sampleData.limit();
                buffer.clear();
                buffer.put(sampleData);
                buffer.flip();
                mediaCodec.queueInputBuffer(inIndex, 0, sampleSize, 0, 0);
            }
            int outputBufferId = mediaCodec.dequeueOutputBuffer(videoBufferInfo, decodeTimeout);
            if (outputBufferId >= 0) {
                mediaCodec.releaseOutputBuffer(outputBufferId, true);
            } else {
                Log.e(TAG, "dequeueOutputBuffer() error");
            }

        } catch (Exception e) {
            Log.e(TAG, "发生错误", e);
        }
    }
    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
        this.surfaceTexture = surfaceTexture;
        this.surface = new Surface(surfaceTexture);

        // Configure stream options and open media stream
        StreamOption streamOption = new StreamOption();
        streamOption.setChannel(StreamOption.MAIN_STREAM);
        streamOption.setDecodType(StreamOption.HARDWARE_DECODE);
        streamOption.setJustIframe(false);
        mediaManager.openStream(streamOption);

        // Configure MediaCodec
        startDecoding(this.surface);
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
        // Handle size changes if necessary
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
        // Close media stream and stop decoding
        mediaManager.closeStream();
        stopDecoding();
        if (surface != null) {
            surface.release();
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
        // Called when the content of the TextureView is updated
    }
    /**
     * 初始化视频编解码器
     *
     * @param surface
     */
    private void startDecoding(Surface surface) {
        if (mediaCodec != null) {
            return;
        }
        try {
            mediaCodec = MediaCodec.createDecoderByType("video/avc");
            MediaFormat format = MediaFormat.createVideoFormat(
                    "video/avc", 1280, 720);
            mediaCodec.configure(format, surface, null, 0);
            mediaCodec.start();
            videoInputBuffers = mediaCodec.getInputBuffers();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }
    /**
     * 结束视频编解码器
     */
    private void stopDecoding() {
        if (mediaCodec != null) {
            mediaCodec.stop();
            mediaCodec.release();
            mediaCodec = null;
            Log.i(TAG, "stopDecoding");
        }
        videoInputBuffers = null;
    }



}




