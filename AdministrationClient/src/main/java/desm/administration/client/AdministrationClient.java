package desm.administration.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import desm.common.PlantInfo;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.URI;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Scanner;

public class AdministrationClient {

    private static final String BASE_URL = "http://localhost:8080/client";
    private final HttpHeaders headers;
    private RestTemplate client = new RestTemplate();
    private final Scanner scanner;

    public AdministrationClient() {
        this.headers = new HttpHeaders();
        this.scanner = new Scanner(System.in);
    }



    public void start() {
        System.out.println("=== Administration Client ===");
        System.out.println("Connecting to server at: " + BASE_URL);
        System.out.println();

        while (true) {
            displayMenu();
            int choice = getMenuChoice();

            switch (choice) {
                case 1:
                    getAllPlants();
                    break;
                case 2:
                    getAveragePollution();
                    break;
                case 3:
                    System.out.println("Exiting... Goodbye!");
                    return;
                default:
                    System.out.println("Invalid choice. Please try again.");
            }

            System.out.println();
            waitForEnter();
        }
    }

    private void displayMenu() {
        System.out.println("Select an operation:");
        System.out.println("1. Get all plants in the network");
        System.out.println("2. Get average pollution between timestamps");
        System.out.println("3. Exit");
        System.out.print("Enter your choice (1-3): ");
    }

    private int getMenuChoice() {
        try {
            return Integer.parseInt(scanner.nextLine().trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void getAllPlants() {
            System.out.println("\n--- Getting all plants in the network ---");

            this.headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<PlantInfo[]> getAllPlant =client.getForEntity(BASE_URL+"/allPlants", PlantInfo[].class);
            PlantInfo[] plants = getAllPlant.getBody();
                if (plants == null) {
                    System.out.println("No plants found in the network.");
                } else {
                    System.out.println( "Registered plants: " + plants.length );
                    for (PlantInfo plant : plants){
                        System.out.println("# PlantId:  " + plant.getPLANT_ID());
                        System.out.println("# Server Address: " + plant.getGRPC_ADDRESS()+":"+ plant.getGRPC_PORT());
                    }
                }

    }

    private void getAveragePollution() {
        System.out.println("\n--- Getting average pollution between timestamps ---");

        System.out.print("Enter start timestamp (t1): ");
        String t1 = scanner.nextLine().trim();

        if (t1.isEmpty()) {
            System.out.println("Start timestamp cannot be empty.");
            return;
        }

        System.out.print("Enter end timestamp (t2): ");
        String t2 = scanner.nextLine().trim();

        if (t2.isEmpty()) {
            System.out.println("End timestamp cannot be empty.");
            return;
        }

        String url = BASE_URL + "/average/" + t1 + "/" + t2;

        ResponseEntity<Double> response =client.getForEntity(url, Double.class);
                try {
                    Double average = response.getBody();
                    System.out.println("Average pollution between " + t1 + " and " + t2 + ": " + String.format("%.2f", average));
                } catch (NumberFormatException e) {
                    System.out.println("Invalid response format: " + response.getBody());
                }

    }

    private void waitForEnter() {
        System.out.print("Press Enter to continue...");
        scanner.nextLine();
    }
}