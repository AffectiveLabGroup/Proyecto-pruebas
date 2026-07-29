package com.example.sanbotapp;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import com.example.sanbotapp.moduloReactivo.EmotionMirrorPhoto;
import com.example.sanbotapp.moduloReactivo.EmotionMirrorStream;
import com.example.sanbotapp.robotControl.HardwareControl;
import com.example.sanbotapp.robotControl.HeadControl;
import com.example.sanbotapp.robotControl.SpeechControl;
import com.example.sanbotapp.robotControl.SystemControl;
import com.example.sanbotapp.robotControl.WheelControl;
import com.qihancloud.opensdk.base.TopBaseActivity;
import com.qihancloud.opensdk.beans.FuncConstant;
import com.qihancloud.opensdk.function.beans.EmotionsType;
import com.qihancloud.opensdk.function.beans.LED;
import com.qihancloud.opensdk.function.unit.HardWareManager;
import com.qihancloud.opensdk.function.unit.HeadMotionManager;
import com.qihancloud.opensdk.function.unit.MediaManager;
import com.qihancloud.opensdk.function.unit.SpeechManager;
import com.qihancloud.opensdk.function.unit.SystemManager;
import com.qihancloud.opensdk.function.unit.WheelMotionManager;
import com.qihancloud.opensdk.function.unit.HandMotionManager;


public class MainActivity extends TopBaseActivity {

    private static final String TAG = "MainActivity";

    // ── Managers del SDK ──────────────────────────────────────────────────────
    private SpeechManager    speechManager;
    private MediaManager     mediaManager;
    private SystemManager    systemManager;
    private HeadMotionManager headMotionManager;
    private WheelMotionManager wheelMotionManager;
    private HardWareManager  hardWareManager;
    private HandMotionManager handMotionManager;

    // ── Clases de control ─────────────────────────────────────────────────────
    private SpeechControl    speechControl;
    private SystemControl    systemControl;
    private HeadControl      headControl;
    private WheelControl     wheelControl;
    private HardwareControl  hardwareControl;

    // ── Módulo de emociones ───────────────────────────────────────────────────
    private RobotEmotionManager  emotionManager;
    private EmotionMirrorStream  emotionMirrorStream;
    private EmotionMirrorPhoto   emotionMirrorPhoto;

    // ── Vistas ────────────────────────────────────────────────────────────────
    private TextureView tvMedia;
    private TextView    tvEstado;       // muestra la emoción detectada en pantalla

    // ── Botones originales ────────────────────────────────────────────────────
    private Button ledOn, ledOff;
    private Button headLeft, headRight, headUp, headDown, headCenter;
    private Button buttonSayHi, buttonWheelForward, setEmotion;

    // ── Botones nuevos de emociones ───────────────────────────────────────────
    private Button btnModoStream;   // activa/desactiva el stream continuo
    private Button btnModoFoto;     // hace una foto y reacciona una vez

    private boolean streamActivo = false;

    private Handler handler = new Handler(Looper.getMainLooper());

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onMainServiceConnected() {
        // No es necesario hacer nada aquí; la inicialización va en onCreate
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onCreate(savedInstanceState);
        onMainServiceConnected();
        setContentView(R.layout.activity_main);

        inicializarManagers();
        inicializarVistas();
        inicializarModuloEmociones();
        setOnClicks();
    }

    @Override
    public void onDestroy() {
        // Paramos el stream si estaba activo para no dejar hilos huérfanos
        if (emotionMirrorStream != null && streamActivo) {
            emotionMirrorStream.detener();
        }
        super.onDestroy();
    }

    // ── Inicialización ────────────────────────────────────────────────────────

    private void inicializarManagers() {
        speechManager      = (SpeechManager)      getUnitManager(FuncConstant.SPEECH_MANAGER);
        mediaManager       = (MediaManager)        getUnitManager(FuncConstant.MEDIA_MANAGER);
        systemManager      = (SystemManager)       getUnitManager(FuncConstant.SYSTEM_MANAGER);
        headMotionManager  = (HeadMotionManager)   getUnitManager(FuncConstant.HEADMOTION_MANAGER);
        wheelMotionManager = (WheelMotionManager)  getUnitManager(FuncConstant.WHEELMOTION_MANAGER);
        hardWareManager    = (HardWareManager)     getUnitManager(FuncConstant.HARDWARE_MANAGER);
        handMotionManager  = (HandMotionManager)   getUnitManager(FuncConstant.HANDMOTION_MANAGER);

        speechControl   = new SpeechControl(speechManager);
        systemControl   = new SystemControl(systemManager);
        headControl     = new HeadControl(headMotionManager);
        wheelControl    = new WheelControl(wheelMotionManager);
        hardwareControl = new HardwareControl(hardWareManager);
    }

    private void inicializarVistas() {
        tvMedia   = findViewById(R.id.tv_media);
        tvEstado  = findViewById(R.id.tvEstado);      // TextView para mostrar la emoción detectada

        // Botones nuevos — añádelos también al layout XML con estos IDs
        btnModoStream = findViewById(R.id.btnModoStream);
        btnModoFoto   = findViewById(R.id.btnModoFoto);
    }

    private void inicializarModuloEmociones() {
        // Gestor central de emociones del robot (RobotEmotionManager)
        emotionManager = new RobotEmotionManager(
                this,
                systemManager,
                hardWareManager,
                speechManager,
                headMotionManager,
                handMotionManager,
                wheelMotionManager
        );

        // Modo stream continuo — la cámara queda vinculada a tvMedia
        emotionMirrorStream = new EmotionMirrorStream(
                mediaManager,
                tvMedia,
                emotionManager
        );

        // Modo foto única — comparte la misma tvMedia
        emotionMirrorPhoto = new EmotionMirrorPhoto(
                mediaManager,
                speechManager,
                tvMedia,
                emotionManager
        );

        // Callback del modo foto: actualizar UI cuando llega el resultado
        emotionMirrorPhoto.setOnResultadoListener(new EmotionMirrorPhoto.OnResultadoListener() {
            @Override
            public void onEmocionDetectada(String emocion, double confianza) {
                mostrarEstado("Detectado: " + emocion + " (" + (int)(confianza * 100) + "%)");
                btnModoFoto.setEnabled(true);   // volver a habilitar el botón
            }
            @Override
            public void onSinCara() {
                mostrarEstado("Sin cara detectada");
                btnModoFoto.setEnabled(true);
            }
            @Override
            public void onError(String motivo) {
                mostrarEstado("Error: " + motivo);
                btnModoFoto.setEnabled(true);
                Log.e(TAG, "Error en modo foto: " + motivo);
            }
        });
    }

    // ── OnClicks ──────────────────────────────────────────────────────────────

    public void setOnClicks() {

        btnModoStream.setOnClickListener(v -> {
            if (!streamActivo) {
                // Si el modo foto estaba ocupado, no permitimos activar stream
                if (emotionMirrorPhoto.isOcupado()) {
                    speechControl.hablar("Espera, estoy procesando una foto");
                    return;
                }
                streamActivo = true;
                btnModoStream.setText("Detener stream");
                btnModoFoto.setEnabled(false);     // los dos modos son excluyentes
                mostrarEstado("Stream activo...");
                emotionMirrorStream.iniciar();

            } else {
                streamActivo = false;
                btnModoStream.setText("Iniciar stream");
                btnModoFoto.setEnabled(true);
                mostrarEstado("Stream detenido");
                emotionMirrorStream.detener();
            }
        });


        btnModoFoto.setOnClickListener(v -> {
            if (streamActivo) {
                speechControl.hablar("Primero detén el stream");
                return;
            }
            // Deshabilitamos el botón hasta que llegue el resultado por el callback
            btnModoFoto.setEnabled(false);
            mostrarEstado("Analizando...");
            emotionMirrorPhoto.tomarFotoYReaccionar();
        });
    }




    /** Actualiza el TextView de estado en el hilo UI. */
    private void mostrarEstado(String texto) {
        runOnUiThread(() -> {
            if (tvEstado != null) tvEstado.setText(texto);
            Log.d(TAG, texto);
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_settings) return true;
        return super.onOptionsItemSelected(item);
    }
}