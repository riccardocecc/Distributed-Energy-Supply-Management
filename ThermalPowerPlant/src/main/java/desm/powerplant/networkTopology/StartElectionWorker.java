    package desm.powerplant.networkTopology;

    import desm.common.EnergyRequest;
    import desm.common.PLANT_STATUS;
    import desm.common.PlantInfo;
    import desm.powerplant.communication.grpc.PlantClient;
    import desm.proto.powerplant.ThermalPlant;

    import java.util.logging.Logger;

    /**
     * Bisogna tenere traccia delle elezione a cui partecipato.
     * Non c'è controllo tra quelle che ha gestito e quelle nella coda.
     * Se una pianta inizia prima un elezione
     */


    public class StartElectionWorker implements Runnable {

        private static final Logger logger = Logger.getLogger(StartElectionWorker.class.getName());

        private final NewElectionQueue queue;
        private final ElectionDispatcher dispatcher;
        private volatile boolean running;
        private volatile boolean dispatcherOkForElection;
        private final PlantClient client;
        private final PlantInfo plantInfo;
        private final Object waitLock = new Object();
        private final ElectionManager electionManager;


        public StartElectionWorker(NewElectionQueue queue, PlantInfo plantInfo, PlantClient plantClient, ElectionDispatcher electionDispatcher, ElectionManager electionManager) {
            this.queue = queue;
            this.client = plantClient;
            this.dispatcher = electionDispatcher;
            this.plantInfo = plantInfo;
            this.running = false;
            this.electionManager = electionManager;
            this.dispatcherOkForElection = false;
        }

        /**
         * Metodo principale del worker che implementa il loop di elaborazione delle elezioni.
         * Gestisce due modalità operative:
         *
         * 1. CENTRALE NON ISOLATA: Attende notifica dal dispatcher che indica disponibilità
         *    per iniziare una nuova elezione, poi processa la richiesta dalla coda
         *
         * 2. CENTRALE ISOLATA: Attende che la centrale sia disponibile (non occupata),
         *    poi elabora direttamente le richieste senza elezione
         *
         */
        @Override
        public void run() {
            running = true;
            while (running) {
                try {

                    if (!plantInfo.isAlone()) {
                        waitForDispatcherNotification();
                        if (!running) {
                            break;
                        }

                        startNewElectionFromQueue();
                    } else {
                        waitForAvailability();
                        handleAlonePlant();
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.info("StartElectionWorker interrupted");
                    break;
                } catch (Exception e) {
                    logger.severe("Error in StartElectionWorker: " + e.getMessage());
                    e.printStackTrace();
                }
            }

            logger.info("StartElectionWorker terminated");
        }

        /**
         * Gestisce il caso speciale di una centrale elettrica isolata (senza connessioni).
         * Quando una centrale è sola nella rete, non è necessario avviare un'elezione
         * distribuita, quindi processa direttamente le richieste di energia.
         *
         * Il metodo:
         * 1. Verifica che la centrale sia effettivamente isolata
         * 2. Preleva la prossima richiesta dalla coda
         * 3. Avvia l'elezione locale (che restituisce -1 se da sola)
         * 4. Delega direttamente la produzione tramite electionManager
         *
         */
        private void handleAlonePlant() throws InterruptedException {
            if(!plantInfo.isAlone()){
                return;
            }
            logger.info(String.format("\u001B[95m[NEW ELECTION WORKER] plant alone start election !  \u001B[0m"));
                try {

                    EnergyRequest nextRequest = queue.take();

                    if (nextRequest != null && running) {
                        logger.info("Processing request immediately (plant is alone): " + nextRequest.getRequestId());

                        int next = plantInfo.startElection(nextRequest);
                        if (next == -1) {
                            electionManager.handleElectionWin(nextRequest.getRequestId(), nextRequest.getEnergyAmount());
                        }
                    }

                } catch (Exception e) {
                    logger.severe("Error processing request for alone plant: " + e.getMessage());
                    e.printStackTrace();
                }
        }


        /**
         * Attende che la centrale isolata sia disponibile per elaborare nuove richieste.
         * La centrale è considerata disponibile quando:
         * - Non ha richieste attive in elaborazione (requestId == null)
         * - Non è occupata nella produzione (status != BUSY)
         * - Il dispatcher non ha handler worker occupati
         *
         * Utilizza il pattern wait/notify per bloccare il thread fino a quando
         * tutte le condizioni di disponibilità non sono soddisfatte.
         */
        private void waitForAvailability() throws InterruptedException {
            synchronized (waitLock) {
                while (running  && (plantInfo.getRequestId()!=null  || plantInfo.getPlantStatus()== PLANT_STATUS.BUSY || dispatcher.isHandlerWorkerBusy())) {
                    logger.info(String.format("\u001B[95m[ALONE NEW ELECTION WORKER] waiting....  \u001B[0m"));
                    waitLock.wait();
                }
            }
        }

        /**
         * Attende la notifica dal dispatcher che indica la possibilità di avviare una nuova elezione.
         * Per centrali connesse in rete,
         *
         * Il worker rimane in attesa fino a quando:
         * - La centrale non diventa isolata (caso gestito separatamente)
         * - Il dispatcher non segnala disponibilità tramite dispatcherOkForElection
         *
         * @throws InterruptedException se il thread viene interrotto durante l'attesa
         */
        private void waitForDispatcherNotification() throws InterruptedException {
            synchronized (waitLock) {
                while (running  && !plantInfo.isAlone() && !dispatcherOkForElection) {
                    logger.info(String.format("\u001B[95m[NOT ALONE NEW ELECTION WORKER] waiting....  \u001B[0m"));
                    waitLock.wait();
                }
            }
        }


        public void notifyStartElectionWorker() {
            synchronized (waitLock) {
                logger.info(String.format("\u001B[95m[NEW ELECTION WORKER] svegliato !  \u001B[0m"));
                dispatcherOkForElection=true;
                waitLock.notify();
            }
        }

        /**
         * Avvia una nuova elezione per centrali connesse in rete.
         * Il metodo:
         * 1. Resetta il flag di autorizzazione del dispatcher
         * 2. Verifica che la centrale non sia diventata isolata nel frattempo
         * 3. Preleva la prossima richiesta dalla coda
         * 4. Inizializza l'elezione locale e ottiene la porta della centrale successiva
         * 5. Crea e invia il messaggio di elezione iniziale alla centrale successiva
         *
         * Il messaggio contiene:
         * - ID della centrale mittente
         * - Prezzo offerto dalla centrale corrente
         * - ID della richiesta di energia
         * - Timestamp e quantità di energia richiesta
         * - Tipo di messaggio (ELECTION)
         *
         * L'invio avviene in un thread separato per evitare blocchi.
         */
        private void startNewElectionFromQueue() {
            synchronized (waitLock){
                dispatcherOkForElection=false;
            }
            try {
                if(plantInfo.isAlone()){
                    return;
                }
                logger.info(String.format("\u001B[95m[NEW ELECTION WORKER] plant not alone start election !  \u001B[0m"));
                EnergyRequest nextRequest = queue.take();
                int nextPlantPort =  plantInfo.startElection(nextRequest);

                ThermalPlant.ElectionMessage electionMessage = ThermalPlant.ElectionMessage.newBuilder()
                            .setSenderId(plantInfo.getPLANT_ID())
                            .setPriceOffered(plantInfo.getPrice())
                            .setEnergyRequestId(nextRequest.getRequestId())
                            .setTimestamp(System.currentTimeMillis())
                            .setProviderKwh(nextRequest.getEnergyAmount())
                            .setElectionType(ThermalPlant.ElectionType.ELECTION)
                            .build();

                Thread sendThread = new Thread(
                            new SendElectionThread(
                                    client,
                                    electionMessage,
                                    nextPlantPort
                            )
                    );
                    sendThread.start();

            } catch (Exception e) {
                logger.severe("Error starting new election: " + e.getMessage());
                e.printStackTrace();
            }
        }

        /**
         * Ferma il NewElectionWorker
         */
        public void shutdown() {
            logger.info("Shutting down NewElectionWorker...");
            running = false;

            synchronized (waitLock) {
                waitLock.notify();
            }
        }

    }