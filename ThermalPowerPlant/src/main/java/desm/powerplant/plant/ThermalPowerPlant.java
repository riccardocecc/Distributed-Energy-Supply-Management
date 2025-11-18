package desm.powerplant.plant;

import desm.common.PlantInfo;
import desm.common.RegistrationResult;
import desm.powerplant.communication.Client;
import desm.powerplant.communication.MqttHandler;
import desm.powerplant.networkTopology.ElectionManager;
import desm.powerplant.networkTopology.NewElectionQueue;
import desm.powerplant.networkTopology.ElectionDispatcher;
import desm.powerplant.communication.grpc.PlantClient;
import desm.powerplant.communication.grpc.PlantServer;
import desm.powerplant.communication.grpc.PlantServiceImpl;
import desm.powerplant.pollutionSensor.simulator.Buffer;
import desm.powerplant.pollutionSensor.simulator.Measurement;
import desm.powerplant.pollutionSensor.simulator.PollutionSensor;
import desm.powerplant.pollutionSensor.WindowBuffer;


import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.logging.Logger;

/**
 * Thermal Power Plant implementation following the initialization specifications:
 * 1. Register with administration server
 * 2. Start pollution sensor data acquisition
 * 3. Present itself to other plants (if any exist)
 * 4. Subscribe to MQTT energy production requests
 */
public class ThermalPowerPlant {
    private static final Logger logger = Logger.getLogger(ThermalPowerPlant.class.getName());

    // Plant configuration
    private final PlantInfo plantInfo;
    private final String adminServerAddress;
    private final int adminServerPort;

    private List<Measurement> windowForSliding;

    private final List<Double> computedAverages;
    // Network components
    private Client adminClient;
    private PlantServer plantServer;
    private PlantClient plantClient;
    private PlantServiceImpl plantService;
    private ElectionManager electionManager;

    // Sensor and communication
    private MqttHandler mqttHandler;
    private Buffer buffer;
    private PollutionSensor sensor;
    private Thread bufferReaderThread;
    private Thread mqttSenderThread;
    private volatile boolean isRunning = false;
    private static final int SLIDING_WINDOW_SIZE = 8;
    private static final int SLIDING_STEP = 4;


    // State management
    private boolean isInitialized = false;
    private boolean isAloneInNetwork = false;
    private NewElectionQueue newElectionQueue;

    private final Object slidingWindowLock = new Object();
    private final Object averagesLock = new Object();

    public ThermalPowerPlant(String plantId, String grpcAddress, int grpcPort,
                             String adminServerAddress, int adminServerPort) {
        this.plantInfo = new PlantInfo(plantId, grpcAddress, grpcPort);
        this.adminServerAddress = adminServerAddress;
        this.adminServerPort = adminServerPort;
        this.newElectionQueue = new NewElectionQueue();
        this.buffer = new WindowBuffer();
        logger.info("Creating Thermal Power Plant with ID: " + plantId +
                " on address: " + grpcAddress + ":" + grpcPort);
        this.windowForSliding = new ArrayList<>();
        this.computedAverages = new ArrayList<>();
    }
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        try {
            System.out.println("=== Thermal Power Plant Registration ===");
            System.out.println("Inserisci 'exit' per uscire dal programma");

            String plantId = getPlantIdFromUser(scanner);
            if ("exit".equalsIgnoreCase(plantId)) {
                System.out.println("Uscita dal programma...");
                return;
            }

            int grpcPort = getGrpcPortFromUser(scanner);
            if (grpcPort == -1) {
                System.out.println("Uscita dal programma...");
                return;
            }


            ThermalPowerPlant plant = null;
            boolean success = false;

            while (!success && !plantId.equalsIgnoreCase("exit")) {
                try {
                    plant = new ThermalPowerPlant(
                            plantId,
                            "localhost",
                            grpcPort,
                            "localhost",
                            8080
                    );

                    plant.initialize();
                    success = true;

                } catch (DuplicateIdException e) {
                    System.out.println("\nID pianta  esistente. Inserisci nuovi dati o 'exit' per uscire:");
                    plantId = getPlantIdFromUser(scanner);
                    if ("exit".equalsIgnoreCase(plantId)) {
                        System.out.println("Uscita dal programma...");
                        return;
                    }

                    // Richiedi nuova porta
                    grpcPort = getGrpcPortFromUser(scanner);
                    if (grpcPort == -1) { // L'utente ha inserito "exit"
                        System.out.println("Uscita dal programma...");
                        return;
                    }
                } catch (Exception e) {
                    logger.severe("Errore durante l'inizializzazione: " + e.getMessage());
                    System.out.println("Errore durante l'inizializzazione: " + e.getMessage());
                    break;
                }
            }

            if (success && plant != null) {
                System.out.println("\n=== Registrazione e inizializzazione completata! ===");
                System.out.println("La pianta " + plantId + "attiva e operativa.");
            }

        } catch (Exception e) {
            logger.severe("Errore durante l'esecuzione: " + e.getMessage());
            e.printStackTrace();
        } finally {
            scanner.close();
        }
    }

    /**
     * Ottiene l'ID della pianta dall'utente
     */
    private static String getPlantIdFromUser(Scanner scanner) {
        while (true) {
            System.out.print("Inserisci l'ID della pianta: ");
            String input = scanner.nextLine().trim();

            if ("exit".equalsIgnoreCase(input)) {
                return input;
            }

            if (input.isEmpty()) {
                System.out.println("L'ID della pianta non può essere vuoto. Riprova.");
                continue;
            }

            return input;
        }
    }

    /**
     * Ottiene la porta gRPC dall'utente
     */
    private static int getGrpcPortFromUser(Scanner scanner) {
        while (true) {
            System.out.print("Inserisci la porta gRPC: ");
            String input = scanner.nextLine().trim();
            if ("exit".equalsIgnoreCase(input)) {
                return -1;
            }
            return Integer.parseInt(input);
        }
    }

    /**
     * Inizializza la centrale termica seguendo la sequenza specificata:
     * registrazione al server amministrativo, avvio sensore inquinamento,
     * presentazione alle altre centrali e sottoscrizione MQTT.
     * @throws InterruptedException se l'inizializzazione viene interrotta
     * @throws DuplicateIdException se l'ID della centrale è già esistente in caso richiede id e porta
     */
    public void initialize() throws InterruptedException, DuplicateIdException{
        if (isInitialized) {
            logger.warning("Plant already initialized");
            return;
        }

        try {
            logger.info("Starting initialization of plant: " + plantInfo.getPLANT_ID());

            //  Register with administration server
            if (!registerWithAdminServer()) {
                throw new RuntimeException("Failed to register with administration server");
            }


            initializeNetworkComponents();


            startPollutionSensorAcquisition();

            //  Present to other plants (if any exist)
            if (!isAloneInNetwork) {
                presentToOtherPlants();
            }

            //  Subscribe to MQTT energy production requests
            subscribeToEnergyRequests();

            isInitialized = true;
            logger.info("Plant " + plantInfo.getPLANT_ID() + " successfully initialized");

        }catch (DuplicateIdException e){
            throw e;
        } catch (Exception e) {
            logger.severe("Failed to initialize plant: " + e.getMessage());
        }
    }

    /**
     * Registra la centrale presso il server amministrativo e riceve
     * le informazioni sulla topologia di rete (predecessore e successore).
     * @return true se la registrazione è avvenuta con successo, false altrimenti
     * @throws DuplicateIdException se l'ID della centrale è già registrato
     */
    private boolean registerWithAdminServer() throws DuplicateIdException {
        try {
            logger.info("Registering with administration server at " +
                    adminServerAddress + ":" + adminServerPort);

            adminClient = new Client(adminServerAddress, adminServerPort, plantInfo);
            RegistrationResult registrationResult = adminClient.postRequest();

            if (registrationResult == null) {
                logger.severe("Registration failed - no response from server");
                return false;
            }

            if (!registrationResult.isSuccess()) {
                // ID già esistente
                throw new DuplicateIdException("Plant ID " + plantInfo.getPLANT_ID() + " already exists");
            }

            processRegistrationResult(registrationResult);
            return true;

        } catch (DuplicateIdException e) {
            throw e; // Rilancia l'eccezione
        } catch (Exception e) {
            logger.severe("Error during registration: " + e.getMessage());
            return false;
        }
    }

    /**
     * Elabora il risultato della registrazione e configura la topologia di rete.
     * Determina se la centrale è sola nella rete o ha predecessore/successore.
     * @param result risultato della registrazione contenente info sulla topologia
     */
    private void processRegistrationResult(RegistrationResult result) {
        String successorId = result.getSuccessor().getPLANT_ID();
        String predecessorId = result.getPredecessor().getPLANT_ID();
        String myId = plantInfo.getPLANT_ID();

        // Check if this plant is alone in the network
        if (myId.equals(successorId) && myId.equals(predecessorId)) {
            plantInfo.setPlantAlone();
            isAloneInNetwork = true;
        } else {
            plantInfo.setNextPlant(result.getSuccessor());
            plantInfo.setPrevPlant(result.getPredecessor());
            isAloneInNetwork = false;
            logger.info("Network topology - Successor: " + successorId +
                    ", Predecessor: " + predecessorId);
        }
    }

    /**
     * Inizializza i componenti di comunicazione di rete: client, server gRPC,
     * gestore elezioni e handler MQTT per la comunicazione tra centrali centrale.
     */
    private void initializeNetworkComponents() throws Exception {
        // Initialize plant client for communication with other plants
        plantClient = new PlantClient(plantInfo);

        this.electionManager = new ElectionManager(plantInfo,plantClient);
        ElectionDispatcher dispatcher = new ElectionDispatcher(plantInfo, plantClient, newElectionQueue);

        // Initialize plant service and election manager
        plantService = new PlantServiceImpl(plantInfo);
        plantService.setElectionDispatcher(dispatcher);

        // Initialize and start plant server
        plantServer = new PlantServer(plantInfo, plantService);

        if (!plantServer.startServer()) {
            throw new RuntimeException("Failed to start plant server");
        }

        dispatcher.start(electionManager);

        mqttHandler = new MqttHandler(plantInfo, newElectionQueue);
        logger.info("Init mqtt handler");
        electionManager.setMqttHandler(mqttHandler);
        mqttHandler.init();
    }

    /**
     * Avvia l'acquisizione dati dal sensore di inquinamento creando thread separati
     * per la lettura del buffer e l'invio periodico dei dati via MQTT.
     */
    private void startPollutionSensorAcquisition() {
        try {
            this.sensor = new PollutionSensor(buffer);
            sensor.start();
            bufferReaderThread = new Thread(this::bufferReaderTasks);
            bufferReaderThread.start();

            mqttSenderThread = new Thread(this::mqttSenderTask);
            mqttSenderThread.start();

        } catch (Exception e) {
            logger.severe("Failed to start pollution sensor: " + e.getMessage());
            throw new RuntimeException("Pollution sensor initialization failed", e);
        }
    }

    /**
     * Task eseguito in thread separato per leggere continuamente le misurazioni
     * dal buffer del sensore e inserire i dati che verranno trattati tramite
     * la sliding window per il calcolo delle medie.
     */
    private void bufferReaderTasks(){
        while(sensor.isAlive()){
            try{
                List<Measurement> measurements = buffer.readAllAndClean();
                System.out.println(measurements.size());
                synchronized (slidingWindowLock){
                    windowForSliding.addAll(measurements);

                }
                List<Double> newAverages = processedSlidingWindow();
                synchronized (averagesLock) {
                    computedAverages.addAll(newAverages);

                }

                Thread.sleep(100);
            } catch (InterruptedException e) {
                logger.info("Buffer reader thread interrupted");
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.severe("Error in buffer reader thread: " + e.getMessage());
            }
        }
    }

    /**
     * Task eseguito in thread separato che invia ogni 10 secondi le medie calcolate
     * al server amministrativo tramite MQTT, svuotando la lista delle medie accumulate.
     */
    private void mqttSenderTask() {
        while (sensor.isAlive()) {
            try {
                Thread.sleep(10000);

                List<Double> averagesToSend;
                synchronized (averagesLock) {
                    if (computedAverages.isEmpty()) {
                        logger.info("No averages to send");
                        continue;
                    }

                    // Get all computed averages and clear the list
                    averagesToSend = new ArrayList<>(computedAverages);
                    computedAverages.clear();
                }

                // Send averages to administration server via MQTT
                long timestamp = System.currentTimeMillis();
                mqttHandler.sendPollutionData(plantInfo.getPLANT_ID(), averagesToSend, timestamp);


            } catch (InterruptedException e) {
                logger.info("MQTT sender thread interrupted");
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.severe("Error in MQTT sender thread: " + e.getMessage());
            }
        }
    }

    /**
     * Elabora SlidingWindowe con sovrapposizione del 50% calcolando le medie
     * quando sono disponibili almeno 8 misurazioni, poi scarta le 4 più vecchie.
     * @return lista delle medie calcolate dalle finestre elaborate
     */
    private List<Double> processedSlidingWindow() {
        List<Double> averages = new ArrayList<>();

        synchronized (slidingWindowLock) {

            windowForSliding.sort((m1, m2) -> Long.compare(m1.getTimestamp(), m2.getTimestamp()));


            while (windowForSliding.size() >= SLIDING_WINDOW_SIZE) {

                List<Measurement> currentWindow = windowForSliding.subList(0, SLIDING_WINDOW_SIZE);


                double average = calculateAverage(currentWindow);
                averages.add(average);



                windowForSliding.subList(0, SLIDING_STEP).clear();

            }
        }

        return averages;
    }


    /**
      Calcola la media dei valori CO2 da una lista di misurazioni.
     */
    private double calculateAverage(List<Measurement> measurements) {
        if (measurements.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (Measurement measurement : measurements) {
            sum += measurement.getValue();
        }
        return sum / measurements.size();
    }


    /**
     * Presenta questa centrale a tutte le altre centrali già presenti nella rete
     * tramite comunicazione asincrona per stabilire i collegamenti inter-centrale.
     */
    private void presentToOtherPlants() {
        try {
            plantClient.asyncIntroduceToAll();
        } catch (Exception e) {
            logger.warning("Failed to present to other plants: " + e.getMessage());

        }
    }

    /**
     * Sottoscrive la centrale al topic MQTT per ricevere le richieste di produzione
     * energetica dal sistema di gestione centralizzato.
     */
    private void subscribeToEnergyRequests() {
        try {


            mqttHandler.subscribeToEnergyRequests();


        } catch (Exception e) {
            logger.severe("Failed to subscribe to energy requests: " + e.getMessage());
            throw new RuntimeException("MQTT subscription failed", e);
        }
    }

    public static class DuplicateIdException extends Exception {
        public DuplicateIdException(String message) {
            super(message);
        }
    }
}