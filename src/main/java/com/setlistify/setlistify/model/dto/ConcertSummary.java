package com.setlistify.setlistify.model.dto;

import java.util.Optional;

public record ConcertSummary(String id, String eventDate, String venueName, String cityName, String countryName) {

    public static ConcertSummary from(ConcertRecord concert) {
        String venue = Optional.ofNullable(concert.venue())
                .map(VenueRecord::name)
                .orElse("Unknown Venue");

        String city = Optional.ofNullable(concert.venue())
                .map(VenueRecord::city)
                .map(CityRecord::name)
                .orElse("Unknown City");

        String country = Optional.ofNullable(concert.venue())
                .map(VenueRecord::city)
                .map(CityRecord::country)
                .map(CountryRecord::name)
                .orElse("Unknown Country");

        return new ConcertSummary(
                concert.id(),
                concert.eventDate(),
                venue,
                city,
                country
        );
    }
}
