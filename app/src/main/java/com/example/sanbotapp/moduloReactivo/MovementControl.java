package com.example.sanbotapp.moduloReactivo;

import android.content.Context;
import android.os.Handler;
import android.util.Log;

import com.example.sanbotapp.robotControl.HandsControl;
import com.example.sanbotapp.robotControl.HeadControl;
import com.example.sanbotapp.robotControl.SpeechControl;
import com.example.sanbotapp.robotControl.WheelControl;
import com.qihancloud.opensdk.function.unit.ModularMotionManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;

public class MovementControl {

    private SpeechControl speechControl;
    private WheelControl wheelControl;
    private HeadControl headControl;
    private HandsControl handsControl;
    private ModularMotionManager modularMotionManager;
    private Context context;
    Runnable runnable;
    Handler handler = new Handler();

    boolean primeraVez = true;
    boolean segundaVez = false;

    boolean primeraVezTorso = true;
    boolean segundaVezTorso = false;

    boolean desplazamientoAvanzar = false;

    private Timer timer;


    public MovementControl(ModularMotionManager modularMotionManager, Context context, WheelControl wheelControl, HeadControl headControl, HandsControl handsControl){
        this.modularMotionManager = modularMotionManager;
        this.context = context;
        this.wheelControl = wheelControl;
        this.headControl = headControl;
        this.handsControl = handsControl;
    }


    /**
     * Activar movimiento aleoatorio / comienza a caminar
     */
    public void activarMovimientoAleatorio(){
        modularMotionManager.switchWander(true);
    }

    /**
     * Desactivar movimiento aleoatorio / comienza a caminar
     */
    public void desactivarMovimientoAleatorio(){
        modularMotionManager.switchWander(false);
    }

    /**
     * Activar movimiento aleatorio con wheels
     */
    public void activarMovimientoAleatorioWheels() {
        Random random = new Random();
        timer = new Timer();

        TimerTask movimientoSimple = new TimerTask() {
            @Override
            public void run() {
                int accion = random.nextInt(3); // 0 = cabeza, 1 = cuerpo derecha, 2 = cuerpo izquierda

                if (accion == 0) { // Giro de cabeza simple, mover la cabeza unos 45 grados

                    int primerAngulo = 135;
                    int segundoAngulo = 45;


                    if(primeraVez){
                        headControl.girarCabeza(primerAngulo);
                        primeraVez = false;
                        segundaVez = true;
                    }else if(segundaVez){
                        headControl.girarCabeza(segundoAngulo);
                        segundaVez = false;
                        primeraVez = true;
                    }

                    try {
                        Thread.sleep(10000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }

                    headControl.girarCabeza(90);


                } else if (accion == 1) { // Giro de torso
                    // Gira un poco el cuerpo sin dar la espalda entre 0 y 45 grados
                    int angulo = 30;

                    if(primeraVezTorso){
                        wheelControl.controlBasicoRuedasLento(WheelControl.AccionesRuedas.IZQUIERDA, angulo);
                    }else if(segundaVezTorso){
                        wheelControl.controlBasicoRuedasLento(WheelControl.AccionesRuedas.DERECHA, angulo);
                    }


                    // Espera un poco y vuelve a la posición inicial
                    try {
                        Thread.sleep(10000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    //El ángulo que has girado lo recuperas hacia el lado contrario
                    if(primeraVezTorso){
                        wheelControl.controlBasicoRuedasLento(WheelControl.AccionesRuedas.DERECHA, angulo);
                        primeraVezTorso = false;
                        segundaVezTorso = true;
                    }else{
                        wheelControl.controlBasicoRuedasLento(WheelControl.AccionesRuedas.IZQUIERDA, angulo);
                        segundaVezTorso = false;
                        primeraVezTorso = true;
                    }

                } else if (accion == 2) { // Desplazamiento

                   /* if(desplazamientoAvanzar){
                        wheelControl.atras(10, 1);
                        desplazamientoAvanzar = false;
                    }else{
                        wheelControl.avanzar(10, 1);
                        desplazamientoAvanzar = true;
                    }

                    try {
                        Thread.sleep(10000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }*/


                }


            }
        };

        // Ejecuta cada 5 a 10 segundos aleatoriamente
        timer.schedule(movimientoSimple, 0, 30000 + random.nextInt(5000));

    }

    /**
     * Desactivar movimiento aleatorio con wheels
     */
    public void desactivarMovimientoAleatorioWheels() {
        if (timer != null) {
            timer.cancel();
            timer.purge();
            //timer = null;

            // Asegurar que el robot vuelva a una posición neutra
            //wheelControl.detener();
            //brazoControl.moverBrazo(0);
            headControl.girarCabeza(90);

            // Esperar
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }


    /**
     * Activa el seguimiento del robot
     */
    public void activarSeguimiento(){
        modularMotionManager.switchFollow(true);

        System.out.println("FOLLOW STATUS" + modularMotionManager.getFollowStatus().getDescription());
        System.out.println("FOLLOW result" + modularMotionManager.getFollowStatus().getResult());
        System.out.println("FOLLOW errr code" + modularMotionManager.getFollowStatus().getErrorCode());

    }

    public void desactivarSeguimiento(){
        modularMotionManager.switchFollow(false);
    }


}




