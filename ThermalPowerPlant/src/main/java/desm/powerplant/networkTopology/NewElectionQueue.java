package desm.powerplant.networkTopology;

import desm.common.EnergyRequest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;

/**
 * Utilizzata dal dispatcher e dall'MQTT handler. Quando arriva una richiesta. Viene
 * utilizzata per far partire nuove elezioni.
 *
 */
public class NewElectionQueue{
    private static final Logger logger = Logger.getLogger(NewElectionQueue.class.getName());
    private final List<EnergyRequest> energyRequests = new ArrayList<>();

    public synchronized void put(EnergyRequest newRequest) {
        energyRequests.add(newRequest);
        notify();
    }
    public synchronized void removeByRequestId(String requestId) {
        if (requestId == null) {
            logger.warning("Tentativo di rimozione con requestId null");
        }

        boolean removed = energyRequests.removeIf(request ->
                requestId.equals(request.getRequestId())
        );

        if (removed) {
            logger.info("Rimossa EnergyRequest con ID: " + requestId);
        }
    }

    public synchronized EnergyRequest peek() {
        EnergyRequest message = null;

        while(energyRequests.isEmpty()) {
            try {
                wait();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        EnergyRequest oldest = energyRequests.stream()
                .min(Comparator.comparingLong(EnergyRequest::getTimestamp))
                .orElse(null);

        message = oldest;
        return message;
    }
    public synchronized EnergyRequest take() {
        EnergyRequest message = null;

        while(energyRequests.isEmpty()) {
            try {
                wait();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        EnergyRequest oldest = energyRequests.stream()
                    .min(Comparator.comparingLong(EnergyRequest::getTimestamp))
                    .orElse(null);

        message = oldest;
        energyRequests.remove(oldest);

        return message;
    }

}