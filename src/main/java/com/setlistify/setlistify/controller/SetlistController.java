package com.setlistify.setlistify.controller;

import com.setlistify.setlistify.client.SetlistFmClient;
import com.setlistify.setlistify.model.dto.SetlistResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/setlists")
public class SetlistController {
    private final SetlistFmClient setlistFmClient;

    public SetlistController(SetlistFmClient setlistFmClient) {
        this.setlistFmClient = setlistFmClient;
    }

    @GetMapping("/search")
    public SetlistResponse searchSetlists(@RequestParam String artist) {
        return setlistFmClient.fetchRawSetlists(artist);
    }
}
