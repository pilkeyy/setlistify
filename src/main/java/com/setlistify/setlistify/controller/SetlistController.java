package com.setlistify.setlistify.controller;

import com.setlistify.setlistify.model.dto.SetlistResponseDTO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/setlists")
public class SetlistController {
    @GetMapping("/mock")
    public SetlistResponseDTO getMockSetlist() {
        return new SetlistResponseDTO(
                "Opeth",
                List.of("Windowpane", "Deliverance", "Ghost of Perdition")
        );
    }
}
