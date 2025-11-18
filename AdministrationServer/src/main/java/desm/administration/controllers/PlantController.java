package desm.administration.controllers;

import desm.administration.services.PlantService;
import desm.common.PlantInfo;
import desm.common.RegistrationResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/plant")
public class PlantController {

    private final PlantService plantService;

    @Autowired
    public PlantController(PlantService plantService){
        this.plantService = plantService;
    }

    @PostMapping("/register")
    public ResponseEntity<RegistrationResult> registerPlant(@RequestBody PlantInfo plant) {
        RegistrationResult registrationResult = plantService.register(plant);

        if(registrationResult!=null){
            return ResponseEntity.ok(registrationResult);
        }
        return ResponseEntity.notFound().build();
    }


}
