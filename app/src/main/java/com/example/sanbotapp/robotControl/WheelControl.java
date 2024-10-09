package com.example.sanbotapp.robotControl;

import com.qihancloud.opensdk.function.beans.wheelmotion.RelativeAngleWheelMotion;
import com.qihancloud.opensdk.function.unit.WheelMotionManager;

public class WheelControl {
    private WheelMotionManager wheelMotionManager;

    public WheelControl(WheelMotionManager wheelMotionManager){
        this.wheelMotionManager = wheelMotionManager;
    }

    public enum AccionesRuedas {
        IZQUIERDA,
        DERECHA,
        GIRAR;
    }

    // Funcion que ejecute esas acciones
    public boolean controlBasicoRuedas(AccionesRuedas accion) {
        RelativeAngleWheelMotion movimientoRuedas;
        switch (accion) {
            case IZQUIERDA:
                movimientoRuedas = new RelativeAngleWheelMotion(RelativeAngleWheelMotion.TURN_LEFT, 5, 90);
                wheelMotionManager.doRelativeAngleMotion(movimientoRuedas);
                break;
            case DERECHA:
                movimientoRuedas = new RelativeAngleWheelMotion(RelativeAngleWheelMotion.TURN_RIGHT, 5, 90);
                wheelMotionManager.doRelativeAngleMotion(movimientoRuedas);

                break;
            case GIRAR:
                movimientoRuedas = new RelativeAngleWheelMotion(RelativeAngleWheelMotion.TURN_LEFT, 5, 360);
                wheelMotionManager.doRelativeAngleMotion(movimientoRuedas);
                break;
        }

        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        return true;
    }


}
