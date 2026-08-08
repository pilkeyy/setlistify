package com.setlistify.setlistify.model.dto;

import java.util.List;
import java.util.Optional;

public class SetlistFmDTOs {

    public record SetlistResponse(List<ConcertRecord> setlist) {}

    public record ConcertRecord(
            String id,
            String eventDate,
            ArtistRecord artist,
            VenueRecord venue,
            SetsWrapper sets
    ) {}

    public record ArtistRecord(String name) {}

    public record VenueRecord(String name, CityRecord city) {}

    public record CityRecord(String name, CountryRecord country) {}

    public record CountryRecord(String name, String code) {}

    public record SetsWrapper(List<SetRecord> set) {}

    public record SetRecord(List<SongRecord> song) {}

    public record SongRecord(String name) {}

    public record ConcertSummary(
            String id,
            String eventDate,
            String venueName,
            String cityName,
            String countryName
    ) {
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
}
