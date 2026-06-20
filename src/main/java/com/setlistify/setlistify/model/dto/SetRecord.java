package com.setlistify.setlistify.model.dto;

import java.util.List;

public record SetRecord(String name, List<SongRecord> song) {
}
