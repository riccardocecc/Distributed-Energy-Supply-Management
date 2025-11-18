package desm.common;

public class RegistrationResult {
    private  PlantInfo predecessor;
    private  PlantInfo successor;
    private  boolean success;

    public RegistrationResult(PlantInfo predecessor, PlantInfo successor, boolean success) {
        this.predecessor = predecessor;
        this.successor = successor;
        this.success = success;
    }

    public RegistrationResult(){

    }

    public PlantInfo getPredecessor() { return predecessor; }
    public PlantInfo getSuccessor() { return successor; }
    public boolean isSuccess() { return success; }

}