package desm.powerplant.networkTopology;

import desm.proto.powerplant.ThermalPlant.*;
import io.grpc.stub.StreamObserver;

import java.util.logging.Logger;

public class HandleElectionWorker implements Runnable {

    private static final Logger logger = Logger.getLogger(HandleElectionWorker.class.getName());

    private static class WorkItem {
        final ElectionMessage message;
        final StreamObserver<ElectionResponse> responseObserver;
        final long timestamp;

        WorkItem(ElectionMessage message, StreamObserver<ElectionResponse> responseObserver) {
            this.message = message;
            this.responseObserver = responseObserver;
            this.timestamp = System.currentTimeMillis();
        }

        @Override
        public String toString() {
            return String.format("WorkItem{requestId=%s, senderId=%s, timestamp=%d}",
                    message.getEnergyRequestId(), message.getSenderId(), timestamp);
        }
    }

    private final ElectionManager electionManager;
    private final ElectionDispatcher dispatcher;
    private volatile boolean running;


    private WorkItem currentWork;
    private final Object workLock = new Object();



    public HandleElectionWorker( ElectionManager electionManager, ElectionDispatcher dispatcher) {
        this.electionManager = electionManager;
        this.dispatcher = dispatcher;
        this.running = false;
        this.currentWork = null;
    }

    /**
     * Assegna un nuovo lavoro al worker per l'elaborazione di un messaggio di elezione.
     * Quando viene assegnato un nuovo lavoro, il worker viene svegliato tramite notify().
     *
     * @param message Il messaggio di elezione contenente informazioni su requestId,
     *                senderId, tipo di elezione, prezzo offerto e kWh richiesti
     * @param responseObserver L'observer gRPC per inviare la risposta asincrona
     *                         al client che ha fatto la richiesta
     */
    public void assignWork(ElectionMessage message, StreamObserver<ElectionResponse> responseObserver) {
        if (message == null) {
            logger.warning(String.format("Worker received null message!"));
            return;
        }

        synchronized (workLock) {
            logger.info(String.format("\u001B[93m[HANDLER WOKRER] svegliato per nuovo lavoro:  " + message.getProviderKwh() + "kw/h \u001B[0m"));
            currentWork = new WorkItem(message, responseObserver);
            workLock.notify();
        }
    }


    /**
     * Ferma il worker
     */
    public void shutdown() {
        running = false;
        synchronized (workLock) {
            workLock.notify();
        }

    }

    /**
     * Metodo principale del worker che implementa il pattern producer-consumer.
     * Esegue un loop continuo che:
     * 1. Attende l'assegnazione di nuovo lavoro tramite waitForWork()
     * 2. Elabora il messaggio di elezione chiamando processElectionRequest()
     * 3. Resetta il lavoro corrente e notifica il dispatcher del completamento
     *
     */
    @Override
    public void run() {
        running = true;
        while (running) {
            try {
                WorkItem work = waitForWork();

                if (work != null && running) {

                    String requestId = work.message.getEnergyRequestId();

                    ElectionResponseType responseType = processElectionRequest(work);

                    resetCurrentWork();
                    dispatcher.handlerWorkerCompleted(responseType, requestId);

                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.info(String.format("Worker interrupted"));
                break;
            } catch (Exception e) {
                logger.severe(String.format("Unexpected error in worker: %s", e.getMessage()));
                e.printStackTrace();


                handleUnexpectedError();
            }
        }

    }


    private void resetCurrentWork() {
        synchronized (workLock) {
            currentWork = null;
        }
    }

    private void handleUnexpectedError() {
        currentWork = null;
    }

    private WorkItem waitForWork() throws InterruptedException {
        synchronized (workLock) {
            while (currentWork == null && running) {
                logger.info(String.format("\u001B[93m[HANDLER WORKER] Waiting for work... \u001B[0m"));
                workLock.wait();
            }
            if (!running) {
                return null;
            }

            return currentWork;
        }
    }

    /**
     * Elabora una richiesta di elezione delegando la logica di business all'ElectionManager.
     * Il metodo:
     * -. Chiama electionManager.processElectionMessage() per ottenere la risposta
     * -. Invia la risposta al client tramite responseObserver
     *
     * @param work Il WorkItem contenente il messaggio da elaborare e l'observer per la risposta
     * @return Il tipo di risposta dell'elezione (IN_PROGRESS, ENDED, ERROR)
     */
    private ElectionResponseType processElectionRequest(WorkItem work) {
        ElectionMessage message = work.message;
        StreamObserver<ElectionResponse> responseObserver = work.responseObserver;
        String requestId = message.getEnergyRequestId();
        logger.info(String.format(
                "\u001B[93m[HANDLE WORKER] gestendo:\n" +
                        "EnergyRequestID: %s\n" +
                        "SenderID       : %s\n" +
                        "ElectionType   : %s\n" +
                        "PriceOffered   : %.2f\u001B[0m",
                message.getEnergyRequestId(),
                message.getSenderId(),
                message.getElectionType(),
                message.getPriceOffered()
        ));

            long startTime = System.currentTimeMillis();
        ElectionResponseType resultType = ElectionResponseType.ERROR;

        try {
            ElectionResponse response = electionManager.processElectionMessage(message);

            if (response == null) {

                response = ElectionResponse.newBuilder()
                        .setSuccess(false)
                        .setMessage("Internal error: null response from election manager")
                        .setResponseType(ElectionResponseType.ERROR)
                        .build();
            }

            resultType = response.getResponseType();


            responseObserver.onNext(response);
            responseObserver.onCompleted();

            return resultType;

        } catch (Exception e) {
            long processingTime = System.currentTimeMillis() - startTime;
            logger.severe(String.format("Worker error processing request %s after %d ms: %s",
                     requestId, processingTime, e.getMessage()));

            try {
                ElectionResponse errorResponse = ElectionResponse.newBuilder()
                        .setSuccess(false)
                        .setMessage("Processing error: " + e.getMessage())
                        .setResponseType(ElectionResponseType.ERROR)
                        .build();

                responseObserver.onNext(errorResponse);
                responseObserver.onCompleted();

                return ElectionResponseType.ENDED;

            } catch (Exception responseError) {
                logger.severe(String.format("Worker  failed to send error response for request %s: %s",
                         requestId, responseError.getMessage()));

                try {
                    responseObserver.onError(responseError);
                } catch (Exception finalError) {
                    logger.severe(String.format("Worker  complete failure for request %s: %s",
                             requestId, finalError.getMessage()));
                }

                return ElectionResponseType.ERROR;
            }
        }
    }




}