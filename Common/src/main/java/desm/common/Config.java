package desm.common;

public class Config {
        public static final String MQTT_BROKER_URL = "tcp://localhost:1883";
        public static final int MIN_ENERGY = 5000;
        public static final int MAX_ENERGY = 15000;
        public static final String ENERGY_REQUEST_TOPIC = "energy/requests";
        public static final String ENERGY_RESPONSE_TOPIC = "energy/responses";

        public static final String PLANT_POLLUTION_TOPIC = "plant/pollution/sensor";

        /**
         * Le richieste di energia devono arrivare alle centrali termiche
         * Nel tuo scenario, se una richiesta arriva due volte, le centrali possono gestirlo (solo una risponderà)
         *  QoS 1 offre garanzia di consegna senza l'overhead eccessivo di QoS 2
         */
        public static final int PROVIDER_QOS = 1;

        public static final int POLLUTION_QOS = 1;

}
