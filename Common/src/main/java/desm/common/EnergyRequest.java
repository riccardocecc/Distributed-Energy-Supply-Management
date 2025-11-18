package desm.common;

public class EnergyRequest {

    private final String requestId;
    private final long timestamp;
    private final double energyAmount;

    public EnergyRequest(String requestId, long timestamp, double energyAmount) {
        this.requestId = requestId;
        this.timestamp = timestamp;
        this.energyAmount = energyAmount;
    }

    public String getRequestId() {
        return requestId;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public double getEnergyAmount() {
        return energyAmount;
    }

    public String toJson() {
        return String.format(
                "{\"requestId\":\"%s\",\"timestamp\":\"%s\",\"energyAmount\":%d}",
                requestId, timestamp, energyAmount
        );
    }


}
