package desm.administration.services;

import desm.common.PlantInfo;
import desm.common.RegistrationResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 *
 */
@Service
public class PlantService {

    private final List<PlantInfo> registredPlant = new ArrayList<>();


    public synchronized List<PlantInfo> getAllRegistredPlant() {
        return new ArrayList<>(registredPlant);
    }

    /**
     * Register new plant. If the plant id dosen not already exist it will be addeed to the list and the
     * next plant on the ring it will be returned.
     * Othewise null will be returned.
     * @param plant pianta da aggiungere
     * @return successore
     */
    public synchronized RegistrationResult register(PlantInfo plant) {
        if (alreadyExist(plant.getPLANT_ID())) {
            System.out.println("ERRORE: Pianta con ID " + plant.getPLANT_ID() + " già esistente");
            return new RegistrationResult(null, null, false);
        }
        registredPlant.add(plant);
        System.out.println("Aggiunta pianta con ID : " + plant.getPLANT_ID());

        PlantInfo predecessor = getPredecessor(plant);
        PlantInfo successor = getSuccessor(plant);

        return new RegistrationResult(predecessor, successor, true);
    };

    /**
     * there are no other plants with the same ID
     * @param id
     * @return
     */
    private boolean alreadyExist(String id){
        for (PlantInfo plantInfo : registredPlant){
            if(plantInfo.getPLANT_ID().equals(id)){
                return true;
            }
        }
        return false;
    }

    /**
     * Trova il predecessore di una pianta nell'anello.
     * Se è da sola, il predecessore è se stessa.
     *
     * @param plant la pianta di cui trovare il predecessore
     * @return il predecessore
     */
    private PlantInfo getPredecessor(PlantInfo plant) {
        if (registredPlant.size() == 1) {
            return plant;
        }

        int index = registredPlant.indexOf(plant);

        int predecessorIndex = (index - 1 + registredPlant.size()) % registredPlant.size();

        return registredPlant.get(predecessorIndex);
    }

    /**
     * Trova il successore di una pianta nell'anello.
     * Se è da sola, il successore è se stessa.
     *
     * @param plant la pianta di cui trovare il successore
     * @return il successore
     */
    private PlantInfo getSuccessor(PlantInfo plant) {
        if (registredPlant.size() == 1) {
            return plant;
        }
        int index = registredPlant.indexOf(plant);
        int successorIndex = (index + 1) % registredPlant.size();
        return registredPlant.get(successorIndex);
    }




}
