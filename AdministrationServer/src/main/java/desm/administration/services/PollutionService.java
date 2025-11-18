package desm.administration.services;
import desm.common.PollutionData;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class PollutionService {

    private final List<PollutionData> payloads = new ArrayList<>();
    private final Object lock = new Object();

    public void addPayloadFromSubscriber(PollutionData payload) {
        synchronized (lock) {
            System.out.println("Aggiungendo PollutionData TimeStamp " + payload.getTimestamp());
            payloads.add(payload);
        }
    }

    public List<String> getCurrentPowerPlants() {
        synchronized (lock) {
            return payloads.stream()
                    .map(PollutionData::getPlantId)
                    .distinct()
                    .collect(Collectors.toList());
        }
    }

    public double computeAverageBetween(String time1, String time2) {
        List<PollutionData> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<>(payloads);
        }
        long t1, t2;
        try {
            t1 = Long.parseLong(time1);
            t2 = Long.parseLong(time2);
        } catch (NumberFormatException e) {
            System.err.println("Invalid timestamp format: " + e.getMessage());
            return 0.0;
        }

        // Filtra i dati nel range di timestamp
        List<PollutionData> filteredData = snapshot.stream()
                .filter(data -> isBetween(data.getTimestamp(), t1, t2))
                .collect(Collectors.toList());

        // Se non ci sono dati nel range, restituisce 0.0
        if (filteredData.isEmpty()) {
            return 0.0;
        }

        double totalSum = filteredData.stream()
                .flatMapToDouble(data -> data.getAverages().stream().mapToDouble(Double::doubleValue))
                .sum();

        long totalCount = filteredData.stream()
                .mapToLong(data -> data.getAverages().size())
                .sum();

        return totalCount > 0 ? totalSum / totalCount : 0.0;
    }

    private boolean isBetween(long timestamp, long t1, long t2) {
        return timestamp >= t1 && timestamp <= t2;
    }
}

