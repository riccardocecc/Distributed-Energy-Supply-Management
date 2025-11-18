package desm.administration.mqtt;
import desm.administration.mqtt.PollutionSubscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;

@Configuration
public class MqttConfig {

    private static final Logger logger = LoggerFactory.getLogger(MqttConfig.class);

    private final PollutionSubscriber pollutionSubscriber;

    @Autowired
    public MqttConfig(PollutionSubscriber pollutionSubscriber) {
        this.pollutionSubscriber = pollutionSubscriber;
    }

    @EventListener
    public void onApplicationEvent(ContextRefreshedEvent event) {
        logger.info("Starting MQTT subscriber");
        try {
            pollutionSubscriber.startListening();
        } catch (Exception e) {
            logger.error("Failed to start MQTT subscriber", e);
        }
    }
}
