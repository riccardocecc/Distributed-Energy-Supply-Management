package desm.administration.mqtt;

import desm.administration.services.PollutionService;
import desm.common.Config;
import desm.common.PollutionData;
import org.eclipse.paho.client.mqttv3.*;
import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;

@Component
public class PollutionSubscriber {

    private static final Logger logger = LoggerFactory.getLogger(PollutionSubscriber.class);

    private MqttClient client;
    private final String broker = Config.MQTT_BROKER_URL;
    private final String clientId = MqttClient.generateClientId();
    private final String topic = Config.PLANT_POLLUTION_TOPIC;
    private final int qos = Config.PROVIDER_QOS;
    private final int MAX_RECONNECT_DELAY = 5000;

    private final PollutionService pollutionService;

    public PollutionSubscriber(PollutionService pollutionService) {
        this.pollutionService = pollutionService;
        setupMqttClient();
    }

    private void setupMqttClient() {
        try {
            this.client = new MqttClient(broker, clientId);
            logger.info("MQTT Client created with ID: {}", clientId);
        } catch (MqttException me) {
            logger.error("Failed to create MQTT client", me);
            throw new RuntimeException("Failed to initialize MQTT client", me);
        }
    }

    public void startListening() {
        try {
            if (client == null) {
                setupMqttClient();
            }

            if (!client.isConnected()) {
                connect();
            }

            logger.info("MQTT Client successfully connected and listening to topic: {}", topic);
        } catch (MqttException me) {
            logger.error("Failed to start listening", me);
            throw new RuntimeException("Failed to start MQTT listener", me);
        }
    }

    private void connect() throws MqttException {
        MqttConnectOptions connOpts = new MqttConnectOptions();
        connOpts.setCleanSession(true);
        connOpts.setAutomaticReconnect(true);
        connOpts.setMaxReconnectDelay(MAX_RECONNECT_DELAY);

        client.setCallback(new MqttCallback() {
            @Override
            public void messageArrived(String topic, MqttMessage message) {
                try {
                    String receivedMessage = new String(message.getPayload());
                    logger.debug("Received message on topic", topic);

                    Gson gson = new Gson();

                    PollutionData payload = gson.fromJson(receivedMessage, PollutionData.class);
                    pollutionService.addPayloadFromSubscriber(payload);
                } catch (Exception e) {
                    logger.error("Error processing message: {}", e.getMessage(), e);
                }
            }

            @Override
            public void connectionLost(Throwable cause) {
                logger.warn("Connection lost for client {}. Cause: {}", clientId, cause.getMessage());

            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {

            }
        });

        client.connect(connOpts);
        client.subscribe(topic, qos);
    }

    @PreDestroy
    public void shutdown() {
        try {
            if (client != null && client.isConnected()) {
                client.disconnect();
                client.close();
                logger.info("MQTT Client disconnected and closed");
            }
        } catch (MqttException e) {
            logger.error("Error during MQTT client shutdown", e);
        }
    }

    public boolean isConnected() {
        return client != null && client.isConnected();
    }
}