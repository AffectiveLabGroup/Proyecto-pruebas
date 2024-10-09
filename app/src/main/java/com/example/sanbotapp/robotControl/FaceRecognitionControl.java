package com.example.sanbotapp.robotControl;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Environment;
import android.widget.ImageView;

import com.google.gson.Gson;
import com.qihancloud.opensdk.base.BindBaseActivity;
import com.qihancloud.opensdk.beans.FuncConstant;
import com.qihancloud.opensdk.function.beans.FaceRecognizeBean;
import com.qihancloud.opensdk.function.unit.MediaManager;
import com.qihancloud.opensdk.function.unit.SpeechManager;
import com.qihancloud.opensdk.function.unit.interfaces.media.FaceRecognizeListener;

import java.io.File;
import java.util.List;
import java.util.Random;

public class FaceRecognitionControl {

    private MediaManager mediaManager;
    private SpeechControl speechControl;



    public FaceRecognitionControl(SpeechManager speechManager, MediaManager mediaManager){
        this.mediaManager = mediaManager;
        this.speechControl = new SpeechControl(speechManager);
    }

    public void startFaceRecognition() {
        mediaManager.setMediaListener(new FaceRecognizeListener() {
            @Override
            public void recognizeResult(List<FaceRecognizeBean> list) {
                StringBuilder sb = new StringBuilder();
                for (FaceRecognizeBean bean : list) {
                    sb.append(new Gson().toJson(bean));
                    sb.append("\n");


                    // Acceder al valor de la propiedad "user"
                    String user = bean.getUser();
                    // Hacer algo con el valor de "user"
                    System.out.println("Usuario reconocido: " + user);



                    if(user != ""){

                        String[] frases = {
                                "Hola " + user + ", ¿cómo estás?",
                                "¡Qué gusto verte " + user + "!",
                                "Hola " + user + ", espero que estés teniendo un buen día.",
                                "¡Hola " + user + "! ¿Cómo te va?",
                                "Hola " + user + ", ¿qué tal todo?"
                        };

                        // Seleccionar una frase aleatoria
                        Random random = new Random();
                        String fraseAleatoria = frases[random.nextInt(frases.length)];

                        speechControl.hablar(fraseAleatoria);
                        while(speechControl.isRobotHablando()){
                            // Esperar a que termine de hablar
                            try {
                                Thread.sleep(100); // Esperar 100ms antes de volver a comprobar
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                    }

                }
                System.out.println("Persona reconocida????：" + sb.toString());


            }
        });
    }

    // Parar reconocimiento facial
    public void stopFaceRecognition() {
        mediaManager.setMediaListener(new FaceRecognizeListener() {
            @Override
            public void recognizeResult(List<FaceRecognizeBean> list) {

            }
        });
    }

    private static File getDiskCacheDir(Context context, String uniqueName) {
        String cachePath;
        if (Environment.MEDIA_MOUNTED.equals(Environment
                .getExternalStorageState())
                || !Environment.isExternalStorageRemovable()) {
            cachePath = context.getExternalCacheDir().getPath();
        } else {
            cachePath = context.getCacheDir().getPath();
        }
        return new File(cachePath + File.separator + uniqueName);
    }
}




