package desm.powerplant.communication.grpc;

import desm.common.PlantInfo;
import io.grpc.Server;
import io.grpc.ServerBuilder;

import java.io.IOException;

public class PlantServer {
    private final PlantInfo plantInfo;

    private Server server;

    private  PlantServiceImpl plantService;
    public PlantServer(PlantInfo plantInfo, PlantServiceImpl plantService) {
        this.plantInfo = plantInfo;
        this.plantService =plantService;
    }

    public boolean startServer() {
        try {
            this.server = ServerBuilder
                    .forPort(plantInfo.getGRPC_PORT())
                    .addService(plantService)
                    .build();
            server.start();

            System.out.println("Server started on port " + plantInfo.getGRPC_PORT());
            return true;

        } catch (IOException  e) {
            e.printStackTrace();
            return false;
        }
    }
}
