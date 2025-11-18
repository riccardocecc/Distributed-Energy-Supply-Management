package desm.powerplant.communication.grpc;

import desm.common.PlantInfo;
import desm.powerplant.networkTopology.ElectionDispatcher;
import desm.proto.powerplant.PowerPlantServiceGrpc.PowerPlantServiceImplBase;
import desm.proto.powerplant.ThermalPlant.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import desm.proto.powerplant.PowerPlantServiceGrpc;

import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

// ... package e import rimangono invariati

public class PlantServiceImpl extends PowerPlantServiceImplBase {
    private static final Logger logger = Logger.getLogger(PlantServiceImpl.class.getName());

    private final PlantInfo localPlant;

    private ElectionDispatcher electionDispatcher;

    public PlantServiceImpl(PlantInfo localPlant) {
        this.localPlant = localPlant;
    }

    public void setElectionDispatcher(ElectionDispatcher electionDispatcher){
        this.electionDispatcher = electionDispatcher;
    }

    /**
     * Gestisce le richieste di introduzione di nuove centrali elettriche nella topologia ad anello.
     * Quando una centrale si presenta, questo metodo:
     * 1. Valuta se la topologia dell'anello deve essere modificata
     * 2. Aggiorna i riferimenti successore/predecessore se necessario
     * 3. Propaga l'introduzione alle altre centrali nell'anello
     *
     * @param introducePlantRequest Richiesta contenente le informazioni della centrale che si introduce
     * @param responseStreamObserver Observer per inviare la risposta alla centrale richiedente
     */
    @Override
    public void introducePlant(IntroducePlantRequest introducePlantRequest, StreamObserver<IntroducePlantResponse> responseStreamObserver) {
        String introducingPlantId = introducePlantRequest.getPlantId();
        String propagatorId = introducePlantRequest.getPropagatorId();

        logger.info(String.format("\u001B[33mReceived introduction from plant %s (address: %s, port: %d), propagated by: %s\u001B[0m",
                introducingPlantId, introducePlantRequest.getAddress(), introducePlantRequest.getPort(),
                propagatorId.isEmpty() ? "original" : propagatorId));

        try {

            String messageToSend;
            String plantRequestSuccor = introducePlantRequest.getSuccessor().getPlantId();
            String plantRequestPredec = introducePlantRequest.getPredecessor().getPlantId();
            PlantInfo requestPlant = new PlantInfo(
                    introducePlantRequest.getPlantId(),
                    introducePlantRequest.getAddress(),
                    introducePlantRequest.getPort()
            );

            if (changedRingTopology(plantRequestSuccor, plantRequestPredec, requestPlant)) {
                messageToSend = "Hi plant " + introducingPlantId +
                        " welcome. I changed my succ/pred! I am plant " + localPlant.getPLANT_ID();
            } else {
                messageToSend = "Hi plant " + introducingPlantId +
                        " welcome! I am plant " + localPlant.getPLANT_ID();
            }

            PlantInfo.TopologySnapshot topology = localPlant.getTopologySnapshot();
            String nextIdAfter = topology.nextPlant != null ? topology.nextPlant.getPLANT_ID() : "null";

            IntroducePlantResponse introducePlantResponse = IntroducePlantResponse.newBuilder()
                    .setMessage(messageToSend)
                    .build();

            responseStreamObserver.onNext(introducePlantResponse);
            responseStreamObserver.onCompleted();

            boolean shouldPropagate = topology.nextPlant != null &&
                    !topology.nextPlant.getPLANT_ID().equals(introducingPlantId);

            if (shouldPropagate) {
                propagateHello(introducePlantRequest);
            }

        } catch (Exception e) {
            Thread.currentThread().interrupt();
            logger.severe("Interrupted while waiting for topology update: " + e.getMessage());

            IntroducePlantResponse errorResponse = IntroducePlantResponse.newBuilder()
                    .setMessage("Error processing introduction: topology update interrupted")
                    .build();
            responseStreamObserver.onNext(errorResponse);
            responseStreamObserver.onCompleted();
        }
    }

    /**
     * Propaga il messaggio di introduzione alla centrale successiva nell'anello.
     * Questo metodo implementa la propagazione circolare delle introduzioni,
     * permettendo a tutte le centrali nell'anello di essere informate della nuova centrale.
     *
     * @param introducePlantRequest La richiesta di introduzione da propagare
     */
    private void propagateHello(IntroducePlantRequest introducePlantRequest) {
        PlantInfo.TopologySnapshot topology = localPlant.getTopologySnapshot();
        PlantInfo myNext = topology.nextPlant;

        if (myNext == null) {
            logger.warning("No next plant available for propagation");
            return;
        }

        String serverToContact = "localhost:" + myNext.getGRPC_PORT();
        logger.info(String.format("\u001B[33mInvio a :%s\u001B[0m", serverToContact));

        final ManagedChannel channel = ManagedChannelBuilder.forTarget(serverToContact).usePlaintext().build();

        PowerPlantServiceGrpc.PowerPlantServiceStub nextStub = PowerPlantServiceGrpc.newStub(channel);
        nextStub.introducePlant(introducePlantRequest, new StreamObserver<IntroducePlantResponse>() {
            @Override
            public void onNext(IntroducePlantResponse value) {
                logger.fine(String.format("Forwarded introduction to next plant %s, got response: %s",
                        myNext.getPLANT_ID(), value.getMessage()));
            }

            @Override
            public void onError(Throwable t) {
                logger.warning(String.format("Error forwarding introduction to %s: %s",
                        myNext.getPLANT_ID(), t.getMessage()));
            }

            @Override
            public void onCompleted() {
                channel.shutdownNow();
            }
        });

        try {
            channel.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    /**
     * Determina se la topologia dell'anello deve essere modificata in base alla nuova centrale.
     * Analizza le relazioni successore/predecessore e aggiorna la topologia locale se necessario.
     * Gestisce tre scenari:
     * 1. Centrale isolata (prima centrale nell'anello)
     * 2. La centrale locale deve diventare successore della nuova centrale
     * 3. La centrale locale deve diventare predecessore della nuova centrale
     *
     * @param plantRequestSuccessor ID del successore della centrale richiedente
     * @param plantRequestPredecessor ID del predecessore della centrale richiedente
     * @param requestPlant Informazioni della centrale che richiede l'introduzione
     * @return true se la topologia è stata modificata, false altrimenti
     */
    private boolean changedRingTopology(String plantRequestSuccessor, String plantRequestPredecessor, PlantInfo requestPlant) {
        String myId = localPlant.getPLANT_ID();
        PlantInfo.TopologySnapshot localTopology = localPlant.getTopologySnapshot();

        if (localTopology.nextPlant == null && localTopology.prevPlant == null) {
            logger.info("\u001B[33mSono da solo, lui diventa il mio successore e predecessore\u001B[0m");
            localPlant.updateTopology(requestPlant, requestPlant);
            return true;
        } else {
            String currentNextId = localTopology.nextPlant.getPLANT_ID();
            String currentPrevId = localTopology.prevPlant.getPLANT_ID();

            logger.fine(String.format("myId: %s, mySucc: %s, myPred: %s", myId, currentNextId, currentPrevId));

            if (myId.equals(plantRequestSuccessor)) {
                logger.info("\u001B[33mRing topology: becoming successor of new plant\u001B[0m");
                localPlant.setPrevPlant(requestPlant);
                return true;
            }

            if (myId.equals(plantRequestPredecessor)) {
                logger.info("\u001B[33mRing topology: becoming predecessor of new plant\u001B[0m");
                localPlant.setNextPlant(requestPlant);
                return true;
            }
        }

        logger.fine("Ring topology: no changes needed");
        return false;
    }

    /**
     * Gestisce i token di elezione ricevuti da altre centrali elettriche.
     * Questo metodo implementa la ricezione dei messaggi nel protocollo di elezione distribuita,
     * delegando la logica di gestione al Thread ElectionDispatcher.
     *
     * @param request Messaggio di elezione contenente tipo, mittente, prezzo offerto e ID richiesta
     * @param responseObserver StreamObserver per inviare la risposta al mittente del token
     */
    @Override
    public void passElectionToken(ElectionMessage request, StreamObserver<ElectionResponse> responseObserver) {
        logger.info(String.format("\u001B[33m[PLANT SERVICE IMPL] Received election token: type=%s, sender=%s, price=%.3f, request=%s\u001B[0m",
                request.getElectionType(),
                request.getSenderId(),
                request.getPriceOffered(),
                request.getEnergyRequestId()));

        if (electionDispatcher == null) {
            logger.severe("ElectionDispatcher not initialized - cannot process election token");

            ElectionResponse errorResponse = ElectionResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("ElectionDispatcher not initialized")
                    .build();

            responseObserver.onNext(errorResponse);
            responseObserver.onCompleted();
            return;
        }

        try {

            electionDispatcher.handleElectionRequest(request, responseObserver);

        } catch (Exception e) {
            logger.severe(String.format("Error queuing election token for request %s: %s",
                    request.getEnergyRequestId(), e.getMessage()));

            ElectionResponse errorResponse = ElectionResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Error queuing election token: " + e.getMessage())
                    .build();

            responseObserver.onNext(errorResponse);
            responseObserver.onCompleted();
        }
    }

}
