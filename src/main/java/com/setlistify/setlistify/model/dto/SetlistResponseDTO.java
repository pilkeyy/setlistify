package com.setlistify.setlistify.model.dto;

import java.util.List;

public record SetlistResponseDTO(String artistName, List<String> songTitles) {
}
