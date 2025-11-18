package desm.powerplant.pollutionSensor;

import desm.powerplant.pollutionSensor.simulator.Buffer;
import desm.powerplant.pollutionSensor.simulator.Measurement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class WindowBuffer implements Buffer {

    private static final int WINDOW_SIZE = 8;
    private final List<Measurement> buffer;
    private final Object lock = new Object();

    public WindowBuffer() {
        this.buffer = new ArrayList<>();
    }

    @Override
    public void addMeasurement(Measurement m) {
        synchronized (lock) {
            buffer.add(m);
            Collections.sort(buffer);
            lock.notifyAll();
        }
    }

    @Override
    public List<Measurement> readAllAndClean() {
        synchronized (lock) {
            while (buffer.size() < WINDOW_SIZE) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new ArrayList<>();
                }
            }
            List<Measurement> snap = new ArrayList<>(buffer);
            buffer.clear();
            return snap;
        }
    }
}