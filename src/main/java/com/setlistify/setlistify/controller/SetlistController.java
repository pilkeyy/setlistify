package com.setlistify.setlistify.controller;

import com.setlistify.setlistify.client.SetlistFmClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/setlists")
public class SetlistController {
    private final SetlistFmClient setlistFmClient;

    public SetlistController(SetlistFmClient setlistFmClient) {
        this.setlistFmClient = setlistFmClient;
    }

    @GetMapping("/search")
    public String searchSetlists(@RequestParam String artist) {
        return setlistFmClient.fetchRawSetlists(artist);
    }
}
