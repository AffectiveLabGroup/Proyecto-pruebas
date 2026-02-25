package com.example.sanbotapp;


import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import com.example.sanbotapp.moduloReactivo.RecognitionControl;
import com.example.sanbotapp.robotControl.FaceRecognitionControl;
import com.example.sanbotapp.robotControl.HardwareControl;
import com.example.sanbotapp.robotControl.HeadControl;
import com.example.sanbotapp.robotControl.MediaControlActivity;
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

import org.json.JSONObject;

import java.net.URISyntaxException;

import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;

public class MainActivity extends TopBaseActivity {

    private SpeechControl speechControl;
    private FaceRecognitionControl faceRecognitionControl;
    private SpeechManager speechManager;
    private MediaManager mediaManager;
    private SystemControl systemControl;
    private SystemManager systemManager;
    private HeadControl headControl;
    private HeadMotionManager headMotionManager;
    private WheelControl wheelControl;
    private WheelMotionManager wheelMotionManager;
    private HardWareManager hardWareManager;
    private HardwareControl hardwareControl;
    private TextView textoreconocido;
    private RecognitionControl recognitionControl;
    private VoskRecognition voskRecognition;

    TextureView tvMedia;

    private Handler handler = new Handler(Looper.getMainLooper());

    Button ledOn, ledOff, headLeft, headRight,
            headUp, headDown, buttonSayHi, buttonWheelForward,
            setEmotion, headCenter, media, escuchar, decirfrase, escuchawav;
    private Socket mSocket;

    {
        try {
            IO.Options opts = new IO.Options();
            opts.transports = new String[] {"websocket"}; // Solo WebSocket
            mSocket = IO.socket("http://robot-server-flask.onrender.com", opts);

        } catch (URISyntaxException e) {
            System.out.println("Error al crear el socket");
            e.printStackTrace();
        }
    }

    private boolean reconocimientoActivo = false;
    private boolean yaEnviado = false;
    private static final int TIEMPO_ACTIVO_MS = 8000; // 8 segundos activo




    @Override
    protected void onMainServiceConnected() {

    }


    @Override
    public void onCreate(Bundle savedInstanceState) {

        getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onCreate(savedInstanceState);
        onMainServiceConnected();
        setContentView(R.layout.activity_main);

        speechManager = (SpeechManager) getUnitManager(FuncConstant.SPEECH_MANAGER);
        mediaManager = (MediaManager) getUnitManager(FuncConstant.MEDIA_MANAGER);
        systemManager = (SystemManager) getUnitManager(FuncConstant.SYSTEM_MANAGER);
        speechControl = new SpeechControl(speechManager);
        faceRecognitionControl = new FaceRecognitionControl(speechManager, mediaManager);
        systemControl = new SystemControl(systemManager);
        headMotionManager = (HeadMotionManager) getUnitManager(FuncConstant.HEADMOTION_MANAGER);
        headControl = new HeadControl(headMotionManager);
        wheelMotionManager = (WheelMotionManager) getUnitManager(FuncConstant.WHEELMOTION_MANAGER);
        wheelControl = new WheelControl(wheelMotionManager);
        hardWareManager = (HardWareManager) getUnitManager(FuncConstant.HARDWARE_MANAGER);
        hardwareControl = new HardwareControl(hardWareManager);

        ledOn = findViewById(R.id.ledOn);
        ledOff = findViewById(R.id.ledOff);
        headLeft = findViewById(R.id.headLeft);
        headRight = findViewById(R.id.headRight);
        headUp = findViewById(R.id.headUp);
        headDown = findViewById(R.id.headDown);
        buttonSayHi = findViewById(R.id.buttonSayHi);
        buttonWheelForward = findViewById(R.id.buttonWheelForward);
        setEmotion = findViewById(R.id.setEmotion);
        headCenter = findViewById(R.id.headCenter);
        media = findViewById(R.id.media);
        escuchar = findViewById(R.id.escuchar);
        textoreconocido = findViewById(R.id.textoreconocido);
        decirfrase = findViewById(R.id.decirfrase);
        tvMedia = findViewById(R.id.tv_media);
        escuchawav = findViewById(R.id.wav);
        voskRecognition = new VoskRecognition();


        recognitionControl = new RecognitionControl(speechManager, mediaManager, tvMedia, this, voskRecognition);
        recognitionControl.startDeteccionIsSpeaking();


        setonClicks();

        escuchar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new Thread(new Runnable() {
                    public void run(){
                        //speechControl.modoEscucha();
                        speechControl.iniciar();

                    }
                }).start();
            }
        });

        decirfrase.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                speechControl.hablar("Hola, soy Sanbot, ¿cómo estás?");
            }
        });

        //TODO: ESCUCHA AUDIO Y RESPUESTA
        escuchawav.setOnClickListener(new View.OnClickListener() {
              @Override
              public void onClick(View v) {
                  Log.d("ServerLive", "PULSA BOTÓN");
                  //recognitionControl.audiowav();
                  recognitionControl.activarReconocimiento();

              }
        });      

        mSocket.connect();

        socketFunctions("b", "Hola A! soy b");

        voskRecognition.startRecognition(this, new VoskRecognition.VoskListener() {
            @Override
            public void onResult(String result) {
                Log.d("VOSK", "✅ Resultado final: " + result);
            }

            @Override
            public void onPartialResult(String partial) {
                Log.d("VOSK", "🟡 Parcial: " + partial);
            }

            @Override
            public void onError(String error) {
                Log.e("VOSK", "❌ Error: " + error);
            }
        });

    }

    public void socketFunctions(String robot, String message){

        mSocket.on("receive_message", new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                JSONObject data = (JSONObject) args[0];
                String robotm = data.optString("robot");
                String message = data.optString("message");

                if (robot.equals(robotm)) {// solo mostramos si el mensaje es para A
                    Log.i("Socket", "Mensaje recibido para" + robotm + ": " + message);

                    speechControl.hablar("He recibido un mensaje de " + robotm + ": " + message);
                }
            }
        });

        // Pedir mensaje pendiente
        /*JSONObject pedir = new JSONObject();
        try {
            pedir.put("robot", "b");
            mSocket.emit("request_message", pedir);
        } catch (Exception e) {
            e.printStackTrace();
        }*/

        Button btnEnviar = findViewById(R.id.sendButton);
        btnEnviar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                JSONObject enviar = new JSONObject();
                try {
                    enviar.put("robot", robot);
                    enviar.put("message", message);  // o desde B si es ese robot
                    mSocket.emit("send_message", enviar);
                    Log.i("Socket", "Mensaje enviado");
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });

    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        mSocket.disconnect();
    }


    public void setonClicks() {
        media.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = null;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN) {
                    intent = new Intent(MainActivity.this, MediaControlActivity.class);
                }
                startActivity(intent);
            }
        });

        setEmotion.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                systemControl.cambiarEmocion(EmotionsType.FAINT);
            }
        });
        ledOn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                hardwareControl.encenderLED(LED.PART_ALL, LED.MODE_BLUE);
            }
        });
        ledOff.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                hardwareControl.apagarLED(LED.PART_ALL);
            }
        });
        headLeft.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                headControl.controlBasicoCabeza(HeadControl.AccionesCabeza.IZQUIERDA);
            }
        });
        headRight.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                headControl.controlBasicoCabeza(HeadControl.AccionesCabeza.DERECHA);
            }
        });
        headUp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                headControl.controlBasicoCabeza(HeadControl.AccionesCabeza.ARRIBA);
            }
        });
        headDown.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                headControl.controlBasicoCabeza(HeadControl.AccionesCabeza.ABAJO);
            }
        });
        headCenter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                headControl.reiniciar();
            }
        });

        buttonSayHi.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                speechControl.hablar("Hola, soy Sanbot, ¿cómo estás?");
            }
        });


    }






    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }


}
