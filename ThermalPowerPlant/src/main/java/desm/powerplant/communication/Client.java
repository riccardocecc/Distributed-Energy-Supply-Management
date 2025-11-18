package desm.powerplant.communication;

import desm.common.PlantInfo;
import desm.common.RegistrationResult;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.List;


public class Client {

    private final String ADMIN_ADDRESS;
    private final int ADMIN_PORT;

    private final PlantInfo plantInfo;

    private final RestTemplate client;


    public Client(String ADMIN_ADDRESS, int ADMIN_PORT, PlantInfo plantInfo) {
        this.ADMIN_ADDRESS = ADMIN_ADDRESS;
        this.ADMIN_PORT = ADMIN_PORT;
        this.plantInfo = plantInfo;
        this.client = new RestTemplate();
    }


    public RegistrationResult postRequest() {
        String serverAddress = "http://" + ADMIN_ADDRESS + ":" + ADMIN_PORT;
        String postPath = "/plant/register";
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setContentType(MediaType.APPLICATION_JSON);
        if (plantInfo != null) {
            HttpEntity<PlantInfo> request = new HttpEntity<>(plantInfo, httpHeaders);


            ResponseEntity<RegistrationResult> postResponse = client.exchange(
                    serverAddress + postPath,
                    HttpMethod.POST,
                    request,
                    new ParameterizedTypeReference<RegistrationResult>() {
                    }
            );


            System.out.println("POST Response status: " + postResponse.getStatusCode());


            RegistrationResult registrationResult = postResponse.getBody();
            if (registrationResult != null) {
               return registrationResult;
            } else {
                System.out.println("Nessuna pianta ricevuta");
            }
        }
        return null;
    }


}
