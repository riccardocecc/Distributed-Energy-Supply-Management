package desm.powerplant.networkTopology;

import desm.common.ELECTION_RESPONSE;
import desm.common.EnergyRequest;
import desm.common.PlantInfo;
import desm.powerplant.communication.grpc.PlantClient;
import desm.powerplant.networkTopology.*;
import desm.powerplant.communication.MqttHandler;
import desm.proto.powerplant.ThermalPlant.*;

import java.time.Instant;
import java.util.logging.Logger;

public class ElectionManager {
    private static final Logger logger = Logger.getLogger(ElectionManager.class.getName());
    private static final long ELECTION_TIMEOUT_MS = 30000;
    private PlantInfo myPlant;
    private final PlantClient plantClient;
    private PlantInfo successor;
    private MqttHandler mqttHandler;
    private ElectionDispatcher dispatcher;


    public ElectionManager(PlantInfo myPlant, PlantClient plantClient) {
        this.myPlant = myPlant;
        this.plantClient = plantClient;
    }

    public void setMqttHandler(MqttHandler mqttHandler) {
        this.mqttHandler = mqttHandler;
    }

    public void setDispatcher(ElectionDispatcher dispatcher){
        this.dispatcher = dispatcher;
    }

    /**
     * Elabora un messaggio di elezione implementando l'algoritmo di elezione ad anello.
     *
     * Flusso di elaborazione:
     * 1. Simula un delay di elaborazione (3 secondi) per testare la concorrenza
     * 2. Delega alla propria PlantInfo la decisione sull'azione da intraprendere
     * 3. Esegue l'azione appropriata basata sulla risposta ricevuta:
     *    - FORWARD_ORIGINAL: Inoltra il messaggio originale al successore
     *    - FORWARD_MY: Sostituisce il proprio prezzo e inoltra
     *    - ANNOUNCE_WIN: Annuncia il vincitore dell'elezione
     *    - DROP_MESSAGE: Termina l'elezione scartando il messaggio
     *    - FORWARD_ELECTED: Inoltra il messaggio del vincitore
     *    - I_WON: Gestisce la vittoria avviando la simulazione di produzione
     *
     * @param message Messaggio di elezione contenente requestId, senderId, prezzo e tipo
     * @return ElectionResponse con esito dell'elaborazione e tipo di risposta
     * @throws RuntimeException in caso di errori durante l'elaborazione
     * @throws IllegalStateException se la risposta dell'elezione è null o malformata
     */
    public synchronized ElectionResponse processElectionMessage(ElectionMessage message) {
        String energyRequestId = message.getEnergyRequestId();
        try {
            logger.info("\u001B[92m[ELECTION MANAGER] Enter in sleeep...\u001B[0m");
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            PlantInfo.ElectionResponse electionResponse = myPlant.joinInElection(
                    energyRequestId,
                    message.getPriceOffered(),
                    message.getSenderId(),
                    message.getElectionType().toString()
            );

            if (electionResponse == null) {
                throw new IllegalStateException("joinInElection returned null response");
            }

            // Controllo che l'action non sia null
            if (electionResponse.action == null) {
                throw new IllegalStateException("Election response action is null");
            }
            logger.info(String.format(
                    "\u001B[92m[ELECTION MANAGER] Result election response for request %s -> Action: %s | price=%.3f \u001B[0m",
                    energyRequestId,
                    electionResponse.action,
                    electionResponse.price
            ));

            if (electionResponse.action == ELECTION_RESPONSE.FORWARD_ORIGINAL) {
                sendToNextInRing(message);
                return ElectionResponse.newBuilder()
                        .setSuccess(true)
                        .setMessage("Forwarded Original")
                        .setResponseType(ElectionResponseType.IN_PROGRESS)
                        .build();
            }

            if (electionResponse.action == ELECTION_RESPONSE.FORWARD_MY) {
                ElectionMessage newMessage = ElectionMessage.newBuilder()
                        .setSenderId(myPlant.getPLANT_ID())
                        .setPriceOffered(electionResponse.price)
                        .setEnergyRequestId(message.getEnergyRequestId())
                        .setTimestamp(System.currentTimeMillis())
                        .setProviderKwh(message.getProviderKwh())
                        .setElectionType(ElectionType.ELECTION)
                        .build();
                sendToNextInRing(newMessage);
                return ElectionResponse.newBuilder()
                        .setSuccess(true)
                        .setMessage("Price changed")
                        .setResponseType(ElectionResponseType.IN_PROGRESS)
                        .build();
            }

            if (electionResponse.action == ELECTION_RESPONSE.ANNOUNCE_WIN) {
                announceWinner(message);
                return ElectionResponse.newBuilder()
                        .setSuccess(true)
                        .setMessage("Announcing winner")
                        .setResponseType(ElectionResponseType.IN_PROGRESS)
                        .build();
            }

            if(electionResponse.action == ELECTION_RESPONSE.DROP_MESSAGE){
                sendToNextInRing(message);
                return ElectionResponse.newBuilder()
                        .setSuccess(true)
                        .setMessage("drop message, election terminated")
                        .setResponseType(ElectionResponseType.ENDED)
                        .build();
            }

            if (electionResponse.action == ELECTION_RESPONSE.FORWARD_ELECTED) {
                sendToNextInRing(message);
                return ElectionResponse.newBuilder()
                        .setSuccess(true)
                        .setMessage("there is a winner")
                        .setResponseType(ElectionResponseType.ENDED)
                        .build();
            }


            if (electionResponse.action == ELECTION_RESPONSE.I_WON) {
                handleElectionWin(energyRequestId,message.getProviderKwh());
                return ElectionResponse.newBuilder()
                        .setSuccess(true)
                        .setMessage("i won")
                        .setResponseType(ElectionResponseType.ENDED)
                        .build();
            }

            return ElectionResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("")
                    .build();
        } catch (Exception e) {

            String errorMsg = "Error during election processing for request " + energyRequestId +
                    ": " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            logger.severe(errorMsg);
            throw new RuntimeException(errorMsg, e);
        }
    }

    private void announceWinner(ElectionMessage originalMessage) {
        logger.info(String.format("\u001B[92m[ELECTION MANAGER] Winner for request %s is %s\u001B[0m",
                originalMessage.getEnergyRequestId(), originalMessage.getSenderId()));

        ElectionMessage winnerMessage = ElectionMessage.newBuilder()
                .setSenderId(originalMessage.getSenderId())
                .setPriceOffered(originalMessage.getPriceOffered())
                .setEnergyRequestId(originalMessage.getEnergyRequestId())
                .setProviderKwh(originalMessage.getProviderKwh())
                .setTimestamp(System.currentTimeMillis())
                .setElectionType(ElectionType.ELECTED)
                .build();

        sendToNextInRing(winnerMessage);
    }

    private void sendToNextInRing(ElectionMessage message) {
        PlantInfo.TopologySnapshot topologySnapshot = myPlant.getTopologySnapshot();
        this.successor = topologySnapshot.nextPlant;

        if (successor == null) {
            logger.warning("\u001B[91mNo next plant found - terminating election\u001B[0m");
            return;
        }
        Thread sendMessageThread = new Thread(new SendElectionThread(plantClient, message, successor.getGRPC_PORT()));
        sendMessageThread.start();
    }


    public synchronized void handleElectionWin(String energyRequestId, double kwhRequest) {
        logger.info("\u001B[92m[ELECTION MANAGER] Enter in ProductionSimulation...\u001B[0m");
        long timeStamp = Instant.now().toEpochMilli();
        Thread productionSimultaion = new Thread(new ProductionSimulation(dispatcher,mqttHandler, myPlant, new EnergyRequest(energyRequestId,timeStamp, kwhRequest)));
        productionSimultaion.start();
    }
}