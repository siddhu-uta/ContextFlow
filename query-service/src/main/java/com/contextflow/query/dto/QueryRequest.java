package com.contextflow.query.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record QueryRequest(

        @NotBlank(message = "Question is required")
        @Size(max = 2000, message = "Question must be under 2000 characters")
        String question
) {}
