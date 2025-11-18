package desm.administration.controllers;

import com.google.api.PageOrBuilder;
import desm.administration.services.PlantService;
import desm.administration.services.PollutionService;
import desm.common.PlantInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/client")
public class ClientController {

    private final PollutionService pollutionService;
    private final PlantService plantService;

    @Autowired
    public ClientController(PollutionService pollutionService, PlantService plantService){
        this.pollutionService = pollutionService;
        this.plantService = plantService;
    }

    @GetMapping("/allPlants")
    public ResponseEntity<List<PlantInfo>> getAllPlantsInTheNetwork(){
        return ResponseEntity.ok(plantService.getAllRegistredPlant());
    }


    @GetMapping("/average/{t1}/{t2}")
    public Double getAverage(@PathVariable String t1,@PathVariable String t2){
        return pollutionService.computeAverageBetween(t1,t2);
    }


}
