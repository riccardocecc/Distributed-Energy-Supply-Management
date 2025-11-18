package desm.powerplant.networkTopology;

import desm.common.EnergyRequest;
import desm.common.PLANT_STATUS;
import desm.common.PlantInfo;
import desm.powerplant.communication.MqttHandler;

import java.util.logging.Logger;

public class ProductionSimulation implements Runnable{
    private static final Logger logger = Logger.getLogger(ProductionSimulation.class.getName());

    private final MqttHandler mqttHandler;
    private final PlantInfo plantInfo;

    private EnergyRequest energyRequest;

    private ElectionDispatcher dispatcher;
    public ProductionSimulation(ElectionDispatcher electionDispatcher,MqttHandler mqttHandler, PlantInfo plantInfo, EnergyRequest energyRequest) {
        this.dispatcher = electionDispatcher;
        this.mqttHandler = mqttHandler;
        this.plantInfo = plantInfo;
        this.energyRequest = energyRequest;
    }

    @Override
    public void run() {
        try{
        plantInfo.changeStatus(PLANT_STATUS.BUSY);
        logger.info(String.format("\u001B[38;5;37m[PRODUCTION] BUSY: producing " + energyRequest.getEnergyAmount() +" kw/h \u001B[0m"));
        mqttHandler.responseToProvider(energyRequest.getRequestId(), plantInfo.getPLANT_ID());
        long productionTimeMs = Math.round(energyRequest.getEnergyAmount());

        logger.info(String.format("\u001B[38;5;37m[PRODUCTION] Simulating energy production for %d ms...\u001B[0m", productionTimeMs));
        // Simula la produzione di energia
        Thread.sleep(productionTimeMs);
        logger.info(String.format("\u001B[38;5;37m[PRODUCTION] Energy production completed for request %s\u001B[0m", energyRequest.getRequestId()));

        plantInfo.resetAfterProduction();
        logger.info(String.format("\u001B[38;5;37m[PRODUCTION] NOT BUSY \u001B[0m"));
        dispatcher.notifyDispatcher();

    } catch (InterruptedException e) {
        throw new RuntimeException(e);
    }
    }
}
