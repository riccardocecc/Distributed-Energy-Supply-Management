    package desm.powerplant.networkTopology;

    import desm.common.PLANT_STATUS;
    import desm.common.PlantInfo;
    import desm.powerplant.communication.grpc.PlantClient;
    import desm.proto.powerplant.ThermalPlant.*;
    import io.grpc.stub.StreamObserver;

    import java.util.*;
    import java.util.logging.Logger;

    public class ElectionDispatcher implements Runnable {

        private static final Logger logger = Logger.getLogger(ElectionDispatcher.class.getName());

        private static class ElectionRequest {
            final ElectionMessage message;
            final StreamObserver<ElectionResponse> responseObserver;
            final long timestamp;

            ElectionRequest(ElectionMessage message, StreamObserver<ElectionResponse> responseObserver) {
                this.message = message;
                this.responseObserver = responseObserver;
                this.timestamp = System.currentTimeMillis();
            }
        }
        private final List<String> requestWhileProducing;

        private List<ElectionRequest> pendingRequest;
        private HandleElectionWorker handlerElectionWorker;
        private StartElectionWorker newElectionWorker;
        private volatile boolean running;
        private Thread dispatcherThread;
        private final Object workerLock = new Object();
        private final Object processedRequestsLock = new Object();

        private final Object producingRequestsLock = new Object();
        private final Set<String> processedRequestIds;
        private volatile ElectionRequest currentRequest;
        private final PlantInfo plantInfo;
        private final NewElectionQueue queue;
        private volatile boolean handlerWorkerBusy;
        private final PlantClient plantClient;
        public ElectionDispatcher(PlantInfo plantInfo, PlantClient plantClient, NewElectionQueue newElectionQueue) {
            this.plantInfo = plantInfo;
            this.plantClient = plantClient;
            this.queue = newElectionQueue;
            this.requestWhileProducing = new ArrayList<>();
            this.running = false;
            this.processedRequestIds = new HashSet<>();
            this.handlerWorkerBusy = false;
            this.pendingRequest = new ArrayList<>();
        }

        public synchronized void start(ElectionManager electionManager) {
            if (running) {
                logger.warning("Dispatcher already running");
                return;
            }
            electionManager.setDispatcher(this);
            handlerElectionWorker = new HandleElectionWorker(electionManager, this);
            newElectionWorker = new StartElectionWorker(queue,plantInfo,plantClient, this,electionManager);


            Thread workerThread = new Thread(handlerElectionWorker);
            workerThread.start();


            Thread newElectionWorkerThread = new Thread(newElectionWorker);
            newElectionWorkerThread.start();

            running = true;

            dispatcherThread = new Thread(this, "ElectionDispatcher");
            dispatcherThread.start();
        }




        /**
         * Gestisce una nuova richiesta di elezione ricevuta da un'altra centrale.
         * Verifica se la richiesta è già stata processata in caso la ignora, altrimenti la assegna al
         * worker se disponibile o la mette in coda se il worker è occupato.
         */
        public void handleElectionRequest(ElectionMessage message, StreamObserver<ElectionResponse> responseObserver) {
            if (!running) {
                logger.warning("Dispatcher not running - rejecting request");
                sendErrorResponse(responseObserver, "Dispatcher not running");
                return;
            }

            String requestId = message.getEnergyRequestId();


            synchronized (processedRequestsLock) {
                if (processedRequestIds.contains(requestId)) {
                    logger.info(String.format("Request %s already processed , sender %s", requestId, message.getSenderId()));
                    return;
                }
            }

            synchronized (workerLock) {
                ElectionRequest electionRequest = new ElectionRequest(message, responseObserver);
                if(handlerWorkerBusy){
                    pendingRequest.add(electionRequest);
                    logger.info(String.format(
                            "\u001B[94m[DISPATCHER] METTO IN CODA pending:\n" +
                                    "pendingSize: %d\n" +
                                    "EnergyRequestID: %s\n" +
                                    "SenderID       : %s\n" +
                                    "ElectionType   : %s\n" +
                                    "PriceOffered   : %.2f\u001B[0m",
                            pendingRequest.size(),
                            electionRequest.message.getEnergyRequestId(),
                            electionRequest.message.getSenderId(),
                            electionRequest.message.getElectionType(),
                            electionRequest.message.getPriceOffered()
                    ));
                }else{
                    handlerWorkerBusy = true;
                    logger.info(String.format("\u001B[94m[DISPATCHER] New request assigned  \u001B[0m"));
                    currentRequest = electionRequest;
                    workerLock.notify();
                }
            }
        }

        /**
         * Metodo chiamato quando la pianta finisce la procedura di simulazione
         *  di energia. Elimina le richieste che ha inoltrato
         * mentre era occupato dalla coda delle richieste utilizzata per iniziare
         * una nuova elezione, in quanto mentre è busy non può partecipare ad un elezione.
         * Se sono presenti le condizioni per iniziare una nuova elezione notifica
         * il thread StartElectionThread.
         */
       public void notifyDispatcher() throws InterruptedException {
           logger.info(String.format("\u001B[94m[DISPATCHER] Dispatcher svegliato  \u001B[0m"));
            synchronized (producingRequestsLock){
                for(String request: requestWhileProducing){
                    queue.removeByRequestId(request);
                }
                requestWhileProducing.clear();
            }
            synchronized (workerLock){
                if(pendingRequest.isEmpty() && plantInfo.getPlantStatus() != PLANT_STATUS.BUSY && !plantInfo.isElectionInProgress()){
                    logger.info(String.format("\u001B[94m[DISPATCHER] notifico nuova elezione  \u001B[0m"));
                    newElectionWorker.notifyStartElectionWorker();
                }
            }
       }

        private void sendErrorResponse(StreamObserver<ElectionResponse> responseObserver, String errorMessage) {
            try {
                ElectionResponse response = ElectionResponse.newBuilder()
                        .setSuccess(false)
                        .setMessage(errorMessage)
                        .setResponseType(ElectionResponseType.ENDED)
                        .build();

                responseObserver.onNext(response);
                responseObserver.onCompleted();
            } catch (Exception e) {
                logger.severe("Error sending error response: " + e.getMessage());
            }
        }

        @Override
        public void run() {
            logger.info("ElectionDispatcher thread started"); // Aggiungi questo
            while (running) {
                try {
                    waitForAvailability();
                    if (!running) {
                        break;
                    }

                    ElectionRequest requestToProcess;
                    synchronized (workerLock) {
                        requestToProcess = currentRequest;
                    }

                    if (requestToProcess != null){
                        processRequest(requestToProcess);
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.info("Dispatcher interrupted");
                    break;
                } catch (Exception e) {
                    logger.severe("Unexpected error in dispatcher loop: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }

        /**
         * Attende che ci sia una richiesta da processare.
         * Il thread rimane in wait finché non arriva una nuova richiesta.
         */
        private void waitForAvailability() throws InterruptedException {
            synchronized (workerLock) {
                while (running && currentRequest == null){
                    logger.info(String.format("\u001B[94m[DISPATCHER] Dispatcher waiting for request from server...  \u001B[0m"));
                    workerLock.wait();
                }
            }
        }



        /**
         * Processa una richiesta di elezione assegnandola a HandlerElectionWorker

         * Se la centrale è in produzione (BUSY), la richiesta viene messa nella coda
         * apposita per non considerarle.
         */
        private void processRequest(ElectionRequest request) throws InterruptedException {
            String requestId = request.message.getEnergyRequestId();


            String requestIDinPlant= plantInfo.getRequestId();
            logger.info(String.format("Request nella pianta %s",
                    requestIDinPlant));


            if(plantInfo.getPlantStatus()==PLANT_STATUS.BUSY){
                synchronized (producingRequestsLock){
                requestWhileProducing.add(request.message.getEnergyRequestId());
                logger.info(String.format("\u001B[94m[DISPATCHER QUEUE] Richiesta messa in coda producing: " + currentRequest.message.getEnergyRequestId() + "\u001B[0m"));
                logger.info(String.format("\u001B[94m[DISPATCHER QUEUE] Coda size: " + requestWhileProducing.size() + "\u001B[0m"));
                }
            }

            synchronized (workerLock){
                    handlerElectionWorker.assignWork(request.message, request.responseObserver);
                    currentRequest = null;
            }
        }

        /**
         * Callback chiamato dal HandleElectionWorker quando completa la gestione
         * di una richiesta. Gestisce la transizione al prossimo lavoro disponibile:
         * - Se ci sono richieste pending, assegna la prossima al worker
         * - Altrimenti, notifica StartElectionWorker per avviare nuove elezioni se vengono
         * soddisfatte le condizioni
         */
        public void handlerWorkerCompleted(ElectionResponseType responseType, String requestId) {
            if (responseType == ElectionResponseType.ENDED) {
                logger.info(String.format("\u001B[94m[DISPATCHER] ELEZIONE TERMINATA  \u001B[0m"));
                synchronized (processedRequestsLock) {
                    processedRequestIds.add(requestId);
                }
                queue.removeByRequestId(requestId);
            }

            ElectionRequest nextRequest = null;
            synchronized (workerLock) {
                logger.info(String.format("\u001B[94m[DISPATCHER] CHECK PENDING REQUEST AFTER ELECTION  \u001B[0m"));
                if (!pendingRequest.isEmpty()) {
                    logger.info(String.format("\u001B[94m[DISPATCHER] pending request is not empty  \u001B[0m"));
                    nextRequest = pendingRequest.remove(0);
                    queue.removeByRequestId(nextRequest.message.getEnergyRequestId());
                    handlerWorkerBusy = true;
                    currentRequest = nextRequest;

                    logger.info(String.format(
                            "\u001B[94m[DISPATCHER] Assigning pending request:\n" +
                                    "EnergyRequestID: %s\n" +
                                    "SenderID       : %s\n" +
                                    "ElectionType   : %s\n" +
                                    "PriceOffered   : %.2f\u001B[0m",
                            nextRequest.message.getEnergyRequestId(),
                            nextRequest.message.getSenderId(),
                            nextRequest.message.getElectionType(),
                            nextRequest.message.getPriceOffered()
                    ));
                    workerLock.notify();
                }else{
                    boolean shouldNotifyNewElection = false;
                    synchronized (workerLock) {
                        shouldNotifyNewElection = pendingRequest.isEmpty();
                        if (shouldNotifyNewElection && plantInfo.getPlantStatus()!=PLANT_STATUS.BUSY && !plantInfo.isElectionInProgress()) {
                            logger.info(String.format("\u001B[94m[DISPATCHER] Start new election  \u001B[0m"));
                            newElectionWorker.notifyStartElectionWorker();
                        }
                        handlerWorkerBusy = false;
                    }
                }
            }
        }

        public boolean isHandlerWorkerBusy(){
            synchronized (workerLock){
                return handlerWorkerBusy;
            }
        }

        public synchronized void shutdown() {
            if (!running) {
                return;
            }

            logger.info("Shutting down ElectionDispatcher...");
            running = false;

            synchronized (workerLock) {
                workerLock.notifyAll();
            }

            // Ferma il worker
            if (handlerElectionWorker != null) {
                handlerElectionWorker.shutdown();
            }

            // Interrompi il thread dispatcher
            if (dispatcherThread != null) {
                dispatcherThread.interrupt();
            }

            logger.info("ElectionDispatcher shutdown completed");
        }



    }