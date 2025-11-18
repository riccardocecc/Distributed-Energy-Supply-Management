package desm.common;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

public class PlantInfo {
    private static final Logger logger = Logger.getLogger(PlantInfo.class.getName());
    private String PLANT_ID;
    private String GRPC_ADDRESS;
    private int GRPC_PORT;
    //PLANT STATUS
    //BUSY - NOT_BUSY - IN_ELECTION - NOT_IN_ELECTION
    private PLANT_STATUS plantStatus;
    private PlantInfo nextPlant;
    private PlantInfo prevPlant;
    private final Object coordinationLock = new Object();
    private volatile boolean topologyUpdateInProgress;
    private String currentRequest;

    private double myPrice;
    private boolean electionInProgress;
    private int waitingForElectionEnd = 0;
    private int waitingForTopologyEnd = 0;

    public PlantInfo(String PLANT_ID, String GRPC_ADDRESS, int GRPC_PORT) {
        this.PLANT_ID = PLANT_ID;
        this.GRPC_ADDRESS = GRPC_ADDRESS;
        this.GRPC_PORT = GRPC_PORT;
        this.plantStatus = PLANT_STATUS.NOT_IN_ELECTION;
        this.electionInProgress = false;
    }

    public PlantInfo() {
        // Jackson constructor
    }

    public boolean isElectionInProgress(){
        synchronized (coordinationLock){
            return this.electionInProgress;
        }
    }
    public int startElection(
            EnergyRequest energyRequest
    ){
        synchronized (coordinationLock){
            String electiondebug = "New election: " + energyRequest.getRequestId();
            waitForTopologyUpdate(electiondebug);
            currentRequest = energyRequest.getRequestId();
            plantStatus = PLANT_STATUS.IN_ELECTION;
            electionInProgress=true;
            initPrice();
            if(isAlone()){
                return -1;
            }
            logger.info(
                    "\u001B[96m [NEW ELECTION] " + energyRequest.getRequestId() +
                            " for " + energyRequest.getEnergyAmount() +
                            " kW. price= " + myPrice +
                            "\u001B[0m");
            return nextPlant.getGRPC_PORT();
        }
    }

    public ElectionResponse joinInElection(
            String requestId,
            double recivedPrice,
            String senderId,
            String messageElectionType
    ) {
        synchronized (coordinationLock) {
            String electiondebug = "Join in election: " + requestId;
            waitForTopologyUpdate(electiondebug);

            if(plantStatus == PLANT_STATUS.BUSY){
                logger.info(String.format("\u001B[96m[PLANT INFO BUSY] sono impegato a produrre : " + currentRequest + "inoltro \u001B[0m"));
                return new ElectionResponse(ELECTION_RESPONSE.FORWARD_ORIGINAL, recivedPrice);
            }

            logger.info(String.format("\u001B[96m[PLANT INFO] CURRENT REQUEST : " + currentRequest + " PLANT STATUS: " + plantStatus + " \u001B[0m"));


            if(currentRequest==null && messageElectionType.equals("ELECTED")){
                logger.info(String.format("\u001B[96m[PLANT INFO] JOIN IN TERMINATED ELECTION :  forward to conclude " + plantStatus + " \u001B[0m"));
                return new ElectionResponse(ELECTION_RESPONSE.DROP_MESSAGE, recivedPrice);
            }

            if(currentRequest!=null){
                if (messageElectionType.equals("ELECTED") && currentRequest.equals(requestId)) {
                    if (senderId.equals(PLANT_ID)) {
                        //sono io il vincitore
                        return new ElectionResponse(ELECTION_RESPONSE.I_WON, myPrice);
                    }
                    plantStatus = PLANT_STATUS.NOT_IN_ELECTION;
                    currentRequest = null;
                    electionInProgress=false;
                    myPrice = 0.0;
                    return new ElectionResponse(ELECTION_RESPONSE.FORWARD_ELECTED, recivedPrice);
                }

                if (messageElectionType.equals("ELECTION") && currentRequest.equals(requestId)) {
                    if (senderId.equals(PLANT_ID)) {
                        //il messaggio di tipo election è tornato all'iniziatore, annunciamo gli altri che c'è un eletto
                        plantStatus = PLANT_STATUS.NOT_IN_ELECTION;
                        return new ElectionResponse(ELECTION_RESPONSE.ANNOUNCE_WIN, recivedPrice);
                    }
                }
            }

            ElectionResponse responseFromLogic = processLogic(
                    requestId,
                    recivedPrice,
                    senderId
            );
            return new ElectionResponse(responseFromLogic.action, responseFromLogic.price);
        }
    }

    private ElectionResponse processLogic(
            String requestId,
            double recivedPrice,
            String senderId
    ) {

        if(currentRequest == null){
            electionInProgress = true;
            currentRequest = requestId;
        }
        if(myPrice==0.0){
            initPrice();
            logger.info(String.format("\u001B[96m[GENERATED PRICE IN LOGIC] myPrice : " + myPrice + " \u001B[0m"));
        }

        if (!currentRequest.equals(requestId)) {
            logger.info(String.format("\u001B[96m[DEBUG] Mi hai dato: " + requestId + " ma sono ancora in elezione per " + currentRequest + "\u001B[0m"));
            logger.info(String.format("\u001B[96m[DEBUG] propago il messaggio che mi hai inviato\u001B[0m"));
            return new ElectionResponse(ELECTION_RESPONSE.FORWARD_ORIGINAL, recivedPrice);
        }

        if (recivedPrice < myPrice) {
            logger.info(String.format("\u001B[96m[ALGO LOGIC] FORWARD_ORIGINAL " +currentRequest + " recivedPrice: " + recivedPrice + " myPrice: " + myPrice + " \u001B[0m"));
            plantStatus = PLANT_STATUS.IN_ELECTION;
            return new ElectionResponse(ELECTION_RESPONSE.FORWARD_ORIGINAL, recivedPrice);

        } else if (recivedPrice > myPrice) {
            logger.info(String.format("\u001B[96m[ALGO LOGIC] FORWARD_MY " +currentRequest + " recivedPrice: " + recivedPrice + " myPrice: " + myPrice + " \u001B[0m"));
            if (plantStatus == PLANT_STATUS.NOT_IN_ELECTION) {
                plantStatus = PLANT_STATUS.IN_ELECTION;
                return new ElectionResponse(ELECTION_RESPONSE.FORWARD_MY, myPrice);
            } else if (plantStatus == PLANT_STATUS.IN_ELECTION) {
                // If it is already participant it does not forward the message
                return new ElectionResponse(ELECTION_RESPONSE.FORWARD_MY, myPrice);
            }
        }
        if (recivedPrice == myPrice) {
            if (Integer.parseInt(PLANT_ID) < Integer.parseInt(senderId)) {
                logger.info(String.format("\u001B[96m[ALGO LOGIC] SAME PRICE FORWARD_MY " +currentRequest + " recivedPrice: " + recivedPrice + " myPrice: " + myPrice + " \u001B[0m"));
                return new ElectionResponse(ELECTION_RESPONSE.FORWARD_MY, myPrice);
            }
            logger.info(String.format("\u001B[96m[ALGO LOGIC] SAME PRICE FORWARD_ORIGINAL " +currentRequest + " recivedPrice: " + recivedPrice + " myPrice: " + myPrice + " \u001B[0m"));
            return new ElectionResponse(ELECTION_RESPONSE.FORWARD_ORIGINAL, recivedPrice);
        }

        return new ElectionResponse(ELECTION_RESPONSE.FORWARD_ORIGINAL, recivedPrice);
    }


    private void initPrice(){
            this.myPrice = generateRandomPrice();
    }

    public double getPrice(){
        synchronized (coordinationLock){
            return this.myPrice;
        }
    }
    public String getRequestId(){
        synchronized (coordinationLock){
            return this.currentRequest;
        }
    }
    private double generateRandomPrice() {
        double price = 0.1 + (0.8 * ThreadLocalRandom.current().nextDouble());
        return Math.round(price * 100.0) / 100.0;
    }

    private boolean checkStartElectionCondition() {
        if (plantStatus == PLANT_STATUS.BUSY || plantStatus == PLANT_STATUS.IN_ELECTION) {
            logger.info("\u001B[96m[" + nextPlant.getPLANT_ID() + "] NEXT PLANT of " + PLANT_ID + ": " + plantStatus + " electionStuck : " + currentRequest + "\u001B[0m");
            return false;
        }
        return true;
    }

    private void waitForTopologyUpdate(String operationName) {
        logger.info("\u001B[96mCheck Topology [" + operationName + "] - topologyInProgress: " + topologyUpdateInProgress);

        while (topologyUpdateInProgress) {
            waitingForTopologyEnd++;
            try {
                logger.info("\u001B[96m[" + PLANT_ID + "] " + operationName + " in attesa di aggiornamento topologia\u001B[0m");
                coordinationLock.wait();
            } catch (InterruptedException e) {
                waitingForTopologyEnd--;
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for topology update", e);
            } finally {
                waitingForTopologyEnd--;
            }
        }
        logger.info("\u001B[96mPlant " + PLANT_ID + " " + operationName + "\u001B[0m");
    }

    private void topologyStart() {
        topologyUpdateInProgress = true;
        logger.info("\u001B[96mUpdatingTopology  - topolgyInProgress: " + topologyUpdateInProgress);
    }

    /**
     * Termina un'elezione - notifica eventuali thread in attesa
     */
    private void topologyEnd() {
        topologyUpdateInProgress = false;
        logger.info("\u001B[96mEndedUpdateTopology  - notifyAll: ");
        coordinationLock.notifyAll();
    }

    /**
     * Ottiene snapshot atomico della topologia - thread-safe anche durante aggiornamenti
     */
    public TopologySnapshot getTopologySnapshot() {
        synchronized (coordinationLock) {
            return new TopologySnapshot(nextPlant, prevPlant);

        }
    }

    /**
     * Aggiorna la topologia in modo thread-safe
     */
    public void updateTopology(PlantInfo newNext, PlantInfo newPrev) {
        synchronized (coordinationLock) {
            topologyStart();
            if (newNext != null) this.nextPlant = newNext;
            if (newPrev != null) this.prevPlant = newPrev;
            topologyEnd();
        }
    }

    public void setPlantAlone(){
        synchronized (coordinationLock){
            topologyStart();
            this.nextPlant = null;
            this.prevPlant = null;
            topologyEnd();
        }
    }

    /**
     * Aggiorna singolo next - protetto
     */
    public void setNextPlant(PlantInfo nextPlant) {
        synchronized (coordinationLock) {
            topologyStart();
            this.nextPlant = nextPlant;
            topologyEnd();
        }
    }

    /**
     * Aggiorna singolo prev - protetto
     */
    public void setPrevPlant(PlantInfo prevPlant) {
        synchronized (coordinationLock) {
            topologyStart();
            this.prevPlant = prevPlant;
            topologyEnd();
        }
    }

    /**
     * Cambia status in modo thread-safe
     */
    public void changeStatus(PLANT_STATUS newStatus) {
        synchronized (coordinationLock) {
            this.plantStatus = newStatus;
        }
    }

    /**
     * Legge status in modo thread-safe
     */
    public PLANT_STATUS getPlantStatus() {
        synchronized (coordinationLock) {
            return plantStatus;
        }
    }

    public String getElectionRequest() {
        synchronized (coordinationLock) {
            return currentRequest;
        }
    }

    public void resetAfterProduction(){
        synchronized (coordinationLock){
            plantStatus=PLANT_STATUS.NOT_IN_ELECTION;
            myPrice=0.0;
            currentRequest=null;
            electionInProgress=false;
        }
    }

    public boolean isAlone() {
        synchronized (coordinationLock){
            return nextPlant == null && prevPlant == null;
        }
    }

    // Metodi per Jackson
    public String getGRPC_ADDRESS() {
        return this.GRPC_ADDRESS;
    }

    public int getGRPC_PORT() {
        return this.GRPC_PORT;
    }

    public String getPLANT_ID() {
        return this.PLANT_ID;
    }

    public void setPLANT_ID(String PLANT_ID) {
        this.PLANT_ID = PLANT_ID;
    }

    public void setGRPC_ADDRESS(String GRPC_ADDRESS) {
        this.GRPC_ADDRESS = GRPC_ADDRESS;
    }

    public void setGRPC_PORT(int GRPC_PORT) {
        this.GRPC_PORT = GRPC_PORT;
    }

    public void setPlantStatus(PLANT_STATUS plantStatus) {
        synchronized (coordinationLock) {
            this.plantStatus = plantStatus;
        }
    }


    /**
     * Snapshot immutabile della topologia
     */
    public static class TopologySnapshot {
        public final PlantInfo nextPlant;
        public final PlantInfo prevPlant;

        public TopologySnapshot(PlantInfo next, PlantInfo prev) {
            this.nextPlant = next;
            this.prevPlant = prev;
        }
    }

    public static class ElectionResponse {
        public final ELECTION_RESPONSE action;
        public final double price;

        public ElectionResponse(ELECTION_RESPONSE action, double price) {
            this.action = action;
            this.price = price;
        }
    }



}