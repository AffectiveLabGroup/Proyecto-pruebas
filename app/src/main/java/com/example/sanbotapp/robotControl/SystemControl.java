package com.example.sanbotapp.robotControl;

import com.qihancloud.opensdk.function.beans.EmotionsType;
import com.qihancloud.opensdk.function.unit.SystemManager;

public class SystemControl {
    private SystemManager systemManager;
    private EmotionsType currentEmotion;

    public SystemControl(SystemManager systemManager){
        this.systemManager = systemManager;
    }

    // Función utilizada para cambiar la expresión facial del robot
    // por alguna de las emociones definidas en el sistema
    public void cambiarEmocion(EmotionsType emotion) {
        currentEmotion = emotion;
        systemManager.showEmotion(currentEmotion);
    }


}
