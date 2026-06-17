package com.setlistify.setlistify.model.dto;

import java.util.List;

public record SetlistResponse(String artistName, List<String> songTitles) {
}
