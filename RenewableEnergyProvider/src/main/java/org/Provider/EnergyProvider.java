package org.Provider;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;
import desm.common.Config;
import desm.common.EnergyRequest;
import org.eclipse.paho.client.mqttv3.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class EnergyProvider implements MqttCallback {
    private static final Logger LOGGER = Logger.getLogger(EnergyProvider.class.getName());
    private final int MIN_ENERGY = Config.MIN_ENERGY;
    private final int MAX_ENERGY = Config.MAX_ENERGY;
    private MqttClient client;
    private final String broker = Config.MQTT_BROKER_URL;
    private final String clientId = MqttClient.generateClientId();
    private final String topic = Config.ENERGY_REQUEST_TOPIC;
    private final String responseTopic = Config.ENERGY_RESPONSE_TOPIC;
    private final int qos = Config.PROVIDER_QOS;
    private Random random = new Random();
    private DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // Gson instance for JSON serialization/deserialization
    private final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    private Map<String, EnergyRequest> pendingRequests = new HashMap<>();
    private Object pendingLock = new Object();

    public EnergyProvider(){
        try {
            client = new MqttClient(broker, clientId);
        }catch (MqttException me ) {
            System.out.println("reason " + me.getReasonCode());
            System.out.println("msg " + me.getMessage());
            System.out.println("loc " + me.getLocalizedMessage());
            System.out.println("cause " + me.getCause());
            System.out.println("excep " + me);
            me.printStackTrace();
        }
    }

    public void run(){
        try{
            MqttConnectOptions connOpts = new MqttConnectOptions();
            connOpts.setCleanSession(true);
            connOpts.setAutomaticReconnect(true);
            client.setCallback(this);
            client.connect(connOpts);
            System.out.println(clientId + "Energy provider Connected:");
            client.subscribe(responseTopic + "/+/+", qos);

            System.out.println(clientId + " Energy provider Connected and subscribed to responses");
            // Pulisce tutto quello che trova
            clearAllRetainedRequests();


            Thread.sleep(5000);
            while (true){
                try {
                    EnergyRequest request = createEnergyRequest();

                    publishEnergyRequest(request);
                    Thread.sleep(10000);

                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }catch (MqttException me ) {
            System.out.println("reason " + me.getReasonCode());
            System.out.println("msg " + me.getMessage());
            System.out.println("loc " + me.getLocalizedMessage());
            System.out.println("cause " + me.getCause());
            System.out.println("excep " + me);
            me.printStackTrace();

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Crea una nuova richiesta energetica con ID univoco, timestamp corrente
     * e quantità casuale di energia compresa tra 5000 e 15000 kWh.
     * @return oggetto EnergyRequest con parametri generati casualmente
     */
    private EnergyRequest createEnergyRequest() {
        double energyAmount = Math.round((5000 + random.nextDouble() * (15000 - 5000)) * 100.0) / 100.0;
        long timestamp = Instant.now().toEpochMilli();
        String requestId = UUID.randomUUID().toString();

        return new EnergyRequest(requestId, timestamp, energyAmount);
    }

    /**
     * Pubblica una richiesta energetica sul topic MQTT specifico con messaggio retained,
     * aggiunge la richiesta alla lista di quelle in attesa di risposta.
     * @param request richiesta energetica da pubblicare
     */
    private void publishEnergyRequest(EnergyRequest request) throws MqttException {
        String requestId = request.getRequestId();
        // Use Gson to serialize the EnergyRequest
        String messageJson = gson.toJson(request);
        String specificTopic = topic + "/" + requestId;

        LOGGER.info("\033[96m=== PUBLISHING REQUEST ===\033[0m");
        LOGGER.info("\033[96mMessage JSON: " + messageJson+"\033[0m");
        LOGGER.info("\033[95mPending request:" + pendingRequests.size()+ "\033[0m");
        MqttMessage mqttMessage = new MqttMessage(messageJson.getBytes());
        mqttMessage.setQos(qos);
        mqttMessage.setRetained(true);

        synchronized (pendingLock) {
            pendingRequests.put(requestId, request);
        }

        client.publish(specificTopic, mqttMessage);
    }

    @Override
    public void connectionLost(Throwable throwable) {
        System.out.println("Connessione MQTT persa: " + throwable.getMessage());
    }

    /**
     * Callback invocato alla ricezione di messaggi MQTT. Gestisce le risposte
     * delle centrali alle richieste energetiche, rimuove le richieste completate
     * e pulisce i messaggi retained corrispondenti.
     * @param s topic del messaggio ricevuto
     * @param mqttMessage messaggio MQTT ricevuto
     */
    @Override
    public void messageArrived(String s, MqttMessage mqttMessage) throws Exception {
        try {

            String[] topicParts = s.split("/");
            if (topicParts.length >= 4 && topicParts[1].equals("responses")) {
                String requestId = topicParts[2];
                String plantId = topicParts[3];

                synchronized (pendingLock) {
                    if (pendingRequests.containsKey(requestId)) {
                        String responseJson = new String(mqttMessage.getPayload());

                        try {
                            LOGGER.info("\033[95mRicevuta risposta per richiesta: " + requestId
                                    + " dalla pianta: " + plantId + "\033[0m");

                        } catch (JsonSyntaxException e) {
                            LOGGER.warning("Errore parsing JSON response: " + e.getMessage());
                            LOGGER.info("Raw response: " + responseJson);
                        }

                        pendingRequests.remove(requestId);

                        // Pulizia messaggio retained sul topic della richiesta
                        MqttMessage clearMessage = new MqttMessage(new byte[0]);
                        clearMessage.setRetained(true);
                        clearMessage.setQos(qos);
                        String topicToClear = topic + "/" + requestId;
                        client.publish(topicToClear, clearMessage);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.severe("Errore nel processare il messaggio: " + e.getMessage());
        }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken iMqttDeliveryToken) {

    }

    /**
     * Pulisce tutti i messaggi retained presenti sui topic delle richieste energetiche
     * creando un client temporaneo per individuarli e poi cancellandoli uno per uno.
     * usato per sviluppo e debug. Ad ogni avvio cancella quelle dell'avvio precedente
     */
    private void clearAllRetainedRequests() throws MqttException, InterruptedException {


        MqttMessage clearMessage = new MqttMessage(new byte[0]);
        clearMessage.setRetained(true);
        clearMessage.setQos(qos);


        String tempClientId = "cleaner-" + System.currentTimeMillis();
        MqttClient tempClient = new MqttClient(broker, tempClientId);

        MqttConnectOptions tempOpts = new MqttConnectOptions();
        tempOpts.setCleanSession(true);
        tempClient.connect(tempOpts);

        final List<String> retainedTopics = new ArrayList<>();

        tempClient.setCallback(new MqttCallback() {
            @Override
            public void connectionLost(Throwable throwable) {}

            @Override
            public void messageArrived(String topic, MqttMessage message) throws Exception {
                if (message.isRetained()) {
                    retainedTopics.add(topic);
                    LOGGER.info("Found retained message on: " + topic);
                }
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken iMqttDeliveryToken) {}
        });

        // Sottoscrizione a tutti i topic energy/requests per trovare messaggi retained
        tempClient.subscribe("energy/requests/+", 0);


        Thread.sleep(2000);

        tempClient.disconnect();
        tempClient.close();


        LOGGER.info("Found " + retainedTopics.size() + " retained messages to clear");

        for (String topicToClear : retainedTopics) {
            client.publish(topicToClear, clearMessage);
            LOGGER.info("Cleared: " + topicToClear);
        }

        LOGGER.info("Cleanup completed!");
    }
}