package desm.powerplant.communication;

import desm.common.Config;
import desm.common.EnergyRequest;
import desm.common.PlantInfo;
import desm.common.PollutionData;
import desm.powerplant.networkTopology.NewElectionQueue;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import com.google.gson.Gson;

import java.util.List;
import java.util.logging.Logger;

public class MqttHandler {
    private static final Logger logger = Logger.getLogger(MqttHandler.class.getName());

    private String broker = "tcp://localhost:1883";

    private PlantInfo plantInfo;
    private MqttClient mqttClient;
    private Gson gson;
    private String requestProviderTopic = Config.ENERGY_REQUEST_TOPIC;

    private String responseProviderTopic = Config.ENERGY_RESPONSE_TOPIC;

    private String pollutionTopic = Config.PLANT_POLLUTION_TOPIC;

    private int providerQos = Config.PROVIDER_QOS;

    private int pollutionQos = Config.POLLUTION_QOS;


    private NewElectionQueue newElectionQueue;




    public MqttHandler(PlantInfo plantInfo, NewElectionQueue newElectionQueue) {
        this.plantInfo = plantInfo;
        this.newElectionQueue = newElectionQueue;
        this.gson = new Gson();
    }



    public void init(){
        try {
            this.mqttClient = new MqttClient(broker, plantInfo.getPLANT_ID(), new MemoryPersistence());


            MqttConnectOptions connOpts = new MqttConnectOptions();
            connOpts.setCleanSession(true);
            connOpts.setAutomaticReconnect(true);
            connOpts.setKeepAliveInterval(60);
            connOpts.setConnectionTimeout(30);


            System.out.println("Connecting to broker: " + broker);
            mqttClient.connect(connOpts);
            System.out.println("Connected to broker");


            mqttClient.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                    logger.warning("Connection to MQTT broker lost: " + cause.getMessage());

                }

                @Override
                public void messageArrived(String topic, MqttMessage message) throws Exception {
                    handleIncomingMessage(topic, message);
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {

                }
            });

        } catch (MqttException e) {
            System.err.println("Error initializing MQTT client: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Si iscrive al topic per ricevere le richieste di energia.
     * Utilizza un wildcard (+) per ascoltare tutte le richieste energetiche su diversi subtopic.
     *
     */
    public void subscribeToEnergyRequests() {
        try {
            String allRquestTopic = requestProviderTopic + "/+";
            mqttClient.subscribe(allRquestTopic, providerQos);
        } catch (MqttException e) {
            logger.severe("[SUBSCRIBE TO ENERGY REQUEST] Failed to subscribe to energy requests: " + e.getMessage());
            throw new RuntimeException("Failed to subscribe to MQTT topic", e);
        }
    }

    /**
     * Invia una risposta vuota al provider per confermare che la pianta ha vinto e ha gestito l'elezione.
     * Pubblica un messaggio vuoto retained su un topic specifico costruito con request id e plant id
     *
     * @param requestId ID univoco della richiesta energetica
     * @param plantId ID della centrale elettrica che risponde
     */
    public void responseToProvider(String requestId, String plantId){
        try{
            String specificTopicToRespond = responseProviderTopic+"/"+requestId+"/"+plantId;
            MqttMessage response = new MqttMessage(new byte[0]);
            response.setRetained(true);
            mqttClient.publish(specificTopicToRespond, response);
        } catch (MqttException e) {
            logger.severe("[RESPONSE TO PROVIDER] Failed to response to energy requests: " + e.getMessage());
            throw new RuntimeException("Failed to subscribe to MQTT topic", e);
        }
    }

    /**
     * Gestisce i messaggi MQTT in arrivo.
     * Se il messaggio è vuoto, significa che una richiesta è
     * stata gestita dalla una painta quindi estrae il requestId dal topic
     * e lo rimuove dalla coda delle elezioni.
     * Se il messaggio contiene dati, deserializza la richiesta energetica e la aggiunge alla coda.
     *
     * @param topic Topic MQTT da cui proviene il messaggio
     * @param message Messaggio MQTT ricevuto
     */
    private void handleIncomingMessage(String topic, MqttMessage message) {
        try {
            String payload = new String(message.getPayload());
            if (payload.trim().isEmpty() || message.getPayload().length == 0) {
                String[] parts = topic.split("/");
                if (parts.length >= 3) {
                    String requestId = parts[2]; // UUID della richiesta
                    newElectionQueue.removeByRequestId(requestId);
                } else {
                    logger.warning("Malformed topic, cannot extract request ID: " + topic);
                }

            }else{
                EnergyRequest energyRequest = gson.fromJson(payload, EnergyRequest.class);
                newElectionQueue.put(energyRequest);
            }
        } catch (Exception e) {
            logger.severe("Error handling incoming message: " + e.getMessage());
            e.printStackTrace(); // Aggiungi stack trace completo
        }
    }
    /**
     * Invia i dati di inquinamento della centrale elettrica.
     * Crea un oggetto PollutionData con i valori medi delle emissioni e lo pubblica sul topic dedicato.
     *
     * @param plantId ID della centrale elettrica
     * @param averagesToSend Lista dei valori medi di inquinamento da inviare
     * @param timestamp Timestamp dei dati di inquinamento
     */
    public void sendPollutionData(String plantId,  List<Double> averagesToSend, long timestamp) {

        try {

            PollutionData pollutionData = new PollutionData(plantId, averagesToSend, timestamp);

            String jsonPayload = gson.toJson(pollutionData);


            MqttMessage message = new MqttMessage(jsonPayload.getBytes());
            message.setQos(pollutionQos);
            message.setRetained(false);

            mqttClient.publish(pollutionTopic, message);

        } catch (MqttException e) {
            logger.severe("[SEND POLLUTION DATA] Failed to send pollution data: " + e.getMessage());
            throw new RuntimeException("Failed to publish pollution data to MQTT topic", e);
        } catch (Exception e) {
            logger.severe("[SEND POLLUTION DATA] Unexpected error: " + e.getMessage());
            e.printStackTrace();
        }
    }



    public void disconnect() {
        try {
            if (mqttClient != null && mqttClient.isConnected()) {
                mqttClient.disconnect();
                mqttClient.close();
            }
        } catch (MqttException e) {
            logger.severe("Error disconnecting MQTT client: " + e.getMessage());
        }
    }
}