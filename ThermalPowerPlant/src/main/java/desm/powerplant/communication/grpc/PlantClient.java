package desm.powerplant.communication.grpc;

import desm.common.PlantInfo;
import desm.proto.powerplant.ThermalPlant;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import desm.proto.powerplant.PowerPlantServiceGrpc;
import desm.proto.powerplant.PowerPlantServiceGrpc.PowerPlantServiceStub;
import desm.proto.powerplant.ThermalPlant.*;
import io.grpc.stub.StreamObserver;

import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class PlantClient {
    private static final Logger logger = Logger.getLogger(PlantClient.class.getName());
    private static final String BLUE = "\u001B[34m";
    private static final String RESET = "\u001B[0m";
    private final PlantInfo plantInfoSender;


    public PlantClient(PlantInfo plantInfoSender) {
        this.plantInfoSender = plantInfoSender;
    }

    /**
     * Introduce la centrale elettrica corrente a tutte le altre centrali nella topologia ad anello.
     * Invia un messaggio di presentazione asincrono al successore nella topologia,
     * includendo le informazioni sulla centrale corrente e sui suoi vicini (successore e predecessore).
     *
     */
    public void asyncIntroduceToAll() throws InterruptedException {
        PlantInfo.TopologySnapshot topologySnapshot = plantInfoSender.getTopologySnapshot();
        PlantInfo successor = topologySnapshot.nextPlant;
        PlantInfo precedessor = topologySnapshot.prevPlant;
        System.out.println(successor.getPLANT_ID());
        System.out.println(precedessor.getPLANT_ID());
        String serverToContact = "localhost:" + successor.getGRPC_PORT();

        final ManagedChannel channel = ManagedChannelBuilder.forTarget(serverToContact).usePlaintext().build();
        PowerPlantServiceStub stub = PowerPlantServiceGrpc.newStub(channel);

        IntroducePlantRequest introduceMessage = IntroducePlantRequest.newBuilder()
                .setPlantId(plantInfoSender.getPLANT_ID())
                .setAddress(plantInfoSender.getGRPC_ADDRESS())
                .setPort(plantInfoSender.getGRPC_PORT())
                .setPropagatorId(plantInfoSender.getPLANT_ID())
                .setSuccessor(
                        ThermalPlant.PlantInfo.newBuilder()
                                .setPlantId(successor.getPLANT_ID())
                                .setAddress(successor.getGRPC_ADDRESS())
                                .setPort(successor.getGRPC_PORT())
                                .build()
                )
                .setPredecessor(
                        ThermalPlant.PlantInfo.newBuilder()
                                .setPlantId(precedessor.getPLANT_ID())
                                .setAddress(precedessor.getGRPC_ADDRESS())
                                .setPort(precedessor.getGRPC_PORT())
                                .build()
                )
                .build();

        stub.introducePlant(introduceMessage, new StreamObserver<IntroducePlantResponse>() {
            @Override
            public void onNext(IntroducePlantResponse introducePlantResponse) {
                logger.info(BLUE + String.format("Introduction response from %s: %s",
                        successor.getPLANT_ID(), introducePlantResponse.getMessage()) + RESET);
            }

            @Override
            public void onError(Throwable throwable) {
                logger.severe(String.format("Error introducing to plant %s: %s",
                        successor.getPLANT_ID(), throwable.getMessage()));
            }

            @Override
            public void onCompleted() {
                logger.fine(String.format("Introduction to plant %s completed, closing channel",
                        successor.getPLANT_ID()));
                channel.shutdownNow();
            }
        });

        try {
            channel.awaitTermination(15, TimeUnit.SECONDS);

        } catch (InterruptedException e) {
            logger.warning(String.format("Channel termination interrupted for plant %s: %s",
                    successor.getPLANT_ID(), e.getMessage()));
            Thread.currentThread().interrupt();
            throw e;
        }


    }

    public void sendTokenToPlant(int portTarget, ElectionMessage message) {
        String serverToContact = "localhost:"+portTarget;
        final ManagedChannel channel = ManagedChannelBuilder.forTarget(serverToContact).usePlaintext().build();
        PowerPlantServiceStub stub = PowerPlantServiceGrpc.newStub(channel);

        stub.passElectionToken(message, new StreamObserver<ElectionResponse>() {
            @Override
            public void onNext(ElectionResponse electionResponse) {
                //logger.info(BLUE + String.format("PlantServiceImpl response %s:",electionResponse.getMessage()) + RESET);
            }

            @Override
            public void onError(Throwable throwable) {
                logger.warning(String.format("ERROR: %s", throwable.getMessage()));
            }

            @Override
            public void onCompleted() {
                channel.shutdownNow();
            }
        });
        try {
            channel.awaitTermination(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }




}
