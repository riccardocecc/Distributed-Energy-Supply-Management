package desm.powerplant.networkTopology;

import desm.powerplant.communication.grpc.PlantClient;
import desm.proto.powerplant.ThermalPlant.ElectionMessage;

import java.util.logging.Logger;

public class SendElectionThread implements Runnable{
    private static final Logger logger = Logger.getLogger(SendElectionThread.class.getName());
    private final PlantClient plantClient;

    private ElectionMessage message;

    private int portTarget;

    public SendElectionThread(PlantClient plantClient, ElectionMessage message, int portTarget){
        this.plantClient = plantClient;
        this.message = message;
        this.portTarget = portTarget;
    }
    @Override
    public void run() {
        try {
            logger.info(String.format("\u001B[95m[SEND ELECTION THREAD] invio a " +portTarget+"  \u001B[0m"));
            plantClient.sendTokenToPlant(portTarget, message);
        } catch (Exception e) {
            System.err.println("ElectionSenderThread failed: " + e.getMessage());
        }
    }
}
