package desm.common;

import java.util.List;

public class PollutionData {
    private String plantId;
    private List<Double> averages;
    private long timestamp;

    public PollutionData(String plantId, List<Double> averages, long timestamp) {
        this.plantId = plantId;
        this.averages = averages;
        this.timestamp = timestamp;
    }


    public String getPlantId() { return plantId; }
    public List<Double> getAverages() { return averages; }
    public long getTimestamp() { return timestamp; }
}
