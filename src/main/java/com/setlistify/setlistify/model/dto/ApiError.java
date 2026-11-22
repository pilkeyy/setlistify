package com.setlistify.setlistify.model.dto;

public record ApiError(int status, String error, String message) {
}
