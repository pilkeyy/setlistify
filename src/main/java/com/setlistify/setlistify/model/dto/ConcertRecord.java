package com.setlistify.setlistify.model.dto;

public record ConcertRecord(String id, String eventDate,ArtistRecord artist, VenueRecord venue,SetsWrapper sets) {
}
